package com.xai.sudokupro.service;

import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MetricsScheduler}, driven against a real {@link SimpleMeterRegistry}
 * (mocking a MeterRegistry would test nothing about the meters that actually get published).
 *
 * <p><b>Defect classes this class protects against:</b>
 * <ul>
 *   <li><b>Unbounded meter cardinality</b> — {@code triggerChaosMode} and
 *       {@code recordDuelOutcome} tagged their counters with the raw player id, so the
 *       registry grew one permanently-retained time series per player. That is the classic
 *       Micrometer leak: meters are never reclaimed, and a Prometheus scrape of a
 *       per-player-tagged counter takes the metrics backend down with it.</li>
 *   <li><b>A metrics sink throwing into its caller</b> — {@code recordDuelOutcome} only
 *       guarded the rank lookup against {@code NumberFormatException}; a database failure
 *       inside {@code LeaderboardService.getPlayerRank} (which wraps everything in a
 *       {@code RuntimeException}) escaped into the business flow that was merely trying to
 *       record a duel result.</li>
 *   <li><b>Fractional gauges truncated to whole numbers</b> — the "average" gauges are
 *       registered as doubles but were stored in {@code AtomicLong}s via a {@code (long)}
 *       cast, so an average cosmic drip of 0.9 was published as 0.</li>
 *   <li><b>A scheduled run that latches itself off</b> — if the overlap guard were not
 *       released on the exception path, one failure would silently disable the 30-second
 *       metrics job for the lifetime of the process.</li>
 *   <li><b>Bucket / threshold boundaries, level-vs-counter semantics, empty inputs, and
 *       input validation.</b></li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MetricsSchedulerTest {

    @Mock private UserRepository userRepository;
    @Mock private GameRepository gameRepository;
    @Mock private AnalyticsService analyticsService;
    @Mock private AntiCheatEngine antiCheatEngine;
    @Mock private LeaderboardService leaderboardService;

    private SimpleMeterRegistry registry;
    private MetricsScheduler scheduler;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        scheduler = newScheduler(registry);
        // SUM(...) is COALESCEd in the query, so the real repository never returns null here.
        when(userRepository.getTotalPointsByTheme(anyString())).thenReturn(0L);
    }

    private MetricsScheduler newScheduler(MeterRegistry r) {
        return new MetricsScheduler(r, userRepository, gameRepository, analyticsService,
                antiCheatEngine, leaderboardService);
    }

    private static double gauge(MeterRegistry r, String name) {
        return r.get(name).gauge().value();
    }

    private static double gauge(MeterRegistry r, String name, String tagKey, String tagValue) {
        return r.get(name).tag(tagKey, tagValue).gauge().value();
    }

    private static double counter(MeterRegistry r, String name) {
        Counter c = r.find(name).counter();
        return c == null ? 0.0 : c.count();
    }

    private static long metersNamed(MeterRegistry r, String name) {
        return r.getMeters().stream().map(Meter::getId).filter(id -> id.getName().equals(name)).count();
    }

    private static Map<String, Double> suspicionMap(int overThreshold) {
        Map<String, Double> m = new HashMap<>();
        for (int i = 0; i < overThreshold; i++) {
            m.put("p" + i, 90.0);
        }
        return m;
    }

    // ------------------------------------------------------------------
    // Meter cardinality
    // ------------------------------------------------------------------

    /**
     * Reproduction of the cardinality leak: every distinct player id used to mint its own
     * {@code sudokupro.chaos.mode.activations{player=...}} meter, retained forever by the
     * registry.
     */
    @Test
    void chaosModeDoesNotMintOneMeterPerPlayer() {
        for (int i = 0; i < 500; i++) {
            scheduler.triggerChaosMode("player-" + i);
        }

        assertEquals(1, metersNamed(registry, "sudokupro.chaos.mode.activations"),
                "one aggregate counter, not one per player");
        assertEquals(500.0, counter(registry, "sudokupro.chaos.mode.activations"),
                "every activation is still counted");
    }

    /**
     * Same leak on the duel path. Outcome (2) x tier (5) is a bounded tag space; the player id
     * is not.
     */
    @Test
    void duelOutcomesDoNotMintOneMeterPerPlayer() {
        for (int i = 0; i < 300; i++) {
            scheduler.recordDuelOutcome("player-" + i, i % 2 == 0);
        }

        assertTrue(metersNamed(registry, "sudokupro.duels.by.tier") <= 10,
                "duel meters must stay in a bounded tag space; got "
                        + metersNamed(registry, "sudokupro.duels.by.tier"));
        double total = registry.find("sudokupro.duels.by.tier").counters()
                .stream().mapToDouble(Counter::count).sum();
        assertEquals(300.0, total, "every duel outcome is still counted");
    }

    // ------------------------------------------------------------------
    // recordDuelOutcome robustness
    // ------------------------------------------------------------------

    /**
     * Reproduction: {@code LeaderboardService.getPlayerRank} wraps any repository failure in a
     * RuntimeException. Recording a duel outcome is pure telemetry and must not turn a
     * database hiccup into a failed duel.
     */
    @Test
    void duelOutcomeRecordingSurvivesARankLookupFailure() {
        when(leaderboardService.getPlayerRank(anyLong()))
                .thenThrow(new RuntimeException("player rank for 7 retrieval failed"));

        assertDoesNotThrow(() -> scheduler.recordDuelOutcome("7", true));

        assertEquals(1.0, registry.get("sudokupro.duels.by.tier")
                .tag("tier", "Unranked").tag("outcome", "win").counter().count());
    }

    @Test
    void duelOutcomeWithNonNumericPlayerIdIsTaggedUnranked() {
        assertDoesNotThrow(() -> scheduler.recordDuelOutcome("anonymous", false));

        assertEquals(1.0, registry.get("sudokupro.duels.by.tier")
                .tag("tier", "Unranked").tag("outcome", "loss").counter().count());
        verify(leaderboardService, never()).getPlayerRank(anyLong());
    }

    @Test
    void duelOutcomeUsesThePlayersActualTier() {
        when(leaderboardService.getPlayerRank(7L)).thenReturn(
                new LeaderboardService.LeaderboardSnapshot("seven", 12_000, 3, "Gold", 1, 2, 3, 0));

        scheduler.recordDuelOutcome("7", true);

        assertEquals(1.0, registry.get("sudokupro.duels.by.tier")
                .tag("tier", "Gold").tag("outcome", "win").counter().count());
    }

    /** A player with no rank row at all is Unranked, not a null-tag crash. */
    @Test
    void duelOutcomeWithNoRankRowIsUnranked() {
        when(leaderboardService.getPlayerRank(7L)).thenReturn(null);

        scheduler.recordDuelOutcome("7", false);

        assertEquals(1.0, registry.get("sudokupro.duels.by.tier")
                .tag("tier", "Unranked").counter().count());
    }

    // ------------------------------------------------------------------
    // Gauge semantics
    // ------------------------------------------------------------------

    /**
     * "Users active in the last 24h" is a level. Reporting the same head-count twice must
     * leave the gauge at that head-count, not at twice it.
     */
    @Test
    void activeUsersIsALevelNotACumulativeTotal() {
        when(userRepository.countActiveUsersSince(any())).thenReturn(40L);

        scheduler.reportUserMetrics();
        scheduler.reportUserMetrics();

        assertEquals(40.0, gauge(registry, "sudokupro.active.users"));
    }

    /**
     * Reproduction of the truncation defect: the average gauges are published as doubles but
     * were stored through a {@code (long)} cast. A population averaging 0.9 cosmic drip was
     * reported as 0 — a 100% error, and the metric is useless below 1.
     */
    @Test
    void averageGaugesKeepTheirFractionalPart() {
        when(analyticsService.getAverageCosmicDripActiveUsers(any())).thenReturn(0.9);
        when(analyticsService.getPlayerWinRates()).thenReturn(Map.of("a", 1.0, "b", 0.0, "c", 0.5));

        scheduler.reportUserMetrics();

        assertEquals(0.9, gauge(registry, "sudokupro.cosmic.drip.average"), 1e-9);
        // (1.0 + 0.0 + 0.5) / 3 = 0.5 -> 50% ; the old code truncated 50.0 fine but
        // 2/3 -> 66.66..% became 66.
        assertEquals(50.0, gauge(registry, "sudokupro.duel.win.rate.average"), 1e-9);
    }

    @Test
    void averageSolveTimeGaugeKeepsItsFractionalPart() {
        when(analyticsService.getAverageSolveTime()).thenReturn(45.75);

        scheduler.reportDailyMetrics();

        assertEquals(45.75, gauge(registry, "sudokupro.solve.time.average"), 1e-9);
    }

    /** No duel outcomes recorded at all must not yield NaN (0/0) in the win-rate gauge. */
    @Test
    void emptyWinRatesReportZeroNotNaN() {
        when(analyticsService.getPlayerWinRates()).thenReturn(Map.of());

        scheduler.reportUserMetrics();

        double v = gauge(registry, "sudokupro.duel.win.rate.average");
        assertFalse(Double.isNaN(v), "win-rate average must not be NaN");
        assertEquals(0.0, v);
    }

    @Test
    void totalGemsAndActiveGamesAreReportedIntoTheirOwnGauges() {
        when(userRepository.getTotalGems()).thenReturn(1234L);
        when(gameRepository.countActiveUnfinishedGames(any())).thenReturn(7L);

        scheduler.reportUserMetrics();

        assertEquals(1234.0, gauge(registry, "sudokupro.total.gems"));
        assertEquals(7.0, gauge(registry, "sudokupro.active.games"));
    }

    @Test
    void everyGaugeIsRegisteredExactlyOnceAndCarriesTheGlobalAppTag() {
        assertEquals(1, metersNamed(registry, "sudokupro.total.users"));
        assertEquals(1, metersNamed(registry, "sudokupro.total.gems"));
        assertEquals(1, metersNamed(registry, "sudokupro.active.users"));
        assertNotNull(registry.find("sudokupro.total.users").tag("app", "SudokuPro").gauge(),
                "gauges must carry the global app tag");
    }

    // ------------------------------------------------------------------
    // Query windows
    // ------------------------------------------------------------------

    /** The "last 24h" active-user window must really be 24 hours, and games "last hour". */
    @Test
    void activityWindowsMatchTheirMetricNames() {
        scheduler.reportUserMetrics();

        ArgumentCaptor<LocalDateTime> users = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userRepository).countActiveUsersSince(users.capture());
        long userHours = Duration.between(users.getValue(), LocalDateTime.now()).toMinutes();
        assertTrue(userHours >= 23 * 60 && userHours <= 25 * 60,
                "active-user cutoff should be ~24h ago, was " + userHours + " minutes");

        ArgumentCaptor<LocalDateTime> games = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(gameRepository).countActiveUnfinishedGames(games.capture());
        long gameMinutes = Duration.between(games.getValue(), LocalDateTime.now()).toMinutes();
        assertTrue(gameMinutes >= 55 && gameMinutes <= 65,
                "active-game cutoff should be ~1h ago, was " + gameMinutes + " minutes");
    }

    // ------------------------------------------------------------------
    // Overlap guard / failure paths
    // ------------------------------------------------------------------

    /**
     * A failed run must release the overlap guard. If it did not, the very first transient
     * database error would permanently disable the 30-second metrics job — every later run
     * would hit the "previous run still in progress" branch and return.
     */
    @Test
    void aFailedRunDoesNotLatchTheSchedulerOff() {
        when(userRepository.getTotalGems())
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(55L);

        assertThrows(IllegalStateException.class, () -> scheduler.reportUserMetrics(),
                "the failure is rethrown so @Retryable can see it");

        scheduler.reportUserMetrics();
        assertEquals(55.0, gauge(registry, "sudokupro.total.gems"), "the next run must still execute");
    }

    /** A second, concurrent invocation is skipped rather than double-reporting. */
    @Test
    void overlappingRunsAreSkipped() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        when(userRepository.getTotalGems()).thenAnswer(inv -> {
            if (first.compareAndSet(true, false)) {
                inside.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return 0L;
        });

        Thread slowRun = new Thread(scheduler::reportUserMetrics, "slow-metrics-run");
        slowRun.start();
        assertTrue(inside.await(5, TimeUnit.SECONDS), "first run should have entered the body");

        scheduler.reportUserMetrics(); // must return immediately, doing nothing

        release.countDown();
        slowRun.join(5000);

        verify(userRepository, times(1)).countActiveUsersSince(any());
    }

    @Test
    void dailyMetricsRethrowFailuresSoRetryCanSeeThem() {
        when(userRepository.count()).thenThrow(new IllegalStateException("db down"));
        assertThrows(IllegalStateException.class, () -> scheduler.reportDailyMetrics());
    }

    @Test
    void gaugeDumpNeverThrows() {
        assertDoesNotThrow(() -> scheduler.logAllGaugesToConsole());
    }

    // ------------------------------------------------------------------
    // Daily metrics
    // ------------------------------------------------------------------

    /**
     * Tier gauges must be fed from the matching point ranges. A transposed pair here would
     * silently mislabel the whole player population on the dashboard.
     */
    @Test
    void tierGaugesAreFedFromTheDocumentedPointRanges() {
        when(userRepository.countUsersInPointsRange(1_000, 5_000)).thenReturn(11L);
        when(userRepository.countUsersInPointsRange(5_000, 10_000)).thenReturn(7L);
        when(userRepository.countUsersInPointsRange(10_000, 25_000)).thenReturn(3L);
        when(userRepository.countUsersWithMinPoints(25_000)).thenReturn(1L);

        scheduler.reportDailyMetrics();

        assertEquals(11.0, gauge(registry, "sudokupro.tiers.bronze"));
        assertEquals(7.0, gauge(registry, "sudokupro.tiers.silver"));
        assertEquals(3.0, gauge(registry, "sudokupro.tiers.gold"));
        assertEquals(1.0, gauge(registry, "sudokupro.tiers.cosmic"));
    }

    @Test
    void themePointsAreReportedPerTheme() {
        when(userRepository.getTotalPointsByTheme("retro-pixel")).thenReturn(10L);
        when(userRepository.getTotalPointsByTheme("manga-mode")).thenReturn(20L);
        when(userRepository.getTotalPointsByTheme("astral-nebula")).thenReturn(30L);
        when(userRepository.getTotalPointsByTheme("default")).thenReturn(40L);

        scheduler.reportDailyMetrics();

        assertEquals(10.0, gauge(registry, "sudokupro.points.by.theme", "theme", "retro-pixel"));
        assertEquals(20.0, gauge(registry, "sudokupro.points.by.theme", "theme", "manga-mode"));
        assertEquals(30.0, gauge(registry, "sudokupro.points.by.theme", "theme", "astral-nebula"));
        assertEquals(40.0, gauge(registry, "sudokupro.points.by.theme", "theme", "default"));
    }

    @Test
    void totalAndDailyActiveUsersAreReported() {
        when(userRepository.count()).thenReturn(9_001L);
        when(userRepository.countActiveUsersSince(any())).thenReturn(42L);

        scheduler.reportDailyMetrics();

        assertEquals(9001.0, gauge(registry, "sudokupro.total.users"));
        assertEquals(42.0, gauge(registry, "sudokupro.daily.active.users"));
    }

    // ------------------------------------------------------------------
    // Buckets — hand-derived boundary tables
    // ------------------------------------------------------------------

    @Test
    void solveTimeBucketBoundaries() {
        assertEquals("sudokupro.solve.bucket.0_30", bucketFor(0.0));
        assertEquals("sudokupro.solve.bucket.0_30", bucketFor(30.0));
        assertEquals("sudokupro.solve.bucket.30_60", bucketFor(30.01));
        assertEquals("sudokupro.solve.bucket.30_60", bucketFor(60.0));
        assertEquals("sudokupro.solve.bucket.60_120", bucketFor(60.01));
        assertEquals("sudokupro.solve.bucket.60_120", bucketFor(120.0));
        assertEquals("sudokupro.solve.bucket.120_300", bucketFor(120.01));
        assertEquals("sudokupro.solve.bucket.120_300", bucketFor(300.0));
        assertEquals("sudokupro.solve.bucket.300_plus", bucketFor(300.01));
        assertEquals("sudokupro.solve.bucket.300_plus", bucketFor(99_999.0));
    }

    /** Records one completion into a fresh registry and returns the single bucket that moved. */
    private String bucketFor(double solveTimeSeconds) {
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        newScheduler(r).recordGameCompletion("g1", solveTimeSeconds);
        return r.getMeters().stream()
                .filter(m -> m.getId().getName().startsWith("sudokupro.solve.bucket."))
                .filter(m -> ((Counter) m).count() > 0)
                .map(m -> m.getId().getName())
                .reduce((a, b) -> {
                    throw new AssertionError("more than one bucket moved: " + a + ", " + b);
                })
                .orElseThrow(() -> new AssertionError("no bucket moved for " + solveTimeSeconds));
    }

    @Test
    void suspicionBucketBoundaries() {
        assertEquals("sudokupro.suspicion.bucket.0", suspicionBucketFor(0));
        assertEquals("sudokupro.suspicion.bucket.1_5", suspicionBucketFor(1));
        assertEquals("sudokupro.suspicion.bucket.1_5", suspicionBucketFor(4));
        assertEquals("sudokupro.suspicion.bucket.5_10", suspicionBucketFor(5));
        assertEquals("sudokupro.suspicion.bucket.5_10", suspicionBucketFor(9));
        assertEquals("sudokupro.suspicion.bucket.10_plus", suspicionBucketFor(10));
        assertEquals("sudokupro.suspicion.bucket.10_plus", suspicionBucketFor(50));
    }

    private String suspicionBucketFor(int suspiciousPlayers) {
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        when(antiCheatEngine.getCheatSuspicionScores()).thenReturn(suspicionMap(suspiciousPlayers));
        newScheduler(r).reportUserMetrics();
        return r.getMeters().stream()
                .filter(m -> m.getId().getName().startsWith("sudokupro.suspicion.bucket."))
                .filter(m -> ((Counter) m).count() > 0)
                .map(m -> m.getId().getName())
                .reduce((a, b) -> {
                    throw new AssertionError("more than one bucket moved: " + a + ", " + b);
                })
                .orElseThrow(() -> new AssertionError("no suspicion bucket moved"));
    }

    /** A score of exactly 75.0 is not suspicious; the threshold is strictly greater-than. */
    @Test
    void suspicionThresholdIsExclusive() {
        Map<String, Double> scores = new HashMap<>();
        scores.put("clean", 10.0);
        scores.put("borderline", 75.0);
        scores.put("cheat", 75.1);
        when(antiCheatEngine.getCheatSuspicionScores()).thenReturn(scores);

        scheduler.reportUserMetrics();

        assertEquals(1.0, gauge(registry, "sudokupro.suspicious.players"));
    }

    /** No players at all: no crash, gauge at zero, the "0" bucket ticks. */
    @Test
    void emptyPopulationProducesZeroedMetrics() {
        assertDoesNotThrow(() -> scheduler.reportUserMetrics());

        assertEquals(0.0, gauge(registry, "sudokupro.suspicious.players"));
        assertEquals(0.0, gauge(registry, "sudokupro.total.gems"));
        assertEquals(0.0, gauge(registry, "sudokupro.active.games"));
        assertEquals(1.0, counter(registry, "sudokupro.suspicion.bucket.0"));
    }

    // ------------------------------------------------------------------
    // Ad-hoc recording API
    // ------------------------------------------------------------------

    @Test
    void gameCompletionRecordsCounterTimerAndHistogram() {
        scheduler.recordGameCompletion("g1", 42.0);

        assertEquals(1.0, counter(registry, "sudokupro.games.completed"));
        assertEquals(1L, registry.get("sudokupro.game.solve.time").timer().count());
        assertEquals(1L, registry.get("sudokupro.solve.time.buckets").summary().count());
    }

    @Test
    void invalidIdentifiersAreRejectedBeforeAnyMeterIsTouched() {
        assertThrows(IllegalArgumentException.class, () -> scheduler.triggerChaosMode(null));
        assertThrows(IllegalArgumentException.class, () -> scheduler.triggerChaosMode(""));
        assertThrows(IllegalArgumentException.class, () -> scheduler.triggerChaosMode("   "));
        assertThrows(IllegalArgumentException.class, () -> scheduler.recordGameCompletion(null, 1.0));
        assertThrows(IllegalArgumentException.class, () -> scheduler.recordGameCompletion("  ", 1.0));
        assertThrows(IllegalArgumentException.class, () -> scheduler.recordDuelOutcome(null, true));
        assertThrows(IllegalArgumentException.class, () -> scheduler.recordDuelOutcome("", true));

        assertEquals(0.0, counter(registry, "sudokupro.chaos.mode.activations"));
        assertEquals(0.0, counter(registry, "sudokupro.games.completed"));
        assertEquals(0, metersNamed(registry, "sudokupro.duels.by.tier"));
    }

    @Test
    void shutdownIsQuiet() {
        assertDoesNotThrow(() -> scheduler.shutdown());
    }

    // ------------------------------------------------------------------
    // Gauges that can only ever read zero
    // ------------------------------------------------------------------

    /**
     * Reproduction of a permanently-zero gauge. {@code reportUserMetrics} computed
     * {@code calculateActiveGamesByTheme(List.of())} — a hardcoded empty list — every 30
     * seconds, so all four {@code sudokupro.active.games.by.theme{theme=...}} series read 0
     * for the life of the process. Nothing else wrote them either: the comment claimed
     * "gauges updated in reportDailyMetrics", but that method only touches
     * {@code sudokupro.points.by.theme}. Even fed real rows the series could not have been
     * right — {@code getBoardTheme} was a stub returning the literal {@code "default"},
     * because {@code SudokuBoard} has no theme field to read.
     *
     * <p>This is worse than having no metric: a Grafana panel "active games by theme" sitting
     * flat at zero during an outage is indistinguishable from the same panel during the
     * healthy hours before it, so the dashboard actively argues the system is fine. With no
     * theme on a game there is nothing truthful to publish, so the series is gone; the real
     * total continues to be published as {@code sudokupro.active.games}.
     *
     * <p>Pre-fix this test failed on the second assertion while the first passed — seven
     * active games in the system, zero in every per-theme series.
     */
    @Test
    void noPerThemeActiveGamesGaugeIsPublishedBecauseGamesHaveNoTheme() {
        when(gameRepository.countActiveUnfinishedGames(any())).thenReturn(7L);

        scheduler.reportUserMetrics();

        assertEquals(7.0, gauge(registry, "sudokupro.active.games"),
                "the honest total is still reported");
        assertEquals(0, metersNamed(registry, "sudokupro.active.games.by.theme"),
                "a per-theme breakdown that can only ever read 0 must not be published at all");
    }

    /**
     * The theme dimension that <i>is</i> backed by real data must survive. Points per theme
     * come from a real aggregate query over {@code users.theme_preference}, so that gauge
     * stays — the fix removes the lie, not every mention of themes.
     */
    @Test
    void thePointsPerThemeGaugeIsUnaffectedAndStillCarriesRealData() {
        when(userRepository.getTotalPointsByTheme("retro-pixel")).thenReturn(11L);
        when(userRepository.getTotalPointsByTheme("default")).thenReturn(22L);

        scheduler.reportDailyMetrics();

        assertEquals(11.0, gauge(registry, "sudokupro.points.by.theme", "theme", "retro-pixel"));
        assertEquals(22.0, gauge(registry, "sudokupro.points.by.theme", "theme", "default"));
    }
}
