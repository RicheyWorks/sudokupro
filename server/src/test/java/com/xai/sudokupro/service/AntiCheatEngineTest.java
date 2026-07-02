package com.xai.sudokupro.service;

import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Anti-cheat scoring tests (AUDIT P1-1), including the regression for the bug where
 * detectCheating was called on unsolved boards (solveTime=0) and blocked every move.
 */
@ExtendWith(MockitoExtension.class)
class AntiCheatEngineTest {

    @Mock private AnalyticsService analyticsService;
    @Mock private UserRepository userRepository;
    @Mock private GameRepository gameRepository;

    private AntiCheatEngine engine;

    @BeforeEach
    void setUp() {
        engine = new AntiCheatEngine(analyticsService, userRepository, gameRepository);
    }

    @Test
    void unsolvedBoardIsNeverFlagged() {
        // Regression: solveTime=0 (Duration.ZERO, board not solved yet) used to satisfy
        // "0 < difficulty * 10s" and flag every in-progress game as cheating.
        assertFalse(engine.detectCheating(0, 3), "solveTime=0 means unsolved — never a cheat signal");
        assertFalse(engine.detectCheating(-1, 3), "negative solveTime must not flag");
    }

    @Test
    void implausiblyFastSolveIsFlagged() {
        // Threshold is difficulty * 10_000ms; difficulty 3 → 30s.
        assertTrue(engine.detectCheating(5_000, 3), "5s solve on difficulty 3 is implausible");
        assertFalse(engine.detectCheating(45_000, 3), "45s solve on difficulty 3 is plausible");
    }

    @Test
    void cosmicStreakTracksConsecutiveCosmicMovesOnly() {
        engine.recordMove("p1", true);
        engine.recordMove("p1", true);
        engine.recordMove("p1", true);
        assertEquals(3, engine.getCosmicStreaks().get("p1"));

        engine.recordMove("p1", false);
        assertEquals(0, engine.getCosmicStreaks().get("p1"),
            "A non-cosmic move must reset the cosmic streak");
    }

    @Test
    void moveRateCountsMovesInWindow() {
        for (int i = 0; i < 5; i++) engine.recordMove("p2", false);
        assertEquals(5, engine.getMoveRates().get("p2"), "All 5 moves fall in one 60s window");
    }

    @Test
    void flagPlayerHalvesCosmicDripForRealUsers() {
        com.xai.sudokupro.model.User user = new com.xai.sudokupro.model.User(42L, "richmond");
        user.setCosmicDrip(80);
        org.mockito.Mockito.when(userRepository.findById(42L))
            .thenReturn(java.util.Optional.of(user));

        engine.flagPlayer("42");

        assertEquals(40, user.getCosmicDrip(), "Flagging must halve the player's cosmic drip");
        org.mockito.Mockito.verify(userRepository).save(user);
    }

    @Test
    void flagPlayerIgnoresAnonymousAndNonNumericIds() {
        engine.flagPlayer("anonymous");
        engine.flagPlayer("player_abc123");
        engine.flagPlayer(null);

        org.mockito.Mockito.verifyNoInteractions(userRepository);
    }
}
