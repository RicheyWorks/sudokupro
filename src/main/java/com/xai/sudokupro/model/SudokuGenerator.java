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

    public SudokuGenerator(long seed) {
        this.rand = new SecureRandomGenerator();
        rand.setSeed(seed);
        logger.info("SudokuGenerator initialized with seed: {}", seed);
    }

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
                removeNumbers(board, difficulty.cellsRemoved, enforceSymmetry, maximizeConflicts);
                if (chaosMode) applyChaosTwist(board);
                if (mirrorMode) applyMirrorSymmetry(board);
                if (cosmicDripFactor > 0) applyCosmicDrip(board, cosmicDripFactor);
                long timeLimit = calculateTimeLimit(chaosMode, difficulty);
                populateDifficultyHints(board);
                populateConflictZones(board);
                populateCosmicSignatures(board);
                generationLog.add("Board generated successfully");
                SudokuBoard result = new SudokuBoard(board, chaosMode, mirrorMode, timeLimit, UUID.randomUUID().toString(), new AISolverService());
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

    private boolean solveSudoku(SudokuCell[][] board, int row, int col, int depth) {
        if (depth <= 0) {
            logger.warn("Max depth reached at row: {}, col: {}", row, col);
            generationLog.add("Max depth reached at row: " + row + ", col: " + col);
            return false;
        }
        if (row == size) return true;
        if (col == size) return solveSudoku(board, row + 1, 0, depth - 1);
        if (board[row][col].getValue() != 0) return solveSudoku(board, row, col + 1, depth - 1);

        List<Integer> numbers = SecureRandomGenerator.getShuffledNumbers(1, 9, rand);
        for (int num : numbers) {
            if (isValidMove(board, row, col, num)) {
                board[row][col].setValue(num, SudokuCell.MoveSource.INITIAL);
                if (solveSudoku(board, row, col + 1, depth - 1)) return true;
                board[row][col].setValue(0, SudokuCell.MoveSource.INITIAL);
            }
        }
        return false;
    }

    private void removeNumbers(SudokuCell[][] board, int cellsToRemove, boolean enforceSymmetry, boolean maximizeConflicts) {
        int attempts = cellsToRemove * 2;
        int removed = 0;
        while (removed < cellsToRemove && attempts > 0) {
            int row = rand.nextInt(size);
            int col = rand.nextInt(size);
            if (board[row][col].getValue() != 0) {
                int temp = board[row][col].getValue();
                board[row][col].setValue(0, SudokuCell.MoveSource.INITIAL);
                board[row][col].setGiven(false);
                if (enforceSymmetry) {
                    int symRow = size - 1 - row;
                    int symCol = size - 1 - col;
                    if (symRow >= 0 && symCol >= 0 && board[symRow][symCol].getValue() != 0) {
                        int symTemp = board[symRow][symCol].getValue();
                        board[symRow][symCol].setValue(0, SudokuCell.MoveSource.INITIAL);
                        board[symRow][symCol].setGiven(false);
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
                attempts--;
            }
        }
        if (removed < cellsToRemove) {
            logger.warn("Failed to remove {} cells after {} attempts; achieved: {}", cellsToRemove - removed, attempts, removed);
            generationLog.add("Failed to remove " + (cellsToRemove - removed) + " cells after " + attempts + " attempts");
            throw new IllegalStateException("Failed to remove enough cells for difficulty: " + removed + "/" + cellsToRemove);
        }
    }

    private void applyChaosTwist(SudokuCell[][] board) {
        int swaps = Constants.CHAOS_MODE_SWAPS;
        while (swaps > 0) {
            int row1 = rand.nextInt(size);
            int col1 = rand.nextInt(size);
            int row2 = rand.nextInt(size);
            int col2 = rand.nextInt(size);
            if (board[row1][col1].getValue() != 0 && board[row2][col2].getValue() != 0 &&
                !board[row1][col1].isGiven() && !board[row2][col2].isGiven()) {
                int temp = board[row1][col1].getValue();
                board[row1][col1].setValue(board[row2][col2].getValue(), SudokuCell.MoveSource.INITIAL);
                board[row2][col2].setValue(temp, SudokuCell.MoveSource.INITIAL);
                swaps--;
                logger.debug("Chaos swap: ({},{}) <-> ({},{})", row1, col1, row2, col2);
                generationLog.add("Chaos swap: (" + row1 + "," + col1 + ") <-> (" + row2 + "," + col2 + ")");
            }
        }
    }

    private void applyMirrorSymmetry(SudokuCell[][] board) {
        int symmetryType = Constants.MIRROR_MODE_SYMMETRY;
        for (int i = 0; i < size / 2; i++) {
            for (int j = 0; j < size; j++) {
                if (symmetryType == 1) { // Vertical symmetry
                    int mirroredRow = size - 1 - i;
                    if (board[mirroredRow][j].getValue() != 0 && !isValidMove(board, i, j, board[mirroredRow][j].getValue())) {
                        logger.warn("Invalid mirror move at ({},{}); skipping", i, j);
                        continue;
                    }
                    board[i][j].setValue(board[mirroredRow][j].getValue(), SudokuCell.MoveSource.INITIAL);
                    board[i][j].setGiven(board[mirroredRow][j].isGiven());
                    logger.debug("Mirrored vertically: ({},{}) -> ({},{})", mirroredRow, j, i, j);
                    generationLog.add("Mirrored vertically: (" + mirroredRow + "," + j + ") -> (" + i + "," + j + ")");
                } else { // Horizontal symmetry
                    int mirroredCol = size - 1 - j;
                    if (board[i][mirroredCol].getValue() != 0 && !isValidMove(board, i, j, board[i][mirroredCol].getValue())) {
                        logger.warn("Invalid mirror move at ({},{}); skipping", i, j);
                        continue;
                    }
                    board[i][j].setValue(board[i][mirroredCol].getValue(), SudokuCell.MoveSource.INITIAL);
                    board[i][j].setGiven(board[i][mirroredCol].isGiven());
                    logger.debug("Mirrored horizontally: ({},{}) -> ({},{})", i, mirroredCol, i, j);
                    generationLog.add("Mirrored horizontally: (" + i + "," + mirroredCol + ") -> (" + i + "," + j + ")");
                }
            }
        }
    }

    private void applyCosmicDrip(SudokuCell[][] board, int cosmicDripFactor) {
        int dripCount = Math.min(cosmicDripFactor * 3, size * size / 4); // Cap at 25% of board
        while (dripCount > 0) {
            int row = rand.nextInt(size);
            int col = rand.nextInt(size);
            if (board[row][col].getValue() == 0) {
                List<Integer> possibles = new ArrayList<>();
                for (int num = 1; num <= 9; num++) {
                    if (isValidMove(board, row, col, num)) possibles.add(num);
                }
                if (!possibles.isEmpty()) {
                    int dripValue = possibles.get(rand.nextInt(possibles.size()));
                    SudokuCell.Strategy dripStyle = rand.nextBoolean() ? SudokuCell.Strategy.COSMIC : SudokuCell.Strategy.STARFORGE;
                    board[row][col].setValue(dripValue, SudokuCell.MoveSource.AUTOSOLVE, dripStyle);
                    cosmicSignatures.put(row + "," + col, new CosmicSignature(dripValue, dripStyle));
                    logger.debug("Cosmic drip at ({},{}): {} ({})", row, col, dripValue, dripStyle);
                    generationLog.add("Cosmic drip at (" + row + "," + col + "): " + dripValue + " (" + dripStyle + ")");
                    dripCount--;
                }
            }
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
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                int val = board[i][j].getValue();
                if (val != 0 && !isValidMove(board, i, j, val)) {
                    logger.warn("Invalid move detected at ({},{}): {}", i, j, val);
                    generationLog.add("Invalid move detected at (" + i + "," + j + "): " + val);
                    return false;
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
                board[row][col].setValue(0, SudokuCell.MoveSource.INITIAL);
                board[row][col].setGiven(false);
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
                        int value = rand.nextInt(1, 10);
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
                        int value = rand.nextInt(1, 10);
                        if (isValidMove(board, i, mid, value)) {
                            board[i][mid].setValue(value, SudokuCell.MoveSource.AUTOSOLVE, SudokuCell.Strategy.COSMIC);
                            cosmicSignatures.put(i + "," + mid, new CosmicSignature(value, SudokuCell.Strategy.COSMIC));
                            logger.debug("Cross cosmic signature at ({},{}): {}", i, mid, value);
                            generationLog.add("Cross cosmic signature at (" + i + "," + mid + "): " + value);
                        }
                    }
                    if (board[mid][i].getValue() == 0) {
                        int value = rand.nextInt(1, 10);
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
                logger.warn("Unknown cosmic pattern: {} - skipping", pattern);
                generationLog.add("Unknown cosmic pattern: " + pattern + " - skipping");
        }
    }

    public record CosmicSignature(int value, SudokuCell.Strategy style) {}
}
