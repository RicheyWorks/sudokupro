package com.xai.sudokupro.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.service.LeaderboardService;
import com.xai.sudokupro.util.SecureRandomGenerator;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Central Spring configuration. Redis beans are conditional so they yield to
 * RedisConfig when the "redis" profile is active.
 */
@Configuration
@EnableScheduling
@EnableRetry
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
    public ObjectMapper objectMapper() {
        // AppConfig is component-scanned and runs before Spring Boot's JacksonAutoConfiguration,
        // so this bean wins the @ConditionalOnMissingBean race. Register JavaTimeModule here so
        // that LocalDateTime fields (e.g. SudokuBoard.startTime) serialize correctly in both
        // Jackson2JsonRedisSerializer and the REST layer.
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    public ConcurrentHashMap<Long, String> playerStatsCache() { return new ConcurrentHashMap<>(); }

    /** UTC clock — the daily puzzle's day boundary; tests override with a fixed clock. */
    @Bean
    @ConditionalOnMissingBean(java.time.Clock.class)
    public java.time.Clock clock() { return java.time.Clock.systemUTC(); }

    // Single JedisPool for the remaining raw-Jedis consumers (RedisSyncScheduler,
    // RedisHealthIndicator, SudokuHealthMonitor). Reads the SAME spring.data.redis.*
    // properties as Boot's connection factory so both clients always target the same
    // server — previously this was hardcoded to localhost while a second pool in
    // RedisConfig read custom spring.redis.* keys. (AUDIT P1-4)
    @Bean
    @ConditionalOnMissingBean(JedisPool.class)
    public JedisPool jedisPool(@org.springframework.beans.factory.annotation.Value("${spring.data.redis.host:localhost}") String host,
                               @org.springframework.beans.factory.annotation.Value("${spring.data.redis.port:6379}") int port) {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(128); cfg.setMaxIdle(128); cfg.setMinIdle(16); cfg.setTestOnBorrow(true);
        return new JedisPool(cfg, host, port, 2000);
    }

    @Bean
    @ConditionalOnMissingBean(name = "redisHealthIndicator")
    public HealthIndicator redisHealthIndicator(JedisPool jedisPool) {
        return new RedisHealthIndicator(jedisPool);
    }

    @Bean("redisTemplate")
    @ConditionalOnMissingBean(name = "redisTemplate")
    public RedisTemplate<String, User> redisTemplate(RedisConnectionFactory cf, ObjectMapper objectMapper) {
        return buildTemplate(cf, User.class, objectMapper);
    }

    @Bean("gameStateRedisTemplate")
    @ConditionalOnMissingBean(name = "gameStateRedisTemplate")
    public RedisTemplate<String, SudokuBoard> gameStateRedisTemplate(RedisConnectionFactory cf, ObjectMapper objectMapper) {
        return buildTemplate(cf, SudokuBoard.class, objectMapper);
    }

    // stringRedisTemplate intentionally omitted — Spring Boot's RedisAutoConfiguration
    // provides it automatically. Defining it here caused a BeanDefinitionOverrideException.

    @Bean
    public Executor gameEventDispatcher() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2); ex.setMaxPoolSize(4);
        ex.setQueueCapacity(50); ex.setThreadNamePrefix("GameEvent-");
        ex.initialize();
        return ex;
    }

    /**
     * Periodically evicts the leaderboard caches and rolls the recent-score window.
     *
     * <p>This used to be a bare {@code Runnable} bean. Nothing consumed it — no
     * {@code @Scheduled}, no {@code TaskScheduler.schedule}, no {@code ApplicationRunner};
     * grepping the name found the declaration and nothing else. It read in review as a
     * working scheduler and had never run in production.
     *
     * <p>What that cost: {@code refreshLeaderboard} is the ONLY {@code @CacheEvict} for
     * {@code topPlayers}, {@code leaderboardSummary} and {@code publicLeaderboard}, and the
     * only reset of the {@code scoreDeltas} window. So the leaderboard caches were evicted
     * by nothing but their TTL, and {@code pointsDelta} — documented as a "recent" figure —
     * silently reported the monotonically growing lifetime total instead, out of a map that
     * never rolled over. Deleting the bean entirely would not have failed a single test.
     *
     * <p>Now a real scheduled method on a real bean, so Spring's post-processor can see it.
     */
    @Component
    static class LeaderboardRefreshJob {
        private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(LeaderboardRefreshJob.class);
        private final LeaderboardService leaderboardService;

        LeaderboardRefreshJob(LeaderboardService leaderboardService) {
            this.leaderboardService = leaderboardService;
        }

        @org.springframework.scheduling.annotation.Scheduled(
            fixedRateString = "${sudokupro.leaderboard.refresh-ms:300000}")
        public void refresh() {
            try {
                leaderboardService.refreshLeaderboard();
            } catch (Exception e) {
                // Logged, not rethrown: a failed refresh must not retire the schedule, and
                // a stale leaderboard is not worth failing a health check over.
                log.warn("Leaderboard refresh failed (non-fatal): {}", e.getMessage(), e);
            }
        }
    }

    // ---- Private helper ----
    // Uses the Spring-managed ObjectMapper (auto-configured with JavaTimeModule by
    // Spring Boot's JacksonAutoConfiguration) so that LocalDateTime fields in
    // SudokuBoard can be serialized/deserialized without an InvalidDefinitionException.
    private <T> RedisTemplate<String, T> buildTemplate(RedisConnectionFactory cf, Class<T> clazz,
                                                        ObjectMapper objectMapper) {
        Jackson2JsonRedisSerializer<T> valueSerializer = new Jackson2JsonRedisSerializer<>(objectMapper, clazz);
        RedisTemplate<String, T> t = new RedisTemplate<>();
        t.setConnectionFactory(cf);
        t.setKeySerializer(new StringRedisSerializer());
        t.setValueSerializer(valueSerializer);
        t.setHashKeySerializer(new StringRedisSerializer());
        t.setHashValueSerializer(valueSerializer);
        t.afterPropertiesSet();
        return t;
    }
}
