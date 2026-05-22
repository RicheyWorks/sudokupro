package com.xai.sudokupro.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import com.xai.sudokupro.service.GameService;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class WebSocketController extends TextWebSocketHandler {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketController.class);

    private final ConcurrentMap<WebSocketSession, String> playerMap  = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SudokuBoard>      gameBoards = new ConcurrentHashMap<>();

    private final GameService            gameService;
    private final ObjectMapper           objectMapper;
    private final MultiplayerBroadcaster broadcaster;

    @Autowired
    public WebSocketController(GameService gameService, ObjectMapper objectMapper,
                                MultiplayerBroadcaster broadcaster) {
        this.gameService   = gameService;
        this.objectMapper  = objectMapper;
        this.broadcaster   = broadcaster;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String playerId = extractPlayerId(session);
        playerMap.put(session, playerId);
        broadcaster.registerClient();

        String gameId = (String) session.getAttributes().getOrDefault("gameId",
            UUID.randomUUID().toString());
        session.getAttributes().put("gameId", gameId);
        gameBoards.computeIfAbsent(gameId, id -> gameService.createNewGame(1, playerId, false, false));

        logger.info("Connected: session={} player={} game={}", session.getId(), playerId, gameId);
        broadcast(session, buildEnvelope("join", playerId, Map.of("player", playerId, "gameId", gameId)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            String playerId = playerMap.getOrDefault(session, session.getId());
            String gameId   = (String) session.getAttributes().get("gameId");
            SudokuBoard board = gameBoards.get(gameId);
            if (board == null) { logger.error("No board for game {}", gameId); return; }

            Map<?,?> payload = objectMapper.readValue(message.getPayload(), Map.class);
            String type = payload.get("type") instanceof String s ? s : "unknown";

            switch (type) {
                case "move" -> {
                    EnhancedMove raw = objectMapper.convertValue(payload.get("payload"), EnhancedMove.class);
                    EnhancedMove move = new EnhancedMove(raw.row(), raw.col(),
                        raw.oldVal(), raw.newVal(), SudokuCell.MoveSource.PLAYER);
                    if (board.isValidMove(move.row(), move.col(), move.newVal())) {
                        gameService.applyMove(gameId, move, playerId);
                        broadcast(session, buildEnvelope("move", playerId, move));
                    } else {
                        send(session, buildEnvelope("error", playerId,
                            Map.of("detail", "Invalid move")));
                    }
                }
                case "join" -> broadcast(session, buildEnvelope("join", playerId, payload));
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
        String gameId   = (String) session.getAttributes().get("gameId");
        broadcaster.unregisterClient();
        logger.info("Disconnected: player={} status={}", playerId, status);
        broadcast(session, buildEnvelope("leave", playerId, Map.of("player", playerId)));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        logger.error("Transport error {}: {}", session.getId(), ex.getMessage());
        playerMap.remove(session);
        broadcaster.unregisterClient();
    }

    // ---- helpers ----

    private void broadcast(WebSocketSession sender, Map<String,Object> envelope) {
        String msg;
        try { msg = objectMapper.writeValueAsString(envelope); }
        catch (IOException e) { logger.error("Serialization failed: {}", e.getMessage()); return; }
        for (Map.Entry<WebSocketSession,String> e : playerMap.entrySet()) {
            if (e.getKey().isOpen() && !e.getKey().equals(sender)) {
                try { e.getKey().sendMessage(new TextMessage(msg)); }
                catch (IOException ex) { logger.error("Broadcast to {} failed: {}", e.getValue(), ex.getMessage()); }
            }
        }
    }

    private void send(WebSocketSession session, Map<String,Object> envelope) throws IOException {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
    }

    private Map<String,Object> buildEnvelope(String type, String from, Object payload) {
        return Map.of("type", type, "from", from != null ? from : "unknown", "payload", payload);
    }

    private String extractPlayerId(WebSocketSession session) {
        return session.getPrincipal() != null
            ? session.getPrincipal().getName()
            : "player_" + session.getId();
    }
}
