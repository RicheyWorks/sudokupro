package com.xai.sudokupro.config;

import com.xai.sudokupro.model.SudokuBoard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
        // Use an ObjectMapper with JavaTimeModule so that LocalDateTime fields
        // (e.g. SudokuBoard.startTime) serialize without InvalidDefinitionException.
        com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Jackson2JsonRedisSerializer<T> valueSerializer = new Jackson2JsonRedisSerializer<>(mapper, clazz);
        RedisTemplate<String, T> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(prefixedKeySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashKeySerializer(prefixedKeySerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }

    public long getDefaultTtl() {
        return defaultTtl;
    }
}
