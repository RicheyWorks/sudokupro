package com.xai.sudokupro.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

import java.time.Instant;

/**
 * Custom health check for Redis connectivity in SudokuPro using JedisPool.
 * Reports status to Spring Actuator with cosmic precision.
 * Activated under the "redis" Spring profile.
 */
@Component
@Profile("redis") // Aligns with RedisConfig for mixed environments
public class RedisHealthIndicator implements HealthIndicator {

    private static final Logger logger = LoggerFactory.getLogger(RedisHealthIndicator.class);

    private final JedisPool jedisPool;

    public RedisHealthIndicator(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    @Override
    public Health health() {
        try (var jedis = jedisPool.getResource()) {
            String pingResponse = jedis.ping();
            if ("PONG".equals(pingResponse)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Redis Jedis ping successful: {}", pingResponse);
                }
                return Health.up()
                    .withDetail("redis", "Connection successful")
                    .withDetail("response", pingResponse)
                    .withDetail("checkedAt", Instant.now().toString()) // ISO 8601 format
                    .build();
            } else {
                logger.warn("Redis Jedis ping returned unexpected response: {}", pingResponse);
                return Health.down()
                    .withDetail("redis", "Connection failed")
                    .withDetail("response", pingResponse)
                    .withDetail("checkedAt", Instant.now().toString())
                    .build();
            }
        } catch (Exception e) {
            logger.error("Redis Jedis health check failed: {}", e.getMessage());
            return Health.down()
                .withException(e)
                .withDetail("redis", "Connection failed: " + e.getMessage())
                .withDetail("checkedAt", Instant.now().toString())
                .build();
        }
    }
}
