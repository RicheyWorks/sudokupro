package com.xai.sudokupro.ui;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.AuthService;
import com.xai.sudokupro.service.NotificationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ThemeManager {
    private static final Logger logger = LoggerFactory.getLogger(ThemeManager.class);
    private static final String[] THEMES = {
        "/css/cyber-grid.css",
        "/css/retro-pixel.css",
        "/css/astral-nebula.css",
        "/css/manga-mode.css"
    };
    private static final String[] THEME_NAMES = {
        "Cyber Grid",
        "Retro Pixel",
        "Astral Nebula",
        "Manga Mode"
    };
    private static final Map<String, String> PRESET_THEMES = new HashMap<>();
    private static final Tags GLOBAL_TAGS = Tags.of("app", "SudokuPro");
    private static final int DEFAULT_THEME_INDEX = 0;

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationService notificationService;
    @Autowired private MeterRegistry meterRegistry;

    // Fix: plain HashMap is not thread-safe — read by the cycling background thread while
    // written from JavaFX / Spring request threads. Use ConcurrentHashMap throughout.
    private final Map<String, String> customThemes = new ConcurrentHashMap<>();
    private final Map<String, String> sharedThemes = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private volatile boolean isCycling = false;

    static {
        PRESET_THEMES.put("Midnight Cosmos", "data:text/css," +
            ".root { -fx-background-color: #0A0A23; }" +
            ".button { -fx-background-color: #1E90FF; -fx-text-fill: #FFFFFF; }" +
            ".check-box .box { -fx-background-color: #0A0A23; -fx-border-color: #1E90FF; }" +
            ".combo-box { -fx-background-color: #0A0A23; -fx-text-fill: #FFFFFF; }" +
            ".title-fade { -fx-fill: #FFD700; }");
        PRESET_THEMES.put("Solar Flare", "data:text/css," +
            ".root { -fx-background-color: #FFD700; }" +
            ".button { -fx-background-color: #FF4500; -fx-text-fill: #FFFFFF; }" +
            ".check-box .box { -fx-background-color: #FFD700; -fx-border-color: #FF4500; }" +
            ".combo-box { -fx-background-color: #FFD700; -fx-text-fill: #000000; }" +
            ".title-fade { -fx-fill: #FF4500; }");
    }

    public void applyTheme(Scene scene, int themeIndex) {
        validateScene(scene);
        int normalizedIndex = themeIndex % THEMES.length;
        try {
            URL url = getClass().getResource(THEMES[normalizedIndex]);
            if (url == null) {
                logger.warn("Stylesheet not found on classpath: {}", THEMES[normalizedIndex]);
                applyDefaultTheme(scene);
                return;
            }
            scene.getStylesheets().clear();
            scene.getStylesheets().add(url.toExternalForm());
            meterRegistry.counter("sudokupro.ui.theme.switches",
                Tags.concat(GLOBAL_TAGS, Tags.of("theme", THEME_NAMES[normalizedIndex]))).increment();
            logger.debug("Applied theme '{}' (index {}) to scene", THEME_NAMES[normalizedIndex], normalizedIndex);
        } catch (Exception e) {
            logger.error("Failed to apply theme '{}': {}", THEME_NAMES[normalizedIndex], e.getMessage(), e);
            notificationService.sendNotification(authService.getCurrentPlayerId(),
                "Theme switch failed: " + e.getMessage());
            applyDefaultTheme(scene);
        }
    }

    public void applyUserPreferredTheme(Scene scene, String playerId) {
        validateScene(scene);
        validatePlayerId(playerId);
        try {
            User user = findUserByPlayerId(playerId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + playerId));
            String preferredTheme = user.getThemePreference().toLowerCase();
            if (customThemes.containsKey(playerId)) {
                scene.getStylesheets().clear();
                scene.getStylesheets().add(customThemes.get(playerId));
                logger.debug("Applied custom theme for player '{}'", playerId);
            } else if (sharedThemes.containsKey(preferredTheme)) {
                scene.getStylesheets().clear();
                scene.getStylesheets().add(sharedThemes.get(preferredTheme));
                logger.debug("Applied shared theme '{}' for player '{}'", preferredTheme, playerId);
            } else if (PRESET_THEMES.containsKey(preferredTheme)) {
                scene.getStylesheets().clear();
                scene.getStylesheets().add(PRESET_THEMES.get(preferredTheme));
                logger.debug("Applied preset theme '{}' for player '{}'", preferredTheme, playerId);
            } else {
                int themeIndex = getThemeIndexFromName(preferredTheme);
                applyTheme(scene, themeIndex != -1 ? themeIndex : DEFAULT_THEME_INDEX);
                logger.debug("Applied user '{}' preferred theme: '{}'", playerId, preferredTheme);
            }
        } catch (Exception e) {
            logger.error("Failed to apply preferred theme for player {}: {}", playerId, e.getMessage(), e);
            notificationService.sendNotification(playerId, "Failed to load preferred theme: " + e.getMessage());
            applyDefaultTheme(scene);
        }
    }

    public ComboBox<String> createThemeSelector(Scene scene) {
        ComboBox<String> themeSelector = new ComboBox<>();
        List<String> options = new ArrayList<>(Arrays.asList(THEME_NAMES));
        if (customThemes.containsKey(authService.getCurrentPlayerId())) {
            options.add("Custom Theme");
        }
        options.addAll(sharedThemes.keySet());
        options.addAll(PRESET_THEMES.keySet());
        themeSelector.getItems().addAll(options);
        themeSelector.setValue(getCurrentThemeName());
        themeSelector.setStyle("-fx-background-color: #1E1E1E; -fx-text-fill: #FFFFFF;");

        themeSelector.setOnAction(e -> {
            int selectedIndex = themeSelector.getSelectionModel().getSelectedIndex();
            String selectedTheme = themeSelector.getValue();
            if (selectedIndex >= THEMES.length) {
                if ("Custom Theme".equals(selectedTheme)) {
                    applyCustomTheme(scene, authService.getCurrentPlayerId());
                } else if (sharedThemes.containsKey(selectedTheme)) {
                    applySharedTheme(scene, selectedTheme);
                } else if (PRESET_THEMES.containsKey(selectedTheme)) {
                    applyPresetTheme(scene, selectedTheme);
                }
            } else {
                applyTheme(scene, selectedIndex);
                updateUserPreference(selectedIndex);
                notificationService.sendTypedNotification(authService.getCurrentPlayerId(),
                    "ui", "Theme switched to: " + THEME_NAMES[selectedIndex]);
            }
        });

        themeSelector.setOnShowing(e ->
            previewTheme(scene, themeSelector.getSelectionModel().getSelectedIndex(), themeSelector.getValue()));
        themeSelector.setOnHidden(e ->
            applyUserPreferredTheme(scene, authService.getCurrentPlayerId()));

        logger.debug("Theme selector created with {} options", options.size());
        return themeSelector;
    }

    private void previewTheme(Scene scene, int themeIndex, String themeName) {
        if (themeIndex >= THEMES.length) {
            if ("Custom Theme".equals(themeName)) {
                applyCustomTheme(scene, authService.getCurrentPlayerId());
            } else if (sharedThemes.containsKey(themeName)) {
                applySharedTheme(scene, themeName);
            } else if (PRESET_THEMES.containsKey(themeName)) {
                applyPresetTheme(scene, themeName);
            }
        } else {
            applyTheme(scene, themeIndex);
        }
    }

    private void applyCustomTheme(Scene scene, String playerId) {
        scene.getStylesheets().clear();
        scene.getStylesheets().add(customThemes.getOrDefault(playerId, THEMES[DEFAULT_THEME_INDEX]));
        logger.debug("Applied custom theme for player '{}'", playerId);
    }

    private void applySharedTheme(Scene scene, String themeName) {
        scene.getStylesheets().clear();
        scene.getStylesheets().add(sharedThemes.getOrDefault(themeName, THEMES[DEFAULT_THEME_INDEX]));
        logger.debug("Applied shared theme '{}'", themeName);
    }

    private void applyPresetTheme(Scene scene, String themeName) {
        scene.getStylesheets().clear();
        scene.getStylesheets().add(PRESET_THEMES.getOrDefault(themeName, THEMES[DEFAULT_THEME_INDEX]));
        logger.debug("Applied preset theme '{}'", themeName);
    }

    private void updateUserPreference(int themeIndex) {
        String playerId = authService.getCurrentPlayerId();
        try {
            User user = findUserByPlayerId(playerId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + playerId));
            String newTheme = THEME_NAMES[themeIndex].toLowerCase().replace(" ", "-");
            user.setThemePreference(newTheme);
            userRepository.save(user);
            logger.debug("Updated theme preference for player {} to '{}'", playerId, newTheme);
        } catch (Exception e) {
            logger.error("Failed to update theme preference for player {}: {}", playerId, e.getMessage(), e);
            notificationService.sendNotification(playerId, "Failed to save theme preference: " + e.getMessage());
        }
    }

    private void applyDefaultTheme(Scene scene) {
        URL url = getClass().getResource(THEMES[DEFAULT_THEME_INDEX]);
        scene.getStylesheets().clear();
        if (url != null) scene.getStylesheets().add(url.toExternalForm());
        logger.warn("Applied default theme '{}' due to error", THEME_NAMES[DEFAULT_THEME_INDEX]);
    }

    private int getThemeIndexFromName(String themeName) {
        for (int i = 0; i < THEME_NAMES.length; i++) {
            if (THEME_NAMES[i].toLowerCase().replace(" ", "-").equals(themeName)) {
                return i;
            }
        }
        return -1;
    }

    public void showThemeCustomizer(Scene currentScene) {
        Stage customizerStage = new Stage();
        customizerStage.initModality(Modality.APPLICATION_MODAL);
        customizerStage.setTitle("Cosmic Theme Customizer");

        BorderPane previewPane = new BorderPane();
        Label previewLabel = new Label("Preview");
        previewPane.setTop(previewLabel);
        previewPane.setStyle("-fx-background-color: #000000; -fx-pref-width: 300; -fx-pref-height: 300;");
        BorderPane.setAlignment(previewLabel, Pos.CENTER);

        VBox options = new VBox(10);
        options.setPadding(new Insets(10));

        ColorPicker bgColorPicker = new ColorPicker(Color.valueOf("#1E1E1E"));
        ColorPicker textColorPicker = new ColorPicker(Color.WHITE);
        ColorPicker accentColorPicker = new ColorPicker(Color.valueOf("#FFD700"));
        CheckBox glowEffect = new CheckBox("Enable Glow Effect");
        glowEffect.setStyle("-fx-text-fill: #FFFFFF;");
        Slider brightnessSlider = new Slider(0.5, 1.5, 1.0);
        brightnessSlider.setShowTickMarks(true);
        brightnessSlider.setShowTickLabels(true);
        brightnessSlider.setMajorTickUnit(0.25);
        CheckBox gradientAccent = new CheckBox("Gradient Accent");
        gradientAccent.setStyle("-fx-text-fill: #FFFFFF;");
        TextField themeNameField = new TextField();
        themeNameField.setPromptText("Theme Name (for sharing)");

        Button applyButton = new Button("Apply");
        applyButton.setOnAction(e -> {
            String customCSS = generateCustomCSS(
                bgColorPicker.getValue().toString(), textColorPicker.getValue().toString(),
                accentColorPicker.getValue().toString(), glowEffect.isSelected(),
                brightnessSlider.getValue(), gradientAccent.isSelected());
            saveCustomTheme(authService.getCurrentPlayerId(), customCSS);
            applyCustomTheme(currentScene, authService.getCurrentPlayerId());
            customizerStage.close();
        });

        Button randomButton = new Button("Randomize");
        randomButton.setOnAction(e -> randomizeTheme(
            bgColorPicker, textColorPicker, accentColorPicker,
            glowEffect, brightnessSlider, gradientAccent));

        Button shareButton = new Button("Share");
        shareButton.setOnAction(e -> shareTheme(themeNameField.getText(),
            generateCustomCSS(bgColorPicker.getValue().toString(),
                textColorPicker.getValue().toString(),
                accentColorPicker.getValue().toString(),
                glowEffect.isSelected(), brightnessSlider.getValue(), gradientAccent.isSelected())));

        Button cycleButton = new Button("Cycle Themes");
        cycleButton.setOnAction(e -> toggleThemeCycling(currentScene));

        Button exportButton = new Button("Export");
        exportButton.setOnAction(e -> exportTheme(currentScene));

        Button importButton = new Button("Import");
        importButton.setOnAction(e -> importTheme(currentScene));

        options.getChildren().addAll(
            new Label("Customize Your Theme:"),
            new Label("Background Color:"), bgColorPicker,
            new Label("Text Color:"), textColorPicker,
            new Label("Accent Color:"), accentColorPicker,
            glowEffect, new Label("Brightness:"), brightnessSlider,
            gradientAccent, themeNameField,
            applyButton, randomButton, shareButton, cycleButton, exportButton, importButton
        );

        AtomicInteger updateCounter = new AtomicInteger(0);
        ChangeListener<Object> previewListener = (obs, oldVal, newVal) -> {
            if (updateCounter.incrementAndGet() % 5 == 0) {
                String css = generateCustomCSS(
                    bgColorPicker.getValue().toString(), textColorPicker.getValue().toString(),
                    accentColorPicker.getValue().toString(), glowEffect.isSelected(),
                    brightnessSlider.getValue(), gradientAccent.isSelected());
                Platform.runLater(() -> {
                    previewPane.getStylesheets().clear();
                    previewPane.getStylesheets().add("data:text/css," + css);
                });
            }
        };
        bgColorPicker.valueProperty().addListener(previewListener);
        textColorPicker.valueProperty().addListener(previewListener);
        accentColorPicker.valueProperty().addListener(previewListener);
        glowEffect.selectedProperty().addListener(previewListener);
        brightnessSlider.valueProperty().addListener(previewListener);
        gradientAccent.selectedProperty().addListener(previewListener);

        HBox layout = new HBox(20, previewPane, options);
        layout.setAlignment(Pos.CENTER);
        Scene customizerScene = new Scene(layout, 600, 600);
        customizerStage.setScene(customizerScene);
        customizerStage.show();

        logger.info("Theme customizer opened for player {}", authService.getCurrentPlayerId());
    }

    private String generateCustomCSS(String bgColor, String textColor, String accentColor,
                                     boolean glowEffect, double brightness, boolean gradientAccent) {
        try {
            Color bg = Color.valueOf(bgColor);
            Color text = Color.valueOf(textColor);
            Color accent = Color.valueOf(accentColor);
            String adjustedBg = adjustBrightness(bg, brightness);
            String adjustedText = adjustBrightness(text, brightness);
            String adjustedAccent = adjustBrightness(accent, brightness);
            String glow = glowEffect
                ? "-fx-effect: dropshadow(gaussian, %s, 10, 0.5, 0, 0);".formatted(adjustedAccent)
                : "";
            String accentStyle;
            if (gradientAccent) {
                accentStyle = "-fx-background-color: linear-gradient(to right, %s, %s);".formatted(
                    adjustedAccent, adjustBrightness(accent, brightness * 1.2));
            } else {
                accentStyle = "-fx-background-color: %s;".formatted(adjustedAccent);
            }
            return String.format(
                ".root { -fx-background-color: %s; }" +
                ".button { %s -fx-text-fill: %s; %s }" +
                ".check-box .box { -fx-background-color: %s; -fx-border-color: %s; }" +
                ".combo-box { -fx-background-color: %s; -fx-text-fill: %s; }" +
                ".title-fade { -fx-fill: %s; %s }",
                adjustedBg, accentStyle, adjustedText, glow,
                adjustedBg, adjustedAccent,
                adjustedBg, adjustedText,
                adjustedAccent, glow
            );
        } catch (Exception e) {
            logger.warn("Invalid color input: {}", e.getMessage());
            return generateCustomCSS("#1E1E1E", "#FFFFFF", "#FFD700", false, 1.0, false);
        }
    }

    private String adjustBrightness(Color color, double factor) {
        double r = Math.min(255, Math.max(0, color.getRed() * 255 * factor));
        double g = Math.min(255, Math.max(0, color.getGreen() * 255 * factor));
        double b = Math.min(255, Math.max(0, color.getBlue() * 255 * factor));
        return String.format("#%02X%02X%02X", (int) r, (int) g, (int) b);
    }

    private void saveCustomTheme(String playerId, String customCSS) {
        customThemes.put(playerId, "data:text/css," + customCSS);
        notificationService.sendTypedNotification(playerId, "ui", "Custom theme saved!");
        logger.debug("Saved custom theme for player '{}'", playerId);
    }

    private String getCurrentThemeName() {
        String playerId = authService.getCurrentPlayerId();
        if (customThemes.containsKey(playerId)) return "Custom Theme";
        User user = findUserByPlayerId(playerId).orElse(null);
        if (user != null) {
            String pref = user.getThemePreference();
            if (sharedThemes.containsKey(pref)) return pref;
            if (PRESET_THEMES.containsKey(pref)) return pref;
            int index = getThemeIndexFromName(pref);
            return index != -1 ? THEME_NAMES[index] : THEME_NAMES[DEFAULT_THEME_INDEX];
        }
        return THEME_NAMES[DEFAULT_THEME_INDEX];
    }

    private void randomizeTheme(ColorPicker bgColorPicker, ColorPicker textColorPicker,
                                ColorPicker accentColorPicker, CheckBox glowEffect,
                                Slider brightnessSlider, CheckBox gradientAccent) {
        Color bgColor = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256));
        Color textColor = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256));
        Color accentColor = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256));
        boolean glow = random.nextBoolean();
        double brightness = 0.5 + random.nextDouble();
        boolean gradient = random.nextBoolean();
        Platform.runLater(() -> {
            bgColorPicker.setValue(bgColor);
            textColorPicker.setValue(textColor);
            accentColorPicker.setValue(accentColor);
            glowEffect.setSelected(glow);
            brightnessSlider.setValue(brightness);
            gradientAccent.setSelected(gradient);
        });
        meterRegistry.counter("sudokupro.ui.theme.randomizations", GLOBAL_TAGS).increment();
    }

    public void resetTheme(Scene scene) {
        String playerId = authService.getCurrentPlayerId();
        try {
            customThemes.remove(playerId);
            User user = findUserByPlayerId(playerId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + playerId));
            user.setThemePreference("default");
            userRepository.save(user);
            applyTheme(scene, DEFAULT_THEME_INDEX);
            notificationService.sendTypedNotification(playerId, "ui", "Theme reset to default");
            meterRegistry.counter("sudokupro.ui.theme.resets", GLOBAL_TAGS).increment();
        } catch (Exception e) {
            logger.error("Failed to reset theme for player {}: {}", playerId, e.getMessage(), e);
            notificationService.sendNotification(playerId, "Theme reset failed: " + e.getMessage());
        }
    }

    public void applyEventTheme(Scene scene, String eventType) {
        validateScene(scene);
        int themeIndex = switch (eventType.toLowerCase()) {
            case "win"   -> 2;
            case "loss"  -> 1;
            case "chaos" -> 3;
            default      -> DEFAULT_THEME_INDEX;
        };
        applyTheme(scene, themeIndex);
        notificationService.sendTypedNotification(authService.getCurrentPlayerId(),
            "ui", "Event theme applied: " + THEME_NAMES[themeIndex]);
    }

    private void shareTheme(String themeName, String css) {
        String playerId = authService.getCurrentPlayerId();
        if (themeName == null || themeName.trim().isEmpty()) {
            notificationService.sendNotification(playerId, "Please enter a theme name to share.");
            return;
        }
        if (sharedThemes.containsKey(themeName)) {
            notificationService.sendNotification(playerId, "Theme name '" + themeName + "' already exists.");
            return;
        }
        sharedThemes.put(themeName, "data:text/css," + css);
        notificationService.sendTypedNotification(playerId, "ui",
            "Theme '" + themeName + "' shared with the galaxy!");
        meterRegistry.counter("sudokupro.ui.theme.shares", GLOBAL_TAGS).increment();
    }

    private void toggleThemeCycling(Scene scene) {
        String playerId = authService.getCurrentPlayerId();
        isCycling = !isCycling;
        if (isCycling) {
            Thread cycler = new Thread(() -> {
                int index = 0;
                List<String> allThemes = new ArrayList<>(Arrays.asList(THEMES));
                allThemes.addAll(customThemes.values());
                allThemes.addAll(sharedThemes.values());
                allThemes.addAll(PRESET_THEMES.values());
                while (isCycling) {
                    final int currentIndex = index % allThemes.size();
                    Platform.runLater(() -> {
                        scene.getStylesheets().clear();
                        scene.getStylesheets().add(allThemes.get(currentIndex));
                    });
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    index++;
                }
            });
            // Fix: mark daemon so this thread cannot prevent JVM shutdown.
            cycler.setDaemon(true);
            cycler.start();
            meterRegistry.counter("sudokupro.ui.theme.cycles", GLOBAL_TAGS).increment();
        } else {
            applyUserPreferredTheme(scene, playerId);
        }
    }

    private void exportTheme(Scene currentScene) {
        String playerId = authService.getCurrentPlayerId();
        try {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Export Theme");
            fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSS Files", "*.css"));
            fileChooser.setInitialFileName("sudokupro_theme.css");
            File file = fileChooser.showSaveDialog(currentScene.getWindow());
            if (file != null) {
                String css = customThemes.getOrDefault(playerId, THEMES[DEFAULT_THEME_INDEX])
                    .replace("data:text/css,", "");
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write(css);
                }
                notificationService.sendTypedNotification(playerId, "ui",
                    "Theme exported to " + file.getAbsolutePath());
                meterRegistry.counter("sudokupro.ui.theme.exports", GLOBAL_TAGS).increment();
            }
        } catch (IOException e) {
            logger.error("Failed to export theme for player {}: {}", playerId, e.getMessage(), e);
            notificationService.sendNotification(playerId, "Export failed: " + e.getMessage());
        }
    }

    private void importTheme(Scene currentScene) {
        String playerId = authService.getCurrentPlayerId();
        try {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Import Theme");
            fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSS Files", "*.css"));
            File file = fileChooser.showOpenDialog(currentScene.getWindow());
            if (file != null) {
                String css = new String(Files.readAllBytes(file.toPath()));
                customThemes.put(playerId, "data:text/css," + css);
                applyCustomTheme(currentScene, playerId);
                notificationService.sendTypedNotification(playerId, "ui",
                    "Theme imported from " + file.getAbsolutePath());
                meterRegistry.counter("sudokupro.ui.theme.imports", GLOBAL_TAGS).increment();
            }
        } catch (IOException e) {
            logger.error("Failed to import theme for player {}: {}", playerId, e.getMessage(), e);
            notificationService.sendNotification(playerId, "Import failed: " + e.getMessage());
        }
    }

    private void validateScene(Scene scene) {
        if (scene == null) throw new IllegalArgumentException("Scene cannot be null");
    }

    private void validatePlayerId(String playerId) {
        if (playerId == null || playerId.trim().isEmpty())
            throw new IllegalArgumentException("Player ID cannot be null or empty");
    }

    private Optional<User> findUserByPlayerId(String playerId) {
        if (playerId == null || playerId.isBlank() || "anonymous".equals(playerId)) {
            return Optional.empty();
        }
        try {
            return userRepository.findById(Long.parseLong(playerId));
        } catch (NumberFormatException e) {
            logger.debug("Skipping non-numeric playerId '{}' in theme manager", playerId);
            return Optional.empty();
        }
    }
}
