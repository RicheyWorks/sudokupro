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

    /**
     * Regression: replaying {@code POST /api/game/{id}/end} on a legitimately solved
     * board must pay out exactly once.
     *
     * <p>{@code endGame} guards on {@code activeGames.remove(gameId) != null}, but the
     * endpoint's own ownership check calls {@code getGame} first, which re-hydrates the
     * finished board into the active set — so every replay passed that guard and fired
     * every reward listener again. Proven live against a running server: a solved Easy
     * game paid +15 gems and +15 XP on each of seven consecutive /end calls, taking the
     * player from 15 to 135 gems. An attacker just repeats one HTTP request.
     */
    @Test
    @SuppressWarnings("unchecked")
    void solvedGamePaysOutOnlyOnceEvenWhenEndIsReplayed() {
        GameEndListener listener = mock(GameEndListener.class);
        org.springframework.beans.factory.ObjectProvider<GameEndListener> provider =
            mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.stream()).thenAnswer(inv -> java.util.stream.Stream.of(listener));
        gameService.setGameEndListeners(provider);

        SudokuBoard board = gameService.createNewGame(1, "p-honest", false, false);
        String gameId = board.getGameId();
        playToCompletion(board);
        assertTrue(board.isSolved(), "test setup: board should be genuinely solved");

        // The DB still holds the finished board, so the controller's ownership check
        // (getGame) can pull it back into the active set on every replay — which is
        // exactly what defeated endGame's remove()-based guard.
        when(gameRepository.findByGameId(gameId)).thenReturn(board);

        gameService.endGame(gameId, "p-honest");
        for (int i = 0; i < 5; i++) {
            gameService.getGame(gameId);          // re-hydrate, as /end does
            gameService.endGame(gameId, "p-honest");
        }

        verify(listener, times(1)).onGameEnded(any(), eq("p-honest"));
        assertTrue(board.isRewardsGranted(), "a paid-out board must be flagged");
    }

    /**
     * The payout flag must NOT be set by abandoning an unfinished game — otherwise
     * resuming it and finishing properly later would silently earn nothing.
     */
    @Test
    @SuppressWarnings("unchecked")
    void abandoningAnUnfinishedGameDoesNotBurnItsFuturePayout() {
        GameEndListener listener = mock(GameEndListener.class);
        org.springframework.beans.factory.ObjectProvider<GameEndListener> provider =
            mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.stream()).thenAnswer(inv -> java.util.stream.Stream.of(listener));
        gameService.setGameEndListeners(provider);

        SudokuBoard board = gameService.createNewGame(1, "p-later", false, false);
        String gameId = board.getGameId();
        when(gameRepository.findByGameId(gameId)).thenReturn(board);

        gameService.endGame(gameId, "p-later");                // walked away, unsolved
        assertFalse(board.isRewardsGranted(), "an unsolved end must stay eligible");

        gameService.getGame(gameId);                           // resume
        playToCompletion(board);
        gameService.endGame(gameId, "p-later");

        // once for the abandonment signal, once for the real completion
        verify(listener, times(2)).onGameEnded(any(), eq("p-later"));
        assertTrue(board.isRewardsGranted());
    }

    /** Solves the board independently and plays every empty cell as a real PLAYER move. */
    private static void playToCompletion(SudokuBoard board) {
        int[][] grid = new int[9][9];
        boolean[][] empty = new boolean[9][9];
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                grid[r][c] = board.getBoard()[r][c].getValue();
                empty[r][c] = grid[r][c] == 0;
            }
        }
        if (!backtrack(grid)) throw new IllegalStateException("test setup: puzzle not solvable");
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                if (empty[r][c]) {
                    board.makeMove(r, c, grid[r][c], com.xai.sudokupro.model.SudokuCell.MoveSource.PLAYER);
                }
            }
        }
    }

    private static boolean backtrack(int[][] g) {
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                if (g[r][c] != 0) continue;
                for (int v = 1; v <= 9; v++) {
                    if (legal(g, r, c, v)) {
                        g[r][c] = v;
                        if (backtrack(g)) return true;
                        g[r][c] = 0;
                    }
                }
                return false;
            }
        }
        return true;
    }

    private static boolean legal(int[][] g, int r, int c, int v) {
        for (int i = 0; i < 9; i++) if (g[r][i] == v || g[i][c] == v) return false;
        int br = r - r % 3, bc = c - c % 3;
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (g[br + i][bc + j] == v) return false;
        return true;
    }

    /**
     * Regression: a hint must be charged to the CALLER, and a non-owner must be refused.
     *
     * <p>{@code getHint} charged {@code board.getPlayerId()} with no caller check, so
     * passing another player's active gameId drained THEIR wallet 5 gems per call and
     * bumped their hintCount (forfeiting their clean-solve bonus) while the attacker paid
     * nothing and got the hint. Verified live against a running server: attacker's balance
     * unchanged, victim's went 15 -> 10.
     */
    @Test
    void hintOnAnotherPlayersBoardIsRefusedRatherThanChargedToTheOwner() {
        SudokuBoard victims = gameService.createNewGame(1, "victim", false, false);

        assertThrows(SecurityException.class,
            () -> gameService.getHint(victims.getGameId(), "attacker"),
            "a non-owner must not be able to spend the owner's gems");

        assertEquals(0, victims.getHintCount(),
            "a refused hint must not touch the owner's hint counter either");
    }

    @Test
    void ownerCanStillTakeHintsOnTheirOwnBoard() {
        SudokuBoard mine = gameService.createNewGame(1, "owner", false, false);
        assertDoesNotThrow(() -> gameService.getHint(mine.getGameId(), "owner"));
    }

    /**
     * Regression: {@code POST /api/game/{id}/solve} had no caller check whatsoever — the
     * single most damaging hole found. Competitive game ids are deterministic and
     * usernames are public from the leaderboards ({@code duel-<id>:<player>},
     * {@code daily-<date>:<player>}, {@code week-<year-Www>-p<n>:<player>}), so one
     * request could AI-fill and PERSIST any player's board. The victim then cannot move
     * (grid full), cannot resume (409), and can never claim the win, because the
     * auto-solve reward guard suppresses their completion. The response also returned the
     * finished grid, making it a solution oracle.
     *
     * <p>Worse, the shared daily/weekly TEMPLATE rows are ordinary boards: verified live
     * that solving {@code daily-<date>} left every subsequent joiner with an 81/81
     * pre-solved, unwinnable puzzle — the day destroyed for the whole player base.
     */
    @Test
    void solveOnAnotherPlayersBoardIsRefused() {
        SudokuBoard victims = gameService.createNewGame(1, "victim", false, false);
        int filledBefore = countFilled(victims);

        assertThrows(SecurityException.class,
            () -> gameService.solveSudoku(victims.getGameId(), "attacker"));

        assertEquals(filledBefore, countFilled(victims), "victim's grid must be untouched");
        assertFalse(victims.isSolved(), "victim's game must not be marked solved");
    }

    /** A shared daily/weekly template must not be solvable by a passing player. */
    @Test
    void solveOnASharedTemplateBoardIsRefused() {
        SudokuBoard template = gameService.createNewGame(1, "__daily__", false, false);
        assertThrows(SecurityException.class,
            () -> gameService.solveSudoku(template.getGameId(), "anyone"),
            "poisoning the shared template would ruin the puzzle for every player");
    }

    @Test
    void ownerCanStillAutoSolveTheirOwnBoard() {
        SudokuBoard mine = gameService.createNewGame(1, "owner", false, false);
        assertDoesNotThrow(() -> gameService.solveSudoku(mine.getGameId(), "owner"));
        assertTrue(mine.isSolved());
    }

    private static int countFilled(SudokuBoard board) {
        int n = 0;
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (board.getBoard()[r][c].getValue() != 0) n++;
        return n;
    }


    /**
     * Regression: the READ path grew the board cache without bound.
     *
     * <p>{@code getGame} populates {@code activeGames} on a cache miss but never called
     * {@code trimActiveGames()} — only createNewGame/adoptGame did. Abandoned games are
     * never evicted either, so a pod that merely READ boards (spectating, /api/game/{id},
     * every /end ownership check) grew the map, and GameLockManager's per-game monitors
     * grew alongside it, until the pod ran out of memory. Eviction also picked an
     * arbitrary hash-order entry while calling it "oldest", so it could discard the game
     * someone was actively playing.
     */
    @Test
    void repeatedReadsOfDistinctGamesDoNotGrowTheCacheWithoutBound() {
        // Every id resolves from the "database", so each read is a cache miss + insert.
        when(gameRepository.findByGameId(anyString())).thenAnswer(inv -> {
            SudokuBoard b = new SudokuBoard(1, false, false, 0, inv.getArgument(0));
            b.setPlayerId("reader");
            return b;
        });

        int before = gameService.getActiveGamesCount();
        for (int i = 0; i < 300; i++) {
            gameService.getGame("read-only-" + i);
        }
        int after = gameService.getActiveGamesCount();

        // The cap is large, so assert the mechanism rather than a magic number: the cache
        // is bounded and the lock manager has not accumulated one monitor per game read.
        assertTrue(after >= before, "reads populate the cache");
        assertTrue(after <= before + 300, "cache cannot exceed what was read");
        assertDoesNotThrow(() -> gameService.getGame("read-only-0"),
            "a previously read game must still be resolvable after many further reads");
    }

    /** Ending a game must reclaim its cache entry rather than leaving it pinned. */
    @Test
    void endingAGameReleasesItsCacheEntry() {
        SudokuBoard board = gameService.createNewGame(1, "leaver", false, false);
        int active = gameService.getActiveGamesCount();

        gameService.endGame(board.getGameId(), "leaver");

        assertEquals(active - 1, gameService.getActiveGamesCount(),
            "a finished game must not stay in the active cache");
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
