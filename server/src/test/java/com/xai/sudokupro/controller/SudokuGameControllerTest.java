package com.xai.sudokupro.controller;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.api.BoardState;
import com.xai.sudokupro.service.AuthService;
import com.xai.sudokupro.service.GameService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** REST lifecycle endpoints for remote clients: new / get / solve / end. */
@ExtendWith(MockitoExtension.class)
class SudokuGameControllerTest {

    @Mock private GameService gameService;
    @Mock private AuthService authService;

    private SudokuGameController controller;
    private SudokuBoard board;

    @BeforeEach
    void setUp() {
        controller = new SudokuGameController(gameService, authService);
        board = new SudokuBoard(1, false, false, 0, "g-1");
        board.setPlayerId("richmond");
    }

    @Test
    void createGameReturnsBoardStateNotEntity() {
        when(authService.getCurrentPlayerId()).thenReturn("richmond");
        when(gameService.createNewGame(2, "richmond", true, false)).thenReturn(board);

        ResponseEntity<Object> response = controller.createGame(2, true, false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertInstanceOf(BoardState.class, response.getBody(),
            "REST must serialize the BoardState projection, never the raw JPA entity");
        assertEquals("g-1", ((BoardState) response.getBody()).gameId());
    }

    @Test
    void getGameReturns404ForUnknownId() {
        when(gameService.getGame("nope")).thenThrow(new IllegalArgumentException("Game not found: nope"));

        assertEquals(HttpStatus.NOT_FOUND, controller.getGame("nope").getStatusCode());
    }

    @Test
    void solveDelegatesAndReturnsFreshState() {
        when(gameService.getGame("g-1")).thenReturn(board);

        ResponseEntity<Object> response = controller.solve("g-1");

        verify(gameService).solveSudoku("g-1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertInstanceOf(BoardState.class, response.getBody());
    }

    @Test
    void endReturnsNoContentAndUsesAuthenticatedPlayer() {
        when(authService.getCurrentPlayerId()).thenReturn("richmond");

        assertEquals(HttpStatus.NO_CONTENT, controller.end("g-1").getStatusCode());
        verify(gameService).endGame("g-1", "richmond");
    }
}
