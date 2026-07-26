package com.xai.sudokupro.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.service.GameService;
import com.xai.sudokupro.websocket.GameSessionRegistry;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for AUDIT P0-1: unauthenticated WebSocket connections must be
 * rejected, and game state must live in GameService (not a controller-local map).
 * Also covers the remote-client protocol types: chat, undo/redo, sync.
 */
@ExtendWith(MockitoExtension.class)
class WebSocketControllerTest {

    @Mock private GameService gameService;
    @Mock private MultiplayerBroadcaster broadcaster;
    @Mock private WebSocketSession session;

    private WebSocketController controller;
    private final Map<String, Object> attributes = new HashMap<>();

    @BeforeEach
    void setUp() {
        controller = new WebSocketController(gameService, new ObjectMapper(), broadcaster,
            new GameSessionRegistry(new ObjectMapper()));
        lenient().when(session.getAttributes()).thenReturn(attributes);
        lenient().when(session.getId()).thenReturn("sess-1");
        // applyMove now REPORTS whether the board actually changed, and the handler only
        // stays silent when it did. It used to be void, so the handler had no success
        // signal and broadcast every move regardless — including ones GameService dropped
        // silently for a FREEZE-locked player. Default the double to "applied".
        lenient().when(gameService.applyMove(anyString(), any(), anyString())).thenReturn(true);
    }

    @Test
    void unauthenticatedConnectionIsClosedAndCreatesNoGame() throws Exception {
        when(session.getPrincipal()).thenReturn(null);

        controller.afterConnectionEstablished(session);

        verify(session).close(WebSocketController.UNAUTHENTICATED);
        verify(gameService, never()).createNewGame(anyInt(), anyString(), anyBoolean(), anyBoolean());
        verify(broadcaster, never()).registerClient();
    }

    @Test
    void authenticatedConnectionUsesGameServiceGameId() throws Exception {
        Principal principal = () -> "richmond";
        when(session.getPrincipal()).thenReturn(principal);
        SudokuBoard board = mock(SudokuBoard.class);
        when(board.getGameId()).thenReturn("real-game-id");
        when(gameService.createNewGame(anyInt(), eq("richmond"), anyBoolean(), anyBoolean()))
            .thenReturn(board);

        controller.afterConnectionEstablished(session);

        // The session must be bound to the board's REAL gameId (previously the controller
        // invented its own UUID, so applyMove could never find the game in GameService).
        org.junit.jupiter.api.Assertions.assertEquals("real-game-id", attributes.get("gameId"));
        verify(broadcaster).registerClient();
        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    void undoRoundTripsThroughServerAndBroadcastsBoard() throws Exception {
        WebSocketSession live = connectedSession("richmond");

        controller.handleTextMessage(live,
            new org.springframework.web.socket.TextMessage("{\"type\":\"undo\"}"));

        verify(gameService).undo("real-game-id");
        // The requester (and everyone else in the game) receives the fresh board.
        verify(live).sendMessage(argThat(msg ->
            ((org.springframework.web.socket.TextMessage) msg).getPayload().contains("\"type\":\"board\"")));
    }

    @Test
    void syncSendsBoardToRequester() throws Exception {
        WebSocketSession live = connectedSession("richmond");

        controller.handleTextMessage(live,
            new org.springframework.web.socket.TextMessage("{\"type\":\"sync\"}"));

        verify(live).sendMessage(argThat(msg ->
            ((org.springframework.web.socket.TextMessage) msg).getPayload().contains("\"type\":\"board\"")));
    }

    @Test
    void chatIsRelayedWithoutTouchingGameState() throws Exception {
        WebSocketSession live = connectedSession("richmond");

        controller.handleTextMessage(live,
            new org.springframework.web.socket.TextMessage("{\"type\":\"chat\",\"payload\":\"gg\"}"));

        verify(gameService, never()).undo(anyString());
        verify(gameService, never()).applyMove(anyString(), any(), anyString());
        // No error envelope back to the sender.
        verify(live, never()).sendMessage(argThat(msg ->
            ((org.springframework.web.socket.TextMessage) msg).getPayload().contains("\"type\":\"error\"")));
    }

    /**
     * Regression: the browser client sends {@code {row, col, oldVal, newVal}} with NO
     * {@code source}. The controller used to deserialize that straight into
     * {@link com.xai.sudokupro.model.EnhancedMove}, whose canonical constructor calls
     * {@code requireNonNull(source)} — so every move from the web client blew up with
     * "MoveSource cannot be null" and came back as an error envelope. Verified live:
     * a full 28-cell solve attempt in a real browser had 28/28 moves rejected.
     *
     * <p>{@code spectatorsCanWatchButNotMutate} already sends a sourceless payload but
     * never caught this, because a spectator is rejected BEFORE the payload is parsed.
     */
    @Test
    void ownerMoveWithoutSourceFieldIsAppliedAndStampedPlayer() throws Exception {
        WebSocketSession live = connectedSession("richmond");
        SudokuBoard board = gameService.getGame("real-game-id");

        int[] target = firstLegalMove(board);
        org.junit.jupiter.api.Assertions.assertNotNull(target, "generated board should accept some move");

        controller.handleTextMessage(live, new org.springframework.web.socket.TextMessage(
            String.format("{\"type\":\"move\",\"payload\":{\"row\":%d,\"col\":%d,\"oldVal\":0,\"newVal\":%d}}",
                target[0], target[1], target[2])));

        org.mockito.ArgumentCaptor<com.xai.sudokupro.model.EnhancedMove> captor =
            org.mockito.ArgumentCaptor.forClass(com.xai.sudokupro.model.EnhancedMove.class);
        verify(gameService).applyMove(eq("real-game-id"), captor.capture(), eq("richmond"));
        org.junit.jupiter.api.Assertions.assertEquals(
            com.xai.sudokupro.model.SudokuCell.MoveSource.PLAYER, captor.getValue().source(),
            "server must stamp the move source itself");
        verify(live, never()).sendMessage(argThat(msg ->
            ((org.springframework.web.socket.TextMessage) msg).getPayload().contains("\"type\":\"error\"")));
    }

    /**
     * A client must not be able to label its own move HINT or AUTOSOLVE: the
     * clean-solve bonus and the auto-solve reward guard both key on move source.
     */
    @Test
    void clientSuppliedSourceIsIgnoredSoMovesCannotMasqueradeAsAutosolve() throws Exception {
        WebSocketSession live = connectedSession("richmond");
        SudokuBoard board = gameService.getGame("real-game-id");
        int[] target = firstLegalMove(board);

        controller.handleTextMessage(live, new org.springframework.web.socket.TextMessage(
            String.format("{\"type\":\"move\",\"payload\":" +
                    "{\"row\":%d,\"col\":%d,\"oldVal\":0,\"newVal\":%d,\"source\":\"AUTOSOLVE\"}}",
                target[0], target[1], target[2])));

        org.mockito.ArgumentCaptor<com.xai.sudokupro.model.EnhancedMove> captor =
            org.mockito.ArgumentCaptor.forClass(com.xai.sudokupro.model.EnhancedMove.class);
        verify(gameService).applyMove(eq("real-game-id"), captor.capture(), eq("richmond"));
        org.junit.jupiter.api.Assertions.assertEquals(
            com.xai.sudokupro.model.SudokuCell.MoveSource.PLAYER, captor.getValue().source(),
            "a client-declared source must never be honoured");
    }

    /**
     * Regression: competitive boards must not be spectatable.
     *
     * <p>Daily, weekly and duel games are per-player COPIES of one shared grid, so another
     * player's copy is an answer key for the identical puzzle you are racing them on — and
     * {@code sync} returns all 81 cell values with no ownership check. Verified live: an
     * attacker connected to {@code daily-<date>:<victim>} and received the victim's 53/81
     * in-progress board. In a ranked duel this streams the opponent's board move by move.
     * Free-play games stay spectatable — that is a deliberate feature.
     */
    @Test
    void competitiveGamesCannotBeSpectated() throws Exception {
        for (String sharedId : new String[]{
                "daily-2026-07-25:alice", "week-2026-W30-p3:alice", "duel-abc123:alice"}) {
            WebSocketSession intruder = mock(WebSocketSession.class);
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("gameId", sharedId);
            lenient().when(intruder.getAttributes()).thenReturn(attrs);
            lenient().when(intruder.getId()).thenReturn("sess-intruder");
            lenient().when(intruder.isOpen()).thenReturn(true);
            Principal principal = () -> "mallory";
            lenient().when(intruder.getPrincipal()).thenReturn(principal);

            SudokuBoard alices = new SudokuBoard(1, false, false, 0, sharedId);
            alices.setPlayerId("alice");
            lenient().when(gameService.getGame(sharedId)).thenReturn(alices);

            controller.afterConnectionEstablished(intruder);

            verify(intruder).close(argThat(cs -> cs.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));
        }
    }

    @Test
    void freePlayGamesRemainSpectatable() throws Exception {
        // The spectator feature itself must survive: only shared-grid ids are restricted.
        org.junit.jupiter.api.Assertions.assertFalse(WebSocketController.isSharedPuzzle("3f9a-free-play"));
        org.junit.jupiter.api.Assertions.assertFalse(WebSocketController.isSharedPuzzle("shared-import-1"));
        org.junit.jupiter.api.Assertions.assertTrue(WebSocketController.isSharedPuzzle("daily-2026-07-25:bob"));
        org.junit.jupiter.api.Assertions.assertTrue(WebSocketController.isSharedPuzzle("week-2026-W30-p1:bob"));
        org.junit.jupiter.api.Assertions.assertTrue(WebSocketController.isSharedPuzzle("duel-xyz:bob"));
    }

    /**
     * Regression: there was NO rate limit anywhere on the WebSocket path, and every inbound
     * frame — including an unknown type — called getGame, which takes the cross-replica
     * game lock. A spectator looping junk frames could therefore hold that lock and starve
     * the board's owner, who then failed every move/hint/undo after a 3s stall.
     */
    @Test
    void floodingASessionIsThrottledInsteadOfHammeringTheGameLock() throws Exception {
        WebSocketSession live = connectedSession("richmond");

        for (int i = 0; i < 400; i++) {
            controller.handleTextMessage(live,
                new org.springframework.web.socket.TextMessage("{\"type\":\"unknown-junk\"}"));
        }

        // The bucket must run dry well before 400 frames, so getGame is not called 400 times.
        verify(gameService, org.mockito.Mockito.atMost(120)).getGame("real-game-id");
        verify(live, org.mockito.Mockito.atLeastOnce()).sendMessage(argThat(msg ->
            ((org.springframework.web.socket.TextMessage) msg).getPayload().contains("Too many messages")));
    }

    /** Chat is fanned out to every session on every replica, so its length must be capped. */
    @Test
    void oversizedChatIsTruncatedBeforeBroadcast() throws Exception {
        WebSocketSession live = connectedSession("richmond");
        WebSocketSession peer = connectedSession("peer");
        String huge = "A".repeat(5000);

        controller.handleTextMessage(live, new org.springframework.web.socket.TextMessage(
            "{\"type\":\"chat\",\"payload\":\"" + huge + "\"}"));

        verify(peer, org.mockito.Mockito.atLeastOnce()).sendMessage(argThat(msg -> {
            String p = ((org.springframework.web.socket.TextMessage) msg).getPayload();
            return !p.contains("\"type\":\"chat\"")
                || p.length() < huge.length();  // truncated, not relayed whole
        }));
    }

    /** First {row, col, value} the board would accept, or null. */
    private static int[] firstLegalMove(SudokuBoard board) {
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                if (board.getBoard()[r][c].getValue() != 0) continue;
                for (int v = 1; v <= 9; v++) {
                    if (board.isValidMove(r, c, v)) return new int[]{r, c, v};
                }
            }
        }
        return null;
    }

    /** Connects an authenticated session bound to a real (serializable) board. */
    @Test
    void spectatorsCanWatchButNotMutate() throws Exception {
        WebSocketSession watcher = connectedSession("watcher");
        // The channel's board actually belongs to someone else.
        SudokuBoard richmonds = new SudokuBoard(1, false, false, 0, "real-game-id");
        richmonds.setPlayerId("richmond");
        when(gameService.getGame("real-game-id")).thenReturn(richmonds);

        controller.handleTextMessage(watcher, new org.springframework.web.socket.TextMessage(
            "{\"type\":\"move\",\"payload\":{\"row\":0,\"col\":0,\"oldVal\":0,\"newVal\":5}}"));
        controller.handleTextMessage(watcher,
            new org.springframework.web.socket.TextMessage("{\"type\":\"undo\"}"));
        controller.handleTextMessage(watcher,
            new org.springframework.web.socket.TextMessage("{\"type\":\"sync\"}"));

        verify(gameService, never()).applyMove(anyString(), any(), anyString());
        verify(gameService, never()).undo(anyString());
        // Mutations are answered with error envelopes; the read-only sync still works.
        verify(watcher, times(2)).sendMessage(argThat(msg ->
            ((org.springframework.web.socket.TextMessage) msg).getPayload().contains("\"type\":\"error\"")));
        verify(watcher).sendMessage(argThat(msg ->
            ((org.springframework.web.socket.TextMessage) msg).getPayload().contains("\"type\":\"board\"")));
    }

    private WebSocketSession connectedSession(String player) throws Exception {
        WebSocketSession live = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        lenient().when(live.getAttributes()).thenReturn(attrs);
        lenient().when(live.getId()).thenReturn("sess-live");
        lenient().when(live.isOpen()).thenReturn(true);
        Principal principal = () -> player;
        lenient().when(live.getPrincipal()).thenReturn(principal);

        SudokuBoard board = new SudokuBoard(1, false, false, 0, "real-game-id");
        board.setPlayerId(player);
        lenient().when(gameService.createNewGame(anyInt(), eq(player), anyBoolean(), anyBoolean()))
            .thenReturn(board);
        lenient().when(gameService.getGame("real-game-id")).thenReturn(board);
        lenient().when(gameService.undo("real-game-id")).thenReturn(board);
        lenient().when(gameService.redo("real-game-id")).thenReturn(board);

        controller.afterConnectionEstablished(live);
        return live;
    }

    @Test
    void joiningUnknownGameIsRejected() throws Exception {
        Principal principal = () -> "richmond";
        when(session.getPrincipal()).thenReturn(principal);
        attributes.put("gameId", "no-such-game");
        when(gameService.getGame("no-such-game"))
            .thenThrow(new IllegalArgumentException("Game not found: no-such-game"));

        controller.afterConnectionEstablished(session);

        verify(session).close(any(CloseStatus.class));
        verify(gameService, never()).createNewGame(anyInt(), anyString(), anyBoolean(), anyBoolean());
    }
}
