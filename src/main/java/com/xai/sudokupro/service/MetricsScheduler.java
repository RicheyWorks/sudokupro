package com.xai.sudokupro.service;

import com.xai.sudokupro.model.SudokuBoard;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    private final Map<String, AtomicLong> themeGameGauges = new HashMap<>();
    private final AtomicLong totalUsersGauge = new AtomicLong(0);
    private final AtomicLong totalGemsGauge = new AtomicLong(0);
    private final AtomicLong activeGamesGauge = new AtomicLong(0);
    private final AtomicLong suspiciousPlayersGauge = new AtomicLong(0);
    private final AtomicLong cosmicDripGauge = new AtomicLong(0);
    private final AtomicLong duelWinRateGauge = new AtomicLong(0);
    private final AtomicLong dailyActiveGauge = new AtomicLong(0);
    private final AtomicLong solveTimeGauge = new AtomicLong(0);
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
            themeGameGauges.put(theme, new AtomicLong(0));
            meterRegistry.gauge("sudokupro.active.games.by.theme", Tags.concat(GLOBAL_TAGS, Tags.of("theme", theme)), 
                themeGameGauges.get(theme), AtomicLong::get);
            themePointsGauges.put(theme, new AtomicLong(0));
            meterRegistry.gauge("sudokupro.points.by.theme", Tags.concat(GLOBAL_TAGS, Tags.of("theme", theme)), 
                themePointsGauges.get(theme), AtomicLong::get);
        }
        meterRegistry.gauge("sudokupro.total.users", GLOBAL_TAGS, totalUsersGauge, AtomicLong::get);
        meterRegistry.gauge("sudokupro.total.gems", GLOBAL_TAGS, totalGemsGauge, AtomicLong::get);
        meterRegistry.gauge("sudokupro.active.games", GLOBAL_TAGS, activeGamesGauge, AtomicLong::get);
        meterRegistry.gauge("sudokupro.suspicious.players", GLOBAL_TAGS, suspiciousPlayersGauge, AtomicLong::get);
        meterRegistry.gauge("sudokupro.cosmic.drip.average", GLOBAL_TAGS, cosmicDripGauge, AtomicLong::doubleValue);
        meterRegistry.gauge("sudokupro.duel.win.rate.average", GLOBAL_TAGS, duelWinRateGauge, AtomicLong::doubleValue);
        meterRegistry.gauge("sudokupro.daily.active.users", GLOBAL_TAGS, dailyActiveGauge, AtomicLong::get);
        meterRegistry.gauge("sudokupro.solve.time.average", GLOBAL_TAGS, solveTimeGauge, AtomicLong::doubleValue);
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
            meterRegistry.counter("sudokupro.active.users", Tags.concat(GLOBAL_TAGS, Tags.of("period", "24h"))).increment(activeUsers);
            logger.debug("Reported active users: {}", activeUsers);

            // Total Gems in System
            long totalGems = userRepository.findAll().stream().mapToLong(User::getGems).sum();
            totalGemsGauge.set(totalGems);
            logger.debug("Reported total gems: {}", totalGems);

            // Active Games (last hour)
            List<SudokuBoard> activeGamesList = gameRepository.findActiveUnfinishedGames(LocalDateTime.now().minusHours(1), null);
            long activeGames = activeGamesList.size();
            activeGamesGauge.set(activeGames);
            logger.debug("Reported active games: {}", activeGames);

            // Chaos Mode Activations
            meterRegistry.counter("sudokupro.chaos.mode.activations", GLOBAL_TAGS).increment(0); // Reset if no new triggers
            logger.trace("Chaos mode activations reset");

            // Cosmic Drip Distribution
            double avgDrip = analyticsService.getAverageCosmicDripActiveUsers(LocalDateTime.now().minusDays(1));
            cosmicDripGauge.set((long) avgDrip);
            logger.debug("Reported average cosmic drip: {}", avgDrip);

            // Duel Win Rate
            Map<String, Double> winRates = analyticsService.getPlayerWinRates();
            double avgWinRate = winRates.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            duelWinRateGauge.set((long) (avgWinRate * 100)); // Store as percentage
            logger.debug("Reported average duel win rate: {}", avgWinRate);

            // Suspicious Players
            long suspiciousCount = antiCheatEngine.getCheatSuspicionScores().entrySet().stream()
                .filter(e -> e.getValue() > SUSPICION_THRESHOLD)
                .count();
            suspiciousPlayersGauge.set(suspiciousCount);
            suspicionRateHistogram.record(suspiciousCount);
            reportSuspicionBuckets(suspiciousCount);
            logger.debug("Reported suspicious players: {}", suspiciousCount);

            // Active Games by Theme
            Map<String, Long> activeGamesByTheme = calculateActiveGamesByTheme(activeGamesList);
            activeGamesByTheme.forEach((theme, count) -> themeGameGauges.get(theme).set(count));
            logger.debug("Reported active games by theme: {}", activeGamesByTheme);

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
            solveTimeGauge.set((long) avgSolveTime);
            solveTimeHistogram.record(avgSolveTime);
            reportSolveTimeBuckets(avgSolveTime);
            logger.debug("Reported average solve time: {}s", avgSolveTime);

            // Total Points by Theme
            List<User> allUsers = userRepository.findAll();
            for (String theme : THEMES) {
                long themePoints = allUsers.stream()
                    .filter(u -> theme.equals(u.getThemePreference()))
                    .mapToLong(User::getPoints)
                    .sum();
                themePointsGauges.get(theme).set(themePoints);
                logger.debug("Reported total points for theme {}: {}", theme, themePoints);
            }

            // Users by Leaderboard Tier
            reportUsersByTier(allUsers);
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
            themeGameGauges.forEach((theme, gauge) -> logger.info("Active Games for Theme {}: {}", theme, gauge.get()));
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
        meterRegistry.counter("sudokupro.chaos.mode.activations", Tags.concat(GLOBAL_TAGS, Tags.of("player", playerId))).increment();
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
        LeaderboardService.LeaderboardSnapshot rank = leaderboardService.getPlayerRank(Long.parseLong(playerId));
        String tier = rank != null ? rank.tier() : "Unranked";
        String outcome = won ? "win" : "loss";
        meterRegistry.counter("sudokupro.duels.by.tier", 
            Tags.concat(GLOBAL_TAGS, Tags.of("player", playerId, "outcome", outcome, "tier", tier))).increment();
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
    private void reportUsersByTier(List<User> allUsers) {
        long bronze = allUsers.stream()
            .filter(u -> u.getPoints() >= TIER_THRESHOLDS[0] && u.getPoints() < TIER_THRESHOLDS[1]).count();
        long silver = allUsers.stream()
            .filter(u -> u.getPoints() >= TIER_THRESHOLDS[1] && u.getPoints() < TIER_THRESHOLDS[2]).count();
        long gold = allUsers.stream()
            .filter(u -> u.getPoints() >= TIER_THRESHOLDS[2] && u.getPoints() < TIER_THRESHOLDS[3]).count();
        long cosmic = allUsers.stream()
            .filter(u -> u.getPoints() >= TIER_THRESHOLDS[3]).count();

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

    private Map<String, Long> calculateActiveGamesByTheme(List<SudokuBoard> activeGames) {
        Map<String, Long> themeCounts = new HashMap<>();
        for (String theme : THEMES) {
            long count = activeGames.stream()
                .filter(board -> theme.equals(getBoardTheme(board)))
                .count();
            themeCounts.put(theme, count);
        }
        return themeCounts;
    }

    /**
     * TODO: Replace with actual theme extraction logic when SudokuBoard includes a theme field.
     * Expected: board.getTheme() or similar.
     */
    private String getBoardTheme(SudokuBoard board) {
        // Placeholder until theme is implemented in SudokuBoard
        return "default";
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
