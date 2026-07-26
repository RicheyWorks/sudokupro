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

    /**
     * Regression: EVERY difficulty tier must actually produce a puzzle.
     *
     * <p>HARD asked for 60 removals (21 clues), which single-cell digging can never reach —
     * an exhaustive shuffled sweep tops out at 55-58 removals — so HARD generation failed
     * 100% of the time (measured 0/20), surfacing as {@code Failed to remove enough cells}
     * and EventEngine's "Cosmic duel failed to erupt". EXTREME (70) and NIGHTMARE (80) left
     * 11 and 1 clues, below the proven 17-clue minimum for a unique solution, so they were
     * impossible by construction. The removal loop is now a shuffled single pass over all
     * 81 cells and the ladder sits inside the achievable range.
     */
    @Test
    void everyDifficultyTierGeneratesAUniquelySolvablePuzzle() {
        for (Constants.Difficulty difficulty : Constants.Difficulty.values()) {
            for (int seed = 0; seed < 3; seed++) {
                final Constants.Difficulty d = difficulty;
                final long s = seed;
                SudokuBoard board = assertDoesNotThrow(
                    () -> generator.generate(d, false, false, s),
                    () -> d + " (remove " + d.cellsRemoved + ") must be generatable, seed " + s);

                int empty = 0;
                SudokuCell[][] grid = board.getBoard();
                for (int r = 0; r < 9; r++)
                    for (int c = 0; c < 9; c++)
                        if (grid[r][c].getValue() == 0) empty++;

                assertEquals(difficulty.cellsRemoved, empty,
                    difficulty + " must clear exactly its configured cell count");
                assertTrue(81 - empty >= 17,
                    difficulty + " must leave at least the 17 clues a unique solution requires");
                assertTrue(generator.validateBoard(grid),
                    difficulty + " must still have a unique solution");
            }
        }
    }

    /** The ladder must stay strictly increasing, or the tiers stop meaning anything. */
    @Test
    void difficultyLadderIsStrictlyIncreasing() {
        Constants.Difficulty[] tiers = Constants.Difficulty.values();
        for (int i = 1; i < tiers.length; i++) {
            assertTrue(tiers[i].cellsRemoved > tiers[i - 1].cellsRemoved,
                tiers[i] + " must remove more cells than " + tiers[i - 1]);
        }
    }
}
