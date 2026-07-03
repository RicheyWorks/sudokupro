package com.xai.sudokupro.ui;

import com.xai.sudokupro.client.GameClient;
import com.xai.sudokupro.client.Notifier;
import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * The 9×9 grid view. Networked (AUDIT follow-up: client/server separation):
 * all game mutations go through {@link GameClient}; the local board instance
 * is read through the client because server resyncs replace it mid-game.
 */
public class BoardView {
    private static final Logger logger = LoggerFactory.getLogger(BoardView.class);
    private static final int    GRID_SIZE   = 9;
    private static final double CELL_SIZE   = 60.0;

    private final GridPane           grid;
    private final GameClient         client;
    private final Notifier           notifier;
    private final TextField[][]      cellFields;
    private final HBox               controlsBox;
    private final ListView<String>   moveHistoryList;
    private final ComboBox<String>   historyFilter;
    private final ProgressBar        difficultyProgress;

    // Bug 2 fix: guard programmatic setText calls so the text listener doesn't re-fire
    private final AtomicBoolean updatingCell = new AtomicBoolean(false);
    // Bug 1 fix: backing list that is never filtered, so filter toggling doesn't lose entries
    private final List<String>  allMoveHistory = new ArrayList<>();

    private boolean       pencilMode      = false;
    private boolean       isPaused        = false;
    private int           highlightedRow  = -1;
    private int           highlightedCol  = -1;
    private Label         statusLabel;
    private ToggleButton  pencilToggle;
    private ToggleButton  autoSolveToggle;

    public BoardView(GameClient client, Notifier notifier) {
        this.client   = Objects.requireNonNull(client);
        this.notifier = Objects.requireNonNull(notifier);
        this.grid                 = new GridPane();
        this.cellFields           = new TextField[GRID_SIZE][GRID_SIZE];
        this.controlsBox          = new HBox(10);
        this.moveHistoryList      = new ListView<>();
        this.historyFilter        = new ComboBox<>();
        this.difficultyProgress   = new ProgressBar(0);
        grid.setPadding(new Insets(10));
        grid.setHgap(2); grid.setVgap(2);
        grid.setBackground(new Background(new BackgroundFill(Color.BLACK, null, null)));
        initializeGrid();
        initializeControls();
        initializeMoveHistory();
        updateDifficultyProgress();
        logger.info("BoardView initialized for game {}", board().getGameId());
    }

    /**
     * The local board is read through the client on every access: undo/redo
     * and server pushes REPLACE the instance with a fresh reconstruction.
     */
    private SudokuBoard board() {
        SudokuBoard board = client.board();
        if (board == null) throw new IllegalStateException("No active game");
        return board;
    }

    // =====================================================================
    // Grid init
    // =====================================================================

    private void initializeGrid() {
        SudokuCell[][] cells = board().getBoard();   // direct access – no copy needed at init
        validateCells(cells);
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                TextField cell = createCell(row, col, cells[row][col]);
                cellFields[row][col] = cell;
                grid.add(cell, col, row);
                // Box borders are now applied by updateCellStyle so they survive every refresh.
            }
        }
    }

    private TextField createCell(int row, int col, SudokuCell sudokuCell) {
        TextField cell = new TextField();
        cell.setPrefSize(CELL_SIZE, CELL_SIZE);
        cell.setFont(Font.font("Arial", 18));
        cell.setStyle(difficultyStyle(board().getDifficulty()) + "-fx-alignment: center;");

        // Digit-only filter
        UnaryOperator<TextFormatter.Change> filter = change -> {
            if (change.getControlNewText().matches("[0-9]?")) return change;
            return null;
        };
        cell.setTextFormatter(new TextFormatter<>(filter));

        int value = sudokuCell.getValue();
        if (value != 0) {
            cell.setText(String.valueOf(value));
            cell.setEditable(false);
            cell.setStyle(cell.getStyle() + "-fx-background-color: #4B4B4B;");
        }

        cell.textProperty().addListener((ChangeListener<String>) (obs, oldVal, newVal) -> {
            // Bug 2 fix: ignore programmatic setText() fired from refreshCell()
            if (updatingCell.get()) return;
            try {
                SudokuBoard board = board();
                if (!board.isCellEditable(row, col)) return;
                int v = newVal.isEmpty() ? 0 : Integer.parseInt(newVal);
                if (pencilMode && v != 0) {
                    board.getBoard()[row][col].togglePencilMark(v);
                    refreshCell(row, col);
                } else if (v >= 0 && v <= 9) {
                    int oldV = board.getBoard()[row][col].getValue();
                    EnhancedMove move = new EnhancedMove(row, col, oldV, v, SudokuCell.MoveSource.PLAYER);
                    client.applyMove(move);
                    refreshCell(row, col);
                    updateMoveHistory(move);
                    updateDifficultyProgress();
                }
            } catch (Exception e) {
                logger.error("Move failed at ({},{}): {}", row, col, e.getMessage());
                notifier.notify("error", "Invalid move: " + e.getMessage());
                Platform.runLater(() -> cell.setText(oldVal));
            }
        });

        cell.setOnKeyPressed(event -> {
            switch (event.getCode()) {
                case SPACE -> { togglePencilMode(); event.consume(); }
                case H     -> { requestHint();      event.consume(); }
                case Z     -> { if (event.isControlDown()) { undoMove();  event.consume(); } }
                case Y     -> { if (event.isControlDown()) { redoMove();  event.consume(); } }
            }
        });

        cell.setOnMouseClicked((MouseEvent e) -> highlightCells(row, col));

        cell.focusedProperty().addListener((obs, was, now) -> {
            if (now) cell.setStyle(cell.getStyle() + "-fx-border-color: #00FFFF; -fx-border-width: 2;");
            else     updateCellStyle(cell, row, col);
        });

        return cell;
    }

    // =====================================================================
    // Controls
    // =====================================================================

    private void initializeControls() {
        statusLabel = new Label("Game: " + board().getGameId());
        statusLabel.setStyle("-fx-text-fill: #FFD700;");

        pencilToggle = new ToggleButton("Pencil");
        pencilToggle.setOnAction(e -> togglePencilMode());

        Button hintButton = new Button("Hint");
        hintButton.setOnAction(e -> requestHint());

        Button undoButton = new Button("Undo");
        undoButton.setOnAction(e -> undoMove());

        Button redoButton = new Button("Redo");
        redoButton.setOnAction(e -> redoMove());

        autoSolveToggle = new ToggleButton("Auto-Solve: Off");
        autoSolveToggle.setOnAction(e -> toggleAutoSolve());

        Button resolveConflictsButton = new Button("Fix Conflicts");
        resolveConflictsButton.setOnAction(e -> resolveConflicts());

        historyFilter.getItems().addAll("All", "Player Moves", "Hints");
        historyFilter.setValue("All");
        historyFilter.setOnAction(e -> filterMoveHistory());

        difficultyProgress.setPrefWidth(100);
        difficultyProgress.setStyle("-fx-accent: #00FFFF;");

        controlsBox.getChildren().addAll(statusLabel, pencilToggle, hintButton,
            undoButton, redoButton, autoSolveToggle, resolveConflictsButton,
            historyFilter, difficultyProgress);
        controlsBox.setPadding(new Insets(10));
        controlsBox.setAlignment(Pos.CENTER);
    }

    private void initializeMoveHistory() {
        moveHistoryList.setPrefHeight(100);
        moveHistoryList.setStyle("-fx-background-color: #1E1E1E; -fx-text-fill: #FFFFFF;");
    }

    // =====================================================================
    // Actions
    // =====================================================================

    public void setPaused(boolean p) { this.isPaused = p; }

    private void togglePencilMode() {
        pencilMode = !pencilMode;
        pencilToggle.setText(pencilMode ? "Pencil: ON" : "Pencil: OFF");
    }

    /** Fetches a hint from the server (network call — off the FX thread). */
    public void requestHint() {
        Thread fetcher = new Thread(() -> {
            try {
                String hint = client.hint();
                notifier.notify("hint", hint);
                refresh();
                updateMoveHistory(new EnhancedMove(-1, -1, 0, SudokuCell.MoveSource.HINT));
            } catch (Exception e) {
                logger.error("Hint failed: {}", e.getMessage());
                notifier.notify("error", "Hint failed: " + e.getMessage());
            }
        }, "sudokupro-hint");
        fetcher.setDaemon(true);
        fetcher.start();
    }

    private void undoMove() {
        try {
            // Server-side undo; the fresh board arrives as a "board" envelope
            // and triggers a full refresh through MainStage's onBoardChanged.
            client.undo();
            // Bug 1 fix: remove from allMoveHistory and re-apply filter
            if (!allMoveHistory.isEmpty())
                allMoveHistory.remove(allMoveHistory.size() - 1);
            Platform.runLater(this::filterMoveHistory);
            notifier.notify("ui", "Move undone");
        } catch (Exception e) {
            logger.error("Undo failed: {}", e.getMessage());
        }
    }

    private void redoMove() {
        try {
            client.redo();
            notifier.notify("ui", "Move redone");
        } catch (Exception e) {
            logger.error("Redo failed: {}", e.getMessage());
        }
    }

    /** One-shot server-side auto-solve (network call — off the FX thread). */
    private void toggleAutoSolve() {
        if (!autoSolveToggle.isSelected()) {
            autoSolveToggle.setText("Auto-Solve: Off");
            return;
        }
        autoSolveToggle.setText("Auto-Solve: On");
        Thread solver = new Thread(() -> {
            try {
                client.solve();
                notifier.notify("game", "Puzzle auto-solved!");
            } catch (Exception e) {
                logger.error("Auto-solve error: {}", e.getMessage());
                notifier.notify("error", "Auto-solve failed: " + e.getMessage());
            } finally {
                Platform.runLater(() -> {
                    autoSolveToggle.setSelected(false);
                    autoSolveToggle.setText("Auto-Solve: Off");
                });
            }
        }, "sudokupro-solve");
        solver.setDaemon(true);
        solver.start();
    }

    private void resolveConflicts() {
        boolean resolved = false;
        SudokuBoard board = board();
        for (int r = 0; r < GRID_SIZE; r++) for (int c = 0; c < GRID_SIZE; c++) {
            SudokuCell cell = board.getBoard()[r][c];
            if (cell.isConflicted() && !cell.isGiven()) {
                EnhancedMove clear = new EnhancedMove(r, c, cell.getValue(), 0, SudokuCell.MoveSource.PLAYER);
                try {
                    client.applyMove(clear);
                    updateMoveHistory(clear);
                    resolved = true;
                } catch (Exception e) {
                    logger.error("resolveConflicts failed at ({},{}): {}", r, c, e.getMessage());
                }
            }
        }
        if (resolved) { refresh(); notifier.notify("ui", "Conflicts resolved"); }
    }

    // =====================================================================
    // Rendering helpers
    // =====================================================================

    public void refresh() {
        validateCells(board().getBoard());
        for (int r = 0; r < GRID_SIZE; r++) for (int c = 0; c < GRID_SIZE; c++) refreshCell(r, c);
        updateStatus();
        updateDifficultyProgress();
    }

    private void refreshCell(int row, int col) {
        TextField cell = cellFields[row][col];
        SudokuCell sc = board().getBoard()[row][col];
        String text = sc.getValue() == 0
            ? (sc.getPencilMarks().isEmpty() ? "" : formatPencilMarks(sc.getPencilMarks()))
            : String.valueOf(sc.getValue());
        // Both setText and setStyle/setFont must run on the JavaFX Application Thread.
        // Batch them in a single Platform.runLater so the guard flag is also set on-thread.
        Platform.runLater(() -> {
            if (!cell.getText().equals(text)) {
                updatingCell.set(true);
                try {
                    cell.setText(text);
                } finally {
                    updatingCell.set(false);
                }
            }
            updateCellStyle(cell, row, col);
        });
    }

    private void updateCellStyle(TextField cell, int row, int col) {
        SudokuCell sc = board().getBoard()[row][col];
        StringBuilder style = new StringBuilder(difficultyStyle(board().getDifficulty()) + "-fx-alignment: center;");

        // Background — priority order: conflict > given > pencil > highlight > default
        if (sc.isConflicted())
            style.append("-fx-background-color: #FF5555;");
        else if (sc.isGiven())
            style.append("-fx-background-color: #4B4B4B;");
        else if (!sc.getPencilMarks().isEmpty())
            style.append("-fx-background-color: #2F2F5F;");
        else if (row == highlightedRow || col == highlightedCol)
            style.append("-fx-background-color: #3A3A6A;");
        else
            style.append("-fx-background-color: #1E1E1E;");

        // 3×3 box borders — re-applied every time so they survive style rebuilds.
        // Corner cells (both row%3==0 and col%3==0) get combined top+left border.
        if (row == highlightedRow && col == highlightedCol) {
            // Selected cell: highlight border overrides box border colour.
            String topW  = (row % 3 == 0) ? "2" : "0";
            String leftW = (col % 3 == 0) ? "2" : "0";
            style.append(String.format(
                "-fx-border-width: %s 0 0 %s; -fx-border-color: #FF00FF #FFD700 #FFD700 #FF00FF;",
                topW, leftW));
        } else {
            String topW  = (row % 3 == 0) ? "2" : "0";
            String leftW = (col % 3 == 0) ? "2" : "0";
            style.append(String.format("-fx-border-width: %s 0 0 %s; -fx-border-color: #FFD700;",
                topW, leftW));
        }

        cell.setStyle(style.toString());
        cell.setFont(Font.font("Arial", sc.getPencilMarks().isEmpty() ? 18 : 12));
    }

    private void highlightCells(int row, int col) {
        highlightedRow = row; highlightedCol = col;
        for (int r = 0; r < GRID_SIZE; r++) for (int c = 0; c < GRID_SIZE; c++)
            updateCellStyle(cellFields[r][c], r, c);
    }

    private String difficultyStyle(int d) {
        String color = switch (d) {
            case 1 -> "#00FF00"; case 2 -> "#FFFF00"; case 3 -> "#FF4500"; case 4 -> "#FF0000";
            default -> "#FFFFFF";
        };
        return "-fx-background-color: #1E1E1E; -fx-text-fill: " + color + ";";
    }

    private String formatPencilMarks(Set<Integer> marks) {
        return marks.stream().sorted().map(String::valueOf).collect(Collectors.joining(" "));
    }

    private void updateMoveHistory(EnhancedMove move) {
        String text = move.source() == SudokuCell.MoveSource.HINT ? "Hint Applied"
            : String.format("(%d,%d)=%d", move.row()+1, move.col()+1, move.newVal());
        // Bug 1 fix: append to the immutable backing list, then re-render
        Platform.runLater(() -> {
            allMoveHistory.add(text);
            filterMoveHistory();
        });
    }

    private void filterMoveHistory() {
        // Bug 1 fix: filter from allMoveHistory, not from the currently displayed list,
        // so switching the filter back to "All" restores entries that were hidden.
        String filter = historyFilter.getValue();
        List<String> filtered = allMoveHistory.stream().filter(item -> switch (filter) {
            case "Player Moves" -> !item.equals("Hint Applied");
            case "Hints"        -> item.equals("Hint Applied");
            default             -> true;
        }).collect(Collectors.toList());
        moveHistoryList.getItems().setAll(filtered);
    }

    private void updateDifficultyProgress() {
        int filled = 0;
        SudokuCell[][] cells = board().getBoard();
        for (int r = 0; r < GRID_SIZE; r++) for (int c = 0; c < GRID_SIZE; c++)
            if (cells[r][c].getValue() != 0) filled++;
        double p = (double) filled / (GRID_SIZE * GRID_SIZE);
        Platform.runLater(() -> {
            difficultyProgress.setProgress(p);
            if (p == 1.0) animateSolvedCells();
        });
    }

    private void animateSolvedCells() {
        for (int r = 0; r < GRID_SIZE; r++) for (int c = 0; c < GRID_SIZE; c++) {
            FadeTransition ft = new FadeTransition(Duration.millis(500), cellFields[r][c]);
            ft.setFromValue(1.0); ft.setToValue(0.5); ft.setAutoReverse(true); ft.setCycleCount(2);
            ft.play();
        }
    }

    private void updateStatus() {
        SudokuBoard board = board();
        String s = board.isSolved() ? "Solved!" : "In Progress";
        Platform.runLater(() -> statusLabel.setText("Game: " + board.getGameId() + " | " + s));
    }

    private void validateCells(SudokuCell[][] cells) {
        if (cells == null || cells.length != GRID_SIZE)
            throw new IllegalStateException("Board must be " + GRID_SIZE + "x" + GRID_SIZE);
        for (int i = 0; i < GRID_SIZE; i++)
            if (cells[i] == null || cells[i].length != GRID_SIZE)
                throw new IllegalStateException("Row " + i + " invalid");
    }

    // =====================================================================
    // Public API
    // =====================================================================

    public VBox getView() {
        VBox root = new VBox(10, controlsBox, grid, moveHistoryList);
        root.setStyle("-fx-background-color: #000000;");
        return root;
    }

    public SudokuBoard getBoard() { return board(); }
}
