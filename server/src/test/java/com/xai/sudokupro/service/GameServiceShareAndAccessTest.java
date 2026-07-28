package com.xai.sudokupro.service;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.Base64;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Share-code import and competitive-board privacy.
 *
 * <p>These cover two separate P0s found in the deeper audit and reproduced live against a
 * running server before being fixed: an unlimited currency mint through
 * {@code POST /api/game/import} + {@code POST /{id}/end}, and a complete bypass of the
 * competitive-spectate protection through the plain REST read.
 */
class GameServiceShareAndAccessTest {

    /** Fills a 9x9 grid with a valid completed Sudoku. */
    private static int[][] solvedGrid() {
        int[][] g = new int[9][9];
        assertTrue(fill(g, 0), "test setup: the backtracking solver must produce a grid");
        return g;
    }

    private static boolean fill(int[][] g, int idx) {
        if (idx == 81) return true;
        int r = idx / 9, c = idx % 9;
        for (int v = 1; v <= 9; v++) {
            if (!legal(g, r, c, v)) continue;
            g[r][c] = v;
            if (fill(g, idx + 1)) return true;
            g[r][c] = 0;
        }
        return false;
    }

    private static boolean legal(int[][] g, int r, int c, int v) {
        for (int i = 0; i < 9; i++) {
            if (g[r][i] == v || g[i][c] == v) return false;
        }
        int br = (r / 3) * 3, bc = (c / 3) * 3;
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (g[br + i][bc + j] == v) return false;
        return true;
    }

    /** Encodes a cell snapshot the way the client's share code does. */
    private static String encode(String cellsJson) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) {
            gzip.write(cellsJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    }

    /** A snapshot of a completed grid, every cell claimed as a legitimate PLAYER move. */
    private static String solvedSnapshotAllPlayerMoves() {
        int[][] g = solvedGrid();
        StringBuilder sb = new StringBuilder("[");
        for (int r = 0; r < 9; r++) {
            if (r > 0) sb.append(',');
            sb.append('[');
            for (int c = 0; c < 9; c++) {
                if (c > 0) sb.append(',');
                sb.append("{\"v\":").append(g[r][c]).append(",\"g\":false,\"ms\":\"PLAYER\"}");
            }
            sb.append(']');
        }
        return sb.append(']').toString();
    }

    /** The same completed grid, but claiming all 81 cells are givens. */
    private static String solvedSnapshotAllGivens() {
        return solvedSnapshotAllPlayerMoves().replace("\"g\":false", "\"g\":true");
    }

    // ── import ────────────────────────────────────────────────────────────────

    /**
     * The headline regression. The share code is attacker-authored — unsigned and never
     * checked against anything the server issued — and {@code restoreCells} validates only
     * shape. So a fully completed grid marked {@code "ms":"PLAYER"} imported as a board
     * that {@code isSolved()} reported true for immediately (the flag is computed from the
     * grid, not stored), and {@code POST /{id}/end} then paid out in full: the caller
     * genuinely owns the board so the ownership check passes, {@code hasAutosolvedCells()}
     * finds no AUTOSOLVE source, and the {@code rewardsGranted} replay guard never fires
     * because each import mints a fresh {@code shared-<uuid>} id. Every reward guard added
     * in the previous eight passes was bypassed at once. Measured live: 15 to 140 gems in
     * five request pairs, no race and no second player required.
     */
    @Test
    void importingACompletedGridDoesNotYieldASolvedBoard() throws Exception {
        SudokuBoard board = importSnapshot(solvedSnapshotAllPlayerMoves());

        assertFalse(board.isSolved(),
            "an imported board must never arrive already solved — that is a free payout");
        int filled = 0;
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (board.getBoard()[r][c].getValue() != 0) filled++;
        assertEquals(0, filled,
            "a grid of 81 non-given values carries no clues, so it imports as an empty board");
    }

    /** The obvious follow-up: claim all 81 cells are givens instead. */
    @Test
    void importingAGridClaimingEveryCellIsAClueIsRejected() {
        var e = assertThrows(IllegalArgumentException.class,
            () -> importSnapshot(solvedSnapshotAllGivens()));
        assertTrue(e.getMessage().contains("playable"),
            "expected a not-a-playable-puzzle rejection, got: " + e.getMessage());
    }

    /** A real shared puzzle must still import and still be playable. */
    @Test
    void aGenuinePuzzleStillImports() throws Exception {
        SudokuBoard source = new SudokuBoard(2, false, false, 0, "share-source");
        SudokuBoard imported = importSnapshot(source.snapshotCells());

        assertFalse(imported.isSolved());
        int givens = 0;
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (imported.getBoard()[r][c].isGiven()) givens++;
        assertTrue(givens >= 17, "a shared puzzle must keep its clues, found " + givens);
    }

    /** An import carries the puzzle, not the sender's partial work or their notes. */
    @Test
    void importDropsTheSendersProgressAndPencilMarks() throws Exception {
        SudokuBoard source = new SudokuBoard(2, false, false, 0, "share-progress");
        int movedRow = -1, movedCol = -1;
        outer:
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (source.getBoard()[r][c].getValue() == 0) {
                    source.getBoard()[r][c].setValue(5, SudokuCell.MoveSource.PLAYER);
                    source.getBoard()[r][c].addPencilMark(3);
                    movedRow = r; movedCol = c;
                    break outer;
                }
        assertTrue(movedRow >= 0, "test setup: found an empty cell");

        SudokuBoard imported = importSnapshot(source.snapshotCells());

        assertEquals(0, imported.getBoard()[movedRow][movedCol].getValue(),
            "the sender's own entries must not come across");
        assertTrue(imported.getBoard()[movedRow][movedCol].getPencilMarks().isEmpty(),
            "pencil marks are the sender's deduction notes, not part of the puzzle");
    }

    /** Bounded decompression: 16KB of encoded input must not expand without limit. */
    @Test
    void aGzipBombIsRefused() throws Exception {
        String bomb = encode("A".repeat(20 * 1024 * 1024));
        var e = assertThrows(IllegalArgumentException.class, () -> importSnapshot(bomb));
        assertTrue(e.getMessage().contains("size") || e.getMessage().contains("Malformed"),
            "expected a size rejection, got: " + e.getMessage());
    }

    // ── competitive-board privacy ─────────────────────────────────────────────

    /**
     * The competitive id namespaces. Verified live before the fix: an attacker read a
     * victim's 33/81 daily board through {@code GET /api/game/daily-<date>:<victim>} and
     * exported it through {@code /share}, both of which the WebSocket layer had blocked
     * since pass 4.
     */
    @Test
    void competitiveGameIdsAreRecognised() {
        assertTrue(GameService.isCompetitiveGameId("daily-2026-07-25:alice"));
        assertTrue(GameService.isCompetitiveGameId("week-2026-W30-p3:alice"));
        assertTrue(GameService.isCompetitiveGameId("duel-abc12345:alice"));
        assertFalse(GameService.isCompetitiveGameId("shared-deadbeef"));
        assertFalse(GameService.isCompetitiveGameId("game-123"));
        assertFalse(GameService.isCompetitiveGameId(null));
    }

    // ── real service under test ───────────────────────────────────────────────

    /**
     * A real {@link GameService}.
     *
     * <p>These tests used to call a private {@code ImportHarness} that MIRRORED
     * {@code importShareCode}, on the reasoning that it kept the assertions readable
     * without a Spring context. That was a mistake, and a mutation audit proved it:
     * deleting the clue-count rejection from the real method, and deleting the real
     * cell-clearing loop — which is verbatim the P0 currency mint this class exists to
     * prevent — both left every test in this file green. A test that reimplements the
     * code it covers asserts only that the copy is self-consistent.
     *
     * <p>The service is real; only its collaborators are mocked, and Redis is a mock that
     * throws on every call so the in-memory fallback path runs.
     */
    private static GameService realService() {
        var gameRepository = mock(com.xai.sudokupro.repository.GameRepository.class);
        lenient().when(gameRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.RedisTemplate<String, SudokuBoard> redis =
            mock(org.springframework.data.redis.core.RedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, SudokuBoard> valueOps =
            mock(org.springframework.data.redis.core.ValueOperations.class);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);

        var stringRedis = mock(org.springframework.data.redis.core.StringRedisTemplate.class,
            inv -> { throw new org.springframework.data.redis.RedisConnectionFailureException("down (test)"); });

        var rng = new com.xai.sudokupro.util.SecureRandomGenerator(
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        return new GameService(
            new AISolverService(rng), gameRepository,
            mock(com.xai.sudokupro.websocket.MultiplayerBroadcaster.class),
            redis, rng,
            new PlayerStateStore(stringRedis), new GameLockManager(stringRedis),
            mock(AnalyticsService.class), mock(AntiCheatEngine.class));
    }

    /** Runs a snapshot through the PRODUCTION import path. */
    private static SudokuBoard importSnapshot(String cellsJsonOrCode) throws Exception {
        String code = cellsJsonOrCode.startsWith("[") ? encode(cellsJsonOrCode) : cellsJsonOrCode;
        return realService().importShareCode(code, "importer");
    }

    // ── the guards that protect a real board ──────────────────────────────────

    /**
     * Regression for a mutation-audit blind spot: nothing exercised
     * {@code getGameForReader}, only the pure {@code isCompetitiveGameId} predicate. The
     * whole condition could be replaced with {@code false} and the suite stayed green —
     * leaving the P0 this class documents (reading another player's daily board over
     * REST) completely unguarded.
     */
    @Test
    void aCompetitiveBoardIsRefusedToAnyoneButItsOwner() {
        GameService service = realService();
        SudokuBoard victim = new SudokuBoard(2, false, false, 0, "daily-2026-07-25:victim");
        victim.setPlayerId("victim");
        service.adoptGame(victim);

        assertThrows(SecurityException.class,
            () -> service.getGameForReader("daily-2026-07-25:victim", "attacker"),
            "an attacker must not read another player's daily board");

        assertDoesNotThrow(() -> service.getGameForReader("daily-2026-07-25:victim", "victim"),
            "the owner must still be able to read their own board");
    }

    /** A casual board is not competitive, so spectating it stays allowed. */
    @Test
    void aCasualBoardIsStillReadableByOthers() {
        GameService service = realService();
        SudokuBoard casual = new SudokuBoard(2, false, false, 0, "casual-abc");
        casual.setPlayerId("owner");
        service.adoptGame(casual);

        assertDoesNotThrow(() -> service.getGameForReader("casual-abc", "someone-else"));
    }

    /**
     * Regression: the share-export ownership check was unguarded. Only the one-argument
     * overload was ever called in tests, and that overload skips the check by design, so
     * deleting {@code requireOwner} from the real method changed nothing in the suite.
     */
    @Test
    void shareExportIsRefusedToAnyoneButTheOwner() {
        GameService service = realService();
        SudokuBoard board = new SudokuBoard(2, false, false, 0, "duel-ff00:victim");
        board.setPlayerId("victim");
        service.adoptGame(board);

        assertThrows(SecurityException.class,
            () -> service.exportShareCode("duel-ff00:victim", "attacker"),
            "a share code is the victim's live grid, including their pencil marks");

        assertDoesNotThrow(() -> service.exportShareCode("duel-ff00:victim", "victim"));
    }
}
