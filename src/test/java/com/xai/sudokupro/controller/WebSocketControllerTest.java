package com.xai.sudokupro.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.service.GameService;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for AUDIT P0-1: unauthenticated WebSocket connections must be
 * rejected, and game state must live in GameService (not a controller-local map).
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
        controller = new WebSocketController(gameService, new ObjectMapper(), broadcaster);
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
