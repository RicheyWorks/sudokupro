package com.xai.sudokupro.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.GameEvent;
import com.xai.sudokupro.service.AnalyticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final String SERVER = "server";

    // WebSocket convergence (AUDIT P2-2): messages go out over the raw /ws/game sessions
    // in GameSessionRegistry. The old SimpMessagingTemplate/STOMP path published to
    // /topic and /queue destinations no client ever subscribed to.
    private final GameSessionRegistry sessionRegistry;
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
    public MultiplayerBroadcaster(GameSessionRegistry sessionRegistry,
                                 ObjectMapper objectMapper,
                                 AnalyticsService analyticsService) {
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
        this.analyticsService = analyticsService;
    }

    // ---- Core topic broadcasts ------------------------------------------

    public void sendMove(String gameId, EnhancedMove move) {
        broadcast(gameId, "move", move, "move_broadcast",
                Map.of("gameId", gameId, "move", move.toString()));
        notifyGameSubscribers(gameId);
    }

    public void sendGameEvent(String gameId, GameEvent event) {
        broadcast(gameId, "event", event, "event_broadcast",
                Map.of("gameId", gameId, "eventType", event.getType().toString()));
    }

    public void sendBatchMoves(String gameId, List<EnhancedMove> moves) {
        if (moves == null || moves.isEmpty()) return;

        broadcast(gameId, "batch_moves", moves, "batch_move_broadcast",
                Map.of("gameId", gameId, "moveCount", moves.size()));

        notifyGameSubscribers(gameId);
    }

    public void sendGameStatus(String gameId, String status) {
        broadcast(gameId, "status",
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
        boolean delivered = sessionRegistry.sendToPlayer(playerId,
                Map.of("type", type, "from", SERVER, "payload", message));
        if (delivered) {
            messageRateCounter.incrementAndGet();
            logger.debug("Sent [{}] to {}: {}", type, playerId, message);
        } else {
            logger.debug("Player {} not connected; [{}] not delivered", playerId, type);
        }
    }

    // ---- Broadcast events (all players) ---------------------------------

    public void broadcastEvent(String type, String message, String gameId) {
        Map<String, Object> envelope = Map.of("type", type, "from", SERVER, "payload", message);
        if (gameId != null) {
            sessionRegistry.broadcastToGame(gameId, null, envelope);
        } else {
            sessionRegistry.broadcastToAll(envelope);
        }
        messageRateCounter.incrementAndGet();
        notifyEventSubscribers(type, message);
        logger.debug("Broadcast [{}]: {}", type, message);
    }

    public void broadcastGameStart(String gameId, String playerId) {
        broadcastEvent("gameStart", "Game " + gameId + " started by " + playerId, gameId);
    }

    public void broadcastGameEnd(String gameId, String playerId) {
        broadcastEvent("gameEnd", "Game " + gameId + " ended for " + playerId, gameId);
    }

    // ---- Health / monitoring --------------------------------------------

    public void broadcastHealthPing() {
        sessionRegistry.broadcastToAll(Map.of("type", "health", "from", SERVER, "payload", "PING"));
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

    private void broadcast(String gameId, String type, Object payload,
                           String analyticsEvent, Map<String, Object> analyticsData) {
        // Per-session delivery failures are handled (and logged) inside the registry;
        // the broker-level retry executor the STOMP path needed is gone with it.
        sessionRegistry.broadcastToGame(gameId, null, Map.of("type", type, "from", SERVER, "payload", payload));
        messageRateCounter.incrementAndGet();
        analyticsService.logEvent(analyticsEvent, analyticsData);
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
