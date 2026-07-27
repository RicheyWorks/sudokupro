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
 *
 * <p>A second index runs the other way, token → playerId, and it is not an
 * optimisation. A push token identifies a <em>device installation</em>, not an
 * account, so two accounts used on one phone — a shared family tablet, a handset
 * sold on, a tester with two logins, simply logging out and back in as someone
 * else — register the SAME token. The forward map alone kept both entries, so the
 * previous owner's notifications carried on resolving to that token and landing on
 * the new owner's lock screen: friend requests naming them, duel results, daily
 * streak reminders, achievement unlocks. Nothing errors, nothing logs, and the
 * person receiving them has no way to tell the app it is wrong. The reverse index
 * lets {@link #register} evict the stale owner, so a token belongs to exactly one
 * player at a time.
 */
@Component
public class DeviceTokenStore {

    private static final Logger logger = LoggerFactory.getLogger(DeviceTokenStore.class);
    private static final String TOKEN_KEY = "sudokupro:push:token:";
    /** Reverse index: which player a device token currently belongs to. */
    private static final String OWNER_KEY = "sudokupro:push:owner:";
    /** Tokens go stale (app reinstalls, OS churn); expire idle ones after 60 days. */
    private static final Duration TOKEN_TTL = Duration.ofDays(60);

    private final StringRedisTemplate redis;
    private final AtomicBoolean degradedLogged = new AtomicBoolean(false);
    private final Map<String, String> localTokens = new ConcurrentHashMap<>();
    private final Map<String, String> localOwners = new ConcurrentHashMap<>();

    public DeviceTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Binds {@code deviceToken} to {@code playerId}, taking it away from whichever player
     * held it before. Both directions are written, and the eviction happens first so there
     * is never a window in which two players resolve to the same device.
     */
    public void register(String playerId, String deviceToken) {
        if (playerId == null || deviceToken == null || deviceToken.isBlank()) return;
        try {
            String previousOwner = redis.opsForValue().get(OWNER_KEY + deviceToken);
            if (previousOwner != null && !previousOwner.equals(playerId)) {
                // The device changed hands. Drop the old account's binding before creating
                // the new one, or that account keeps pushing to this phone.
                redis.delete(TOKEN_KEY + previousOwner);
                logger.info("Push token reassigned from player {} to {} — same device", previousOwner, playerId);
            }
            // A player who re-registers with a NEW token leaves their old token owned by
            // them in the reverse index; clear it so the index does not accumulate
            // orphans pointing at a player whose forward entry has moved on.
            String supersededToken = redis.opsForValue().get(TOKEN_KEY + playerId);
            if (supersededToken != null && !supersededToken.equals(deviceToken)) {
                redis.delete(OWNER_KEY + supersededToken);
            }
            redis.opsForValue().set(TOKEN_KEY + playerId, deviceToken, TOKEN_TTL);
            redis.opsForValue().set(OWNER_KEY + deviceToken, playerId, TOKEN_TTL);
        } catch (Exception e) {
            degraded(e);
            registerLocally(playerId, deviceToken);
        }
    }

    private void registerLocally(String playerId, String deviceToken) {
        String previousOwner = localOwners.get(deviceToken);
        if (previousOwner != null && !previousOwner.equals(playerId)) {
            localTokens.remove(previousOwner);
        }
        String supersededToken = localTokens.get(playerId);
        if (supersededToken != null && !supersededToken.equals(deviceToken)) {
            localOwners.remove(supersededToken);
        }
        localTokens.put(playerId, deviceToken);
        localOwners.put(deviceToken, playerId);
    }

    public Optional<String> find(String playerId) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(TOKEN_KEY + playerId));
        } catch (Exception e) {
            degraded(e);
            return Optional.ofNullable(localTokens.get(playerId));
        }
    }

    /** Which player a device token currently belongs to, if any. */
    public Optional<String> ownerOf(String deviceToken) {
        if (deviceToken == null) return Optional.empty();
        try {
            return Optional.ofNullable(redis.opsForValue().get(OWNER_KEY + deviceToken));
        } catch (Exception e) {
            degraded(e);
            return Optional.ofNullable(localOwners.get(deviceToken));
        }
    }

    public void remove(String playerId) {
        try {
            String token = redis.opsForValue().get(TOKEN_KEY + playerId);
            redis.delete(TOKEN_KEY + playerId);
            // Only clear the reverse entry if it still points at THIS player — the device
            // may already have been claimed by someone else.
            if (token != null && playerId.equals(redis.opsForValue().get(OWNER_KEY + token))) {
                redis.delete(OWNER_KEY + token);
            }
        } catch (Exception e) {
            degraded(e);
        }
        String localToken = localTokens.remove(playerId);
        if (localToken != null) localOwners.remove(localToken, playerId);
    }

    private void degraded(Exception e) {
        if (degradedLogged.compareAndSet(false, true)) {
            logger.warn("DeviceTokenStore: Redis unavailable — device tokens held in-memory only. "
                + "Fine for a single replica; NOT shared across replicas. Cause: {}", e.getMessage());
        }
    }
}
