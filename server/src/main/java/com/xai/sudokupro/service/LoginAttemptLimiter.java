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
    /** Only sweep the degraded-mode map once it is big enough to be worth sweeping. */
    private static final int LOCAL_SWEEP_THRESHOLD = 1024;

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

    /**
     * Builds the counter key.
     *
     * <p>Keying on the remote address ALONE made the whole limiter bypassable, because
     * {@link #recordSuccess} deletes the key and any successful login from that address
     * cleared it — including a login to a different account. {@code POST /api/auth/register}
     * is permitAll and CSRF-exempt, so an attacker registers their own account for free,
     * then guesses four passwords against the victim, logs in once as themselves, and
     * repeats. Measured: twelve consecutive wrong-password attempts against one target,
     * zero 429s, where the control run without the reset produced a 429 on the sixth.
     *
     * <p>Scoping the counter to (address, username) means a success can only clear the
     * counter for the account that succeeded, so another account's failures keep counting.
     */
    private static String scope(String remoteAddress, String username) {
        String who = (username == null || username.isBlank()) ? "-" : username.toLowerCase(java.util.Locale.ROOT);
        return remoteAddress + "|" + who;
    }

    public boolean isBlocked(String remoteAddress, String username) {
        return isBlocked(scope(remoteAddress, username));
    }

    public void recordFailure(String remoteAddress, String username) {
        recordFailure(scope(remoteAddress, username));
    }

    public void recordSuccess(String remoteAddress, String username) {
        recordSuccess(scope(remoteAddress, username));
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
            sweepExpired(now);
            local.compute(remoteAddress, (k, c) -> {
                if (c == null || now > c.expiresAtMs) {
                    return new LocalCounter(1, now + lockoutWindow.toMillis());
                }
                c.count++;
                return c;
            });
        }
    }

    /**
     * Drops expired counters from the in-memory fallback map.
     *
     * <p>Nothing swept it. Expiry was only ever consulted for a key that was touched again,
     * and the sole removal was {@link #recordSuccess}, which needs a <em>successful</em>
     * authentication for that exact key. The key is {@code (remoteAddress, username)}, and
     * the username comes straight off the wire — the filter base64-decodes it out of the
     * {@code Authorization} header and the failure listener records it whether or not the
     * account exists.
     *
     * <p>So while Redis is down — an explicitly supported degraded mode, and exactly when
     * the pod is least able to shed load — an unauthenticated caller could send
     * {@code Authorization: Basic base64(<random>:x)} in a loop and permanently allocate a
     * map entry per distinct username. No success would ever arrive to clear them, and
     * nothing expired them. Heap grows until the process dies.
     *
     * <p>An expired counter permits exactly what an absent one permits ({@code isBlocked}
     * treats both as zero attempts, and {@code recordFailure} resets a stale counter to 1),
     * so removing them cannot change a decision. The Redis path never had this problem —
     * its keys carry the lockout window as a TTL.
     */
    private void sweepExpired(long now) {
        if (local.size() <= LOCAL_SWEEP_THRESHOLD) return;
        local.values().removeIf(c -> now > c.expiresAtMs);
    }

    /** Test/observability hook: live entries in the degraded-mode counter map. */
    int localCounterCount() {
        return local.size();
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
