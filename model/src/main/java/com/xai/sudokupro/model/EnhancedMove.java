package com.xai.sudokupro.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.xai.sudokupro.model.SudokuCell.MoveSource;

import java.io.Serializable;
import java.util.Objects;

@JsonPropertyOrder({"row", "col", "oldVal", "newVal", "source"})
// Tolerate properties this record no longer accepts. Redis cache entries written
// before getValue() was marked @JsonIgnore carry a "value" alias, and without this
// they fail to deserialize — see the note on getValue() below.
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record EnhancedMove(
    @JsonProperty("row")    int row,
    @JsonProperty("col")    int col,
    @JsonProperty("oldVal") int oldVal,
    @JsonProperty("newVal") int newVal,
    @JsonProperty("source") MoveSource source
) implements Serializable {

    // Canonical validation
    public EnhancedMove {
        // 0..8, not -1..8. The lower bound was off by one and -1 is not a sentinel
        // anywhere in the codebase, so a move at row or column -1 passed this guard and
        // then threw ArrayIndexOutOfBoundsException deep inside the board — surfacing as a
        // raw "Index -1 out of bounds for length 9" instead of a clean rejection. Found by
        // the live engine feeding out-of-range coordinates over the WebSocket.
        if (row < 0 || row > 8 || col < 0 || col > 8)
            throw new IllegalArgumentException("Row/col must be 0..8, got (" + row + "," + col + ")");
        if (oldVal < 0 || oldVal > 9 || newVal < 0 || newVal > 9)
            throw new IllegalArgumentException("Values must be 0-9");
        Objects.requireNonNull(source, "MoveSource cannot be null");
    }

    /** Convenience: oldVal defaults to 0. */
    public EnhancedMove(int row, int col, int newVal, MoveSource source) {
        this(row, col, 0, newVal, source);
    }

    // ---- JavaBean-style getters so existing callers compile unchanged ----
    public int getRow()       { return row; }
    public int getCol()       { return col; }
    public int getOldVal()    { return oldVal; }
    /**
     * Alias for {@link #newVal()}, kept for existing callers.
     *
     * <p>MUST stay {@code @JsonIgnore}. Jackson treats this bean getter as a property
     * named {@code "value"}, but the record's canonical creator only accepts
     * row/col/oldVal/newVal/source — so a serialized move could be written but never
     * read back. {@code SudokuBoard} exposes {@code getReplayHistory()}, so every board
     * with at least one move serialized a {@code replayHistory} array of these, and
     * deserializing it threw {@code UnrecognizedPropertyException: "value"}. That made
     * the entire Redis cache entry unreadable for any board that had been played, and
     * {@code GameService.getGame} does not guard the Redis read — so it threw instead of
     * falling back to the database, 500-ing every read of that game for the cache TTL.
     */
    @JsonIgnore
    public int getValue()     { return newVal; }   // alias for newVal
    public int getNewVal()    { return newVal; }
    public MoveSource getSource() { return source; }

    /** Return a copy with a different newVal (records are immutable). */
    public EnhancedMove withValue(int v) {
        return new EnhancedMove(row, col, oldVal, v, source);
    }

    public static EnhancedMove of(int row, int col, int oldVal, int newVal, MoveSource source) {
        return new EnhancedMove(row, col, oldVal, newVal, source);
    }

    @Override
    public String toString() {
        return "Move[" + row + "," + col + "] " + oldVal + "→" + newVal + " via " + source;
    }
}
