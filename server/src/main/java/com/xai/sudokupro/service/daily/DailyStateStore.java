package com.xai.sudokupro.service.daily;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Daily-puzzle state shared across replicas: per-day completion sets, a per-day
 * solve-time leaderboard (Redis ZSET), and per-player consecutive-day streaks.
 * Same degrade-to-local-on-outage shape as {@code PlayerStateStore} — correct
 * for a single replica when Redis is down, logged once.
 */
@Component
public class DailyStateStore {

    private static final Logger logger = LoggerFactory.getLogger(DailyStateStore.class);
    private static final String COMPLETED_KEY = "sudokupro:daily:completed:"; // + date → set of playerIds
    private static final String LEADERBOARD_KEY = "sudokupro:daily:lb:";      // + date → zset player→seconds
    private static final String STREAK_KEY = "sudokupro:daily:streak:";       // + playerId → "count|lastDate"
    /** Per-day keys are only interesting briefly; streaks survive 90 idle days. */
    private static final Duration DAY_TTL = Duration.ofDays(3);
    private static final Duration STREAK_TTL = Duration.ofDays(90);

    private final StringRedisTemplate redis;
    private final AtomicBoolean degradedLogged = new AtomicBoolean(false);

    // Local fallbacks (single-replica correctness when Redis is down)
    private final Map<String, Set<String>> localCompleted = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentSkipListMap<Long, List<String>>> localBoards = new ConcurrentHashMap<>();
    private final Map<String, String> localStreaks = new ConcurrentHashMap<>();

    public record StreakState(int count, LocalDate lastDate) {}

    public DailyStateStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Records a first-time completion of {@code date}'s puzzle. Returns true if
     * this was the first completion (streak and leaderboard were updated), false
     * if the player had already completed the day.
     */
    public boolean recordCompletion(LocalDate date, String playerId, long solveSeconds) {
        String day = date.toString();
        boolean firstCompletion;
        try {
            // SADD's return value makes the first-completion check atomic across
            // replicas — two pods finishing simultaneously can't double-count.
            Long added = redis.opsForSet().add(COMPLETED_KEY + day, playerId);
            firstCompletion = added != null && added > 0;
            if (firstCompletion) {
                redis.expire(COMPLETED_KEY + day, DAY_TTL);
                redis.opsForZSet().add(LEADERBOARD_KEY + day, playerId, solveSeconds);
                redis.expire(LEADERBOARD_KEY + day, DAY_TTL);
            }
        } catch (Exception e) {
            degraded(e);
            firstCompletion = localCompleted
                .computeIfAbsent(day, d -> ConcurrentHashMap.newKeySet()).add(playerId);
            if (firstCompletion) {
                localBoards.computeIfAbsent(day, d -> new ConcurrentSkipListMap<>())
                    .computeIfAbsent(solveSeconds, s -> new ArrayList<>()).add(playerId);
            }
        }
        if (firstCompletion) advanceStreak(date, playerId);
        return firstCompletion;
    }

    public boolean isCompleted(LocalDate date, String playerId) {
        String day = date.toString();
        try {
            return Boolean.TRUE.equals(redis.opsForSet().isMember(COMPLETED_KEY + day, playerId));
        } catch (Exception e) {
            degraded(e);
            return localCompleted.getOrDefault(day, Set.of()).contains(playerId);
        }
    }

    /** Consecutive-day streak as of {@code today}. A gap of more than one day resets to 0. */
    public int getStreak(String playerId, LocalDate today) {
        StreakState s = readStreak(playerId);
        if (s == null) return 0;
        // Still "alive" if the last completion was today or yesterday.
        if (s.lastDate().isBefore(today.minusDays(1))) return 0;
        return s.count();
    }

    /** Ranked leaderboard for {@code date}, fastest first, ties by insertion order. */
    public List<Map.Entry<String, Long>> leaderboard(LocalDate date, int limit) {
        String day = date.toString();
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().rangeWithScores(LEADERBOARD_KEY + day, 0, limit - 1L);
            List<Map.Entry<String, Long>> out = new ArrayList<>();
            if (tuples != null) {
                for (ZSetOperations.TypedTuple<String> t : tuples) {
                    if (t.getValue() != null && t.getScore() != null) {
                        out.add(Map.entry(t.getValue(), t.getScore().longValue()));
                    }
                }
            }
            return out;
        } catch (Exception e) {
            degraded(e);
            List<Map.Entry<String, Long>> out = new ArrayList<>();
            var byTime = localBoards.getOrDefault(day, new ConcurrentSkipListMap<>());
            outer:
            for (var entry : byTime.entrySet()) {
                for (String p : entry.getValue()) {
                    out.add(Map.entry(p, entry.getKey()));
                    if (out.size() >= limit) break outer;
                }
            }
            return out;
        }
    }

    // ---- streak plumbing -----------------------------------------------------

    private void advanceStreak(LocalDate date, String playerId) {
        StreakState current = readStreak(playerId);
        int next;
        if (current == null) {
            next = 1;
        } else if (current.lastDate().equals(date)) {
            return; // double-guard; recordCompletion already filters repeats
        } else if (current.lastDate().equals(date.minusDays(1))) {
            next = current.count() + 1;
        } else {
            next = 1;
        }
        writeStreak(playerId, new StreakState(next, date));
    }

    private StreakState readStreak(String playerId) {
        String raw;
        try {
            raw = redis.opsForValue().get(STREAK_KEY + playerId);
        } catch (Exception e) {
            degraded(e);
            raw = localStreaks.get(playerId);
        }
        if (raw == null) return null;
        try {
            String[] parts = raw.split("\\|");
            return new StreakState(Integer.parseInt(parts[0]), LocalDate.parse(parts[1]));
        } catch (Exception e) {
            logger.warn("Corrupt daily streak entry for {}: '{}' — resetting", playerId, raw);
            return null;
        }
    }

    private void writeStreak(String playerId, StreakState state) {
        String raw = state.count() + "|" + state.lastDate();
        try {
            redis.opsForValue().set(STREAK_KEY + playerId, raw, STREAK_TTL);
        } catch (Exception e) {
            degraded(e);
            localStreaks.put(playerId, raw);
        }
    }

    private void degraded(Exception e) {
        if (degradedLogged.compareAndSet(false, true)) {
            logger.warn("DailyStateStore: Redis unavailable — daily state held in-memory only. "
                + "Fine for a single replica; NOT shared across replicas. Cause: {}", e.getMessage());
        }
    }
}
