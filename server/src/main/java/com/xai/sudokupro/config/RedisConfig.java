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

@Profile("redis")
@Configuration
public class RedisConfig {

    private static final Logger logger = LoggerFactory.getLogger(RedisConfig.class);

    // Consolidation (AUDIT P1-4): connection settings live ONLY in spring.data.redis.*,
    // consumed by Boot's autoconfigured RedisConnectionFactory. The duplicate JedisPool
    // this class used to build from custom spring.redis.* bindings is gone — AppConfig
    // provides the single JedisPool for the remaining raw-Jedis consumers, reading the
    // same canonical properties. App-level knobs use the sudokupro.redis.* namespace.

    @Value("${sudokupro.redis.key-prefix:sudokupro:}")
    private String keyPrefix;

    @Value("${sudokupro.redis.ttl-seconds:600}")
    private long defaultTtl;

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
