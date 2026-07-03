package com.xai.sudokupro.ui;

import com.xai.sudokupro.client.GameClient;
import com.xai.sudokupro.client.net.ApiException;
import com.xai.sudokupro.client.net.ServerApi;
import com.xai.sudokupro.client.net.ServerConfig;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.api.EventInfo;
import com.xai.sudokupro.model.api.LeaderboardEntry;
import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.media.AudioClip;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Deque;
import java.util.List;

/**
 * JavaFX main window. Pure network client (AUDIT follow-up: client/server
 * separation): every game interaction goes through {@link GameClient} — no
 * Spring context, no server beans, no shared JVM.
 */
public class MainStage extends Application {
    private static final Logger logger = LoggerFactory.getLogger(MainStage.class);
    private static final String CYBER_CSS_PATH = "/css/cyber-grid.css";
    private static final String SOUND_PATH = "/audio/startup-chime.mp3";
    private static final String BACKGROUND_MUSIC_PATH = "/audio/cosmic-ambience.mp3";
    private static final String VICTORY_SOUND_PATH = "/audio/victory-fanfare.mp3";

    private GameClient client;
    private ThemeManager themeManager;

    private BoardView boardView;
    private ComboBox<String> difficultySelector;
    private Label timerLabel;
    private Label statsLabel;
    private CheckBox chaosModeCheck;
    private CheckBox mirrorModeCheck;
    private ToggleButton soundToggle;
    private ToggleButton pauseButton;
    private ListView<String> chatList;
    private TextField chatInput;
    private ListView<String> eventNotifications;
    private AudioClip backgroundMusic;
    private AudioClip victorySound;
    private volatile boolean isPaused = false;
    private int timerInterval = 1000; // Default 1s
    // Bug 5 fix: generation counter — each startTimer() call increments this so older
    // threads see a changed generation and exit, preventing thread accumulation on reset.
    private volatile int timerGeneration = 0;

    @Override
    public void start(Stage primaryStage) {
        try {
            URL cssURL = getClass().getResource(CYBER_CSS_PATH);
            URL soundURL = getClass().getResource(SOUND_PATH);
            URL musicURL = getClass().getResource(BACKGROUND_MUSIC_PATH);
            URL victoryURL = getClass().getResource(VICTORY_SOUND_PATH);
            AudioClip startupSound = soundURL != null ? new AudioClip(soundURL.toExternalForm()) : null;
            backgroundMusic = musicURL != null ? new AudioClip(musicURL.toExternalForm()) : null;
            victorySound = victoryURL != null ? new AudioClip(victoryURL.toExternalForm()) : null;
            if (backgroundMusic != null) {
                backgroundMusic.setCycleCount(AudioClip.INDEFINITE);
                backgroundMusic.play();
            }

            VBox welcomeScreen = createConnectScreen(primaryStage, cssURL);
            Scene welcomeScene = new Scene(welcomeScreen, 1000, 750);
            if (cssURL != null) welcomeScene.getStylesheets().add(cssURL.toExternalForm());

            primaryStage.setScene(welcomeScene);
            primaryStage.setTitle("SudokuPro: The Divine Puzzle Empire");
            primaryStage.setResizable(false);
            primaryStage.show();

            if (startupSound != null) startupSound.play();

            FadeTransition fadeIn = new FadeTransition(Duration.seconds(2), welcomeScreen);
            fadeIn.setFromValue(0);
            fadeIn.setToValue(1);
            fadeIn.play();

            logger.info("SudokuPro UI launched with connect screen");
        } catch (Exception e) {
            logger.error("Failed to launch SudokuPro UI: {}", e.getMessage(), e);
            showErrorDialog("Launch Error", "SudokuPro failed to start: " + e.getMessage());
        }
    }

    // =====================================================================
    // Connect / welcome screen
    // =====================================================================

    private VBox createConnectScreen(Stage primaryStage, URL cssURL) {
        Text welcomeTitle = new Text("Welcome to SudokuPro");
        welcomeTitle.setFont(Font.font("Orbitron", 36));
        welcomeTitle.getStyleClass().add("title-fade");

        ServerConfig defaults = ServerConfig.fromEnvironment();
        TextField serverField = new TextField(defaults.baseUrl());
        serverField.setPromptText("Server URL");
        serverField.setMaxWidth(320);
        TextField userField = new TextField(defaults.username());
        userField.setPromptText("Username");
        userField.setMaxWidth(320);
        PasswordField passField = new PasswordField();
        passField.setText(defaults.password());
        passField.setPromptText("Password");
        passField.setMaxWidth(320);

        Label statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #FF5555;");

        Button startButton = new Button("Connect & Start Puzzle");
        startButton.setStyle("-fx-background-color: #4B0082; -fx-text-fill: #FFFFFF;");
        startButton.setOnAction(e -> {
            startButton.setDisable(true);
            statusLabel.setStyle("-fx-text-fill: #00FFFF;");
            statusLabel.setText("Connecting to " + serverField.getText() + "…");
            ServerConfig config = new ServerConfig(
                serverField.getText().trim(), userField.getText().trim(), passField.getText());
            Thread connector = new Thread(() -> {
                try {
                    ServerApi api = new ServerApi(config);
                    api.connect();
                    GameClient newClient = new GameClient(api);
                    Platform.runLater(() -> {
                        this.client = newClient;
                        enterGame(primaryStage, cssURL);
                    });
                } catch (ApiException ex) {
                    logger.warn("Connect failed: {}", ex.getMessage());
                    Platform.runLater(() -> {
                        statusLabel.setStyle("-fx-text-fill: #FF5555;");
                        statusLabel.setText(ex.getMessage());
                        startButton.setDisable(false);
                    });
                }
            }, "sudokupro-connect");
            connector.setDaemon(true);
            connector.start();
        });

        VBox welcome = new VBox(20, welcomeTitle, serverField, userField, passField, startButton, statusLabel);
        welcome.setAlignment(Pos.CENTER);
        welcome.setStyle("-fx-background-color: #000000;");
        return welcome;
    }

    /** Called on the FX thread once the server connection is up. */
    private void enterGame(Stage primaryStage, URL cssURL) {
        themeManager = new ThemeManager(this::notify);

        BorderPane gameRoot = createGameScene(primaryStage);
        Scene gameScene = new Scene(gameRoot, 1000, 750);
        if (cssURL != null) gameScene.getStylesheets().add(cssURL.toExternalForm());
        themeManager.applyUserPreferredTheme(gameScene);

        // Server pushes flow through these single-slot callbacks; the current
        // BoardView is looked up at dispatch time because resets replace it.
        client.setNotifier(this::notify);
        client.setOnBoardChanged(() -> {
            BoardView bv = boardView;
            if (bv != null) {
                bv.refresh();
                updateStats();
            }
        });
        client.setOnChat(msg -> Platform.runLater(() -> chatList.getItems().add(msg)));
        client.setOnEvent(msg -> Platform.runLater(() -> eventNotifications.getItems().add(msg)));

        primaryStage.setScene(gameScene);
        resetBoard(primaryStage);
        loadActiveEvents();
    }

    // =====================================================================
    // Game scene
    // =====================================================================

    private BorderPane createGameScene(Stage primaryStage) {
        // Difficulty Selector
        difficultySelector = new ComboBox<>();
        difficultySelector.getItems().addAll("Easy", "Medium", "Hard", "Insane");
        difficultySelector.setValue("Medium");
        difficultySelector.setOnAction(e -> {
            resetBoard(primaryStage);
            updateDifficultyFeedback();
        });

        // Timer & Stats
        timerLabel = new Label("Time: 00:00");
        timerLabel.setStyle("-fx-text-fill: #FFD700; -fx-font-size: 14;");
        statsLabel = new Label("Moves: 0 | Hints: 0");
        statsLabel.setStyle("-fx-text-fill: #00FFFF; -fx-font-size: 14;");

        // Game Mode Toggles with Previews
        chaosModeCheck = new CheckBox("Chaos Mode");
        chaosModeCheck.setStyle("-fx-text-fill: #FFFFFF;");
        chaosModeCheck.setOnAction(e -> {
            resetBoard(primaryStage);
            showModePreview("Chaos Mode", chaosModeCheck.isSelected());
        });
        mirrorModeCheck = new CheckBox("Mirror Mode");
        mirrorModeCheck.setStyle("-fx-text-fill: #FFFFFF;");
        mirrorModeCheck.setOnAction(e -> {
            resetBoard(primaryStage);
            showModePreview("Mirror Mode", mirrorModeCheck.isSelected());
        });

        // Controls
        Button hintButton = new Button("Hint");
        hintButton.setOnAction(e -> { if (boardView != null) boardView.requestHint(); });
        Button resetButton = new Button("Reset");
        resetButton.setOnAction(e -> resetBoard(primaryStage));
        Button leaderboardButton = new Button("Leaderboard");
        leaderboardButton.setOnAction(e -> showLeaderboard());
        soundToggle = new ToggleButton("Sound: On");
        soundToggle.setSelected(true);
        soundToggle.setOnAction(e -> toggleSound());
        Button saveButton = new Button("Save");
        saveButton.setOnAction(e -> saveGame());
        Button loadButton = new Button("Load");
        loadButton.setOnAction(e -> loadGame());
        Button themeButton = new Button("Themes");
        themeButton.setOnAction(e -> themeManager.showThemeCustomizer(primaryStage.getScene()));
        pauseButton = new ToggleButton("Pause");
        pauseButton.setOnAction(e -> togglePause());
        Button replayButton = new Button("Replay");
        replayButton.setOnAction(e -> replayGame(primaryStage));
        Button settingsButton = new Button("Settings");
        settingsButton.setOnAction(e -> showSettings());

        HBox controls = new HBox(10, difficultySelector, chaosModeCheck, mirrorModeCheck, timerLabel, statsLabel,
            hintButton, resetButton, leaderboardButton, soundToggle, saveButton, loadButton, themeButton, pauseButton,
            replayButton, settingsButton);
        controls.setAlignment(Pos.CENTER);
        controls.setPadding(new Insets(10));

        // Chat Panel
        chatList = new ListView<>();
        chatList.setPrefWidth(200);
        chatList.setStyle("-fx-background-color: #1E1E1E; -fx-text-fill: #FFFFFF;");
        chatInput = new TextField();
        chatInput.setPromptText("Type a message...");
        chatInput.setOnAction(e -> sendChatMessage(chatInput.getText()));

        // Event Notifications
        eventNotifications = new ListView<>();
        eventNotifications.setPrefWidth(200);
        eventNotifications.setStyle("-fx-background-color: #1E1E1E; -fx-text-fill: #FFFFFF;");

        VBox rightPanel = new VBox(10,
            new Label("Cosmic Chat"), chatList, chatInput,
            new Label("Event Notifications"), eventNotifications);
        rightPanel.setPadding(new Insets(10));
        rightPanel.setStyle("-fx-background-color: #000000;");

        // Title
        Text title = new Text("SudokuPro: The Divine Puzzle Empire");
        title.setFont(Font.font("Orbitron", 28));
        title.getStyleClass().add("title-fade");

        // Layout (the board arrives asynchronously via resetBoard)
        VBox topSection = new VBox(10, title, controls);
        topSection.setAlignment(Pos.CENTER);
        BorderPane root = new BorderPane();
        root.setTop(topSection);
        root.setCenter(new Label("Creating puzzle…"));
        root.setRight(rightPanel);
        root.setStyle("-fx-background-color: #000000;");
        return root;
    }

    private int getDifficultyLevel(String label) {
        return switch (label.toLowerCase()) {
            case "easy" -> 1;
            case "medium" -> 2;
            case "hard" -> 3;
            case "insane" -> 4;
            default -> 2;
        };
    }

    /** Creates a new game on the server (network call — runs off the FX thread). */
    private void resetBoard(Stage primaryStage) {
        int difficulty = getDifficultyLevel(difficultySelector.getValue());
        boolean chaos = chaosModeCheck.isSelected();
        boolean mirror = mirrorModeCheck.isSelected();
        Thread creator = new Thread(() -> {
            try {
                SudokuBoard newBoard = client.newGame(difficulty, chaos, mirror);
                Platform.runLater(() -> {
                    boardView = new BoardView(client, this::notify);
                    BorderPane root = (BorderPane) primaryStage.getScene().getRoot();
                    root.setCenter(boardView.getView());
                    startTimer(timerLabel);
                    updateStats();
                    logger.info("Board reset to difficulty: {}, Chaos: {}, Mirror: {} (game {})",
                        difficulty, chaos, mirror, newBoard.getGameId());
                });
            } catch (Exception e) {
                logger.error("Failed to reset board: {}", e.getMessage(), e);
                notify("error", "Reset failed: " + e.getMessage());
            }
        }, "sudokupro-newgame");
        creator.setDaemon(true);
        creator.start();
    }

    private void startTimer(Label timerLabel) {
        // Bug 5 fix: increment generation so any already-running timer thread sees a changed
        // generation and exits. Mark new thread as daemon so it doesn't block JVM shutdown.
        final int myGeneration = ++timerGeneration;
        Thread t = new Thread(() -> {
            long startTime = System.currentTimeMillis();
            // Read the board through the client each tick: undo/redo and server
            // resyncs REPLACE the local board instance mid-game.
            while (timerGeneration == myGeneration && client.board() != null
                    && !client.board().isSolved()) {
                if (!isPaused) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    long minutes = elapsed / 60000;
                    long seconds = (elapsed % 60000) / 1000;
                    String time = String.format("Time: %02d:%02d", minutes, seconds);
                    Platform.runLater(() -> timerLabel.setText(time));
                }
                try {
                    Thread.sleep(timerInterval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("Timer interrupted: {}", e.getMessage(), e);
                    break;
                }
            }
            SudokuBoard board = client.board();
            if (board != null && board.isSolved() && timerGeneration == myGeneration) {
                Platform.runLater(() -> {
                    timerLabel.setText("Solved in " + timerLabel.getText().substring(6));
                    notify("game", "Puzzle solved in " + timerLabel.getText().substring(6));
                    if (boardView != null) playVictoryAnimation(boardView.getView());
                    if (victorySound != null) victorySound.play();
                });
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void togglePause() {
        isPaused = !isPaused;
        pauseButton.setText(isPaused ? "Resume" : "Pause");
        pauseButton.setStyle(isPaused ?
            "-fx-background-color: #FF4500; -fx-text-fill: #FFFFFF;" :
            "-fx-background-color: #4B0082; -fx-text-fill: #FFFFFF;");
        if (boardView != null) boardView.setPaused(isPaused);
        notify("ui", "Game " + (isPaused ? "paused" : "resumed"));
        logger.debug("Game {} by player {}", isPaused ? "paused" : "resumed", client.playerId());
    }

    private void showLeaderboard() {
        Thread fetcher = new Thread(() -> {
            try {
                List<LeaderboardEntry> topPlayers = client.leaderboard(5);
                StringBuilder text = new StringBuilder("Top 5 Players:\n");
                for (LeaderboardEntry player : topPlayers) {
                    text.append(String.format("%d. %s - %d points (%s)\n",
                        player.rank(), player.username(), player.sortValue(), player.tier()));
                }
                Platform.runLater(() -> {
                    Alert dialog = new Alert(Alert.AlertType.INFORMATION);
                    dialog.setTitle("Cosmic Leaderboard");
                    dialog.setHeaderText("Galactic Rankings");
                    dialog.setContentText(text.toString());
                    dialog.showAndWait();
                });
                logger.info("Leaderboard displayed for player {}", client.playerId());
            } catch (Exception e) {
                logger.error("Failed to show leaderboard: {}", e.getMessage(), e);
                notify("error", "Leaderboard fetch failed: " + e.getMessage());
            }
        }, "sudokupro-leaderboard");
        fetcher.setDaemon(true);
        fetcher.start();
    }

    private void toggleSound() {
        if (soundToggle.isSelected()) {
            if (backgroundMusic != null) backgroundMusic.play();
            soundToggle.setText("Sound: On");
        } else {
            if (backgroundMusic != null) backgroundMusic.stop();
            soundToggle.setText("Sound: Off");
        }
    }

    private void saveGame() {
        Thread saver = new Thread(() -> {
            try {
                client.save();
                notify("game", "Game saved successfully");
                logger.info("Game saved for player {}", client.playerId());
            } catch (Exception e) {
                logger.error("Failed to save game: {}", e.getMessage(), e);
                notify("error", "Save failed: " + e.getMessage());
            }
        }, "sudokupro-save");
        saver.setDaemon(true);
        saver.start();
    }

    private void loadGame() {
        Thread loader = new Thread(() -> {
            try {
                client.refresh();
                Platform.runLater(() -> {
                    startTimer(timerLabel);
                    updateStats();
                });
                notify("game", "Game loaded successfully");
                logger.info("Game loaded for player {}", client.playerId());
            } catch (Exception e) {
                logger.error("Failed to load game: {}", e.getMessage(), e);
                notify("error", "Load failed: " + e.getMessage());
            }
        }, "sudokupro-load");
        loader.setDaemon(true);
        loader.start();
    }

    private void replayGame(Stage primaryStage) {
        try {
            SudokuBoard board = client.board();
            if (board == null) return;
            // Capture history before reset (getMoveHistory() returns a defensive Deque copy)
            Deque<SudokuBoard.Move> moveHistory = board.getMoveHistory();
            resetBoard(primaryStage);
            Thread replayThread = new Thread(() -> {
                for (SudokuBoard.Move move : moveHistory) {
                    try {
                        if (!isPaused) {
                            com.xai.sudokupro.model.EnhancedMove em = new com.xai.sudokupro.model.EnhancedMove(
                                move.row(), move.col(), move.oldVal(), move.newVal(), move.source());
                            client.applyMove(em);
                            Platform.runLater(() -> {
                                if (boardView != null) boardView.refresh();
                                updateStats();
                            });
                            Thread.sleep(1000);
                        }
                    } catch (Exception e) {
                        logger.error("Replay failed for move {}: {}", move, e.getMessage(), e);
                        break;
                    }
                }
                notify("ui", "Replay completed");
            });
            replayThread.setDaemon(true);
            replayThread.start();
            logger.info("Replay started for player {}", client.playerId());
        } catch (Exception e) {
            logger.error("Failed to start replay: {}", e.getMessage(), e);
            notify("error", "Replay failed: " + e.getMessage());
        }
    }

    private void showSettings() {
        Stage settingsStage = new Stage();
        settingsStage.initModality(Modality.APPLICATION_MODAL);
        settingsStage.setTitle("Cosmic Settings");

        VBox settingsPane = new VBox(10);
        settingsPane.setPadding(new Insets(10));
        settingsPane.setStyle("-fx-background-color: #1E1E1E;");

        // Timer Interval
        Label timerIntervalLabel = new Label("Timer Interval (ms):");
        timerIntervalLabel.setStyle("-fx-text-fill: #FFFFFF;");
        Spinner<Integer> timerSpinner = new Spinner<>(500, 5000, timerInterval, 500);
        timerSpinner.setEditable(true);
        timerSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            timerInterval = newVal;
            notify("ui", "Timer interval set to " + newVal + "ms");
        });

        Button applyButton = new Button("Apply");
        applyButton.setStyle("-fx-background-color: #4B0082; -fx-text-fill: #FFFFFF;");
        applyButton.setOnAction(e -> settingsStage.close());

        settingsPane.getChildren().addAll(timerIntervalLabel, timerSpinner, applyButton);
        Scene settingsScene = new Scene(settingsPane, 300, 200);
        settingsStage.setScene(settingsScene);
        settingsStage.show();
    }

    private void updateStats() {
        SudokuBoard board = client.board();
        if (board == null) return;
        int moves = board.getMoveHistory().size();
        int hints = board.getHintCount();
        String stats = String.format("Moves: %d | Hints: %d", moves, hints);
        Platform.runLater(() -> statsLabel.setText(stats));
    }

    private void updateDifficultyFeedback() {
        String feedback = switch (difficultySelector.getValue().toLowerCase()) {
            case "easy" -> "A gentle cosmic breeze.";
            case "medium" -> "A balanced galactic challenge.";
            case "hard" -> "A starry trial awaits.";
            case "insane" -> "Embrace the cosmic chaos!";
            default -> "Choose your fate.";
        };
        notify("ui", feedback);
    }

    private void showModePreview(String mode, boolean enabled) {
        notify("ui", enabled ? mode + " activated: Prepare for a cosmic twist!" : mode + " deactivated.");
    }

    private void sendChatMessage(String message) {
        if (message.trim().isEmpty()) return;
        String chatMessage = String.format("[%s] %s: %s",
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), client.playerId(), message);
        try {
            client.sendChat(chatMessage);
            Platform.runLater(() -> chatList.getItems().add(chatMessage));
            chatInput.clear();
        } catch (Exception e) {
            notify("error", "Chat failed: " + e.getMessage());
        }
    }

    private void loadActiveEvents() {
        Thread fetcher = new Thread(() -> {
            try {
                for (EventInfo event : client.activeEvents()) {
                    Platform.runLater(() -> eventNotifications.getItems().add(
                        "Event " + event.eventId() + " ends at " + event.endTime()));
                }
            } catch (Exception e) {
                logger.debug("Active events fetch failed: {}", e.getMessage());
            }
        }, "sudokupro-events");
        fetcher.setDaemon(true);
        fetcher.start();
    }

    /**
     * Local notification sink (replaces the server-side NotificationService):
     * messages land in the Event Notifications panel.
     */
    private void notify(String type, String message) {
        Platform.runLater(() -> {
            if (eventNotifications != null) {
                eventNotifications.getItems().add("[" + type + "] " + message);
                // Keep the panel bounded.
                if (eventNotifications.getItems().size() > 200) {
                    eventNotifications.getItems().remove(0);
                }
            }
        });
        logger.debug("[{}] {}", type, message);
    }

    private void playVictoryAnimation(Pane view) {
        ScaleTransition scale = new ScaleTransition(Duration.seconds(1), view);
        scale.setFromX(1.0);
        scale.setFromY(1.0);
        scale.setToX(1.1);
        scale.setToY(1.1);
        scale.setAutoReverse(true);
        scale.setCycleCount(2);
        scale.play();
    }

    private void showErrorDialog(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText("Startup Failure");
        alert.setContentText(message);
        alert.showAndWait();
    }

    @Override
    public void stop() {
        if (backgroundMusic != null) backgroundMusic.stop();
        if (victorySound != null) victorySound.stop();
        if (client != null) client.close();
        logger.info("MainStage shut down—cosmic UI closed");
    }

    public static void main(String[] args) {
        launch(args);
    }

    public BoardView getBoardView() {
        return boardView;
    }
}
