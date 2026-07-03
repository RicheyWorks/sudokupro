package com.xai.sudokupro.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import com.xai.sudokupro.model.api.BoardState;
import com.xai.sudokupro.service.GameService;
import com.xai.sudokupro.websocket.GameSessionRegistry;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class WebSocketController extends TextWebSocketHandler {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketController.class);

    /** Close code sent when a connection arrives without an authenticated principal. */
    static final CloseStatus UNAUTHENTICATED = CloseStatus.POLICY_VIOLATION.withReason("Authentication required");

    private final ConcurrentMap<WebSocketSession, String> playerMap = new ConcurrentHashMap<>();

    // Session bookkeeping lives in GameSessionRegistry (shared with MultiplayerBroadcaster —
    // AUDIT P2-2); game boards live in GameService (AUDIT P0-1). This class holds no
    // broadcast or board state of its own.
    private final GameService            gameService;
    private final ObjectMapper           objectMapper;
    private final MultiplayerBroadcaster broadcaster;
    private final GameSessionRegistry    sessionRegistry;

    @Autowired
    public WebSocketController(GameService gameService, ObjectMapper objectMapper,
                                MultiplayerBroadcaster broadcaster, GameSessionRegistry sessionRegistry) {
        this.gameService     = gameService;
        this.objectMapper    = objectMapper;
        this.broadcaster     = broadcaster;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        // Security fix (AUDIT P0-1): /ws/** is permitAll at the HTTP layer so the handshake
        // can complete, but gameplay requires an authenticated principal. Previously an
        // anonymous connection was given a synthetic "player_<sessionId>" identity and a
        // freshly created server-side game — unauthenticated players and unbounded state.
        if (session.getPrincipal() == null) {
            logger.warn("Rejecting unauthenticated WebSocket connection: session={}", session.getId());
            session.close(UNAUTHENTICATED);
            return;
        }
        String playerId = session.getPrincipal().getName();
        playerMap.put(session, playerId);
        broadcaster.registerClient();

        // Join the game named in the handshake (?gameId= query param, copied into the
        // session attributes by GameIdHandshakeInterceptor), or create a new one. Bug fix:
        // the previous code invented its own UUID as the map key while createNewGame
        // registered the board under a *different* internal gameId, so applyMove could
        // never find the game. Always use the board's real gameId.
        String requestedGameId = (String) session.getAttributes().get("gameId");
        SudokuBoard board;
        if (requestedGameId != null) {
            try {
                board = gameService.getGame(requestedGameId);
            } catch (IllegalArgumentException e) {
                logger.warn("Rejecting join of unknown game {}: session={}", requestedGameId, session.getId());
                session.close(CloseStatus.POLICY_VIOLATION.withReason("Unknown game"));
                playerMap.remove(session);
                broadcaster.unregisterClient();
                return;
            }
        } else {
            board = gameService.createNewGame(1, playerId, false, false);
        }
        String gameId = board.getGameId();
        session.getAttributes().put("gameId", gameId);

        // Register this session under its game so broadcasts stay scoped to the right players
        sessionRegistry.register(gameId, playerId, session);

        logger.info("Connected: session={} player={} game={}", session.getId(), playerId, gameId);
        broadcastToGame(gameId, session, buildEnvelope("join", playerId, Map.of("player", playerId, "gameId", gameId)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            String playerId = playerMap.get(session);
            if (playerId == null) {
                // Never registered (unauthenticated connections are closed above).
                session.close(UNAUTHENTICATED);
                return;
            }
            String gameId = (String) session.getAttributes().get("gameId");
            SudokuBoard board = gameService.getGame(gameId);

            Map<?,?> payload = objectMapper.readValue(message.getPayload(), Map.class);
            String type = payload.get("type") instanceof String s ? s : "unknown";

            switch (type) {
                case "move" -> {
                    EnhancedMove raw = objectMapper.convertValue(payload.get("payload"), EnhancedMove.class);
                    EnhancedMove move = new EnhancedMove(raw.row(), raw.col(),
                        raw.oldVal(), raw.newVal(), SudokuCell.MoveSource.PLAYER);
                    if (board.isValidMove(move.row(), move.col(), move.newVal())) {
                        gameService.applyMove(gameId, move, playerId);
                        broadcastToGame(gameId, session, buildEnvelope("move", playerId, move));
                    } else {
                        send(session, buildEnvelope("error", playerId,
                            Map.of("detail", "Invalid move")));
                    }
                }
                case "join" -> broadcastToGame(gameId, session, buildEnvelope("join", playerId, payload));
                case "chat" -> {
                    String text = payload.get("payload") instanceof String s2 ? s2 : "";
                    if (!text.isBlank()) {
                        broadcastToGame(gameId, session, buildEnvelope("chat", playerId, text));
                    }
                }
                case "undo" -> {
                    SudokuBoard updated = gameService.undo(gameId);
                    broadcastBoard(gameId, updated);
                }
                case "redo" -> {
                    SudokuBoard updated = gameService.redo(gameId);
                    broadcastBoard(gameId, updated);
                }
                // Full-state resync: sent only to the requesting session (e.g. after
                // reconnect, or after a REST-side mutation like a hint or auto-solve).
                case "sync" -> send(session, buildEnvelope("board", "server", BoardState.from(board)));
                default -> {
                    logger.warn("Unknown type from {}: {}", playerId, type);
                    send(session, buildEnvelope("error", playerId,
                        Map.of("detail", "Unknown type: " + type)));
                }
            }
        } catch (Exception e) {
            logger.error("Handle message failed for {}: {}", session.getId(), e.getMessage());
            try { send(session, buildEnvelope("error", session.getId(),
                Map.of("detail", e.getMessage()))); }
            catch (Exception ex) { logger.error("Error send failed: {}", ex.getMessage()); }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String playerId = playerMap.remove(session);
        if (playerId == null) {
            // Rejected before registration (e.g. unauthenticated) — nothing to clean up.
            return;
        }
        String gameId = (String) session.getAttributes().get("gameId");
        sessionRegistry.unregister(gameId, playerId, session);
        broadcaster.unregisterClient();
        logger.info("Disconnected: player={} game={} status={}", playerId, gameId, status);
        broadcastToGame(gameId, session, buildEnvelope("leave", playerId, Map.of("player", playerId)));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        logger.error("Transport error {}: {}", session.getId(), ex.getMessage());
        String gameId = (String) session.getAttributes().get("gameId");
        String playerId = playerMap.remove(session);
        if (playerId != null) {
            sessionRegistry.unregister(gameId, playerId, session);
            broadcaster.unregisterClient();
        }
    }

    // ---- helpers ----

    /** Sends to every session in the same game, excluding the sender. */
    private void broadcastToGame(String gameId, WebSocketSession sender, Map<String,Object> envelope) {
        sessionRegistry.broadcastToGame(gameId, sender, envelope);
    }

    /**
     * Sends the authoritative board state to every session in the game,
     * including the requester — undo/redo change state for all players.
     */
    private void broadcastBoard(String gameId, SudokuBoard board) {
        sessionRegistry.broadcastToGame(gameId, null,
            buildEnvelope("board", "server", BoardState.from(board)));
    }

    private void send(WebSocketSession session, Map<String,Object> envelope) throws IOException {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
    }

    private Map<String,Object> buildEnvelope(String type, String from, Object payload) {
        return Map.of("type", type, "from", from != null ? from : "unknown", "payload", payload);
    }
}
