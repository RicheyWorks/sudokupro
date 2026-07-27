package com.xai.sudokupro.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Redis is "down" here (mock always throws), so this exercises the in-memory fallback —
 * the single-replica code path. Cross-replica behavior is just Redis INCR/EXPIRE, already
 * proven correct by GameLockManager/PlayerStateStore's use of the same primitives.
 */
class LoginAttemptLimiterTest {

    private LoginAttemptLimiter limiterWith(int maxAttempts, long lockoutSeconds) {
        StringRedisTemplate down = mock(StringRedisTemplate.class,
            inv -> { throw new RedisConnectionFailureException("down (test)"); });
        return new LoginAttemptLimiter(down, maxAttempts, lockoutSeconds);
    }

    @Test
    void notBlockedBeforeMaxAttempts() {
        LoginAttemptLimiter limiter = limiterWith(3, 60);
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        assertFalse(limiter.isBlocked("1.2.3.4"));
    }

    @Test
    void blockedAtMaxAttempts() {
        LoginAttemptLimiter limiter = limiterWith(3, 60);
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        assertTrue(limiter.isBlocked("1.2.3.4"));
    }

    @Test
    void differentAddressesAreTrackedIndependently() {
        LoginAttemptLimiter limiter = limiterWith(2, 60);
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        assertTrue(limiter.isBlocked("1.2.3.4"));
        assertFalse(limiter.isBlocked("5.6.7.8"), "A different address must not share the counter");
    }

    @Test
    void recordSuccessClearsTheCounter() {
        LoginAttemptLimiter limiter = limiterWith(2, 60);
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        assertTrue(limiter.isBlocked("1.2.3.4"));

        limiter.recordSuccess("1.2.3.4");

        assertFalse(limiter.isBlocked("1.2.3.4"));
    }

    @Test
    void lockoutExpiresAfterTheWindow() throws InterruptedException {
        LoginAttemptLimiter limiter = limiterWith(2, 1); // 1-second window
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        assertTrue(limiter.isBlocked("1.2.3.4"));

        Thread.sleep(1100);

        assertFalse(limiter.isBlocked("1.2.3.4"), "Lockout must lapse once the window expires");
    }

    /**
     * The degraded-mode counter map must not grow with every distinct username ever tried.
     *
     * <p>Nothing swept it: expiry was consulted only for a key touched again, and the sole
     * removal path requires a SUCCESSFUL authentication for that exact key. The key includes
     * the username, which the filter base64-decodes straight out of the {@code Authorization}
     * header and which the failure listener records whether or not the account exists.
     *
     * <p>So while Redis is down — a supported degraded mode, and precisely when the pod can
     * least afford it — an unauthenticated caller looping
     * {@code Authorization: Basic base64(<random>:x)} permanently allocated an entry per
     * distinct username. No success would ever arrive to free them. Heap grows until the
     * process dies. The Redis path never had this problem; its keys carry a TTL.
     */
    @Test
    void theDegradedModeCounterMapDoesNotGrowWithEveryUsernameEverTried() throws Exception {
        StringRedisTemplate down = org.mockito.Mockito.mock(StringRedisTemplate.class,
            inv -> { throw new org.springframework.data.redis.RedisConnectionFailureException("down (test)"); });
        // A one-millisecond window so entries are stale almost immediately — the point is
        // that stale entries are REMOVED, not that the window is long.
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(down, 5, 0);

        int attempts = 4_000;
        for (int i = 0; i < attempts; i++) {
            limiter.recordFailure("203.0.113.9", "victim-guess-" + i);
        }

        assertTrue(limiter.localCounterCount() < attempts,
            "expired counters must be evicted rather than accumulated — held "
                + limiter.localCounterCount() + " of " + attempts + " distinct usernames");
    }

    /** Eviction must not weaken the lockout: a live counter still blocks. */
    @Test
    void sweepingDoesNotDropALiveLockout() {
        StringRedisTemplate down = org.mockito.Mockito.mock(StringRedisTemplate.class,
            inv -> { throw new org.springframework.data.redis.RedisConnectionFailureException("down (test)"); });
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(down, 3, 300);

        for (int i = 0; i < 3; i++) limiter.recordFailure("203.0.113.9", "victim");
        // Drive enough unrelated traffic to trigger a sweep, all inside the window.
        for (int i = 0; i < 4_000; i++) limiter.recordFailure("203.0.113.9", "noise-" + i);

        assertTrue(limiter.isBlocked("203.0.113.9", "victim"),
            "a live lockout must survive the sweep");
    }
}
