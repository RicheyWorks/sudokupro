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

import java.util.List;

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
        // Shared wiring stubs, lenient() because not every test creates a game
        // (e.g. the save/load tests drive boards in via the repository mock).
        // Wire up the Redis mock so saveToRedis doesn't NPE.
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().doNothing().when(valueOps).set(anyString(), any(), anyLong(), any());

        // Stub side-effects called during createNewGame.
        lenient().doNothing().when(chaosEngine).onGameEvent(anyString(), anyString());
        lenient().doNothing().when(multiplayerBroadcaster).broadcastGameStart(anyString(), anyString());
        lenient().when(gameRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

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

    // ---- game-end listener guard ----

    @Test
    @SuppressWarnings("unchecked")
    void autosolvedGamesNeverReachRewardListeners() {
        GameEndListener listener = mock(GameEndListener.class);
        org.springframework.beans.factory.ObjectProvider<GameEndListener> provider =
            mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.stream()).thenAnswer(inv -> java.util.stream.Stream.of(listener));
        gameService.setGameEndListeners(provider);

        // AI-solved game: listeners must be suppressed (reward exploit guard)
        SudokuBoard cheated = gameService.createNewGame(1, "p-cheat", false, false);
        gameService.solveSudoku(cheated.getGameId());
        gameService.endGame(cheated.getGameId(), "p-cheat");
        verify(listener, never()).onGameEnded(any(), anyString());

        // Abandoned (unsolved) game: listeners still fire — smart difficulty wants it
        SudokuBoard abandoned = gameService.createNewGame(1, "p-quit", false, false);
        gameService.endGame(abandoned.getGameId(), "p-quit");
        verify(listener).onGameEnded(abandoned, "p-quit");
    }

    // ---- puzzle sharing ----

    @Test
    void shareCodeRoundTripsThePuzzleWithoutTheSolution() {
        SudokuBoard original = gameService.createNewGame(2, "p-share", false, false);
        String code = gameService.exportShareCode(original.getGameId());

        SudokuBoard imported = gameService.importShareCode(code, "p-friend");

        assertNotEquals(original.getGameId(), imported.getGameId());
        assertEquals("p-friend", imported.getPlayerId());
        assertTrue(imported.getGameId().startsWith("shared-"));
        int empty = 0;
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++) {
                assertEquals(original.getBoard()[r][c].getValue(), imported.getBoard()[r][c].getValue());
                assertEquals(original.getBoard()[r][c].isGiven(), imported.getBoard()[r][c].isGiven());
                if (imported.getBoard()[r][c].getValue() == 0) empty++;
            }
        assertTrue(empty >= 28, "the shared code carries the puzzle, not a solved grid");
    }

    @Test
    void garbageShareCodesAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> gameService.importShareCode("not-base64!!!", "p-x"));
        assertThrows(IllegalArgumentException.class,
            () -> gameService.importShareCode(
                java.util.Base64.getUrlEncoder().encodeToString("plain junk".getBytes()), "p-x"));
    }

    // ---- hint economy wiring ----

    @Test
    void hintsChargeTheBoardOwnerAndInsufficientGemsWithholdsTheHint() {
        var economy = mock(com.xai.sudokupro.service.economy.EconomyService.class);
        gameService.setEconomyService(economy);
        SudokuBoard board = gameService.createNewGame(1, "p-hints", false, false);

        when(economy.chargeForHint("p-hints")).thenReturn(10);
        assertNotNull(gameService.getHint(board.getGameId()));
        verify(economy).chargeForHint("p-hints");

        when(economy.chargeForHint("p-hints"))
            .thenThrow(new com.xai.sudokupro.service.economy.InsufficientGemsException("p-hints", 0, 5));
        assertThrows(com.xai.sudokupro.service.economy.InsufficientGemsException.class,
            () -> gameService.getHint(board.getGameId()),
            "a broke player must not receive the hint");
    }

    // ---- save / load ----

    @Test
    void saveGamePersistsForOwner() {
        SudokuBoard board = gameService.createNewGame(1, "p-save", false, false);
        clearInvocations(gameRepository);

        SudokuBoard saved = gameService.saveGame(board.getGameId(), "p-save");

        assertSame(board, saved);
        verify(gameRepository).save(board);
    }

    @Test
    void saveGameRejectsNonOwner() {
        SudokuBoard board = gameService.createNewGame(1, "p-owner", false, false);
        clearInvocations(gameRepository);

        assertThrows(SecurityException.class,
            () -> gameService.saveGame(board.getGameId(), "p-intruder"));
        verify(gameRepository, never()).save(any());
    }

    @Test
    void resumeGameFallsBackToDatabaseAndRestoresTheGrid() {
        // Simulate a game that survives only in Postgres: not in activeGames, Redis
        // read returns null, and the repository hands back an entity whose grid was
        // rebuilt from cells_json (mimicked here via a snapshot round-trip, exactly
        // what @PostLoad does).
        SudokuBoard original = new SudokuBoard(1, false, false, 0, "g-db-only");
        original.setPlayerId("p-resume");
        SudokuBoard fromDb = new SudokuBoard(1, false, false, 0, "g-db-only");
        fromDb.setPlayerId("p-resume");
        fromDb.restoreCells(original.snapshotCells());

        when(valueOps.get(anyString())).thenReturn(null);
        when(gameRepository.findByGameId("g-db-only")).thenReturn(fromDb);

        SudokuBoard resumed = gameService.resumeGame("g-db-only", "p-resume");

        assertSame(fromDb, resumed);
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                assertEquals(original.getBoard()[r][c].getValue(),
                    resumed.getBoard()[r][c].getValue(), "restored value at (" + r + "," + c + ")");
        // And it is active again — a follow-up getGame must not hit the repository twice.
        clearInvocations(gameRepository);
        assertSame(resumed, gameService.getGame("g-db-only"));
        verify(gameRepository, never()).findByGameId(anyString());
    }

    @Test
    void resumeGameRejectsBlankPreMigrationBoards() {
        // A row persisted before the V3 cells_json migration restores as a blank
        // 9x9 shell — resume must refuse it instead of serving an empty board.
        SudokuCell[][] blank = new SudokuCell[9][9];
        for (int r = 0; r < 9; r++) for (int c = 0; c < 9; c++) blank[r][c] = new SudokuCell();
        SudokuBoard preMigration = new SudokuBoard(blank, false, false, 0, "g-pre-v3");
        preMigration.setPlayerId("p-old");

        when(valueOps.get(anyString())).thenReturn(null);
        when(gameRepository.findByGameId("g-pre-v3")).thenReturn(preMigration);

        assertThrows(IllegalStateException.class,
            () -> gameService.resumeGame("g-pre-v3", "p-old"));
    }

    @Test
    void solveSudokuPersistsTheSolvedBoard() {
        SudokuBoard board = gameService.createNewGame(1, "p-solve", false, false);
        clearInvocations(gameRepository);

        gameService.solveSudoku(board.getGameId());

        verify(gameRepository).save(board);
        assertTrue(board.isSolved(), "AI solve should complete the board");
    }

    @Test
    void resumeGameRejectsNonOwner() {
        SudokuBoard board = gameService.createNewGame(1, "p-mine", false, false);
        assertThrows(SecurityException.class,
            () -> gameService.resumeGame(board.getGameId(), "p-thief"));
    }

    @Test
    void listSavedGamesQueriesResumableGamesWithCappedLimit() {
        when(gameRepository.findResumableByPlayerId(eq("p-list"), any()))
            .thenReturn(List.of());

        gameService.listSavedGames("p-list", 500);

        var pageCaptor = org.mockito.ArgumentCaptor.forClass(
            org.springframework.data.domain.PageRequest.class);
        verify(gameRepository).findResumableByPlayerId(eq("p-list"), pageCaptor.capture());
        assertEquals(50, pageCaptor.getValue().getPageSize(), "limit must be capped at 50");
    }
}
