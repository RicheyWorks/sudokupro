package com.xai.sudokupro.service.daily;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Streak math and leaderboard ordering, exercised in the degraded (local
 * in-memory) mode — the same single-replica fallback GameServiceTest uses, so
 * the logic is tested without a Redis dependency. The Redis happy path is the
 * same code shape with the store swapped.
 */
class DailyStateStoreTest {

    private static final LocalDate DAY1 = LocalDate.of(2026, 7, 14);
    private static final LocalDate DAY2 = LocalDate.of(2026, 7, 15);
    private static final LocalDate DAY3 = LocalDate.of(2026, 7, 16);

    private DailyStateStore store;

    @BeforeEach
    void setUp() {
        StringRedisTemplate downRedis = mock(StringRedisTemplate.class,
            inv -> { throw new RedisConnectionFailureException("down (test)"); });
        store = new DailyStateStore(downRedis);
    }

    @Test
    void firstCompletionCountsRepeatsDoNot() {
        assertTrue(store.recordCompletion(DAY1, "richmond", 120));
        assertFalse(store.recordCompletion(DAY1, "richmond", 90),
            "second submission for the same day must not count");
        assertTrue(store.isCompleted(DAY1, "richmond"));
        assertEquals(1, store.getStreak("richmond", DAY1));
    }

    @Test
    void consecutiveDaysGrowTheStreakAndAGapResetsIt() {
        store.recordCompletion(DAY1, "richmond", 100);
        store.recordCompletion(DAY2, "richmond", 100);
        assertEquals(2, store.getStreak("richmond", DAY2));

        // Missed DAY3 entirely: streak reads 0 two days later...
        LocalDate day5 = DAY3.plusDays(2);
        assertEquals(0, store.getStreak("richmond", day5));

        // ...and the next completion starts over at 1.
        store.recordCompletion(day5, "richmond", 100);
        assertEquals(1, store.getStreak("richmond", day5));
    }

    @Test
    void streakIsStillAliveTheMorningAfter() {
        store.recordCompletion(DAY2, "richmond", 100);
        assertEquals(1, store.getStreak("richmond", DAY3),
            "yesterday's completion must still show as a live streak today");
    }

    @Test
    void leaderboardOrdersByTimeAndCapsAtLimit() {
        store.recordCompletion(DAY1, "slow", 300);
        store.recordCompletion(DAY1, "fast", 60);
        store.recordCompletion(DAY1, "middle", 150);

        List<Map.Entry<String, Long>> top2 = store.leaderboard(DAY1, 2);

        assertEquals(2, top2.size());
        assertEquals("fast", top2.get(0).getKey());
        assertEquals(60L, top2.get(0).getValue());
        assertEquals("middle", top2.get(1).getKey());
    }

    @Test
    void daysAreIndependent() {
        store.recordCompletion(DAY1, "richmond", 100);
        assertFalse(store.isCompleted(DAY2, "richmond"));
        assertTrue(store.leaderboard(DAY2, 10).isEmpty());
    }
}
