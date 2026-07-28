package com.xai.sudokupro.service;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.util.SecureRandomGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AISolverServiceTest {

    private AISolverService solverService;
    private SudokuBoard board;

    @BeforeEach
    void setUp() {
        // SimpleMeterRegistry is an in-memory no-op registry — no external infra needed.
        SecureRandomGenerator rng = new SecureRandomGenerator(new SimpleMeterRegistry());
        solverService = new AISolverService(rng);
        // Use the full 5-arg constructor; difficulty=1 (Easy), no special modes.
        board = new SudokuBoard(1, false, false, 0L, "test-game");
    }

    @Test
    void testSolveSudoku() {
        assertTrue(solverService.solveSudoku(board), "Solver should be able to solve an Easy board");
    }

    @Test
    void testGetNextLogicalMove() {
        String hint = solverService.getNextLogicalMove(board);
        assertNotNull(hint, "Hint should not be null");
        assertFalse(hint.isEmpty(), "Hint should provide some guidance");
    }

    /**
     * The health probe must be read-only against the solver's shared state.
     *
     * <p>{@code getNextLogicalMoveForTestBoard} used to delegate to the real hint path,
     * which records every answer into the process-global {@code recentHints} window (10
     * entries) and the {@code cosmicHotspots} tie-break map. Kubernetes probes readiness
     * every 10 s and liveness every 20 s through this method — about nine calls a minute
     * per pod — so health traffic turned the entire de-duplication window over roughly
     * every seventy seconds and steadily biased the hotspot weights toward whatever
     * coordinates the probe's throwaway board produced. Real players' hints were being
     * filtered and re-ranked by the load balancer's heartbeat.
     */
    @Test
    void theHealthProbeDoesNotPolluteSharedHintState() {
        for (int i = 0; i < 25; i++) {
            String hint = solverService.getNextLogicalMoveForTestBoard();
            assertNotNull(hint, "the probe must still prove the solver is alive");
        }

        assertTrue(solverService.getCosmicHotspotMap().isEmpty(),
            "twenty-five health checks must leave the hotspot weights untouched");

        // And a real board still gets a hint — the probe has not consumed the
        // de-duplication window that real hints share.
        String realHint = solverService.getNextLogicalMove(board);
        assertNotNull(realHint);
        assertFalse(realHint.isEmpty());
    }
}
