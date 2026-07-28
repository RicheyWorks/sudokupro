package com.xai.sudokupro.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The scheduled-event firing guard. There was no test here at all, and the guard it replaced
 * fired a fresh daily challenge on <em>every process restart</em>: the in-memory "last fired"
 * map was seeded at construction to {@code now - interval}, so the first tick after boot
 * always passed the elapsed check. It was also per-pod, so N replicas each fired
 * independently.
 */
class EventSchedulerTest {

    private EventEngine engine;
    private MutableClock clock;
    private Map<String, String> redisStore;
    private EventScheduler scheduler;

    @BeforeEach
    void setUp() {
        engine = mock(EventEngine.class);
        clock = new MutableClock(Instant.parse("2026-07-28T09:00:00Z"));
        redisStore = new HashMap<>();
        scheduler = new EventScheduler(engine, fakeRedis(redisStore), clock);
    }

    /**
     * A stateful stand-in for {@code setIfAbsent(key, val, ttl)} so the SET NX semantics are
     * exercised for real: the first call for a key returns true, later calls return false.
     */
    @SuppressWarnings("unchecked")
    private static StringRedisTemplate fakeRedis(Map<String, String> store) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        lenient().when(redis.opsForValue()).thenReturn(ops);
        lenient().when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenAnswer(inv -> store.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
        return redis;
    }

    /**
     * The headline regression: repeated ticks within the same period fire once, and a
     * restart (a fresh scheduler over the SAME shared Redis) does NOT re-fire.
     */
    @Test
    void restartsAndRepeatTicksWithinAPeriodFireTheDailyChallengeOnce() {
        scheduler.triggerDailyChallenge();
        scheduler.triggerDailyChallenge();   // same day, later tick

        // A deploy: a brand-new scheduler instance over the same Redis, same day.
        EventScheduler afterDeploy = new EventScheduler(engine, fakeRedis(redisStore), clock);
        afterDeploy.triggerDailyChallenge();

        verify(engine, times(1)).triggerDailyChallenge();
    }

    /** A genuinely new day is a new period, so the daily challenge fires again. */
    @Test
    void theDailyChallengeFiresAgainOnANewDay() {
        scheduler.triggerDailyChallenge();
        clock.advance(Duration.ofDays(1));
        scheduler.triggerDailyChallenge();

        verify(engine, times(2)).triggerDailyChallenge();
    }

    /** Drip showdown buckets by 15 minutes: same bucket once, next bucket again. */
    @Test
    void dripShowdownFiresOncePerQuarterHour() {
        scheduler.triggerDripShowdown();
        clock.advance(Duration.ofMinutes(5));
        scheduler.triggerDripShowdown();      // same 15-minute bucket
        verify(engine, times(1)).triggerDripShowdown();

        clock.advance(Duration.ofMinutes(15)); // next bucket
        scheduler.triggerDripShowdown();
        verify(engine, times(2)).triggerDripShowdown();
    }

    /**
     * Two replicas over one Redis: exactly one wins the period. This is the cross-replica
     * duplicate the per-pod map could never prevent.
     */
    @Test
    void onlyOneReplicaFiresAGivenPeriod() {
        EventScheduler replicaA = new EventScheduler(engine, fakeRedis(redisStore), clock);
        EventScheduler replicaB = new EventScheduler(engine, fakeRedis(redisStore), clock);

        replicaA.triggerCosmicDuel();
        replicaB.triggerCosmicDuel();   // same hour, loses the claim

        verify(engine, times(1)).triggerCosmicDuel();
    }

    /**
     * With Redis down the guard degrades to per-pod dedup: a single replica still fires once
     * per period rather than on every tick. An outage can skip an event on some replicas,
     * never double-fire on one.
     */
    @Test
    void withRedisDownASinglePodStillFiresOncePerPeriod() {
        StringRedisTemplate down = mock(StringRedisTemplate.class,
            inv -> { throw new RedisConnectionFailureException("down (test)"); });
        EventScheduler degraded = new EventScheduler(engine, down, clock);

        degraded.triggerDailyChallenge();
        degraded.triggerDailyChallenge();   // same day
        verify(engine, times(1)).triggerDailyChallenge();

        clock.advance(Duration.ofDays(1));
        degraded.triggerDailyChallenge();
        verify(engine, times(2)).triggerDailyChallenge();
    }

    /**
     * A manual fire claims the period, so the scheduled tick in the same period does not
     * fire it a second time.
     */
    @Test
    void aManualFireClaimsThePeriodSoTheScheduledTickSkips() {
        scheduler.triggerEventNow("daily_challenge");
        scheduler.triggerDailyChallenge();

        verify(engine, times(1)).triggerDailyChallenge();
    }

    /** A failing engine still propagates for @Retryable, and does not swallow the error. */
    @Test
    void aFailingEngineCallPropagates() {
        doThrow(new IllegalStateException("generator exhausted")).when(engine).triggerDripShowdown();

        assertThrows(IllegalStateException.class, () -> scheduler.triggerDripShowdown());
    }

    /** A clock the test drives. */
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration by) { this.now = this.now.plus(by); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
