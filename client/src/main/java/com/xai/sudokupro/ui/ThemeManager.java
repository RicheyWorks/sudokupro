package com.xai.sudokupro.ui;

import com.xai.sudokupro.client.Notifier;
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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Theme management for the desktop client. Fully client-local (AUDIT follow-up:
 * client/server separation): the theme preference and custom CSS persist in
 * {@code ~/.sudokupro/theme.properties} instead of the server's user table —
 * a display preference belongs to the machine showing the display.
 */
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
    private static final int DEFAULT_THEME_INDEX = 0;
    private static final String PREF_THEME = "theme";
    private static final String PREF_CUSTOM_CSS = "custom.css";

    private final Notifier notifier;
    private final Path prefsFile;
    private final Properties prefs = new Properties();

    // In-memory only: themes shared during this session (multi-window / future use).
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

    public ThemeManager(Notifier notifier) {
        this(notifier, Path.of(System.getProperty("user.home"), ".sudokupro", "theme.properties"));
    }

    ThemeManager(Notifier notifier, Path prefsFile) {
        this.notifier = Objects.requireNonNull(notifier);
        this.prefsFile = prefsFile;
        loadPrefs();
    }

    // =====================================================================
    // Applying themes
    // =====================================================================

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
            logger.debug("Applied theme '{}' (index {}) to scene", THEME_NAMES[normalizedIndex], normalizedIndex);
        } catch (Exception e) {
            logger.error("Failed to apply theme '{}': {}", THEME_NAMES[normalizedIndex], e.getMessage(), e);
            notifier.notify("error", "Theme switch failed: " + e.getMessage());
            applyDefaultTheme(scene);
        }
    }

    /** Applies the locally saved preference (custom CSS wins over named themes). */
    public void applyUserPreferredTheme(Scene scene) {
        validateScene(scene);
        try {
            String customCss = prefs.getProperty(PREF_CUSTOM_CSS);
            String preferredTheme = prefs.getProperty(PREF_THEME, "").toLowerCase();
            if (customCss != null && !customCss.isBlank()) {
                scene.getStylesheets().clear();
                scene.getStylesheets().add(customCss);
                logger.debug("Applied locally saved custom theme");
            } else if (sharedThemes.containsKey(preferredTheme)) {
                scene.getStylesheets().clear();
                scene.getStylesheets().add(sharedThemes.get(preferredTheme));
            } else if (PRESET_THEMES.containsKey(preferredTheme)) {
                scene.getStylesheets().clear();
                scene.getStylesheets().add(PRESET_THEMES.get(preferredTheme));
            } else {
                int themeIndex = getThemeIndexFromName(preferredTheme);
                applyTheme(scene, themeIndex != -1 ? themeIndex : DEFAULT_THEME_INDEX);
            }
        } catch (Exception e) {
            logger.error("Failed to apply preferred theme: {}", e.getMessage(), e);
            notifier.notify("error", "Failed to load preferred theme: " + e.getMessage());
            applyDefaultTheme(scene);
        }
    }

    public ComboBox<String> createThemeSelector(Scene scene) {
        ComboBox<String> themeSelector = new ComboBox<>();
        List<String> options = new ArrayList<>(Arrays.asList(THEME_NAMES));
        if (prefs.getProperty(PREF_CUSTOM_CSS) != null) {
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
                    applyCustomTheme(scene);
                } else if (sharedThemes.containsKey(selectedTheme)) {
                    applySharedTheme(scene, selectedTheme);
                } else if (PRESET_THEMES.containsKey(selectedTheme)) {
                    applyPresetTheme(scene, selectedTheme);
                }
            } else {
                applyTheme(scene, selectedIndex);
                updateUserPreference(selectedIndex);
                notifier.notify("ui", "Theme switched to: " + THEME_NAMES[selectedIndex]);
            }
        });

        themeSelector.setOnShowing(e ->
            previewTheme(scene, themeSelector.getSelectionModel().getSelectedIndex(), themeSelector.getValue()));
        themeSelector.setOnHidden(e -> applyUserPreferredTheme(scene));

        logger.debug("Theme selector created with {} options", options.size());
        return themeSelector;
    }

    private void previewTheme(Scene scene, int themeIndex, String themeName) {
        if (themeIndex >= THEMES.length) {
            if ("Custom Theme".equals(themeName)) {
                applyCustomTheme(scene);
            } else if (sharedThemes.containsKey(themeName)) {
                applySharedTheme(scene, themeName);
            } else if (PRESET_THEMES.containsKey(themeName)) {
                applyPresetTheme(scene, themeName);
            }
        } else {
            applyTheme(scene, themeIndex);
        }
    }

    private void applyCustomTheme(Scene scene) {
        scene.getStylesheets().clear();
        scene.getStylesheets().add(prefs.getProperty(PREF_CUSTOM_CSS, THEMES[DEFAULT_THEME_INDEX]));
        logger.debug("Applied custom theme");
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
        String newTheme = THEME_NAMES[themeIndex].toLowerCase().replace(" ", "-");
        prefs.setProperty(PREF_THEME, newTheme);
        prefs.remove(PREF_CUSTOM_CSS);
        savePrefs();
        logger.debug("Updated local theme preference to '{}'", newTheme);
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

    // =====================================================================
    // Customizer
    // =====================================================================

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
            saveCustomTheme(customCSS);
            applyCustomTheme(currentScene);
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

        logger.info("Theme customizer opened");
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

    private void saveCustomTheme(String customCSS) {
        prefs.setProperty(PREF_CUSTOM_CSS, "data:text/css," + customCSS);
        savePrefs();
        notifier.notify("ui", "Custom theme saved!");
    }

    private String getCurrentThemeName() {
        if (prefs.getProperty(PREF_CUSTOM_CSS) != null) return "Custom Theme";
        String pref = prefs.getProperty(PREF_THEME, "");
        if (sharedThemes.containsKey(pref)) return pref;
        if (PRESET_THEMES.containsKey(pref)) return pref;
        int index = getThemeIndexFromName(pref);
        return index != -1 ? THEME_NAMES[index] : THEME_NAMES[DEFAULT_THEME_INDEX];
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
    }

    public void resetTheme(Scene scene) {
        try {
            prefs.remove(PREF_CUSTOM_CSS);
            prefs.setProperty(PREF_THEME, "default");
            savePrefs();
            applyTheme(scene, DEFAULT_THEME_INDEX);
            notifier.notify("ui", "Theme reset to default");
        } catch (Exception e) {
            logger.error("Failed to reset theme: {}", e.getMessage(), e);
            notifier.notify("error", "Theme reset failed: " + e.getMessage());
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
        notifier.notify("ui", "Event theme applied: " + THEME_NAMES[themeIndex]);
    }

    private void shareTheme(String themeName, String css) {
        if (themeName == null || themeName.trim().isEmpty()) {
            notifier.notify("error", "Please enter a theme name to share.");
            return;
        }
        if (sharedThemes.containsKey(themeName)) {
            notifier.notify("error", "Theme name '" + themeName + "' already exists.");
            return;
        }
        sharedThemes.put(themeName, "data:text/css," + css);
        notifier.notify("ui", "Theme '" + themeName + "' shared with the galaxy!");
    }

    private void toggleThemeCycling(Scene scene) {
        isCycling = !isCycling;
        if (isCycling) {
            Thread cycler = new Thread(() -> {
                int index = 0;
                List<String> allThemes = new ArrayList<>(Arrays.asList(THEMES));
                String customCss = prefs.getProperty(PREF_CUSTOM_CSS);
                if (customCss != null) allThemes.add(customCss);
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
        } else {
            applyUserPreferredTheme(scene);
        }
    }

    private void exportTheme(Scene currentScene) {
        try {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Export Theme");
            fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSS Files", "*.css"));
            fileChooser.setInitialFileName("sudokupro_theme.css");
            File file = fileChooser.showSaveDialog(currentScene.getWindow());
            if (file != null) {
                String css = prefs.getProperty(PREF_CUSTOM_CSS, THEMES[DEFAULT_THEME_INDEX])
                    .replace("data:text/css,", "");
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write(css);
                }
                notifier.notify("ui", "Theme exported to " + file.getAbsolutePath());
            }
        } catch (IOException e) {
            logger.error("Failed to export theme: {}", e.getMessage(), e);
            notifier.notify("error", "Export failed: " + e.getMessage());
        }
    }

    private void importTheme(Scene currentScene) {
        try {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Import Theme");
            fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSS Files", "*.css"));
            File file = fileChooser.showOpenDialog(currentScene.getWindow());
            if (file != null) {
                String css = new String(Files.readAllBytes(file.toPath()));
                prefs.setProperty(PREF_CUSTOM_CSS, "data:text/css," + css);
                savePrefs();
                applyCustomTheme(currentScene);
                notifier.notify("ui", "Theme imported from " + file.getAbsolutePath());
            }
        } catch (IOException e) {
            logger.error("Failed to import theme: {}", e.getMessage(), e);
            notifier.notify("error", "Import failed: " + e.getMessage());
        }
    }

    // =====================================================================
    // Local persistence
    // =====================================================================

    private void loadPrefs() {
        if (!Files.exists(prefsFile)) return;
        try (InputStream in = Files.newInputStream(prefsFile)) {
            prefs.load(in);
        } catch (IOException e) {
            logger.warn("Could not read theme preferences from {}: {}", prefsFile, e.getMessage());
        }
    }

    private void savePrefs() {
        try {
            Files.createDirectories(prefsFile.getParent());
            try (OutputStream out = Files.newOutputStream(prefsFile)) {
                prefs.store(out, "SudokuPro client theme preferences");
            }
        } catch (IOException e) {
            logger.warn("Could not save theme preferences to {}: {}", prefsFile, e.getMessage());
        }
    }

    private void validateScene(Scene scene) {
        if (scene == null) throw new IllegalArgumentException("Scene cannot be null");
    }
}
