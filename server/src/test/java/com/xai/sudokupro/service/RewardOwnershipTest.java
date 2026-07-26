package com.xai.sudokupro.service;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.economy.AchievementService;
import com.xai.sudokupro.service.economy.EconomyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Rewards go to the board's OWNER, never to whoever happens to end the game.
 *
 * <p>Both {@code GameEndListener}s carry the guard
 * {@code if (!playerId.equals(board.getPlayerId())) return;}. A mutation audit deleted it
 * from {@link EconomyService} and from {@link AchievementService} and the whole suite
 * stayed green — every existing test sets the board's player and then ends the game as
 * that same player, so the owner-differs-from-ender case is never constructed at all.
 *
 * <p>That matters because {@code endGame} is reachable with a {@code playerId} that is not
 * the owner: the controller's ownership check is a separate layer, and the listeners are
 * also invoked from the WebSocket close path and the shutdown flush. The guard is the last
 * line, so it needs a test of its own rather than relying on callers.
 */
class RewardOwnershipTest {

    private static final int HINT_COST = 5, STARTING_GEMS = 15, CLEAN_BONUS = 5;
    private static final Clock FIXED =
        Clock.fixed(Instant.parse("2026-07-25T12:00:00Z"), ZoneOffset.UTC);

    private final Map<String, User> users = new HashMap<>();
    private UserRepository repo;
    private EconomyService economy;
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
        lenient().when(repo.creditGemsAndXp(anyString(), anyInt(), anyInt())).thenAnswer(inv -> {
            User u = users.get(inv.<String>getArgument(0));
            if (u == null) return 0;
            u.setGems(u.getGems() + inv.<Integer>getArgument(1));
            u.addXp(inv.<Integer>getArgument(2));
            return 1;
        });
        lenient().when(repo.updateLevel(anyString(), anyInt())).thenReturn(1);

        economy = new EconomyService(repo, HINT_COST, STARTING_GEMS, CLEAN_BONUS);

        StringRedisTemplate downRedis = mock(StringRedisTemplate.class,
            inv -> { throw new RedisConnectionFailureException("down (test)"); });
        achievements = new AchievementService(
            new EconomyService(repo, HINT_COST, STARTING_GEMS, CLEAN_BONUS), repo,
            new com.xai.sudokupro.service.daily.DailyStateStore(downRedis),
            mock(NotificationService.class), FIXED);
    }

    /** A solved board belonging to `owner`. */
    private static SudokuBoard solvedBoardOwnedBy(String owner) {
        SudokuBoard board = new SudokuBoard(2, false, false, 0, "reward-" + owner);
        board.setPlayerId(owner);
        int[][] grid = new int[9][9];
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                grid[r][c] = board.getBoard()[r][c].getValue();
        assertTrue(fill(grid, 0), "test setup: the generated puzzle must be solvable");
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (board.getBoard()[r][c].getValue() == 0)
                    board.makeMove(r, c, grid[r][c], SudokuCell.MoveSource.PLAYER);
        assertTrue(board.isSolved(), "test setup: the board must be solved");
        return board;
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

    @Test
    void solvingYourOwnBoardPays() {
        users.put("owner", new User(null, "owner"));
        int before = users.get("owner").getGems();

        economy.onGameEnded(solvedBoardOwnedBy("owner"), "owner");

        assertTrue(users.get("owner").getGems() > before,
            "the board's owner must be paid for solving it");
    }

    /** The guard the mutation audit found unprotected. */
    @Test
    void endingSomeoneElsesSolvedBoardPaysNobody() {
        users.put("owner", new User(null, "owner"));
        users.put("bystander", new User(null, "bystander"));
        int ownerBefore = users.get("owner").getGems();
        int otherBefore = users.get("bystander").getGems();

        economy.onGameEnded(solvedBoardOwnedBy("owner"), "bystander");

        assertEquals(otherBefore, users.get("bystander").getGems(),
            "whoever ends a game must not be paid for a board they do not own");
        assertEquals(ownerBefore, users.get("owner").getGems(),
            "and the payout must not be redirected to the owner either — this listener "
                + "is invoked per-ender, so paying here would double-pay on a replay");
    }

    /** Same guard, second listener. Achievements are permanent, so a wrong unlock sticks. */
    @Test
    void endingSomeoneElsesSolvedBoardUnlocksNoAchievement() {
        users.put("owner", new User(null, "owner"));
        users.put("bystander", new User(null, "bystander"));

        achievements.onGameEnded(solvedBoardOwnedBy("owner"), "bystander");

        Map<String, Boolean> unlocked = users.get("bystander").getAchievements();
        boolean any = unlocked != null && unlocked.values().stream().anyMatch(Boolean::booleanValue);
        assertFalse(any,
            "a player who merely ended someone else's game must unlock nothing, got: " + unlocked);
    }

    @Test
    void solvingYourOwnBoardCanUnlockAnAchievement() {
        users.put("owner", new User(null, "owner"));

        achievements.onGameEnded(solvedBoardOwnedBy("owner"), "owner");

        Map<String, Boolean> unlocked = users.get("owner").getAchievements();
        assertNotNull(unlocked, "the owner's achievement map should have been touched");
    }

    /** The template pseudo-player must never earn, however a game ends. */
    @Test
    void theSharedTemplatePlayerNeverEarns() {
        users.put("__daily__", new User(null, "__daily__"));
        int before = users.get("__daily__").getGems();

        economy.onGameEnded(solvedBoardOwnedBy("__daily__"), "__daily__");

        assertEquals(before, users.get("__daily__").getGems(),
            "the daily/duel template owner is not a real player and must not accumulate gems");
    }

    /** An unsolved board pays nothing, even to its owner. */
    @Test
    void abandoningAnUnsolvedBoardPaysNothing() {
        users.put("owner", new User(null, "owner"));
        int before = users.get("owner").getGems();
        SudokuBoard unsolved = new SudokuBoard(2, false, false, 0, "unsolved");
        unsolved.setPlayerId("owner");

        economy.onGameEnded(unsolved, "owner");

        assertEquals(before, users.get("owner").getGems());
    }
}
