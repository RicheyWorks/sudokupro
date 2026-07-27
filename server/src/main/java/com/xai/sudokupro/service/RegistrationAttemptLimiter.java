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
 * Per-client quota on account creation: at most {@code maxAttempts} calls to
 * {@code POST /api/auth/register} from one client address within {@code window}, enforced by
 * {@link com.xai.sudokupro.config.RegistrationThrottleFilter}.
 *
 * <h2>Why registration needs its own limiter rather than reusing {@link LoginAttemptLimiter}</h2>
 * The two count opposite things. The login limiter counts <em>failures</em> and a success
 * clears the counter, because a legitimate user who eventually remembers their password
 * should not stay locked out. For registration the successes <em>are</em> the abuse: an
 * attacker creating ten thousand accounts succeeds every time. Clearing on success — or
 * scoping by (address, username) as the login limiter does, since every registration uses a
 * fresh username — would make the counter reset on every request and the throttle a no-op.
 * So this counts every attempt, keyed by client address alone, and nothing clears it but the
 * window expiring.
 *
 * <h2>Client address</h2>
 * The caller supplies the address; {@code RegistrationThrottleFilter} takes it from
 * {@code request.getRemoteAddr()} and never from a request header. See that class and the
 * {@code server.forward-headers-strategy} note in {@code application.properties} for why
 * {@code X-Forwarded-For} is not trusted here.
 *
 * <h2>Storage</h2>
 * Redis-backed so the quota holds across replicas, with the same degrade-gracefully shape as
 * {@link LoginAttemptLimiter} / {@link GameLockManager} / {@link PlayerStateStore}: if Redis
 * is unreachable it falls back to an in-memory counter, which is correct for a single replica
 * and is logged once. Failing open is deliberate — a Redis outage must not stop new players
 * from signing up.
 */
@Component
public class RegistrationAttemptLimiter {

    private static final Logger logger = LoggerFactory.getLogger(RegistrationAttemptLimiter.class);
    private static final String KEY_PREFIX = "sudokupro:register:attempt:";

    private final StringRedisTemplate redis;
    private final int maxAttempts;
    private final Duration window;
    private final AtomicBoolean degradedLogged = new AtomicBoolean(false);

    private final Map<String, LocalCounter> local = new ConcurrentHashMap<>();
    /** Only sweep the degraded-mode map once it is big enough to be worth sweeping. */
    private static final int LOCAL_SWEEP_THRESHOLD = 1024;

    /**
     * Drops expired counters from the in-memory fallback map.
     *
     * <p>Same no-eviction shape as {@code LoginAttemptLimiter} had: nothing ever swept this,
     * and expiry was consulted only for a key touched again. Milder here because the key is
     * the client address alone, so growth is bounded by reachable source addresses rather
     * than by an attacker-chosen string — but a botnet or a v6 range still walks it up, and
     * an expired counter permits exactly what an absent one permits, so removing them cannot
     * change a decision.
     */
    private void sweepExpired(long now) {
        if (local.size() <= LOCAL_SWEEP_THRESHOLD) return;
        local.values().removeIf(c -> now > c.expiresAtMs);
    }

    /** Test/observability hook: live entries in the degraded-mode counter map. */
    int localCounterCount() {
        return local.size();
    }

    public RegistrationAttemptLimiter(
            StringRedisTemplate redis,
            @Value("${sudokupro.security.register.max-attempts:5}") int maxAttempts,
            @Value("${sudokupro.security.register.window-seconds:600}") long windowSeconds) {
        this.redis = redis;
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration window() {
        return window;
    }

    /** True when {@code clientAddress} has already used its quota for the current window. */
    public boolean isThrottled(String clientAddress) {
        try {
            String v = redis.opsForValue().get(KEY_PREFIX + clientAddress);
            return v != null && Integer.parseInt(v) >= maxAttempts;
        } catch (Exception e) {
            degraded(e);
            LocalCounter c = local.get(clientAddress);
            return c != null && c.count >= maxAttempts && System.currentTimeMillis() < c.expiresAtMs;
        }
    }

    /**
     * Counts one registration attempt, successful or not.
     *
     * <p>Rejected attempts count deliberately: the endpoint's cost (request handling, the
     * username lookup, and a 409 that also reveals whether a username exists) is incurred
     * either way, so counting only successes would leave both the resource drain and a free
     * username-enumeration oracle unlimited.
     */
    public void recordAttempt(String clientAddress) {
        try {
            String key = KEY_PREFIX + clientAddress;
            Long count = redis.opsForValue().increment(key);
            // Set the TTL on first use only: re-setting it on every attempt would make the
            // window slide forward forever under sustained traffic, so the key would never
            // expire and a client throttled once would stay throttled permanently.
            if (count != null && count == 1L) {
                redis.expire(key, window);
            }
        } catch (Exception e) {
            degraded(e);
            long now = System.currentTimeMillis();
            sweepExpired(now);
            local.compute(clientAddress, (k, c) -> {
                if (c == null || now > c.expiresAtMs) {
                    return new LocalCounter(1, now + window.toMillis());
                }
                c.count++;
                return c;
            });
        }
    }

    private void degraded(Exception e) {
        if (degradedLogged.compareAndSet(false, true)) {
            logger.warn("RegistrationAttemptLimiter: Redis unavailable — falling back to in-memory "
                + "counters. Fine for a single replica; NOT safe with multiple replicas. Cause: {}",
                e.getMessage());
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
