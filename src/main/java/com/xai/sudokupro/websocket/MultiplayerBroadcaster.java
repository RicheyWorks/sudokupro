package com.xai.sudokupro.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.GameEvent;
import com.xai.sudokupro.service.AnalyticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Component
public class MultiplayerBroadcaster {

    private static final Logger logger = LoggerFactory.getLogger(MultiplayerBroadcaster.class);
    private static final int MAX_RETRY = 3;

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final AnalyticsService analyticsService;

    // Event subscriptions (topic → list of handlers)
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<String>>> eventSubscribers
            = new ConcurrentHashMap<>();

    // Game subscriptions (gameId → list of Runnable refresh callbacks)
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Runnable>> gameSubscribers
            = new ConcurrentHashMap<>();

    private final AtomicInteger activeClientCount = new AtomicInteger(0);
    private final AtomicInteger messageRateCounter = new AtomicInteger(0);

    @Autowired
    public MultiplayerBroadcaster(SimpMessagingTemplate messagingTemplate,
                                 ObjectMapper objectMapper,
                                 AnalyticsService analyticsService) {
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
        this.analyticsService = analyticsService;
    }

    // ---- Core topic broadcasts ------------------------------------------

    public void sendMove(String gameId, EnhancedMove move) {
        broadcast(gameId, "/topic/board/", move, "move_broadcast",
                Map.of("gameId", gameId, "move", move.toString()));
        notifyGameSubscribers(gameId);
    }

    public void sendGameEvent(String gameId, GameEvent event) {
        broadcast(gameId, "/topic/events/", event, "event_broadcast",
                Map.of("gameId", gameId, "eventType", event.getType().toString()));
    }

    public void sendBatchMoves(String gameId, List<EnhancedMove> moves) {
        if (moves == null || moves.isEmpty()) return;

        broadcast(gameId, "/topic/board/", moves, "batch_move_broadcast",
                Map.of("gameId", gameId, "moveCount", moves.size()));

        notifyGameSubscribers(gameId);
    }

    public void sendGameStatus(String gameId, String status) {
        broadcast(gameId, "/topic/status/",
                Map.of("status", status), "status_update",
                Map.of("gameId", gameId, "status", status));
    }

    public void sendHint(String playerId, String hint) {
        sendToPlayer(playerId, "hint", hint);
    }

    public void sendDebugInfo(String playerId, String info) {
        sendToPlayer(playerId, "debug", info);
    }

    // ---- Player-targeted messages ---------------------------------------

    public void sendToPlayer(String playerId, String type, String message) {
        String destination = "/queue/player/" + playerId + "/" + type;
        try {
            messagingTemplate.convertAndSend(destination, message);
            messageRateCounter.incrementAndGet();
            logger.debug("Sent [{}] to {}: {}", type, playerId, message);
        } catch (Exception e) {
            logger.error("Failed to send [{}] to {}: {}", type, playerId, e.getMessage());
        }
    }

    // ---- Broadcast events (all players) ---------------------------------

    public void broadcastEvent(String type, String message, String gameId) {
        String destination = gameId != null ? "/topic/events/" + gameId : "/topic/events/global";
        try {
            messagingTemplate.convertAndSend(destination, Map.of("type", type, "message", message));
            messageRateCounter.incrementAndGet();
            notifyEventSubscribers(type, message);
            logger.debug("Broadcast [{}]: {}", type, message);
        } catch (Exception e) {
            logger.error("Broadcast [{}] failed: {}", type, e.getMessage());
        }
    }

    public void broadcastGameStart(String gameId, String playerId) {
        broadcastEvent("gameStart", "Game " + gameId + " started by " + playerId, gameId);
    }

    public void broadcastGameEnd(String gameId, String playerId) {
        broadcastEvent("gameEnd", "Game " + gameId + " ended for " + playerId, gameId);
    }

    // ---- Health / monitoring --------------------------------------------

    public void broadcastHealthPing() {
        try {
            messagingTemplate.convertAndSend("/topic/health", "PING");
        } catch (Exception e) {
            logger.warn("Health ping failed: {}", e.getMessage());
        }
    }

    public int getActiveClientCount() {
        return activeClientCount.get();
    }

    public int getMessageRatePerSecond() {
        return messageRateCounter.getAndSet(0);
    }

    public void registerClient() {
        activeClientCount.incrementAndGet();
    }

    public void unregisterClient() {
        activeClientCount.decrementAndGet();
    }

    // ---- Subscriptions (for BoardView / MainStage) ----------------------

    public void subscribeToGame(String gameId, Runnable onUpdate) {
        gameSubscribers.computeIfAbsent(gameId, k -> new CopyOnWriteArrayList<>()).add(onUpdate);
    }

    public void subscribeToEvent(String eventType, Consumer<String> handler) {
        eventSubscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    // ---- Private --------------------------------------------------------

    private void broadcast(String gameId, String prefix, Object payload,
                           String analyticsEvent, Map<String, Object> analyticsData) {

        String dest = prefix + gameId;
        String msg;

        try {
            msg = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            logger.error("Serialization failed for {}: {}", dest, e.getMessage());
            return;
        }

        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                messagingTemplate.convertAndSend(dest, msg);
                messageRateCounter.incrementAndGet();
                analyticsService.logEvent(analyticsEvent, analyticsData);
                return;
            } catch (Exception e) {
                if (attempt == MAX_RETRY) {
                    logger.error("All retries failed for {}: {}", dest, e.getMessage());
                } else {
                    try {
                        Thread.sleep(1000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private void notifyGameSubscribers(String gameId) {
        CopyOnWriteArrayList<Runnable> subs = gameSubscribers.get(gameId);
        if (subs != null) subs.forEach(Runnable::run);
    }

    private void notifyEventSubscribers(String type, String message) {
        CopyOnWriteArrayList<Consumer<String>> subs = eventSubscribers.get(type);
        if (subs != null) subs.forEach(h -> h.accept(message));
    }
}
