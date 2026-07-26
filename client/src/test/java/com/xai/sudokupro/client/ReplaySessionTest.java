package com.xai.sudokupro.client;

import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the defect class "a feature assembled from the wrong data structure".
 *
 * <p>Replay read {@code SudokuBoard.getMoveHistory()}. That deque is
 * {@code push}ed, so a copy of it iterates <em>newest first</em> — the replay ran
 * the game backwards. It then pushed those moves at the server after calling the
 * asynchronous {@code resetBoard()}, which both raced the replay and generated a
 * completely different puzzle for the old moves to be rejected by.
 *
 * <p>The ordering half is what these tests pin down, and they pin it against a
 * real {@link SudokuBoard} rather than a hand-built list: the bug was a wrong
 * belief about the model's own accessors, so asserting against a list this test
 * built itself would prove nothing.
 */
class ReplaySessionTest {

    private SudokuBoard playableBoard() {
        SudokuCell[][] grid = new SudokuCell[9][9];
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) grid[r][c] = new SudokuCell();
        }
        return new SudokuBoard(grid, false, false, 0, "g1");
    }

    /** The reproduction: three moves in, three moves out, in the order they were played. */
    @Test
    void replaysThePlayersMovesOldestFirst() {
        SudokuBoard board = playableBoard();
        board.applyExternalMove(new EnhancedMove(0, 0, 0, 1, SudokuCell.MoveSource.PLAYER));
        board.applyExternalMove(new EnhancedMove(1, 1, 0, 2, SudokuCell.MoveSource.PLAYER));
        board.applyExternalMove(new EnhancedMove(2, 2, 0, 3, SudokuCell.MoveSource.PLAYER));

        ReplaySession session = ReplaySession.of(board);

        assertEquals(3, session.size());
        assertEquals(1, session.next().newVal(), "The first move played must replay first");
        assertEquals(2, session.next().newVal());
        assertEquals(3, session.next().newVal());
        assertFalse(session.hasNext());
    }

    @Test
    void stepsThroughEveryMoveExactlyOnce() {
        List<EnhancedMove> moves = List.of(
            new EnhancedMove(0, 0, 0, 1, SudokuCell.MoveSource.PLAYER),
            new EnhancedMove(0, 1, 0, 2, SudokuCell.MoveSource.PLAYER));
        ReplaySession session = new ReplaySession(moves);

        int seen = 0;
        while (session.hasNext()) {
            session.next();
            seen++;
            assertTrue(seen <= 5, "hasNext must eventually be false");
        }
        assertEquals(2, seen);
    }

    @Test
    void progressTextCountsUpAsItGoes() {
        ReplaySession session = new ReplaySession(List.of(
            new EnhancedMove(0, 0, 0, 1, SudokuCell.MoveSource.PLAYER),
            new EnhancedMove(0, 1, 0, 2, SudokuCell.MoveSource.PLAYER)));

        assertEquals("Replaying 0/2", session.progressText());
        session.next();
        assertEquals("Replaying 1/2", session.progressText());
        session.next();
        assertEquals("Replaying 2/2", session.progressText());
    }

    @Test
    void nextPastTheEndThrowsRatherThanSilentlyRepeating() {
        ReplaySession session = new ReplaySession(List.of(
            new EnhancedMove(0, 0, 0, 1, SudokuCell.MoveSource.PLAYER)));
        session.next();
        assertThrows(NoSuchElementException.class, session::next);
    }

    /** A board with no moves is the common case right after a new game; it must not blow up. */
    @Test
    void aFreshBoardHasNothingToReplay() {
        ReplaySession session = ReplaySession.of(playableBoard());
        assertTrue(session.isEmpty());
        assertEquals(0, session.size());
        assertFalse(session.hasNext());
    }

    @Test
    void aNullBoardIsAnEmptyReplayNotACrash() {
        assertTrue(ReplaySession.of(null).isEmpty());
        assertTrue(new ReplaySession(null).isEmpty());
    }

    /** The session is a snapshot: play continuing during a replay must not mutate it. */
    @Test
    void theSnapshotIsIndependentOfLaterPlay() {
        SudokuBoard board = playableBoard();
        board.applyExternalMove(new EnhancedMove(0, 0, 0, 1, SudokuCell.MoveSource.PLAYER));
        ReplaySession session = ReplaySession.of(board);

        board.applyExternalMove(new EnhancedMove(0, 1, 0, 2, SudokuCell.MoveSource.PLAYER));

        assertEquals(1, session.size());
    }
}
