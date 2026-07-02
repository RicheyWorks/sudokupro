package com.xai.sudokupro.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Two simulated pods share one real Redis (Testcontainers): a broadcast on pod A
 * must reach a session registered on pod B — the multi-replica claim of Phase 5.
 * Skipped automatically where Docker is unavailable; CI runs it.
 */
@Testcontainers(disabledWithoutDocker = true)
class CrossReplicaBroadcastTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private final List<AutoCloseable> resources = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable r : resources) r.close();
    }

    private record Pod(GameSessionRegistry registry, RedisBroadcastRelay relay) {}

    private Pod startPod() {
        LettuceConnectionFactory cf =
            new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        cf.afterPropertiesSet();
        resources.add(cf::destroy);

        StringRedisTemplate template = new StringRedisTemplate(cf);
        GameSessionRegistry registry = new GameSessionRegistry(new ObjectMapper());
        RedisBroadcastRelay relay = new RedisBroadcastRelay(registry, template, new ObjectMapper());
        relay.attach();

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(cf);
        container.addMessageListener(
            (message, pattern) -> relay.onMessage(new String(message.getBody(), StandardCharsets.UTF_8)),
            new ChannelTopic(RedisBroadcastRelay.CHANNEL));
        container.afterPropertiesSet();
        container.start();
        resources.add(container::stop);

        return new Pod(registry, relay);
    }

    @Test
    void broadcastOnPodAReachesSessionOnPodB() throws Exception {
        Pod a = startPod();
        Pod b = startPod();

        WebSocketSession sessionOnB = mock(WebSocketSession.class);
        when(sessionOnB.isOpen()).thenReturn(true);
        b.registry().register("game-1", "bob", sessionOnB);

        a.registry().broadcastToGame("game-1", null,
            Map.of("type", "move", "from", "alice", "payload", "r1c1=5"));

        verify(sessionOnB, timeout(TimeUnit.SECONDS.toMillis(10)))
            .sendMessage(any(TextMessage.class));
    }

    @Test
    void playerTargetedMessageCrossesPods() throws Exception {
        Pod a = startPod();
        Pod b = startPod();

        WebSocketSession sessionOnB = mock(WebSocketSession.class);
        when(sessionOnB.isOpen()).thenReturn(true);
        b.registry().register("game-2", "carol", sessionOnB);

        // carol is NOT on pod A — local delivery fails there, the relay carries it to B.
        a.registry().sendToPlayer("carol", Map.of("type", "hint", "from", "server", "payload", "x"));

        verify(sessionOnB, timeout(TimeUnit.SECONDS.toMillis(10)))
            .sendMessage(any(TextMessage.class));
        // No echo loops: give the channel a beat, then confirm exactly one delivery.
        Thread.sleep(500);
        Mockito.verify(sessionOnB, times(1)).sendMessage(any(TextMessage.class));
    }
}
