package com.xai.sudokupro.service;

import com.xai.sudokupro.model.SudokuBoard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AISolverServiceTest {
    private AISolverService solverService;
    private SudokuBoard board;

    @BeforeEach
    void setUp() {
        board = new SudokuBoard(1); // Easy difficulty
        solverService = new AISolverService(board);
    }

    @Test
    void testSolveSudoku() {
        assertTrue(solverService.solveSudoku(), "Solver should solve the board");
    }

    @Test
    void testGetNextLogicalMove() {
        String hint = solverService.getNextLogicalMove();
        assertNotNull(hint, "Hint should not be null");
        assertFalse(hint.isEmpty(), "Hint should provide some guidance");
    }
} 
