package com.xai.sudokupro.model.api;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import com.xai.sudokupro.model.SudokuCellView;

import java.util.ArrayList;
import java.util.List;

/**
 * Wire representation of a game board, shared by the server (which produces it
 * from the authoritative {@link SudokuBoard}) and the desktop client (which
 * reconstructs a local {@link SudokuBoard} from it).
 *
 * <p>Deliberately contains only what a player is allowed to see: current cell
 * values, given flags, pencil marks, and conflict state. The solution is never
 * serialized — it exists only inside the server's solver.
 */
public record BoardState(
    String gameId,
    String playerId,
    int difficulty,
    boolean chaosMode,
    boolean mirrorMode,
    boolean solved,
    int lives,
    int score,
    int hintCount,
    int moveCount,
    List<List<SudokuCellView>> cells
) {

    /** Builds the wire representation from the server's authoritative board. */
    public static BoardState from(SudokuBoard board) {
        SudokuCell[][] grid = board.getBoard();
        List<List<SudokuCellView>> cells = new ArrayList<>(grid.length);
        for (SudokuCell[] row : grid) {
            List<SudokuCellView> viewRow = new ArrayList<>(row.length);
            for (SudokuCell cell : row) viewRow.add(new SudokuCellView(cell));
            cells.add(viewRow);
        }
        return new BoardState(board.getGameId(), board.getPlayerId(), board.getDifficulty(),
            board.isChaosMode(), board.isMirrorMode(), board.isSolved(),
            board.getLives(), board.getScore(), board.getHintCount(),
            board.getMoveHistory().size(), cells);
    }

    /**
     * Reconstructs a local, non-authoritative {@link SudokuBoard} from this
     * state — used by the remote client to drive the UI between server syncs.
     */
    public SudokuBoard toBoard() {
        SudokuCell[][] grid = new SudokuCell[cells.size()][];
        for (int r = 0; r < cells.size(); r++) {
            List<SudokuCellView> viewRow = cells.get(r);
            grid[r] = new SudokuCell[viewRow.size()];
            for (int c = 0; c < viewRow.size(); c++) {
                SudokuCellView v = viewRow.get(c);
                SudokuCell cell = new SudokuCell();
                if (v.value() != 0) {
                    cell.setValue(v.value(),
                        v.moveSource() != null ? v.moveSource() : SudokuCell.MoveSource.INITIAL);
                }
                cell.setGiven(v.isGiven());
                if (v.pencilMarks() != null) v.pencilMarks().forEach(cell::addPencilMark);
                if (v.conflicts() != null) v.conflicts().forEach(cell::addConflict);
                grid[r][c] = cell;
            }
        }
        SudokuBoard board = new SudokuBoard(grid, chaosMode, mirrorMode, 0, gameId);
        board.setPlayerId(playerId);
        board.setDifficulty(difficulty);
        board.setLives(lives);
        return board;
    }
}
