package com.xai.sudokupro.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Cross-replica WebSocket broadcast fan-out (Phase 5 / AUDIT P1-7).
 *
 * Each pod publishes outbound envelopes to one Redis pub/sub channel and receives
 * the other pods' messages there, delivering them to its OWN live sessions via
 * GameSessionRegistry.deliver*Local. The origin id filters out a pod's own
 * publications (it already delivered locally before publishing).
 *
 * Degrades gracefully: if Redis is unavailable, publishing fails quietly and the
 * pod behaves like a single-replica deployment — exactly the pre-Phase-5 behavior.
 */
@Component
public class RedisBroadcastRelay implements GameSessionRegistry.RemotePublisher {

    public static final String CHANNEL = "sudokupro:ws:broadcast";

    private static final Logger logger = LoggerFactory.getLogger(RedisBroadcastRelay.class);

    private final String originId = UUID.randomUUID().toString();
    private final GameSessionRegistry registry;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisBroadcastRelay(GameSessionRegistry registry, StringRedisTemplate redis,
                               ObjectMapper objectMapper) {
        this.registry = registry;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void attach() {
        registry.setRemotePublisher(this);
        logger.info("RedisBroadcastRelay attached (origin={})", originId);
    }

    public String getOriginId() {
        return originId;
    }

    // ---- publish side (called by GameSessionRegistry after local delivery) ----

    @Override
    public void publishToGame(String gameId, Map<String, Object> envelope) {
        publish("GAME", gameId, envelope);
    }

    @Override
    public void publishToAll(Map<String, Object> envelope) {
        publish("ALL", "", envelope);
    }

    @Override
    public void publishToPlayer(String playerId, Map<String, Object> envelope) {
        publish("PLAYER", playerId, envelope);
    }

    private void publish(String scope, String target, Map<String, Object> envelope) {
        try {
            String msg = objectMapper.writeValueAsString(Map.of(
                "origin", originId, "scope", scope, "target", target, "envelope", envelope));
            redis.convertAndSend(CHANNEL, msg);
        } catch (Exception e) {
            // Redis down → single-replica behavior; local delivery already happened.
            logger.debug("Cross-replica publish skipped ({}): {}", scope, e.getMessage());
        }
    }

    // ---- receive side (wired to a Redis message listener in RedisRelayConfig) ----

    /** Handles one raw pub/sub message from the channel. Package-visible for tests. */
    public void onMessage(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            if (originId.equals(node.path("origin").asText())) return; // own message — already delivered
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = objectMapper.convertValue(node.get("envelope"), Map.class);
            String target = node.path("target").asText();
            switch (node.path("scope").asText()) {
                case "GAME"   -> registry.deliverToGameLocal(target, null, envelope);
                case "ALL"    -> registry.deliverToAllLocal(envelope);
                case "PLAYER" -> registry.deliverToPlayerLocal(target, envelope);
                default       -> logger.warn("Unknown broadcast scope in relay message");
            }
        } catch (Exception e) {
            logger.error("Relay message handling failed: {}", e.getMessage());
        }
    }
}
