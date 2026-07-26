package com.xai.sudokupro.service.economy;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.NotificationService;
import com.xai.sudokupro.service.daily.DailyStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unlock conditions for {@link AchievementService}, with {@code SpeedDemon} and
 * {@code LevelUp} as the focus. Achievements are permanent and are read by the profile and
 * badge surfaces, so a wrong unlock cannot be taken back.
 *
 * <p>Defect classes this guards against:
 * <ul>
 *   <li><b>Nonsense solve times unlocking a speed badge.</b> {@code solveTime} is
 *       {@code Duration.between(startTime, now)} on a {@code @Transient} field that is
 *       rehydrated from a persisted column. A board whose clock never really ran (0s) or
 *       whose start stamp is in the future (negative duration) must not satisfy a
 *       "solved in under two minutes" badge — a bare {@code < 120} comparison awards it to
 *       both, because 0 and -60 are both under 120.</li>
 *   <li><b>Boundary drift on the threshold.</b> "Under two minutes" is exclusive: 119s
 *       earns it, 120s does not. Tests below use those two literals directly.</li>
 *   <li><b>Re-unlocking.</b> {@code onGameEnded} fires per finished game and is also
 *       reachable from the WebSocket close path, so an unlock that does not check the
 *       existing map would re-notify — and, for any future unlock that pays, re-pay —
 *       on every subsequent game.</li>
 *   <li><b>LevelUp firing at the wrong level.</b> {@code LevelUp} is documented as
 *       "reach level 5" and level is documented as {@code 1 + xp/100}, i.e. 400 xp. The
 *       tests pin both halves: the service's threshold against the level column, and the
 *       xp→level mapping the column is derived from.</li>
 * </ul>
 *
 * <p>Construction follows {@code RewardOwnershipTest}: real {@link User} objects behind a
 * map-backed repository mock, a fixed {@link Clock}, and a {@link DailyStateStore} over a
 * Redis mock that throws on every call so the store uses its in-memory fallback.
 */
class AchievementUnlockTest {

    private static final int HINT_COST = 5, STARTING_GEMS = 15, CLEAN_BONUS = 5;
    private static final Clock FIXED =
        Clock.fixed(Instant.parse("2026-07-25T12:00:00Z"), ZoneOffset.UTC);

    private final Map<String, User> users = new HashMap<>();
    private UserRepository repo;
    private NotificationService notifications;
    private AchievementService achievements;

    @BeforeEach
    void setUp() {
        repo = mock(UserRepository.class);
        lenient().when(repo.findByUsername(anyString()))
            .thenAnswer(inv -> Optional.ofNullable(users.get(inv.<String>getArgument(0))));
        lenient().when(repo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            users.put(u.getUsername(), u);
            return u;
        });
        lenient().when(repo.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            users.put(u.getUsername(), u);
            return u;
        });

        StringRedisTemplate downRedis = mock(StringRedisTemplate.class,
            inv -> { throw new RedisConnectionFailureException("down (test)"); });

        notifications = mock(NotificationService.class);
        achievements = new AchievementService(
            new EconomyService(repo, HINT_COST, STARTING_GEMS, CLEAN_BONUS), repo,
            new DailyStateStore(downRedis), notifications, FIXED);
    }

    private User player(String name) {
        User u = new User(null, name);
        users.put(name, u);
        return u;
    }

    private boolean unlocked(String name, String achievement) {
        return Boolean.TRUE.equals(users.get(name).getAchievements().get(achievement));
    }

    // =================================================================
    // SpeedDemon
    // =================================================================

    /** 119 seconds is inside "under two minutes" — the badge must be awarded. */
    @Test
    void speedDemonUnlocksOneSecondUnderTheThreshold() {
        player("swift");

        achievements.onGameEnded(solvedBoardTaking("swift", "game-swift", 119), "swift");

        assertTrue(unlocked("swift", "SpeedDemon"),
            "a 119-second solve is under two minutes and must earn SpeedDemon");
    }

    /**
     * The threshold is exclusive: exactly 120 seconds is NOT under two minutes.
     * Reproduction for a {@code <=} boundary slip.
     */
    @Test
    void speedDemonDoesNotUnlockExactlyAtTheThreshold() {
        player("bang-on");

        achievements.onGameEnded(solvedBoardTaking("bang-on", "game-bang", 120), "bang-on");

        assertFalse(unlocked("bang-on", "SpeedDemon"),
            "120 seconds is the threshold, not inside it");
    }

    @Test
    void speedDemonDoesNotUnlockWellOverTheThreshold() {
        player("plodder");

        achievements.onGameEnded(solvedBoardTaking("plodder", "game-plod", 3600), "plodder");

        assertFalse(unlocked("plodder", "SpeedDemon"));
    }

    /**
     * A board reporting a zero-second solve has no usable timing at all — that is the value
     * a board carries when its transient duration was never populated, not evidence of a
     * superhuman solve. It must not earn a speed badge.
     */
    @Test
    void speedDemonDoesNotUnlockOnAZeroSecondSolve() {
        player("instant");

        SudokuBoard board = solvedBoardTaking("instant", "game-instant", 0);
        assertEquals(0, board.getSolveTime().toSeconds(), "test setup: a zero-second solve");

        achievements.onGameEnded(board, "instant");

        assertFalse(unlocked("instant", "SpeedDemon"),
            "a zero-second solve is impossible and must not unlock SpeedDemon");
    }

    /**
     * A start stamp in the future (clock skew across pods, or a doctored resume) makes
     * {@code Duration.between} negative. Negative is "less than 120" too, so a bare
     * upper-bound comparison hands out the badge for a solve that never happened.
     */
    @Test
    void speedDemonDoesNotUnlockOnANegativeSolveTime() {
        player("timelord");

        SudokuBoard board = solvedBoardTaking("timelord", "game-time", -60);
        assertTrue(board.getSolveTime().toSeconds() < 0,
            "test setup: the board must report a negative solve time, got "
                + board.getSolveTime());

        achievements.onGameEnded(board, "timelord");

        assertFalse(unlocked("timelord", "SpeedDemon"),
            "a negative solve time must not unlock SpeedDemon");
    }

    /**
     * Unlocks are once-only. A second fast solve must not re-announce (nor, for any unlock
     * that later becomes paid, re-pay) an achievement the player already holds.
     */
    @Test
    void anAchievementIsNeverUnlockedTwice() {
        player("swift");

        achievements.onGameEnded(solvedBoardTaking("swift", "game-1", 30), "swift");
        assertTrue(unlocked("swift", "SpeedDemon"));
        // CleanSolver rides along on the same solve — both are new on this first game.
        verify(notifications, times(2)).sendTypedNotification(eq("swift"), eq("ACHIEVEMENT"), anyString());

        achievements.onGameEnded(solvedBoardTaking("swift", "game-2", 30), "swift");
        achievements.onGameEnded(solvedBoardTaking("swift", "game-3", 30), "swift");

        verifyNoMoreInteractions(notifications);
        verify(repo, times(1)).save(any(User.class));
    }

    // =================================================================
    // LevelUp
    // =================================================================

    /**
     * The persisted {@code level} column is what the service reads, and it is written by
     * {@code UserRepository.updateLevel} from {@code 1 + xp/100}. Level 4 (399 xp) must not
     * satisfy a "reach level 5" badge.
     */
    @Test
    void levelUpDoesNotFireBelowLevelFive() {
        User user = player("climber");
        user.setXp(399);
        assertEquals(4, user.getLevel(), "test setup: 399 xp is level 4 under 1 + xp/100");

        achievements.onGameEnded(solvedBoardTaking("climber", "game-climb", 300), "climber");

        assertFalse(unlocked("climber", "LevelUp"),
            "LevelUp is 'reach level 5'; level 4 must not earn it");
    }

    /**
     * Level 5 — 400 xp — earns it.
     *
     * <p>The level is placed before the xp on purpose. {@code User.updateLevel} only fires
     * its own internal achievement bookkeeping when the level actually RISES, and that
     * bookkeeping pays 100 xp, which re-enters {@code addXp} and overshoots. Setting the
     * column first and the xp second reproduces the row exactly as the database holds it
     * after {@code creditGemsAndXp} + {@code updateLevel}, which is the state the service
     * really observes.
     */
    @Test
    void levelUpFiresAtLevelFive() {
        User user = player("veteran");
        user.setLevel(5);
        user.setXp(400);
        assertEquals(5, user.getLevel(), "test setup: 400 xp is level 5 under 1 + xp/100");
        assertFalse(unlocked("veteran", "LevelUp"), "test setup: not yet awarded");

        achievements.onGameEnded(solvedBoardTaking("veteran", "game-vet", 300), "veteran");

        assertTrue(unlocked("veteran", "LevelUp"), "level 5 must earn LevelUp");
    }

    /**
     * The xp→level contract the badge threshold rests on, stated as concrete pairs rather
     * than recomputed. 100 xp is the first level-up; 199 is still level 2; 200 is level 3;
     * 399 is level 4 (so 400 is the first level that can earn the badge).
     *
     * <p>Stops at 399 deliberately: reaching level 5 through {@code addXp} triggers
     * {@code User}'s own achievement payout, which credits further xp and moves the level
     * again. That re-entrancy belongs to the model, not to this service.
     */
    @Test
    void theXpToLevelMappingMatchesTheDocumentedFormula() {
        assertEquals(1, xpToLevel(0));
        assertEquals(1, xpToLevel(99));
        assertEquals(2, xpToLevel(100), "100 xp is the first level-up");
        assertEquals(2, xpToLevel(199));
        assertEquals(3, xpToLevel(200));
        assertEquals(4, xpToLevel(399), "level 4 — one short of the LevelUp badge");
    }

    private static int xpToLevel(int xp) {
        User u = new User(null, "probe-" + xp);
        u.setXp(xp);
        return u.getLevel();
    }

    // =================================================================
    // the conditions that ride along on the same call
    // =================================================================

    @Test
    void cleanSolverNeedsAHintFreeSolve() {
        player("hinter");
        SudokuBoard board = solvedBoardTaking("hinter", "game-hint", 300);
        board.setHintCount(1);

        achievements.onGameEnded(board, "hinter");

        assertFalse(unlocked("hinter", "CleanSolver"),
            "a solve that took a hint is not a clean solve");
    }

    @Test
    void dailyPlayerNeedsADailyBoard() {
        player("casual");
        player("regular");

        achievements.onGameEnded(solvedBoardTaking("casual", "game-casual", 300), "casual");
        achievements.onGameEnded(
            solvedBoardTaking("regular", "daily-2026-07-25:regular", 300), "regular");

        assertFalse(unlocked("casual", "DailyPlayer"), "an ordinary game is not the daily");
        assertTrue(unlocked("regular", "DailyPlayer"));
    }

    /** Nothing at all is unlocked for a board that was never finished. */
    @Test
    void anUnsolvedBoardUnlocksNothing() {
        player("quitter");
        SudokuBoard abandoned = new SudokuBoard(2, false, false, 0, "daily-2026-07-25:quitter");
        abandoned.setPlayerId("quitter");

        achievements.onGameEnded(abandoned, "quitter");

        Map<String, Boolean> map = users.get("quitter").getAchievements();
        assertFalse(map.values().stream().anyMatch(Boolean::booleanValue),
            "an abandoned board must unlock nothing, got: " + map);
        verifyNoInteractions(notifications);
    }

    // =================================================================
    // helpers — test scaffolding only; no production logic is reproduced here
    // =================================================================

    /**
     * A solved board that reports {@code seconds} on the clock.
     *
     * <p>The board is filled to one cell short, its start stamp is then moved by reflection,
     * and only then is the last move played — so the duration is produced by the production
     * {@code Duration.between(startTime, now)} path rather than written in by the test. A
     * negative {@code seconds} puts the start stamp in the future.
     */
    private static SudokuBoard solvedBoardTaking(String owner, String gameId, long seconds) {
        SudokuBoard board = new SudokuBoard(2, false, false, 0, gameId);
        board.setPlayerId(owner);

        int[][] grid = new int[9][9];
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                grid[r][c] = board.getBoard()[r][c].getValue();
        assertTrue(fill(grid, 0), "test setup: the generated puzzle must be solvable");

        List<int[]> blanks = new ArrayList<>();
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (board.getBoard()[r][c].getValue() == 0) blanks.add(new int[]{r, c});
        assertFalse(blanks.isEmpty(), "test setup: the puzzle must have empty cells");

        for (int i = 0; i < blanks.size() - 1; i++) {
            int[] cell = blanks.get(i);
            board.makeMove(cell[0], cell[1], grid[cell[0]][cell[1]], SudokuCell.MoveSource.PLAYER);
        }
        setStartTime(board, LocalDateTime.now().minusSeconds(seconds));
        int[] last = blanks.get(blanks.size() - 1);
        board.makeMove(last[0], last[1], grid[last[0]][last[1]], SudokuCell.MoveSource.PLAYER);

        assertTrue(board.isSolved(), "test setup: the board must be solved");
        return board;
    }

    private static void setStartTime(SudokuBoard board, LocalDateTime when) {
        try {
            Field f = SudokuBoard.class.getDeclaredField("startTime");
            f.setAccessible(true);
            f.set(board, when);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("test setup: cannot set startTime", e);
        }
    }

    private static boolean fill(int[][] g, int idx) {
        if (idx == 81) return true;
        int r = idx / 9, c = idx % 9;
        if (g[r][c] != 0) return fill(g, idx + 1);
        for (int v = 1; v <= 9; v++) {
            if (!legal(g, r, c, v)) continue;
            g[r][c] = v;
            if (fill(g, idx + 1)) return true;
            g[r][c] = 0;
        }
        return false;
    }

    private static boolean legal(int[][] g, int r, int c, int v) {
        for (int i = 0; i < 9; i++) if (g[r][i] == v || g[i][c] == v) return false;
        int br = (r / 3) * 3, bc = (c / 3) * 3;
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (g[br + i][bc + j] == v) return false;
        return true;
    }
}
