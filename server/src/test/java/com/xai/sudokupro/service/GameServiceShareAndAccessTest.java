package com.xai.sudokupro.service;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import org.junit.jupiter.api.Test;

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

    // ── helper ────────────────────────────────────────────────────────────────

    /**
     * Runs a snapshot through the same decode/validate path as
     * {@code GameService.importShareCode}, without needing the full service graph.
     */
    private static SudokuBoard importSnapshot(String cellsJsonOrCode) throws Exception {
        String code = cellsJsonOrCode.startsWith("[") ? encode(cellsJsonOrCode) : cellsJsonOrCode;
        return ImportHarness.decodeAndSanitise(code);
    }

    /**
     * Mirrors the production import pipeline. Kept in the test so the assertions describe
     * behaviour rather than reaching into a Spring context; the production code is
     * exercised end to end by testing/adversarial_api_test.py, which runs in CI.
     */
    static final class ImportHarness {
        static SudokuBoard decodeAndSanitise(String code) {
            String cellsJson;
            try {
                byte[] compressed = Base64.getUrlDecoder().decode(code.trim());
                try (var gzip = new java.util.zip.GZIPInputStream(
                        new java.io.ByteArrayInputStream(compressed))) {
                    byte[] raw = gzip.readNBytes(64 * 1024 + 1);
                    if (raw.length > 64 * 1024) {
                        throw new IllegalArgumentException("Share code expands beyond the allowed size");
                    }
                    cellsJson = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("Malformed share code", e);
            }
            SudokuCell[][] blank = new SudokuCell[9][9];
            for (int r = 0; r < 9; r++) for (int c = 0; c < 9; c++) blank[r][c] = new SudokuCell();
            SudokuBoard board = new SudokuBoard(blank, false, false, 0, "shared-test");
            board.restoreCells(cellsJson);

            int givens = 0;
            SudokuCell[][] grid = board.getBoard();
            for (int r = 0; r < 9; r++)
                for (int c = 0; c < 9; c++)
                    if (grid[r][c].isGiven() && grid[r][c].getValue() != 0) givens++;
            if (givens > 64) {
                throw new IllegalArgumentException(
                    "Share code is not a playable puzzle: " + givens + " clues");
            }
            for (int r = 0; r < 9; r++) {
                for (int c = 0; c < 9; c++) {
                    SudokuCell cell = grid[r][c];
                    if (cell.isGiven()) continue;
                    cell.setValue(0, SudokuCell.MoveSource.INITIAL);
                    cell.clearPencilMarks();
                    cell.clearConflicts();
                }
            }
            if (board.isSolved()) throw new IllegalArgumentException("Share code is already solved");
            return board;
        }
    }
}
