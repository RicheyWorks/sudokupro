package com.xai.sudokupro.service.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * playerId → push device token registry, shared across replicas via Redis with
 * the same degrade-to-local-map-on-outage shape as {@code PlayerStateStore}.
 * One token per player (last registration wins) — enough for the desktop/mobile
 * single-device story; expand to a set if multi-device ever matters.
 */
@Component
public class DeviceTokenStore {

    private static final Logger logger = LoggerFactory.getLogger(DeviceTokenStore.class);
    private static final String TOKEN_KEY = "sudokupro:push:token:";
    /** Tokens go stale (app reinstalls, OS churn); expire idle ones after 60 days. */
    private static final Duration TOKEN_TTL = Duration.ofDays(60);

    private final StringRedisTemplate redis;
    private final AtomicBoolean degradedLogged = new AtomicBoolean(false);
    private final Map<String, String> localTokens = new ConcurrentHashMap<>();

    public DeviceTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void register(String playerId, String deviceToken) {
        try {
            redis.opsForValue().set(TOKEN_KEY + playerId, deviceToken, TOKEN_TTL);
        } catch (Exception e) {
            degraded(e);
            localTokens.put(playerId, deviceToken);
        }
    }

    public Optional<String> find(String playerId) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(TOKEN_KEY + playerId));
        } catch (Exception e) {
            degraded(e);
            return Optional.ofNullable(localTokens.get(playerId));
        }
    }

    public void remove(String playerId) {
        try {
            redis.delete(TOKEN_KEY + playerId);
        } catch (Exception e) {
            degraded(e);
        }
        localTokens.remove(playerId);
    }

    private void degraded(Exception e) {
        if (degradedLogged.compareAndSet(false, true)) {
            logger.warn("DeviceTokenStore: Redis unavailable — device tokens held in-memory only. "
                + "Fine for a single replica; NOT shared across replicas. Cause: {}", e.getMessage());
        }
    }
}
