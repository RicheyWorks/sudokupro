package com.xai.sudokupro.model;

import com.xai.sudokupro.util.Constants;
import com.xai.sudokupro.util.SecureRandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The cosmic architect of SudokuPro's grids.
 * Forges puzzles with divine precision and dripping flair—difficulty-tuned, chaos-twisted, mirrored, seeded, and styled for galactic duels.
 */
@Component
public class SudokuGenerator {
    private static final Logger logger = LoggerFactory.getLogger(SudokuGenerator.class);
    private final int size = Constants.BOARD_SIZE;
    private final SecureRandomGenerator rand;
    private final List<String> generationLog = new ArrayList<>();
    private final Map<String, Integer> difficultyHints = new HashMap<>();
    private final Map<String, Set<Integer>> conflictZones = new HashMap<>();
    private final Map<String, CosmicSignature> cosmicSignatures = new HashMap<>();

    @Autowired
    public SudokuGenerator(SecureRandomGenerator rand) {
        this.rand = Objects.requireNonNull(rand, "Random generator cannot be null");
        logger.info("SudokuGenerator initialized with SecureRandomGenerator");
    }

    /**
     * Generates a puzzle.
     *
     * <p><b>{@code seed} does not make generation reproducible.</b> The RNG is a
     * {@link java.security.SecureRandom}, and its {@code setSeed} <em>supplements</em> the
     * existing entropy rather than replacing it, so the same seed yields a different grid
     * every call. The parameter is retained because callers pass a varying value
     * (typically {@code System.currentTimeMillis()}) to stir the pool, and because the
     * retry loop below depends on retries NOT being replays — a deterministic generator
     * would retry a failing seed identically three times and fail three times.
     *
     * <p>Nothing in the application relies on reproducibility: daily, weekly and duel
     * templates are each generated once and persisted, then read back by id. Do not build
     * on the assumption that a seed reproduces a board — it does not.
     */
    public SudokuBoard generate(Constants.Difficulty difficulty, boolean chaosMode, boolean mirrorMode, long seed) {
        return generate(difficulty, chaosMode, mirrorMode, seed, false, false, 0);
    }

    public SudokuBoard generate(Constants.Difficulty difficulty, boolean chaosMode, boolean mirrorMode, long seed, 
                                boolean enforceSymmetry, boolean maximizeConflicts, int cosmicDripFactor) {
        generationLog.clear();
        difficultyHints.clear();
        conflictZones.clear();
        cosmicSignatures.clear();
        rand.setSeed(seed);
        logger.info("Generating board with seed: {}, difficulty: {}, chaos: {}, mirror: {}, symmetry: {}, conflicts: {}, drip: {}", 
            seed, difficulty, chaosMode, mirrorMode, enforceSymmetry, maximizeConflicts, cosmicDripFactor);
        generationLog.add("Seed set: " + seed);

        int attempts = 3;
        while (attempts > 0) {
            try {
                SudokuCell[][] board = initializeBoard();
                createFullSolution(board);
                markAllGiven(board);
                // Mirror mode is delivered by removing clues in symmetric PAIRS, not by
                // copying one half of a finished puzzle over the other — see the note on
                // the deleted applyMirrorSymmetry below for why the latter cannot work.
                // Symmetric removal is the generator's existing, uniqueness-checked path.
                boolean symmetric = symmetricRemovalFor(difficulty, enforceSymmetry || mirrorMode, attempts);
                removeNumbers(board, difficulty.cellsRemoved, symmetric, maximizeConflicts);
                // No generation-time chaos twist. It was a no-op that could never fire, and
                // the coherent version of the feature already exists at play time — see
                // the note on the deleted applyChaosTwist below.
                if (cosmicDripFactor > 0) applyCosmicDrip(board, cosmicDripFactor);
                long timeLimit = calculateTimeLimit(chaosMode, difficulty);
                populateDifficultyHints(board);
                populateConflictZones(board);
                populateCosmicSignatures(board);
                generationLog.add("Board generated successfully");
                SudokuBoard result = new SudokuBoard(board, chaosMode, mirrorMode, timeLimit, UUID.randomUUID().toString());
                logger.info("Generated SudokuBoard with gameId: {}", result.getGameId());
                return result;
            } catch (Exception e) {
                attempts--;
                logger.warn("Generation attempt {} failed: {}", 3 - attempts, e.getMessage());
                if (attempts == 0) {
                    logger.error("All generation attempts failed: {}", e.getMessage());
                    throw new RuntimeException("Failed to generate Sudoku board after retries", e);
                }
                try {
                    Thread.sleep(100); // Small backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Generation interrupted", ie);
                }
            }
        }
        throw new IllegalStateException("Unexpected exit from generation loop"); // Should never reach here
    }

    private SudokuCell[][] initializeBoard() {
        SudokuCell[][] board = new SudokuCell[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                board[i][j] = new SudokuCell();
                board[i][j].setGiven(false);
            }
        }
        logger.debug("Board initialized: {}x{}", size, size);
        generationLog.add("Board initialized: " + size + "x" + size);
        return board;
    }

    private void createFullSolution(SudokuCell[][] board) {
        if (!solveSudoku(board, 0, 0, 10000)) {
            logger.error("Failed to generate full solution");
            generationLog.add("Failed to generate full solution");
            throw new IllegalStateException("Failed to generate a valid Sudoku solution");
        }
        logger.debug("Full solution created");
        generationLog.add("Full solution created");
    }

    /**
     * Flags every filled cell as given right after the solution is built. removeNumbers()
     * then unmarks exactly the cells it actually removes (setGiven(false) before setValue(0)),
     * so every retained clue ends the run correctly flagged isGiven=true.
     */
    private void markAllGiven(SudokuCell[][] board) {
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                board[i][j].setGiven(true);
            }
        }
    }

    private boolean solveSudoku(SudokuCell[][] board, int row, int col, int depth) {
        if (depth <= 0) {
            logger.warn("Max depth reached at row: {}, col: {}", row, col);
            generationLog.add("Max depth reached at row: " + row + ", col: " + col);
            return false;
        }
        if (row == size) return true;
        if (col == size) return solveSudoku(board, row + 1, 0, depth - 1);
        if (board[row][col].getValue() != 0) return solveSudoku(board, row, col + 1, depth - 1);

        List<Integer> numbers = rand.getShuffledNumbers(1, 9);
        for (int num : numbers) {
            if (isValidMove(board, row, col, num)) {
                board[row][col].setValue(num, SudokuCell.MoveSource.INITIAL);
                if (solveSudoku(board, row, col + 1, depth - 1)) return true;
                board[row][col].setValue(0, SudokuCell.MoveSource.INITIAL);
            }
        }
        return false;
    }

    /**
     * Clears {@code cellsToRemove} cells, keeping the puzzle uniquely solvable.
     *
     * <p>Cells are visited in a shuffled single pass over all 81 positions rather than by
     * repeated random sampling with a {@code cellsToRemove * 2} budget. The old scheme
     * regularly ran dry on HARD (60 removals): as the grid empties, random picks keep
     * landing on already-cleared cells, and each surviving candidate is likelier to break
     * uniqueness and be restored — so the budget was consumed before the target was met.
     * Observed live at 55–58 of 60, ten times in a single startup, which surfaced as
     * "Cosmic duel failed to erupt" from EventEngine and made difficulty-4 games fail.
     *
     * <p>A full sweep tries every cell exactly once: strictly more thorough than the old
     * budget, and bounded by 81 uniqueness checks instead of an unbounded retry loop.
     */
    private void removeNumbers(SudokuCell[][] board, int cellsToRemove, boolean enforceSymmetry, boolean maximizeConflicts) {
        List<int[]> positions = new ArrayList<>(size * size);
        for (int r = 0; r < size; r++)
            for (int c = 0; c < size; c++)
                positions.add(new int[]{r, c});
        // Fisher-Yates using the generator's own RNG, so seeding still drives layout.
        for (int i = positions.size() - 1; i > 0; i--) {
            int j = rand.nextInt(i + 1);
            int[] tmp = positions.get(i);
            positions.set(i, positions.get(j));
            positions.set(j, tmp);
        }

        int removed = 0;
        for (int[] pos : positions) {
            if (removed >= cellsToRemove) break;
            int row = pos[0], col = pos[1];
            if (board[row][col].getValue() != 0) {
                int temp = board[row][col].getValue();
                // Bug fix: setGiven(false) must precede setValue(0) — SudokuCell.setValue
                // silently refuses to modify a given cell, so the old order left the value
                // in place while removed++ still counted it (a "phantom removal": the
                // board ended up with fewer empty cells than the difficulty requires).
                board[row][col].setGiven(false);
                board[row][col].setValue(0, SudokuCell.MoveSource.INITIAL);
                if (enforceSymmetry) {
                    int symRow = size - 1 - row;
                    int symCol = size - 1 - col;
                    if (symRow >= 0 && symCol >= 0 && board[symRow][symCol].getValue() != 0) {
                        int symTemp = board[symRow][symCol].getValue();
                        board[symRow][symCol].setGiven(false);
                        board[symRow][symCol].setValue(0, SudokuCell.MoveSource.INITIAL);
                        if (hasUniqueSolution(board)) {
                            removed += 2;
                            logger.debug("Symmetric removal at ({},{}) and ({},{}): {}, {}", row, col, symRow, symCol, temp, symTemp);
                            generationLog.add("Symmetric removal at (" + row + "," + col + ") and (" + symRow + "," + symCol + "): " + temp + ", " + symTemp);
                        } else {
                            board[row][col].setValue(temp, SudokuCell.MoveSource.INITIAL);
                            board[row][col].setGiven(true);
                            board[symRow][symCol].setValue(symTemp, SudokuCell.MoveSource.INITIAL);
                            board[symRow][symCol].setGiven(true);
                        }
                    } else {
                        board[row][col].setValue(temp, SudokuCell.MoveSource.INITIAL);
                        board[row][col].setGiven(true);
                    }
                } else if (hasUniqueSolution(board)) {
                    if (maximizeConflicts) {
                        int conflictPotential = countConflictPotential(board, row, col);
                        if (conflictPotential > 2) {
                            removed++;
                            logger.debug("Conflict-driven removal at ({},{}): {} (potential: {})", row, col, temp, conflictPotential);
                            generationLog.add("Conflict-driven removal at (" + row + "," + col + "): " + temp + " (potential: " + conflictPotential + ")");
                        } else {
                            board[row][col].setValue(temp, SudokuCell.MoveSource.INITIAL);
                            board[row][col].setGiven(true);
                        }
                    } else {
                        removed++;
                        logger.debug("Removed cell at ({},{}): {}", row, col, temp);
                        generationLog.add("Removed cell at (" + row + "," + col + "): " + temp);
                    }
                } else {
                    board[row][col].setValue(temp, SudokuCell.MoveSource.INITIAL);
                    board[row][col].setGiven(true);
                }
            }
        }
        if (removed < cellsToRemove) {
            logger.warn("Exhausted all {} cells; removed {} of {} requested", size * size, removed, cellsToRemove);
            generationLog.add("Failed to remove " + (cellsToRemove - removed) + " cells after a full sweep");
            throw new IllegalStateException("Failed to remove enough cells for difficulty: " + removed + "/" + cellsToRemove);
        }
    }

    /*
     * applyChaosTwist was DELETED here. It looked for pairs of cells that were
     * non-empty AND non-given, and by the time it ran no such cell could exist:
     * markAllGiven marks every cell, and removeNumbers only ever produces cells that
     * are (value == 0, given == false) — SudokuCell.setValue refuses to clear a given,
     * which is why removal calls setGiven(false) first. So after generation every cell
     * is either a clue with a value or an empty non-given, and the predicate is the
     * empty set. Measured before removal: 60 chaos boards, 0 swaps logged, and 0 cells
     * anywhere that satisfied the condition.
     *
     * It could not be repaired in place either, because the operation is incoherent at
     * generation time: the only filled cells are the clues, and swapping two clues
     * destroys the puzzle's unique solution. Chaos on the PLAYER's own entries is the
     * version that makes sense, and it already exists at play time —
     * GameService.triggerChaosSwap, which an earlier pass gave the legality checks this
     * one never had. chaosMode also still selects the BLITZ time limit
     * (calculateTimeLimit) and still rides on the board so the runtime swaps fire.
     *
     * Constants.CHAOS_MODE_SWAPS is left in place: it is externally configurable and
     * now describes only the runtime feature.
     */

    /**
     * Whether this attempt should remove clues in symmetric pairs.
     *
     * <p>Two guards, both measured rather than guessed:
     * <ul>
     *   <li><b>The removal target must be even.</b> Pairs move the count two at a time, so an
     *       odd target is unreachable: NIGHTMARE asks for 55 and symmetric removal overshoots
     *       to 56, producing a board whose clue count does not match its tier. Measured at 25
     *       boards per tier: EASY/MEDIUM/HARD 25/25 symmetric with exact counts, EXTREME 24/25,
     *       NIGHTMARE 6/25 and every one of those six off-target.</li>
     *   <li><b>The last attempt drops symmetry.</b> A symmetric layout is an aesthetic
     *       preference; a playable puzzle is not. Rather than fail a player's game outright,
     *       the final retry generates an ordinary asymmetric board.</li>
     * </ul>
     */
    private boolean symmetricRemovalFor(Constants.Difficulty difficulty, boolean wanted, int attemptsLeft) {
        if (!wanted) return false;
        if (difficulty.cellsRemoved % 2 != 0) {
            logger.debug("Symmetric removal skipped for {}: {} is an odd removal target",
                difficulty, difficulty.cellsRemoved);
            return false;
        }
        if (attemptsLeft <= 1) {
            logger.info("Final generation attempt for {} — dropping symmetry to guarantee a board",
                difficulty);
            return false;
        }
        return true;
    }

    /*
     * applyMirrorSymmetry was DELETED here. It ran AFTER removeNumbers — that is, after the
     * puzzle's unique solution had been established — and copied one half of the grid onto
     * the other. Two guards added by an earlier pass (never blank a given, never write a
     * value that breaks the grid) are what kept it from destroying the puzzle, and they are
     * also what made it pointless: after removal, mirroring almost always either blanks a
     * clue or duplicates a digit, so nearly every cell is skipped.
     *
     * Measured over 40 mirror boards before removal: ~13 cells "mirrored" per board, but the
     * empty-cell count changed on 0 of them and 0 of them came out actually symmetric. The
     * writes that survived the guards were assignments of a value the cell already held. The
     * only observable effect was a burst of WARN lines on every mirror game.
     *
     * A genuinely symmetric puzzle has to be BUILT that way, by removing clues in pairs with
     * the uniqueness check applied to each pair — which removeNumbers already does under
     * enforceSymmetry. mirrorMode now routes there, so the flag produces a real symmetric
     * clue layout with a verified unique solution. Note the symmetry is 180-degree
     * rotational, the classic Sudoku convention, not a vertical flip.
     */

    private void applyCosmicDrip(SudokuCell[][] board, int cosmicDripFactor) {
        int dripCount = Math.min(cosmicDripFactor * 3, size * size / 4); // Cap at 25% of board
        int maxAttempts = dripCount * size * 2; // hard cap to prevent infinite loop
        while (dripCount > 0 && maxAttempts-- > 0) {
            int row = rand.nextInt(size);
            int col = rand.nextInt(size);
            if (board[row][col].getValue() == 0) {
                List<Integer> possibles = new ArrayList<>();
                for (int num = 1; num <= 9; num++) {
                    if (isValidMove(board, row, col, num)) possibles.add(num);
                }
                if (!possibles.isEmpty()) {
                    int dripValue = possibles.get(rand.nextInt(possibles.size()));
                    SudokuCell.Strategy dripStyle = rand.flipCoin() ? SudokuCell.Strategy.COSMIC : SudokuCell.Strategy.STARFORGE;
                    board[row][col].setValue(dripValue, SudokuCell.MoveSource.AUTOSOLVE, dripStyle);
                    cosmicSignatures.put(row + "," + col, new CosmicSignature(dripValue, dripStyle));
                    logger.debug("Cosmic drip at ({},{}): {} ({})", row, col, dripValue, dripStyle);
                    generationLog.add("Cosmic drip at (" + row + "," + col + "): " + dripValue + " (" + dripStyle + ")");
                    dripCount--;
                }
            }
        }
        if (maxAttempts <= 0 && dripCount > 0) {
            logger.warn("applyCosmicDrip: could not place all drip cells — {} remaining after max attempts", dripCount);
        }
    }

    private void populateDifficultyHints(SudokuCell[][] board) {
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (board[i][j].getValue() == 0) {
                    int possibleValues = countPossibleValues(board, i, j);
                    difficultyHints.put(i + "," + j, possibleValues);
                    logger.trace("Hint spot at ({},{}): {} possibilities", i, j, possibleValues);
                    generationLog.add("Hint spot at (" + i + "," + j + "): " + possibleValues + " possibilities");
                }
            }
        }
    }

    private void populateConflictZones(SudokuCell[][] board) {
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (board[i][j].getValue() == 0) {
                    Set<Integer> conflicts = calculatePotentialConflicts(board, i, j);
                    if (!conflicts.isEmpty()) {
                        conflictZones.put(i + "," + j, conflicts);
                        logger.trace("Conflict zone at ({},{}): {}", i, j, conflicts);
                        generationLog.add("Conflict zone at (" + i + "," + j + "): " + conflicts);
                    }
                }
            }
        }
    }

    private void populateCosmicSignatures(SudokuCell[][] board) {
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (board[i][j].getStrategy() == SudokuCell.Strategy.COSMIC || 
                    board[i][j].getStrategy() == SudokuCell.Strategy.STARFORGE) {
                    cosmicSignatures.put(i + "," + j, new CosmicSignature(board[i][j].getValue(), board[i][j].getStrategy()));
                }
            }
        }
    }

    private int countPossibleValues(SudokuCell[][] board, int row, int col) {
        int count = 0;
        for (int num = 1; num <= 9; num++) {
            if (isValidMove(board, row, col, num)) count++;
        }
        return count;
    }

    private Set<Integer> calculatePotentialConflicts(SudokuCell[][] board, int row, int col) {
        Set<Integer> conflicts = new HashSet<>();
        for (int num = 1; num <= 9; num++) {
            if (!isValidMove(board, row, col, num)) conflicts.add(num);
        }
        return conflicts;
    }

    private int countConflictPotential(SudokuCell[][] board, int row, int col) {
        return calculatePotentialConflicts(board, row, col).size();
    }

    private long calculateTimeLimit(boolean chaosMode, Constants.Difficulty difficulty) {
        long baseTime = chaosMode ? Constants.BLITZ_MODE_SECONDS : Constants.TIME_ATTACK_SECONDS;
        int difficultyFactor = switch (difficulty) {
            case EASY -> 2;
            case MEDIUM -> 1;
            case HARD -> 0;
            default -> 0;
        };
        long timeLimit = baseTime * (difficultyFactor + 1);
        logger.debug("Calculated time limit: {}s (chaos: {}, difficulty: {})", timeLimit, chaosMode, difficulty);
        return timeLimit;
    }

    private boolean hasUniqueSolution(SudokuCell[][] board) {
        int[][] tempBoard = copyBoard(board);
        int solutions = countSolutions(tempBoard, 0, 0, 0, 10000);
        logger.debug("Solution count: {}", solutions);
        generationLog.add("Solution count: " + solutions);
        return solutions == 1;
    }

    private int countSolutions(int[][] tempBoard, int row, int col, int count, int depth) {
        if (count > 1 || depth <= 0) return count;
        if (row == size) return count + 1;
        if (col == size) return countSolutions(tempBoard, row + 1, 0, count, depth - 1);
        if (tempBoard[row][col] != 0) return countSolutions(tempBoard, row, col + 1, count, depth - 1);

        for (int num = 1; num <= 9 && count <= 1; num++) {
            if (isValidTempMove(tempBoard, row, col, num)) {
                tempBoard[row][col] = num;
                count = countSolutions(tempBoard, row, col + 1, count, depth - 1);
                tempBoard[row][col] = 0;
            }
        }
        return count;
    }

    private boolean isValidMove(SudokuCell[][] board, int row, int col, int value) {
        for (int i = 0; i < size; i++) {
            if (board[row][i].getValue() == value || board[i][col].getValue() == value) return false;
        }
        int startRow = row - row % 3;
        int startCol = col - col % 3;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (board[startRow + i][startCol + j].getValue() == value) return false;
            }
        }
        return true;
    }

    private boolean isValidTempMove(int[][] tempBoard, int row, int col, int value) {
        for (int i = 0; i < size; i++) {
            if (tempBoard[row][i] == value || tempBoard[i][col] == value) return false;
        }
        int startRow = row - row % 3;
        int startCol = col - col % 3;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (tempBoard[startRow + i][startCol + j] == value) return false;
            }
        }
        return true;
    }

    private int[][] copyBoard(SudokuCell[][] board) {
        int[][] copy = new int[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                copy[i][j] = board[i][j].getValue();
            }
        }
        return copy;
    }

    // Validation and Insights
    public boolean validateBoard(SudokuCell[][] board) {
        // Bug fix: isValidMove sees the cell's own value in its row/col/box, so calling it
        // on a FILLED cell always failed and validateBoard rejected every non-empty board.
        // Check each filled cell against a copy with that cell blanked instead.
        int[][] temp = copyBoard(board);
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                int val = temp[i][j];
                if (val != 0) {
                    temp[i][j] = 0;
                    boolean ok = isValidTempMove(temp, i, j, val);
                    temp[i][j] = val;
                    if (!ok) {
                        logger.warn("Invalid move detected at ({},{}): {}", i, j, val);
                        generationLog.add("Invalid move detected at (" + i + "," + j + "): " + val);
                        return false;
                    }
                }
            }
        }
        boolean unique = hasUniqueSolution(board);
        if (!unique) logger.warn("Board lacks unique solution");
        return unique;
    }

    public List<String> getGenerationLog() {
        return new ArrayList<>(generationLog);
    }

    public Map<String, Integer> getDifficultyHints() {
        return new HashMap<>(difficultyHints);
    }

    public Map<String, Set<Integer>> getConflictZones() {
        return new HashMap<>(conflictZones);
    }

    public Map<String, CosmicSignature> getCosmicSignatures() {
        return new HashMap<>(cosmicSignatures);
    }

    public int calculateDifficultyScore(SudokuCell[][] board) {
        int givenCells = 0;
        int conflicts = 0;
        int hintComplexity = 0;
        int cosmicFlair = 0;
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (board[i][j].isGiven()) givenCells++;
                conflicts += board[i][j].getConflicts().size();
                hintComplexity += difficultyHints.getOrDefault(i + "," + j, 0);
                if (cosmicSignatures.containsKey(i + "," + j)) cosmicFlair += 5;
            }
        }
        int emptyCells = size * size - givenCells;
        int conflictScore = conflictZones.values().stream().mapToInt(Set::size).sum();
        int score = emptyCells * 10 + conflicts * 5 + hintComplexity + conflictScore * 2 + cosmicFlair;
        logger.debug("Difficulty score calculated: {}", score);
        return score;
    }

    public void enforceMinimumDifficulty(SudokuCell[][] board, int minScore) {
        int currentScore = calculateDifficultyScore(board);
        int attempts = 10; // Limit attempts to avoid infinite loops
        while (currentScore < minScore && attempts > 0) {
            int row = rand.nextInt(size);
            int col = rand.nextInt(size);
            if (board[row][col].getValue() != 0 && board[row][col].isGiven()) {
                int temp = board[row][col].getValue();
                // Bug fix: same ordering issue as removeNumbers — these cells are given,
                // so setValue(0) before setGiven(false) was ALWAYS a silent no-op and this
                // method never actually increased difficulty.
                board[row][col].setGiven(false);
                board[row][col].setValue(0, SudokuCell.MoveSource.INITIAL);
                if (hasUniqueSolution(board)) {
                    logger.debug("Enforced difficulty by removing ({},{}): {}", row, col, temp);
                    generationLog.add("Enforced difficulty by removing (" + row + "," + col + "): " + temp);
                    currentScore = calculateDifficultyScore(board);
                } else {
                    board[row][col].setValue(temp, SudokuCell.MoveSource.INITIAL);
                    board[row][col].setGiven(true);
                }
            }
            attempts--;
        }
        if (currentScore < minScore) {
            logger.warn("Failed to reach minimum difficulty score {} (current: {}) after {} attempts", minScore, currentScore, attempts);
        }
    }

    public void applyCosmicSignaturePattern(SudokuCell[][] board, String pattern) {
        switch (pattern.toLowerCase()) {
            case "diagonal":
                for (int i = 0; i < size; i++) {
                    if (board[i][i].getValue() == 0) {
                        int value = rand.nextIntRange(1, 9);
                        if (isValidMove(board, i, i, value)) {
                            board[i][i].setValue(value, SudokuCell.MoveSource.AUTOSOLVE, SudokuCell.Strategy.STARFORGE);
                            cosmicSignatures.put(i + "," + i, new CosmicSignature(value, SudokuCell.Strategy.STARFORGE));
                            logger.debug("Diagonal cosmic signature at ({},{}): {}", i, i, value);
                            generationLog.add("Diagonal cosmic signature at (" + i + "," + i + "): " + value);
                        }
                    }
                }
                break;
            case "cross":
                int mid = size / 2;
                for (int i = 0; i < size; i++) {
                    if (board[i][mid].getValue() == 0) {
                        int value = rand.nextIntRange(1, 9);
                        if (isValidMove(board, i, mid, value)) {
                            board[i][mid].setValue(value, SudokuCell.MoveSource.AUTOSOLVE, SudokuCell.Strategy.COSMIC);
                            cosmicSignatures.put(i + "," + mid, new CosmicSignature(value, SudokuCell.Strategy.COSMIC));
                            logger.debug("Cross cosmic signature at ({},{}): {}", i, mid, value);
                            generationLog.add("Cross cosmic signature at (" + i + "," + mid + "): " + value);
                        }
                    }
                    if (board[mid][i].getValue() == 0) {
                        int value = rand.nextIntRange(1, 9);
                        if (isValidMove(board, mid, i, value)) {
                            board[mid][i].setValue(value, SudokuCell.MoveSource.AUTOSOLVE, SudokuCell.Strategy.COSMIC);
                            cosmicSignatures.put(mid + "," + i, new CosmicSignature(value, SudokuCell.Strategy.COSMIC));
                            logger.debug("Cross cosmic signature at ({},{}): {}", mid, i, value);
                            generationLog.add("Cross cosmic signature at (" + mid + "," + i + "): " + value);
                        }
                    }
                }
                break;
            default:
                logger.warn("Unknown cosmic signature pattern: {}", pattern);
        }
    }

    public static class CosmicSignature {
        private final int value;
        private final SudokuCell.Strategy strategy;

        public CosmicSignature(int value, SudokuCell.Strategy strategy) {
            this.value    = value;
            this.strategy = strategy;
        }

        public int                getValue()   { return value; }
        public SudokuCell.Strategy getStrategy() { return strategy; }

        @Override
        public String toString() {
            return "CosmicSignature{value=" + value + ", strategy=" + strategy + "}";
        }
    }
}
