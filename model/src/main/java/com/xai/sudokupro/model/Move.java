package com.xai.sudokupro.model;

import com.xai.sudokupro.model.SudokuCell.MoveSource;
import java.io.Serializable;

public record Move(
    int row,
    int col,
    int oldVal,
    int newVal,
    MoveSource source
) implements Serializable {

    public Move(int row, int col, int oldVal, int newVal) {
        this(row, col, oldVal, newVal, MoveSource.PLAYER);
    }
}
