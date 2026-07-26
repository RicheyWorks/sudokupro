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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** REST lifecycle endpoints for remote clients: new / get / solve / end. */
@ExtendWith(MockitoExtension.class)
class SudokuGameControllerTest {

    @Mock private GameService gameService;
    @Mock private AuthService authService;
    @Mock private com.xai.sudokupro.service.SmartDifficultyService smartDifficulty;

    private SudokuGameController controller;
    private SudokuBoard board;

    @BeforeEach
    void setUp() {
        controller = new SudokuGameController(gameService, authService, smartDifficulty);
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
        // getGame now reads through getGameForReader, which refuses a competitive board
        // the caller does not own (the REST twin of the WebSocket spectate block).
        when(gameService.getGameForReader(eq("nope"), any()))
            .thenThrow(new IllegalArgumentException("Game not found: nope"));

        assertEquals(HttpStatus.NOT_FOUND, controller.getGame("nope").getStatusCode());
    }

    @Test
    void solveDelegatesAndReturnsFreshState() {
        when(authService.getCurrentPlayerId()).thenReturn("richmond");
        when(gameService.getGame("g-1")).thenReturn(board);

        ResponseEntity<Object> response = controller.solve("g-1");

        // The caller MUST be passed through: solveSudoku had no ownership check, so one
        // request could auto-solve any player's board — or a shared daily/weekly template,
        // ruining the puzzle for everyone — and hand back the full solution.
        verify(gameService).solveSudoku("g-1", "richmond");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertInstanceOf(BoardState.class, response.getBody());
    }

    @Test
    void solveOnSomeoneElsesGameIsForbidden() {
        when(authService.getCurrentPlayerId()).thenReturn("attacker");
        org.mockito.Mockito.doThrow(new SecurityException("Game g-1 belongs to richmond"))
            .when(gameService).solveSudoku("g-1", "attacker");

        assertEquals(HttpStatus.FORBIDDEN, controller.solve("g-1").getStatusCode());
    }

    @Test
    void endReturnsNoContentAndUsesAuthenticatedPlayer() {
        when(authService.getCurrentPlayerId()).thenReturn("richmond");
        when(gameService.getGame("g-1")).thenReturn(board);

        assertEquals(HttpStatus.NO_CONTENT, controller.end("g-1").getStatusCode());
        verify(gameService).endGame("g-1", "richmond");
    }

    @Test
    void spectatorsCannotEndSomeoneElsesGame() {
        when(authService.getCurrentPlayerId()).thenReturn("intruder");
        when(gameService.getGame("g-1")).thenReturn(board); // owned by richmond

        assertEquals(HttpStatus.FORBIDDEN, controller.end("g-1").getStatusCode());
        verify(gameService, never()).endGame(anyString(), anyString());
    }

    // ---- save / load ----

    @Test
    void saveReturnsSavedStatusForOwner() {
        when(authService.getCurrentPlayerId()).thenReturn("richmond");
        when(gameService.saveGame("g-1", "richmond")).thenReturn(board);

        ResponseEntity<Object> response = controller.save("g-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(java.util.Map.of("status", "saved", "gameId", "g-1"), response.getBody());
    }

    @Test
    void saveMapsSecurityExceptionTo403() {
        when(authService.getCurrentPlayerId()).thenReturn("intruder");
        when(gameService.saveGame("g-1", "intruder")).thenThrow(new SecurityException("not yours"));

        assertEquals(HttpStatus.FORBIDDEN, controller.save("g-1").getStatusCode());
    }

    @Test
    void resumeReturnsBoardStateProjection() {
        when(authService.getCurrentPlayerId()).thenReturn("richmond");
        when(gameService.resumeGame("g-1", "richmond")).thenReturn(board);

        ResponseEntity<Object> response = controller.resume("g-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertInstanceOf(BoardState.class, response.getBody(),
            "resume must serialize the BoardState projection, never the raw entity");
    }

    @Test
    void resumeMapsUnknownGameTo404AndFinishedGameTo409() {
        when(authService.getCurrentPlayerId()).thenReturn("richmond");
        when(gameService.resumeGame("gone", "richmond"))
            .thenThrow(new IllegalArgumentException("Game not found: gone"));
        when(gameService.resumeGame("done", "richmond"))
            .thenThrow(new IllegalStateException("Game already solved: done"));

        assertEquals(HttpStatus.NOT_FOUND, controller.resume("gone").getStatusCode());
        assertEquals(HttpStatus.CONFLICT, controller.resume("done").getStatusCode());
    }

    @Test
    void savedGamesListsCallersBoardsAsProjections() {
        when(authService.getCurrentPlayerId()).thenReturn("richmond");
        when(gameService.listSavedGames("richmond", 10)).thenReturn(java.util.List.of(board));

        ResponseEntity<Object> response = controller.savedGames(10);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var list = (java.util.List<?>) response.getBody();
        assertEquals(1, list.size());
        assertInstanceOf(BoardState.class, list.get(0));
    }
}
