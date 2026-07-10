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
}
