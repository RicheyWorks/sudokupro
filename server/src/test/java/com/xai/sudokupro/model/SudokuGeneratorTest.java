package com.xai.sudokupro.model;

import com.xai.sudokupro.service.AISolverService;
import com.xai.sudokupro.util.Constants;
import com.xai.sudokupro.util.SecureRandomGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Core-engine tests (AUDIT P1-1): generated puzzles must be valid, have exactly one
 * solution, honor the difficulty's clue count, and be solvable by the AI solver.
 */
class SudokuGeneratorTest {

    private SudokuGenerator generator;
    private AISolverService solver;

    @BeforeEach
    void setUp() {
        SecureRandomGenerator rng = new SecureRandomGenerator(new SimpleMeterRegistry());
        generator = new SudokuGenerator(rng);
        solver = new AISolverService(rng);
    }

    @Test
    void generatedEasyBoardIsValidAndHasUniqueSolution() {
        SudokuBoard board = generator.generate(Constants.Difficulty.EASY, false, false, 42L);

        assertNotNull(board.getGameId(), "Board must carry a real gameId");
        assertTrue(board.isValidBoardState(), "Generated board must have no rule conflicts");
        // validateBoard re-checks validity AND counts solutions — must be exactly one.
        assertTrue(generator.validateBoard(board.getBoard()),
            "Generated board must have exactly one solution");
    }

    @Test
    void easyDifficultyRemovesExpectedCellCount() {
        SudokuBoard board = generator.generate(Constants.Difficulty.EASY, false, false, 7L);

        int empty = 0;
        SudokuCell[][] cells = board.getBoard();
        for (int i = 0; i < 9; i++)
            for (int j = 0; j < 9; j++)
                if (cells[i][j].getValue() == 0) empty++;

        assertEquals(Constants.Difficulty.EASY.cellsRemoved, empty,
            "EASY must remove exactly " + Constants.Difficulty.EASY.cellsRemoved + " cells");
    }

    @Test
    void solverCompletesGeneratedBoard() {
        SudokuBoard board = generator.generate(Constants.Difficulty.MEDIUM, false, false, 99L);

        assertTrue(solver.solveSudoku(board), "Solver must solve a generated MEDIUM board");
        assertTrue(board.isSolved(), "Board must report solved after solver completes");
        assertTrue(board.isValidBoardState(), "Solved board must satisfy all Sudoku rules");
    }

    @Test
    void solverPreservesGivens() {
        SudokuBoard board = generator.generate(Constants.Difficulty.EASY, false, false, 5L);

        // Snapshot the given cells before solving.
        int[][] givens = new int[9][9];
        SudokuCell[][] cells = board.getBoard();
        for (int i = 0; i < 9; i++)
            for (int j = 0; j < 9; j++)
                givens[i][j] = cells[i][j].isGiven() ? cells[i][j].getValue() : -1;

        assertTrue(solver.solveSudoku(board));

        for (int i = 0; i < 9; i++)
            for (int j = 0; j < 9; j++)
                if (givens[i][j] != -1)
                    assertEquals(givens[i][j], cells[i][j].getValue(),
                        "Solver must not change given at (" + i + "," + j + ")");
    }
}
