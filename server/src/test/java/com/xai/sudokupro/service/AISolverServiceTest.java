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
}
