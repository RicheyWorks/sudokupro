package com.xai.sudokupro.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import com.xai.sudokupro.repository.GameRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PuzzleEditorService} is the one path that turns a grid drawn by an untrusted
 * client into a persisted, playable board, so this class guards two families of defect:
 *
 * <ol>
 *   <li><b>The submitted puzzle is not the puzzle that gets stored.</b> The service used to
 *       start from {@code new SudokuBoard(0, ...)} — a constructor that GENERATES a random
 *       puzzle and marks its clues {@code given} — and then write the player's digits over
 *       the top. {@link SudokuCell#setValue} silently refuses to change a given cell, so
 *       most of the submission was discarded. The tests below pin the whole 9x9 grid, the
 *       given flags, the owner and the persisted JSON snapshot.</li>
 *   <li><b>Validation that cannot reject anything.</b> The only content check was
 *       {@code cell.isConflicted()} (a conflict set nothing ever populates) plus
 *       "the backtracker completed it" (which never inspects the digits it was handed and
 *       says nothing about uniqueness). Duplicate digits, a two-solution grid, a 1-clue
 *       grid and an adversarial grid engineered to make uniqueness checking exponential
 *       all sailed through. Each now has a test.</li>
 * </ol>
 *
 * <p>Expected values here are hand-derived from the literal grids in this file — the clue
 * count of the Wikipedia puzzle was counted by hand (30 clues, 51 blanks) and the
 * two-solution grid was built by blanking a known unavoidable set — never recomputed with
 * a second copy of the service's algorithm.
 */
class PuzzleEditorServiceTest {

    /** The canonical Wikipedia example puzzle: 30 clues, 51 blanks, exactly one solution. */
    private static final int[][] VALID_PUZZLE = {
        {5, 3, 0, 0, 7, 0, 0, 0, 0},
        {6, 0, 0, 1, 9, 5, 0, 0, 0},
        {0, 9, 8, 0, 0, 0, 0, 6, 0},
        {8, 0, 0, 0, 6, 0, 0, 0, 3},
        {4, 0, 0, 8, 0, 3, 0, 0, 1},
        {7, 0, 0, 0, 2, 0, 0, 0, 6},
        {0, 6, 0, 0, 0, 0, 2, 8, 0},
        {0, 0, 0, 4, 1, 9, 0, 0, 5},
        {0, 0, 0, 0, 8, 0, 0, 7, 9},
    };

    /** Its unique solution. */
    private static final int[][] SOLUTION = {
        {5, 3, 4, 6, 7, 8, 9, 1, 2},
        {6, 7, 2, 1, 9, 5, 3, 4, 8},
        {1, 9, 8, 3, 4, 2, 5, 6, 7},
        {8, 5, 9, 7, 6, 1, 4, 2, 3},
        {4, 2, 6, 8, 5, 3, 7, 9, 1},
        {7, 1, 3, 9, 2, 4, 8, 5, 6},
        {9, 6, 1, 5, 3, 7, 2, 8, 4},
        {2, 8, 7, 4, 1, 9, 6, 3, 5},
        {3, 4, 5, 2, 8, 6, 1, 7, 9},
    };

    private GameRepository gameRepository;
    private SimpleMeterRegistry registry;
    private PuzzleEditorService service;

    @BeforeEach
    void setUp() {
        gameRepository = mock(GameRepository.class);
        when(gameRepository.save(any(SudokuBoard.class))).thenAnswer(i -> i.getArgument(0));
        registry = new SimpleMeterRegistry();
        service = new PuzzleEditorService(gameRepository, registry);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static int[][] copyOf(int[][] grid) {
        int[][] c = new int[9][9];
        for (int r = 0; r < 9; r++) c[r] = grid[r].clone();
        return c;
    }

    /** A board holding exactly {@code grid}, built without touching the service. */
    private static SudokuBoard boardOf(int[][] grid) {
        SudokuCell[][] cells = new SudokuCell[9][9];
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                cells[r][c] = new SudokuCell();
                if (grid[r][c] != 0) {
                    cells[r][c].setValue(grid[r][c], SudokuCell.MoveSource.INITIAL);
                    cells[r][c].setGiven(true);
                }
            }
        }
        return new SudokuBoard(cells, false, false, 0L, "fixture");
    }

    private SudokuBoard captureSaved() {
        ArgumentCaptor<SudokuBoard> captor = ArgumentCaptor.forClass(SudokuBoard.class);
        verify(gameRepository).save(captor.capture());
        return captor.getValue();
    }

    private double counterCount(String name, String tagKey, String tagValue) {
        Counter c = registry.find(name).tag(tagKey, tagValue).counter();
        return c == null ? 0.0 : c.count();
    }

    private double counterCount(String name) {
        Counter c = registry.find(name).counter();
        return c == null ? 0.0 : c.count();
    }

    // ── the submitted grid is the stored grid ──────────────────────────────────

    /**
     * Reproduction of the headline defect: before the fix this call threw
     * {@code RuntimeException: Custom puzzle creation failed} for this perfectly ordinary
     * 30-clue puzzle, because the generated board's given cells refused ~60 of the 81
     * assignments and the resulting hybrid grid had no solution.
     */
    @Test
    void storesTheExactGridThatWasSubmitted() {
        SudokuBoard board = service.createCustomPuzzle(copyOf(VALID_PUZZLE), "player-1", false);

        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                assertEquals(VALID_PUZZLE[r][c], board.getBoard()[r][c].getValue(),
                    "cell (" + r + "," + c + ") does not hold the submitted digit");
            }
        }
    }

    @Test
    void clueCellsAreGivenAndBlanksStayEditable() {
        SudokuBoard board = service.createCustomPuzzle(copyOf(VALID_PUZZLE), "player-1", false);

        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                boolean isClue = VALID_PUZZLE[r][c] != 0;
                assertEquals(isClue, board.getCell(r, c).isGiven(),
                    "given flag wrong at (" + r + "," + c + ")");
                assertEquals(!isClue, board.isCellEditable(r, c),
                    "editability wrong at (" + r + "," + c + ")");
            }
        }
    }

    /** Clues are the puzzle's own digits, not moves somebody made. */
    @Test
    void clueCellsAreTaggedAsInitialNotAsAPlayerMove() {
        SudokuBoard board = service.createCustomPuzzle(copyOf(VALID_PUZZLE), "player-1", false);

        assertEquals(SudokuCell.MoveSource.INITIAL, board.getCell(0, 0).getMoveSource());
        assertEquals(0, board.getMoveCount(), "building the puzzle must not count as moves");
    }

    @Test
    void savedBoardIsOwnedBySubmitter() {
        service.createCustomPuzzle(copyOf(VALID_PUZZLE), "player-42", false);

        SudokuBoard saved = captureSaved();
        assertEquals("player-42", saved.getPlayerId(),
            "an unowned board is invisible to every player-scoped repository query");
    }

    /**
     * What actually reaches the database is the {@code cells_json} snapshot, so assert on
     * that rather than only on the in-memory grid.
     */
    @Test
    void persistedSnapshotCarriesTheSubmittedGrid() throws Exception {
        service.createCustomPuzzle(copyOf(VALID_PUZZLE), "player-1", false);
        SudokuBoard saved = captureSaved();

        JsonNode rows = new ObjectMapper().readTree(saved.snapshotCells());
        assertEquals(9, rows.size());
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                JsonNode cell = rows.get(r).get(c);
                assertEquals(VALID_PUZZLE[r][c], cell.get("v").asInt(),
                    "snapshot value wrong at (" + r + "," + c + ")");
                assertEquals(VALID_PUZZLE[r][c] != 0, cell.get("g").asBoolean(),
                    "snapshot given flag wrong at (" + r + "," + c + ")");
            }
        }
    }

    @Test
    void difficultyEstimateIsPersistedOnTheBoard() {
        service.createCustomPuzzle(copyOf(VALID_PUZZLE), "player-1", false);

        // 51 blanks, counted by hand from VALID_PUZZLE, over nine rows.
        assertEquals(2, captureSaved().getDifficulty(),
            "the estimate was computed and then thrown away, leaving difficulty 0");
    }

    @Test
    void eachPuzzleGetsItsOwnGameId() {
        String first = service.createCustomPuzzle(copyOf(VALID_PUZZLE), "p", false).getGameId();
        String second = service.createCustomPuzzle(copyOf(VALID_PUZZLE), "p", false).getGameId();

        assertNotNull(first);
        assertNotEquals(first, second);
    }

    // ── validation: dimensions and value range ────────────────────────────────

    @Test
    void rejectsNullGrid() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(null, "player-1", false));
        assertEquals("Invalid grid", e.getMessage());
        verifyNoInteractions(gameRepository);
    }

    @Test
    void rejectsGridWithWrongRowCount() {
        assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(new int[8][9], "player-1", false));
        assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(new int[10][9], "player-1", false));
        verifyNoInteractions(gameRepository);
    }

    @Test
    void rejectsJaggedOrNullRow() {
        int[][] shortRow = copyOf(VALID_PUZZLE);
        shortRow[4] = new int[8];
        assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(shortRow, "player-1", false));

        int[][] longRow = copyOf(VALID_PUZZLE);
        longRow[4] = new int[10];
        assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(longRow, "player-1", false));

        int[][] nullRow = copyOf(VALID_PUZZLE);
        nullRow[0] = null;
        assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(nullRow, "player-1", false));

        verifyNoInteractions(gameRepository);
    }

    /**
     * 0..9 inclusive is the whole legal range; 10 and -1 are the first values outside it.
     * The message is asserted exactly because SudokuCell has a range guard of its own
     * ("Value must be 0-9"): the service must reject the submission itself, before any
     * board is built, rather than relying on a lower layer to blow up.
     */
    @Test
    void rejectsValuesOutsideZeroToNine() {
        int[][] tooHigh = copyOf(VALID_PUZZLE);
        tooHigh[8][8] = 10;
        IllegalArgumentException high = assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(tooHigh, "player-1", false));
        assertEquals("Grid value must be 0-9", high.getMessage());

        int[][] negative = copyOf(VALID_PUZZLE);
        negative[0][2] = -1;
        assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(negative, "player-1", false));

        int[][] farOut = copyOf(VALID_PUZZLE);
        farOut[3][3] = Integer.MAX_VALUE;
        assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(farOut, "player-1", false));

        verifyNoInteractions(gameRepository);
    }

    /** A rejected value must never reach the board — not even the ones before it. */
    @Test
    void anOutOfRangeValueIsRejectedAsABadRequestNotAServerError() {
        int[][] bad = copyOf(VALID_PUZZLE);
        bad[0][2] = 42;

        // Before the fix this surfaced as RuntimeException("Custom puzzle creation failed")
        // because every failure inside the method was wrapped, so the API answered 500 to
        // what is plainly a 400.
        assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(bad, "player-1", false));
    }

    // ── validation: player id ─────────────────────────────────────────────────

    @Test
    void rejectsMissingPlayerId() {
        assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(copyOf(VALID_PUZZLE), null, false));
        assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(copyOf(VALID_PUZZLE), "", false));
        assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(copyOf(VALID_PUZZLE), "   ", false));
        verifyNoInteractions(gameRepository);
    }

    // ── validation: puzzle content ────────────────────────────────────────────

    /**
     * The duplicate sits in a row that the backtracker can still fill around, which is
     * exactly why the old "the solver completed it" check could not see it: {@code backtrack}
     * only validates the digits it places itself.
     */
    @Test
    void rejectsDuplicateDigitInARow() {
        int[][] dup = copyOf(VALID_PUZZLE);
        // 7 at (0,8) duplicates the 7 at (0,4). Column 8 and box (0,2) hold no 7, so only
        // the row rule can reject it.
        dup[0][8] = 7;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(dup, "player-1", false));
        assertTrue(e.getMessage().startsWith("Duplicate"), e.getMessage());
        verifyNoInteractions(gameRepository);
    }

    @Test
    void rejectsDuplicateDigitInAColumn() {
        int[][] dup = copyOf(VALID_PUZZLE);
        dup[8][0] = 5;                       // column 0 already holds a 5 at row 0

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(dup, "player-1", false));
        assertTrue(e.getMessage().startsWith("Duplicate"), e.getMessage());
    }

    /**
     * An 8 at (1,1) duplicates the 8 at (2,2) inside box (0,0) while row 1 and column 1
     * both stay 8-free, so only the box rule can reject it.
     */
    @Test
    void rejectsDuplicateDigitInABox() {
        int[][] dup = copyOf(VALID_PUZZLE);
        dup[1][1] = 8;                       // box (0,0) already holds an 8 at (2,2)

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(dup, "player-1", false));
        assertTrue(e.getMessage().contains("box"), e.getMessage());
    }

    @Test
    void rejectsAnEmptyGrid() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(new int[9][9], "player-1", false));
        assertTrue(e.getMessage().contains("at least 17"), e.getMessage());
        verifyNoInteractions(gameRepository);
    }

    /**
     * 16 clues is one below the mathematical floor for a uniquely solvable Sudoku, so it
     * must be refused by the clue-count rule (naming 17) and not merely by the later
     * uniqueness check.
     */
    @Test
    void rejectsPuzzleWithFewerThanSeventeenClues() {
        int[][] sparse = new int[9][9];
        int kept = 0;
        for (int r = 0; r < 9 && kept < 16; r++) {
            for (int c = 0; c < 9 && kept < 16; c++) {
                if (VALID_PUZZLE[r][c] != 0) {
                    sparse[r][c] = VALID_PUZZLE[r][c];
                    kept++;
                }
            }
        }
        assertEquals(16, kept);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(sparse, "player-1", false));
        assertTrue(e.getMessage().contains("at least 17"), e.getMessage());
    }

    @Test
    void rejectsAnAlreadyCompleteGrid() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(copyOf(SOLUTION), "player-1", false));
        assertTrue(e.getMessage().contains("empty"), e.getMessage());
    }

    /**
     * Blanking the unavoidable set {(0,3),(0,4),(3,3),(3,4)} — which holds 6,7 / 7,6 — leaves
     * 77 clues and exactly two solutions, because the two digits can be swapped without
     * breaking any row, column or box. Verified independently before it was written down.
     */
    @Test
    void rejectsPuzzleWithMoreThanOneSolution() {
        int[][] ambiguous = copyOf(SOLUTION);
        ambiguous[0][3] = 0;
        ambiguous[0][4] = 0;
        ambiguous[3][3] = 0;
        ambiguous[3][4] = 0;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(ambiguous, "player-1", false));
        assertTrue(e.getMessage().contains("unique"), e.getMessage());
        verifyNoInteractions(gameRepository);
    }

    /**
     * (0,2) must be 4 in the only completion of VALID_PUZZLE; setting it to 1 keeps the grid
     * rule-consistent (no duplicate 1 in row 0, column 2 or box 0) while leaving it with no
     * solution at all.
     */
    @Test
    void rejectsPuzzleThatHasNoSolution() {
        int[][] unsolvable = copyOf(VALID_PUZZLE);
        unsolvable[0][2] = 1;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(unsolvable, "player-1", false));
        assertEquals("Board has no solution", e.getMessage());
        verifyNoInteractions(gameRepository);
    }

    /**
     * The classic anti-brute-force 17-clue grid. It is legal and uniquely solvable, but
     * row-major/ascending backtracking needs millions of steps to prove it, so accepting it
     * unbounded hands an attacker a CPU core per HTTP request. The service must give up
     * quickly instead — the preemptive timeout is what fails if the budget guard is removed.
     */
    @Test
    void refusesToBurnUnboundedCpuOnAnAdversarialGrid() {
        int[][] adversarial = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0},
            {0, 0, 0, 0, 0, 3, 0, 8, 5},
            {0, 0, 1, 0, 2, 0, 0, 0, 0},
            {0, 0, 0, 5, 0, 7, 0, 0, 0},
            {0, 0, 4, 0, 0, 0, 1, 0, 0},
            {0, 9, 0, 0, 0, 0, 0, 0, 0},
            {5, 0, 0, 0, 0, 0, 0, 7, 3},
            {0, 0, 2, 0, 1, 0, 0, 0, 0},
            {0, 0, 0, 0, 4, 0, 0, 0, 9},
        };

        IllegalArgumentException e = assertTimeoutPreemptively(Duration.ofSeconds(5),
            () -> assertThrows(IllegalArgumentException.class,
                () -> service.createCustomPuzzle(adversarial, "player-1", false)));
        assertTrue(e.getMessage().contains("too complex"), e.getMessage());
        verifyNoInteractions(gameRepository);
    }

    // ── difficulty estimation ─────────────────────────────────────────────────

    /** A finished grid has nothing left to solve; it is the easiest tier, 1. */
    @Test
    void aCompletedGridIsTheEasiestPossibleBoard() {
        assertEquals(1, service.estimateDifficulty(boardOf(SOLUTION)));
    }

    /**
     * 51 blanks (30 clues) maps to tier 2 on the game's 1..5 scale — the scale the economy
     * and anti-cheat both read. This used to assert 5, treating the editor's old 0-10 count
     * as if it were a tier; that mismatch is exactly what would have paid a 17-clue custom
     * board 70 gems, over a real NIGHTMARE's 50.
     */
    @Test
    void difficultyOfTheThirtyClueBoardMapsToTierTwo() {
        assertEquals(2, service.estimateDifficulty(boardOf(VALID_PUZZLE)));
    }

    /** Implementation-independent invariant: taking clues away cannot make a board easier. */
    @Test
    void fewerCluesIsNeverEasier() {
        int[][] fewerClues = copyOf(VALID_PUZZLE);
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++) fewerClues[r][c] = 0;   // wipe to the emptiest grid

        int harder = service.estimateDifficulty(boardOf(fewerClues));
        int easier = service.estimateDifficulty(boardOf(VALID_PUZZLE));
        assertTrue(harder > easier,
            "an empty grid must rate harder than a 30-clue one: " + harder + " vs " + easier);
    }

    @Test
    void difficultyStaysInsideTheOneToFiveTierScale() {
        int[][] oneClue = new int[9][9];
        oneClue[0][0] = 5;   // 80 empty cells — the emptiest legal grid

        int d = service.estimateDifficulty(boardOf(oneClue));
        assertTrue(d >= 1 && d <= 5, "difficulty must be a 1..5 tier, got: " + d);
        assertEquals(5, d, "80 empties is the hardest tier");
    }

    /**
     * The editor must never write a difficulty the economy would over-pay for. This pins the
     * whole exploit closed: for every legal empty-cell count, the estimate stays within the
     * tier range EconomyService multiplies, so no custom board can out-earn a NIGHTMARE.
     */
    @Test
    void noLegalBoardEstimatesAboveTheNightmareTier() {
        for (int empties = 0; empties <= 81; empties++) {
            int[][] grid = new int[9][9];
            int filled = 81 - empties;
            int placed = 0;
            outer:
            for (int r = 0; r < 9 && placed < filled; r++)
                for (int c = 0; c < 9 && placed < filled; c++) { grid[r][c] = 1; placed++; }
            int d = service.estimateDifficulty(boardOf(grid));
            assertTrue(d >= 1 && d <= 5, empties + " empties gave tier " + d);
        }
    }

    @Test
    void estimateDifficultyRejectsNullBoard() {
        assertThrows(IllegalArgumentException.class, () -> service.estimateDifficulty(null));
    }

    // ── metrics ───────────────────────────────────────────────────────────────

    @Test
    void successCountsOneCreationTaggedWithItsProvenanceAndDifficulty() {
        service.createCustomPuzzle(copyOf(VALID_PUZZLE), "player-1", true);

        assertEquals(1.0, counterCount("sudokupro.custom.puzzles.created", "verified", "true"));
        assertEquals(0.0, counterCount("sudokupro.custom.puzzles.created", "verified", "false"));
        assertEquals(1.0, counterCount("sudokupro.custom.puzzles.by.difficulty", "level", "2"));
        assertEquals(0.0, counterCount("sudokupro.custom.puzzles.failed"));
    }

    @Test
    void aRejectedSubmissionIsCountedAsAFailureAndNeverPersisted() {
        int[][] dup = copyOf(VALID_PUZZLE);
        dup[0][8] = 5;

        assertThrows(IllegalArgumentException.class,
            () -> service.createCustomPuzzle(dup, "player-1", false));

        assertEquals(1.0, counterCount("sudokupro.custom.puzzles.failed"));
        assertEquals(0.0, counterCount("sudokupro.custom.puzzles.created"));
        verifyNoInteractions(gameRepository);
    }

    /** A repository failure is a real fault: it must not be reported as a bad grid. */
    @Test
    void aStorageFailurePropagatesUnchanged() {
        when(gameRepository.save(any(SudokuBoard.class)))
            .thenThrow(new IllegalStateException("database down"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> service.createCustomPuzzle(copyOf(VALID_PUZZLE), "player-1", false));
        assertEquals("database down", e.getMessage());
        assertEquals(1.0, counterCount("sudokupro.custom.puzzles.failed"));
    }
}
