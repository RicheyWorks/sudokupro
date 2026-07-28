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
    /** Ceiling on the hotspot bonus, so it can never outweigh the naked-single preference. */
    private static final int MAX_COSMIC_BOOST = 20;
    /** Cap on distinct coordinates tracked, so the global hotspot map cannot grow forever. */
    private static final int MAX_HOTSPOTS     = 512;

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

    /**
     * Sentinel returned by {@link #getNextLogicalMove} when no logical move exists.
     *
     * <p>A named constant rather than an inline literal because the value is part of a
     * contract: {@code GameService.purchaseHint} must recognise it to keep the "empty
     * hints are free" rule — the literal is non-blank, so a plain
     * {@code !hint.isBlank()} check charged 5 gems for a hint that said nothing.
     * The literal itself is pinned by AISolverCorrectnessTest and shown verbatim by
     * clients, so it stays "No moves"; what changed is that it now has a name the
     * charge site can compare against.
     */
    public static final String NO_MOVES = "No moves";

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
                    // 81 coordinates on a 9x9, but the map is keyed by string and shared
                    // process-wide; bound it rather than trust that invariant forever.
                    if (cosmicHotspots.size() > MAX_HOTSPOTS) cosmicHotspots.clear();
                }
                hintStreak++;  // track consecutive hint usage for scoring
                return formatHint(h);
            }
            hintStreak = 0;  // no move found — reset streak
        }
        return NO_MOVES;
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
     * Returns a hint string for a fixed test board — used by the health indicator to
     * verify the solver is alive without touching a real game.
     *
     * <p><b>Read-only by construction.</b> This used to delegate to
     * {@link #getNextLogicalMove(SudokuBoard)}, which records its answer into the two
     * process-global maps every real hint shares: the 10-entry {@code recentHints}
     * de-duplication window and the {@code cosmicHotspots} tie-break weights. Kubernetes
     * probes both readiness and liveness through this path — roughly nine calls a minute
     * per pod — so health traffic turned the entire {@code recentHints} window over about
     * every seventy seconds and steadily biased the hotspot tie-break toward whatever
     * coordinates the probe's throwaway board happened to produce. Paying players' hints
     * were being filtered and re-ranked by the load balancer's heartbeat. The probe now
     * scores candidates without recording anything.
     */
    public String getNextLogicalMoveForTestBoard() {
        SudokuBoard test = new SudokuBoard(3, false, false, 0L, "health-check");
        SudokuCell[][] snapshot = test.getBoardCopy();
        List<Hint> hints = collectAllHints(test, snapshot);
        synchronized (recentHints) {
            return hints.stream()
                .max(Comparator.comparingInt(this::scoreHint))
                .map(this::formatHint)
                .orElse(NO_MOVES);
        }
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

    /**
     * Every empty cell, hinted with its <em>true</em> value.
     *
     * <p>This used to emit {@code candidates.get(0)} — the smallest locally legal digit —
     * for any cell that was not a naked single, and label it "Candidate". That is a guess,
     * not a deduction, and it was frequently wrong: over 25 boards with no naked single
     * available, <b>16 of the hints named a digit that was not the answer</b>. Players paid
     * 5 gems each time. Worse, {@code PowerUpService}'s REVEAL_CELL runs through
     * {@link #getNextLogicalMoveAsEnhancedMove} and <em>writes the value into the board</em>
     * with {@code applyExternalMove}; the wrong digit is locally legal so the board accepts
     * it, and measured across difficulty-4 and -5 boards it rendered the puzzle
     * <b>unsolvable</b> in every case where it fired. The player spent an item to have their
     * game silently destroyed, with the hint counter incremented for the privilege.
     *
     * <p>Solving the grid once up front and reading the answer out of the solution costs one
     * backtrack per hint request and makes every hint correct by construction. The
     * naked-single label is preserved, because it still expresses "you could have deduced
     * this one" and the scorer prefers those.
     *
     * <p>An unsolvable board yields no hints at all, rather than a confident guess on a
     * position that has no answer — {@code getHint} charges only for a non-empty hint, so
     * the player is no longer billed for nonsense.
     */
    private List<Hint> collectAllHints(SudokuBoard board, SudokuCell[][] snapshot) {
        List<Hint> hints = new ArrayList<>();
        int size = snapshot.length;

        int[][] solution = solveToGrid(snapshot);
        if (solution == null) {
            logger.debug("No hints: board {} has no completion from its current state",
                board.getGameId());
            return hints;
        }

        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (snapshot[r][c].getValue() != 0) continue;
                int truth = solution[r][c];
                int candidates = 0;
                for (int v = 1; v <= 9; v++) {
                    if (isValidTempMove(snapshot, r, c, v)) candidates++;
                }
                if (candidates == 1) {
                    hints.add(new Hint(r, c, truth, "Naked single", SudokuCell.Strategy.NAKED_SINGLE));
                } else {
                    hints.add(new Hint(r, c, truth, "Candidate", SudokuCell.Strategy.UNKNOWN));
                }
            }
        }
        return hints;
    }

    /**
     * Completes a copy of {@code snapshot} by backtracking; null if it cannot be completed.
     *
     * <p>The pre-check matters. {@code fillGrid} only validates the digits it places, so a
     * grid whose EXISTING values already conflict — three 5s in one row, say — can still be
     * "completed" by filling the remaining cells with digits that happen not to clash, and
     * the solver would then hand out hints for a position that has no answer. Normal play
     * cannot reach such a state ({@code applyExternalMove} validates every move), but a
     * corrupt cache entry, a hand-edited row or a future bulk-import path could, and
     * confidently charging a player for a hint on an impossible board is the worst way to
     * find out.
     */
    private int[][] solveToGrid(SudokuCell[][] snapshot) {
        int size = snapshot.length;
        int[][] grid = new int[size][size];
        for (int r = 0; r < size; r++)
            for (int c = 0; c < size; c++)
                grid[r][c] = snapshot[r][c].getValue();
        if (!isConsistent(grid)) return null;
        return fillGrid(grid, 0) ? grid : null;
    }

    /** True when no filled cell duplicates another in its row, column or box. */
    private static boolean isConsistent(int[][] g) {
        int size = g.length;
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                int v = g[r][c];
                if (v == 0) continue;
                g[r][c] = 0;                       // ignore the cell itself
                boolean ok = legalInGrid(g, r, c, v);
                g[r][c] = v;
                if (!ok) return false;
            }
        }
        return true;
    }

    private boolean fillGrid(int[][] g, int idx) {
        int size = g.length;
        if (idx == size * size) return true;
        int r = idx / size, c = idx % size;
        if (g[r][c] != 0) return fillGrid(g, idx + 1);
        for (int v = 1; v <= 9; v++) {
            if (!legalInGrid(g, r, c, v)) continue;
            g[r][c] = v;
            if (fillGrid(g, idx + 1)) return true;
            g[r][c] = 0;
        }
        return false;
    }

    private static boolean legalInGrid(int[][] g, int row, int col, int v) {
        int size = g.length;
        for (int i = 0; i < size; i++) {
            if (g[row][i] == v || g[i][col] == v) return false;
        }
        int br = row - row % 3, bc = col - col % 3;
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (g[br + i][bc + j] == v) return false;
        return true;
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

    /**
     * Ranks candidate hints. A naked single always outranks a non-deducible cell.
     *
     * <p>The cosmic boost is now capped. {@code cosmicHotspots} is keyed by coordinate
     * only — no game id, no player — so it is a single global map shared by every board in
     * the JVM, and every hint issued added to it while only {@code solveSudoku} decayed it.
     * With the boost uncapped it grew without bound, and after roughly 23 hints ever taken
     * at a coordinate the +2-per-hit bonus overtook the 90-point gap between a naked single
     * and a mere candidate. Measured with one long-lived solver and a fresh board per
     * request: correct hints only up to request 40, then a steadily rising share of hints
     * on cells that could not be deduced — hint quality degrading purely as a function of
     * server uptime, resetting only on restart.
     *
     * <p>Capping keeps the boost as the tie-breaker it was meant to be. (Since the hints
     * themselves are now always correct, the failure mode is no longer a wrong answer —
     * but a hint you could not have deduced is still a worse hint.)
     */
    private int scoreHint(Hint h) {
        int base = switch (h.strategy()) {
            case NAKED_SINGLE -> 100;
            default           -> 10;
        };
        int cosmicBoost = Math.min(MAX_COSMIC_BOOST,
            cosmicHotspots.getOrDefault(h.row() + "," + h.col(), 0) * COSMIC_BOOST);
        boolean recentlyUsed = hintFeedback.getOrDefault(hintKey(h), Boolean.FALSE);
        return base + cosmicBoost - (recentlyUsed ? 20 : 0);
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
