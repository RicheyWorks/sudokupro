package com.xai.sudokupro.service;

import com.xai.sudokupro.engine.ChaosEngine;
import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.util.SecureRandomGenerator;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The defect these cover: <b>a power-up the player paid gems for changed the board in
 * one pod's memory and nowhere else.</b>
 *
 * <p>{@code PowerUpService} called {@code SudokuBoard.applyExternalMove} — deliberately
 * the non-broadcasting variant — and then neither {@code saveToRedis} nor
 * {@code persistBoard}. Nothing marked the game {@code locallyMutated} either, which is
 * the flag {@code GameService.shutdown()} keys on, so even the shutdown safety net
 * skipped the board. A cache trim, a pod restart, a reconnect, or a read from another
 * replica reverted the revealed cell; after pass 15's cross-replica version check, the
 * next write from any other replica drops the copy holding it outright. The player was
 * charged 20 gems and, because the desktop client re-renders its stale local board,
 * could not even see that nothing had happened.
 *
 * <p>So the assertions here are deliberately about the plumbing rather than the digit:
 * that the write-through pair ran, and that peers were told. A test that only checked
 * "a cell got filled" is exactly the test that passed throughout the bug's life.
 */
class GameServicePowerUpEffectTest {

    private GameRepository gameRepository;
    private MultiplayerBroadcaster broadcaster;
    private RedisTemplate<String, SudokuBoard> redisTemplate;
    private ValueOperations<String, SudokuBoard> valueOps;
    private GameService gameService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        gameRepository = mock(GameRepository.class);
        broadcaster = mock(MultiplayerBroadcaster.class);
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(gameRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StringRedisTemplate downRedis = mock(StringRedisTemplate.class,
            inv -> { throw new RedisConnectionFailureException("down (test)"); });
        SecureRandomGenerator rng = new SecureRandomGenerator(new SimpleMeterRegistry());

        gameService = new GameService(
            new AISolverService(rng), gameRepository, broadcaster, redisTemplate, rng,
            new PlayerStateStore(downRedis), new GameLockManager(downRedis),
            mock(AnalyticsService.class), mock(AntiCheatEngine.class), mock(ChaosEngine.class));
    }

    private long countEmpty(SudokuBoard board) {
        long empty = 0;
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (board.getBoard()[r][c].getValue() == 0) empty++;
        return empty;
    }

    @Test
    void revealCellFillsPersistsAndBroadcasts() {
        SudokuBoard board = gameService.createNewGame(1, "richmond", false, false);
        String gameId = board.getGameId();
        long emptyBefore = countEmpty(board);
        clearInvocations(gameRepository, valueOps, broadcaster);

        EnhancedMove revealed = gameService.revealCell(gameId, "richmond");

        assertNotNull(revealed);
        assertEquals(emptyBefore - 1, countEmpty(board), "exactly one cell must be filled");
        assertEquals(revealed.newVal(), board.getBoard()[revealed.row()][revealed.col()].getValue());
        assertEquals(1, board.getHintCount(),
            "a reveal is assistance — it must forfeit the clean-solve bonus like a hint");

        // The three things the old implementation skipped.
        verify(gameRepository).save(board);
        verify(valueOps).set(contains(gameId), eq(board), anyLong(), any());
        verify(broadcaster).sendMove(eq(gameId), eq(revealed));
    }

    @Test
    void extraLifePersists() {
        SudokuBoard board = gameService.createNewGame(1, "richmond", false, false);
        String gameId = board.getGameId();
        int before = board.getLives();
        clearInvocations(gameRepository, valueOps);

        int after = gameService.grantExtraLife(gameId, "richmond");

        assertEquals(before + 1, after);
        assertEquals(before + 1, board.getLives());
        verify(gameRepository).save(board);
        verify(valueOps).set(contains(gameId), eq(board), anyLong(), any());
    }

    @Test
    void neitherEffectTouchesSomeoneElsesBoard() {
        SudokuBoard board = gameService.createNewGame(1, "ada", false, false);
        String gameId = board.getGameId();
        long emptyBefore = countEmpty(board);
        int livesBefore = board.getLives();

        assertThrows(SecurityException.class, () -> gameService.revealCell(gameId, "richmond"));
        assertThrows(SecurityException.class, () -> gameService.grantExtraLife(gameId, "richmond"));

        assertEquals(emptyBefore, countEmpty(board), "the victim's grid must be untouched");
        assertEquals(livesBefore, board.getLives());
    }

    /** A finished grid has nothing to reveal, and must fail before any write. */
    @Test
    void revealOnASolvedBoardFailsWithoutWriting() {
        SudokuBoard board = gameService.createNewGame(1, "richmond", false, false);
        String gameId = board.getGameId();
        gameService.solveSudoku(gameId, "richmond");
        clearInvocations(gameRepository, valueOps, broadcaster);

        assertThrows(IllegalStateException.class, () -> gameService.revealCell(gameId, "richmond"));

        verify(broadcaster, never()).sendMove(anyString(), any());
    }
}
