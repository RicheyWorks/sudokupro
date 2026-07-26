package com.xai.sudokupro.service;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.util.Constants;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cosmic observer of SudokuPro's metric universe.
 * Tracks users, games, chaos, and drip with galactic precision—Micrometer-powered for App Store telemetry.
 */
@Service
public class MetricsScheduler {
    private static final Logger logger = LoggerFactory.getLogger(MetricsScheduler.class);
    private static final long METRICS_INTERVAL_MS = 30_000; // 30 seconds
    private static final long DAILY_INTERVAL_MS = 24 * 60 * 60 * 1000; // 24 hours
    private static final long PROMETHEUS_DUMP_INTERVAL_MS = 600_000; // 10 minutes
    private static final int RETRY_ATTEMPTS = 3; // Retry attempts for metric updates
    private static final String[] THEMES = {"retro-pixel", "manga-mode", "astral-nebula", "default"};
    private static final int[] TIER_THRESHOLDS = {1000, 5000, 10000, 25000}; // Bronze, Silver, Gold, Cosmic
    private static final String[] TIER_NAMES = {"Bronze", "Silver", "Gold", "Cosmic"};
    private static final Tags GLOBAL_TAGS = Tags.of("app", "SudokuPro");
    private static final double SUSPICION_THRESHOLD = 75.0;

    private final MeterRegistry meterRegistry;
    private final UserRepository userRepository;
    private final GameRepository gameRepository;
    private final AnalyticsService analyticsService;
    private final AntiCheatEngine antiCheatEngine;
    private final LeaderboardService leaderboardService;

    private final AtomicBoolean isRunning = new AtomicBoolean(false); // Prevent overlapping runs
    private final DistributionSummary solveTimeHistogram;
    private final DistributionSummary suspicionRateHistogram;
    private final Map<String, AtomicLong> tierGauges = new HashMap<>();
    private final AtomicLong totalUsersGauge = new AtomicLong(0);
    private final AtomicLong totalGemsGauge = new AtomicLong(0);
    private final AtomicLong activeGamesGauge = new AtomicLong(0);
    private final AtomicLong suspiciousPlayersGauge = new AtomicLong(0);
    // Bug fix: these three were AtomicLong fed through a (long) cast even though they are
    // published as double gauges named "...average". A population averaging 0.9 cosmic drip
    // was reported as 0, and a 2/3 duel win rate as 66 instead of 66.67 — the metrics were
    // wrong by up to 100% for small averages. AtomicReference<Double> keeps the fraction.
    private final AtomicReference<Double> cosmicDripGauge = new AtomicReference<>(0.0);
    private final AtomicReference<Double> duelWinRateGauge = new AtomicReference<>(0.0);
    private final AtomicLong dailyActiveGauge = new AtomicLong(0);
    private final AtomicLong activeUsersGauge = new AtomicLong(0);
    private final AtomicReference<Double> solveTimeGauge = new AtomicReference<>(0.0);
    private final Map<String, AtomicLong> themePointsGauges = new HashMap<>();

    @Autowired
    public MetricsScheduler(MeterRegistry meterRegistry, UserRepository userRepository, 
                            GameRepository gameRepository, AnalyticsService analyticsService, 
                            AntiCheatEngine antiCheatEngine, LeaderboardService leaderboardService) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "MeterRegistry cannot be null");
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository cannot be null");
        this.gameRepository = Objects.requireNonNull(gameRepository, "GameRepository cannot be null");
        this.analyticsService = Objects.requireNonNull(analyticsService, "AnalyticsService cannot be null");
        this.antiCheatEngine = Objects.requireNonNull(antiCheatEngine, "AntiCheatEngine cannot be null");
        this.leaderboardService = Objects.requireNonNull(leaderboardService, "LeaderboardService cannot be null");

        // Initialize histograms
        this.solveTimeHistogram = meterRegistry.summary("sudokupro.solve.time.buckets", GLOBAL_TAGS);
        this.suspicionRateHistogram = meterRegistry.summary("sudokupro.suspicious.rate", GLOBAL_TAGS);

        // Initialize gauges
        initializeGauges();

        logger.info("MetricsScheduler initialized with cosmic dependencies and gauges");
    }

    private void initializeGauges() {
        for (String tier : TIER_NAMES) {
            tierGauges.put(tier, new AtomicLong(0));
            meterRegistry.gauge("sudokupro.tiers." + tier.toLowerCase(), GLOBAL_TAGS, tierGauges.get(tier), AtomicLong::get);
        }
        for (String theme : THEMES) {
            // Bug fix: "sudokupro.active.games.by.theme" used to be registered here. It could
            // only ever read 0 — see reportUserMetrics — and there is no honest way to fill
            // it, so it is no longer published. The real figure is sudokupro.active.games.
            themePointsGauges.put(theme, new AtomicLong(0));
            meterRegistry.gauge("sudokupro.points.by.theme", Tags.concat(GLOBAL_TAGS, Tags.of("theme", theme)), 
                themePointsGauges.get(theme), AtomicLong::get);
        }
        meterRegistry.gauge("sudokupro.total.users", GLOBAL_TAGS, totalUsersGauge, AtomicLong::get);
        meterRegistry.gauge("sudokupro.total.gems", GLOBAL_TAGS, totalGemsGauge, AtomicLong::get);
        meterRegistry.gauge("sudokupro.active.games", GLOBAL_TAGS, activeGamesGauge, AtomicLong::get);
        meterRegistry.gauge("sudokupro.suspicious.players", GLOBAL_TAGS, suspiciousPlayersGauge, AtomicLong::get);
        meterRegistry.gauge("sudokupro.cosmic.drip.average", GLOBAL_TAGS, cosmicDripGauge, r -> r.get());
        meterRegistry.gauge("sudokupro.duel.win.rate.average", GLOBAL_TAGS, duelWinRateGauge, r -> r.get());
        meterRegistry.gauge("sudokupro.daily.active.users", GLOBAL_TAGS, dailyActiveGauge, AtomicLong::get);
        meterRegistry.gauge("sudokupro.active.users",
            Tags.concat(GLOBAL_TAGS, Tags.of("period", "24h")), activeUsersGauge, AtomicLong::get);
        meterRegistry.gauge("sudokupro.solve.time.average", GLOBAL_TAGS, solveTimeGauge, r -> r.get());
    }

    @Scheduled(fixedRate = METRICS_INTERVAL_MS)
    @Retryable(maxAttempts = RETRY_ATTEMPTS, backoff = @Backoff(delay = 5000))
    public void reportUserMetrics() {
        if (!isRunning.compareAndSet(false, true)) {
            logger.debug("Skipping metrics update—previous run still in progress");
            return;
        }
        MDC.put("thread", "metrics");
        logger.info("Updating cosmic user metrics...");

        try {
            // Active Users (last 24 hours)
            long activeUsers = userRepository.countActiveUsersSince(LocalDateTime.now().minusDays(1));
            // Gauge, not counter. This was a Micrometer Counter incremented BY the
            // absolute head-count each cycle, so a steady 40 active users reported 40,
            // then 80, then 120 — the series measured how long the pod had been up, and
            // any rate() over it was pure noise. "Users active in the last 24h" is a
            // level, and a level belongs in a gauge.
            activeUsersGauge.set(activeUsers);
            logger.debug("Reported active users: {}", activeUsers);

            // Total Gems in System — aggregate query avoids loading all rows into heap
            long totalGems = userRepository.getTotalGems();
            totalGemsGauge.set(totalGems);
            logger.debug("Reported total gems: {}", totalGems);

            // Active Games (last hour)
            long activeGames = gameRepository.countActiveUnfinishedGames(LocalDateTime.now().minusHours(1));
            activeGamesGauge.set(activeGames);
            logger.debug("Reported active games: {}", activeGames);

            // Chaos Mode Activations
            meterRegistry.counter("sudokupro.chaos.mode.activations", GLOBAL_TAGS).increment(0); // Reset if no new triggers
            logger.trace("Chaos mode activations reset");

            // Cosmic Drip Distribution
            double avgDrip = analyticsService.getAverageCosmicDripActiveUsers(LocalDateTime.now().minusDays(1));
            cosmicDripGauge.set(avgDrip);
            logger.debug("Reported average cosmic drip: {}", avgDrip);

            // Duel Win Rate
            Map<String, Double> winRates = analyticsService.getPlayerWinRates();
            double avgWinRate = winRates.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            duelWinRateGauge.set(avgWinRate * 100); // Store as percentage
            logger.debug("Reported average duel win rate: {}", avgWinRate);

            // Suspicious Players
            long suspiciousCount = antiCheatEngine.getCheatSuspicionScores().entrySet().stream()
                .filter(e -> e.getValue() > SUSPICION_THRESHOLD)
                .count();
            suspiciousPlayersGauge.set(suspiciousCount);
            suspicionRateHistogram.record(suspiciousCount);
            reportSuspicionBuckets(suspiciousCount);
            logger.debug("Reported suspicious players: {}", suspiciousCount);

            // Bug fix: an "active games by theme" block used to sit here. It called
            // calculateActiveGamesByTheme(List.of()) — a hardcoded empty list — every 30
            // seconds, so all four sudokupro.active.games.by.theme{theme=...} series read 0
            // for the life of the process. The comment claimed the gauges were "updated in
            // reportDailyMetrics", but that method only writes sudokupro.points.by.theme;
            // nothing anywhere ever wrote a non-zero value.
            //
            // It could not have been fixed by passing real rows either: SudokuBoard has no
            // theme attribute, and the getBoardTheme() helper was a placeholder returning the
            // literal "default", so three of the four series would still have been flat zero
            // and the fourth would merely have duplicated sudokupro.active.games.
            //
            // A gauge that always reads 0 is worse than no gauge: a dashboard panel built on
            // it looks identical during an outage and during normal operation, so it
            // positively argues that a broken system is healthy. Rather than publish a
            // dimension the data model cannot support, the series is removed; the honest
            // total is already published above as sudokupro.active.games. Restoring a
            // per-theme breakdown needs a theme column on SudokuBoard first.

        } catch (Exception e) {
            logger.error("Failed to update cosmic user metrics: {}", e.getMessage(), e);
            throw e; // Trigger retry
        } finally {
            isRunning.set(false);
            MDC.clear();
        }
    }

    @Scheduled(fixedRate = DAILY_INTERVAL_MS)
    @Retryable(maxAttempts = RETRY_ATTEMPTS, backoff = @Backoff(delay = 5000))
    public void reportDailyMetrics() {
        MDC.put("thread", "daily-metrics");
        logger.info("Updating cosmic daily metrics...");

        try {
            // Total Users
            long totalUsers = userRepository.count();
            totalUsersGauge.set(totalUsers);
            logger.debug("Reported total users: {}", totalUsers);

            // Daily Active Users
            long dailyActive = userRepository.countActiveUsersSince(LocalDateTime.now().minusDays(1));
            dailyActiveGauge.set(dailyActive);
            logger.debug("Reported daily active users: {}", dailyActive);

            // Average Solve Time with Buckets
            double avgSolveTime = analyticsService.getAverageSolveTime();
            solveTimeGauge.set(avgSolveTime);
            solveTimeHistogram.record(avgSolveTime);
            reportSolveTimeBuckets(avgSolveTime);
            logger.debug("Reported average solve time: {}s", avgSolveTime);

            // Total Points by Theme — one aggregate query per theme instead of full table load
            for (String theme : THEMES) {
                long themePoints = userRepository.getTotalPointsByTheme(theme);
                themePointsGauges.get(theme).set(themePoints);
                logger.debug("Reported total points for theme {}: {}", theme, themePoints);
            }

            // Users by Leaderboard Tier — aggregate COUNT queries, no full table scan
            reportUsersByTier();
            logger.debug("Reported users by leaderboard tier");

            // Suspicious Players (daily reset)
            long suspiciousCount = antiCheatEngine.getCheatSuspicionScores().entrySet().stream()
                .filter(e -> e.getValue() > SUSPICION_THRESHOLD)
                .count();
            suspicionRateHistogram.record(suspiciousCount);
            reportSuspicionBuckets(suspiciousCount);
            logger.debug("Reported daily suspicious players: {}", suspiciousCount);

        } catch (Exception e) {
            logger.error("Failed to update cosmic daily metrics: {}", e.getMessage(), e);
            throw e; // Trigger retry
        } finally {
            MDC.clear();
        }
    }

    @Scheduled(fixedRate = PROMETHEUS_DUMP_INTERVAL_MS)
    public void logAllGaugesToConsole() {
        MDC.put("thread", "prometheus-dump");
        logger.info("Dumping all cosmic gauges to console for debugging...");

        try {
            logger.info("Total Users: {}", totalUsersGauge.get());
            logger.info("Total Gems: {}", totalGemsGauge.get());
            logger.info("Active Games: {}", activeGamesGauge.get());
            logger.info("Suspicious Players: {}", suspiciousPlayersGauge.get());
            logger.info("Average Cosmic Drip: {}", cosmicDripGauge.get());
            logger.info("Average Duel Win Rate: {}%", duelWinRateGauge.get());
            logger.info("Daily Active Users: {}", dailyActiveGauge.get());
            logger.info("Average Solve Time: {}s", solveTimeGauge.get());
            tierGauges.forEach((tier, gauge) -> logger.info("Tier {} Users: {}", tier, gauge.get()));
            themePointsGauges.forEach((theme, gauge) -> logger.info("Points for Theme {}: {}", theme, gauge.get()));
            logger.info("Cosmic gauge dump complete");
        } catch (Exception e) {
            logger.error("Failed to dump cosmic gauges: {}", e.getMessage(), e);
        } finally {
            MDC.clear();
        }
    }

    public void triggerChaosMode(String playerId) {
        validatePlayerId(playerId);
        // Bug fix: this counter used to carry a "player" tag holding the raw player id. Every
        // distinct player minted a new Micrometer meter that the registry retains for the life
        // of the process — unbounded heap growth here and a cardinality explosion in whatever
        // scrapes it. The player id belongs in a log line, not in a metric tag.
        meterRegistry.counter("sudokupro.chaos.mode.activations", GLOBAL_TAGS).increment();
        logger.info("Chaos mode triggered by player {}—cosmic metrics updated", playerId);
    }

    public void recordGameCompletion(String gameId, double solveTimeSeconds) {
        validateGameId(gameId);
        meterRegistry.counter("sudokupro.games.completed", GLOBAL_TAGS).increment();
        meterRegistry.timer("sudokupro.game.solve.time", GLOBAL_TAGS).record((long) solveTimeSeconds, TimeUnit.SECONDS);
        solveTimeHistogram.record(solveTimeSeconds);
        reportSolveTimeBuckets(solveTimeSeconds);
        logger.debug("Game {} completed in {} seconds—metrics recorded", gameId, solveTimeSeconds);
    }

    public void recordDuelOutcome(String playerId, boolean won) {
        validatePlayerId(playerId);
        LeaderboardService.LeaderboardSnapshot rank = null;
        try {
            rank = leaderboardService.getPlayerRank(Long.parseLong(playerId));
        } catch (NumberFormatException e) {
            logger.debug("Cannot look up rank for non-numeric playerId '{}', defaulting to Unranked", playerId);
        } catch (RuntimeException e) {
            // Bug fix: only NumberFormatException was handled. LeaderboardService.getPlayerRank
            // wraps every repository failure in a RuntimeException, so a database blip escaped
            // this metrics sink and failed the duel flow that was merely trying to record a
            // result. Telemetry must never break the caller.
            logger.warn("Rank lookup failed for player {} while recording a duel outcome; tagging Unranked: {}",
                playerId, e.getMessage());
        }
        String tier = rank != null ? rank.tier() : "Unranked";
        String outcome = won ? "win" : "loss";
        // Bug fix: the "player" tag made this counter unbounded in cardinality (see
        // triggerChaosMode). outcome x tier is a bounded 2 x 5 tag space.
        meterRegistry.counter("sudokupro.duels.by.tier",
            Tags.concat(GLOBAL_TAGS, Tags.of("outcome", outcome, "tier", tier))).increment();
        logger.debug("Duel outcome recorded for player {}: {} (tier: {})", playerId, outcome, tier);
    }

    @PreDestroy
    public void shutdown() {
        logger.info("MetricsScheduler shutting down—cosmic metrics preserved");
    }

    /**
     * Reports user counts by tier in Grafana/Prometheus as:
     * - sudokupro.tiers.bronze
     * - sudokupro.tiers.silver
     * - sudokupro.tiers.gold
     * - sudokupro.tiers.cosmic
     */
    /** Uses aggregate COUNT queries — no full table load required. */
    private void reportUsersByTier() {
        long bronze = userRepository.countUsersInPointsRange(TIER_THRESHOLDS[0], TIER_THRESHOLDS[1]);
        long silver = userRepository.countUsersInPointsRange(TIER_THRESHOLDS[1], TIER_THRESHOLDS[2]);
        long gold   = userRepository.countUsersInPointsRange(TIER_THRESHOLDS[2], TIER_THRESHOLDS[3]);
        long cosmic = userRepository.countUsersWithMinPoints(TIER_THRESHOLDS[3]);

        tierGauges.get("Bronze").set(bronze);
        tierGauges.get("Silver").set(silver);
        tierGauges.get("Gold").set(gold);
        tierGauges.get("Cosmic").set(cosmic);
    }

    /**
     * Reports solve time buckets in Grafana/Prometheus as:
     * - sudokupro.solve.bucket.0_30
     * - sudokupro.solve.bucket.30_60
     * - sudokupro.solve.bucket.60_120
     * - sudokupro.solve.bucket.120_300
     * - sudokupro.solve.bucket.300_plus
     */
    private void reportSolveTimeBuckets(double solveTimeSeconds) {
        if (solveTimeSeconds <= 30) {
            meterRegistry.counter("sudokupro.solve.bucket.0_30", GLOBAL_TAGS).increment();
        } else if (solveTimeSeconds <= 60) {
            meterRegistry.counter("sudokupro.solve.bucket.30_60", GLOBAL_TAGS).increment();
        } else if (solveTimeSeconds <= 120) {
            meterRegistry.counter("sudokupro.solve.bucket.60_120", GLOBAL_TAGS).increment();
        } else if (solveTimeSeconds <= 300) {
            meterRegistry.counter("sudokupro.solve.bucket.120_300", GLOBAL_TAGS).increment();
        } else {
            meterRegistry.counter("sudokupro.solve.bucket.300_plus", GLOBAL_TAGS).increment();
        }
    }

    /**
     * Reports suspicion buckets in Grafana/Prometheus as:
     * - sudokupro.suspicion.bucket.0
     * - sudokupro.suspicion.bucket.1_5
     * - sudokupro.suspicion.bucket.5_10
     * - sudokupro.suspicion.bucket.10_plus
     */
    private void reportSuspicionBuckets(long suspiciousCount) {
        if (suspiciousCount == 0) {
            meterRegistry.counter("sudokupro.suspicion.bucket.0", GLOBAL_TAGS).increment();
        } else if (suspiciousCount < 5) {
            meterRegistry.counter("sudokupro.suspicion.bucket.1_5", GLOBAL_TAGS).increment();
        } else if (suspiciousCount < 10) {
            meterRegistry.counter("sudokupro.suspicion.bucket.5_10", GLOBAL_TAGS).increment();
        } else {
            meterRegistry.counter("sudokupro.suspicion.bucket.10_plus", GLOBAL_TAGS).increment();
        }
    }

    private void validatePlayerId(String playerId) {
        if (playerId == null || playerId.trim().isEmpty()) {
            logger.error("Invalid playerId: {}", playerId);
            throw new IllegalArgumentException("Player ID cannot be null or empty");
        }
    }

    private void validateGameId(String gameId) {
        if (gameId == null || gameId.trim().isEmpty()) {
            logger.error("Invalid gameId: {}", gameId);
            throw new IllegalArgumentException("Game ID cannot be null or empty");
        }
    }
}
