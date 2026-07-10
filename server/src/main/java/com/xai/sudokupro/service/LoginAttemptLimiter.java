package com.xai.sudokupro.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Brute-force lockout for HTTP Basic auth: after {@code maxAttempts} failed logins from
 * the same remote address within {@code lockoutWindow}, further attempts are rejected
 * (see {@code LoginAttemptFilter}) until the window expires.
 *
 * Redis-backed so the lockout holds across replicas, same degrade-gracefully shape as
 * {@link GameLockManager} / {@link PlayerStateStore}: if Redis is unreachable, falls back
 * to an in-memory counter — correct for a single replica, logged once.
 */
@Component
public class LoginAttemptLimiter {

    private static final Logger logger = LoggerFactory.getLogger(LoginAttemptLimiter.class);
    private static final String KEY_PREFIX = "sudokupro:login:fail:";

    private final StringRedisTemplate redis;
    private final int maxAttempts;
    private final Duration lockoutWindow;
    private final AtomicBoolean degradedLogged = new AtomicBoolean(false);

    private final Map<String, LocalCounter> local = new ConcurrentHashMap<>();

    public LoginAttemptLimiter(
            StringRedisTemplate redis,
            @Value("${sudokupro.security.login.max-attempts:5}") int maxAttempts,
            @Value("${sudokupro.security.login.lockout-seconds:60}") long lockoutSeconds) {
        this.redis = redis;
        this.maxAttempts = maxAttempts;
        this.lockoutWindow = Duration.ofSeconds(lockoutSeconds);
    }

    public boolean isBlocked(String remoteAddress) {
        try {
            String v = redis.opsForValue().get(KEY_PREFIX + remoteAddress);
            return v != null && Integer.parseInt(v) >= maxAttempts;
        } catch (Exception e) {
            degraded(e);
            LocalCounter c = local.get(remoteAddress);
            return c != null && c.count >= maxAttempts && System.currentTimeMillis() < c.expiresAtMs;
        }
    }

    public void recordFailure(String remoteAddress) {
        try {
            String key = KEY_PREFIX + remoteAddress;
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, lockoutWindow);
            }
        } catch (Exception e) {
            degraded(e);
            long now = System.currentTimeMillis();
            local.compute(remoteAddress, (k, c) -> {
                if (c == null || now > c.expiresAtMs) {
                    return new LocalCounter(1, now + lockoutWindow.toMillis());
                }
                c.count++;
                return c;
            });
        }
    }

    public void recordSuccess(String remoteAddress) {
        try {
            redis.delete(KEY_PREFIX + remoteAddress);
        } catch (Exception e) {
            degraded(e);
        }
        local.remove(remoteAddress);
    }

    private void degraded(Exception e) {
        if (degradedLogged.compareAndSet(false, true)) {
            logger.warn("LoginAttemptLimiter: Redis unavailable — falling back to in-memory counters. "
                + "Fine for a single replica; NOT safe with multiple replicas. Cause: {}", e.getMessage());
        }
    }

    private static final class LocalCounter {
        int count;
        final long expiresAtMs;
        LocalCounter(int count, long expiresAtMs) {
            this.count = count;
            this.expiresAtMs = expiresAtMs;
        }
    }
}
