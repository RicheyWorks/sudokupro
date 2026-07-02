package com.xai.sudokupro.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Degraded-mode tests (Phase 5): with Redis down, the store must behave exactly
 * like the old per-JVM maps — correct for a single replica.
 */
class PlayerStateStoreTest {

    private PlayerStateStore store;

    @BeforeEach
    void setUp() {
        StringRedisTemplate down = mock(StringRedisTemplate.class,
            inv -> { throw new RedisConnectionFailureException("down (test)"); });
        store = new PlayerStateStore(down);
    }

    @Test
    void streaksFallBackToMemory() {
        assertEquals(0, store.getStreak("p1"));
        store.incrementStreak("p1");
        store.incrementStreak("p1");
        assertEquals(2, store.getStreak("p1"));
    }

    @Test
    void cosmicPointsFallBackToMemory() {
        store.addCosmicPoints("p1", 2);
        store.addCosmicPoints("p1", 3);
        assertEquals(5, store.getCosmicPoints("p1"));
    }

    @Test
    void inputLockHoldsAndExpires() throws Exception {
        store.lockPlayerInput("p1", 60_000);
        assertTrue(store.isPlayerLocked("p1"));

        store.lockPlayerInput("p2", 1); // 1ms — expires immediately
        Thread.sleep(10);
        assertFalse(store.isPlayerLocked("p2"), "Expired fallback lock must clear");
    }

    @Test
    void resetClearsEverything() {
        store.incrementStreak("p1");
        store.addCosmicPoints("p1", 2);
        store.lockPlayerInput("p1", 60_000);

        store.resetPlayer("p1");

        assertEquals(0, store.getStreak("p1"));
        assertEquals(0, store.getCosmicPoints("p1"));
        assertFalse(store.isPlayerLocked("p1"));
    }
}
