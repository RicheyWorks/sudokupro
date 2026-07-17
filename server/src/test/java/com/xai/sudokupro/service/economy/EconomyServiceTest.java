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
}
