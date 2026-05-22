package com.xai.sudokupro.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.xai.sudokupro.model.SudokuCell.MoveSource;

import java.io.Serializable;
import java.util.Objects;

@JsonPropertyOrder({"row", "col", "oldVal", "newVal", "source"})
public record EnhancedMove(
    @JsonProperty("row")    int row,
    @JsonProperty("col")    int col,
    @JsonProperty("oldVal") int oldVal,
    @JsonProperty("newVal") int newVal,
    @JsonProperty("source") MoveSource source
) implements Serializable {

    // Canonical validation
    public EnhancedMove {
        if (row < -1 || row > 8 || col < -1 || col > 8)
            throw new IllegalArgumentException("Row/col must be -1..8, got (" + row + "," + col + ")");
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
