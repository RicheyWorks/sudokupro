package com.xai.sudokupro.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Move-path semantics on the real board: erasing, counter accuracy, provenance through
 * undo/redo, solved-flag consistency, and generation variety.
 *
 * <p>Every case here reproduces a bug the game-playing engine harness
 * ({@code engine/Engine.java}) found by driving these classes directly.
 */
class SudokuBoardMoveSemanticsTest {

    /**
     * Regression: a player could not erase a wrong entry through ANY move path.
     * {@code isValidMove} scanned the row/column/box for a cell equal to the new value,
     * and an unfinished board always contains an empty cell — so a clear (newVal 0) was
     * always "a duplicate" and always rejected. That hit makeMove, applyExternalMove,
     * applyBatchMoves, and therefore the WebSocket move handler and both clients' erase
     * buttons; only a server-side undo could take a value back.
     */
    @Test
    void aPlayerCanEraseTheirOwnEntry() {
        SudokuBoard board = new SudokuBoard(2, false, false, 0, "erase-test");
        int[] cell = firstEmptyCell(board);
        int value = firstLegalValue(board, cell[0], cell[1]);

        board.makeMove(cell[0], cell[1], value, SudokuCell.MoveSource.PLAYER);
        assertEquals(value, board.getBoard()[cell[0]][cell[1]].getValue());

        board.makeMove(cell[0], cell[1], 0, SudokuCell.MoveSource.PLAYER);
        assertEquals(0, board.getBoard()[cell[0]][cell[1]].getValue(), "the cell must clear");
    }

    @Test
    void erasingIsNotRecordedAsAMistake() {
        SudokuBoard board = new SudokuBoard(2, false, false, 0, "erase-heatmap");
        int[] cell = firstEmptyCell(board);
        int value = firstLegalValue(board, cell[0], cell[1]);
        board.makeMove(cell[0], cell[1], value, SudokuCell.MoveSource.PLAYER);

        board.makeMove(cell[0], cell[1], 0, SudokuCell.MoveSource.PLAYER);

        assertTrue(board.getColorizedHeatmap().isEmpty(),
            "clearing a cell is not a wrong answer and must not feed the mistake heatmap");
    }

    /**
     * Regression: {@code applyBatchMoves} did {@code moveCount += moves.size()}
     * unconditionally, so rejected moves still inflated the counter — and
     * jumpToMove()/loadReplayFromJson() inherited it. Anti-cheat move-rate scoring reads
     * this number.
     */
    @Test
    void batchMovesCountOnlyWhatWasActuallyApplied() {
        SudokuBoard board = new SudokuBoard(2, false, false, 0, "batch-count");
        int[] empty = firstEmptyCell(board);
        int legal = firstLegalValue(board, empty[0], empty[1]);
        int[] given = firstGivenCell(board);

        int before = board.getMoveCount();
        board.applyBatchMoves(List.of(
            new EnhancedMove(empty[0], empty[1], 0, legal, SudokuCell.MoveSource.PLAYER),
            new EnhancedMove(given[0], given[1], 0, 5, SudokuCell.MoveSource.PLAYER)  // rejected: given
        ));

        assertEquals(before + 1, board.getMoveCount(),
            "only the one applicable move may count");
    }

    /**
     * Regression: undo()/redo() used the single-arg setValue(), which resets MoveSource to
     * UNKNOWN. Because {@code hasAutosolvedCells()} is exactly what suppresses rewards on
     * an AI-solved board, an undo/redo round trip laundered an auto-solved board into a
     * "legitimate" solve that paid gems, streaks and achievements.
     */
    @Test
    void undoRedoPreservesMoveProvenance() {
        SudokuBoard board = new SudokuBoard(2, false, false, 0, "provenance");
        int[] cell = firstEmptyCell(board);
        int value = firstLegalValue(board, cell[0], cell[1]);

        board.makeMove(cell[0], cell[1], value, SudokuCell.MoveSource.HINT);
        board.undo();
        board.redo();

        assertEquals(SudokuCell.MoveSource.HINT,
            board.getBoard()[cell[0]][cell[1]].getMoveSource(),
            "redo must restore the original source, not UNKNOWN");
    }

    @Test
    void autoSolvedBoardsCannotBeLaunderedByUndoRedo() {
        SudokuBoard board = new SudokuBoard(1, false, false, 0, "launder");
        board.autoSolve();
        assertTrue(board.hasAutosolvedCells(), "test setup: the solver filled this board");

        int undone = 0;
        while (!board.getMoveHistory().isEmpty() && undone < 200) { board.undo(); undone++; }
        while (board.redo() != null) { /* replay everything back */ }

        assertTrue(board.hasAutosolvedCells(),
            "an AI-solved board must still be recognisable as such after undo/redo");
    }

    /**
     * Regression: nothing ever cleared the persisted {@code solved} flag, so undoing a cell
     * on a finished board left solved=true while isSolved() was false. Such a board is
     * filtered out of findResumableByPlayerId (which requires solved = false), permanently
     * hiding a game the player can still finish.
     */
    @Test
    void undoingAfterASolveClearsThePersistedSolvedFlag() {
        SudokuBoard board = new SudokuBoard(1, false, false, 0, "flag");
        board.autoSolve();
        assertTrue(board.isSolvedState());

        board.undo();

        assertFalse(board.isSolved(), "the grid is no longer complete");
        assertFalse(board.isSolvedState(), "the persisted flag must follow the grid");
    }

    /**
     * Regression: the generator's backtracker tried candidates 1..9 in fixed order, so it
     * was deterministic — EVERY game ever created resolved to the same completed grid and
     * only the clue positions varied. Solving one puzzle gave you the answer to every
     * daily, duel and tournament board on the platform.
     */
    @Test
    void generatedBoardsDoNotAllShareOneSolution() {
        java.util.Set<String> solutions = new java.util.HashSet<>();
        for (int i = 0; i < 5; i++) {
            SudokuBoard board = new SudokuBoard(3, false, false, 0, "variety-" + i);
            int[][] grid = new int[9][9];
            for (int r = 0; r < 9; r++)
                for (int c = 0; c < 9; c++)
                    grid[r][c] = board.getBoard()[r][c].isGiven() ? board.getBoard()[r][c].getValue() : 0;
            solve(grid);
            StringBuilder flat = new StringBuilder();
            for (int[] row : grid) for (int v : row) flat.append(v);
            solutions.add(flat.toString());
        }
        assertTrue(solutions.size() > 1,
            "five generated boards must not all resolve to one identical solution");
    }

    /**
     * Structural diversity — the check the literal comparison above cannot make.
     *
     * <p>The test above compares completed grids as strings, and that is exactly how the
     * relabelling bug survived a "fix". Drawing ONE 1..9 permutation per board and reusing
     * it at every cell only RENAMES the digits: the backtracker walks the identical search
     * path, so every puzzle is the same grid with different labels. Those grids differ
     * literally, so the string comparison sees five distinct solutions and passes.
     *
     * <p>Canonicalising first — relabel each solution so its top row reads 1..9 — collapses
     * the disguise. Measured before the real fix: 30 boards across all difficulties reduced
     * to ONE canonical grid, which means nine clues (one per digit) pin the entire solution
     * to every daily, duel and tournament board on the platform, with no solving required.
     *
     * <p>A mutation audit confirmed this gap was still open in the Java suite even after
     * the engine harness had been taught to canonicalise: reintroducing the one-permutation
     * bug left all 270 tests green.
     */
    @Test
    void generatedBoardsAreNotOneGridWithTheDigitsRenamed() {
        java.util.Set<String> canonical = new java.util.HashSet<>();
        int boards = 0;
        for (int difficulty = 1; difficulty <= 4; difficulty++) {
            for (int i = 0; i < 4; i++) {
                SudokuBoard board = new SudokuBoard(difficulty, false, false, 0,
                    "canon-" + difficulty + "-" + i);
                int[][] grid = new int[9][9];
                for (int r = 0; r < 9; r++)
                    for (int c = 0; c < 9; c++)
                        grid[r][c] = board.getBoard()[r][c].isGiven()
                            ? board.getBoard()[r][c].getValue() : 0;
                solve(grid);
                canonical.add(canonicalise(grid));
                boards++;
            }
        }
        assertTrue(canonical.size() > boards / 2,
            "only " + canonical.size() + " structurally distinct grids across " + boards
                + " boards once canonicalised by relabeling row 0 — the generator is "
                + "producing one grid with the digits renamed, so a handful of clues "
                + "reveals every answer on the platform");
    }

    /** Relabels a completed grid so its first row reads 1..9. */
    private static String canonicalise(int[][] grid) {
        int[] map = new int[10];
        for (int c = 0; c < 9; c++) map[grid[0][c]] = c + 1;
        StringBuilder sb = new StringBuilder(81);
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                sb.append(map[grid[r][c]]);
        return sb.toString();
    }

    /** In mirror mode one player action writes two cells; one undo must revert both. */
    @Test
    void mirrorModeUndoRevertsThePairAsOneAction() {
        SudokuBoard board = new SudokuBoard(3, false, true, 0, "mirror");
        board.setMirrorMode(true);
        int primaryRow = -1, primaryCol = -1;
        outer:
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++) {
                int mr = 8 - r, mc = 8 - c;
                if ((r != mr || c != mc)
                        && board.getBoard()[r][c].getValue() == 0
                        && board.getBoard()[mr][mc].getValue() == 0) {
                    primaryRow = r; primaryCol = c; break outer;
                }
            }
        if (primaryRow < 0) return;   // no mirrored empty pair on this board
        int mr = 8 - primaryRow, mc = 8 - primaryCol;
        int value = firstLegalValue(board, primaryRow, primaryCol);

        board.makeMove(primaryRow, primaryCol, value, SudokuCell.MoveSource.PLAYER);
        boolean mirrorWasWritten = board.getBoard()[mr][mc].getValue() != 0;

        board.undo();

        assertEquals(0, board.getBoard()[primaryRow][primaryCol].getValue(),
            "the cell the player typed in must be cleared");
        if (mirrorWasWritten) {
            assertEquals(0, board.getBoard()[mr][mc].getValue(),
                "its mirror twin belongs to the same action and must clear too");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────
    private static int[] firstEmptyCell(SudokuBoard b) {
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (b.getBoard()[r][c].getValue() == 0) return new int[]{r, c};
        throw new IllegalStateException("no empty cell");
    }

    private static int[] firstGivenCell(SudokuBoard b) {
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (b.getBoard()[r][c].isGiven()) return new int[]{r, c};
        throw new IllegalStateException("no given cell");
    }

    private static int firstLegalValue(SudokuBoard b, int row, int col) {
        for (int v = 1; v <= 9; v++) if (b.isValidMove(row, col, v)) return v;
        throw new IllegalStateException("no legal value at (" + row + "," + col + ")");
    }

    private static boolean solve(int[][] g) {
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (g[r][c] == 0) {
                    for (int v = 1; v <= 9; v++)
                        if (legal(g, r, c, v)) {
                            g[r][c] = v;
                            if (solve(g)) return true;
                            g[r][c] = 0;
                        }
                    return false;
                }
        return true;
    }

    private static boolean legal(int[][] g, int r, int c, int v) {
        for (int i = 0; i < 9; i++) if (g[r][i] == v || g[i][c] == v) return false;
        int br = r - r % 3, bc = c - c % 3;
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (g[br + i][bc + j] == v) return false;
        return true;
    }
}
