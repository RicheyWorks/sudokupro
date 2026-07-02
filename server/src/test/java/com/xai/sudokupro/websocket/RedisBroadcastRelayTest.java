package com.xai.sudokupro.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Routing and loop-prevention tests for the cross-replica relay (Phase 5). */
@ExtendWith(MockitoExtension.class)
class RedisBroadcastRelayTest {

    @Mock private GameSessionRegistry registry;
    @Mock private StringRedisTemplate redis;

    private RedisBroadcastRelay relay;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        relay = new RedisBroadcastRelay(registry, redis, mapper);
    }

    @Test
    void publishSendsToTheSharedChannel() {
        relay.publishToGame("game-1", Map.of("type", "move", "from", "server", "payload", "x"));
        verify(redis).convertAndSend(eq(RedisBroadcastRelay.CHANNEL), anyString());
    }

    @Test
    void ownMessagesAreIgnored() throws Exception {
        String own = mapper.writeValueAsString(Map.of(
            "origin", relay.getOriginId(), "scope", "GAME", "target", "game-1",
            "envelope", Map.of("type", "move")));

        relay.onMessage(own);

        verifyNoInteractions(registry);
    }

    @Test
    void remoteGameMessageIsDeliveredLocally() throws Exception {
        String remote = mapper.writeValueAsString(Map.of(
            "origin", "another-pod", "scope", "GAME", "target", "game-1",
            "envelope", Map.of("type", "move", "from", "alice", "payload", "x")));

        relay.onMessage(remote);

        verify(registry).deliverToGameLocal(eq("game-1"), eq(null), anyMap());
        verify(registry, never()).deliverToAllLocal(anyMap());
    }

    @Test
    void remotePlayerAndAllScopesRoute() throws Exception {
        relay.onMessage(mapper.writeValueAsString(Map.of(
            "origin", "another-pod", "scope", "PLAYER", "target", "bob",
            "envelope", Map.of("type", "hint"))));
        verify(registry).deliverToPlayerLocal(eq("bob"), anyMap());

        relay.onMessage(mapper.writeValueAsString(Map.of(
            "origin", "another-pod", "scope", "ALL", "target", "",
            "envelope", Map.of("type", "health"))));
        verify(registry).deliverToAllLocal(anyMap());
    }

    @Test
    void redisFailureDegradesQuietly() {
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"))
            .when(redis).convertAndSend(anyString(), any());

        relay.publishToAll(Map.of("type", "health"));  // must not throw
    }
}
