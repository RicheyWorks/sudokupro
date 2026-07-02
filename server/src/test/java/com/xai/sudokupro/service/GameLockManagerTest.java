package com.xai.sudokupro.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

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
}
