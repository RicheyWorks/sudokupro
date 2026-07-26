package com.xai.sudokupro.client;

import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.SudokuCell;

/**
 * The one place a move is turned into a history line.
 *
 * <p>The label text used to be written out at each site that needed it, and the
 * filter that hides hints compared against its own copy of the literal — so the
 * two could (and did) drift, leaving the Hints filter matching nothing.
 */
public final class MoveLabels {

    /** The single spelling of a hint entry, shared by every writer and the filter. */
    public static final String HINT = "Hint Applied";

    private MoveLabels() { }

    /** A move as it appears in the history list; coordinates are 1-based for the player. */
    public static String describe(EnhancedMove move) {
        if (move.source() == SudokuCell.MoveSource.HINT) return HINT;
        if (move.newVal() == 0) {
            return String.format("(%d,%d) cleared", move.row() + 1, move.col() + 1);
        }
        return String.format("(%d,%d)=%d", move.row() + 1, move.col() + 1, move.newVal());
    }

    /** True when a history line is a hint rather than a placement. */
    public static boolean isHint(String historyLine) {
        return HINT.equals(historyLine);
    }
}
