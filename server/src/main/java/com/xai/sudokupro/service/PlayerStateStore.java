package com.xai.sudokupro.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hot per-player state shared across replicas (Phase 5 / AUDIT P1-7): win streaks,
 * cosmic points, and input locks live in Redis so every pod sees the same values.
 *
 * Degrades gracefully: if Redis is unreachable, operations fall back to in-memory
 * maps — correct for single-replica deployments (the pre-Phase-5 behavior), and the
 * degradation is logged once so multi-replica operators notice.
 */
@Component
public class PlayerStateStore {

    private static final Logger logger = LoggerFactory.getLogger(PlayerStateStore.class);
    private static final String STREAK_KEY = "sudokupro:player:streak:";
    private static final String COSMIC_KEY = "sudokupro:player:cosmic:";
    private static final String INPUT_LOCK_KEY = "sudokupro:player:inputlock:";
    /** Idle streak/points expire after 30 days so abandoned players don't accumulate keys. */
    private static final Duration STATE_TTL = Duration.ofDays(30);

    private final StringRedisTemplate redis;
    private final AtomicBoolean degradedLogged = new AtomicBoolean(false);

    // In-memory fallback (single-replica correctness when Redis is down)
    private final Map<String, Integer> localStreaks = new ConcurrentHashMap<>();
    private final Map<String, Integer> localCosmic = new ConcurrentHashMap<>();
    private final Map<String, Long> localInputLocks = new ConcurrentHashMap<>();

    public PlayerStateStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ---- streaks ----

    public int incrementStreak(String playerId) {
        try {
            Long v = redis.opsForValue().increment(STREAK_KEY + playerId);
            redis.expire(STREAK_KEY + playerId, STATE_TTL);
            return v == null ? 0 : v.intValue();
        } catch (Exception e) {
            degraded(e);
            return localStreaks.merge(playerId, 1, Integer::sum);
        }
    }

    public int getStreak(String playerId) {
        try {
            String v = redis.opsForValue().get(STREAK_KEY + playerId);
            return v == null ? 0 : Integer.parseInt(v);
        } catch (Exception e) {
            degraded(e);
            return localStreaks.getOrDefault(playerId, 0);
        }
    }

    // ---- cosmic points ----

    public int addCosmicPoints(String playerId, int amount) {
        try {
            Long v = redis.opsForValue().increment(COSMIC_KEY + playerId, amount);
            redis.expire(COSMIC_KEY + playerId, STATE_TTL);
            return v == null ? 0 : v.intValue();
        } catch (Exception e) {
            degraded(e);
            return localCosmic.merge(playerId, amount, Integer::sum);
        }
    }

    public int getCosmicPoints(String playerId) {
        try {
            String v = redis.opsForValue().get(COSMIC_KEY + playerId);
            return v == null ? 0 : Integer.parseInt(v);
        } catch (Exception e) {
            degraded(e);
            return localCosmic.getOrDefault(playerId, 0);
        }
    }

    // ---- input locks (expiry handled by Redis TTL; fallback checks timestamps) ----

    public void lockPlayerInput(String playerId, long durationMs) {
        try {
            redis.opsForValue().set(INPUT_LOCK_KEY + playerId, "1", Duration.ofMillis(durationMs));
        } catch (Exception e) {
            degraded(e);
            localInputLocks.put(playerId, System.currentTimeMillis() + durationMs);
        }
    }

    public boolean isPlayerLocked(String playerId) {
        // Check the local fallback first: a lock taken during a Redis outage must still
        // hold after Redis comes back (it only exists in the local map).
        Long until = localInputLocks.get(playerId);
        if (until != null) {
            if (System.currentTimeMillis() <= until) return true;
            localInputLocks.remove(playerId);
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(INPUT_LOCK_KEY + playerId));
        } catch (Exception e) {
            degraded(e);
            return false;
        }
    }

    // ---- reset ----

    public void resetPlayer(String playerId) {
        try {
            redis.delete(java.util.List.of(STREAK_KEY + playerId, COSMIC_KEY + playerId,
                                           INPUT_LOCK_KEY + playerId));
        } catch (Exception e) {
            degraded(e);
        }
        localStreaks.remove(playerId);
        localCosmic.remove(playerId);
        localInputLocks.remove(playerId);
    }

    private void degraded(Exception e) {
        if (degradedLogged.compareAndSet(false, true)) {
            logger.warn("PlayerStateStore: Redis unavailable — falling back to in-memory state. "
                + "Fine for a single replica; NOT safe with multiple replicas. Cause: {}", e.getMessage());
        }
    }
}
