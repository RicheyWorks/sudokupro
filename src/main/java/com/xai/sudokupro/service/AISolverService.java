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

/**
 * Stateless (board-wise) AI solver.  The board is always passed as a parameter
 * rather than stored as a shared field, so concurrent calls for different games
 * never interfere with each other.
 *
 * cosmicHotspots / recentHints are still instance-level maps shared across games.
 * A future improvement would move them to a per-game GameState object; for now they
 * are thread-safe (ConcurrentHashMap / synchronised methods) and the worst effect of
 * cross-game contamination is a slightly skewed hint-ranking score.
 */
@Service
public class AISolverService {
    private static final Logger logger = LoggerFactory.getLogger(AISolverService.class);

    private static final int HINT_CACHE_SIZE  = 10;
    private static final int COSMIC_THRESHOLD = 3;
    private static final int COSMIC_BOOST     = 2;

    private final SecureRandomGenerator chaosRand;

    // ── Shared state (game-agnostic metrics only) ──────────────────────────────
    private final LinkedHashSet<String>   recentHints          = new LinkedHashSet<>(HINT_CACHE_SIZE);
    private int                           hintStreak           = 0;
    private final Map<String, Boolean>    hintFeedback         = new HashMap<>();
    private final Map<String, Integer>    cosmicHotspots       = new ConcurrentHashMap<>();
    private final Map<String, Integer>    playerFeedbackHeatmap= new ConcurrentHashMap<>();

    @Autowired
    public AISolverService(SecureRandomGenerator chaosRand) {
        this.chaosRand = Objects.requireNonNull(chaosRand);
    }

    /**
     * @deprecated The shared currentBoard field has been removed.
     *             Pass the board directly to {@link #getNextLogicalMove(SudokuBoard)}
     *             or {@link #solveSudoku(SudokuBoard)}.  This method is a no-op kept
     *             for compile-time compatibility while callers are migrated.
     */
    @Deprecated
    public void setCurrentBoard(SudokuBoard board) {
        // no-op — board is now passed directly to each method that needs it
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Fills every empty cell in {@code board} using backtracking.
     * All mutations go through {@link SudokuBoard#makeMove} so move-history is preserved.
     * No shared mutable state is accessed, so no synchronisation is required.
     */
    public boolean solveSudoku(SudokuBoard board) {
        if (board == null) return false;
        SudokuCell[][] snapshot = board.getBoardCopy();
        if (backtrack(snapshot, 0, 0, board)) {
            applySnapshot(snapshot, board, SudokuCell.MoveSource.AUTOSOLVE);
            boostCosmicHotspots();
            return true;
        }
        return false;
    }

    public String getNextLogicalMove(SudokuBoard board) {
        // Hint collection is read-only; run it without holding the lock.
        SudokuCell[][] snapshot = board.getBoardCopy();
        List<Hint> hints = collectAllHints(board, snapshot);

        // recentHints is not thread-safe — guard the read+write as one atomic op.
        synchronized (recentHints) {
            Optional<Hint> best = hints.stream()
                .filter(h -> !recentHints.contains(hintKey(h)))
                .max(Comparator.comparingInt(this::scoreHint));

            if (best.isPresent()) {
                Hint h = best.get();
                recentHints.add(hintKey(h));
                // Evict the oldest entry when the cache is full.
                // Must call next() before remove() — calling remove() on a fresh
                // iterator without next() throws IllegalStateException.
                if (recentHints.size() > HINT_CACHE_SIZE) {
                    Iterator<String> it = recentHints.iterator();
                    if (it.hasNext()) { it.next(); it.remove(); }
                }
                board.incrementHintCount();
                if (h.value() instanceof Integer) {
                    cosmicHotspots.merge(h.row() + "," + h.col(), COSMIC_BOOST, Integer::sum);
                }
                hintStreak++;  // track consecutive hint usage for scoring
                return formatHint(h);
            }
            hintStreak = 0;  // no move found — reset streak
        }
        return "No moves";
    }

    public EnhancedMove getNextLogicalMoveAsEnhancedMove(SudokuBoard board) {
        SudokuCell[][] snapshot = board.getBoardCopy();
        List<Hint> hints = collectAllHints(board, snapshot);

        synchronized (recentHints) {
            return hints.stream()
                .filter(h -> !recentHints.contains(hintKey(h)))
                .max(Comparator.comparingInt(this::scoreHint))
                .map(h -> {
                    if (h.value() instanceof Integer v) {
                        return new EnhancedMove(h.row(), h.col(), 0, v, SudokuCell.MoveSource.HINT);
                    }
                    return null;
                })
                .orElse(null);
        }
    }

    public Map<String, Integer> getCosmicHotspotMap() {
        return new HashMap<>(cosmicHotspots);
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private List<Hint> collectAllHints(SudokuBoard board, SudokuCell[][] snapshot) {
        List<Hint> hints = new ArrayList<>();
        hints.addAll(detectSingle(board, snapshot));
        hints.addAll(detectCosmic(board, snapshot));
        return hints;
    }

    private List<Hint> detectSingle(SudokuBoard board, SudokuCell[][] snapshot) {
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++) {
                if (snapshot[r][c].getValue() == 0) {
                    List<Integer> valid = getValidMoves(board, r, c);
                    if (valid.size() == 1) {
                        return List.of(new Hint(r, c, valid.get(0), "Single"));
                    }
                }
            }
        return List.of();
    }

    private List<Hint> detectCosmic(SudokuBoard board, SudokuCell[][] snapshot) {
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++) {
                if (snapshot[r][c].getValue() == 0 &&
                    cosmicHotspots.getOrDefault(r + "," + c, 0) >= COSMIC_THRESHOLD) {
                    List<Integer> valid = getValidMoves(board, r, c);
                    if (valid.size() == 1) {
                        return List.of(new Hint(r, c, valid.get(0), "Cosmic"));
                    }
                }
            }
        return List.of();
    }

    private List<Integer> getValidMoves(SudokuBoard board, int r, int c) {
        List<Integer> valid = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            if (board.isValidMove(r, c, i)) valid.add(i);
        }
        return valid;
    }

    /**
     * Recursive backtracking solver — operates on the snapshot array,
     * using {@link SudokuBoard#isValidMove} for constraint checking.
     */
    private boolean backtrack(SudokuCell[][] snapshot, int row, int col, SudokuBoard board) {
        if (row == 9) return true;
        if (col == 9) return backtrack(snapshot, row + 1, 0, board);
        if (snapshot[row][col].getValue() != 0) return backtrack(snapshot, row, col + 1, board);

        for (int n = 1; n <= 9; n++) {
            if (board.isValidMove(row, col, n)) {
                snapshot[row][col].setValue(n, SudokuCell.MoveSource.AUTOSOLVE);
                if (backtrack(snapshot, row, col + 1, board)) return true;
                snapshot[row][col].setValue(0, SudokuCell.MoveSource.AUTOSOLVE);
            }
        }
        return false;
    }

    /** Writes the completed snapshot back to the live board via makeMove. */
    private void applySnapshot(SudokuCell[][] snapshot, SudokuBoard board,
                               SudokuCell.MoveSource source) {
        for (int i = 0; i < 9; i++)
            for (int j = 0; j < 9; j++)
                if (!snapshot[i][j].isGiven())
                    board.makeMove(i, j, snapshot[i][j].getValue(), source);
    }

    private void boostCosmicHotspots() {
        cosmicHotspots.replaceAll((k, v) -> v + COSMIC_BOOST);
    }

    private int scoreHint(Hint h) {
        // Base 1 + consecutive-hint streak bonus + hotspot weight for this cell.
        // Cells that have been identified as cosmic hotspots rank higher,
        // and scores rise as the player keeps requesting hints in a session.
        return 1 + hintStreak + cosmicHotspots.getOrDefault(h.row() + "," + h.col(), 0);
    }

    private String hintKey(Hint h) {
        return h.row() + "," + h.col();
    }

    private String formatHint(Hint h) {
        if (h.value() instanceof Integer v) {
            return "Set " + v + " at (" + h.row() + "," + h.col() + ")";
        }
        return "Hint at (" + h.row() + "," + h.col() + ")";
    }
}
