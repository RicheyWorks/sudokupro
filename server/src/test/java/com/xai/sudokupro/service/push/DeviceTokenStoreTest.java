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

    /**
     * A stateful stand-in for the two Redis string keys this store uses, so the
     * token-ownership tests exercise real read-after-write behaviour rather than a
     * script of stubs.
     */
    @SuppressWarnings("unchecked")
    private static DeviceTokenStore storeBackedByFakeRedis(java.util.Map<String, String> kv) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        lenient().when(redis.opsForValue()).thenReturn(ops);
        lenient().when(ops.get(anyString())).thenAnswer(inv -> kv.get(inv.<String>getArgument(0)));
        lenient().doAnswer(inv -> { kv.put(inv.getArgument(0), inv.getArgument(1)); return null; })
            .when(ops).set(anyString(), anyString(), any(Duration.class));
        lenient().when(redis.delete(anyString()))
            .thenAnswer(inv -> kv.remove(inv.<String>getArgument(0)) != null);
        return new DeviceTokenStore(redis);
    }

    /**
     * One device token belongs to exactly one player.
     *
     * <p>A push token identifies a device installation, not an account, so two accounts used
     * on one phone register the SAME token: a shared tablet, a handset sold on, a tester with
     * two logins, or simply logging out and back in as someone else. Only the forward map
     * existed, so both entries survived and the previous owner's notifications carried on
     * resolving to that token — friend requests naming them, duel results, streak reminders,
     * achievement unlocks, all landing on the new owner's lock screen. Nothing errors,
     * nothing logs, and the person receiving them cannot tell the app it is wrong.
     */
    @Test
    void registeringATokenTakesItAwayFromItsPreviousOwner() {
        DeviceTokenStore store = storeBackedByFakeRedis(new java.util.HashMap<>());
        store.register("richmond", "shared-device-token");
        assertEquals(Optional.of("shared-device-token"), store.find("richmond"));

        // Same phone, different account.
        store.register("ada", "shared-device-token");

        assertEquals(Optional.of("shared-device-token"), store.find("ada"));
        assertEquals(Optional.empty(), store.find("richmond"),
            "the previous owner must no longer resolve to a device that is not theirs");
        assertEquals(Optional.of("ada"), store.ownerOf("shared-device-token"));
    }

    /** The same rule must hold on the in-memory fallback, not just through Redis. */
    @Test
    void tokenOwnershipIsExclusiveOnTheDegradedPathToo() {
        StringRedisTemplate down = mock(StringRedisTemplate.class,
            inv -> { throw new RedisConnectionFailureException("down (test)"); });
        DeviceTokenStore store = new DeviceTokenStore(down);

        store.register("richmond", "shared-device-token");
        store.register("ada", "shared-device-token");

        assertEquals(Optional.of("shared-device-token"), store.find("ada"));
        assertEquals(Optional.empty(), store.find("richmond"));
    }

    /**
     * A player who re-registers with a new token must not leave the old token pointing at
     * them, or the reverse index accumulates owners whose forward entry has moved on — and
     * whoever picks up that recycled token later gets their registration silently stolen.
     */
    @Test
    void rotatingAPlayersOwnTokenClearsTheSupersededReverseEntry() {
        DeviceTokenStore store = storeBackedByFakeRedis(new java.util.HashMap<>());
        store.register("richmond", "old-token");
        store.register("richmond", "new-token");

        assertEquals(Optional.of("new-token"), store.find("richmond"));
        assertEquals(Optional.empty(), store.ownerOf("old-token"),
            "the superseded token must no longer claim an owner");
        assertEquals(Optional.of("richmond"), store.ownerOf("new-token"));
    }

    /** Removing a registration clears both directions. */
    @Test
    void removingARegistrationClearsTheReverseIndex() {
        DeviceTokenStore store = storeBackedByFakeRedis(new java.util.HashMap<>());
        store.register("richmond", "tok-1");

        store.remove("richmond");

        assertEquals(Optional.empty(), store.find("richmond"));
        assertEquals(Optional.empty(), store.ownerOf("tok-1"));
    }

    /**
     * Removing a player's stale registration must NOT clear a reverse entry that now points
     * at someone else — the device has already changed hands, and the new owner's binding is
     * the correct one.
     */
    @Test
    void removingAStaleRegistrationDoesNotStealTheDeviceBack() {
        DeviceTokenStore store = storeBackedByFakeRedis(new java.util.HashMap<>());
        store.register("richmond", "shared-device-token");
        store.register("ada", "shared-device-token");

        store.remove("richmond");

        assertEquals(Optional.of("ada"), store.ownerOf("shared-device-token"),
            "the current owner's binding must survive the previous owner's cleanup");
        assertEquals(Optional.of("shared-device-token"), store.find("ada"));
    }

    /** A blank or null token is not a registration. */
    @Test
    void emptyRegistrationsAreIgnored() {
        DeviceTokenStore store = storeBackedByFakeRedis(new java.util.HashMap<>());
        store.register("richmond", null);
        store.register("richmond", "  ");
        assertEquals(Optional.empty(), store.find("richmond"));
    }
}
