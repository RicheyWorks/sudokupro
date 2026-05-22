package com.xai.sudokupro.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Custom health check for Redis connectivity in SudokuPro.
 * Reports Redis status to Spring Actuator and logs diagnostics.
 * Activated under the "redis" Spring profile for consistency.
 */
@Component
@Profile("redis") // Optional: aligns with RedisConfig for mixed environments
public class RedisHealthCheck implements HealthIndicator {

    private static final Logger logger = LoggerFactory.getLogger(RedisHealthCheck.class);

    private final RedisConnectionFactory connectionFactory;

    public RedisHealthCheck(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Health health() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            // Optional future enhancement: Measure ping latency
            /*
            long start = System.nanoTime();
            String pingResponse = connection.ping();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            */
            String pingResponse = connection.ping();

            if ("PONG".equals(pingResponse)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Redis ping successful: {}", pingResponse);
                }
                return Health.up()
                    .withDetail("response", pingResponse)
                    .withDetail("checkedAt", Instant.now().toString()) // ISO 8601 format
                    // .withDetail("pingTimeMs", elapsedMs) // Uncomment when latency tracking is added
                    .build();
            } else {
                logger.warn("Redis ping returned unexpected response: {}", pingResponse);
                return Health.down()
                    .withDetail("response", pingResponse)
                    .withDetail("checkedAt", Instant.now().toString())
                    .build();
            }
        } catch (Exception e) {
            logger.error("Redis health check failed: {}", e.getMessage());
            return Health.down()
                .withException(e)
                .withDetail("error", e.getMessage())
                .withDetail("checkedAt", Instant.now().toString())
                .build();
        }
    }

    // Legacy method for direct boolean checks if needed
    public boolean isRedisHealthy() {
        return health().getStatus().equals(Health.Status.UP);
    }
}
