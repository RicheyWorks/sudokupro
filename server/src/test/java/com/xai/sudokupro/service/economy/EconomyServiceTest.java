package com.xai.sudokupro.service.economy;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.AISolverService;
import com.xai.sudokupro.util.SecureRandomGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Hint economy: wallets auto-provision, hints charge, solves pay. */
class EconomyServiceTest {

    private static final int HINT_COST = 5;
    private static final int STARTING_GEMS = 15;
    private static final int CLEAN_BONUS = 5;

    private final Map<String, User> users = new ConcurrentHashMap<>();
    private EconomyService economy;

    @BeforeEach
    void setUp() {
        UserRepository repo = mock(UserRepository.class);
        lenient().when(repo.findByUsername(anyString()))
            .thenAnswer(inv -> Optional.ofNullable(users.get(inv.<String>getArgument(0))));
        lenient().when(repo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            users.put(u.getUsername(), u);
            return u;
        });
        // walletFor provisions via saveAndFlush, not save: the flush is what makes the
        // users.username unique-constraint violation surface inside walletFor's own
        // try/catch (so the loser of a provisioning race can re-read the winner's row)
        // rather than at transaction commit, where nothing would handle it.
        lenient().when(repo.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            users.put(u.getUsername(), u);
            return u;
        });
        // Solve rewards now credit atomically in SQL rather than read-modify-write, so the
        // in-memory double has to emulate the UPDATE. The old form silently reverted any
        // concurrent hint charge that committed between the read and the save.
        lenient().when(repo.creditGemsAndXp(anyString(), anyInt(), anyInt())).thenAnswer(inv -> {
            User u = users.get(inv.<String>getArgument(0));
            if (u == null) return 0;
            u.setGems(u.getGems() + inv.<Integer>getArgument(1));
            u.addXp(inv.<Integer>getArgument(2));
            return 1;
        });
        lenient().when(repo.updateLevel(anyString(), anyInt())).thenReturn(1);
        lenient().when(repo.creditPoints(anyString(), anyInt())).thenAnswer(inv -> {
            User u = users.get(inv.<String>getArgument(0));
            if (u == null) return 0;
            u.setPoints(u.getPoints() + inv.<Integer>getArgument(1));
            return 1;
        });
        // Emulate the atomic conditional UPDATE: decrement only if affordable, and do it
        // under a lock so the mock behaves like the single SQL statement it stands in for.
        lenient().when(repo.deductGemsIfAffordable(anyString(), org.mockito.ArgumentMatchers.anyInt()))
            .thenAnswer(inv -> {
                String name = inv.getArgument(0);
                int cost = inv.getArgument(1);
                synchronized (users) {
                    User u = users.get(name);
                    if (u == null || u.getGems() < cost) return 0;
                    u.setGems(u.getGems() - cost);
                    return 1;
                }
            });
        economy = new EconomyService(repo, HINT_COST, STARTING_GEMS, CLEAN_BONUS);
    }

    @Test
    void firstTouchProvisionsAWalletWithTheSigningBonus() {
        User wallet = economy.walletFor("richmond");
        assertEquals(STARTING_GEMS, wallet.getGems());
        // Second touch reuses the same wallet, no double bonus.
        assertSame(wallet, economy.walletFor("richmond"));
    }

    @Test
    void hintsChargeUntilTheWalletRunsDry() {
        assertEquals(STARTING_GEMS - HINT_COST, economy.chargeForHint("richmond"));
        assertEquals(STARTING_GEMS - 2 * HINT_COST, economy.chargeForHint("richmond"));
        assertEquals(0, economy.chargeForHint("richmond"));

        InsufficientGemsException broke = assertThrows(InsufficientGemsException.class,
            () -> economy.chargeForHint("richmond"));
        assertEquals(0, broke.balance());
        assertEquals(HINT_COST, broke.cost());
        assertEquals(0, users.get("richmond").getGems(), "failed charge must not go negative");
    }

    @Test
    void solvingPaysDifficultyScaledGemsPlusCleanBonus() {
        SudokuBoard clean = solvedBoard("g-clean", "richmond", 3);
        economy.onGameEnded(clean, "richmond");

        assertEquals(STARTING_GEMS + 3 * 10 + CLEAN_BONUS, users.get("richmond").getGems());
        assertTrue(users.get("richmond").getXp() > 0, "solves must grant XP too");
    }

    /**
     * Solving must credit leaderboard POINTS, from the per-tier table Constants has always
     * carried.
     *
     * <p>{@code User.points} had no production writer at all: the column existed from the
     * baseline schema, five configurable points-per-solve values sat unread in Constants,
     * the public leaderboard ordered by the column, and the anti-cheat scheduler queried
     * for players above a points threshold — but no code path ever added a point. Everyone
     * held 0, so ORDER BY points fell through to the id tie-break: the "top players" board
     * was literally the oldest accounts, every one of them tier "Unranked".
     */
    @Test
    void solvingCreditsLeaderboardPointsPerDifficultyTier() {
        economy.onGameEnded(solvedBoard("g-hard", "richmond", 3), "richmond");
        assertEquals(com.xai.sudokupro.util.Constants.POINTS_PER_SOLVE_HARD,
            users.get("richmond").getPoints(),
            "a tier-3 solve pays the HARD points value");

        economy.onGameEnded(solvedBoard("g-night", "richmond", 5), "richmond");
        assertEquals(com.xai.sudokupro.util.Constants.POINTS_PER_SOLVE_HARD
                + com.xai.sudokupro.util.Constants.POINTS_PER_SOLVE_NIGHTMARE,
            users.get("richmond").getPoints(),
            "points accumulate across solves — they are the leaderboard's ranking currency");
    }

    /**
     * An out-of-range difficulty clamps into the 1..5 tier table rather than scaling past
     * it. Difficulty is a plain int column, and at least one dormant producer (the puzzle
     * editor's 0-10 estimate) writes values outside the tier range; a clamp keeps a
     * mis-scaled row from paying a premium over NIGHTMARE.
     */
    @Test
    void outOfRangeDifficultyClampsIntoTheTierTable() {
        economy.onGameEnded(solvedBoard("g-weird", "richmond", 9), "richmond");
        assertEquals(com.xai.sudokupro.util.Constants.POINTS_PER_SOLVE_NIGHTMARE,
            users.get("richmond").getPoints());
    }

    @Test
    void hintedSolvesForfeitTheCleanBonus() {
        SudokuBoard hinted = solvedBoard("g-hinted", "richmond", 2);
        hinted.incrementHintCount();

        economy.onGameEnded(hinted, "richmond");

        assertEquals(STARTING_GEMS + 2 * 10, users.get("richmond").getGems());
    }

    @Test
    void unsolvedGamesAndPseudoPlayersEarnNothing() {
        SudokuBoard abandoned = new SudokuBoard(2, false, false, 0, "g-quit");
        economy.onGameEnded(abandoned, "richmond"); // not solved

        SudokuBoard template = solvedBoard("daily-2026-07-16", "__daily__", 2);
        economy.onGameEnded(template, "__daily__");

        assertFalse(users.containsKey("richmond"), "no wallet should be touched for unsolved games");
        assertFalse(users.containsKey("__daily__"), "template pseudo-players never earn");
    }

    private SudokuBoard solvedBoard(String gameId, String playerId, int difficulty) {
        SudokuBoard board = new SudokuBoard(1, false, false, 0, gameId);
        board.setPlayerId(playerId);
        board.setDifficulty(difficulty);
        new AISolverService(new SecureRandomGenerator(new SimpleMeterRegistry())).solveSudoku(board);
        assertTrue(board.isSolved());
        return board;
    }

    /**
     * Regression: concurrent hint charges must never oversell the wallet.
     *
     * <p>{@code chargeForHint} was a read-modify-write, and hint charging is serialized
     * only by the PER-GAME lock — so requests against DIFFERENT games never contended and
     * all wrote back the same decremented balance. Verified live against a running server:
     * six concurrent hints across six games were all served for a total of 15 gems instead
     * of 30, i.e. six hints for the price of three. The charge is now a single atomic
     * conditional UPDATE.
     */
    @Test
    void concurrentHintChargesCannotOversellTheWallet() throws Exception {
        economy.walletFor("racer");                       // 15 gems, hint costs 5 -> 3 hints
        int attempts = 12;
        java.util.concurrent.atomic.AtomicInteger served = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(attempts);

        for (int i = 0; i < attempts; i++) {
            new Thread(() -> {
                try {
                    go.await();
                    economy.chargeForHint("racer");
                    served.incrementAndGet();
                } catch (InsufficientGemsException expected) {
                    // correct outcome once the wallet is empty
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        go.countDown();
        assertTrue(done.await(15, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(STARTING_GEMS / HINT_COST, served.get(),
            "exactly " + (STARTING_GEMS / HINT_COST) + " hints are affordable, no matter the concurrency");
        assertEquals(0, users.get("racer").getGems(), "balance must land exactly on zero");
        assertTrue(users.get("racer").getGems() >= 0, "balance must never go negative");
    }
}
