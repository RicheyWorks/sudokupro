package com.xai.sudokupro.service;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisException;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cosmic synchronizer of SudokuPro's Redis galaxy.
 */
@Service
public class RedisSyncScheduler {

    private static final Logger logger = LoggerFactory.getLogger(RedisSyncScheduler.class);
    private static final long SYNC_INTERVAL_MS = 300_000;
    private static final int RETRY_ATTEMPTS = 3;
    private static final double SUSPICION_THRESHOLD = 75.0;
    private static final Tags GLOBAL_TAGS = Tags.of("app", "SudokuPro");
    private static final int[] TIER_THRESHOLDS = {0, 1000, 5000, 10000, 25000};

    private final JedisPool jedisPool;
    private final UserRepository userRepository;
    private final GameRepository gameRepository;
    private final AnalyticsService analyticsService;
    private final AntiCheatEngine antiCheatEngine;
    private final MeterRegistry meterRegistry;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    @Autowired
    public RedisSyncScheduler(JedisPool jedisPool,
                              UserRepository userRepository,
                              GameRepository gameRepository,
                              AnalyticsService analyticsService,
                              AntiCheatEngine antiCheatEngine,
                              MeterRegistry meterRegistry) {

        this.jedisPool = Objects.requireNonNull(jedisPool);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.gameRepository = Objects.requireNonNull(gameRepository);
        this.analyticsService = Objects.requireNonNull(analyticsService);
        this.antiCheatEngine = Objects.requireNonNull(antiCheatEngine);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);

        logger.info("RedisSyncScheduler initialized");
    }

    @Scheduled(fixedRate = SYNC_INTERVAL_MS)
    @Retryable(maxAttempts = RETRY_ATTEMPTS, backoff = @Backoff(delay = 5000))
    public void syncRedis() {

        if (!isRunning.compareAndSet(false, true)) {
            logger.debug("Skipping Redis sync—already running");
            return;
        }

        MDC.put("thread", "redis-sync");
        logger.info("Syncing Redis...");

        try (Jedis jedis = jedisPool.getResource()) {

            Map<String, String> metrics = gatherMetrics();

            jedis.mset(metrics.entrySet().stream()
                .flatMap(e -> List.of(e.getKey(), e.getValue()).stream())
                .toArray(String[]::new));

            meterRegistry.counter("redis.sync.success", GLOBAL_TAGS).increment();

        } catch (JedisException e) {

            meterRegistry.counter("redis.sync.failure", GLOBAL_TAGS).increment();
            logger.error("Redis sync failed", e);
            throw e;

        } catch (Exception e) {

            meterRegistry.counter("redis.sync.failure", GLOBAL_TAGS).increment();
            throw new RuntimeException(e);

        } finally {
            isRunning.set(false);
            MDC.clear();
        }
    }

    private Map<String, String> gatherMetrics() {

        Map<String, String> metrics = new HashMap<>();

        metrics.put("last_sync", String.valueOf(System.currentTimeMillis()));

        long activeUsers = userRepository.countActiveUsersSince(LocalDateTime.now().minusDays(7));
        metrics.put("active_users", String.valueOf(activeUsers));

        long totalUsers = userRepository.count();
        metrics.put("total_users", String.valueOf(totalUsers));

        long totalGems = userRepository.findAll().stream()
            .mapToLong(User::getGems)
            .sum();

        metrics.put("total_gems", String.valueOf(totalGems));

        long activeGames = gameRepository
            .findActiveUnfinishedGames(LocalDateTime.now().minusHours(1), null)
            .size();

        metrics.put("active_games", String.valueOf(activeGames));

        double avgSolveTime = analyticsService.getAverageSolveTime();
        metrics.put("avg_solve_time", String.valueOf(avgSolveTime));

        long suspicious = antiCheatEngine.getCheatSuspicionScores().values().stream()
            .filter(v -> v > SUSPICION_THRESHOLD)
            .count();

        metrics.put("suspicious", String.valueOf(suspicious));

        metrics.put("tier_unranked", String.valueOf(
            userRepository.countByPointsBetween(TIER_THRESHOLDS[0], TIER_THRESHOLDS[1] - 1)
        ));

        metrics.put("tier_bronze", String.valueOf(
            userRepository.countByPointsBetween(TIER_THRESHOLDS[1], TIER_THRESHOLDS[2] - 1)
        ));

        metrics.put("tier_silver", String.valueOf(
            userRepository.countByPointsBetween(TIER_THRESHOLDS[2], TIER_THRESHOLDS[3] - 1)
        ));

        metrics.put("tier_gold", String.valueOf(
            userRepository.countByPointsBetween(TIER_THRESHOLDS[3], TIER_THRESHOLDS[4] - 1)
        ));

        metrics.put("tier_cosmic", String.valueOf(
            userRepository.countByPointsGreaterThanEqual(TIER_THRESHOLDS[4])
        ));

        return metrics;
    }

    @PreDestroy
    public void shutdown() {
        logger.info("RedisSyncScheduler shutting down");
    }
}
