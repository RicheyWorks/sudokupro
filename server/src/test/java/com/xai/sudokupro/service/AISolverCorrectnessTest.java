package com.xai.sudokupro.service;

import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import com.xai.sudokupro.util.SecureRandomGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hints must be correct.
 *
 * <p>{@code collectAllHints} used to emit {@code candidates.get(0)} — the smallest locally
 * legal digit — for any cell that was not a naked single, and sell it as "Try placing N at
 * row R, col C" for 5 gems. That is a guess: measured across 25 boards with no naked single
 * available, 16 of those hints named a digit that was not the answer.
 *
 * <p>The damaging half was {@code REVEAL_CELL}, which runs the same ranking through
 * {@code getNextLogicalMoveAsEnhancedMove} and <em>writes the value into the board</em>. The
 * wrong digit is locally legal, so the board accepts it — and on difficulty-4 and -5 boards
 * every wrong reveal rendered the puzzle unsolvable. The player spent a purchased item to
 * have their game silently destroyed, and was charged a hint against the clean-solve bonus
 * for it.
 */
class AISolverCorrectnessTest {

    private static AISolverService newSolver() {
        return new AISolverService(new SecureRandomGenerator(new SimpleMeterRegistry()));
    }

    private static final Pattern HINT = Pattern.compile("(\\d+) at row (\\d+), col (\\d+)");

    /** The unique completion of a board, computed independently of the solver under test. */
    private static int[][] solutionOf(SudokuBoard board) {
        int[][] g = new int[9][9];
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                g[r][c] = board.getBoard()[r][c].getValue();
        return fill(g, 0) ? g : null;
    }

    private static boolean fill(int[][] g, int idx) {
        if (idx == 81) return true;
        int r = idx / 9, c = idx % 9;
        if (g[r][c] != 0) return fill(g, idx + 1);
        for (int v = 1; v <= 9; v++) {
            if (!legal(g, r, c, v)) continue;
            g[r][c] = v;
            if (fill(g, idx + 1)) return true;
            g[r][c] = 0;
        }
        return false;
    }

    private static boolean legal(int[][] g, int r, int c, int v) {
        for (int i = 0; i < 9; i++) if (g[r][i] == v || g[i][c] == v) return false;
        int br = (r / 3) * 3, bc = (c / 3) * 3;
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (g[br + i][bc + j] == v) return false;
        return true;
    }

    /** True when no cell has exactly one candidate — the case the old code guessed on. */
    private static boolean hasNoNakedSingle(SudokuBoard board) {
        int[][] g = new int[9][9];
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                g[r][c] = board.getBoard()[r][c].getValue();
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                if (g[r][c] != 0) continue;
                int n = 0;
                for (int v = 1; v <= 9; v++) if (legal(g, r, c, v)) n++;
                if (n == 1) return false;
            }
        }
        return true;
    }

    @Test
    void everyHintNamesTheTrueValue() {
        int checked = 0;
        for (int i = 0; i < 40; i++) {
            AISolverService solver = newSolver();   // fresh: no cross-board hint cache
            SudokuBoard board = new SudokuBoard(4, false, false, 0, "hint-" + i);
            int[][] truth = solutionOf(board);
            assertNotNull(truth, "test setup: generated board must be solvable");

            String hint = solver.getNextLogicalMove(board);
            Matcher m = HINT.matcher(hint);
            if (!m.find()) continue;   // "No moves"
            int value = Integer.parseInt(m.group(1));
            int row = Integer.parseInt(m.group(2)) - 1;
            int col = Integer.parseInt(m.group(3)) - 1;

            assertEquals(truth[row][col], value,
                "hint named " + value + " at (" + (row + 1) + "," + (col + 1)
                    + ") but the answer there is " + truth[row][col]);
            checked++;
        }
        assertTrue(checked >= 30, "expected most boards to yield a hint, got " + checked);
    }

    /**
     * The specific case the old implementation got wrong: no naked single anywhere, so it
     * fell back to "the smallest legal digit" and was usually mistaken.
     */
    @Test
    void hintsAreCorrectEvenWithNoNakedSingleAvailable() {
        int examined = 0;
        for (int i = 0; i < 120 && examined < 12; i++) {
            SudokuBoard board = new SudokuBoard(5, false, false, 0, "nns-" + i);
            if (!hasNoNakedSingle(board)) continue;
            int[][] truth = solutionOf(board);
            if (truth == null) continue;

            String hint = newSolver().getNextLogicalMove(board);
            Matcher m = HINT.matcher(hint);
            if (!m.find()) continue;
            int value = Integer.parseInt(m.group(1));
            int row = Integer.parseInt(m.group(2)) - 1;
            int col = Integer.parseInt(m.group(3)) - 1;

            assertEquals(truth[row][col], value,
                "guessed hint on a board with no naked single: said " + value
                    + " at (" + (row + 1) + "," + (col + 1) + "), answer is " + truth[row][col]);
            examined++;
        }
        assertTrue(examined > 0, "test setup: found no board without a naked single");
    }

    /** REVEAL_CELL writes into the board, so a wrong value there destroys the game. */
    @Test
    void revealCellNeverWritesAValueThatBreaksTheBoard() {
        int reveals = 0;
        for (int i = 0; i < 40; i++) {
            AISolverService solver = newSolver();
            SudokuBoard board = new SudokuBoard(4, false, false, 0, "reveal-" + i);
            int[][] truth = solutionOf(board);
            assertNotNull(truth);

            EnhancedMove move = solver.getNextLogicalMoveAsEnhancedMove(board);
            if (move == null) continue;

            assertEquals(truth[move.row()][move.col()], move.newVal(),
                "REVEAL_CELL would write " + move.newVal() + " at ("
                    + (move.row() + 1) + "," + (move.col() + 1) + "), answer is "
                    + truth[move.row()][move.col()]);

            board.applyExternalMove(move);
            assertNotNull(solutionOf(board),
                "a reveal must never render the board unsolvable");
            reveals++;
        }
        assertTrue(reveals >= 30, "expected most boards to yield a reveal, got " + reveals);
    }

    /** A contradictory board has no answer; hinting one confidently is worse than declining. */
    @Test
    void anUnsolvableBoardYieldsNoHintRatherThanAGuess() {
        SudokuBoard board = new SudokuBoard(1, false, false, 0, "broken");
        // Force a contradiction the generator would never produce: two 5s in one row.
        SudokuCell[][] g = board.getBoard();
        int placed = 0;
        for (int c = 0; c < 9 && placed < 2; c++) {
            if (g[0][c].getValue() == 0) { g[0][c].setValue(5, SudokuCell.MoveSource.PLAYER); placed++; }
        }
        if (placed < 2) return;   // this generated board had no two empties in row 0

        assertEquals("No moves", newSolver().getNextLogicalMove(board),
            "an unsolvable position must not produce a confident hint the player pays for");
    }

    /**
     * Regression: hint quality used to degrade with server uptime. {@code cosmicHotspots}
     * is a global, coordinate-keyed map that every issued hint incremented by 2 and only
     * {@code solveSudoku} decayed, and the bonus was uncapped — so after roughly 23 hints
     * ever taken at a coordinate it overtook the 90-point gap between a naked single and a
     * mere candidate. One long-lived solver serving fresh boards started returning
     * non-deducible hints from around request 41 onward.
     */
    @Test
    void hintQualityDoesNotDegradeOverManyRequests() {
        AISolverService solver = newSolver();   // deliberately shared, as in production
        int wrong = 0, checked = 0;
        for (int i = 0; i < 120; i++) {
            SudokuBoard board = new SudokuBoard(2, false, false, 0, "soak-" + i);
            int[][] truth = solutionOf(board);
            if (truth == null) continue;
            String hint = solver.getNextLogicalMove(board);
            Matcher m = HINT.matcher(hint);
            if (!m.find()) continue;
            int value = Integer.parseInt(m.group(1));
            int row = Integer.parseInt(m.group(2)) - 1;
            int col = Integer.parseInt(m.group(3)) - 1;
            checked++;
            if (truth[row][col] != value) wrong++;
        }
        assertTrue(checked > 80, "expected a long run of hints, got " + checked);
        assertEquals(0, wrong, wrong + " of " + checked + " hints were wrong on a long-lived solver");
    }
}
