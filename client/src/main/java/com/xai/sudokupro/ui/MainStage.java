package com.xai.sudokupro.ui;

import com.xai.sudokupro.client.ChatLine;
import com.xai.sudokupro.client.GameClient;
import com.xai.sudokupro.client.MoveLabels;
import com.xai.sudokupro.client.PlayClock;
import com.xai.sudokupro.client.ReplaySession;
import com.xai.sudokupro.client.net.ApiException;
import com.xai.sudokupro.client.net.ConnectionState;
import com.xai.sudokupro.client.net.ServerApi;
import com.xai.sudokupro.client.net.ServerConfig;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.api.BoardState;
import com.xai.sudokupro.model.api.EventInfo;
import com.xai.sudokupro.model.api.LeaderboardEntry;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private Label connectionLabel;
    private Button reconnectButton;
    private Timeline replayTimeline;
    private int timerInterval = 1000; // Default 1s
    // Bug 5 fix: generation counter — each startTimer() call increments this so older
    // threads see a changed generation and exit, preventing thread accumulation on reset.
    private volatile int timerGeneration = 0;

    /**
     * The one source of elapsed play time. It replaces a bare {@code startTime}
     * that the pause flag never touched, so "paused" only stopped the label from
     * being repainted and the whole break was billed to the player on resume.
     */
    private final PlayClock clock = new PlayClock();

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

        Button registerButton = new Button("Register New Account");
        registerButton.setStyle("-fx-background-color: #2E2E5E; -fx-text-fill: #FFFFFF;");
        registerButton.setOnAction(e -> {
            registerButton.setDisable(true);
            statusLabel.setStyle("-fx-text-fill: #00FFFF;");
            statusLabel.setText("Registering " + userField.getText().trim() + "…");
            String server = serverField.getText().trim();
            String user = userField.getText().trim();
            String pass = passField.getText();
            Thread registrar = new Thread(() -> {
                try {
                    ServerApi.register(server, user, pass);
                    Platform.runLater(() -> {
                        statusLabel.setStyle("-fx-text-fill: #55FF55;");
                        statusLabel.setText("Account created — now hit Connect!");
                        registerButton.setDisable(false);
                    });
                } catch (ApiException ex) {
                    logger.warn("Registration failed: {}", ex.getMessage());
                    Platform.runLater(() -> {
                        statusLabel.setStyle("-fx-text-fill: #FF5555;");
                        statusLabel.setText(ex.getMessage());
                        registerButton.setDisable(false);
                    });
                }
            }, "sudokupro-register");
            registrar.setDaemon(true);
            registrar.start();
        });

        VBox welcome = new VBox(20, welcomeTitle, serverField, userField, passField, startButton, registerButton, statusLabel);
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
        client.setOnChat((from, text) ->
            Platform.runLater(() -> addChatLine(ChatLine.renderNow(from, text))));
        client.setOnEvent(msg -> Platform.runLater(() -> eventNotifications.getItems().add(msg)));
        client.setOnConnectionState(state -> Platform.runLater(() -> showConnectionState(state)));

        primaryStage.setScene(gameScene);
        resetBoard(primaryStage);
        loadActiveEvents();

        // Smart difficulty: preselect the adaptive model's recommendation.
        runOffFx("sudokupro-smart-difficulty", () -> {
            int recommended = client.recommendedDifficulty();
            String label = switch (recommended) {
                case 1 -> "Easy"; case 3 -> "Hard"; case 4 -> "Insane"; default -> "Medium";
            };
            Platform.runLater(() -> {
                if (!label.equals(difficultySelector.getValue())) {
                    notify("ui", "Based on your recent games, " + label + " is recommended");
                }
            });
        });
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
        Button dailyButton = new Button("Daily");
        dailyButton.setOnAction(e -> playDaily(primaryStage));
        Button duelButton = new Button("Duel");
        duelButton.setOnAction(e -> showDuelDialog(primaryStage));
        Button weeklyButton = new Button("Weekly");
        weeklyButton.setOnAction(e -> showTournamentDialog(primaryStage));
        Button friendsButton = new Button("Friends");
        friendsButton.setOnAction(e -> showFriendsDialog(primaryStage));
        Button shopButton = new Button("Shop");
        shopButton.setOnAction(e -> showShopDialog());
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
        loadButton.setOnAction(e -> loadGame(primaryStage));
        Button themeButton = new Button("Themes");
        themeButton.setOnAction(e -> themeManager.showThemeCustomizer(primaryStage.getScene()));
        pauseButton = new ToggleButton("Pause");
        pauseButton.setOnAction(e -> togglePause());
        // A visible connection state, and a way to act on it. Neither existed: a
        // dead channel was invisible until the next move failed, and there was no
        // control anywhere in the client that could open a socket again.
        connectionLabel = new Label("Live");
        connectionLabel.setStyle("-fx-text-fill: #55FF55; -fx-font-size: 14;");
        reconnectButton = new Button("Reconnect");
        reconnectButton.setDisable(true);
        reconnectButton.setOnAction(e -> reconnectNow());
        Button replayButton = new Button("Replay");
        replayButton.setOnAction(e -> replayGame(primaryStage));
        Button settingsButton = new Button("Settings");
        settingsButton.setOnAction(e -> showSettings());

        HBox controls = new HBox(10, difficultySelector, chaosModeCheck, mirrorModeCheck, timerLabel, statsLabel,
            connectionLabel, reconnectButton,
            dailyButton, duelButton, weeklyButton, friendsButton, shopButton, hintButton, resetButton, leaderboardButton, soundToggle, saveButton, loadButton, themeButton, pauseButton,
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
                // Without this the window keeps the "Creating puzzle…" placeholder
                // for good: the only sign of failure was a line in a side panel.
                Platform.runLater(() -> {
                    if (boardView == null) {
                        BorderPane root = (BorderPane) primaryStage.getScene().getRoot();
                        Label failed = new Label("Could not create a puzzle: " + e.getMessage()
                            + "\nCheck the server and press Reset to try again.");
                        failed.setStyle("-fx-text-fill: #FF5555; -fx-font-size: 14;");
                        failed.setWrapText(true);
                        root.setCenter(failed);
                    }
                });
            }
        }, "sudokupro-newgame");
        creator.setDaemon(true);
        creator.start();
    }

    private void startTimer(Label timerLabel) {
        // Bug 5 fix: increment generation so any already-running timer thread sees a changed
        // generation and exits. Mark new thread as daemon so it doesn't block JVM shutdown.
        final int myGeneration = ++timerGeneration;
        clock.start();
        if (pauseButton != null && pauseButton.isSelected()) {
            pauseButton.setSelected(false);
            pauseButton.setText("Pause");
        }
        Thread t = new Thread(() -> {
            // Read the board through the client each tick: undo/redo and server
            // resyncs REPLACE the local board instance mid-game.
            while (timerGeneration == myGeneration && client.board() != null
                    && !client.board().isSolved()) {
                // The clock itself knows about pausing, so the label simply shows
                // what it says. Skipping the repaint while paused (the old
                // behaviour) left the underlying count running and made the display
                // leap forward by the length of the break the moment play resumed.
                String time = "Time: " + clock.elapsedText();
                Platform.runLater(() -> timerLabel.setText(time));
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
                // Compute the finished time ONCE. Reading it back off the label after
                // having just overwritten the label is what produced the shipped
                // "Puzzle solved in in 04:12": the second substring(6) skipped
                // "Solved" instead of "Time: ".
                final String finalTime = clock.elapsedText();
                Platform.runLater(() -> {
                    timerLabel.setText("Solved in " + finalTime);
                    notify("game", "Puzzle solved in " + finalTime);
                    if (boardView != null) playVictoryAnimation(boardView.getView());
                    if (victorySound != null) victorySound.play();
                });
            }
        }, "sudokupro-timer");
        t.setDaemon(true);
        t.start();
    }

    private void togglePause() {
        boolean paused = !clock.isPaused();
        if (paused) clock.pause(); else clock.resume();
        pauseButton.setSelected(paused);
        pauseButton.setText(paused ? "Resume" : "Pause");
        pauseButton.setStyle(paused ?
            "-fx-background-color: #FF4500; -fx-text-fill: #FFFFFF;" :
            "-fx-background-color: #4B0082; -fx-text-fill: #FFFFFF;");
        if (boardView != null) boardView.setPaused(paused);
        notify("ui", "Game " + (paused ? "paused" : "resumed"));
        logger.debug("Game {} by player {}", paused ? "paused" : "resumed", client.playerId());
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
                notify("game", "Game saved — resume it any time from Load");
                logger.info("Game saved for player {}", client.playerId());
            } catch (Exception e) {
                logger.error("Failed to save game: {}", e.getMessage(), e);
                notify("error", "Save failed: " + e.getMessage());
            }
        }, "sudokupro-save");
        saver.setDaemon(true);
        saver.start();
    }

    /** Fetches the saved-games list off the FX thread, then shows the picker on it. */
    private void loadGame(Stage primaryStage) {
        Thread loader = new Thread(() -> {
            try {
                List<BoardState> saved = client.savedGames(10);
                if (saved.isEmpty()) {
                    notify("game", "No saved games to resume — use Save first");
                    return;
                }
                Platform.runLater(() -> showResumePicker(primaryStage, saved));
            } catch (Exception e) {
                logger.error("Failed to list saved games: {}", e.getMessage(), e);
                notify("error", "Load failed: " + e.getMessage());
            }
        }, "sudokupro-load");
        loader.setDaemon(true);
        loader.start();
    }

    /** FX thread: pick one of the saved games, then resume it off the FX thread. */
    private void showResumePicker(Stage primaryStage, List<BoardState> saved) {
        Map<String, String> gameIdByLabel = new LinkedHashMap<>();
        for (BoardState s : saved) gameIdByLabel.put(describeSavedGame(s), s.gameId());

        ChoiceDialog<String> dialog = new ChoiceDialog<>(
            gameIdByLabel.keySet().iterator().next(), gameIdByLabel.keySet());
        dialog.setTitle("Resume Game");
        dialog.setHeaderText("Saved games (newest first)");
        dialog.setContentText("Resume:");
        dialog.showAndWait().ifPresent(label -> resumeGame(primaryStage, gameIdByLabel.get(label)));
    }

    private String describeSavedGame(BoardState s) {
        String difficulty = switch (s.difficulty()) {
            case 1 -> "Easy"; case 2 -> "Medium"; case 3 -> "Hard"; case 4 -> "Insane";
            default -> "Level " + s.difficulty();
        };
        StringBuilder sb = new StringBuilder(difficulty);
        if (s.chaosMode())  sb.append(" · Chaos");
        if (s.mirrorMode()) sb.append(" · Mirror");
        sb.append(" · ").append(s.moveCount()).append(" moves")
          .append(" · score ").append(s.score())
          .append(" · #").append(s.gameId(), 0, Math.min(8, s.gameId().length()));
        return sb.toString();
    }

    /** Weekly tournament hub: join one of the five puzzles or view standings. */
    private void showTournamentDialog(Stage primaryStage) {
        runOffFx("sudokupro-weekly", () -> {
            var status = client.tournamentStatus();
            Platform.runLater(() -> {
                Map<String, Runnable> actions = new LinkedHashMap<>();
                for (var p : status.get("puzzles")) {
                    int n = p.get("puzzle").asInt();
                    boolean done = p.get("completed").asBoolean();
                    String label = "Puzzle " + n + (done ? " ✓ (" + p.get("seconds").asLong() + "s)" : " — play");
                    if (!done) actions.put(label, () -> joinTournamentPuzzle(primaryStage, n));
                    else actions.put(label, () -> notify("game", "Puzzle " + n + " already done"));
                }
                actions.put("Standings…", () -> runOffFx("sudokupro-standings", () -> {
                    StringBuilder sb = new StringBuilder();
                    for (var row : client.tournamentStandings(10)) {
                        sb.append(row.get("rank").asInt()).append(". ")
                          .append(row.get("playerId").asText()).append(" — ")
                          .append(row.get("totalSeconds").asLong()).append("s\n");
                    }
                    String text = sb.length() == 0 ? "No full finishers yet — five solves to get ranked!" : sb.toString();
                    Platform.runLater(() -> {
                        Alert a = new Alert(Alert.AlertType.INFORMATION);
                        a.setTitle("Weekly Standings");
                        a.setHeaderText(status.get("weekId").asText() + " — full finishers by total time");
                        a.setContentText(text);
                        a.showAndWait();
                    });
                }));
                ChoiceDialog<String> dialog = new ChoiceDialog<>(actions.keySet().iterator().next(), actions.keySet());
                dialog.setTitle("Weekly Tournament");
                dialog.setHeaderText(status.get("weekId").asText() + " — " + status.get("completed").asInt()
                    + "/5 done, total " + status.get("totalSeconds").asLong() + "s");
                dialog.setContentText("Choose:");
                dialog.showAndWait().ifPresent(k -> actions.get(k).run());
            });
        });
    }

    private void joinTournamentPuzzle(Stage primaryStage, int puzzle) {
        runOffFx("sudokupro-weekly-join", () -> {
            client.joinTournament(puzzle);
            Platform.runLater(() -> {
                swapInBoard(primaryStage);
                notify("game", "Tournament puzzle " + puzzle + " — the clock counts!");
            });
        });
    }

    /** Friends hub: accept requests, watch online friends, add new ones. */
    private void showFriendsDialog(Stage primaryStage) {
        runOffFx("sudokupro-friends", () -> {
            var friends = client.friends();
            Platform.runLater(() -> {
                Map<String, Runnable> actions = new LinkedHashMap<>();
                for (var f : friends) {
                    String name = f.get("playerId").asText();
                    boolean online = f.get("online").asBoolean();
                    if (online) {
                        actions.put("● " + name + " (online) — watch", () -> spectateFriend(primaryStage, name));
                    } else {
                        actions.put("○ " + name + " (offline)", () -> notify("ui", name + " is offline"));
                    }
                }
                actions.put("Add a friend…", this::addFriend);
                actions.put("Pending requests…", this::showPendingRequests);
                ChoiceDialog<String> dialog = new ChoiceDialog<>(actions.keySet().iterator().next(), actions.keySet());
                dialog.setTitle("Friends");
                dialog.setHeaderText("Your friends");
                dialog.setContentText("Choose:");
                dialog.showAndWait().ifPresent(k -> actions.get(k).run());
            });
        });
    }

    private void addFriend() {
        TextInputDialog prompt = new TextInputDialog();
        prompt.setTitle("Add Friend");
        prompt.setHeaderText("Send a friend request");
        prompt.setContentText("Player name:");
        prompt.showAndWait().ifPresent(name -> runOffFx("sudokupro-friend-req", () -> {
            client.requestFriend(name.trim());
            notify("game", "Friend request sent to " + name.trim());
        }));
    }

    private void showPendingRequests() {
        runOffFx("sudokupro-friend-pending", () -> {
            var pending = client.pendingFriends();
            Platform.runLater(() -> {
                if (!pending.iterator().hasNext()) { notify("ui", "No pending friend requests"); return; }
                Map<String, Runnable> actions = new LinkedHashMap<>();
                for (var p : pending) {
                    String name = p.asText();
                    actions.put("Accept " + name, () -> runOffFx("sudokupro-friend-accept", () -> {
                        client.acceptFriend(name);
                        notify("game", "You and " + name + " are now friends");
                    }));
                }
                ChoiceDialog<String> dialog = new ChoiceDialog<>(actions.keySet().iterator().next(), actions.keySet());
                dialog.setTitle("Friend Requests");
                dialog.setHeaderText("Pending requests");
                dialog.setContentText("Choose:");
                dialog.showAndWait().ifPresent(k -> actions.get(k).run());
            });
        });
    }

    private void spectateFriend(Stage primaryStage, String name) {
        runOffFx("sudokupro-spectate", () -> {
            String gameId = client.activeGameOf(name);
            client.spectate(gameId);
            Platform.runLater(() -> {
                swapInBoard(primaryStage);
                notify("game", "Watching " + name + " — read-only, enjoy the show");
            });
        });
    }

    /** Power-up shop: buy with gems, use on the current game (or freeze a rival). */
    private void showShopDialog() {
        runOffFx("sudokupro-shop", () -> {
            var shop = client.powerUpShop();
            var wallet = client.wallet();
            Platform.runLater(() -> {
                Map<String, Runnable> actions = new LinkedHashMap<>();
                shop.get("catalog").fields().forEachRemaining(e -> {
                    String type = e.getKey();
                    int price = e.getValue().asInt();
                    int held = shop.get("inventory").path(type).asInt(0);
                    actions.put("Buy " + type + " — " + price + " gems (held: " + held + ")",
                        () -> runOffFx("sudokupro-shop-buy", () -> {
                            client.buyPowerUp(type);
                            notify("game", type + " purchased");
                        }));
                    if (held > 0) {
                        actions.put("Use " + type, () -> usePowerUp(type));
                    }
                });
                if (actions.isEmpty()) {
                    // iterator().next() on an empty catalog threw NoSuchElementException
                    // straight onto the FX thread, with no dialog and no message.
                    notify("ui", "The shop has nothing in stock right now");
                    return;
                }
                ChoiceDialog<String> dialog = new ChoiceDialog<>(actions.keySet().iterator().next(), actions.keySet());
                dialog.setTitle("Power-Up Shop");
                dialog.setHeaderText("You have " + wallet.path("gems").asInt() + " gems");
                dialog.setContentText("Choose:");
                dialog.showAndWait().ifPresent(k -> actions.get(k).run());
            });
        });
    }

    private void usePowerUp(String type) {
        if ("FREEZE".equals(type)) {
            TextInputDialog prompt = new TextInputDialog();
            prompt.setTitle("Freeze");
            prompt.setHeaderText("Freeze whom for 10 seconds?");
            prompt.setContentText("Player name:");
            prompt.showAndWait().ifPresent(target -> runOffFx("sudokupro-shop-use", () -> {
                client.usePowerUp(type, null, target.trim());
                notify("game", target.trim() + " is frozen — go go go!");
            }));
            return;
        }
        SudokuBoard current = client.board();
        if (current == null) { notify("error", "Start a game first"); return; }
        runOffFx("sudokupro-shop-use", () -> {
            client.usePowerUp(type, current.getGameId(), null);
            Platform.runLater(() -> { if (boardView != null) boardView.refresh(); updateStats(); });
            notify("game", type + " used");
        });
    }

    /** Replaces the center board view after the client swapped games. */
    private void swapInBoard(Stage primaryStage) {
        boardView = new BoardView(client, this::notify);
        BorderPane root = (BorderPane) primaryStage.getScene().getRoot();
        root.setCenter(boardView.getView());
        startTimer(timerLabel);
        updateStats();
    }

    /** Background worker with uniform error reporting — UI actions must not block the FX thread. */
    private void runOffFx(String name, Runnable work) {
        Thread t = new Thread(() -> {
            try {
                work.run();
            } catch (Exception e) {
                logger.error("{} failed: {}", name, e.getMessage(), e);
                notify("error", e.getMessage());
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    /** Duel hub: accept a pending challenge, rejoin an active race, or challenge someone new. */
    private void showDuelDialog(Stage primaryStage) {
        Thread fetcher = new Thread(() -> {
            try {
                var duels = client.myDuels();
                Platform.runLater(() -> {
                    Map<String, Runnable> actions = new LinkedHashMap<>();
                    String me = client.playerId();
                    for (var d : duels) {
                        if ("PENDING".equals(d.status()) && me.equals(d.opponent())) {
                            actions.put("Accept challenge from " + d.challenger() + " (#" + d.duelId() + ")",
                                () -> acceptDuel(primaryStage, d.duelId()));
                        } else if ("ACTIVE".equals(d.status()) && d.gameId() != null) {
                            String rival = me.equals(d.challenger()) ? d.opponent() : d.challenger();
                            actions.put("Rejoin race vs " + rival + " (#" + d.duelId() + ")",
                                () -> resumeGame(primaryStage, d.gameId()));
                        }
                    }
                    actions.put("Challenge a player…", () -> challengeDuel());
                    ChoiceDialog<String> dialog = new ChoiceDialog<>(
                        actions.keySet().iterator().next(), actions.keySet());
                    dialog.setTitle("Duels");
                    dialog.setHeaderText("First to solve wins");
                    dialog.setContentText("Action:");
                    dialog.showAndWait().ifPresent(choice -> actions.get(choice).run());
                });
            } catch (Exception e) {
                logger.error("Failed to load duels: {}", e.getMessage(), e);
                notify("error", "Duels unavailable: " + e.getMessage());
            }
        }, "sudokupro-duels");
        fetcher.setDaemon(true);
        fetcher.start();
    }

    private void challengeDuel() {
        TextInputDialog prompt = new TextInputDialog();
        prompt.setTitle("Challenge");
        prompt.setHeaderText("Who do you want to duel?");
        prompt.setContentText("Player name:");
        prompt.showAndWait().ifPresent(opponent -> {
            Thread sender = new Thread(() -> {
                try {
                    String duelId = client.challengeDuel(opponent.trim(),
                        getDifficultyLevel(difficultySelector.getValue()));
                    notify("game", "Challenge sent to " + opponent + " (#" + duelId + ") — waiting for them to accept");
                } catch (Exception e) {
                    logger.error("Challenge failed: {}", e.getMessage(), e);
                    notify("error", "Challenge failed: " + e.getMessage());
                }
            }, "sudokupro-challenge");
            sender.setDaemon(true);
            sender.start();
        });
    }

    private void acceptDuel(Stage primaryStage, String duelId) {
        Thread acceptor = new Thread(() -> {
            try {
                client.acceptDuel(duelId);
                Platform.runLater(() -> {
                    boardView = new BoardView(client, this::notify);
                    BorderPane root = (BorderPane) primaryStage.getScene().getRoot();
                    root.setCenter(boardView.getView());
                    startTimer(timerLabel);
                    updateStats();
                    notify("game", "Duel on! First correct solve wins.");
                });
            } catch (Exception e) {
                logger.error("Failed to accept duel {}: {}", duelId, e.getMessage(), e);
                notify("error", "Accept failed: " + e.getMessage());
            }
        }, "sudokupro-duel-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** Joins today's shared daily puzzle (network call — off the FX thread), then rebuilds the board view. */
    private void playDaily(Stage primaryStage) {
        Thread joiner = new Thread(() -> {
            try {
                var statusBefore = client.dailyStatus();
                if (statusBefore.completed()) {
                    notify("game", "Daily already solved — " + statusBefore.streakDays()
                        + "-day streak. New puzzle at midnight UTC!");
                    return;
                }
                client.joinDaily();
                Platform.runLater(() -> {
                    boardView = new BoardView(client, this::notify);
                    BorderPane root = (BorderPane) primaryStage.getScene().getRoot();
                    root.setCenter(boardView.getView());
                    startTimer(timerLabel);
                    updateStats();
                    notify("game", "Daily puzzle " + statusBefore.date() + " — streak "
                        + statusBefore.streakDays() + ". Good luck!");
                });
            } catch (Exception e) {
                logger.error("Failed to join daily puzzle: {}", e.getMessage(), e);
                notify("error", "Daily puzzle failed: " + e.getMessage());
            }
        }, "sudokupro-daily");
        joiner.setDaemon(true);
        joiner.start();
    }

    /** Resumes the chosen game (network call — runs off the FX thread), then rebuilds the board view. */
    private void resumeGame(Stage primaryStage, String gameId) {
        Thread resumer = new Thread(() -> {
            try {
                SudokuBoard resumed = client.resumeGame(gameId);
                Platform.runLater(() -> {
                    boardView = new BoardView(client, this::notify);
                    BorderPane root = (BorderPane) primaryStage.getScene().getRoot();
                    root.setCenter(boardView.getView());
                    startTimer(timerLabel);
                    updateStats();
                    notify("game", "Game resumed — welcome back");
                    logger.info("Resumed game {} for player {}", resumed.getGameId(), client.playerId());
                });
            } catch (Exception e) {
                logger.error("Failed to resume game {}: {}", gameId, e.getMessage(), e);
                notify("error", "Resume failed: " + e.getMessage());
            }
        }, "sudokupro-resume");
        resumer.setDaemon(true);
        resumer.start();
    }

    /**
     * Walks the current game's moves back through the UI, one per second.
     *
     * <p>Replay used to call the asynchronous {@code resetBoard()} and then
     * immediately start pushing the old game's moves at the server. It raced its
     * own reset, it aimed those moves at a freshly generated and completely
     * different puzzle, and it took them from {@code getMoveHistory()}, a
     * {@code push}ed deque that iterates newest-first — so even had it not raced,
     * it would have replayed the wrong game backwards. It could not work, and the
     * audit's "never works" was exact.
     *
     * <p>It is now a review of what you did: chronological, driven by one FX
     * {@link Timeline} rather than a thread racing an async reset, mutating
     * nothing on the server, and stoppable by pressing Replay again.
     */
    private void replayGame(Stage primaryStage) {
        if (replayTimeline != null) {
            replayTimeline.stop();
            replayTimeline = null;
            notify("ui", "Replay stopped");
            return;
        }
        ReplaySession session = ReplaySession.of(client.board());
        if (session.isEmpty()) {
            notify("ui", "Nothing to replay yet — play a few moves first");
            return;
        }
        replayTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            if (!session.hasNext()) {
                if (replayTimeline != null) replayTimeline.stop();
                replayTimeline = null;
                notify("ui", "Replay complete — " + session.size() + " moves");
                return;
            }
            var move = session.next();
            if (boardView != null) boardView.highlightCell(move.row(), move.col());
            notify("ui", session.progressText() + " — " + MoveLabels.describe(move));
        }));
        replayTimeline.setCycleCount(session.size() + 1);
        replayTimeline.play();
        logger.info("Replay started for player {} ({} moves)", client.playerId(), session.size());
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

    /** Paints the connection indicator. Must run on the FX thread. */
    private void showConnectionState(ConnectionState state) {
        if (connectionLabel == null) return;
        switch (state) {
            case CONNECTED -> {
                connectionLabel.setText("Live");
                connectionLabel.setStyle("-fx-text-fill: #55FF55; -fx-font-size: 14;");
                reconnectButton.setDisable(true);
            }
            case RECONNECTING -> {
                connectionLabel.setText("Reconnecting…");
                connectionLabel.setStyle("-fx-text-fill: #FFD700; -fx-font-size: 14;");
                reconnectButton.setDisable(false);
            }
            case FAILED -> {
                connectionLabel.setText("Offline");
                connectionLabel.setStyle("-fx-text-fill: #FF5555; -fx-font-size: 14;");
                reconnectButton.setDisable(false);
            }
            case DISCONNECTED -> {
                connectionLabel.setText("Idle");
                connectionLabel.setStyle("-fx-text-fill: #AAAAAA; -fx-font-size: 14;");
                reconnectButton.setDisable(client.board() == null);
            }
        }
    }

    /** The player's manual way back onto the gameplay channel — a blocking handshake, so off the FX thread. */
    private void reconnectNow() {
        reconnectButton.setDisable(true);
        runOffFx("sudokupro-reconnect", () -> {
            try {
                client.reconnect();
                notify("game", "Reconnected — you are back in the game");
            } catch (Exception e) {
                notify("error", "Reconnect failed: " + e.getMessage());
                Platform.runLater(() -> reconnectButton.setDisable(false));
            }
        });
    }

    private void updateStats() {
        SudokuBoard board = client.board();
        if (board == null) return;
        // getMoveCount(), not getMoveHistory().size(): the history deque is
        // transient and empty on any board rebuilt from a server snapshot
        // (resume, undo/redo resyncs); the counter carries the real total.
        int moves = board.getMoveCount();
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
        String text = message.trim();
        if (text.isEmpty()) return;
        try {
            // Send the message, not a rendered line: the server puts the sender in
            // the envelope's `from` and every receiver labels it again, so shipping
            // "[12:04:31] ann: hi" over the wire showed peers "ann: [12:04:31] ann: hi".
            client.sendChat(text);
            Platform.runLater(() -> addChatLine(ChatLine.renderNow(client.playerId(), text)));
            chatInput.clear();
        } catch (Exception e) {
            notify("error", "Chat failed: " + e.getMessage());
        }
    }

    /** Appends one chat line, keeping the transcript bounded like the event panel. */
    private void addChatLine(String line) {
        chatList.getItems().add(line);
        if (chatList.getItems().size() > 500) chatList.getItems().remove(0);
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
        timerGeneration++;                       // retires the running timer thread
        if (replayTimeline != null) replayTimeline.stop();
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
