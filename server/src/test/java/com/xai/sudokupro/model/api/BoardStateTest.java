package com.xai.sudokupro.model.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xai.sudokupro.model.SudokuBoard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The wire representation must carry exactly the player-visible state — and
 * must never leak the solution (the client/server separation depends on the
 * server staying authoritative).
 */
class BoardStateTest {

    // Lenient like the desktop client's mapper: SudokuCellView also serializes
    // derived getters (displayValue, hintDisplay, ...) that the record ctor ignores.
    private final ObjectMapper mapper = new ObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void serializedStateContainsNoSolutionAndFullGrid() throws Exception {
        SudokuBoard board = new SudokuBoard(2, false, false, 0, "game-42");

        JsonNode json = mapper.readTree(mapper.writeValueAsString(BoardState.from(board)));

        assertEquals("game-42", json.get("gameId").asText());
        assertEquals(9, json.get("cells").size());
        assertEquals(9, json.get("cells").get(0).size());
        String raw = json.toString().toLowerCase();
        assertFalse(raw.contains("solution"), "wire format must not mention the solution");
        assertFalse(raw.contains("solvedgrid"), "wire format must not embed a solved grid");
        // A difficulty-2 puzzle has empty cells: the grid is the puzzle, not the answer.
        int empty = 0;
        for (JsonNode row : json.get("cells"))
            for (JsonNode cell : row)
                if (cell.get("value").asInt() == 0) empty++;
        assertTrue(empty >= 28, "expected removed cells, found " + empty);
    }

    @Test
    void toBoardRoundTripsValuesGivensAndIdentity() {
        SudokuBoard original = new SudokuBoard(1, true, false, 0, "game-rt");
        original.getBoard()[0][0].addPencilMark(5); // client-visible annotation

        SudokuBoard copy = BoardState.from(original).toBoard();

        assertEquals("game-rt", copy.getGameId());
        assertTrue(copy.isChaosMode());
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++) {
                assertEquals(original.getBoard()[r][c].getValue(), copy.getBoard()[r][c].getValue(),
                    "value at " + r + "," + c);
                assertEquals(original.getBoard()[r][c].isGiven(), copy.getBoard()[r][c].isGiven(),
                    "given flag at " + r + "," + c);
            }
        assertEquals(original.getBoard()[0][0].getPencilMarks(), copy.getBoard()[0][0].getPencilMarks());
    }

    @Test
    void toBoardCarriesPlayProgressCounters() {
        // Regression: these were dropped, so every server resync (resume,
        // undo/redo "board" envelopes) reset the client's Moves/Hints/Score to 0.
        SudokuBoard original = new SudokuBoard(1, false, false, 0, "game-counters");
        original.setScore(70);
        original.setMoveCount(13);
        original.setHintCount(2);
        original.setLives(2);

        SudokuBoard copy = BoardState.from(original).toBoard();

        assertEquals(70, copy.getScore());
        assertEquals(13, copy.getMoveCount());
        assertEquals(2, copy.getHintCount());
        assertEquals(2, copy.getLives());
    }

    @Test
    void jsonRoundTripsThroughSharedRecord() throws Exception {
        SudokuBoard board = new SudokuBoard(1, false, true, 0, "game-json");
        String wire = mapper.writeValueAsString(BoardState.from(board));

        BoardState parsed = mapper.readValue(wire, BoardState.class);

        assertEquals("game-json", parsed.gameId());
        assertTrue(parsed.mirrorMode());
        assertEquals(board.getBoard()[4][4].getValue(), parsed.cells().get(4).get(4).value());
    }
}
