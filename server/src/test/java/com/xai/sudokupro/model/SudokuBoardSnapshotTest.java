package com.xai.sudokupro.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Grid snapshot/restore (save/load feature). The SudokuCell[][] grid is
 * {@code @Transient} for JPA, so persistence round-trips through the
 * {@code cells_json} snapshot produced by {@link SudokuBoard#snapshotCells()}
 * and rebuilt by {@link SudokuBoard#restoreCells(String)}. These tests pin the
 * round-trip fidelity that the DB (@PrePersist/@PostLoad) and Redis
 * (getCellsJson/setCellsJson) paths both rely on.
 */
class SudokuBoardSnapshotTest {

    private static SudokuBoard freshBoard() {
        return new SudokuBoard(2, false, false, 0, "snap-test");
    }

    private static SudokuBoard restoredCopy(SudokuBoard original) {
        SudokuBoard copy = new SudokuBoard(2, false, false, 0, "snap-restored");
        copy.restoreCells(original.snapshotCells());
        return copy;
    }

    @Test
    void roundTripPreservesValuesAndGivenFlags() {
        SudokuBoard original = freshBoard();
        SudokuBoard restored = restoredCopy(original);

        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                SudokuCell o = original.getBoard()[r][c];
                SudokuCell x = restored.getBoard()[r][c];
                assertEquals(o.getValue(), x.getValue(), "value at (" + r + "," + c + ")");
                assertEquals(o.isGiven(), x.isGiven(), "given flag at (" + r + "," + c + ")");
            }
        }
    }

    @Test
    void roundTripPreservesPlayerMovesPencilMarksAndConflicts() {
        SudokuBoard original = freshBoard();

        // Find two empty cells and decorate them with player state.
        int[] move = null, marks = null;
        for (int r = 0; r < 9 && (move == null || marks == null); r++) {
            for (int c = 0; c < 9 && (move == null || marks == null); c++) {
                if (original.getBoard()[r][c].getValue() != 0) continue;
                if (move == null) move = new int[]{r, c};
                else marks = new int[]{r, c};
            }
        }
        assertNotNull(move); assertNotNull(marks);

        original.getBoard()[move[0]][move[1]].setValue(5, SudokuCell.MoveSource.PLAYER);
        original.getBoard()[move[0]][move[1]].addConflict(5);
        original.getBoard()[marks[0]][marks[1]].addPencilMark(3);
        original.getBoard()[marks[0]][marks[1]].addPencilMark(7);

        SudokuBoard restored = restoredCopy(original);

        SudokuCell movedCell = restored.getBoard()[move[0]][move[1]];
        assertEquals(5, movedCell.getValue());
        assertEquals(SudokuCell.MoveSource.PLAYER, movedCell.getMoveSource());
        assertEquals(Set.of(5), movedCell.getConflicts());
        assertEquals(Set.of(3, 7), restored.getBoard()[marks[0]][marks[1]].getPencilMarks());
    }

    @Test
    void restoredGivenCellsStillRefuseModification() {
        // Regression guard for P1-NEW-1's failure mode: a restored clue must keep
        // its given flag so the given-cell guard and isCellEditable stay effective.
        SudokuBoard restored = restoredCopy(freshBoard());

        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                if (!restored.getBoard()[r][c].isGiven()) continue;
                int before = restored.getBoard()[r][c].getValue();
                restored.getBoard()[r][c].setValue(before == 9 ? 1 : before + 1, SudokuCell.MoveSource.PLAYER);
                assertEquals(before, restored.getBoard()[r][c].getValue(),
                    "restored given cell (" + r + "," + c + ") must refuse modification");
                assertFalse(restored.isCellEditable(r, c));
                return; // one representative cell is enough
            }
        }
        fail("generated board has no given cells");
    }

    @Test
    void malformedSnapshotThrowsAndLeavesBoardUntouched() {
        SudokuBoard board = freshBoard();
        String before = board.snapshotCells();

        assertThrows(IllegalArgumentException.class, () -> board.restoreCells("not json"));
        assertThrows(IllegalArgumentException.class, () -> board.restoreCells("[[{\"v\":1}]]"));
        assertEquals(before, board.snapshotCells(), "failed restore must not mutate the grid");
    }

    @Test
    void nullOrBlankSnapshotIsANoOp() {
        SudokuBoard board = freshBoard();
        String before = board.snapshotCells();
        board.restoreCells(null);
        board.restoreCells("  ");
        assertEquals(before, board.snapshotCells());
    }

    @Test
    void jacksonPropertyRoundTripRestoresGrid() {
        // The Redis cache path: cellsJson rides along the serialized entity.
        SudokuBoard original = freshBoard();
        SudokuBoard target = new SudokuBoard(1, false, false, 0, "jackson-copy");
        target.setCellsJson(original.getCellsJson());

        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                assertEquals(original.getBoard()[r][c].getValue(), target.getBoard()[r][c].getValue());
                assertEquals(original.getBoard()[r][c].isGiven(), target.getBoard()[r][c].isGiven());
            }
        }
    }

    /**
     * Regression: a board with ANY move could not be read back out of the Redis cache.
     *
     * <p>{@code SudokuBoard.getReplayHistory()} is serialized, and each element is an
     * {@link com.xai.sudokupro.model.EnhancedMove} whose bean alias {@code getValue()}
     * emitted a {@code "value"} property the record's canonical creator does not accept —
     * so deserialization threw {@code UnrecognizedPropertyException: "value"}.
     * {@code @JsonIgnoreProperties} sits on SudokuBoard, not on the nested record, so it
     * did not help. Because {@code GameService.getGame} does not guard the Redis read, the
     * exception propagated instead of falling back to the database: every read of a played
     * game 500'd for the whole cache TTL once it left the in-memory active set (pod
     * restart, eviction, or a second replica).
     */
    @Test
    void aPlayedBoardSurvivesTheJacksonCacheRoundTrip() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

        SudokuBoard board = new SudokuBoard(1, false, false, 0, "cache-rt");
        board.setPlayerId("p1");
        // one genuine move, which is all it took to poison the cache entry
        outer:
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (board.getBoard()[r][c].getValue() == 0)
                    for (int v = 1; v <= 9; v++)
                        if (board.isValidMove(r, c, v)) {
                            board.makeMove(r, c, v, SudokuCell.MoveSource.PLAYER);
                            break outer;
                        }
        assertEquals(1, board.getMoveCount());

        String json = mapper.writeValueAsString(board);
        SudokuBoard restored = assertDoesNotThrow(() -> mapper.readValue(json, SudokuBoard.class),
            "a played board must survive the Redis serializer");

        assertEquals(board.getMoveCount(), restored.getMoveCount());
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                assertEquals(board.getBoard()[r][c].getValue(), restored.getBoard()[r][c].getValue(),
                    "cell (" + r + "," + c + ") must round-trip");
    }

    /** Old cache entries still carry the "value" alias; they must not break on read. */
    @Test
    void legacyCacheEntriesCarryingTheValueAliasStillDeserialize() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();
        EnhancedMove move = assertDoesNotThrow(() -> mapper.readValue(
            "{\"row\":1,\"col\":2,\"oldVal\":0,\"newVal\":5,\"value\":5,\"source\":\"PLAYER\"}",
            EnhancedMove.class));
        assertEquals(5, move.newVal());
    }

    /**
     * Regression: {@code snapshotCells} omitted each cell's {@code strategy}.
     * {@code calculateCosmicDripLevel()} counts cells whose strategy is COSMIC or
     * STARFORGE, so every board restored from the database or the Redis cache recomputed
     * a drip level of 0 — the value was silently wiped on any round-trip.
     */
    @Test
    void cellStrategySurvivesTheSnapshotRoundTrip() {
        SudokuBoard board = new SudokuBoard(1, false, false, 0, "strategy-rt");
        int marked = 0;
        outer:
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (board.getBoard()[r][c].getValue() == 0) {
                    board.getBoard()[r][c].setStrategy(SudokuCell.Strategy.COSMIC);
                    if (++marked == 3) break outer;
                }
        assertEquals(3, marked, "test setup: three cells marked COSMIC");

        String snapshot = board.snapshotCells();
        SudokuBoard restored = new SudokuBoard(1, false, false, 0, "strategy-target");
        restored.restoreCells(snapshot);

        int cosmic = 0;
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (restored.getBoard()[r][c].getStrategy() == SudokuCell.Strategy.COSMIC) cosmic++;
        assertEquals(3, cosmic, "strategy must round-trip, or cosmicDripLevel resets to 0");
    }

    /** Snapshots written before "st" existed must still load. */
    @Test
    void snapshotsWithoutAStrategyKeyStillRestore() {
        SudokuBoard board = new SudokuBoard(1, false, false, 0, "legacy-st");
        String legacy = board.snapshotCells().replaceAll(",\"st\":\"[A-Z_]+\"", "");
        SudokuBoard restored = new SudokuBoard(1, false, false, 0, "legacy-target");
        assertDoesNotThrow(() -> restored.restoreCells(legacy));
    }
}
