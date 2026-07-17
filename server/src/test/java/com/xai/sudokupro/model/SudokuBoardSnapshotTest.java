package com.xai.sudokupro.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Grid snapshot/restore (save/load feature). The SudokuCell[][] grid is
 * {@code @Transient} for JPA, so persistence round-trips through the
 * {@code cells_json} snapshot produced by {@link SudokuBoard#snapshotCells()}
 * and rebuilt by {@link SudokuBoard#restoreCells(String)}. These tests pin the
 * round-trip fidelity that the DB (@PrePersist/@PostLoad) and Redis
 * (getCellsJson/setCellsJson) paths both rely on.
 */
class SudokuBoardSnapshotTest {

    private static SudokuBoard freshBoard() {
        return new SudokuBoard(2, false, false, 0, "snap-test");
    }

    private static SudokuBoard restoredCopy(SudokuBoard original) {
        SudokuBoard copy = new SudokuBoard(2, false, false, 0, "snap-restored");
        copy.restoreCells(original.snapshotCells());
        return copy;
    }

    @Test
    void roundTripPreservesValuesAndGivenFlags() {
        SudokuBoard original = freshBoard();
        SudokuBoard restored = restoredCopy(original);

        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                SudokuCell o = original.getBoard()[r][c];
                SudokuCell x = restored.getBoard()[r][c];
                assertEquals(o.getValue(), x.getValue(), "value at (" + r + "," + c + ")");
                assertEquals(o.isGiven(), x.isGiven(), "given flag at (" + r + "," + c + ")");
            }
        }
    }

    @Test
    void roundTripPreservesPlayerMovesPencilMarksAndConflicts() {
        SudokuBoard original = freshBoard();

        // Find two empty cells and decorate them with player state.
        int[] move = null, marks = null;
        for (int r = 0; r < 9 && (move == null || marks == null); r++) {
            for (int c = 0; c < 9 && (move == null || marks == null); c++) {
                if (original.getBoard()[r][c].getValue() != 0) continue;
                if (move == null) move = new int[]{r, c};
                else marks = new int[]{r, c};
            }
        }
        assertNotNull(move); assertNotNull(marks);

        original.getBoard()[move[0]][move[1]].setValue(5, SudokuCell.MoveSource.PLAYER);
        original.getBoard()[move[0]][move[1]].addConflict(5);
        original.getBoard()[marks[0]][marks[1]].addPencilMark(3);
        original.getBoard()[marks[0]][marks[1]].addPencilMark(7);

        SudokuBoard restored = restoredCopy(original);

        SudokuCell movedCell = restored.getBoard()[move[0]][move[1]];
        assertEquals(5, movedCell.getValue());
        assertEquals(SudokuCell.MoveSource.PLAYER, movedCell.getMoveSource());
        assertEquals(Set.of(5), movedCell.getConflicts());
        assertEquals(Set.of(3, 7), restored.getBoard()[marks[0]][marks[1]].getPencilMarks());
    }

    @Test
    void restoredGivenCellsStillRefuseModification() {
        // Regression guard for P1-NEW-1's failure mode: a restored clue must keep
        // its given flag so the given-cell guard and isCellEditable stay effective.
        SudokuBoard restored = restoredCopy(freshBoard());

        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                if (!restored.getBoard()[r][c].isGiven()) continue;
                int before = restored.getBoard()[r][c].getValue();
                restored.getBoard()[r][c].setValue(before == 9 ? 1 : before + 1, SudokuCell.MoveSource.PLAYER);
                assertEquals(before, restored.getBoard()[r][c].getValue(),
                    "restored given cell (" + r + "," + c + ") must refuse modification");
                assertFalse(restored.isCellEditable(r, c));
                return; // one representative cell is enough
            }
        }
        fail("generated board has no given cells");
    }

    @Test
    void malformedSnapshotThrowsAndLeavesBoardUntouched() {
        SudokuBoard board = freshBoard();
        String before = board.snapshotCells();

        assertThrows(IllegalArgumentException.class, () -> board.restoreCells("not json"));
        assertThrows(IllegalArgumentException.class, () -> board.restoreCells("[[{\"v\":1}]]"));
        assertEquals(before, board.snapshotCells(), "failed restore must not mutate the grid");
    }

    @Test
    void nullOrBlankSnapshotIsANoOp() {
        SudokuBoard board = freshBoard();
        String before = board.snapshotCells();
        board.restoreCells(null);
        board.restoreCells("  ");
        assertEquals(before, board.snapshotCells());
    }

    @Test
    void jacksonPropertyRoundTripRestoresGrid() {
        // The Redis cache path: cellsJson rides along the serialized entity.
        SudokuBoard original = freshBoard();
        SudokuBoard target = new SudokuBoard(1, false, false, 0, "jackson-copy");
        target.setCellsJson(original.getCellsJson());

        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                assertEquals(original.getBoard()[r][c].getValue(), target.getBoard()[r][c].getValue());
                assertEquals(original.getBoard()[r][c].isGiven(), target.getBoard()[r][c].isGiven());
            }
        }
    }
}
