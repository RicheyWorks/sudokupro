package com.xai.sudokupro.client;

import com.xai.sudokupro.SudokuProApplication;
import com.xai.sudokupro.ui.MainStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Desktop entry point (AUDIT P1-2). Boots the embedded server via
 * SudokuProApplication.start(), then hands the running Spring context to the
 * JavaFX MainStage — same single-JVM behavior as before the module split, but
 * the JavaFX dependency now lives only in this client module.
 */
public final class ClientLauncher {

    private static final Logger logger = LoggerFactory.getLogger(ClientLauncher.class);

    private ClientLauncher() {}

    public static void main(String[] args) {
        try {
            ConfigurableApplicationContext context = SudokuProApplication.start(args);

            // Share the already-running Spring context with MainStage so its init()
            // skips the second SpringApplication.run() that would collide on port 8080.
            MainStage.setSpringContext(context);

            try {
                javafx.application.Application.launch(MainStage.class, args);
            } catch (Exception e) {
                logger.warn("JavaFX launch failed, using fallback mode", e);
                System.out.println("Fallback Mode Active");
            }
        } catch (Exception e) {
            logger.error("SudokuPro client startup failed", e);
            SudokuProApplication.shutdown();
        }
    }
}
