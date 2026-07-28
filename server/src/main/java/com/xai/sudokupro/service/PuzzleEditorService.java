package com.xai.sudokupro.service;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.util.Constants;
import com.xai.sudokupro.model.SudokuCell;
import com.xai.sudokupro.repository.GameRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Accepts player-authored Sudoku grids and turns them into playable boards.
 *
 * <p>Everything this class receives is untrusted input, so validation happens on a
 * private copy of the grid and every rejection is an {@link IllegalArgumentException}
 * (a 400, not a 500). A grid is only accepted when it is 9x9, holds digits 0-9 only,
 * has no duplicate digit in any row, column or box, carries at least
 * {@link #MIN_GIVENS} clues, leaves at least one cell to solve, and has exactly one
 * solution reachable inside {@link #MAX_SEARCH_NODES} search steps.
 */
@Service
public class PuzzleEditorService {
    private static final Logger logger = LoggerFactory.getLogger(PuzzleEditorService.class);
    private static final int GRID_SIZE = 9;
    private static final int CELL_COUNT = GRID_SIZE * GRID_SIZE;
    private static final Tags GLOBAL_TAGS = Tags.of("app", "SudokuPro");

    /**
     * No Sudoku with fewer than 17 clues has a unique solution (McGuire, Tugemann and
     * Civario, 2012), so anything below this is rejected before the solver is ever
     * entered — that also keeps a near-empty grid from being accepted as a "puzzle".
     */
    public static final int MIN_GIVENS = 17;

    /**
     * Hard ceiling on backtracking steps spent verifying one submitted grid.
     *
     * <p>Uniqueness checking is exponential in the worst case and the grid comes from the
     * network. The canonical anti-brute-force 17-clue grid needs well over six million
     * steps in this (row-major, ascending-digit) search order, i.e. one submission can pin
     * a core for as long as the attacker likes; a legitimate 30-clue puzzle needs about
     * seven thousand. One million steps is ~140x the honest cost and still milliseconds.
     */
    public static final long MAX_SEARCH_NODES = 1_000_000L;

    private final GameRepository gameRepository;
    private final MeterRegistry meterRegistry;

    /**
     * {@code AISolverService} used to be injected here and
     * {@code aiSolverService.solveSudoku(probe)} was the only content check. It was the
     * wrong tool twice over: it answers "can this be completed" but not "is the completion
     * unique", and it fills the board it is handed (AUTOSOLVE moves), so a validator had to
     * build a throwaway probe board to avoid destroying the caller's puzzle. The bounded
     * solution counter below answers both questions in a single pass and mutates nothing.
     */
    @Autowired
    public PuzzleEditorService(GameRepository gameRepository, MeterRegistry meterRegistry) {
        this.gameRepository = Objects.requireNonNull(gameRepository);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    /**
     * Validates {@code customGrid} and persists it as a new board owned by {@code playerId}.
     *
     * @param isAdminVerified provenance of the submission. It grants no validation bypass
     *                        whatsoever — it is recorded as a metric tag so the split
     *                        between curated and player-submitted content is visible.
     * @throws IllegalArgumentException if the grid or the player id is unacceptable
     */
    public SudokuBoard createCustomPuzzle(int[][] customGrid, String playerId, boolean isAdminVerified) {
        try {
            validatePlayerId(playerId);
            int[][] grid = validatedCopy(customGrid);

            int solutions = countSolutions(grid);
            if (solutions == 0) {
                throw new IllegalArgumentException("Board has no solution");
            }
            if (solutions > 1) {
                throw new IllegalArgumentException("Board does not have a unique solution");
            }

            SudokuBoard board = buildBoard(grid, UUID.randomUUID().toString(), playerId);
            // Persist the estimate: the board's own difficulty column feeds anti-cheat
            // timing thresholds and the difficulty tuner, and it stayed 0 for every custom
            // puzzle because the estimate was computed after the save and only ever used
            // as a metric tag.
            int difficulty = estimateDifficulty(board);
            board.setDifficulty(difficulty);

            gameRepository.save(board);

            meterRegistry.counter("sudokupro.custom.puzzles.created",
                Tags.concat(GLOBAL_TAGS, Tags.of("verified", String.valueOf(isAdminVerified)))
            ).increment();

            meterRegistry.counter(
                "sudokupro.custom.puzzles.by.difficulty",
                Tags.concat(GLOBAL_TAGS, Tags.of("level", String.valueOf(difficulty)))
            ).increment();

            logger.info("Custom puzzle {} created by {} (difficulty {})",
                board.getGameId(), playerId, difficulty);
            return board;

        } catch (RuntimeException e) {
            meterRegistry.counter("sudokupro.custom.puzzles.failed", GLOBAL_TAGS).increment();
            // Rethrow unchanged. Wrapping every failure in a RuntimeException turned each
            // rejected submission ("value must be 0-9", "no solution") into an opaque
            // server error for the caller, and hid the real cause of genuine faults.
            throw e;
        }
    }

    /**
     * How hard the board is to finish, 0 (nothing left to do) to 10.
     *
     * <p>Returns a tier on the game's 1..5 scale ({@code Constants.Difficulty}), NOT a raw
     * 0-10 count. It used to return {@code emptyCells / 9}, a 0-10 value on a completely
     * different scale from the 1-5 tiers every other producer of the {@code difficulty}
     * column uses — and the column is what {@code EconomyService} multiplies for the solve
     * payout ({@code max(1, difficulty) * 10} gems and XP) and what the anti-cheat
     * fast-solve window scales by. A 17-clue custom puzzle (64 empty cells) scored 7, paying
     * 70 gems+XP against the 50 ceiling of a real NIGHTMARE board — a farmable 40% premium
     * on a grid the author already knows the answer to, plus a widened cheat window. Mapping
     * to the real tier scale removes the premium and keeps the value in the range every
     * consumer expects. Thresholds follow the generator's own per-tier removal counts
     * ({@code EASY 40, MEDIUM 48, HARD 52, EXTREME 54, NIGHTMARE 55}).
     */
    public int estimateDifficulty(SudokuBoard board) {
        if (board == null) {
            throw new IllegalArgumentException("Board must not be null");
        }
        int emptyCells = (int) Arrays.stream(board.getBoardCopy())
            .flatMap(Arrays::stream)
            .filter(cell -> cell.getValue() == 0)
            .count();

        if (emptyCells >= Constants.Difficulty.NIGHTMARE.cellsRemoved) return 5;
        if (emptyCells >= Constants.Difficulty.EXTREME.cellsRemoved)   return 4;
        if (emptyCells >= Constants.Difficulty.HARD.cellsRemoved)      return 3;
        if (emptyCells >= Constants.Difficulty.MEDIUM.cellsRemoved)    return 2;
        return 1;
    }

    private void validatePlayerId(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("Player ID must not be null or blank");
        }
    }

    /**
     * Validates the submitted grid and returns a private copy of it.
     *
     * <p>The copy is not politeness: the caller keeps a reference to the array it passed
     * in, so validating and then reading the caller's array again would let a concurrent
     * writer swap a legal grid for an illegal one between the check and the use.
     */
    private int[][] validatedCopy(int[][] grid) {
        if (grid == null || grid.length != GRID_SIZE) {
            throw new IllegalArgumentException("Invalid grid");
        }

        int[][] copy = new int[GRID_SIZE][GRID_SIZE];
        int givens = 0;
        for (int r = 0; r < GRID_SIZE; r++) {
            int[] row = grid[r];
            if (row == null || row.length != GRID_SIZE) {
                throw new IllegalArgumentException("Invalid row");
            }
            for (int c = 0; c < GRID_SIZE; c++) {
                int value = row[c];
                if (value < 0 || value > 9) {
                    throw new IllegalArgumentException("Grid value must be 0-9");
                }
                copy[r][c] = value;
                if (value != 0) givens++;
            }
        }

        if (givens < MIN_GIVENS) {
            throw new IllegalArgumentException(
                "Puzzle needs at least " + MIN_GIVENS + " given cells, got " + givens);
        }
        if (givens == CELL_COUNT) {
            throw new IllegalArgumentException("Puzzle must leave at least one cell empty");
        }
        requireNoDuplicates(copy);
        return copy;
    }

    /**
     * Rejects a grid that already breaks Sudoku's rules.
     *
     * <p>This is the check that used to be {@code board.getCell(i, j).isConflicted()}, which
     * could never fire: a cell's conflict set is only populated by an explicit
     * {@code addConflict} call and {@code setValue} clears it, so a freshly built board
     * reports zero conflicts no matter what is in it. Nor does the solver catch it —
     * backtracking only validates the digits it places itself, so a row holding two 5s is
     * happily "completed" around them.
     */
    private void requireNoDuplicates(int[][] g) {
        for (int r = 0; r < GRID_SIZE; r++) {
            for (int c = 0; c < GRID_SIZE; c++) {
                int v = g[r][c];
                if (v == 0) continue;
                for (int i = 0; i < GRID_SIZE; i++) {
                    if (i != c && g[r][i] == v) {
                        throw new IllegalArgumentException("Duplicate " + v + " in row " + r);
                    }
                    if (i != r && g[i][c] == v) {
                        throw new IllegalArgumentException("Duplicate " + v + " in column " + c);
                    }
                }
                int br = r - r % 3, bc = c - c % 3;
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) {
                        if ((br + i != r || bc + j != c) && g[br + i][bc + j] == v) {
                            throw new IllegalArgumentException(
                                "Duplicate " + v + " in box " + br + "," + bc);
                        }
                    }
                }
            }
        }
    }

    /**
     * Builds the board from an already-validated grid.
     *
     * <p>This used to start from {@code new SudokuBoard(0, false, false, 0L, gameId)} and
     * overwrite the cells. That constructor GENERATES a random puzzle and marks its clues
     * {@code given}, and {@link SudokuCell#setValue} silently refuses to change a given
     * cell — so roughly sixty of the eighty-one submitted values were dropped on the floor
     * and the board kept the generator's digits instead. The feature could not work: the
     * frankenboard was normally unsolvable and the whole call blew up, and when it did
     * survive it saved a puzzle the player never drew. Starting from blank cells is the fix.
     */
    private SudokuBoard buildBoard(int[][] grid, String gameId, String playerId) {
        SudokuCell[][] cells = new SudokuCell[GRID_SIZE][GRID_SIZE];
        for (int r = 0; r < GRID_SIZE; r++) {
            for (int c = 0; c < GRID_SIZE; c++) {
                SudokuCell cell = new SudokuCell();
                int value = grid[r][c];
                if (value != 0) {
                    // INITIAL, not PLAYER: these are the puzzle's clues, not moves someone
                    // made. Everything downstream reads move source as provenance.
                    cell.setValue(value, SudokuCell.MoveSource.INITIAL);
                    cell.setGiven(true);
                }
                cells[r][c] = cell;
            }
        }
        SudokuBoard board = new SudokuBoard(cells, false, false, 0L, gameId);
        // Without an owner the row is invisible to every player-scoped query
        // (findByPlayerId, findResumableByPlayerId) — the puzzle was saved and lost.
        board.setPlayerId(playerId);
        return board;
    }

    /** Number of solutions, counted to a maximum of 2 (that is all uniqueness needs). */
    private int countSolutions(int[][] grid) {
        int[][] work = new int[GRID_SIZE][GRID_SIZE];
        for (int r = 0; r < GRID_SIZE; r++) {
            work[r] = Arrays.copyOf(grid[r], GRID_SIZE);
        }
        return search(work, 0, 0, new long[]{MAX_SEARCH_NODES});
    }

    private int search(int[][] g, int idx, int found, long[] budget) {
        if (--budget[0] < 0) {
            throw new IllegalArgumentException("Puzzle is too complex to verify");
        }
        if (found > 1) return found;
        if (idx == CELL_COUNT) return found + 1;

        int r = idx / GRID_SIZE, c = idx % GRID_SIZE;
        if (g[r][c] != 0) return search(g, idx + 1, found, budget);

        for (int v = 1; v <= 9 && found <= 1; v++) {
            if (legal(g, r, c, v)) {
                g[r][c] = v;
                found = search(g, idx + 1, found, budget);
                g[r][c] = 0;
            }
        }
        return found;
    }

    private boolean legal(int[][] g, int row, int col, int v) {
        for (int i = 0; i < GRID_SIZE; i++) {
            if (g[row][i] == v || g[i][col] == v) return false;
        }
        int br = row - row % 3, bc = col - col % 3;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (g[br + i][bc + j] == v) return false;
            }
        }
        return true;
    }
}
