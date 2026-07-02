package com.xai.sudokupro.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Single registry of live raw-WebSocket sessions, shared by the connection handler
 * (WebSocketController) and server-side broadcasters (MultiplayerBroadcaster).
 *
 * WebSocket convergence (AUDIT P2-2): previously two stacks coexisted — this raw
 * /ws/game path AND a STOMP broker that MultiplayerBroadcaster published to via
 * SimpMessagingTemplate. No client ever subscribed to the STOMP endpoint (the JavaFX
 * client speaks raw WebSocket; there is no web frontend), so every server-initiated
 * broadcast went nowhere. All traffic now flows through this registry.
 *
 * Envelope format (unchanged from the raw protocol): {"type", "from", "payload"}.
 */
@Component
public class GameSessionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(GameSessionRegistry.class);

    private final ConcurrentMap<String, Set<WebSocketSession>> gameSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WebSocketSession> playerSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public GameSessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(String gameId, String playerId, WebSocketSession session) {
        gameSessions.computeIfAbsent(gameId, id -> ConcurrentHashMap.newKeySet()).add(session);
        playerSessions.put(playerId, session);
    }

    public void unregister(String gameId, String playerId, WebSocketSession session) {
        if (gameId != null) {
            Set<WebSocketSession> sessions = gameSessions.get(gameId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) gameSessions.remove(gameId);
            }
        }
        if (playerId != null) playerSessions.remove(playerId, session);
    }

    /** Sends to every open session in the game, excluding {@code excludeSender} (may be null). */
    public void broadcastToGame(String gameId, WebSocketSession excludeSender, Map<String, Object> envelope) {
        if (gameId == null) return;
        Set<WebSocketSession> sessions = gameSessions.get(gameId);
        if (sessions == null || sessions.isEmpty()) return;
        String msg = serialize(envelope);
        if (msg == null) return;
        for (WebSocketSession s : sessions) {
            if (s.isOpen() && !s.equals(excludeSender)) sendRaw(s, msg);
        }
    }

    /** Sends to every open session across all games (global announcements, health pings). */
    public void broadcastToAll(Map<String, Object> envelope) {
        String msg = serialize(envelope);
        if (msg == null) return;
        for (Set<WebSocketSession> sessions : gameSessions.values()) {
            for (WebSocketSession s : sessions) {
                if (s.isOpen()) sendRaw(s, msg);
            }
        }
    }

    /** Sends to a single player's session, if connected. Returns true when delivered. */
    public boolean sendToPlayer(String playerId, Map<String, Object> envelope) {
        WebSocketSession s = playerSessions.get(playerId);
        if (s == null || !s.isOpen()) return false;
        String msg = serialize(envelope);
        return msg != null && sendRaw(s, msg);
    }

    public int openSessionCount() {
        return gameSessions.values().stream().mapToInt(Set::size).sum();
    }

    private String serialize(Map<String, Object> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (IOException e) {
            logger.error("Envelope serialization failed: {}", e.getMessage());
            return null;
        }
    }

    private boolean sendRaw(WebSocketSession s, String msg) {
        try {
            s.sendMessage(new TextMessage(msg));
            return true;
        } catch (IOException e) {
            logger.error("Send to session {} failed: {}", s.getId(), e.getMessage());
            return false;
        }
    }
}
