package com.xai.sudokupro.service;

import com.xai.sudokupro.engine.ChaosEngine;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.util.SecureRandomGenerator;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GameServiceTest {

    @Mock private AISolverService         aiSolverService;
    @Mock private GameRepository          gameRepository;
    @Mock private MultiplayerBroadcaster  multiplayerBroadcaster;
    @Mock private RedisTemplate<String, SudokuBoard> redisTemplate;
    @Mock private AnalyticsService        analyticsService;
    @Mock private AntiCheatEngine         antiCheatEngine;
    @Mock private ChaosEngine             chaosEngine;
    @Mock private ValueOperations<String, SudokuBoard> valueOps;

    private GameService gameService;

    @BeforeEach
    void setUp() {
        // Wire up the Redis mock so saveToRedis doesn't NPE.
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        doNothing().when(valueOps).set(anyString(), any(), anyLong(), any());

        // Stub side-effects called during createNewGame.
        doNothing().when(chaosEngine).onGameEvent(anyString(), anyString());
        doNothing().when(multiplayerBroadcaster).broadcastGameStart(anyString(), anyString());
        when(gameRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // AISolverService needs a real SecureRandomGenerator for hint calls.
        SecureRandomGenerator rng = new SecureRandomGenerator(new SimpleMeterRegistry());
        AISolverService realSolver = new AISolverService(rng);

        gameService = new GameService(
            realSolver, gameRepository, multiplayerBroadcaster,
            redisTemplate, rng, analyticsService, antiCheatEngine, chaosEngine
        );
    }

    @Test
    void testCreateNewGame() {
        SudokuBoard board = gameService.createNewGame(2);
        assertNotNull(board, "New game board should not be null");
        assertNotNull(board.getBoard(), "Board cells should be initialized");
    }

    @Test
    void testGetHint() {
        // createNewGame registers the board under playerId "anonymous".
        gameService.createNewGame(1);
        String hint = gameService.getHintForPlayer("anonymous");
        assertNotNull(hint, "Hint should not be null");
        assertFalse(hint.isEmpty(), "Hint should provide some guidance");
    }
}
