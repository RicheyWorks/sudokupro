package com.xai.sudokupro.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-game mutual exclusion that holds across replicas (Phase 5 / AUDIT P1-7).
 *
 * Two layers:
 *  - a local ReentrantLock per game (fast path, same-pod contention), and
 *  - a Redis lock (SET NX PX + token-checked release) so pods don't mutate the
 *    same game concurrently.
 *
 * If the Redis lock can't be acquired within the wait budget — or Redis is down —
 * we proceed under the local lock only and log; blocking gameplay on a Redis
 * hiccup would be worse than the (single-replica-equivalent) risk.
 */
@Component
public class GameLockManager {

    private static final Logger logger = LoggerFactory.getLogger(GameLockManager.class);
    private static final String LOCK_KEY = "sudokupro:lock:game:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final long WAIT_BUDGET_MS = 3_000;
    private static final long RETRY_SLEEP_MS = 50;

    /** Token-checked delete: only the holder may release. */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
        Long.class);

    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();
    private final AtomicBoolean degradedLogged = new AtomicBoolean(false);

    public GameLockManager(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Acquires the game lock; release with try-with-resources. */
    public LockHandle lock(String gameId) {
        ReentrantLock local = localLocks.computeIfAbsent(gameId, k -> new ReentrantLock());
        local.lock();
        String token = acquireRedis(gameId);
        return new LockHandle(gameId, local, token);
    }

    /** Drops the local lock entry when a game ends (Redis keys expire via TTL). */
    public void releaseGame(String gameId) {
        localLocks.remove(gameId);
    }

    private String acquireRedis(String gameId) {
        String token = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + WAIT_BUDGET_MS;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (Boolean.TRUE.equals(
                        redis.opsForValue().setIfAbsent(LOCK_KEY + gameId, token, LOCK_TTL))) {
                    return token;
                }
                Thread.sleep(RETRY_SLEEP_MS);
            }
            logger.warn("Game {} redis lock not acquired within {}ms — proceeding under local lock",
                gameId, WAIT_BUDGET_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (degradedLogged.compareAndSet(false, true)) {
                logger.warn("GameLockManager: Redis unavailable — local locking only. "
                    + "Fine for a single replica; NOT safe with multiple replicas. Cause: {}", e.getMessage());
            }
        }
        return null;
    }

    private void releaseRedis(String gameId, String token) {
        if (token == null) return;
        try {
            redis.execute(UNLOCK_SCRIPT, List.of(LOCK_KEY + gameId), token);
        } catch (Exception e) {
            logger.debug("Redis unlock failed for game {} (TTL will expire it): {}", gameId, e.getMessage());
        }
    }

    /** Held game lock; closing releases Redis first, then the local monitor. */
    public final class LockHandle implements AutoCloseable {
        private final String gameId;
        private final ReentrantLock local;
        private final String token;

        private LockHandle(String gameId, ReentrantLock local, String token) {
            this.gameId = gameId;
            this.local = local;
            this.token = token;
        }

        @Override
        public void close() {
            releaseRedis(gameId, token);
            local.unlock();
        }
    }
}
