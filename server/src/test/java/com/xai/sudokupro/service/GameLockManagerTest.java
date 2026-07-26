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
     * Regression: NESTED acquisition of the same game lock on one thread, against a
     * Redis mock with real SET NX semantics (succeeds only while the key is absent).
     *
     * <p>GameService does this constantly — {@code applyMove}/{@code getHint}/
     * {@code undo}/{@code redo}/{@code saveGame}/{@code resumeGame} all take the game
     * lock and then call {@code getGame}, which takes it again; {@code applyMove} also
     * nests {@code endGame}. The local monitor is reentrant but Redis SET NX is not, so
     * the inner acquisition used to spin the entire 3s wait budget and then throw
     * "busy on another server instance" — breaking every one of those operations
     * whenever Redis was actually reachable. Verified live: a single-replica server
     * with Redis up returned HTTP 500 after 3.1s for hint/save/solve, and WebSocket
     * moves came back as error envelopes.
     *
     * <p>{@code lockIsReentrantSafeAfterRelease} did not catch it because it re-locks
     * SEQUENTIALLY (after release), and the contention test below mocks setIfAbsent to
     * a constant, so neither ever holds the key across a nested call.
     */
    @Test
    void nestedLockOnSameGameSucceedsImmediatelyWhenRedisIsUp() throws Exception {
        java.util.Map<String, String> liveKeys = new java.util.concurrent.ConcurrentHashMap<>();
        StringRedisTemplate up = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(up.opsForValue()).thenReturn(ops);
        // Real SET NX: only sets when absent.
        when(ops.setIfAbsent(any(), any(), any(Duration.class))).thenAnswer(inv ->
            liveKeys.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
        // Token-checked release actually removes the key.
        when(up.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                        org.mockito.ArgumentMatchers.anyList(), any()))
            .thenAnswer(inv -> {
                java.util.List<String> keys = inv.getArgument(1);
                liveKeys.remove(keys.get(0));
                return 1L;
            });

        GameLockManager m = new GameLockManager(up);

        long start = System.currentTimeMillis();
        assertDoesNotThrow(() -> {
            try (var outer = m.lock("nested-game")) {
                try (var inner = m.lock("nested-game")) {
                    assertEquals(1, liveKeys.size(), "nested frame must reuse the outer Redis lock");
                }
                assertEquals(1, liveKeys.size(),
                    "closing the nested frame must NOT release the outer Redis lock");
            }
        }, "nested acquisition must not throw 'busy on another server instance'");
        long elapsedMs = System.currentTimeMillis() - start;

        assertTrue(elapsedMs < 1_000,
            "nested acquisition must be immediate, not spin the 3s wait budget (took " + elapsedMs + "ms)");
        assertTrue(liveKeys.isEmpty(), "the outermost release must free the Redis key");

        // And the game must be lockable again afterwards.
        assertDoesNotThrow(() -> { try (var again = m.lock("nested-game")) { /* reacquire */ } });
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

    /**
     * Regression: {@code releaseGame} used to unconditionally drop the map entry. Callers
     * did so while still HOLDING the lock ({@code endGame} called it from inside its own
     * try-with-resources; {@code trimActiveGames} did the same for evicted games), so the
     * next caller of {@code lock()} found no entry, {@code computeIfAbsent} minted a BRAND
     * NEW ReentrantLock, and two threads could occupy the same game's critical section.
     */
    @Test
    void releaseGameRefusesToDropAMonitorThatIsStillHeld() throws Exception {
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finish = new java.util.concurrent.CountDownLatch(1);

        Thread holder = new Thread(() -> {
            try (var lock = manager.lock("busy-game")) {
                inside.countDown();
                finish.await();
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        holder.start();
        assertTrue(inside.await(5, java.util.concurrent.TimeUnit.SECONDS));

        assertFalse(manager.releaseGame("busy-game"),
            "a held monitor must not be discarded — that breaks mutual exclusion");

        finish.countDown();
        holder.join(5000);

        assertTrue(manager.releaseGame("busy-game"),
            "once idle the monitor is reclaimed, so the map does not leak");
    }

    /** Monitors must not accumulate for games that have finished. */
    @Test
    void idleMonitorsAreReclaimedSoTheMapDoesNotGrowWithoutBound() {
        for (int i = 0; i < 500; i++) {
            String game = "ephemeral-" + i;
            try (var lock = manager.lock(game)) { /* play briefly */ }
            manager.releaseGame(game);
        }
        assertEquals(0, manager.trackedGameCount(),
            "every finished game's monitor should have been reclaimed");
    }
}
