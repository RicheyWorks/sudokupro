package com.xai.sudokupro.controller;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.api.BoardState;
import com.xai.sudokupro.model.api.DailyScore;
import com.xai.sudokupro.model.api.DailyStatus;
import com.xai.sudokupro.service.AuthService;
import com.xai.sudokupro.service.daily.DailyPuzzleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyPuzzleControllerTest {

    @Mock private DailyPuzzleService dailyPuzzleService;
    @Mock private AuthService authService;

    private DailyPuzzleController controller;

    @BeforeEach
    void setUp() {
        controller = new DailyPuzzleController(dailyPuzzleService, authService);
        // lenient: the leaderboard endpoint is anonymous-read and never asks who's calling
        org.mockito.Mockito.lenient().when(authService.getCurrentPlayerId()).thenReturn("richmond");
    }

    @Test
    void statusReturnsTheCallersDailyStatus() {
        when(dailyPuzzleService.status("richmond"))
            .thenReturn(new DailyStatus("2026-07-16", 2, true, false, 4));

        var response = controller.status();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(4, response.getBody().streakDays());
    }

    @Test
    void joinReturnsTheBoardStateProjection() {
        SudokuBoard board = new SudokuBoard(2, false, false, 0, "daily-2026-07-16:richmond");
        board.setPlayerId("richmond");
        when(dailyPuzzleService.joinDaily("richmond")).thenReturn(board);

        var response = controller.join();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertInstanceOf(BoardState.class, response.getBody());
        assertEquals("daily-2026-07-16:richmond", response.getBody().gameId());
    }

    @Test
    void leaderboardDelegatesWithLimit() {
        when(dailyPuzzleService.leaderboard(5))
            .thenReturn(List.of(new DailyScore(1, "fast", 90)));

        var response = controller.leaderboard(5);

        assertEquals(1, response.getBody().size());
        assertEquals("fast", response.getBody().get(0).playerId());
    }
}
