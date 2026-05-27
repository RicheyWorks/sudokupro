package com.xai.sudokupro.service;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.util.SecureRandomGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameServiceTest {
    private GameService gameService;
    private AISolverService solverService;

    @BeforeEach
    void setUp() {
        solverService = new AISolverService(new SecureRandomGenerator());
        gameService = new GameService();
        gameService.aiSolverService = solverService; // Manual injection for test
    }

    @Test
    void testCreateNewGame() {
        SudokuBoard board = gameService.createNewGame(2);
        assertNotNull(board, "New game board should not be null");
        assertNotNull(board.getBoard(), "Board cells should be initialized");
    }

    @Test
    void testGetHint() {
        // createNewGame(int) defaults playerId to "anonymous"
        SudokuBoard board = gameService.createNewGame(1);
        String hint = gameService.getHintForPlayer("anonymous");
        assertNotNull(hint, "Hint should not be null");
        assertFalse(hint.isEmpty(), "Hint should provide some guidance");
    }
} 
