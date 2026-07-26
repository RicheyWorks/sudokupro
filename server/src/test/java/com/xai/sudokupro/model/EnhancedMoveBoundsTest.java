package com.xai.sudokupro.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coordinate bounds on {@link EnhancedMove}.
 *
 * <p>The canonical constructor guarded {@code row < -1 || row > 8}, so row or column -1
 * passed validation. -1 is not a sentinel anywhere in the codebase, so the move then threw
 * {@code ArrayIndexOutOfBoundsException} deep inside the board — surfacing to the client as
 * a raw "Index -1 out of bounds for length 9" rather than a clean rejection, and logging an
 * ERROR for what is really a malformed request.
 *
 * <p>Found by the live engine feeding out-of-range coordinates over the WebSocket
 * (engine/live_engine.py, suite L3).
 */
class EnhancedMoveBoundsTest {

    @Test
    void negativeCoordinatesAreRejectedByTheConstructorItself() {
        for (int[] bad : new int[][]{{-1, 0}, {0, -1}, {-1, -1}, {-5, 3}, {3, -9}}) {
            var e = assertThrows(IllegalArgumentException.class,
                () -> new EnhancedMove(bad[0], bad[1], 0, 5, SudokuCell.MoveSource.PLAYER),
                "row=" + bad[0] + " col=" + bad[1] + " must be refused up front, not deep "
                    + "inside the board with an array-index error");
            assertTrue(e.getMessage().contains("0..8"),
                "the message should state the real range, got: " + e.getMessage());
        }
    }

    @Test
    void coordinatesPastTheBoardAreRejected() {
        for (int[] bad : new int[][]{{9, 0}, {0, 9}, {99, 99}, {8, 300}}) {
            assertThrows(IllegalArgumentException.class,
                () -> new EnhancedMove(bad[0], bad[1], 0, 5, SudokuCell.MoveSource.PLAYER));
        }
    }

    /**
     * Every real coordinate, actually varied.
     *
     * <p>This looped r,c over 0..8 but the body was hardcoded to {@code (0, 0)}, so 81
     * iterations tested one coordinate. A mutation audit proved it: narrowing the guard to
     * reject row 3 or column 5 left this class green. The loop variables are now used.
     */
    @Test
    void everyRealCoordinateIsAccepted() {
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                final int row = r, col = c;
                assertDoesNotThrow(
                    () -> new EnhancedMove(row, col, 0, 5, SudokuCell.MoveSource.PLAYER),
                    "(" + row + "," + col + ") is a real board coordinate");
            }
        }
        assertDoesNotThrow(() -> new EnhancedMove(0, 0, 0, 0, SudokuCell.MoveSource.PLAYER));
        assertDoesNotThrow(() -> new EnhancedMove(8, 8, 9, 9, SudokuCell.MoveSource.PLAYER));
    }

    @Test
    void valuesOutsideZeroToNineAreStillRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new EnhancedMove(0, 0, 0, 10, SudokuCell.MoveSource.PLAYER));
        assertThrows(IllegalArgumentException.class,
            () -> new EnhancedMove(0, 0, -1, 5, SudokuCell.MoveSource.PLAYER));
    }
}
