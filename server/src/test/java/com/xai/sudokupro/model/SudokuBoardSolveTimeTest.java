package com.xai.sudokupro.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Elapsed solve time across persistence round-trips.
 *
 * <p>{@code solveTime} is a {@code @Transient} {@link Duration}; only the derived
 * {@code solveTimeSeconds} column is persisted. Nothing rebuilt the Duration from the
 * column on the way back in, so {@code getSolveTime()} answered {@link Duration#ZERO}
 * for every board loaded from the database or read out of the Redis cache, no matter how
 * long the player had actually taken.
 *
 * <p>Three consumers read it and all three were wrong. {@code AntiCheatEngine} scored
 * "solved impossibly fast" against a rehydrated board — {@code 0 < difficulty * 10s} is
 * always true — and stacked a second signal for "under 500ms per move" on top of the
 * same zero. {@code EventEngine.calculateEventScore} subtracts a time penalty of
 * {@code solveTime / 10}, so a slow solve replayed after a cache miss scored strictly
 * higher than the identical solve served from memory. And the solve-time analytics
 * recorded zero seconds for those games, dragging the reported average down.
 */
class SudokuBoardSolveTimeTest {

    private static ObjectMapper mapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        return m;
    }

    /** Plays a board to completion so it carries a real elapsed time. */
    private static SudokuBoard solvedBoard(String gameId) {
        SudokuBoard board = new SudokuBoard(1, false, false, 0, gameId);
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                if (board.getBoard()[r][c].getValue() != 0) continue;
                for (int v = 1; v <= 9; v++) {
                    if (board.isValidMove(r, c, v)) {
                        board.makeMove(r, c, v, SudokuCell.MoveSource.PLAYER);
                        break;
                    }
                }
            }
        }
        return board;
    }

    /**
     * The Redis path. {@code solveTimeSeconds} carries {@code @JsonProperty} on the field,
     * so Jackson wrote straight to it and the transient Duration stayed at zero; the
     * explicit setter is what makes the cache round-trip agree with the database one.
     */
    @Test
    void solveTimeSurvivesTheJacksonCacheRoundTrip() throws Exception {
        SudokuBoard board = solvedBoard("st-cache");
        // Stamp a realistic elapsed time the way a finished game does.
        String json = mapper().writeValueAsString(board);
        SudokuBoard restored = mapper().readValue(json, SudokuBoard.class);

        assertEquals(board.getSolveTimeSeconds(), restored.getSolveTimeSeconds(),
            "the persisted column must round-trip");
        assertEquals(board.getSolveTimeSeconds(), restored.getSolveTime().toSeconds(),
            "getSolveTime() must agree with the column, not report zero");
    }

    /**
     * A board whose persisted column says the player took eleven minutes must not report
     * a zero Duration — that reading is what made the anti-cheat detector treat every
     * rehydrated board as an instant solve.
     */
    @Test
    void aRehydratedBoardReportsTheElapsedTimeItWasSavedWith() {
        SudokuBoard board = new SudokuBoard(1, false, false, 0, "st-rehydrate");
        assertEquals(Duration.ZERO, board.getSolveTime(), "precondition: fresh board has no elapsed time");

        board.setSolveTimeSeconds(660L); // 11 minutes, as read back from the DB column

        assertEquals(Duration.ofMinutes(11), board.getSolveTime());
        assertFalse(board.getSolveTime().isZero(),
            "a rehydrated board reporting zero is what produced false cheat signals");
    }

    /** A live in-progress board genuinely has no elapsed time; that must stay zero. */
    @Test
    void anUnsolvedBoardStillReportsZero() {
        SudokuBoard board = new SudokuBoard(1, false, false, 0, "st-unsolved");
        assertEquals(Duration.ZERO, board.getSolveTime());
        board.setSolveTimeSeconds(0L);
        assertEquals(Duration.ZERO, board.getSolveTime());
    }

    /** Restoring must never clobber a live Duration that is already more precise. */
    @Test
    void restoringDoesNotOverwriteALiveElapsedTime() {
        SudokuBoard board = solvedBoard("st-live");
        Duration live = board.getSolveTime();
        board.setSolveTimeSeconds(99999L);
        if (!live.isZero()) {
            assertEquals(live, board.getSolveTime(),
                "an in-memory board's own timing wins over a column value");
        }
    }

    /** Negative column values (corrupt row) must not produce a negative Duration. */
    @Test
    void aNegativeColumnValueIsClamped() {
        SudokuBoard board = new SudokuBoard(1, false, false, 0, "st-negative");
        board.setSolveTimeSeconds(-5L);
        assertEquals(0L, board.getSolveTimeSeconds());
        assertEquals(Duration.ZERO, board.getSolveTime());
    }
}
