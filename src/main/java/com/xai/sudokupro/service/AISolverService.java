package com.xai.sudokupro.service;

import com.xai.sudokupro.model.Hint;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import com.xai.sudokupro.util.SecureRandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.xai.sudokupro.model.EnhancedMove;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AISolverService {
    private static final Logger logger = LoggerFactory.getLogger(AISolverService.class);

    private static final int HINT_CACHE_SIZE   = 10;
    private static final int COSMIC_THRESHOLD  = 3;
    private static final int COSMIC_BOOST      = 2;

    private final SecureRandomGenerator chaosRand;

    private volatile SudokuBoard currentBoard;
    private SudokuCell[][] cachedBoard;

    private final LinkedHashSet<String> recentHints = new LinkedHashSet<>(HINT_CACHE_SIZE);
    private int hintStreak = 0;

    private final Map<String,Boolean> hintFeedback = new HashMap<>();
    private final Map<String,Integer> cosmicHotspots = new ConcurrentHashMap<>();
    private final Map<String,Integer> playerFeedbackHeatmap = new ConcurrentHashMap<>();

    @Autowired
    public AISolverService(SecureRandomGenerator chaosRand) {
        this.chaosRand = Objects.requireNonNull(chaosRand);
    }

    public synchronized void setCurrentBoard(SudokuBoard board) {
        this.currentBoard = board;
        this.cachedBoard  = board.getBoardCopy();
        initializeCosmicHotspots();
        initializePlayerFeedbackHeatmap();
    }

    public synchronized void refreshCache() {
        if (currentBoard != null) cachedBoard = currentBoard.getBoardCopy();
    }

    public synchronized boolean solveSudoku() {
        if (currentBoard == null) return false;
        refreshCache();

        if (solveSudoku(cachedBoard, 0, 0)) {
            updateBoard(cachedBoard, SudokuCell.MoveSource.AUTOSOLVE);
            boostCosmicHotspots();
            return true;
        }
        return false;
    }

    private boolean solveSudoku(SudokuCell[][] b, int row, int col) {
        if (row == 9) return true;
        if (col == 9) return solveSudoku(b, row + 1, 0);
        if (b[row][col].getValue() != 0) return solveSudoku(b, row, col + 1);

        for (int n = 1; n <= 9; n++) {
            if (currentBoard.isValidMove(row, col, n)) {
                b[row][col].setValue(n, SudokuCell.MoveSource.AUTOSOLVE);
                if (solveSudoku(b, row, col + 1)) return true;
                b[row][col].setValue(0, SudokuCell.MoveSource.AUTOSOLVE);
            }
        }
        return false;
    }

    public synchronized String getNextLogicalMove(SudokuBoard board) {
        setCurrentBoard(board);
        refreshCache();

        List<Hint> hints = collectAllHints(board);

        Optional<Hint> best = hints.stream()
            .filter(h -> !recentHints.contains(hintKey(h)))
            .max(Comparator.comparingInt(this::scoreHint));

        if (best.isPresent()) {
            Hint h = best.get();
            recentHints.add(hintKey(h));
            board.incrementHintCount();

            Object val = h.value();
            if (val instanceof Integer) {
                cosmicHotspots.merge(h.row() + "," + h.col(), COSMIC_BOOST, Integer::sum);
            }

            return formatHint(h);
        }

        return "No moves";
    }

    public synchronized EnhancedMove getNextLogicalMoveAsEnhancedMove(SudokuBoard board) {
        setCurrentBoard(board);
        refreshCache();

        List<Hint> hints = collectAllHints(board);

        return hints.stream()
            .filter(h -> !recentHints.contains(hintKey(h)))
            .max(Comparator.comparingInt(this::scoreHint))
            .map(h -> {
                Object val = h.value();
                if (val instanceof Integer v) {
                    return new EnhancedMove(h.row(), h.col(), 0, v, SudokuCell.MoveSource.HINT);
                }
                return null;
            })
            .orElse(null);
    }

    private List<Hint> collectAllHints(SudokuBoard board) {
        List<Hint> hints = new ArrayList<>();
        hints.addAll(detectSingle(board));
        hints.addAll(detectCosmic(board));
        return hints;
    }

    private List<Hint> detectSingle(SudokuBoard board) {
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++) {
                if (cachedBoard[r][c].getValue() == 0) {
                    List<Integer> valid = getValidMoves(r, c);
                    if (valid.size() == 1) {
                        return List.of(new Hint(r, c, valid.get(0), "Single"));
                    }
                }
            }
        return List.of();
    }

    private List<Hint> detectCosmic(SudokuBoard board) {
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++) {
                if (cachedBoard[r][c].getValue() == 0 &&
                    cosmicHotspots.getOrDefault(r + "," + c, 0) >= COSMIC_THRESHOLD) {

                    List<Integer> valid = getValidMoves(r, c);
                    if (valid.size() == 1) {
                        return List.of(new Hint(r, c, valid.get(0), "Cosmic"));
                    }
                }
            }
        return List.of();
    }

    private List<Integer> getValidMoves(int r, int c) {
        List<Integer> valid = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            if (currentBoard.isValidMove(r, c, i)) valid.add(i);
        }
        return valid;
    }

    private void updateBoard(SudokuCell[][] cells, SudokuCell.MoveSource source) {
        if (currentBoard == null) return;

        for (int i = 0; i < 9; i++)
            for (int j = 0; j < 9; j++)
                if (!cells[i][j].isGiven()) {
                    currentBoard.makeMove(i, j, cells[i][j].getValue(), source);
                }
    }

    private void initializeCosmicHotspots() {
        cosmicHotspots.clear();
    }

    private void initializePlayerFeedbackHeatmap() {
        playerFeedbackHeatmap.clear();
    }

    private void boostCosmicHotspots() {
        cosmicHotspots.replaceAll((k, v) -> v + COSMIC_BOOST);
    }

    private int scoreHint(Hint h) {
        return 1 + hintStreak;
    }

    private String hintKey(Hint h) {
        return h.row() + "," + h.col();
    }

    private String formatHint(Hint h) {
        Object val = h.value();
        if (val instanceof Integer v) {
            return "Set " + v + " at (" + h.row() + "," + h.col() + ")";
        }
        return "Hint at (" + h.row() + "," + h.col() + ")";
    }

    // 🔥 REQUIRED FIX
    public Map<String, Integer> getCosmicHotspotMap() {
        return new HashMap<>(cosmicHotspots);
    }
}
