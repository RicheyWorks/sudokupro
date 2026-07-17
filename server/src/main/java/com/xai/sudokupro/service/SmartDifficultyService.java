package com.xai.sudokupro.service;

import com.xai.sudokupro.model.SudokuBoard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-player adaptive difficulty (the DifficultyTuner promise, made personal).
 * Every finished game feeds the model via the GameEndListener hook:
 *
 * <ul>
 *   <li>a FAST signal — clean (no-hint) solve under 5 minutes at (or above)
 *       the player's current recommended level;</li>
 *   <li>a SLOW signal — a solve over 15 minutes, or an abandoned game.</li>
 * </ul>
 *
 * Three consecutive FAST signals promote the recommendation (cap 4); three
 * consecutive SLOW signals demote it (floor 1). State is a Redis hash with the
 * usual degrade-to-local-map fallback. It also aggregates a global average
 * that {@link TelemetryService} can report through the DifficultyTuner hook.
 */
@Service
public class SmartDifficultyService implements GameEndListener {

    private static final Logger logger = LoggerFactory.getLogger(SmartDifficultyService.class);
    private static final String KEY = "sudokupro:skill:"; // + playerId → hash {level, fast, slow}
    private static final long FAST_SECONDS = 300;
    private static final long SLOW_SECONDS = 900;
    private static final int SIGNALS_TO_MOVE = 3;

    private final StringRedisTemplate redis;
    private final AtomicBoolean degradedLogged = new AtomicBoolean(false);
    private final Map<String, int[]> local = new ConcurrentHashMap<>(); // {level, fast, slow}

    /** Global aggregate for the DifficultyTuner hook: sum of levels and player count. */
    private final AtomicInteger globalLevelSum = new AtomicInteger();
    private final AtomicInteger globalPlayers = new AtomicInteger();

    public SmartDifficultyService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** The player's recommended difficulty, 1-4. New players start at 2. */
    public int recommendedDifficulty(String playerId) {
        return read(playerId)[0];
    }

    @Override
    public void onGameEnded(SudokuBoard board, String playerId) {
        if (board == null || playerId == null || playerId.startsWith("__")) return;
        if (!playerId.equals(board.getPlayerId())) return; // skill signal belongs to the owner

        int[] s = read(playerId);
        int level = s[0], fast = s[1], slow = s[2];
        long seconds = board.getSolveTime().toSeconds();

        if (board.isSolved() && board.getHintCount() == 0
                && seconds > 0 && seconds < FAST_SECONDS && board.getDifficulty() >= level) {
            fast++; slow = 0;
        } else if (!board.isSolved() || seconds > SLOW_SECONDS) {
            slow++; fast = 0;
        } else {
            return; // neutral game — solved, but neither fast-clean nor slow
        }

        int before = level;
        if (fast >= SIGNALS_TO_MOVE && level < 4) { level++; fast = 0; }
        if (slow >= SIGNALS_TO_MOVE && level > 1) { level--; slow = 0; }
        write(playerId, level, fast, slow);
        if (level != before) {
            logger.info("Smart difficulty for {}: {} -> {}", playerId, before, level);
            if (globalPlayers.get() > 0) globalLevelSum.addAndGet(level - before);
        }
    }

    /**
     * Aggregate signal for the DifficultyTuner hook: how far the average
     * recommended level sits from the default of 2, as a small bounded integer.
     */
    public int globalAdjustmentFactor() {
        int players = globalPlayers.get();
        if (players == 0) return 0;
        double avg = (double) globalLevelSum.get() / players;
        return Math.max(-2, Math.min(2, (int) Math.round(avg - 2)));
    }

    // ---- state ----

    private int[] read(String playerId) {
        try {
            Map<Object, Object> h = redis.opsForHash().entries(KEY + playerId);
            if (h.isEmpty()) return new int[]{2, 0, 0};
            return new int[]{
                Integer.parseInt(h.getOrDefault("level", "2").toString()),
                Integer.parseInt(h.getOrDefault("fast", "0").toString()),
                Integer.parseInt(h.getOrDefault("slow", "0").toString())};
        } catch (Exception e) {
            degraded(e);
            return local.getOrDefault(playerId, new int[]{2, 0, 0}).clone();
        }
    }

    private void write(String playerId, int level, int fast, int slow) {
        boolean isNew;
        try {
            isNew = redis.opsForHash().entries(KEY + playerId).isEmpty();
            redis.opsForHash().putAll(KEY + playerId, Map.of(
                "level", String.valueOf(level), "fast", String.valueOf(fast), "slow", String.valueOf(slow)));
            redis.expire(KEY + playerId, java.time.Duration.ofDays(90));
        } catch (Exception e) {
            degraded(e);
            isNew = !local.containsKey(playerId);
            local.put(playerId, new int[]{level, fast, slow});
        }
        if (isNew) {
            globalPlayers.incrementAndGet();
            globalLevelSum.addAndGet(level);
        }
    }

    private void degraded(Exception e) {
        if (degradedLogged.compareAndSet(false, true)) {
            logger.warn("SmartDifficultyService: Redis unavailable — skill state in-memory only. Cause: {}",
                e.getMessage());
        }
    }
}
