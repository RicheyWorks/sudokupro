package com.xai.sudokupro.model;

import com.xai.sudokupro.util.Constants;
import com.xai.sudokupro.util.SecureRandomGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural invariants every generated board must hold, across all difficulties and all
 * chaos/mirror combinations.
 *
 * <p>The first one is the reason {@code applyChaosTwist} was deleted rather than repaired:
 * it hunted for cells that were non-empty AND non-given, and no such cell can exist after
 * generation. But the invariant is worth asserting in its own right, because the failure it
 * describes is a solution leak — a filled cell that is not marked as a clue is a digit the
 * player did not enter and the server did not give them, i.e. part of the answer shipped
 * inside the puzzle. The web client harness asserts the same property from the other side
 * of the wire; this pins it at the source.
 */
class GeneratedBoardInvariantsTest {

    private SudokuGenerator generator() {
        return new SudokuGenerator(new SecureRandomGenerator(new SimpleMeterRegistry()));
    }

    /**
     * Every filled cell on a freshly generated board is a clue.
     *
     * <p>A non-given cell holding a value would mean part of the solution was baked into
     * the puzzle as though the player had entered it — invisible in the UI, and free
     * progress in a duel or daily where everyone races the same grid.
     */
    @Test
    void everyFilledCellOnAGeneratedBoardIsAClue() {
        for (Constants.Difficulty d : Constants.Difficulty.values()) {
            for (boolean chaos : new boolean[]{false, true}) {
                for (boolean mirror : new boolean[]{false, true}) {
                    SudokuBoard board = generator().generate(d, chaos, mirror, 9_000L + d.ordinal());
                    for (int r = 0; r < 9; r++) {
                        for (int c = 0; c < 9; c++) {
                            SudokuCell cell = board.getBoard()[r][c];
                            if (cell.getValue() != 0) {
                                assertTrue(cell.isGiven(),
                                    "cell " + r + "," + c + " holds " + cell.getValue()
                                        + " but is not marked given (" + d + ", chaos=" + chaos
                                        + ", mirror=" + mirror + ") — that is a leaked solution digit");
                            }
                            if (!cell.isGiven()) {
                                assertEquals(0, cell.getValue(),
                                    "a non-given cell must be empty on a fresh board");
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * A chaos board is an ordinary, legal puzzle that merely carries the flag the runtime
     * swaps read. Chaos is a play-time effect; generation must not touch the grid.
     *
     * <p>Asserted as a property rather than by comparing two boards built from the same
     * seed, because <b>the {@code seed} parameter does not make generation
     * reproducible</b> — the RNG is a {@code SecureRandom}, whose {@code setSeed}
     * supplements the existing entropy rather than replacing it. Nothing in production
     * depends on seeded reproducibility (daily, weekly and duel templates are each
     * generated once and persisted), but it is easy to assume otherwise, and this test
     * assumed it in its first draft.
     *
     * <p>A generation-time swap sneaking back in would be caught either way: swapping two
     * clues introduces a duplicate in a row, column or box, and swapping in anything else
     * produces a filled cell that is not marked given.
     */
    @Test
    void chaosModeLeavesALegalPuzzleAndArmsTheRuntimeFlag() {
        for (int i = 0; i < 12; i++) {
            SudokuBoard board = generator().generate(Constants.Difficulty.MEDIUM, true, false, 4242L + i);
            assertTrue(board.isChaosMode(), "the board must carry the flag the runtime swaps read");

            SudokuCell[][] cells = board.getBoard();
            for (int r = 0; r < 9; r++) {
                for (int c = 0; c < 9; c++) {
                    int v = cells[r][c].getValue();
                    if (v == 0) continue;
                    assertTrue(cells[r][c].isGiven(), "a filled cell must be a clue at " + r + "," + c);
                    for (int k = 0; k < 9; k++) {
                        if (k != c) assertNotEquals(v, cells[r][k].getValue(),
                            "duplicate " + v + " in row " + r + " — the grid is not a legal Sudoku");
                        if (k != r) assertNotEquals(v, cells[k][c].getValue(),
                            "duplicate " + v + " in column " + c + " — the grid is not a legal Sudoku");
                    }
                    int br = (r / 3) * 3, bc = (c / 3) * 3;
                    for (int dr = 0; dr < 3; dr++) {
                        for (int dc = 0; dc < 3; dc++) {
                            if (br + dr == r && bc + dc == c) continue;
                            assertNotEquals(v, cells[br + dr][bc + dc].getValue(),
                                "duplicate " + v + " in the box at " + br + "," + bc);
                        }
                    }
                }
            }
        }
    }

    /** The clue count must match the difficulty tier, chaos or not. */
    @Test
    void theClueCountMatchesTheDifficultyTier() {
        for (Constants.Difficulty d : Constants.Difficulty.values()) {
            SudokuBoard board = generator().generate(d, true, false, 7_000L + d.ordinal());
            int empty = 0;
            for (int r = 0; r < 9; r++)
                for (int c = 0; c < 9; c++)
                    if (board.getBoard()[r][c].getValue() == 0) empty++;
            assertEquals(d.cellsRemoved, empty,
                d + " should leave exactly " + d.cellsRemoved + " empty cells");
        }
    }
}
