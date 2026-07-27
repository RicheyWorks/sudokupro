package com.xai.sudokupro.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The replay and undo/redo surface of {@link SudokuBoard}.
 *
 * <p>Nothing here is speculative. Every case below reproduces a defect that was reachable
 * from the shipped UI — the scrub bar, the watch-back button, the undo and redo buttons —
 * and each one was found by reading the code and then executing it, not by inspection alone.
 * The common shape is that {@code reset()} clears {@code replayHistory}, and three of the
 * four callers touched it after resetting rather than before.
 */
class SudokuBoardReplayTest {

    // ---- helpers -------------------------------------------------------------------

    private static int[] firstEmptyCell(SudokuBoard board, int startRow) {
        for (int r = startRow; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (board.getBoard()[r][c].getValue() == 0) return new int[]{r, c};
        throw new IllegalStateException("no empty cell");
    }

    private static int firstLegalValue(SudokuBoard board, int r, int c) {
        for (int v = 1; v <= 9; v++) if (board.isValidMove(r, c, v)) return v;
        throw new IllegalStateException("no legal value at " + r + "," + c);
    }

    /**
     * Plays {@code count} legal moves on distinct empty cells and returns {row, col, value}
     * for each.
     *
     * <p>Cells are scanned across the whole grid and any cell with no legal value left is
     * skipped, rather than walking a single row — filling a row exhausts its candidates and
     * makes the helper, not the code under test, the thing that fails.
     */
    private static List<int[]> playMoves(SudokuBoard board, int count) {
        List<int[]> played = new ArrayList<>();
        for (int r = 0; r < 9 && played.size() < count; r++) {
            for (int c = 0; c < 9 && played.size() < count; c++) {
                if (board.getBoard()[r][c].getValue() != 0) continue;
                if (!board.isCellEditable(r, c)) continue;
                int v = 0;
                for (int candidate = 1; candidate <= 9; candidate++) {
                    if (board.isValidMove(r, c, candidate)) { v = candidate; break; }
                }
                if (v == 0) continue;   // nothing legal here; try elsewhere
                board.makeMove(r, c, v, SudokuCell.MoveSource.PLAYER);
                if (board.getBoard()[r][c].getValue() == v) played.add(new int[]{r, c, v});
            }
        }
        assertEquals(count, played.size(), "test setup could not play " + count + " moves");
        return played;
    }

    // ---- jumpToMove ----------------------------------------------------------------

    /**
     * {@code jumpToMove} threw on every call it did not reject, and wiped the board first.
     *
     * <p>{@code reset()} clears {@code replayHistory}; the {@code subList(0, index + 1)}
     * view was taken afterwards, against a list that was by then empty. So the bounds check
     * passed against the real history and the indexing blew up against nothing. The player
     * lost the whole game to an {@code IndexOutOfBoundsException} on a scrub-bar drag.
     *
     * <p>The only call that escaped was one that returned early.
     */
    @Test
    void jumpingToAMoveRewindsTheBoardInsteadOfThrowing() {
        SudokuBoard board = new SudokuBoard(2, false, false, 0, "jump-test");
        List<int[]> played = playMoves(board, 4);

        assertDoesNotThrow(() -> board.jumpToMove(1),
            "a jump must not throw — this threw IndexOutOfBoundsException on every call");

        int[] second = played.get(1);
        int[] third = played.get(2);
        assertEquals(second[2], board.getBoard()[second[0]][second[1]].getValue(),
            "the board must hold every move up to and including the jump target");
        assertEquals(0, board.getBoard()[third[0]][third[1]].getValue(),
            "moves after the jump target must be rolled back");
    }

    /** Jumping must not destroy the history it jumped through — you can scrub back and forth. */
    @Test
    void jumpingTwiceWorksBecauseTheHistorySurvivesTheFirstJump() {
        SudokuBoard board = new SudokuBoard(2, false, false, 0, "jump-twice");
        List<int[]> played = playMoves(board, 4);

        board.jumpToMove(2);
        assertEquals(3, board.getReplayHistory().size(),
            "after jumping to index 2 the history holds exactly those three moves");

        board.jumpToMove(0);
        int[] first = played.get(0);
        assertEquals(first[2], board.getBoard()[first[0]][first[1]].getValue());
        assertEquals(1, board.getReplayHistory().size());
    }

    @Test
    void anOutOfRangeJumpIsIgnoredAndLeavesTheBoardAlone() {
        SudokuBoard board = new SudokuBoard(2, false, false, 0, "jump-oob");
        playMoves(board, 2);
        int before = board.getReplayHistory().size();

        board.jumpToMove(-1);
        board.jumpToMove(99);

        assertEquals(before, board.getReplayHistory().size(),
            "a rejected jump must not clear anything");
    }

    // ---- replayMoves ---------------------------------------------------------------

    /**
     * "Watch this game back" replayed nothing and destroyed the game.
     *
     * <p>The snapshot was taken after {@code reset()}, so it was always empty: the loop body
     * never ran, no line was ever fed to the output consumer, and the player's board had
     * been cleared on the way in. A feature that consumes its own input before reading it.
     */
    @Test
    void replayingAGameEmitsEveryMoveInsteadOfSilentlyErasingIt() {
        SudokuBoard board = new SudokuBoard(2, false, false, 0, "replay-test");
        List<int[]> played = playMoves(board, 3);

        List<String> lines = new ArrayList<>();
        board.replayMoves(0L, lines::add);

        assertEquals(3, lines.size(), "one line per recorded move, got " + lines);
        for (int[] m : played) {
            assertEquals(m[2], board.getBoard()[m[0]][m[1]].getValue(),
                "the replay must leave the board where the moves left it");
        }
    }

    // ---- loadReplayFromJson --------------------------------------------------------

    /** A well-formed timeline round-trips through export and import. */
    @Test
    void aReplayTimelineRoundTrips() {
        SudokuBoard source = new SudokuBoard(2, false, false, 0, "round-trip");
        List<int[]> played = playMoves(source, 3);
        String json = source.exportMoveTimelineJson();

        source.loadReplayFromJson(json);

        for (int[] m : played) {
            assertEquals(m[2], source.getBoard()[m[0]][m[1]].getValue());
        }
        assertEquals(3, source.getReplayHistory().size());
    }

    /**
     * Rejecting malformed replay JSON must leave the board exactly as it was.
     *
     * <p>It used to {@code reset()} first and map the entries second, so a single bad field
     * — an unknown {@code source}, a missing key, a non-numeric coordinate — cleared the
     * player's board and every move they had made, and only THEN threw. The caller sees
     * "Invalid replay JSON" and reasonably concludes nothing happened. The game is already
     * gone.
     */
    @Test
    void malformedReplayJsonIsRejectedWithoutDestroyingTheGame() {
        SudokuBoard board = new SudokuBoard(2, false, false, 0, "bad-json");
        List<int[]> played = playMoves(board, 3);

        String poisoned = "[{\"row\":0,\"col\":0,\"from\":0,\"to\":1,\"source\":\"NOT_A_SOURCE\"}]";
        assertThrows(IllegalArgumentException.class, () -> board.loadReplayFromJson(poisoned));

        assertEquals(3, board.getReplayHistory().size(),
            "a rejected load must not clear the move history");
        for (int[] m : played) {
            assertEquals(m[2], board.getBoard()[m[0]][m[1]].getValue(),
                "a rejected load must not clear the board");
        }
    }

    /**
     * The same guarantee for an off-board coordinate, which {@link EnhancedMove}'s
     * constructor rejects — the second way a timeline can fail mid-parse, and the one an
     * attacker reaches most easily.
     *
     * <p>The rejection was always correct; what was wrong is that it happened after the
     * board had been cleared. This is the case that makes the ordering matter rather than
     * being a stylistic preference.
     */
    @Test
    void offBoardCoordinatesInReplayJsonLeaveTheGameIntact() {
        SudokuBoard board = new SudokuBoard(2, false, false, 0, "oob-json");
        List<int[]> played = playMoves(board, 2);

        String offBoard = "[{\"row\":99,\"col\":0,\"from\":0,\"to\":1,\"source\":\"PLAYER\"}]";
        assertThrows(IllegalArgumentException.class, () -> board.loadReplayFromJson(offBoard));

        assertEquals(2, board.getReplayHistory().size(),
            "the player's history must survive a rejected load");
        for (int[] m : played) {
            assertEquals(m[2], board.getBoard()[m[0]][m[1]].getValue(),
                "the player's board must survive a rejected load");
        }
    }

    // ---- undo / redo ---------------------------------------------------------------

    /**
     * Undo/redo cycling must not grow {@code replayHistory} without bound.
     *
     * <p>{@code redo()} appended an entry every time; {@code undo()} removed none. A player
     * tapping undo and redo while thinking therefore grew a server-side list by one entry
     * per redo, forever — on a live WebSocket path. The board state stayed correct, which is
     * why nothing ever noticed: only the exported timeline and the replay showed the same
     * move repeated over and over.
     */
    @Test
    void undoRedoCyclingDoesNotGrowTheReplayHistory() {
        SudokuBoard board = new SudokuBoard(2, false, false, 0, "undo-redo-growth");
        playMoves(board, 3);
        int baseline = board.getReplayHistory().size();

        for (int i = 0; i < 50; i++) {
            board.undo();
            board.redo();
        }

        assertEquals(baseline, board.getReplayHistory().size(),
            "fifty undo/redo cycles must leave the history the size it started");
    }

    /** Undo alone shortens the recorded timeline; the undone move is not part of the game. */
    @Test
    void undoRetractsTheMoveFromTheReplayTimeline() {
        SudokuBoard board = new SudokuBoard(2, false, false, 0, "undo-retract");
        playMoves(board, 3);

        board.undo();

        assertEquals(2, board.getReplayHistory().size(),
            "an undone move is not something the player did");
    }

    /**
     * In mirror mode one player action writes a cell and its 180-degree twin. Undo already
     * reverted the pair; redo restored only half of it, leaving the board in a state the
     * game cannot otherwise produce — and that half-state is what gets serialized to Redis
     * and the database.
     */
    @Test
    void redoInMirrorModeRestoresBothHalvesOfThePair() {
        // Board generation is random, and a writable mirror pair is not guaranteed on any
        // given grid, so search a few boards rather than assuming the first one obliges.
        // An earlier draft searched exactly one board at an easy difficulty and passed in
        // isolation while failing in the full suite — a flake I introduced, not a defect it
        // found.
        SudokuBoard board = null;
        int[] cell = null;
        int value = 0;
        for (int attempt = 0; attempt < 30 && cell == null; attempt++) {
            board = new SudokuBoard(5, false, true, 0, "mirror-redo-" + attempt);
            int[] found = findIndependentMirrorPair(board);
            if (found != null) {
                cell = new int[]{found[0], found[1], found[2], found[3]};
                value = found[4];
            }
        }
        assumeMirrorPairFound(cell);

        board.makeMove(cell[0], cell[1], value, SudokuCell.MoveSource.PLAYER);
        assertEquals(value, board.getBoard()[cell[0]][cell[1]].getValue());
        assertEquals(value, board.getBoard()[cell[2]][cell[3]].getValue(),
            "setup: mirror mode must have written the twin");

        board.undo();
        assertEquals(0, board.getBoard()[cell[0]][cell[1]].getValue());
        assertEquals(0, board.getBoard()[cell[2]][cell[3]].getValue(),
            "undo already reverted the pair as a unit");

        board.redo();
        assertEquals(value, board.getBoard()[cell[0]][cell[1]].getValue(),
            "redo must restore the primary cell");
        assertEquals(value, board.getBoard()[cell[2]][cell[3]].getValue(),
            "redo must restore the mirror twin too — half a pair is a board state the game "
                + "cannot otherwise reach, and it is persisted as-is");
    }

    /**
     * Finds {row, col, mirrorRow, mirrorCol, value} such that a single mirror-mode move
     * genuinely writes two cells, or null if this board has no such pair.
     *
     * <p>The twin must be INDEPENDENT of the primary — sharing no row, column or box — or
     * placing the value in the primary makes it illegal in the twin, and
     * {@code applyMirrorMove} correctly declines to write it. A 180-degree twin shares a row
     * when {@code r == 4}, a column when {@code c == 4}, and a box when {@code r} and
     * {@code c} are both in 3..5. With independence guaranteed, the twin write is a
     * consequence of the rules rather than something the test hopes for.
     */
    private static int[] findIndependentMirrorPair(SudokuBoard board) {
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                int mr = 8 - r, mc = 8 - c;
                if (r == mr || c == mc) continue;                       // shares a row or column
                if (r / 3 == mr / 3 && c / 3 == mc / 3) continue;       // shares a box
                if (board.getBoard()[r][c].getValue() != 0) continue;
                if (board.getBoard()[mr][mc].getValue() != 0) continue;
                if (!board.isCellEditable(r, c) || !board.isCellEditable(mr, mc)) continue;
                for (int v = 1; v <= 9; v++) {
                    if (board.isValidMove(r, c, v) && board.isValidMove(mr, mc, v)) {
                        return new int[]{r, c, mr, mc, v};
                    }
                }
            }
        }
        return null;
    }

    private static void assumeMirrorPairFound(int[] cell) {
        assertNotNull(cell, "test setup: no writable mirror pair found on thirty boards");
    }
}
