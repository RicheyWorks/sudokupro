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

        // Raise the per-session frame limits off the container's 8 KB default. A board
        // envelope carries all 81 cells and can exceed 8 KB on a full grid with pencil
        // marks, and an oversized frame is killed by the container with close code 1009
        // ("too big for the output buffer") BEFORE the handler ever runs — so
        // application-level truncation cannot rescue the connection. Confirmed by fuzzing:
        // a 16 KB frame silently closed the socket and left it unusable. Setting the limit
        // on the session is what actually takes effect for Spring's raw WebSocket stack;
        // the container-level factory bean does not reach this path.
        session.setTextMessageSizeLimit(MAX_TEXT_FRAME_BYTES);
        session.setBinaryMessageSizeLimit(MAX_BINARY_FRAME_BYTES);

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
            // Spectating a free-play game is a feature; spectating a COMPETITIVE game is a
            // cheat. Daily, weekly and duel boards are per-player copies of ONE shared
            // grid, so watching someone else's copy hands you the answers to the identical
            // puzzle you are racing them on — and `sync` returns all 81 cell values.
            // Verified live: an attacker connected to daily-<date>:<victim> and read the
            // victim's 53/81 in-progress board. During a ranked duel this streams the
            // opponent's board move by move.
            if (isSharedPuzzle(gameId(board)) && !playerId.equals(board.getPlayerId())) {
                logger.warn("Rejecting spectate of competitive game {} by {}", requestedGameId, playerId);
                session.close(CloseStatus.POLICY_VIOLATION.withReason("Competitive games cannot be spectated"));
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
            // Throttle BEFORE touching the game. getGame takes the cross-replica game lock
            // (a Redis SET NX + Lua DEL round-trip) on every single frame, so an unthrottled
            // client — even one sending an unknown message type — could hold that lock in a
            // loop and starve the board's real owner, who then fails every move/hint/undo
            // with "busy on another server instance" after a 3s stall. There was no rate
            // limit anywhere on the WebSocket path.
            if (!allowMessage(session)) {
                logger.warn("Rate-limiting WebSocket session={} player={}", session.getId(), playerId);
                send(session, buildEnvelope("error", playerId,
                    Map.of("detail", "Too many messages — slow down")));
                return;
            }

            Map<?,?> payload = objectMapper.readValue(message.getPayload(), Map.class);
            String type = payload.get("type") instanceof String s ? s : "unknown";

            String gameId = (String) session.getAttributes().get("gameId");
            SudokuBoard board = gameService.getGame(gameId);

            switch (type) {
                case "move" -> {
                    if (rejectSpectator(session, board, playerId)) return;
                    // Read the move fields directly rather than deserializing straight into
                    // EnhancedMove. Two reasons:
                    //  1) EnhancedMove's canonical constructor requires a non-null MoveSource,
                    //     so a payload of {row,col,oldVal,newVal} — exactly what the browser
                    //     client sends — threw "MoveSource cannot be null" and every single
                    //     move from the web client failed (verified live: 28/28 rejected).
                    //  2) The source must NOT be client-supplied anyway: a client could label
                    //     its move HINT or AUTOSOLVE, which is precisely what the clean-solve
                    //     and auto-solve reward guards key on. The server always stamps PLAYER.
                    EnhancedMove move = readClientMove(payload.get("payload"));
                    // Only announce a move the authoritative board actually accepted, and
                    // announce it exactly once. This used to broadcast unconditionally, so
                    // a move GameService silently dropped (a FREEZE-locked player) was still
                    // sent to every peer. It also broadcast a SECOND time on top of
                    // SudokuBoard.applyMove's own broadcast through MultiplayerBroadcaster,
                    // so every move fanned out twice and cost two Redis publishes. The
                    // service-level broadcast is the one to keep: it reaches every replica
                    // and covers REST-initiated moves too, and the client already ignores
                    // the echo of its own move.
                    if (!board.isValidMove(move.row(), move.col(), move.newVal())
                            || !gameService.applyMove(gameId, move, playerId)) {
                        send(session, buildEnvelope("error", playerId,
                            Map.of("detail", "Invalid move")));
                    }
                }
                // Relay only the identity, never the caller's raw envelope: `payload` here
                // is the whole inbound map, so echoing it forwarded arbitrary
                // attacker-controlled JSON of any shape to every peer on every replica.
                case "join" -> broadcastToGame(gameId, session,
                    buildEnvelope("join", playerId, Map.of("player", playerId, "gameId", gameId)));
                case "chat" -> {
                    String text = payload.get("payload") instanceof String s2 ? s2 : "";
                    if (!text.isBlank()) {
                        // Cap the relayed text. Each inbound frame fans out to every session
                        // in the game on every replica plus a Redis publish, so an unbounded
                        // string is a cheap bandwidth amplifier from one authenticated account.
                        if (text.length() > MAX_CHAT_LENGTH) {
                            text = text.substring(0, MAX_CHAT_LENGTH);
                        }
                        broadcastToGame(gameId, session, buildEnvelope("chat", playerId, text));
                    }
                }
                case "undo" -> {
                    if (rejectSpectator(session, board, playerId)) return;
                    SudokuBoard updated = gameService.undo(gameId);
                    broadcastBoard(gameId, updated);
                }
                case "redo" -> {
                    if (rejectSpectator(session, board, playerId)) return;
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
        rateBuckets.remove(session);
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
        rateBuckets.remove(session);
        String gameId = (String) session.getAttributes().get("gameId");
        String playerId = playerMap.remove(session);
        if (playerId != null) {
            sessionRegistry.unregister(gameId, playerId, session);
            broadcaster.unregisterClient();
            // Peers must be told, exactly as on a clean close. Previously only
            // afterConnectionClosed broadcast "leave", and it bails early because this
            // handler already removed the playerMap entry — so any TCP reset, proxy
            // timeout, pod eviction or client crash left the departed player rendered as
            // still present on every other client, permanently.
            broadcastToGame(gameId, session, buildEnvelope("leave", playerId, Map.of("player", playerId)));
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

    /**
     * Spectator mode: anyone may join a game's channel and watch broadcasts,
     * but only the board's owner may mutate it (move/undo/redo). Returns true
     * when the sender was rejected.
     */
    private boolean rejectSpectator(WebSocketSession session, SudokuBoard board, String playerId)
            throws IOException {
        if (playerId.equals(board.getPlayerId())) return false;
        send(session, buildEnvelope("error", playerId,
            Map.of("detail", "Spectators cannot modify this board — it belongs to " + board.getPlayerId())));
        return true;
    }

    private Map<String,Object> buildEnvelope(String type, String from, Object payload) {
        return Map.of("type", type, "from", from != null ? from : "unknown", "payload", payload);
    }

    private static String gameId(SudokuBoard board) {
        return board.getGameId() == null ? "" : board.getGameId();
    }

    /**
     * True for boards that are per-player copies of ONE shared grid — daily puzzles,
     * weekly tournament puzzles, and duels. Everyone racing these is solving the same
     * cells, so another player's copy is an answer key and must not be spectated.
     * Free-play and imported games stay spectatable.
     */
    /**
     * Delegates to {@link GameService#isCompetitiveGameId(String)} so the WebSocket
     * spectate block and the REST read guard cannot drift apart. They were separate for a
     * while, and the REST side simply did not exist — one plain
     * {@code GET /api/game/daily-<date>:<victim>} bypassed everything this predicate
     * protects.
     */
    static boolean isSharedPuzzle(String gameId) {
        return GameService.isCompetitiveGameId(gameId);
    }

    // ── Per-session flood control ──────────────────────────────────────────
    // Simple token bucket: a burst is fine (a fast solver types quickly, and the web
    // client can emit a move per keystroke), a sustained flood is not.
    private static final int    BURST_CAPACITY    = 40;
    private static final double REFILL_PER_SECOND = 20.0;
    /** Longest chat text relayed to peers; longer messages are truncated, not rejected. */
    static final int MAX_CHAT_LENGTH = 500;

    /** Per-session frame ceilings (the container default of 8 KB is too small for a board). */
    static final int MAX_TEXT_FRAME_BYTES   = 64 * 1024;
    static final int MAX_BINARY_FRAME_BYTES = 16 * 1024;

    private final ConcurrentMap<WebSocketSession, double[]> rateBuckets = new ConcurrentHashMap<>();

    private boolean allowMessage(WebSocketSession session) {
        long now = System.nanoTime();
        double[] bucket = rateBuckets.computeIfAbsent(session,
            s -> new double[]{BURST_CAPACITY, now});
        synchronized (bucket) {
            double elapsedSeconds = (now - (long) bucket[1]) / 1_000_000_000.0;
            bucket[1] = now;
            bucket[0] = Math.min(BURST_CAPACITY, bucket[0] + elapsedSeconds * REFILL_PER_SECOND);
            if (bucket[0] < 1.0) return false;
            bucket[0] -= 1.0;
            return true;
        }
    }

    /**
     * Builds a player move from a client "move" payload ({@code {row, col, oldVal, newVal}}).
     * A {@code source} sent by the client is deliberately ignored — the server stamps
     * {@link SudokuCell.MoveSource#PLAYER} so a client cannot pass its own move off as a
     * hint or an auto-solve. Missing numeric fields default to 0, which
     * {@code EnhancedMove}'s own range validation then checks.
     */
    private EnhancedMove readClientMove(Object rawPayload) {
        Map<?,?> p = rawPayload instanceof Map<?,?> m ? m : Map.of();
        return new EnhancedMove(intField(p, "row"), intField(p, "col"),
            intField(p, "oldVal"), intField(p, "newVal"), SudokuCell.MoveSource.PLAYER);
    }

    private static int intField(Map<?,?> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return 0;
    }
}
