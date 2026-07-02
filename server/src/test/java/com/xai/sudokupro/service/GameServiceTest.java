package com.xai.sudokupro.service;

import com.xai.sudokupro.engine.ChaosEngine;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.SudokuCell;
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

        // Every call on this mock throws like a down Redis, exercising the stores'
        // in-memory fallback — the single-replica code path.
        org.springframework.data.redis.core.StringRedisTemplate stringRedis =
            mock(org.springframework.data.redis.core.StringRedisTemplate.class,
                inv -> { throw new org.springframework.data.redis.RedisConnectionFailureException("down (test)"); });
        PlayerStateStore playerState = new PlayerStateStore(stringRedis);
        GameLockManager gameLockManager = new GameLockManager(stringRedis);

        gameService = new GameService(
            realSolver, gameRepository, multiplayerBroadcaster,
            redisTemplate, rng, playerState, gameLockManager,
            analyticsService, antiCheatEngine, chaosEngine
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

    // ---- move / lock logic (AUDIT P1-1) ----

    /** Finds an empty cell and a legal value for it. Returns {row, col, value}. */
    private static int[] findLegalMove(SudokuBoard board) {
        SudokuCell[][] cells = board.getBoard();
        for (int i = 0; i < 9; i++)
            for (int j = 0; j < 9; j++)
                if (cells[i][j].getValue() == 0)
                    for (int v = 1; v <= 9; v++)
                        if (board.isValidMove(i, j, v)) return new int[]{i, j, v};
        throw new IllegalStateException("Generated board has no legal move");
    }

    @Test
    void applyMoveUpdatesBoardAndRecordsForAntiCheat() {
        SudokuBoard board = gameService.createNewGame(1, "p-move", false, false);
        int[] m = findLegalMove(board);
        EnhancedMove move = new EnhancedMove(m[0], m[1], m[2], SudokuCell.MoveSource.PLAYER);

        gameService.applyMove(board.getGameId(), move, "p-move");

        assertEquals(m[2], board.getBoard()[m[0]][m[1]].getValue(),
            "Move must be applied to the board held by GameService");
        verify(antiCheatEngine).recordMove("p-move", false);
    }

    @Test
    void lockedPlayerMovesAreRejected() {
        SudokuBoard board = gameService.createNewGame(1, "p-lock", false, false);
        int[] m = findLegalMove(board);
        EnhancedMove move = new EnhancedMove(m[0], m[1], m[2], SudokuCell.MoveSource.PLAYER);

        gameService.lockPlayerInput("p-lock", 60_000);
        gameService.applyMove(board.getGameId(), move, "p-lock");

        assertEquals(0, board.getBoard()[m[0]][m[1]].getValue(),
            "A locked player's move must not reach the board");
        verify(antiCheatEngine, never()).recordMove(anyString(), anyBoolean());
    }

    @Test
    void unknownGameIdThrows() {
        gameService.createNewGame(1, "p-x", false, false);
        assertThrows(IllegalArgumentException.class,
            () -> gameService.getGame("no-such-game"),
            "getGame must throw for unknown ids (WebSocketController relies on this)");
    }
}
