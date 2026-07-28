package com.xai.sudokupro.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.GameEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Component
public class MultiplayerBroadcaster implements com.xai.sudokupro.model.MoveBroadcaster {

    private static final Logger logger = LoggerFactory.getLogger(MultiplayerBroadcaster.class);
    private static final String SERVER = "server";

    // WebSocket convergence (AUDIT P2-2): messages go out over the raw /ws/game sessions
    // in GameSessionRegistry. The old SimpMessagingTemplate/STOMP path published to
    // /topic and /queue destinations no client ever subscribed to.
    private final GameSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;

    // Event subscriptions (topic → list of handlers)
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<String>>> eventSubscribers
            = new ConcurrentHashMap<>();

    // Game subscriptions (gameId → list of Runnable refresh callbacks)
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Runnable>> gameSubscribers
            = new ConcurrentHashMap<>();

    private final AtomicInteger activeClientCount = new AtomicInteger(0);
    private final AtomicInteger messageRateCounter = new AtomicInteger(0);
    private final AtomicLong healthPingsSent = new AtomicLong(0);

    @Autowired
    public MultiplayerBroadcaster(GameSessionRegistry sessionRegistry,
                                 ObjectMapper objectMapper) {
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
    }

    // ---- Core topic broadcasts ------------------------------------------

    @Override
    public void sendMove(String gameId, EnhancedMove move) {
        broadcast(gameId, "move", move);
        notifyGameSubscribers(gameId);
    }

    public void sendGameEvent(String gameId, GameEvent event) {
        broadcast(gameId, "event", event);
    }

    public void sendBatchMoves(String gameId, List<EnhancedMove> moves) {
        if (moves == null || moves.isEmpty()) return;

        broadcast(gameId, "batch_moves", moves);

        notifyGameSubscribers(gameId);
    }

    public void sendGameStatus(String gameId, String status) {
        broadcast(gameId, "status", Map.of("status", status));
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

    /**
     * Server-side keep-alive for every open socket.
     *
     * <p>This method existed and was never called by anything — a grep for its name found
     * exactly one hit, its own declaration. The client has always had a {@code case
     * 'health'} arm waiting for it, so both halves of a heartbeat were written and the wire
     * between them was missing. The consequence is the failure mode a front-end pass
     * measured directly: a black-holed connection stays {@code readyState === OPEN} and
     * fires no event, so a client can sit on a dead socket indefinitely believing it is
     * connected. The client now runs its own idle watchdog, but a watchdog that has to
     * probe is strictly worse than a server that speaks first — the probe costs a
     * round-trip per idle client and only fires after the idle threshold.
     *
     * <p>A periodic frame also keeps proxies and load balancers from reaping the connection
     * as idle, which is the ordinary reason long-lived sockets die in production.
     *
     * <p>Exceptions are swallowed on purpose. A heartbeat that throws would be a heartbeat
     * that stops: an exception escaping a {@code @Scheduled} method cancels no future runs
     * for {@code fixedRate}, but it does log a stack trace every interval forever, and one
     * unwritable session must not deny the ping to all the others.
     */
    @Scheduled(fixedRateString = "${sudokupro.ws.health-ping-ms:20000}")
    public void broadcastHealthPing() {
        try {
            sessionRegistry.broadcastToAll(
                Map.of("type", "health", "from", SERVER, "payload", "PING"));
            healthPingsSent.incrementAndGet();
        } catch (RuntimeException e) {
            logger.warn("Health ping broadcast failed (non-fatal): {}", e.getMessage());
        }
    }

    /** Count of successful heartbeat broadcasts — exposed so the wiring is observable. */
    public long getHealthPingsSent() {
        return healthPingsSent.get();
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

    private void broadcast(String gameId, String type, Object payload) {
        // Per-session delivery failures are handled (and logged) inside the registry;
        // the broker-level retry executor the STOMP path needed is gone with it.
        //
        // The analyticsEvent/analyticsData parameters are gone: they fed
        // AnalyticsService.logEvent, an EMPTY method, so every broadcast allocated a
        // payload map that was immediately discarded. Real analytics ingestion is
        // recordEvent(GameEvent), which GameService already calls on the events that
        // matter; nothing consumed a broadcast-level stream.
        sessionRegistry.broadcastToGame(gameId, null, Map.of("type", type, "from", SERVER, "payload", payload));
        messageRateCounter.incrementAndGet();
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
