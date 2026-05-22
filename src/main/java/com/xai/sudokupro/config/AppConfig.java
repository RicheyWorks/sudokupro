package com.xai.sudokupro.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.service.LeaderboardService;
import com.xai.sudokupro.util.SecureRandomGenerator;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Central Spring configuration. Redis beans are conditional so they yield to
 * RedisConfig when the "redis" profile is active.
 */
@Configuration
@EnableScheduling
public class AppConfig {

    @Bean
    public SecureRandomGenerator secureRandomGenerator(io.micrometer.core.instrument.MeterRegistry registry) {
        return new SecureRandomGenerator(registry);
    }

    @Bean
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(4); ex.setMaxPoolSize(8);
        ex.setQueueCapacity(100); ex.setThreadNamePrefix("SudokuPro-Task-");
        ex.initialize();
        return ex;
    }

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() { return new ObjectMapper(); }

    @Bean
    public ConcurrentHashMap<Long, String> playerStatsCache() { return new ConcurrentHashMap<>(); }

    @Bean
    @ConditionalOnMissingBean(JedisPool.class)
    public JedisPool jedisPool() {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(128); cfg.setMaxIdle(128); cfg.setMinIdle(16); cfg.setTestOnBorrow(true);
        return new JedisPool(cfg, "localhost", 6379, 2000);
    }

    @Bean
    @ConditionalOnMissingBean(name = "redisHealthIndicator")
    public HealthIndicator redisHealthIndicator(JedisPool jedisPool) {
        return new RedisHealthIndicator(jedisPool);
    }

    @Bean("redisTemplate")
    @ConditionalOnMissingBean(name = "redisTemplate")
    public RedisTemplate<String, User> redisTemplate(RedisConnectionFactory cf) {
        return buildTemplate(cf, User.class);
    }

    @Bean("gameStateRedisTemplate")
    @ConditionalOnMissingBean(name = "gameStateRedisTemplate")
    public RedisTemplate<String, SudokuBoard> gameStateRedisTemplate(RedisConnectionFactory cf) {
        return buildTemplate(cf, SudokuBoard.class);
    }

    @Bean("stringRedisTemplate")
    @ConditionalOnMissingBean(name = "stringRedisTemplate")
    public RedisTemplate<String, String> stringRedisTemplate(RedisConnectionFactory cf) {
        RedisTemplate<String, String> t = new RedisTemplate<>();
        t.setConnectionFactory(cf);
        t.setKeySerializer(new StringRedisSerializer());
        t.setValueSerializer(new StringRedisSerializer());
        t.afterPropertiesSet();
        return t;
    }

    @Bean
    public Executor gameEventDispatcher() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2); ex.setMaxPoolSize(4);
        ex.setQueueCapacity(50); ex.setThreadNamePrefix("GameEvent-");
        ex.initialize();
        return ex;
    }

    @Bean
    public Runnable leaderboardRefreshScheduler(LeaderboardService leaderboardService,
                                                 RedisTemplate<String, User> redisTemplate) {
        return () -> {
            try {
                leaderboardService.refreshLeaderboard();
                redisTemplate.opsForValue().set("leaderboard:lastRefresh", LocalDateTime.now().toString());
            } catch (Exception e) {
                System.err.println("Leaderboard refresh failed: " + e.getMessage());
            }
        };
    }

    // ---- Private helper ----
    private <T> RedisTemplate<String, T> buildTemplate(RedisConnectionFactory cf, Class<T> clazz) {
        RedisTemplate<String, T> t = new RedisTemplate<>();
        t.setConnectionFactory(cf);
        t.setKeySerializer(new StringRedisSerializer());
        t.setValueSerializer(new Jackson2JsonRedisSerializer<>(clazz));
        t.setHashKeySerializer(new StringRedisSerializer());
        t.setHashValueSerializer(new Jackson2JsonRedisSerializer<>(clazz));
        t.afterPropertiesSet();
        return t;
    }
}
