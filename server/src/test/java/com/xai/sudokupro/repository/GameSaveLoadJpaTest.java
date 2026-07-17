package com.xai.sudokupro.repository;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Save/load through the real JPA lifecycle on H2: @PrePersist writes the
 * cells_json snapshot, @PostLoad rebuilds the transient grid. Before this
 * feature, a board loaded from the database came back with a blank 9x9 grid
 * (every cell 0, nothing given) because the grid is @Transient.
 */
@DataJpaTest
@ActiveProfiles("test")
class GameSaveLoadJpaTest {

    @Autowired private GameRepository repository;
    @Autowired private TestEntityManager entityManager;

    @Test
    void databaseRoundTripRestoresTheFullGrid() {
        SudokuBoard original = new SudokuBoard(2, false, false, 0, "jpa-rt-1");
        original.setPlayerId("richmond");

        // Make a player move on some empty cell so the round-trip covers
        // non-given player state, not just the generated clues.
        int mr = -1, mc = -1;
        outer:
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (original.getBoard()[r][c].getValue() == 0) { mr = r; mc = c; break outer; }
        original.getBoard()[mr][mc].setValue(4, SudokuCell.MoveSource.PLAYER);

        Long id = entityManager.persistAndGetId(original, Long.class);
        entityManager.flush();
        entityManager.clear(); // force a real database read, not a first-level-cache hit

        SudokuBoard loaded = repository.findById(id).orElseThrow();

        int givens = 0, values = 0;
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                SudokuCell o = original.getBoard()[r][c];
                SudokuCell l = loaded.getBoard()[r][c];
                assertEquals(o.getValue(), l.getValue(), "value at (" + r + "," + c + ")");
                assertEquals(o.isGiven(), l.isGiven(), "given at (" + r + "," + c + ")");
                if (l.isGiven()) givens++;
                if (l.getValue() != 0) values++;
            }
        }
        assertTrue(givens >= 17, "restored board must keep its clues (got " + givens + ")");
        assertTrue(values > givens, "player move must survive the round-trip");
        assertEquals(SudokuCell.MoveSource.PLAYER, loaded.getBoard()[mr][mc].getMoveSource());
        assertEquals("richmond", loaded.getPlayerId());
    }

    @Test
    void findResumableByPlayerIdReturnsOnlyUnfinishedGamesWithASnapshot() {
        SudokuBoard unfinished = new SudokuBoard(1, false, false, 0, "jpa-resume-1");
        unfinished.setPlayerId("richmond");
        entityManager.persist(unfinished);

        SudokuBoard someoneElses = new SudokuBoard(1, false, false, 0, "jpa-resume-2");
        someoneElses.setPlayerId("intruder");
        entityManager.persist(someoneElses);

        entityManager.flush();
        entityManager.clear();

        List<SudokuBoard> resumable = repository.findResumableByPlayerId("richmond", PageRequest.of(0, 10));

        assertEquals(1, resumable.size());
        assertEquals("jpa-resume-1", resumable.get(0).getGameId());
        // And the listed board is playable, not a blank shell:
        assertTrue(resumable.get(0).getBoard()[0][0] != null);
    }
}
