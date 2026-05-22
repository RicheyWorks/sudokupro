package com.xai.sudokupro.ui;

import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import com.xai.sudokupro.service.AuthService;
import com.xai.sudokupro.service.GameService;
import com.xai.sudokupro.service.NotificationService;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
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
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public class BoardView {
    private static final Logger logger = LoggerFactory.getLogger(BoardView.class);
    private static final int    GRID_SIZE   = 9;
    private static final double CELL_SIZE   = 60.0;
    private static final Tags   GLOBAL_TAGS = Tags.of("app", "SudokuPro");

    private final GridPane           grid;
    private final SudokuBoard        board;
    private final GameService        gameService;
    private final NotificationService notificationService;
    private final MultiplayerBroadcaster multiplayerBroadcaster;
    private final MeterRegistry      meterRegistry;
    private final AuthService        authService;
    private final TextField[][]      cellFields;
    private final HBox               controlsBox;
    private final ListView<String>   moveHistoryList;
    private final ComboBox<String>   historyFilter;
    private final ProgressBar        difficultyProgress;

    private boolean       pencilMode      = false;
    private boolean       isPaused        = false;
    private int           highlightedRow  = -1;
    private int           highlightedCol  = -1;
    private Label         statusLabel;
    private ToggleButton  pencilToggle;
    private Button        hintButton;
    private Button        undoButton;
    private Button        redoButton;
    private ToggleButton  autoSolveToggle;
    private Button        resolveConflictsButton;

    public BoardView(SudokuBoard board, GameService gameService,
                     NotificationService notificationService,
                     MultiplayerBroadcaster multiplayerBroadcaster,
                     MeterRegistry meterRegistry, AuthService authService) {
        this.board                = Objects.requireNonNull(board);
        this.gameService          = Objects.requireNonNull(gameService);
        this.notificationService  = Objects.requireNonNull(notificationService);
        this.multiplayerBroadcaster = Objects.requireNonNull(multiplayerBroadcaster);
        this.meterRegistry        = Objects.requireNonNull(meterRegistry);
        this.authService          = Objects.requireNonNull(authService);
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
        subscribeToMultiplayerUpdates();
        updateDifficultyProgress();
        logger.info("BoardView initialized for game {}", board.getGameId());
    }

    // =====================================================================
    // Grid init
    // =====================================================================

    private void initializeGrid() {
        SudokuCell[][] cells = board.getBoard();   // direct access – no copy needed at init
        validateCells(cells);
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                TextField cell = createCell(row, col, cells[row][col]);
                cellFields[row][col] = cell;
                grid.add(cell, col, row);
                // Box borders
                String border = "";
                if (row % 3 == 0) border += "-fx-border-width: 2 0 0 0; -fx-border-color: #FFD700;";
                if (col % 3 == 0) border += "-fx-border-width: 0 0 0 2; -fx-border-color: #FFD700;";
                if (!border.isEmpty()) cell.setStyle(cell.getStyle() + border);
            }
        }
    }

    private TextField createCell(int row, int col, SudokuCell sudokuCell) {
        TextField cell = new TextField();
        cell.setPrefSize(CELL_SIZE, CELL_SIZE);
        cell.setFont(Font.font("Arial", 18));
        cell.setStyle(difficultyStyle(board.getDifficulty()) + "-fx-alignment: center;");

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
            String playerId = authService.getCurrentPlayerId();
            try {
                if (!board.isCellEditable(row, col)) return;
                int v = newVal.isEmpty() ? 0 : Integer.parseInt(newVal);
                if (pencilMode && v != 0) {
                    board.getBoard()[row][col].togglePencilMark(v);
                    refreshCell(row, col);
                } else if (v >= 0 && v <= 9) {
                    int oldV = board.getBoard()[row][col].getValue();
                    EnhancedMove move = new EnhancedMove(row, col, oldV, v, SudokuCell.MoveSource.PLAYER);
                    gameService.applyMove(board.getGameId(), move, playerId);
                    refreshCell(row, col);
                    updateMoveHistory(move);
                    updateDifficultyProgress();
                    meterRegistry.counter("sudokupro.ui.moves", GLOBAL_TAGS).increment();
                }
            } catch (Exception e) {
                logger.error("Move failed at ({},{}): {}", row, col, e.getMessage());
                notificationService.sendNotification(playerId, "Invalid move: " + e.getMessage());
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
        statusLabel = new Label("Game: " + board.getGameId());
        statusLabel.setStyle("-fx-text-fill: #FFD700;");

        pencilToggle = new ToggleButton("Pencil");
        pencilToggle.setOnAction(e -> togglePencilMode());

        hintButton = new Button("Hint");
        hintButton.setOnAction(e -> requestHint());

        undoButton = new Button("Undo");
        undoButton.setOnAction(e -> undoMove());

        redoButton = new Button("Redo");
        redoButton.setOnAction(e -> redoMove());

        autoSolveToggle = new ToggleButton("Auto-Solve: Off");
        autoSolveToggle.setOnAction(e -> toggleAutoSolve());

        resolveConflictsButton = new Button("Fix Conflicts");
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
        meterRegistry.counter("sudokupro.ui.pencil.toggles", GLOBAL_TAGS).increment();
    }

    public void requestHint() {
        String pid = authService.getCurrentPlayerId();
        try {
            String hint = gameService.getHint(board.getGameId());
            notificationService.sendTypedNotification(pid, "hint", hint);
            refresh();
            updateMoveHistory(new EnhancedMove(-1, -1, 0, SudokuCell.MoveSource.HINT));
            meterRegistry.counter("sudokupro.ui.hints", GLOBAL_TAGS).increment();
        } catch (Exception e) {
            logger.error("Hint failed: {}", e.getMessage());
            notificationService.sendNotification(pid, "Hint failed: " + e.getMessage());
        }
    }

    private void undoMove() {
        String pid = authService.getCurrentPlayerId();
        try {
            board.undo();
            refresh();
            if (!moveHistoryList.getItems().isEmpty())
                moveHistoryList.getItems().remove(moveHistoryList.getItems().size() - 1);
            updateDifficultyProgress();
            notificationService.sendTypedNotification(pid, "ui", "Move undone");
            meterRegistry.counter("sudokupro.ui.undo", GLOBAL_TAGS).increment();
        } catch (Exception e) {
            logger.error("Undo failed: {}", e.getMessage());
        }
    }

    private void redoMove() {
        String pid = authService.getCurrentPlayerId();
        try {
            EnhancedMove redone = board.redo();
            if (redone != null) {
                refresh();
                updateMoveHistory(redone);
                updateDifficultyProgress();
                notificationService.sendTypedNotification(pid, "ui", "Move redone");
                meterRegistry.counter("sudokupro.ui.redo", GLOBAL_TAGS).increment();
            }
        } catch (Exception e) {
            logger.error("Redo failed: {}", e.getMessage());
        }
    }

    private void toggleAutoSolve() {
        String pid = authService.getCurrentPlayerId();
        boolean solving = autoSolveToggle.isSelected();
        autoSolveToggle.setText("Auto-Solve: " + (solving ? "On" : "Off"));
        if (solving) new Thread(() -> {
            while (autoSolveToggle.isSelected() && !board.isSolved()) {
                try {
                    gameService.solveSudoku(board.getGameId());
                    Platform.runLater(this::refresh);
                    Thread.sleep(500);
                } catch (Exception e) {
                    logger.error("Auto-solve error: {}", e.getMessage());
                    break;
                }
            }
            if (board.isSolved()) Platform.runLater(() ->
                notificationService.sendTypedNotification(pid, "game", "Puzzle auto-solved!"));
        }).start();
    }

    private void resolveConflicts() {
        String pid = authService.getCurrentPlayerId();
        boolean resolved = false;
        for (int r = 0; r < GRID_SIZE; r++) for (int c = 0; c < GRID_SIZE; c++) {
            SudokuCell cell = board.getBoard()[r][c];
            if (cell.isConflicted() && !cell.isGiven()) {
                EnhancedMove clear = new EnhancedMove(r, c, cell.getValue(), 0, SudokuCell.MoveSource.PLAYER);
                board.applyMove(clear, multiplayerBroadcaster);
                updateMoveHistory(clear);
                resolved = true;
            }
        }
        if (resolved) { refresh(); notificationService.sendTypedNotification(pid, "ui", "Conflicts resolved"); }
    }

    // =====================================================================
    // Rendering helpers
    // =====================================================================

    public void refresh() {
        validateCells(board.getBoard());
        for (int r = 0; r < GRID_SIZE; r++) for (int c = 0; c < GRID_SIZE; c++) refreshCell(r, c);
        updateStatus();
        updateDifficultyProgress();
    }

    private void refreshCell(int row, int col) {
        TextField cell = cellFields[row][col];
        SudokuCell sc = board.getBoard()[row][col];
        String text = sc.getValue() == 0
            ? (sc.getPencilMarks().isEmpty() ? "" : formatPencilMarks(sc.getPencilMarks()))
            : String.valueOf(sc.getValue());
        if (!cell.getText().equals(text)) Platform.runLater(() -> cell.setText(text));
        updateCellStyle(cell, row, col);
    }

    private void updateCellStyle(TextField cell, int row, int col) {
        SudokuCell sc = board.getBoard()[row][col];
        StringBuilder style = new StringBuilder(difficultyStyle(board.getDifficulty()) + "-fx-alignment: center;");
        if (sc.isConflicted())                            style.append("-fx-background-color: #FF5555;");
        else if (sc.isGiven())                            style.append("-fx-background-color: #4B4B4B;");
        else if (!sc.getPencilMarks().isEmpty())          style.append("-fx-background-color: #2F2F5F;");
        else if (row == highlightedRow || col == highlightedCol) style.append("-fx-background-color: #3A3A6A;");
        else                                              style.append("-fx-background-color: #1E1E1E;");
        if (row == highlightedRow && col == highlightedCol)
            style.append("-fx-border-color: #FF00FF; -fx-border-width: 2;");
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
        Platform.runLater(() -> { moveHistoryList.getItems().add(text); filterMoveHistory(); });
    }

    private void filterMoveHistory() {
        String filter = historyFilter.getValue();
        List<String> all = new ArrayList<>(moveHistoryList.getItems());
        List<String> filtered = all.stream().filter(item -> switch (filter) {
            case "Player Moves" -> !item.equals("Hint Applied");
            case "Hints"        -> item.equals("Hint Applied");
            default             -> true;
        }).collect(Collectors.toList());
        Platform.runLater(() -> { moveHistoryList.getItems().setAll(filtered); });
    }

    private void updateDifficultyProgress() {
        int filled = 0;
        SudokuCell[][] cells = board.getBoard();
        for (int r = 0; r < GRID_SIZE; r++) for (int c = 0; c < GRID_SIZE; c++)
            if (cells[r][c].getValue() != 0) filled++;
        double p = (double) filled / (GRID_SIZE * GRID_SIZE);
        Platform.runLater(() -> difficultyProgress.setProgress(p));
        if (p == 1.0) animateSolvedCells();
    }

    private void animateSolvedCells() {
        for (int r = 0; r < GRID_SIZE; r++) for (int c = 0; c < GRID_SIZE; c++) {
            FadeTransition ft = new FadeTransition(Duration.millis(500), cellFields[r][c]);
            ft.setFromValue(1.0); ft.setToValue(0.5); ft.setAutoReverse(true); ft.setCycleCount(2);
            ft.play();
        }
    }

    private void updateStatus() {
        String s = board.isSolved() ? "Solved!" : "In Progress";
        Platform.runLater(() -> statusLabel.setText("Game: " + board.getGameId() + " | " + s));
    }

    private void subscribeToMultiplayerUpdates() {
        multiplayerBroadcaster.subscribeToGame(board.getGameId(), () -> Platform.runLater(this::refresh));
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

    public SudokuBoard getBoard() { return board; }
}
