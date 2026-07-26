package com.xai.sudokupro.service;

import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
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
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RedisSyncScheduler}.
 *
 * <p><b>Defect classes this class protects against:</b>
 * <ul>
 *   <li><b>A database outage mis-reported as a Redis outage</b> — {@code gatherMetrics()} (a
 *       dozen aggregate SQL queries) ran <i>inside</i> the try-with-resources that borrows a
 *       Jedis connection. A repository failure therefore (a) checked out a pooled Redis
 *       connection it had no use for, (b) incremented {@code redis.sync.failure}, pointing
 *       on-call at the wrong system, and (c) was rethrown for {@code @Retryable} to hammer
 *       three more times.</li>
 *   <li><b>Redis down must fail fast and be swallowed</b> — a connection failure is counted
 *       and logged but must never escape a scheduled method, and must never leave the
 *       re-entrancy guard latched, which would silently disable the 5-minute sync forever.</li>
 *   <li><b>Pooled connections must always be returned</b>, including on the write-failure
 *       path.</li>
 *   <li><b>Tier bucket ranges and the anti-cheat threshold</b> — a transposed range would
 *       publish a mislabelled player distribution to every dashboard reading Redis.</li>
 * </ul>
 *
 * <p>Redis-down is modelled the way the fail-fast configuration now guarantees: the very
 * first call throws {@link JedisConnectionException} immediately rather than blocking.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisSyncSchedulerTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;
    @Mock private UserRepository userRepository;
    @Mock private GameRepository gameRepository;
    @Mock private AnalyticsService analyticsService;
    @Mock private AntiCheatEngine antiCheatEngine;

    private SimpleMeterRegistry registry;
    private RedisSyncScheduler scheduler;
    private final AtomicReference<List<String>> msetArgs = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        scheduler = new RedisSyncScheduler(jedisPool, userRepository, gameRepository,
                analyticsService, antiCheatEngine, registry);
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.mset(any(String[].class))).thenAnswer(inv -> {
            List<String> flat = new ArrayList<>();
            for (Object a : inv.getArguments()) {
                if (a instanceof String[] arr) {
                    flat.addAll(List.of(arr));
                } else {
                    flat.add((String) a);
                }
            }
            msetArgs.set(flat);
            return "OK";
        });
    }

    private static double counter(MeterRegistry r, String name) {
        Counter c = r.find(name).counter();
        return c == null ? 0.0 : c.count();
    }

    /** The flat key/value vararg array handed to MSET, turned back into a map. */
    private Map<String, String> capturedMset() {
        verify(jedis, atLeastOnce()).mset(any(String[].class));
        List<String> flat = msetArgs.get();
        assertNotNull(flat, "MSET was never called");
        assertEquals(0, flat.size() % 2, "MSET needs an even number of arguments, got " + flat.size());
        Map<String, String> written = new LinkedHashMap<>();
        for (int i = 0; i < flat.size(); i += 2) {
            written.put(flat.get(i), flat.get(i + 1));
        }
        return written;
    }

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    void syncWritesTheFullMetricSetAndCountsSuccess() {
        when(userRepository.countActiveUsersSince(any())).thenReturn(5L);
        when(userRepository.count()).thenReturn(100L);
        when(userRepository.getTotalGems()).thenReturn(7L);
        when(gameRepository.countActiveUnfinishedGames(any())).thenReturn(3L);
        when(analyticsService.getAverageSolveTime()).thenReturn(12.5);
        when(antiCheatEngine.getCheatSuspicionScores()).thenReturn(Map.of("a", 90.0, "b", 80.0, "c", 1.0));
        when(userRepository.countUsersInPointsRange(0, 1_000)).thenReturn(50L);
        when(userRepository.countUsersInPointsRange(1_000, 5_000)).thenReturn(30L);
        when(userRepository.countUsersInPointsRange(5_000, 10_000)).thenReturn(12L);
        when(userRepository.countUsersInPointsRange(10_000, 25_000)).thenReturn(6L);
        when(userRepository.countUsersWithMinPoints(25_000)).thenReturn(2L);

        scheduler.syncRedis();

        Map<String, String> written = capturedMset();
        assertEquals("5", written.get("active_users"));
        assertEquals("100", written.get("total_users"));
        assertEquals("7", written.get("total_gems"));
        assertEquals("3", written.get("active_games"));
        assertEquals("12.5", written.get("avg_solve_time"));
        assertEquals("2", written.get("suspicious"));
        assertEquals("50", written.get("tier_unranked"));
        assertEquals("30", written.get("tier_bronze"));
        assertEquals("12", written.get("tier_silver"));
        assertEquals("6", written.get("tier_gold"));
        assertEquals("2", written.get("tier_cosmic"));
        assertTrue(written.containsKey("last_sync"), "a sync timestamp must be written");
        assertTrue(Math.abs(Long.parseLong(written.get("last_sync")) - System.currentTimeMillis()) < 60_000);

        assertEquals(1.0, counter(registry, "redis.sync.success"));
        assertEquals(0.0, counter(registry, "redis.sync.failure"));
    }

    /** The seven-day active-user window is what the "active_users" key claims to hold. */
    @Test
    void activeUserWindowIsSevenDays() {
        scheduler.syncRedis();

        ArgumentCaptor<java.time.LocalDateTime> cutoff =
                ArgumentCaptor.forClass(java.time.LocalDateTime.class);
        verify(userRepository).countActiveUsersSince(cutoff.capture());
        long days = java.time.Duration.between(cutoff.getValue(), java.time.LocalDateTime.now()).toDays();
        assertEquals(7, days, "active_users must be the 7-day window");
    }

    @Test
    void anEmptyDatabaseStillWritesAConsistentMetricSet() {
        scheduler.syncRedis();

        Map<String, String> written = capturedMset();
        assertEquals("0", written.get("total_users"));
        assertEquals("0", written.get("suspicious"));
        assertEquals("0.0", written.get("avg_solve_time"));
        assertEquals(1.0, counter(registry, "redis.sync.success"));
    }

    /** Only strictly-above-75 suspicion scores count as suspicious. */
    @Test
    void suspicionThresholdIsExclusive() {
        Map<String, Double> scores = new HashMap<>();
        scores.put("clean", 10.0);
        scores.put("borderline", 75.0);
        scores.put("cheat", 75.1);
        when(antiCheatEngine.getCheatSuspicionScores()).thenReturn(scores);

        scheduler.syncRedis();

        assertEquals("1", capturedMset().get("suspicious"));
    }

    @Test
    void theBorrowedConnectionIsReturnedToThePool() {
        scheduler.syncRedis();
        verify(jedis).close();
    }

    // ------------------------------------------------------------------
    // Redis down
    // ------------------------------------------------------------------

    @Test
    void redisUnavailableAtCheckoutIsCountedAndSwallowed() {
        when(jedisPool.getResource()).thenThrow(new JedisConnectionException("connection refused"));

        assertDoesNotThrow(() -> scheduler.syncRedis());

        assertEquals(1.0, counter(registry, "redis.sync.failure"));
        assertEquals(0.0, counter(registry, "redis.sync.success"));
    }

    @Test
    void redisFailingOnWriteIsCountedAndSwallowed() {
        when(jedis.mset(any(String[].class))).thenThrow(new JedisConnectionException("broken pipe"));

        assertDoesNotThrow(() -> scheduler.syncRedis());

        assertEquals(1.0, counter(registry, "redis.sync.failure"));
        verify(jedis).close();
    }

    /**
     * A Redis outage must not latch the re-entrancy guard: if it did, the 5-minute sync would
     * never run again even after Redis came back.
     */
    @Test
    void aRedisOutageDoesNotLatchTheSchedulerOff() {
        when(jedisPool.getResource()).thenThrow(new JedisConnectionException("down"));

        scheduler.syncRedis();
        scheduler.syncRedis();
        scheduler.syncRedis();

        assertEquals(3.0, counter(registry, "redis.sync.failure"), "every tick must still run");
    }

    /** A non-connection Jedis error is genuinely retryable, so it is rethrown. */
    @Test
    void aNonConnectionJedisErrorIsRethrownForRetry() {
        when(jedis.mset(any(String[].class))).thenThrow(new JedisDataException("WRONGTYPE"));

        assertThrows(JedisDataException.class, () -> scheduler.syncRedis());
        assertEquals(1.0, counter(registry, "redis.sync.failure"));

        // ...and the guard was still released, so the retry can actually run.
        assertThrows(JedisDataException.class, () -> scheduler.syncRedis());
        assertEquals(2.0, counter(registry, "redis.sync.failure"));
    }

    // ------------------------------------------------------------------
    // Database down
    // ------------------------------------------------------------------

    /**
     * Reproduction: a repository failure used to be attributed to Redis. The sync had already
     * checked a connection out of the pool before running a single query, then incremented
     * {@code redis.sync.failure} and rethrew for three retries — so a Postgres blip paged the
     * Redis owner and burned pool capacity.
     */
    @Test
    void aDatabaseFailureIsNotAttributedToRedis() {
        when(userRepository.count()).thenThrow(new IllegalStateException("connection pool exhausted"));

        assertDoesNotThrow(() -> scheduler.syncRedis(),
                "a metric-collection failure must not escape the scheduled method");

        assertEquals(0.0, counter(registry, "redis.sync.failure"),
                "the database is not Redis; redis.sync.failure must stay at zero");
        assertEquals(0.0, counter(registry, "redis.sync.success"));
        verify(jedisPool, never()).getResource();
        verify(jedis, never()).mset(any(String[].class));
    }

    /** And the guard is released, so the next tick tries again. */
    @Test
    void aDatabaseFailureDoesNotLatchTheSchedulerOff() {
        when(userRepository.count())
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(11L);

        scheduler.syncRedis();
        scheduler.syncRedis();

        assertEquals("11", capturedMset().get("total_users"));
        assertEquals(1.0, counter(registry, "redis.sync.success"));
    }

    // ------------------------------------------------------------------
    // Overlap guard
    // ------------------------------------------------------------------

    @Test
    void overlappingSyncsAreSkipped() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        when(userRepository.count()).thenAnswer(inv -> {
            if (first.compareAndSet(true, false)) {
                inside.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return 0L;
        });

        Thread slow = new Thread(scheduler::syncRedis, "slow-redis-sync");
        slow.start();
        assertTrue(inside.await(5, TimeUnit.SECONDS));

        scheduler.syncRedis(); // must be skipped

        release.countDown();
        slow.join(5000);

        verify(userRepository, times(1)).count();
        verify(jedis, times(1)).mset(any(String[].class));
    }

    @Test
    void shutdownIsQuiet() {
        assertDoesNotThrow(() -> scheduler.shutdown());
    }
}
