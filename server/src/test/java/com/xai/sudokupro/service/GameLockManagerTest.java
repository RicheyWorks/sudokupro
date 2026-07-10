package com.xai.sudokupro.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Local mutual-exclusion tests for the distributed game lock (Phase 5). Redis is
 * "down" here, so this exercises the local-lock fallback; cross-replica exclusion
 * is covered by the Docker-gated integration test.
 */
class GameLockManagerTest {

    private final GameLockManager manager = new GameLockManager(
        mock(StringRedisTemplate.class,
            inv -> { throw new RedisConnectionFailureException("down (test)"); }));

    @Test
    void locksAreMutuallyExclusivePerGame() throws Exception {
        AtomicInteger inCritical = new AtomicInteger();
        AtomicInteger maxSeen = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(4);

        for (int i = 0; i < 4; i++) {
            new Thread(() -> {
                for (int j = 0; j < 50; j++) {
                    try (var lock = manager.lock("game-1")) {
                        int now = inCritical.incrementAndGet();
                        maxSeen.accumulateAndGet(now, Math::max);
                        inCritical.decrementAndGet();
                    }
                }
                done.countDown();
            }).start();
        }
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, maxSeen.get(), "Never more than one holder of the same game lock");
    }

    @Test
    void differentGamesDoNotBlockEachOther() throws Exception {
        try (var lock1 = manager.lock("game-a")) {
            CountDownLatch acquired = new CountDownLatch(1);
            new Thread(() -> {
                try (var lock2 = manager.lock("game-b")) {
                    acquired.countDown();
                }
            }).start();
            assertTrue(acquired.await(2, java.util.concurrent.TimeUnit.SECONDS),
                "A different game's lock must be acquirable while game-a is held");
        }
    }

    @Test
    void lockIsReentrantSafeAfterRelease() {
        try (var lock = manager.lock("game-c")) { /* first hold */ }
        assertDoesNotThrow(() -> {
            try (var lock = manager.lock("game-c")) { /* second hold */ }
        });
        manager.releaseGame("game-c");
        assertDoesNotThrow(() -> {
            try (var lock = manager.lock("game-c")) { /* after entry dropped */ }
        });
    }

    /**
     * Redis reachable but another replica holds the key the whole wait budget (setIfAbsent
     * keeps returning false, never throws) — must fail loudly rather than silently proceed
     * under local-only locking, and must not leak the local lock on the way out.
     */
    @Test
    void throwsOnGenuineCrossReplicaContentionAndDoesNotLeakLocalLock() throws Exception {
        StringRedisTemplate contended = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(contended.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);
        GameLockManager contendedManager = new GameLockManager(contended);

        assertThrows(IllegalStateException.class, () -> contendedManager.lock("game-contended"));

        // If the local lock leaked on that throw, a second acquisition from a different
        // thread would block forever on local.lock() instead of reaching (and throwing
        // from) acquireRedis again.
        CountDownLatch reachedSecondThrow = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                contendedManager.lock("game-contended");
            } catch (IllegalStateException expected) {
                reachedSecondThrow.countDown();
            }
        });
        t.start();
        assertTrue(reachedSecondThrow.await(4, java.util.concurrent.TimeUnit.SECONDS),
            "Local lock must be released when acquireRedis throws, not leaked");
    }
}
