package com.xai.sudokupro.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the defect class "a fallback that cannot work".
 *
 * <p>Three related failures shipped together:
 * <ul>
 *   <li>The client had <b>no {@code src/main/resources} at all</b>, so every one of
 *       the four bundled themes resolved to {@code null} in every build.</li>
 *   <li>{@code applyTheme} responded to that by clearing the scene's stylesheet list
 *       and then having nothing to add — so choosing a theme stripped the window
 *       bare instead of falling back.</li>
 *   <li>{@code applyCustomTheme}, {@code applySharedTheme} and {@code applyPresetTheme}
 *       all fell back to {@code THEMES[0]}, which is the classpath <em>path</em>
 *       {@code "/css/cyber-grid.css"} — not a URL. JavaFX cannot load a bare path as
 *       a stylesheet, so the fallback was itself a no-op.</li>
 * </ul>
 *
 * <p>Tested through the package-private resolvers rather than through a
 * {@code Scene}: a headless container has no JavaFX toolkit, and nothing about
 * choosing a stylesheet needs one. Requiring a Scene is exactly why none of this
 * was ever covered.
 */
class ThemeResolutionTest {

    @TempDir
    Path tempDir;

    private ThemeManager managerWith(String... keyValuePairs) throws IOException {
        Properties props = new Properties();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            props.setProperty(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        Path prefs = tempDir.resolve("theme.properties");
        try (OutputStream out = Files.newOutputStream(prefs)) {
            props.store(out, "test");
        }
        return new ThemeManager((type, message) -> { }, prefs);
    }

    /** The bundled stylesheets must actually be on the classpath — the original sin. */
    @Test
    void everyBundledThemeIsOnTheClasspath() throws IOException {
        ThemeManager manager = managerWith();
        for (int i = 0; i < 4; i++) {
            Optional<String> url = manager.stylesheetUrl(i);
            assertTrue(url.isPresent(), "Bundled theme " + i + " is not shipped in the jar");
            assertTrue(url.get().endsWith(".css"), url.get());
        }
    }

    /**
     * A stylesheet that is not on the classpath must resolve to <em>nothing</em>, so
     * the caller can keep the styling it already has. Returning the raw classpath
     * path instead is what made the fallback silently do nothing.
     */
    @Test
    void aMissingStylesheetResolvesToNothingNotToARawClasspathPath() throws IOException {
        ThemeManager manager = managerWith();

        assertEquals(Optional.empty(), manager.resolveResource("/css/no-such-theme.css"),
            "A classpath path is not something Scene.getStylesheets() can load, so a "
            + "stylesheet that is not there must resolve to nothing and let the caller "
            + "keep the styling it already has");
    }

    @Test
    void aPresentStylesheetResolvesToALoadableUrl() throws IOException {
        ThemeManager manager = managerWith();
        String url = manager.stylesheetUrl(0).orElseThrow();
        assertTrue(url.startsWith("file:") || url.startsWith("jar:"), url);
        assertTrue(url.endsWith("/css/cyber-grid.css"), url);
    }

    /** A ComboBox with nothing selected reports index -1, which used to index the array. */
    @Test
    void aNegativeThemeIndexDoesNotThrow() throws IOException {
        ThemeManager manager = managerWith();
        assertDoesNotThrow(() -> manager.stylesheetUrl(-1));
        assertTrue(manager.stylesheetUrl(-1).isPresent());
    }

    @Test
    void savedCustomCssWinsOverEveryNamedTheme() throws IOException {
        ThemeManager manager = managerWith(
            "custom.css", "data:text/css,.root { -fx-background-color: #123456; }",
            "theme", "retro-pixel");

        assertEquals(Optional.of("data:text/css,.root { -fx-background-color: #123456; }"),
            manager.preferredStylesheet());
    }

    @Test
    void aSavedNamedThemeResolvesToThatThemesStylesheet() throws IOException {
        ThemeManager manager = managerWith("theme", "retro-pixel");
        String css = manager.preferredStylesheet().orElseThrow();
        assertTrue(css.endsWith("retro-pixel.css"), css);
    }

    @Test
    void aSavedPresetThemeResolvesToItsInlineCss() throws IOException {
        ThemeManager manager = managerWith("theme", "Midnight Cosmos");
        String css = manager.preferredStylesheet().orElseThrow();
        assertTrue(css.startsWith("data:text/css,"), css);
        assertTrue(css.contains("#0A0A23"), css);
    }

    /**
     * Preset and shared themes are keyed by display name ("Midnight Cosmos"), but
     * the resolver lower-cased the saved value before looking them up, so a saved
     * preset could never match its own key. Combined with the ComboBox's
     * {@code setOnHidden} handler — which re-applies the saved preference the
     * instant the popup closes — choosing a preset applied it and then undid it
     * before the player let go of the mouse.
     */
    @Test
    void aPresetSavedInADifferentCaseStillResolves() throws IOException {
        ThemeManager manager = managerWith("theme", "midnight cosmos");
        String css = manager.preferredStylesheet().orElseThrow();
        assertTrue(css.contains("#0A0A23"), css);
    }

    /** The other half of that fix: a chosen preset has to be written down at all. */
    @Test
    void aRememberedThemeSurvivesARestart() throws IOException {
        Path prefs = tempDir.resolve("theme.properties");
        ThemeManager first = new ThemeManager((type, message) -> { }, prefs);
        first.rememberNamedTheme("Solar Flare");

        ThemeManager afterRestart = new ThemeManager((type, message) -> { }, prefs);
        String css = afterRestart.preferredStylesheet().orElseThrow();
        assertTrue(css.contains("#FFD700"), css);
    }

    /** Choosing a named theme must drop the custom CSS, or the custom sheet keeps winning. */
    @Test
    void rememberingANamedThemeClearsAnyCustomCss() throws IOException {
        ThemeManager manager = managerWith("custom.css", "data:text/css,.root {}");
        manager.rememberNamedTheme("manga-mode");

        String css = manager.preferredStylesheet().orElseThrow();
        assertTrue(css.endsWith("manga-mode.css"), css);
    }

    @Test
    void anUnknownPreferenceFallsBackToTheDefaultTheme() throws IOException {
        ThemeManager manager = managerWith("theme", "no-such-theme");
        assertEquals(manager.stylesheetUrl(0), manager.preferredStylesheet());
    }

    @Test
    void noPreferencesAtAllStillResolvesToTheDefault() throws IOException {
        Path missing = tempDir.resolve("does-not-exist.properties");
        ThemeManager manager = new ThemeManager((type, message) -> { }, missing);
        assertEquals(manager.stylesheetUrl(0), manager.preferredStylesheet());
    }

    @Test
    void aBlankCustomCssIsIgnoredRatherThanAppliedAsAnEmptyStylesheet() throws IOException {
        ThemeManager manager = managerWith("custom.css", "   ", "theme", "manga-mode");
        String css = manager.preferredStylesheet().orElseThrow();
        assertTrue(css.endsWith("manga-mode.css"), css);
    }
}
