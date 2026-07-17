package com.xai.sudokupro.controller;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.api.BoardState;
import com.xai.sudokupro.service.AuthService;
import com.xai.sudokupro.service.duel.DuelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DuelControllerTest {

    @Mock private DuelService duelService;
    @Mock private AuthService authService;

    private DuelController controller;

    @BeforeEach
    void setUp() {
        controller = new DuelController(duelService, authService);
        when(authService.getCurrentPlayerId()).thenReturn("richmond");
    }

    @Test
    void challengeReturnsTheDuelId() {
        when(duelService.challenge("richmond", "rival", 3)).thenReturn("abc12345");

        var response = controller.challenge(new DuelController.ChallengeRequest("rival", 3));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("abc12345", ((Map<?, ?>) response.getBody()).get("duelId"));
    }

    @Test
    void selfChallengeMapsTo400() {
        when(duelService.challenge("richmond", "richmond", 2))
            .thenThrow(new IllegalArgumentException("You cannot duel yourself"));

        assertEquals(HttpStatus.BAD_REQUEST,
            controller.challenge(new DuelController.ChallengeRequest("richmond", 2)).getStatusCode());
    }

    @Test
    void acceptReturnsBoardStateAndMapsErrors() {
        SudokuBoard board = new SudokuBoard(1, false, false, 0, "duel-x:richmond");
        board.setPlayerId("richmond");
        when(duelService.accept("x", "richmond")).thenReturn(board);
        when(duelService.accept("theirs", "richmond")).thenThrow(new SecurityException("not yours"));
        when(duelService.accept("done", "richmond")).thenThrow(new IllegalStateException("not pending"));
        when(duelService.accept("gone", "richmond")).thenThrow(new IllegalArgumentException("unknown"));

        assertInstanceOf(BoardState.class, controller.accept("x").getBody());
        assertEquals(HttpStatus.FORBIDDEN, controller.accept("theirs").getStatusCode());
        assertEquals(HttpStatus.CONFLICT, controller.accept("done").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.accept("gone").getStatusCode());
    }
}
