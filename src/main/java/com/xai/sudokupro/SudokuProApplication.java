package com.xai.sudokupro;

import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.*;
import com.xai.sudokupro.ui.MainStage;
import com.xai.sudokupro.util.SudokuHealthMonitor;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;

@SpringBootApplication
@EnableScheduling
public class SudokuProApplication {

    private static final Logger logger = LoggerFactory.getLogger(SudokuProApplication.class);
    private static volatile ConfigurableApplicationContext context;
    private static final AtomicBoolean chaosActive = new AtomicBoolean(false);

    public static void main(String[] args) {

        SpringApplication app = new SpringApplication(SudokuProApplication.class);

        app.setBanner(new SudokuProBanner());
        app.setDefaultProperties(java.util.Map.of(
            "spring.profiles.active", "prod",
            "server.port", "8080",
            "spring.redis.host", "localhost",
            "spring.redis.port", "6379",
            "sudokupro.chaos.enabled", "false"
        ));

        try {
            optimizeStartup(app);

            context = app.run(args);

            Environment env = context.getBean(Environment.class);

            MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);
            meterRegistry.gauge("startup.time", System.currentTimeMillis());

            if (Boolean.parseBoolean(env.getProperty("sudokupro.chaos.enabled"))) {
                chaosActive.set(true);
                context.getBean(MetricsScheduler.class).triggerChaosMode("startup");
                triggerChaosEvents();
            }

            kickoffGuildSync(context);

            broadcastCosmicEvent("SudokuPro Online - Grid War Begins!");

            // Register the shutdown hook BEFORE launching JavaFX — launch() blocks until the
            // window closes, so anything after it runs only after the user exits the UI.
            Runtime.getRuntime().addShutdownHook(new Thread(SudokuProApplication::shutdown));

            // Headless/server mode (containers, k8s): skip the desktop UI entirely.
            // JavaFX toolkit init throws unrecoverable Errors (not Exceptions) without a
            // display, so guarding with try/catch is not enough. Set SUDOKUPRO_UI_ENABLED=false
            // (or -Dsudokupro.ui.enabled=false); the Docker image sets it by default.
            boolean uiEnabled = Boolean.parseBoolean(env.getProperty("sudokupro.ui.enabled", "true"));
            if (uiEnabled) {
                // Share the already-running Spring context with MainStage so that its init()
                // skips the second SpringApplication.run() that would collide on port 8080.
                MainStage.setSpringContext(context);

                try {
                    javafx.application.Application.launch(MainStage.class, args);
                } catch (Exception e) {
                    logger.warn("JavaFX launch failed, using fallback mode", e);
                    startFallbackTerminalMode();
                }

                // Post-UI-close health snapshot — guard against context already closed by stop().
                if (context != null && context.isActive()) {
                    context.getBean(SudokuHealthMonitor.class).runChecks();
                }
            } else {
                logger.info("UI disabled (sudokupro.ui.enabled=false) — headless server mode; web server threads keep the JVM alive");
            }

        } catch (Exception e) {
            logger.error("SudokuPro startup failed", e);
            shutdown();
        }
    }

    private static void optimizeStartup(SpringApplication app) {
        app.setLazyInitialization(true);
    }

    private static void kickoffGuildSync(ConfigurableApplicationContext context) {
        RedisSyncScheduler redisSync = context.getBean(RedisSyncScheduler.class);
        UserRepository userRepo = context.getBean(UserRepository.class);

        long activeUsers = userRepo.count();
        logger.info("Guild sync startup check: {} users detected", activeUsers);

        // Call syncRedis defensively — if Redis is not running at startup the app should still
        // launch. The scheduler will retry on its own schedule once Redis comes online.
        try {
            redisSync.syncRedis();
        } catch (Exception e) {
            logger.warn("Startup Redis sync skipped (Redis may not be running): {}", e.getMessage());
        }
    }

    private static void triggerChaosEvents() {
        logger.info("Chaos mode enabled at startup");
    }

    private static void broadcastCosmicEvent(String message) {
        NotificationService notifier = context.getBean(NotificationService.class);
        notifier.broadcastNotification("system", message);
    }

    private static void shutdown() {
        if (context != null && context.isActive()) {
            context.close();
        }
        System.exit(0);
    }

    private static void startFallbackTerminalMode() {
        System.out.println("Fallback Mode Active");
    }

    private static class SudokuProBanner implements Banner {
        @Override
        public void printBanner(Environment environment, Class<?> sourceClass, PrintStream out) {
            out.println("SudokuPro - Hyperdimensional Void Lord");
        }
    }
}
