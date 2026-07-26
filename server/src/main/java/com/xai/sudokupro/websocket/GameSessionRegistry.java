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
 *
 * Multi-replica (Phase 5): when a RemotePublisher is attached (RedisBroadcastRelay),
 * every broadcast is also published to Redis pub/sub so replicas deliver it to THEIR
 * sessions of the same game. The deliver*Local methods are the receive path and never
 * republish (no loops). Without a publisher the registry is single-pod, as before.
 */
@Component
public class GameSessionRegistry {

    /** Cross-replica publish hook; see RedisBroadcastRelay. */
    public interface RemotePublisher {
        void publishToGame(String gameId, Map<String, Object> envelope);
        void publishToAll(Map<String, Object> envelope);
        void publishToPlayer(String playerId, Map<String, Object> envelope);
    }

    private volatile RemotePublisher remotePublisher;

    public void setRemotePublisher(RemotePublisher publisher) {
        this.remotePublisher = publisher;
    }

    private static final Logger logger = LoggerFactory.getLogger(GameSessionRegistry.class);

    private final ConcurrentMap<String, Set<WebSocketSession>> gameSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WebSocketSession> playerSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public GameSessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Adds {@code session} to the game's set and records it as the player's current session.
     *
     * <p>The add is retried against {@code gameSessions} because {@link #unregister} may
     * concurrently drop the whole entry — see the note there.
     */
    public void register(String gameId, String playerId, WebSocketSession session) {
        while (true) {
            Set<WebSocketSession> sessions =
                gameSessions.computeIfAbsent(gameId, id -> ConcurrentHashMap.newKeySet());
            sessions.add(session);
            // If unregister removed this exact set between the computeIfAbsent and the add,
            // our session is now in an orphan set nothing can reach. Re-check identity and
            // retry against whatever is mapped now.
            if (gameSessions.get(gameId) == sessions) break;
        }
        playerSessions.put(playerId, session);
    }

    /**
     * Removes {@code session} from the game, dropping the game entry once it is empty.
     *
     * <p>The emptiness check and the removal must be ONE atomic operation. Previously this
     * did {@code if (sessions.isEmpty()) gameSessions.remove(gameId)}, so a {@code register}
     * that landed on the same Set in between left the new session inside a Set no longer
     * referenced by {@code gameSessions}: the socket was open and the player believed they
     * had joined, but {@code deliverToGameLocal} found no entry, so they received no moves,
     * no chat, no board syncs and no leave events for the rest of the connection — and never
     * recovered, because a later join creates a different Set. Reproduced under contention:
     * 5 of 25,621 broadcasts missed a live, registered session. The trigger in production is
     * ordinary — one player leaving a game at the same moment another joins it.
     *
     * <p>{@code remove(key, value)} only removes when the mapped Set is still the one we
     * emptied, and {@code computeIfPresent} makes the emptiness test and the removal
     * atomic with respect to other map operations.
     */
    public void unregister(String gameId, String playerId, WebSocketSession session) {
        if (gameId != null) {
            gameSessions.computeIfPresent(gameId, (id, sessions) -> {
                sessions.remove(session);
                return sessions.isEmpty() ? null : sessions;   // null removes the entry
            });
        }
        if (playerId != null) playerSessions.remove(playerId, session);
    }

    /** Sends to every open session in the game, excluding {@code excludeSender} (may be null). */
    public void broadcastToGame(String gameId, WebSocketSession excludeSender, Map<String, Object> envelope) {
        deliverToGameLocal(gameId, excludeSender, envelope);
        RemotePublisher rp = remotePublisher;
        if (rp != null) rp.publishToGame(gameId, envelope);
    }

    /** Sends to every open session across all games (global announcements, health pings). */
    public void broadcastToAll(Map<String, Object> envelope) {
        deliverToAllLocal(envelope);
        RemotePublisher rp = remotePublisher;
        if (rp != null) rp.publishToAll(envelope);
    }

    /**
     * Sends to a single player's session. Returns true when delivered locally; when the
     * player is on another replica the message is relayed and delivery happens there.
     */
    /** Presence: true while the player holds an open gameplay WebSocket on this pod. */
    public boolean isOnline(String playerId) {
        WebSocketSession s = playerSessions.get(playerId);
        return s != null && s.isOpen();
    }

    public boolean sendToPlayer(String playerId, Map<String, Object> envelope) {
        if (deliverToPlayerLocal(playerId, envelope)) return true;
        RemotePublisher rp = remotePublisher;
        if (rp != null) rp.publishToPlayer(playerId, envelope);
        return false;
    }

    // ---- local delivery (also the receive path for relayed messages; never republishes) ----

    public void deliverToGameLocal(String gameId, WebSocketSession excludeSender, Map<String, Object> envelope) {
        if (gameId == null) return;
        Set<WebSocketSession> sessions = gameSessions.get(gameId);
        if (sessions == null || sessions.isEmpty()) return;
        String msg = serialize(envelope);
        if (msg == null) return;
        for (WebSocketSession s : sessions) {
            if (s.isOpen() && !s.equals(excludeSender)) sendRaw(s, msg);
        }
    }

    public void deliverToAllLocal(Map<String, Object> envelope) {
        String msg = serialize(envelope);
        if (msg == null) return;
        for (Set<WebSocketSession> sessions : gameSessions.values()) {
            for (WebSocketSession s : sessions) {
                if (s.isOpen()) sendRaw(s, msg);
            }
        }
    }

    public boolean deliverToPlayerLocal(String playerId, Map<String, Object> envelope) {
        WebSocketSession s = playerSessions.get(playerId);
        if (s == null || !s.isOpen()) return false;
        String msg = serialize(envelope);
        return msg != null && sendRaw(s, msg);
    }

    /** Number of games currently holding at least one session (test/observability hook). */
    public int trackedGameCount() {
        return gameSessions.size();
    }

    /**
     * True when {@code session} is reachable through the game's registered set — i.e. a
     * broadcast to {@code gameId} would actually deliver to it. Exposes the exact property
     * the orphan-Set race used to violate.
     */
    public boolean gameHasSession(String gameId, WebSocketSession session) {
        Set<WebSocketSession> sessions = gameSessions.get(gameId);
        return sessions != null && sessions.contains(session);
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
