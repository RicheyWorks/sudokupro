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

    /**
     * Returns a hint string for a fixed test board — used by SudokuHealthMonitor
     * to verify the solver is alive without touching a real game.
     */
    public String getNextLogicalMoveForTestBoard() {
        SudokuBoard test = new SudokuBoard(3, false, false, 0L, "health-check");
        return getNextLogicalMove(test);
    }

    /**
     * Returns a compact hash of the predicted final move pattern for a player.
     * Stubbed: returns a deterministic string based on playerId.
     */
    public String predictFinalMovePattern(String playerId) {
        return "predicted-" + Integer.toHexString(playerId.hashCode());
    }

    /**
     * Returns a compact hash representing the current move sequence for a game.
     * Stubbed: returns a deterministic string based on gameId.
     */
    public String getCurrentMoveSignature(String gameId) {
        return "sig-" + Integer.toHexString(gameId.hashCode());
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private List<Hint> collectAllHints(SudokuBoard board, SudokuCell[][] snapshot) {
        List<Hint> hints = new ArrayList<>();
        int size = snapshot.length;
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (snapshot[r][c].getValue() == 0) {
                    List<Integer> candidates = new ArrayList<>();
                    for (int v = 1; v <= 9; v++) {
                        if (isValidTempMove(snapshot, r, c, v)) candidates.add(v);
                    }
                    if (candidates.size() == 1) {
                        // Naked single: only one value can go here
                        hints.add(new Hint(r, c, candidates.get(0), "Naked single", SudokuCell.Strategy.NAKED_SINGLE));
                    } else if (!candidates.isEmpty()) {
                        // Add as a candidate hint with the first possible value
                        hints.add(new Hint(r, c, candidates.get(0), "Candidate", SudokuCell.Strategy.UNKNOWN));
                    }
                }
            }
        }
        return hints;
    }

    private boolean backtrack(SudokuCell[][] snapshot, int row, int col, SudokuBoard board) {
        int size = snapshot.length;
        if (row == size) return true;
        if (col == size) return backtrack(snapshot, row + 1, 0, board);
        if (snapshot[row][col].getValue() != 0) return backtrack(snapshot, row, col + 1, board);
        for (int v = 1; v <= 9; v++) {
            if (isValidTempMove(snapshot, row, col, v)) {
                snapshot[row][col].setValue(v, SudokuCell.MoveSource.AUTOSOLVE);
                if (backtrack(snapshot, row, col + 1, board)) return true;
                snapshot[row][col].setValue(0, SudokuCell.MoveSource.AUTOSOLVE);
            }
        }
        return false;
    }

    private void applySnapshot(SudokuCell[][] snapshot, SudokuBoard board, SudokuCell.MoveSource source) {
        SudokuCell[][] live = board.getBoard();
        int size = snapshot.length;
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (!live[r][c].isGiven() && snapshot[r][c].getValue() != live[r][c].getValue()) {
                    board.makeMove(r, c, snapshot[r][c].getValue(), source);
                }
            }
        }
    }

    private boolean isValidTempMove(SudokuCell[][] b, int row, int col, int value) {
        int size = b.length;
        for (int i = 0; i < size; i++) {
            if (b[row][i].getValue() == value || b[i][col].getValue() == value) return false;
        }
        int startRow = row - row % 3, startCol = col - col % 3;
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (b[startRow + i][startCol + j].getValue() == value) return false;
        return true;
    }

    private int scoreHint(Hint h) {
        int base = switch (h.strategy()) {
            case NAKED_SINGLE -> 100;
            default           -> 10;
        };
        int cosmicBoost = cosmicHotspots.getOrDefault(h.row() + "," + h.col(), 0);
        boolean recentlyUsed = hintFeedback.getOrDefault(hintKey(h), Boolean.FALSE);
        return base + cosmicBoost * COSMIC_BOOST - (recentlyUsed ? 20 : 0);
    }

    private String hintKey(Hint h) {
        return h.row() + "," + h.col() + "=" + h.value();
    }

    private String formatHint(Hint h) {
        return String.format("Try placing %s at row %d, col %d [%s]",
            h.value(), h.row() + 1, h.col() + 1, h.strategy());
    }

    private void boostCosmicHotspots() {
        // Decay all existing hotspot scores slightly after a full solve
        cosmicHotspots.replaceAll((k, v) -> Math.max(0, v - 1));
    }

    public synchronized void recordHintFeedback(String key, boolean helpful) {
        hintFeedback.put(key, helpful);
        if (hintFeedback.size() > HINT_CACHE_SIZE * 10) {
            // Trim oldest entries
            Iterator<String> it = hintFeedback.keySet().iterator();
            while (hintFeedback.size() > HINT_CACHE_SIZE * 5 && it.hasNext()) {
                it.next(); it.remove();
            }
        }
    }
}
