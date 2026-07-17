package com.xai.sudokupro.service.push;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DeviceTokenStoreTest {

    @Test
    @SuppressWarnings("unchecked")
    void registerAndFindGoThroughRedisWithTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("sudokupro:push:token:richmond")).thenReturn("tok-1");

        DeviceTokenStore store = new DeviceTokenStore(redis);
        store.register("richmond", "tok-1");

        verify(ops).set(eq("sudokupro:push:token:richmond"), eq("tok-1"), any(Duration.class));
        assertEquals(Optional.of("tok-1"), store.find("richmond"));
    }

    @Test
    void degradesToLocalMapWhenRedisIsDown() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class,
            inv -> { throw new RedisConnectionFailureException("down (test)"); });

        DeviceTokenStore store = new DeviceTokenStore(redis);
        store.register("richmond", "tok-local");

        assertEquals(Optional.of("tok-local"), store.find("richmond"),
            "single-replica fallback must keep tokens usable while Redis is down");

        store.remove("richmond");
        assertEquals(Optional.empty(), store.find("richmond"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void removeDeletesFromBothTiers() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        lenient().when(redis.opsForValue()).thenReturn(ops);
        when(redis.delete(anyString())).thenReturn(true);

        DeviceTokenStore store = new DeviceTokenStore(redis);
        store.remove("richmond");

        verify(redis).delete("sudokupro:push:token:richmond");
    }
}
