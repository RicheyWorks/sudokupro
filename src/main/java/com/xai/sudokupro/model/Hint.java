package com.xai.sudokupro.model;

import com.xai.sudokupro.model.SudokuCell.Strategy;
import java.util.List;

public record Hint(
    int row,
    int col,
    Object value,      // Integer or List<Integer>
    String reason,
    Strategy strategy
) {
    public Hint(int row, int col, int value, String reason) {
        this(row, col, (Object)(Integer) value, reason, Strategy.UNKNOWN);
    }

    public Hint(int row, int col, List<Integer> values, String reason) {
        this(row, col, (Object) values, reason, Strategy.UNKNOWN);
    }

    public Hint(int row, int col, Object value, String reason) {
        this(row, col, value, reason, Strategy.UNKNOWN);
    }
}
