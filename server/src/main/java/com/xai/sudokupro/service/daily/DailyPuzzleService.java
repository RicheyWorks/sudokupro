package com.xai.sudokupro.service.daily;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuGenerator;
import com.xai.sudokupro.model.api.DailyScore;
import com.xai.sudokupro.model.api.DailyStatus;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.service.GameEndListener;
import com.xai.sudokupro.service.GameLockManager;
import com.xai.sudokupro.service.GameService;
import com.xai.sudokupro.service.NotificationService;
import com.xai.sudokupro.util.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One shared puzzle per UTC day, playable by every player, with
 * consecutive-day streaks and a fastest-solve leaderboard.
 *
 * <p>Design: the day's TEMPLATE board is a regular {@code sudoku_boards} row
 * (gameId {@code daily-<date>}, playerId {@code __daily__}) whose grid rides
 * the cells_json snapshot — so every replica serves the same puzzle without
 * relying on seeded-RNG determinism (SecureRandom.setSeed is only additive on
 * some platforms). Creation races across replicas are settled by the existing
 * cross-replica {@link GameLockManager}. Each player then plays their own COPY
 * ({@code daily-<date>:<playerId>}) through the completely ordinary game
 * machinery — moves over WebSocket, saves, resumes all work unchanged.
 *
 * <p>Completion is detected by {@link GameService#endGame} (every solved game
 * passes through it) calling {@link #onGameEnded}; the ObjectProvider hookup on
 * that side breaks the constructor cycle between the two services.
 */
@Service
public class DailyPuzzleService implements GameEndListener {

    private static final Logger logger = LoggerFactory.getLogger(DailyPuzzleService.class);
    static final String DAILY_PREFIX = "daily-";
    static final String TEMPLATE_PLAYER = "__daily__";
    private static final Constants.Difficulty DAILY_DIFFICULTY = Constants.Difficulty.MEDIUM;
    /** Numeric difficulty on the 1-4 REST scale, matching Constants.Difficulty.MEDIUM. */
    private static final int DAILY_DIFFICULTY_LEVEL = 2;

    private final GameService gameService;
    private final GameRepository gameRepository;
    private final GameLockManager gameLocks;
    private final SudokuGenerator generator;
    private final DailyStateStore dailyState;
    private final NotificationService notificationService;
    private final Clock clock;

    public DailyPuzzleService(GameService gameService,
                              GameRepository gameRepository,
                              GameLockManager gameLocks,
                              SudokuGenerator generator,
                              DailyStateStore dailyState,
                              NotificationService notificationService,
                              Clock clock) {
        this.gameService = gameService;
        this.gameRepository = gameRepository;
        this.gameLocks = gameLocks;
        this.generator = generator;
        this.dailyState = dailyState;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    static String templateId(LocalDate date) {
        return DAILY_PREFIX + date;
    }

    static String playerGameId(LocalDate date, String playerId) {
        return templateId(date) + ":" + playerId;
    }

    /** The caller's relationship to today's puzzle. */
    public DailyStatus status(String playerId) {
        LocalDate date = today();
        boolean joined = gameRepository.findByGameId(playerGameId(date, playerId)) != null;
        return new DailyStatus(date.toString(), DAILY_DIFFICULTY_LEVEL, joined,
            dailyState.isCompleted(date, playerId), dailyState.getStreak(playerId, date));
    }

    /**
     * Joins today's puzzle: returns the caller's existing daily game if one
     * exists (in any state — resume semantics), otherwise stamps them a fresh
     * copy of the day's template.
     */
    public SudokuBoard joinDaily(String playerId) {
        LocalDate date = today();
        String gameId = playerGameId(date, playerId);
        try {
            return gameService.getGame(gameId);
        } catch (IllegalArgumentException notFound) {
            SudokuBoard template = templateForToday();
            SudokuBoard copy = copyOf(template, gameId, playerId);
            gameService.adoptGame(copy);
            logger.info("Player {} joined daily puzzle {}", playerId, templateId(date));
            return copy;
        }
    }

    /** Today's fastest solvers. */
    public List<DailyScore> leaderboard(int limit) {
        int capped = Math.max(1, Math.min(limit, 100));
        List<DailyScore> out = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<String, Long> e : dailyState.leaderboard(today(), capped)) {
            out.add(new DailyScore(rank++, e.getKey(), e.getValue()));
        }
        return out;
    }

    /**
     * Hook called by GameService.endGame for every finished game. Records the
     * completion (once) and advances the streak when the finished game is the
     * player's copy of today's puzzle and it is actually solved.
     */
    @Override
    public void onGameEnded(SudokuBoard board, String playerId) {
        LocalDate date = today();
        if (board == null || playerId == null) return;
        if (!playerGameId(date, playerId).equals(board.getGameId())) return;
        if (!board.isSolved()) return;

        long seconds = Math.max(0, board.getSolveTime().toSeconds());
        if (dailyState.recordCompletion(date, playerId, seconds)) {
            int streak = dailyState.getStreak(playerId, date);
            logger.info("Player {} completed daily {} in {}s — streak {}", playerId, date, seconds, streak);
            try {
                notificationService.sendTypedNotification(playerId, "DAILY",
                    "Daily puzzle solved in " + seconds + "s — " + streak + "-day streak!");
            } catch (Exception e) {
                logger.debug("Daily completion notification failed: {}", e.getMessage());
            }
        }
    }

    // ---- template management -------------------------------------------------

    /**
     * Loads today's template, creating and persisting it exactly once across
     * replicas (GameLockManager settles the race; losers re-read the winner's row).
     */
    private SudokuBoard templateForToday() {
        LocalDate date = today();
        String id = templateId(date);
        SudokuBoard template = gameRepository.findByGameId(id);
        if (template != null) return template;

        try (var lock = gameLocks.lock(id)) {
            template = gameRepository.findByGameId(id);
            if (template != null) return template;

            SudokuBoard generated = generator.generate(
                DAILY_DIFFICULTY, false, false, System.currentTimeMillis());
            generated.setGameId(id);
            generated.setPlayerId(TEMPLATE_PLAYER);
            generated.setDifficulty(DAILY_DIFFICULTY_LEVEL);
            gameRepository.save(generated); // fresh entity → @PrePersist snapshots the grid
            logger.info("Daily template {} generated and persisted", id);
            return generated;
        }
    }

    /** Stamps a per-player copy of the template's grid. */
    private SudokuBoard copyOf(SudokuBoard template, String gameId, String playerId) {
        return SudokuBoard.playerCopy(template, gameId, playerId);
    }
}
