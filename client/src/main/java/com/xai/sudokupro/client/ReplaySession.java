package com.xai.sudokupro.client;

import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.SudokuBoard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Steps through a finished game's moves, oldest first, for the Replay feature.
 *
 * <p><b>What Replay used to do.</b> It captured {@code board.getMoveHistory()},
 * called the <em>asynchronous</em> {@code resetBoard()}, and then immediately
 * started a thread pushing those moves at the server. Three things were wrong at
 * once and it could never have worked:
 * <ul>
 *   <li>It raced its own reset — the replay thread began sending while
 *       {@code newGame} was still in flight, so the first moves landed on the game
 *       that was about to be discarded.</li>
 *   <li>{@code resetBoard} generates a <em>different puzzle</em>. Replaying one
 *       game's moves onto another game's grid is not a replay; it is a stream of
 *       moves the server rejects as invalid.</li>
 *   <li>{@code getMoveHistory()} copies a deque that is {@code push}ed, so it
 *       iterates newest-first. Even on the right board the replay ran
 *       backwards.</li>
 * </ul>
 *
 * <p>This class replays the moves the player actually made, in the order they
 * made them, over the board that is on screen — no new game, no server
 * mutation, and therefore no race to lose. It reads
 * {@link SudokuBoard#getReplayHistory()}, which is appended in chronological
 * order, rather than the LIFO undo stack.
 */
public final class ReplaySession {

    private final List<EnhancedMove> moves;
    private int index;

    public ReplaySession(List<EnhancedMove> moves) {
        this.moves = moves == null ? List.of() : List.copyOf(moves);
    }

    /** The chronological moves of the board currently on screen. */
    public static ReplaySession of(SudokuBoard board) {
        if (board == null) return new ReplaySession(List.of());
        List<EnhancedMove> history = board.getReplayHistory();
        return new ReplaySession(history == null ? List.of() : new ArrayList<>(history));
    }

    public int size() {
        return moves.size();
    }

    public boolean isEmpty() {
        return moves.isEmpty();
    }

    public boolean hasNext() {
        return index < moves.size();
    }

    /** The next move, advancing the cursor. */
    public EnhancedMove next() {
        if (!hasNext()) throw new NoSuchElementException("Replay is finished");
        return moves.get(index++);
    }

    /** 1-based position of the move {@link #next()} last returned; 0 before the first. */
    public int position() {
        return index;
    }

    /** Progress text for the status line, e.g. {@code "Replaying 3/27"}. */
    public String progressText() {
        return "Replaying " + position() + "/" + size();
    }

    /** Every move, oldest first — for a caller that wants them all at once. */
    public List<EnhancedMove> moves() {
        return Collections.unmodifiableList(moves);
    }
}
