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
