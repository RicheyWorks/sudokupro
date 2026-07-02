package com.xai.sudokupro;

import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.*;
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

    /** Headless server entry point. The desktop app starts via the client module's ClientLauncher. */
    public static void main(String[] args) {
        try {
            start(args);
            logger.info("SudokuPro server started (headless) — web server threads keep the JVM alive");
        } catch (Exception e) {
            logger.error("SudokuPro startup failed", e);
            shutdown();
        }
    }

    /**
     * Boots the Spring context and runs the shared startup sequence. Used by both the
     * headless server main above and the client module's ClientLauncher, which shares
     * this context with the JavaFX UI (single JVM, no second port-8080 collision).
     */
    public static ConfigurableApplicationContext start(String[] args) {

        SpringApplication app = new SpringApplication(SudokuProApplication.class);

        app.setBanner(new SudokuProBanner());
        app.setDefaultProperties(java.util.Map.of(
            "spring.profiles.active", "prod",
            "server.port", "8080",
            "spring.data.redis.host", "localhost",
            "spring.data.redis.port", "6379",
            "sudokupro.chaos.enabled", "false"
        ));

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

        Runtime.getRuntime().addShutdownHook(new Thread(SudokuProApplication::shutdown));

        return context;
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

    /** Public so ClientLauncher can trigger an orderly stop when the UI exits. */
    public static void shutdown() {
        if (context != null && context.isActive()) {
            context.close();
        }
        System.exit(0);
    }

    private static class SudokuProBanner implements Banner {
        @Override
        public void printBanner(Environment environment, Class<?> sourceClass, PrintStream out) {
            out.println("SudokuPro - Hyperdimensional Void Lord");
        }
    }
}
