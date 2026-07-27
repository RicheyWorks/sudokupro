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

    // ---- archive (past dailies stay playable — untimed, no streak credit) -----

    /** Dates with a playable archived template, newest first (today included). */
    public List<String> archiveDates(int limit) {
        int capped = Math.max(1, Math.min(limit, 60));
        List<String> out = new ArrayList<>();
        // Filter by prefix in SQL, not after the LIMIT. Weekly-tournament templates share
        // TEMPLATE_PLAYER, so paging the owner and discarding non-daily rows in Java spent
        // the limit on rows that were then thrown away — see findByPlayerIdAndGameIdPrefix.
        for (SudokuBoard template : gameRepository.findByPlayerIdAndGameIdPrefix(
                TEMPLATE_PLAYER, DAILY_PREFIX,
                org.springframework.data.domain.PageRequest.of(0, capped))) {
            String gameId = template.getGameId();
            if (gameId != null && gameId.startsWith(DAILY_PREFIX)) {
                out.add(gameId.substring(DAILY_PREFIX.length()));
            }
        }
        return out;
    }

    /**
     * Plays an archived daily. The copy's gameId ({@code daily-<date>:archive:<player>})
     * deliberately never matches {@link #playerGameId}, so archive solves earn
     * gems but never streak credit or a leaderboard slot — replaying the past
     * shouldn't rewrite it.
     */
    public SudokuBoard joinArchive(String playerId, LocalDate date) {
        if (date.isAfter(today())) {
            throw new IllegalArgumentException("No puzzle exists yet for " + date);
        }
        // TODAY is not archive material, and allowing it was a solution oracle: the
        // archive copy is a throwaway (its id shape earns no streak or leaderboard
        // credit), so a player could take an archive copy of today's puzzle, auto-solve
        // THAT, read the completed grid off the response, then join the real daily and
        // type in the answers for a top leaderboard time.
        if (!date.isBefore(today())) {
            throw new IllegalArgumentException(
                "Today's puzzle is played through /api/daily/join, not the archive");
        }
        String gameId = templateId(date) + ":archive:" + playerId;
        try {
            return gameService.getGame(gameId);
        } catch (IllegalArgumentException notFound) {
            SudokuBoard template = gameRepository.findByGameId(templateId(date));
            if (template == null) {
                throw new IllegalArgumentException("No archived puzzle for " + date);
            }
            SudokuBoard copy = SudokuBoard.playerCopy(template, gameId, playerId);
            gameService.adoptGame(copy);
            logger.info("Player {} joined archived daily {}", playerId, date);
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
     * How stale a daily board may be and still earn credit. One day: a puzzle joined
     * on day N and finished at 00:01 on day N+1 is the case this exists for, and it is
     * also exactly the window {@code DailyStateStore.getStreak} calls a streak "alive"
     * (last completion today or yesterday), so credit can never land on a day the
     * streak model has already written off. It sits inside the store's 3-day key TTL
     * too, so a completion cannot resurrect an expired leaderboard.
     */
    private static final int CREDIT_GRACE_DAYS = 1;

    /**
     * Hook called by GameService.endGame for every finished game. Records the
     * completion (once) and advances the streak when the finished game is the
     * player's copy of a daily puzzle and it is actually solved.
     *
     * <p><b>The date comes from the board, not the clock.</b> This used to recompute
     * {@code today()} at solve time and require it to match the id the board was
     * stamped with at join time, so a puzzle joined at 23:58 and solved at 00:01 was
     * compared against the NEXT day's id, matched nothing, and returned here silently —
     * discarding the completion, the streak advance, the leaderboard entry and the
     * notification, for the player's most impressive category of solve. Every daily
     * test runs on a {@code Clock.fixed}, so the boundary was structurally invisible
     * to the suite. The board's own id is the authoritative statement of which puzzle
     * it is; the clock only decides whether that puzzle is still current enough to
     * score, and a board too old to score now says so in the log instead of vanishing.
     *
     * <p>Back-dating within the grace window is safe for the leaderboard because the
     * score is {@code solveTime} — the board's own elapsed timer — so a straggler ranks
     * by how long they actually took, not by when they happened to finish.
     */
    @Override
    public void onGameEnded(SudokuBoard board, String playerId) {
        if (board == null || playerId == null) return;
        if (!board.isSolved()) return;

        LocalDate puzzleDate = puzzleDateOf(board.getGameId(), playerId);
        if (puzzleDate == null) return; // not this player's daily copy (archive, weekly, ordinary game)

        LocalDate today = today();
        if (puzzleDate.isAfter(today)) {
            logger.warn("Daily board {} claims a future date — no credit", board.getGameId());
            return;
        }
        if (puzzleDate.isBefore(today.minusDays(CREDIT_GRACE_DAYS))) {
            logger.info("Player {} finished daily {} on {} — past the {}-day credit window, "
                + "gems only (no streak or leaderboard)", playerId, puzzleDate, today, CREDIT_GRACE_DAYS);
            return;
        }

        long seconds = Math.max(0, board.getSolveTime().toSeconds());
        if (dailyState.recordCompletion(puzzleDate, playerId, seconds)) {
            int streak = dailyState.getStreak(playerId, today);
            logger.info("Player {} completed daily {} in {}s — streak {}", playerId, puzzleDate, seconds, streak);
            try {
                notificationService.sendTypedNotification(playerId, "DAILY",
                    "Daily puzzle solved in " + seconds + "s — " + streak + "-day streak!");
            } catch (Exception e) {
                logger.debug("Daily completion notification failed: {}", e.getMessage());
            }
        }
    }

    /**
     * The date of the daily puzzle this board <em>is</em>, or null when the board is
     * not {@code playerId}'s own copy of a daily.
     *
     * <p>Deliberately exact rather than a {@code startsWith} test. The archive copy
     * ({@code daily-<date>:archive:<player>}) and another player's copy both begin with
     * the daily prefix and must not earn streak or leaderboard credit; requiring the
     * segment after the date to equal the player id excludes both by construction.
     */
    public static LocalDate puzzleDateOf(String gameId, String playerId) {
        if (gameId == null || playerId == null || !gameId.startsWith(DAILY_PREFIX)) return null;
        String rest = gameId.substring(DAILY_PREFIX.length());
        int sep = rest.indexOf(':');
        if (sep < 0) return null;                                   // the shared template itself
        if (!rest.substring(sep + 1).equals(playerId)) return null; // archive copy, or not theirs
        try {
            return LocalDate.parse(rest.substring(0, sep));
        } catch (java.time.format.DateTimeParseException e) {
            return null;
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
