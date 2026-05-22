package com.xai.sudokupro.ui;

import com.xai.sudokupro.SudokuProApplication;
import javafx.application.Platform;
import javafx.stage.Modality;
import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.service.*;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import io.micrometer.core.instrument.MeterRegistry;
import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.media.AudioClip;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class MainStage extends Application {
    private static final Logger logger = LoggerFactory.getLogger(MainStage.class);
    private static final String CYBER_CSS_PATH = "/css/cyber-grid.css";
    private static final String SOUND_PATH = "/audio/startup-chime.mp3";
    private static final String BACKGROUND_MUSIC_PATH = "/audio/cosmic-ambience.mp3";
    private static final String VICTORY_SOUND_PATH = "/audio/victory-fanfare.mp3";
    private static ConfigurableApplicationContext springContext;

    @Autowired private GameService gameService;
    @Autowired private NotificationService notificationService;
    @Autowired private MultiplayerBroadcaster multiplayerBroadcaster;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private AuthService authService;
    @Autowired private LeaderboardService leaderboardService;
    @Autowired private ThemeManager themeManager;
    @Autowired private EventEngine eventEngine;

    private BoardView boardView;
    private ComboBox<String> difficultySelector;
    private Label timerLabel;
    private Label statsLabel;
    private Button hintButton;
    private Button resetButton;
    private CheckBox chaosModeCheck;
    private CheckBox mirrorModeCheck;
    private Button leaderboardButton;
    private ToggleButton soundToggle;
    private Button saveButton;
    private Button loadButton;
    private Button themeButton;
    private ToggleButton pauseButton;
    private Button replayButton;
    private Button settingsButton;
    private ListView<String> chatList;
    private TextField chatInput;
    private ListView<String> eventNotifications;
    private AudioClip backgroundMusic;
    private AudioClip victorySound;
    private volatile boolean isPaused = false;
    private int timerInterval = 1000; // Default 1s

    @Override
    public void init() {
        if (springContext == null) {
            springContext = SpringApplication.run(SudokuProApplication.class);
            springContext.getAutowireCapableBeanFactory().autowireBean(this);
        }
    }

    @Override
    public void start(Stage primaryStage) {
        try {
            // Load assets
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

            // Welcome Screen
            VBox welcomeScreen = createWelcomeScreen(primaryStage);
            Scene welcomeScene = new Scene(welcomeScreen, 1000, 750);
            if (cssURL != null) welcomeScene.getStylesheets().add(cssURL.toExternalForm());

            // Main Game Scene
            BorderPane gameRoot = createGameScene(primaryStage);
            Scene gameScene = new Scene(gameRoot, 1000, 750);
            if (cssURL != null) gameScene.getStylesheets().add(cssURL.toExternalForm());
            themeManager.applyUserPreferredTheme(gameScene, authService.getCurrentPlayerId());

            // Initial Scene
            primaryStage.setScene(welcomeScene);
            primaryStage.setTitle("SudokuPro: The Divine Puzzle Empire");
            primaryStage.setResizable(false);
            primaryStage.show();

            // Cosmic Chime
            if (startupSound != null) startupSound.play();

            // Animate Welcome
            FadeTransition fadeIn = new FadeTransition(Duration.seconds(2), welcomeScreen);
            fadeIn.setFromValue(0);
            fadeIn.setToValue(1);
            fadeIn.play();

            logger.info("SudokuPro UI launched with welcome screen");
        } catch (Exception e) {
            logger.error("Failed to launch SudokuPro UI: {}", e.getMessage(), e);
            showErrorDialog("Launch Error", "SudokuPro failed to start: " + e.getMessage());
        }
    }

    private VBox createWelcomeScreen(Stage primaryStage) {
        Text welcomeTitle = new Text("Welcome to SudokuPro");
        welcomeTitle.setFont(Font.font("Orbitron", 36));
        welcomeTitle.getStyleClass().add("title-fade");

        Button startButton = new Button("Start Puzzle");
        startButton.setStyle("-fx-background-color: #4B0082; -fx-text-fill: #FFFFFF;");
        startButton.setOnAction(e -> {
            primaryStage.setScene(createGameScene(primaryStage).getScene());
            resetBoard(primaryStage);
        });

        VBox welcome = new VBox(20, welcomeTitle, startButton);
        welcome.setAlignment(Pos.CENTER);
        welcome.setStyle("-fx-background-color: #000000;");
        return welcome;
    }

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
        hintButton = new Button("Hint");
        hintButton.setOnAction(e -> boardView.requestHint());
        resetButton = new Button("Reset");
        resetButton.setOnAction(e -> resetBoard(primaryStage));
        leaderboardButton = new Button("Leaderboard");
        leaderboardButton.setOnAction(e -> showLeaderboard());
        soundToggle = new ToggleButton("Sound: On");
        soundToggle.setSelected(true);
        soundToggle.setOnAction(e -> toggleSound());
        saveButton = new Button("Save");
        saveButton.setOnAction(e -> saveGame());
        loadButton = new Button("Load");
        loadButton.setOnAction(e -> loadGame(primaryStage));
        themeButton = new Button("Themes");
        themeButton.setOnAction(e -> themeManager.showThemeCustomizer(primaryStage.getScene()));
        pauseButton = new ToggleButton("Pause");
        pauseButton.setOnAction(e -> togglePause());
        replayButton = new Button("Replay");
        replayButton.setOnAction(e -> replayGame(primaryStage));
        settingsButton = new Button("Settings");
        settingsButton.setOnAction(e -> showSettings(primaryStage));

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

        // Initial Board
        SudokuBoard board = gameService.createNewGame(getDifficultyLevel(difficultySelector.getValue()), 
            authService.getCurrentPlayerId(), false, false);
        boardView = new BoardView(board, gameService, notificationService, multiplayerBroadcaster, meterRegistry, authService);

        // Layout
        VBox topSection = new VBox(10, title, controls);
        topSection.setAlignment(Pos.CENTER);
        BorderPane root = new BorderPane();
        root.setTop(topSection);
        root.setCenter(boardView.getView());
        root.setRight(rightPanel);
        root.setStyle("-fx-background-color: #000000;");

        updateStats();
        subscribeToChat();
        subscribeToEvents();
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

    private void resetBoard(Stage primaryStage) {
        try {
            SudokuBoard newBoard = gameService.createNewGame(getDifficultyLevel(difficultySelector.getValue()), 
                authService.getCurrentPlayerId(), chaosModeCheck.isSelected(), mirrorModeCheck.isSelected());
            boardView = new BoardView(newBoard, gameService, notificationService, multiplayerBroadcaster, meterRegistry, authService);
            BorderPane root = (BorderPane) primaryStage.getScene().getRoot();
            root.setCenter(boardView.getView());
            startTimer(newBoard, timerLabel);
            updateStats();
            logger.info("Board reset to difficulty: {}, Chaos: {}, Mirror: {}", 
                difficultySelector.getValue(), chaosModeCheck.isSelected(), mirrorModeCheck.isSelected());
        } catch (Exception e) {
            logger.error("Failed to reset board: {}", e.getMessage(), e);
            notificationService.sendNotification(authService.getCurrentPlayerId(), "Reset failed: " + e.getMessage());
        }
    }

    private void startTimer(SudokuBoard board, Label timerLabel) {
        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            while (!board.isSolved()) {
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
            if (board.isSolved()) {
                Platform.runLater(() -> {
                    timerLabel.setText("Solved in " + timerLabel.getText().substring(6));
                    notificationService.sendTypedNotification(authService.getCurrentPlayerId(), 
                        "game", "Puzzle solved in " + timerLabel.getText().substring(6));
                    playVictoryAnimation(boardView.getView());
                    if (victorySound != null) victorySound.play();
                });
            }
        }).start();
    }

    private void togglePause() {
        isPaused = !isPaused;
        pauseButton.setText(isPaused ? "Resume" : "Pause");
        pauseButton.setStyle(isPaused ? 
            "-fx-background-color: #FF4500; -fx-text-fill: #FFFFFF;" : 
            "-fx-background-color: #4B0082; -fx-text-fill: #FFFFFF;");
        boardView.setPaused(isPaused);
        notificationService.sendTypedNotification(authService.getCurrentPlayerId(), 
            "ui", "Game " + (isPaused ? "paused" : "resumed"));
        logger.debug("Game {} by player {}", isPaused ? "paused" : "resumed", authService.getCurrentPlayerId());
    }

    private void showLeaderboard() {
        try {
            List<LeaderboardService.LeaderboardSnapshot> topPlayers = leaderboardService.getPublicLeaderboard(5);
            StringBuilder leaderboardText = new StringBuilder("Top 5 Players:\n");
            for (LeaderboardService.LeaderboardSnapshot player : topPlayers) {
                leaderboardText.append(String.format("%d. %s - %d points (%s)\n", 
                    player.rank(), player.username(), player.sortValue(), player.tier()));
            }
            Alert leaderboardDialog = new Alert(Alert.AlertType.INFORMATION);
            leaderboardDialog.setTitle("Cosmic Leaderboard");
            leaderboardDialog.setHeaderText("Galactic Rankings");
            leaderboardDialog.setContentText(leaderboardText.toString());
            leaderboardDialog.showAndWait();
            logger.info("Leaderboard displayed for player {}", authService.getCurrentPlayerId());
        } catch (Exception e) {
            logger.error("Failed to show leaderboard: {}", e.getMessage(), e);
            notificationService.sendNotification(authService.getCurrentPlayerId(), "Leaderboard fetch failed: " + e.getMessage());
        }
    }

    private void toggleSound() {
        if (soundToggle.isSelected()) {
            if (backgroundMusic != null) backgroundMusic.play();
            soundToggle.setText("Sound: On");
            logger.debug("Sound enabled by player {}", authService.getCurrentPlayerId());
        } else {
            if (backgroundMusic != null) backgroundMusic.stop();
            soundToggle.setText("Sound: Off");
            logger.debug("Sound disabled by player {}", authService.getCurrentPlayerId());
        }
    }

    private void saveGame() {
        try {
            String playerId = authService.getCurrentPlayerId();
            gameService.endGame(boardView.getBoard().getGameId(), playerId);
            notificationService.sendTypedNotification(playerId, "game", "Game saved successfully");
            logger.info("Game saved for player {}", playerId);
        } catch (Exception e) {
            logger.error("Failed to save game: {}", e.getMessage(), e);
            notificationService.sendNotification(authService.getCurrentPlayerId(), "Save failed: " + e.getMessage());
        }
    }

    private void loadGame(Stage primaryStage) {
        try {
            String playerId = authService.getCurrentPlayerId();
            SudokuBoard loadedBoard = gameService.getGame(boardView.getBoard().getGameId());
            boardView = new BoardView(loadedBoard, gameService, notificationService, multiplayerBroadcaster, meterRegistry, authService);
            BorderPane root = (BorderPane) primaryStage.getScene().getRoot();
            root.setCenter(boardView.getView());
            startTimer(loadedBoard, timerLabel);
            updateStats();
            notificationService.sendTypedNotification(playerId, "game", "Game loaded successfully");
            logger.info("Game loaded for player {}", playerId);
        } catch (Exception e) {
            logger.error("Failed to load game: {}", e.getMessage(), e);
            notificationService.sendNotification(authService.getCurrentPlayerId(), "Load failed: " + e.getMessage());
        }
    }

    private void replayGame(Stage primaryStage) {
        try {
            String playerId = authService.getCurrentPlayerId();
            List<? extends Move> moveHistory = boardView.getBoard().getMoveHistory();
            resetBoard(primaryStage); // Reset to fresh board
            new Thread(() -> {
                for (Move move : moveHistory) {
                    try {
                        if (!isPaused) {
                            gameService.applyMove(boardView.getBoard().getGameId(), move, playerId);
                            Platform.runLater(() -> {
                                boardView.refresh();
                                updateStats();
                            });
                            Thread.sleep(1000); // Replay at 1s intervals
                        }
                    } catch (Exception e) {
                        logger.error("Replay failed for move {}: {}", move, e.getMessage(), e);
                        break;
                    }
                }
                notificationService.sendTypedNotification(playerId, "ui", "Replay completed");
            }).start();
            logger.info("Replay started for player {}", playerId);
        } catch (Exception e) {
            logger.error("Failed to start replay: {}", e.getMessage(), e);
            notificationService.sendNotification(authService.getCurrentPlayerId(), "Replay failed: " + e.getMessage());
        }
    }

    private void showSettings(Stage primaryStage) {
        Stage settingsStage = new Stage();
        settingsStage.initModality(Modality.APPLICATION_MODAL);
        settingsStage.setTitle("Cosmic Settings");

        VBox settingsPane = new VBox(10);
        settingsPane.setPadding(new Insets(10));
        settingsPane.setStyle("-fx-background-color: #1E1E1E;");

        // Timer Interval
        Label timerLabel = new Label("Timer Interval (ms):");
        timerLabel.setStyle("-fx-text-fill: #FFFFFF;");
        Spinner<Integer> timerSpinner = new Spinner<>(500, 5000, timerInterval, 500);
        timerSpinner.setEditable(true);
        timerSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            timerInterval = newVal;
            notificationService.sendTypedNotification(authService.getCurrentPlayerId(), 
                "ui", "Timer interval set to " + newVal + "ms");
        });

        Button applyButton = new Button("Apply");
        applyButton.setStyle("-fx-background-color: #4B0082; -fx-text-fill: #FFFFFF;");
        applyButton.setOnAction(e -> settingsStage.close());

        settingsPane.getChildren().addAll(timerLabel, timerSpinner, applyButton);
        Scene settingsScene = new Scene(settingsPane, 300, 200);
        settingsStage.setScene(settingsScene);
        settingsStage.show();
        logger.debug("Settings opened by player {}", authService.getCurrentPlayerId());
    }

    private void updateStats() {
        int moves = boardView.getBoard().getMoveHistory().size();
        int hints = boardView.getBoard().getHintCount();
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
        notificationService.sendTypedNotification(authService.getCurrentPlayerId(), "ui", feedback);
    }

    private void showModePreview(String mode, boolean enabled) {
        String message = enabled ? mode + " activated: Prepare for a cosmic twist!" : mode + " deactivated.";
        notificationService.sendTypedNotification(authService.getCurrentPlayerId(), "ui", message);
        logger.debug("{} preview shown for player {}", mode, authService.getCurrentPlayerId());
    }

    private void sendChatMessage(String message) {
        if (message.trim().isEmpty()) return;
        String playerId = authService.getCurrentPlayerId();
        String chatMessage = String.format("[%s] %s: %s", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), playerId, message);
        multiplayerBroadcaster.broadcastEvent("chat", chatMessage, null);
        Platform.runLater(() -> chatList.getItems().add(chatMessage));
        chatInput.clear();
        logger.debug("Chat message sent by player {}: {}", playerId, message);
    }

    private void subscribeToChat() {
        multiplayerBroadcaster.subscribeToEvent("chat", message -> 
            Platform.runLater(() -> chatList.getItems().add(message)));
        logger.debug("Subscribed to chat updates");
    }

    private void subscribeToEvents() {
        multiplayerBroadcaster.subscribeToEvent("event", message -> 
            Platform.runLater(() -> eventNotifications.getItems().add(message)));
        eventEngine.getActiveEvents().forEach((id, details) -> 
            eventNotifications.getItems().add("Event " + id + " ends at " + details.getEndTime()));
        logger.debug("Subscribed to event updates");
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
        logger.debug("Victory animation played");
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
        springContext.close();
        logger.info("MainStage shut down—cosmic UI closed");
    }

    public static void main(String[] args) {
        launch(args);
    }

    public BoardView getBoardView() {
        return boardView;
    }
}
