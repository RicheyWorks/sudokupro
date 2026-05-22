package com.xai.sudokupro.config;

import com.xai.sudokupro.model.SudokuBoard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Profile("redis")
@Configuration
public class RedisConfig {

    private static final Logger logger = LoggerFactory.getLogger(RedisConfig.class);

    @Value("${spring.redis.key-prefix:sudokupro:}")
    private String keyPrefix;

    @Value("${spring.redis.ttl.seconds:600}")
    private long defaultTtl;

    @Value("${spring.redis.timeout:2000}")
    private int timeout;

    @Value("${spring.redis.host:localhost}")
    private String host;

    @Value("${spring.redis.port:6379}")
    private int port;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    @Bean
    public JedisPool jedisPool() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(128);
        poolConfig.setMaxIdle(128);
        poolConfig.setMinIdle(16);
        poolConfig.setTestOnBorrow(true);

        return redisPassword.isEmpty()
            ? new JedisPool(poolConfig, host, port, timeout)
            : new JedisPool(poolConfig, host, port, timeout, redisPassword);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory,
                                                       @Qualifier("prefixedKeySerializer") StringRedisSerializer prefixedKeySerializer) {
        return createTemplate(connectionFactory, Object.class, prefixedKeySerializer);
    }

    @Bean
    public RedisTemplate<String, String> stringRedisTemplate(RedisConnectionFactory connectionFactory,
                                                            @Qualifier("prefixedKeySerializer") StringRedisSerializer prefixedKeySerializer) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(prefixedKeySerializer);
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(prefixedKeySerializer);
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedisTemplate<String, SudokuBoard> boardRedisTemplate(RedisConnectionFactory connectionFactory,
                                                                @Qualifier("prefixedKeySerializer") StringRedisSerializer prefixedKeySerializer) {
        return createTemplate(connectionFactory, SudokuBoard.class, prefixedKeySerializer);
    }

    @Bean
    public HealthIndicator redisHealthIndicator(JedisPool jedisPool) {
        return () -> {
            try (var jedis = jedisPool.getResource()) {
                String pong = jedis.ping();

                return pong.equals("PONG")
                    ? Health.up()
                        .withDetail("host", host)
                        .withDetail("port", port)
                        .withDetail("timeout", timeout)
                        .build()
                    : Health.down().build();

            } catch (Exception e) {
                return Health.down(e).build();
            }
        };
    }

    @Bean("prefixedKeySerializer")
    public StringRedisSerializer prefixedKeySerializer() {
        return new StringRedisSerializer() {
            @Override
            public byte[] serialize(String string) {
                return super.serialize(keyPrefix + string);
            }
        };
    }

    private <T> RedisTemplate<String, T> createTemplate(RedisConnectionFactory connectionFactory,
                                                        Class<T> clazz,
                                                        StringRedisSerializer prefixedKeySerializer) {
        RedisTemplate<String, T> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(prefixedKeySerializer);
        template.setValueSerializer(new Jackson2JsonRedisSerializer<>(clazz));
        template.setHashKeySerializer(prefixedKeySerializer);
        template.setHashValueSerializer(new Jackson2JsonRedisSerializer<>(clazz));
        template.afterPropertiesSet();
        return template;
    }

    public long getDefaultTtl() {
        return defaultTtl;
    }
}
