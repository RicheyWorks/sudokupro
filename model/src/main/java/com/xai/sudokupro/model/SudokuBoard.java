package com.xai.sudokupro.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xai.sudokupro.util.Constants;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Entity
@Table(name = "sudoku_boards")
// Older serialized copies (Redis cache entries written before the save/load work)
// carry properties this class no longer exposes (e.g. the raw "board" grid); ignore
// them instead of failing deserialization.
@JsonIgnoreProperties(ignoreUnknown = true)
public class SudokuBoard implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(SudokuBoard.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom BOARD_RNG = new SecureRandom();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Transient @NotNull private SudokuCell[][] board;
    private final int size = Constants.BOARD_SIZE;

    // Persisted snapshot of the live (transient) grid. Kept in sync by the JPA
    // lifecycle callbacks below so a board loaded back from the database — or from
    // the Redis cache via the Jackson property of the same name — comes back with
    // its real cells instead of the blank grid the no-arg constructor builds.
    // (Save/load feature; also fixes the DB/Redis read-through grid loss.)
    @Column(name = "cells_json", columnDefinition = "text")
    private String cellsJson;

    // Core state
    private int difficulty;
    private String playerId;
    private boolean chaosMode;
    private volatile boolean mirrorMode;
    private long timeLimitSeconds;
    private String gameId;
    // @JsonProperty on the fields below lets Jackson (the Redis cache serializer)
    // restore them on read — they have getters but deliberately no public setters,
    // so without the annotation a cache round-trip silently reset them.
    @JsonProperty private LocalDateTime startTime;

    // Persisted derived state (computed fields are @Transient and can't be queried)
    @JsonProperty private boolean solved = false;
    @JsonProperty private long solveTimeSeconds = 0L;
    @JsonProperty private int moveCount = 0;

    /**
     * True once this board's completion has paid out (gems, XP, achievements,
     * streaks, duel results). Persisted so the payout survives a restart or a
     * cache eviction, because {@code POST /api/game/{id}/end} re-hydrates a
     * finished board into the active set and could otherwise fire every reward
     * listener again on each call — a trivial unbounded currency farm
     * (measured: +15 gems and +15 XP per replayed request).
     * Only ever set for a SOLVED board: abandoning an unfinished game must not
     * poison a later, legitimate completion of the same game after a resume.
     */
    @Column(name = "rewards_granted")
    @JsonProperty private boolean rewardsGranted = false;

    // Lives / scoring
    private int lives      = 3;
    private int maxLives   = 3;
    @JsonProperty private int revives = 0;
    private int score      = 0;

    // Modes
    private boolean cosmicMode;
    private int     cosmicEvents;
    private boolean timeAttack;
    private boolean infiniteMode;
    @JsonProperty private boolean tensRule;
    @JsonProperty private boolean diagonalRules;

    // Move history
    @Transient private final Deque<Move>        moveHistory    = new ArrayDeque<>();
    @Transient private final Deque<Move>        redoStack      = new ArrayDeque<>();
    @Transient private final List<EnhancedMove> replayHistory  = new ArrayList<>();
    @Transient private final Map<String, String> replayMetadata = new HashMap<>();

    // Analytics
    @Transient private final Map<String, Integer> heatmapMistakeCounter = new HashMap<>();
    @JsonProperty private int      hintCount;
    @JsonProperty private boolean  usedUndo;
    @Transient private Duration solveTime = Duration.ZERO;
    @JsonProperty private int      cosmicDripLevel;

    // =====================================================================
    // Constructors
    // =====================================================================

    /** Required by JPA — do not use directly. */
    protected SudokuBoard() {
        this.board = new SudokuCell[9][9];
        for (int i = 0; i < 9; i++)
            for (int j = 0; j < 9; j++)
                this.board[i][j] = new SudokuCell();
        this.startTime = LocalDateTime.now();
        this.solveTime = Duration.ZERO;
    }

    /** Generate a new board from difficulty (1-5). */
    public SudokuBoard(int difficulty, boolean chaosMode, boolean mirrorMode,
                       long timeLimitSeconds, String gameId) {
        this.board = new SudokuCell[9][9];
        for (int i = 0; i < 9; i++)
            for (int j = 0; j < 9; j++)
                this.board[i][j] = new SudokuCell();
        this.difficulty = difficulty;
        this.chaosMode = chaosMode;
        this.mirrorMode = mirrorMode;
        this.timeLimitSeconds = timeLimitSeconds;
        this.timeAttack = timeLimitSeconds > 0;
        this.gameId = gameId != null ? gameId : UUID.randomUUID().toString();
        this.startTime = LocalDateTime.now();
        generateBoard(difficulty);
        this.cosmicDripLevel = calculateCosmicDripLevel();
        logger.info("SudokuBoard generated difficulty={} gameId={}", difficulty, this.gameId);
    }

    /** Build from pre-constructed cell array. */
    public SudokuBoard(SudokuCell[][] board, boolean chaosMode, boolean mirrorMode,
                       long timeLimitSeconds, String gameId) {
        this.board = Objects.requireNonNull(board, "Board cannot be null");
        this.chaosMode = chaosMode;
        this.mirrorMode = mirrorMode;
        this.timeLimitSeconds = timeLimitSeconds;
        this.timeAttack = timeLimitSeconds > 0;
        this.gameId = gameId != null ? gameId : UUID.randomUUID().toString();
        this.startTime = LocalDateTime.now();
        this.cosmicDripLevel = calculateCosmicDripLevel();
        if (!isValidBoardState()) throw new IllegalArgumentException("Invalid board state on creation");
        logger.info("SudokuBoard initialized for gameId: {}", this.gameId);
    }

    /**
     * Stamps a player-owned copy of {@code template}'s grid under a new game
     * identity — the primitive behind shared-puzzle features (daily puzzle,
     * duels): everyone plays the same cells, each on their own board.
     */
    public static SudokuBoard playerCopy(SudokuBoard template, String gameId, String playerId) {
        SudokuCell[][] blank = new SudokuCell[9][9];
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                blank[r][c] = new SudokuCell();
        SudokuBoard copy = new SudokuBoard(blank, false, false, 0, gameId);
        copy.restoreCells(template.snapshotCells());
        copy.setPlayerId(playerId);
        copy.setDifficulty(template.getDifficulty());
        return copy;
    }

    // =====================================================================
    // Grid persistence (save/load)
    // =====================================================================

    /**
     * Serializes the live grid to a compact JSON snapshot: a 9x9 array of
     * {@code {"v":value,"g":given,"ms":moveSource,"st":strategy,"pm":[...],"cf":[...]}}.
     *
     * <p>{@code st} (strategy) must be included: {@code calculateCosmicDripLevel()} counts
     * cells whose strategy is COSMIC or STARFORGE, so omitting it made every restored board
     * recompute a drip level of 0 — silently wiping the value on any DB or Redis
     * round-trip. Older snapshots simply have no {@code st} key and restore as before.
     * This is the format persisted in the {@code cells_json} column and carried
     * through the Redis cache; {@link #restoreCells(String)} is its inverse.
     */
    public synchronized String snapshotCells() {
        ArrayNode rows = MAPPER.createArrayNode();
        for (int i = 0; i < size; i++) {
            ArrayNode row = MAPPER.createArrayNode();
            for (int j = 0; j < size; j++) {
                SudokuCell cell = board[i][j];
                ObjectNode n = MAPPER.createObjectNode();
                n.put("v", cell.getValue());
                n.put("g", cell.isGiven());
                SudokuCell.MoveSource ms = cell.getMoveSource();
                n.put("ms", (ms != null ? ms : SudokuCell.MoveSource.UNKNOWN).name());
                SudokuCell.Strategy st = cell.getStrategy();
                if (st != null) n.put("st", st.name());
                Set<Integer> pm = cell.getPencilMarks();
                if (!pm.isEmpty()) {
                    ArrayNode a = n.putArray("pm");
                    pm.stream().sorted().forEach(a::add);
                }
                Set<Integer> cf = cell.getConflicts();
                if (!cf.isEmpty()) {
                    ArrayNode a = n.putArray("cf");
                    cf.stream().sorted().forEach(a::add);
                }
                row.add(n);
            }
            rows.add(row);
        }
        return rows.toString();
    }

    /**
     * Rebuilds the live grid from a snapshot produced by {@link #snapshotCells()}.
     * Builds the full replacement grid before swapping it in, so a malformed
     * snapshot leaves the current board untouched.
     *
     * @throws IllegalArgumentException if the snapshot is malformed
     */
    public synchronized void restoreCells(String json) {
        if (json == null || json.isBlank()) return;
        try {
            JsonNode rows = MAPPER.readTree(json);
            if (!rows.isArray() || rows.size() != size) {
                throw new IllegalArgumentException("Cell snapshot must contain " + size + " rows");
            }
            SudokuCell[][] restored = new SudokuCell[size][size];
            for (int i = 0; i < size; i++) {
                JsonNode row = rows.get(i);
                if (row == null || !row.isArray() || row.size() != size) {
                    throw new IllegalArgumentException("Cell snapshot row " + i + " must contain " + size + " cells");
                }
                for (int j = 0; j < size; j++) {
                    JsonNode n = row.get(j);
                    SudokuCell cell = new SudokuCell();
                    int v = n.path("v").asInt(0);
                    if (v != 0) {
                        SudokuCell.MoveSource ms;
                        try {
                            ms = SudokuCell.MoveSource.valueOf(
                                n.path("ms").asText(SudokuCell.MoveSource.UNKNOWN.name()));
                        } catch (IllegalArgumentException e) {
                            ms = SudokuCell.MoveSource.UNKNOWN;
                        }
                        cell.setValue(v, ms); // must precede setGiven — given cells refuse value changes
                    }
                    cell.setGiven(n.path("g").asBoolean(false));
                    String strategyName = n.path("st").asText(null);
                    if (strategyName != null && !strategyName.isBlank()) {
                        try {
                            cell.setStrategy(SudokuCell.Strategy.valueOf(strategyName));
                        } catch (IllegalArgumentException ignored) {
                            // Unknown strategy from an older/newer build — leave it unset.
                        }
                    }
                    for (JsonNode m : n.path("pm")) cell.addPencilMark(m.asInt());
                    for (JsonNode c : n.path("cf")) cell.addConflict(c.asInt());
                    restored[i][j] = cell;
                }
            }
            this.board = restored;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed cell snapshot", e);
        }
    }

    /**
     * Copies the live grid into the persisted {@code cellsJson} column. Callers
     * MUST invoke this immediately before {@code gameRepository.save(board)} on an
     * already-persisted board: saves of detached entities go through JPA merge,
     * which copies only persistent fields onto the managed copy — a
     * {@code @PreUpdate} callback would run on that managed copy (whose transient
     * grid was rebuilt from the OLD snapshot in {@code @PostLoad}) and clobber the
     * fresh state, which is why no such callback exists.
     */
    public synchronized void syncCellsJson() {
        this.cellsJson = snapshotCells();
    }

    // JPA lifecycle: @PrePersist covers brand-new entities (the instance being
    // persisted is the live one, so its grid is authoritative). Updates rely on
    // the explicit syncCellsJson() contract above.
    @PrePersist
    private void syncCellsJsonForInsert() {
        this.cellsJson = snapshotCells();
    }

    @PostLoad
    private void restoreGridAfterLoad() {
        if (cellsJson != null && !cellsJson.isBlank()) {
            restoreCells(cellsJson);
        }
        restoreSolveTimeFromSeconds();
    }

    /**
     * Rebuilds the {@code @Transient} {@link #solveTime} Duration from the persisted
     * {@link #solveTimeSeconds} column.
     *
     * <p>Without this, {@code getSolveTime()} returned {@link Duration#ZERO} for every
     * board that came back from the database or the Redis cache, however long the player
     * had actually taken. Three consumers read it and all three were wrong:
     * {@code AntiCheatEngine.detectCheating(board, user)} scored "solved impossibly fast"
     * against a rehydrated board (0 &lt; difficulty x 10s is always true) and
     * "sub-500ms per move" on top of it, manufacturing suspicion signals out of a
     * serialization artefact; {@code EventEngine.calculateEventScore} computed a zero
     * time penalty, so a slow solve replayed after a cache miss outscored the same solve
     * held in memory; and the solve-time analytics recorded 0 for those games.
     */
    private void restoreSolveTimeFromSeconds() {
        if (solveTimeSeconds > 0 && (solveTime == null || solveTime.isZero())) {
            solveTime = Duration.ofSeconds(solveTimeSeconds);
        }
    }

    // Jackson (Redis cache path): the same snapshot rides along the cached JSON
    // value, so a board read through Redis on another pod gets its real grid back.
    @JsonProperty("cellsJson")
    public synchronized String getCellsJson() {
        return snapshotCells();
    }

    @JsonProperty("cellsJson")
    public synchronized void setCellsJson(String json) {
        this.cellsJson = json;
        if (json != null && !json.isBlank()) {
            restoreCells(json);
        }
    }

    // =====================================================================
    // Board generation
    // =====================================================================

    private void generateBoard(int difficulty) {
        int[][] solved = new int[size][size];
        // Randomised candidate order — otherwise every generated board shares one solution.
        solve(solved, 0, 0, true);
        // Mark all cells as given initially
        for (int i = 0; i < size; i++)
            for (int j = 0; j < size; j++) {
                board[i][j].setValue(solved[i][j], SudokuCell.MoveSource.INITIAL);
                board[i][j].setGiven(true);
            }
        // Remove cells based on difficulty 1-5 → 28-56 removed.
        // Each candidate removal is validated with hasUniqueSolution() so the puzzle
        // always has exactly one solution.  The snapshot is rebuilt from the live board
        // each time because setValue() mutates cells in-place.
        int toRemove = Math.min(28 + (difficulty - 1) * 7, 56);
        int attempts = 0;
        int removed  = 0;
        while (removed < toRemove && attempts < 500) {
            int row = BOARD_RNG.nextInt(size);
            int col = BOARD_RNG.nextInt(size);
            if (board[row][col].getValue() != 0) {
                int saved = board[row][col].getValue();
                // Bug fix (same phantom-removal bug AUDIT Phase 1 fixed in SudokuGenerator):
                // setValue(0) silently REFUSES to modify a cell while isGiven is true, so
                // clearing before un-marking counted a "removal" that never happened —
                // boards shipped with only 4-9 empty cells instead of 28-49. Un-mark first.
                board[row][col].setGiven(false);
                board[row][col].setValue(0);
                if (hasUniqueSolution(copyBoard())) {
                    removed++;
                } else {
                    // Removing this cell breaks uniqueness — put it back
                    board[row][col].setValue(saved);
                    board[row][col].setGiven(true);
                }
            }
            attempts++;
        }
    }

    // =====================================================================
    // Move validation
    // =====================================================================

    /**
     * Whether {@code value} may legally go in this cell. A pure query — it records nothing.
     *
     * <p>It used to increment {@code heatmapMistakeCounter} on every false result, which is
     * wrong for a public predicate: it is called to PROBE legality (by the WebSocket
     * handler before applying, by the apply paths themselves, by the solver and by clients
     * listing candidates), so a single rejected move was counted twice and merely asking
     * "which digits fit here?" logged a mistake per digit that did not. The heatmap gates
     * {@link #isPerfectClear()}, so probing could silently cost a flawless solve. Mistakes
     * are now recorded only where a move is genuinely rejected, by
     * {@link #recordMistake(int, int)}.
     */
    public synchronized boolean isValidMove(int row, int col, int value) {
        // Clearing a cell is always legal on an editable cell. The duplicate scan below
        // compares against 0 as if it were a digit, and an unfinished board always has an
        // empty cell in the same row/column/box — so every clear was rejected. A player
        // could not erase a wrong entry through ANY move path (makeMove,
        // applyExternalMove, applyBatchMoves, and therefore the WebSocket move handler and
        // both clients' erase buttons); only a server-side undo could take a value back.
        if (value == 0) return true;
        return checkRow(row, value) && checkCol(col, value) && checkBox(row, col, value);
    }

    /** Records a genuinely rejected player attempt for the mistake heatmap. */
    private void recordMistake(int row, int col) {
        heatmapMistakeCounter.merge(row + "," + col, 1, Integer::sum);
    }

    private boolean checkRow(int row, int value) {
        for (int c = 0; c < size; c++) if (board[row][c].getValue() == value) return false;
        return true;
    }

    private boolean checkCol(int col, int value) {
        for (int r = 0; r < size; r++) if (board[r][col].getValue() == value) return false;
        return true;
    }

    private boolean checkBox(int row, int col, int value) {
        int[] s = getBoxStart(row, col);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (board[s[0]+i][s[1]+j].getValue() == value) return false;
        return true;
    }

    // =====================================================================
    // Move management
    // =====================================================================

    public synchronized void makeMove(int row, int col, int value, SudokuCell.MoveSource source) {
        if (!isCellEditable(row, col) || !isValidMove(row, col, value)) {
            logger.warn("Rejected move ({},{})={} by {}", row, col, value, source);
            recordMistake(row, col);
            return;
        }
        int oldVal = board[row][col].getValue();
        SudokuCell.MoveSource oldSource = board[row][col].getMoveSource();
        board[row][col].setValue(value, source);
        Move move = new Move(row, col, oldVal, value, source, oldSource);
        moveHistory.push(move);
        replayHistory.add(new EnhancedMove(row, col, oldVal, value, source));
        redoStack.clear();
        moveCount++;
        if (mirrorMode) applyMirrorMove(row, col, value, source);
        cosmicDripLevel = calculateCosmicDripLevel();
        refreshSolvedState();
    }

    /**
     * Applies an external move and broadcasts it only if it actually landed.
     *
     * <p>The broadcast used to be unconditional, so a move {@code applyExternalMove}
     * had just REJECTED was still announced to every peer. Their clients applied a value
     * the authoritative board never recorded and nothing ever corrected it — {@code sync}
     * is client-initiated and no error envelope is sent on this path.
     *
     * @return true if the board changed
     */
    public synchronized boolean applyMove(EnhancedMove move, MoveBroadcaster broadcaster) {
        boolean applied = applyExternalMove(move);
        if (applied && broadcaster != null) broadcaster.sendMove(gameId, move);
        return applied;
    }

    /** @return true if the move was accepted and the board changed. */
    public synchronized boolean applyExternalMove(EnhancedMove move) {
        if (move == null) return false;
        if (!isCellEditable(move.row(), move.col())
                || !isValidMove(move.row(), move.col(), move.newVal())) {
            recordMistake(move.row(), move.col());
            return false;
        }
        int oldVal = board[move.row()][move.col()].getValue();
        SudokuCell.MoveSource oldSource = board[move.row()][move.col()].getMoveSource();
        board[move.row()][move.col()].setValue(move.newVal(), move.source());
        moveHistory.push(new Move(move.row(), move.col(), oldVal, move.newVal(),
            move.source(), oldSource));
        replayHistory.add(move);
        redoStack.clear();
        moveCount++;
        if (mirrorMode) applyMirrorMove(move.row(), move.col(), move.newVal(), move.source());
        cosmicDripLevel = calculateCosmicDripLevel();
        refreshSolvedState();
        return true;
    }

    public synchronized void applyBatchMoves(List<EnhancedMove> moves) {
        if (moves == null || moves.isEmpty()) return;
        int applied = 0;
        for (EnhancedMove m : moves) {
            if (isCellEditable(m.row(), m.col()) && isValidMove(m.row(), m.col(), m.newVal())) {
                int oldVal = board[m.row()][m.col()].getValue();
                SudokuCell.MoveSource oldSource = board[m.row()][m.col()].getMoveSource();
                board[m.row()][m.col()].setValue(m.newVal(), m.source());
                moveHistory.push(new Move(m.row(), m.col(), oldVal, m.newVal(),
                    m.source(), oldSource));
                replayHistory.add(m);
                applied++;
                if (mirrorMode) applyMirrorMove(m.row(), m.col(), m.newVal(), m.source());
            }
        }
        redoStack.clear();
        // Count what was actually applied, not what was offered. This was
        // `moveCount += moves.size()`, so every rejected move still inflated the
        // counter — and jumpToMove()/loadReplayFromJson() inherited the inflation.
        // Anti-cheat move-rate scoring and the player's own stats read this number.
        moveCount += applied;
        cosmicDripLevel = calculateCosmicDripLevel();
        refreshSolvedState();
    }

    private void applyMirrorMove(int row, int col, int value, SudokuCell.MoveSource source) {
        int mr = size - 1 - row, mc = size - 1 - col;
        if (isCellEditable(mr, mc) && isValidMove(mr, mc, value)) {
            int oldVal = board[mr][mc].getValue();
            SudokuCell.MoveSource oldSource = board[mr][mc].getMoveSource();
            board[mr][mc].setValue(value, source);
            // Flagged as the mirror twin so undo/redo treat the pair as ONE player action.
            moveHistory.push(new Move(mr, mc, oldVal, value, source, oldSource, true));
            replayHistory.add(new EnhancedMove(mr, mc, oldVal, value, source));
        }
    }

    /**
     * Drops the trailing {@code replayHistory} entry that corresponds to a move just popped
     * off {@code moveHistory}.
     *
     * <p>Every writer appends to both lists in the same order and one-for-one — makeMove,
     * applyExternalMove, applyBatchMoves, applyMirrorMove, autoSolve and redo all do. So the
     * last replay entry always describes the last move, and undoing a move means retracting
     * its replay entry too.
     *
     * <p>This did not exist, and {@code redo()} appends unconditionally, so an
     * undo/redo/undo/redo cycle grew {@code replayHistory} by one entry per redo and never
     * shrank it. That is a live WebSocket path — a player tapping undo/redo while thinking
     * grows an unbounded in-memory list on the server, and every export, jump and replay
     * afterwards replays the same move over and over as though the player had entered it
     * repeatedly. The board state stayed correct, which is why nothing caught it.
     */
    private void dropLastReplayEntry() {
        if (!replayHistory.isEmpty()) replayHistory.remove(replayHistory.size() - 1);
    }

    public synchronized void undo() {
        if (moveHistory.isEmpty()) return;
        Move move = moveHistory.pop();
        // Restore the value AND the source it had before this move; the single-arg
        // setValue() would reset the source to UNKNOWN.
        board[move.row()][move.col()].setValue(move.oldVal(), move.oldSource());
        redoStack.push(move);
        dropLastReplayEntry();
        // In mirror mode one player action writes two cells and pushes two entries (the
        // twin last). Undoing only the twin left the primary cell filled, so the move
        // could not be cleanly taken back. Revert the pair as a unit.
        if (move.mirrored() && !moveHistory.isEmpty()) {
            Move primary = moveHistory.pop();
            board[primary.row()][primary.col()].setValue(primary.oldVal(), primary.oldSource());
            redoStack.push(primary);
            dropLastReplayEntry();
        }
        usedUndo = true;
        cosmicDripLevel = calculateCosmicDripLevel();
        refreshSolvedState();
    }

    /**
     * Re-applies the most recently undone move. Returns the re-applied move — the PRIMARY
     * one in mirror mode — or null if there is nothing to redo.
     *
     * <p>Mirror mode writes two cells per player action, and {@code undo} reverts both and
     * pushes both (twin first, so the primary ends up on top). Redo used to pop exactly one,
     * which restored the primary cell and left its twin empty: the board came back in a state
     * the game cannot otherwise produce, and — because the twin's {@code Move} was still
     * sitting on the redo stack — the next redo would fill a mirrored cell with no visible
     * cause. That half-state persists, since the board is serialized to Redis and the database
     * as-is. Redo now restores the pair as one unit, mirroring what undo takes back.
     */
    public synchronized EnhancedMove redo() {
        if (redoStack.isEmpty()) return null;
        Move move = redoStack.pop();
        // Re-apply with the ORIGINAL source. Without this an AUTOSOLVE cell came back as
        // UNKNOWN, so hasAutosolvedCells() went false and the board could claim rewards.
        board[move.row()][move.col()].setValue(move.newVal(), move.source());
        moveHistory.push(move);
        EnhancedMove em = new EnhancedMove(move.row(), move.col(), move.oldVal(), move.newVal(), move.source());
        replayHistory.add(em);
        // The twin sits immediately beneath its primary on the redo stack (undo pushed it
        // first). Restore it in the same action so the pair is never half-applied.
        if (!redoStack.isEmpty() && redoStack.peek().mirrored()) {
            Move twin = redoStack.pop();
            board[twin.row()][twin.col()].setValue(twin.newVal(), twin.source());
            moveHistory.push(twin);
            replayHistory.add(new EnhancedMove(twin.row(), twin.col(), twin.oldVal(),
                twin.newVal(), twin.source()));
        }
        cosmicDripLevel = calculateCosmicDripLevel();
        refreshSolvedState();
        return em;
    }

    public synchronized void reset() {
        for (int i = 0; i < size; i++)
            for (int j = 0; j < size; j++)
                if (!board[i][j].isGiven()) { board[i][j].setValue(0); board[i][j].clearPencilMarks(); }
        moveHistory.clear(); redoStack.clear(); replayHistory.clear();
        heatmapMistakeCounter.clear(); replayMetadata.clear();
        hintCount = 0; usedUndo = false; solveTime = Duration.ZERO;
        solved = false; solveTimeSeconds = 0L; moveCount = 0;
        startTime = LocalDateTime.now();
        cosmicDripLevel = calculateCosmicDripLevel();
        logger.info("Board reset for gameId: {}", gameId);
    }

    // =====================================================================
    // Auto-solve
    // =====================================================================

    public synchronized void autoSolve() {
        int[][] temp = copyBoard();
        if (solve(temp, 0, 0)) {
            List<EnhancedMove> moves = new ArrayList<>();
            for (int i = 0; i < size; i++)
                for (int j = 0; j < size; j++)
                    if (!board[i][j].isGiven() && temp[i][j] != board[i][j].getValue()) {
                        int old = board[i][j].getValue();
                        board[i][j].setValue(temp[i][j], SudokuCell.MoveSource.AUTOSOLVE);
                        moveHistory.push(new Move(i, j, old, temp[i][j], SudokuCell.MoveSource.AUTOSOLVE));
                        moves.add(new EnhancedMove(i, j, old, temp[i][j], SudokuCell.MoveSource.AUTOSOLVE));
                    }
            replayHistory.addAll(moves);
            moveCount += moves.size();
            solveTime = Duration.between(startTime, LocalDateTime.now());
            solved = true;
            solveTimeSeconds = solveTime.getSeconds();
            cosmicDripLevel = calculateCosmicDripLevel();
        }
    }

    private boolean solve(int[][] b, int row, int col) {
        return solve(b, row, col, false);
    }

    /**
     * Backtracking fill. When {@code randomise} is true, a FRESH 1..9 permutation is drawn
     * at every cell; otherwise candidates are tried in plain ascending order.
     *
     * <p>Generation MUST randomise. Two separate bugs have lived here.
     *
     * <p>The first: a fixed ascending order made this function fully deterministic, so
     * {@code generateBoard} produced the SAME completed grid for every game ever created
     * and only the clue positions varied. Solving one puzzle gave you the answer to every
     * board on the platform.
     *
     * <p>The second — the fix for the first, which did not go far enough. Drawing ONE
     * permutation and reusing it at every cell only renames the digits: the backtracker
     * still walks the identical search path, so all 40 boards in a spread across every
     * difficulty canonicalised (relabel row 0 to 1..9) to a single grid. Nine clues, one
     * per digit, pin the permutation and hence the entire solution, with no solving
     * required — which defeats the hint economy, clean-solve bonuses, achievements,
     * streaks and the anti-cheat move model in one step.
     *
     * <p>The engine harness missed it because its diversity check compared completed grids
     * literally, and relabelled grids differ literally. It now canonicalises first.
     *
     * <p>Re-drawing per cell is what actually randomises the structure — the same thing
     * {@code SudokuGenerator.solveSudoku} has always done, which is why the daily, duel
     * and tournament boards built through that path were never affected.
     */
    private boolean solve(int[][] b, int row, int col, boolean randomise) {
        if (row == size) return true;
        if (col == size) return solve(b, row + 1, 0, randomise);
        if (b[row][col] != 0) return solve(b, row, col + 1, randomise);
        int[] order = randomise ? shuffledDigits() : null;
        for (int i = 1; i <= 9; i++) {
            int n = (order == null) ? i : order[i - 1];
            if (isValidTempMove(b, row, col, n)) {
                b[row][col] = n;
                if (solve(b, row, col + 1, randomise)) return true;
                b[row][col] = 0;
            }
        }
        return false;
    }

    /** A fresh 1..9 permutation from the board RNG, for randomised generation. */
    private static int[] shuffledDigits() {
        int[] digits = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        for (int i = digits.length - 1; i > 0; i--) {
            int j = BOARD_RNG.nextInt(i + 1);
            int tmp = digits[i];
            digits[i] = digits[j];
            digits[j] = tmp;
        }
        return digits;
    }

    // =====================================================================
    // Chaos / game-mode methods (called by FateEntityManager, GameService)
    // =====================================================================

    public synchronized void evolveChaos() {
        if (!chaosMode) return;
        int events = Constants.BOARD_SIZE; // use a safe default
        // Bug fix: unbounded while loop hangs forever when all non-zero cells are givens.
        // Add a hard attempt cap (size*size*2) so we don't spin indefinitely.
        int maxAttempts = size * size * 2;
        List<EnhancedMove> chaosMoves = new ArrayList<>();
        while (events > 0 && maxAttempts-- > 0) {
            int r = BOARD_RNG.nextInt(size), c = BOARD_RNG.nextInt(size);
            if (board[r][c].getValue() != 0 && !board[r][c].isGiven()) {
                int old = board[r][c].getValue();
                board[r][c].setValue(0);
                chaosMoves.add(new EnhancedMove(r, c, old, 0, SudokuCell.MoveSource.CHAOS));
                moveHistory.push(new Move(r, c, old, 0, SudokuCell.MoveSource.CHAOS));
                events--;
            }
        }
        if (maxAttempts <= 0 && events > 0) {
            logger.warn("evolveChaos: could not clear {} more cells — no eligible non-given cells found", events);
        }
        replayHistory.addAll(chaosMoves);
        cosmicDripLevel = calculateCosmicDripLevel();
    }

    public synchronized void shuffleRandomRow(Object rand) {
        int row = BOARD_RNG.nextInt(size);
        List<Integer> vals = new ArrayList<>();
        for (int c = 0; c < size; c++) vals.add(board[row][c].getValue());
        Collections.shuffle(vals, BOARD_RNG);
        for (int c = 0; c < size; c++) board[row][c].setValue(vals.get(c));
        logger.debug("Shuffled row {} in gameId {}", row, gameId);
    }

    public synchronized void invertRandomBox(Object rand) {
        int boxRow = BOARD_RNG.nextInt(3) * 3, boxCol = BOARD_RNG.nextInt(3) * 3;
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) {
                int v = board[boxRow+i][boxCol+j].getValue();
                if (v != 0 && !board[boxRow+i][boxCol+j].isGiven())
                    board[boxRow+i][boxCol+j].setValue(10 - v); // invert 1-9
            }
    }

    public synchronized void addCosmicHint(Object solver) {
        hintCount++;
        logger.debug("Cosmic hint consumed for gameId {}", gameId);
    }

    public synchronized void swapRows(int r1, int r2) {
        SudokuCell[] tmp = board[r1];
        board[r1] = board[r2];
        board[r2] = tmp;
        logger.debug("Swapped rows {} and {} in gameId {}", r1, r2, gameId);
    }

    public synchronized void enableTensRule()     { this.tensRule = true; }
    public synchronized void enableDiagonalRules(){ this.diagonalRules = true; }

    public synchronized void addTimeBonus(int seconds) {
        timeLimitSeconds += seconds;
    }

    public synchronized void addTimePenalty(int seconds) {
        timeLimitSeconds = Math.max(0, timeLimitSeconds - seconds);
    }

    public synchronized void reduceLives(int amount) {
        this.lives = Math.max(0, this.lives - amount);
    }

    public synchronized void addRevive() { this.revives++; }

    public synchronized void applyPenalty(int penalty) {
        this.score = Math.max(0, this.score - penalty);
    }

    public synchronized void multiplyScore(double factor) {
        this.score = (int)(this.score * factor);
    }

    // =====================================================================
    // Replay
    // =====================================================================

    /** Replays move history; each line is fed to {@code output} (UI-agnostic — AUDIT P1-2). */
    public void replayMoves(long delayMs, java.util.function.Consumer<String> output) {
        // Fix: was 'synchronized' but called Thread.sleep() inside the loop, blocking every
        // other board operation for the full replay duration. Snapshot the history under a
        // brief lock, then apply each move (locked individually) and sleep without the lock.
        //
        // Second fix: the snapshot was taken AFTER reset(), and reset() clears
        // replayHistory. So the snapshot was always empty and the loop below never ran —
        // "watch this game back" cleared the player's board, printed nothing, and destroyed
        // the very history it was about to replay. Snapshot first, then reset.
        List<EnhancedMove> snapshot;
        synchronized (this) {
            snapshot = new java.util.ArrayList<>(replayHistory);
            reset();
        }
        for (EnhancedMove move : snapshot) {
            synchronized (this) {
                board[move.row()][move.col()].setValue(move.newVal(), move.source());
            }
            if (output != null)
                output.accept(String.format("Move: (%d,%d) -> %d [%s]\n",
                    move.row()+1, move.col()+1, move.newVal(), move.source()));
            try { Thread.sleep(delayMs); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        synchronized (this) {
            cosmicDripLevel = calculateCosmicDripLevel();
            if (isSolved()) {
                solveTime = Duration.between(startTime, LocalDateTime.now());
                solved = true;
                solveTimeSeconds = solveTime.getSeconds();
            }
        }
    }

    /**
     * Rewinds the board to the state it held immediately after move {@code index}.
     *
     * <p>This threw {@link IndexOutOfBoundsException} on every call it did not reject, and
     * wiped the board on the way out. {@code reset()} clears {@code replayHistory}, and the
     * {@code subList} view was taken <em>after</em> the reset — so the bounds check passed
     * against the real history, then the list it indexed into was empty. A scrub-bar drag,
     * a post-game review, any jump at all: board cleared, exception thrown, history gone.
     * Only {@code index == 0} on an empty history escaped, by returning early.
     *
     * <p>The snapshot is now taken first, and copied rather than viewed — {@code subList}
     * returns a live window onto {@code replayHistory}, which {@code applyBatchMoves}
     * appends to as it goes.
     */
    public synchronized void jumpToMove(int index) {
        if (index < 0 || index >= replayHistory.size()) return;
        List<EnhancedMove> upTo = new ArrayList<>(replayHistory.subList(0, index + 1));
        reset();
        applyBatchMoves(upTo);
    }

    /**
     * Replaces the board's history with a replay timeline previously produced by
     * {@link #exportMoveTimelineJson()}.
     *
     * <p>Parsing is now complete before anything is destroyed. It used to {@code reset()}
     * and then map the timeline, so a single malformed entry — a missing {@code row} key
     * (NPE), a non-numeric value (ClassCastException), a {@code source} that is not a
     * {@link SudokuCell.MoveSource} (IllegalArgumentException) — cleared the player's board
     * and every move they had made, and only then threw. The caller sees
     * "Invalid replay JSON" and reasonably assumes nothing happened; the game is already
     * gone. Rejecting bad input must leave the board exactly as it was.
     */
    public synchronized void loadReplayFromJson(String json) {
        List<EnhancedMove> moves;
        try {
            List<Map<String,Object>> timeline = MAPPER.readValue(json, new TypeReference<>(){});
            moves = timeline.stream().map(d -> new EnhancedMove(
                ((Number)d.get("row")).intValue(), ((Number)d.get("col")).intValue(),
                ((Number)d.get("from")).intValue(), ((Number)d.get("to")).intValue(),
                SudokuCell.MoveSource.valueOf((String)d.get("source"))
            )).collect(Collectors.toList());
        } catch (Exception e) { throw new IllegalArgumentException("Invalid replay JSON", e); }
        reset();
        applyBatchMoves(moves);
    }

    public synchronized String exportMoveTimelineJson() {
        try {
            return MAPPER.writeValueAsString(replayHistory.stream()
                .map(m -> Map.of("row",m.row(),"col",m.col(),"from",m.oldVal(),"to",m.newVal(),"source",m.source().toString()))
                .collect(Collectors.toList()));
        } catch (Exception e) { throw new RuntimeException("Export failed", e); }
    }

    // =====================================================================
    // State checks
    // =====================================================================

    /**
     * Keeps the persisted {@code solved} flag in step with the live grid.
     *
     * <p>Previously every mutation only ever set the flag TRUE and nothing cleared it, so
     * undoing (or now erasing) a cell on a finished board left {@code solved=true} while
     * {@code isSolved()} reported false. A board persisted in that state is filtered out of
     * {@code GameRepository.findResumableByPlayerId} (which requires {@code solved = false}),
     * permanently hiding a game the player can still finish.
     *
     * <p>Note this deliberately does NOT touch {@code rewardsGranted}: a board that has
     * already paid out must not pay again just because it was un-solved and re-solved.
     */
    private void refreshSolvedState() {
        if (isSolved()) {
            if (!solved) {
                solveTime = Duration.between(startTime, LocalDateTime.now());
                solved = true;
                solveTimeSeconds = solveTime.getSeconds();
            }
        } else if (solved) {
            solved = false;
            solveTimeSeconds = 0L;
            solveTime = Duration.ZERO;
        }
    }

    public boolean isSolved() {
        for (int i = 0; i < size; i++)
            for (int j = 0; j < size; j++) {
                int v = board[i][j].getValue();
                if (v == 0) return false;
                // Check uniqueness without mutating the board — skip the cell itself
                if (!isValueUniqueAt(i, j, v)) return false;
            }
        return true;
    }

    /** Returns true when {@code value} does not appear elsewhere in the same row, column, or 3×3 box. */
    private boolean isValueUniqueAt(int row, int col, int value) {
        for (int c = 0; c < size; c++)
            if (c != col && board[row][c].getValue() == value) return false;
        for (int r = 0; r < size; r++)
            if (r != row && board[r][col].getValue() == value) return false;
        int[] s = getBoxStart(row, col);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if ((s[0]+i != row || s[1]+j != col) && board[s[0]+i][s[1]+j].getValue() == value)
                    return false;
        return true;
    }

    public boolean isPerfectClear() {
        return isSolved() && hintCount == 0 && !usedUndo && heatmapMistakeCounter.isEmpty();
    }

    public boolean isValid()           { return isValidBoardState(); }

    public boolean isValidBoardState() {
        for (int r = 0; r < size; r++)
            for (int c = 0; c < size; c++) {
                int v = board[r][c].getValue();
                if (v < 0 || v > 9) return false;
            }
        return timeLimitSeconds >= 0;
    }

    // =====================================================================
    // Analytics
    // =====================================================================

    public synchronized Map<String,Integer> getColorizedHeatmap() {
        Map<String,Integer> m = new HashMap<>();
        heatmapMistakeCounter.forEach((k,v) -> m.put(k, v >= 3 ? 2 : v >= 1 ? 1 : 0));
        return m;
    }

    public synchronized String generateAsciiBoard() {
        StringBuilder sb = new StringBuilder("+-------+-------+-------+\n");
        for (int i = 0; i < size; i++) {
            sb.append("| ");
            for (int j = 0; j < size; j++) {
                int v = board[i][j].getValue();
                sb.append(v == 0 ? "." : v).append(" ");
                if (j % 3 == 2) sb.append("| ");
            }
            sb.append("\n");
            if (i % 3 == 2) sb.append("+-------+-------+-------+\n");
        }
        return sb.toString();
    }

    public synchronized String exportPuzzleStateJson() {
        try {
            Map<String,Object> state = new HashMap<>();
            int[][] grid = new int[size][size];
            for (int i = 0; i < size; i++)
                for (int j = 0; j < size; j++) grid[i][j] = board[i][j].getValue();
            state.put("grid", grid);
            state.put("chaosMode", chaosMode); state.put("mirrorMode", mirrorMode);
            state.put("timeLimitSeconds", timeLimitSeconds); state.put("hintCount", hintCount);
            state.put("usedUndo", usedUndo); state.put("solveTimeSeconds", solveTime.toSeconds());
            state.put("gameId", gameId); state.put("cosmicDripLevel", cosmicDripLevel);
            state.put("score", score); state.put("lives", lives);
            return MAPPER.writeValueAsString(state);
        } catch (Exception e) { throw new RuntimeException("Export failed", e); }
    }

    public synchronized String exportMoves() {
        return moveHistory.stream()
            .map(m -> m.row() + "," + m.col() + "," + m.newVal())
            .collect(Collectors.joining(";"));
    }

    public synchronized String exportAnalytics() {
        try {
            return MAPPER.writeValueAsString(Map.of(
                "heatmap", heatmapMistakeCounter, "hintCount", hintCount,
                "solveTimeSeconds", solveTime.toSeconds(), "usedUndo", usedUndo,
                "cosmicDripLevel", cosmicDripLevel, "score", score));
        } catch (Exception e) { throw new RuntimeException("Export failed", e); }
    }

    public String getBoardHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < size; i++)
                for (int j = 0; j < size; j++) sb.append(board[i][j].getValue());
            sb.append(chaosMode).append(mirrorMode).append(timeLimitSeconds).append(cosmicDripLevel);
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) { String h = Integer.toHexString(0xff & b); if (h.length()==1) hex.append('0'); hex.append(h); }
            return hex.toString();
        } catch (Exception e) { throw new RuntimeException("Hash failed", e); }
    }

    public String generateRuleSignature() {
        return "chaos=" + chaosMode + ",mirror=" + mirrorMode
             + ",tens=" + tensRule + ",diagonal=" + diagonalRules;
    }

    /** Hint hook — returns empty list; AISolverService provides real hints. */
    public synchronized List<Hint> getAdvancedHint() { return new ArrayList<>(); }

    // =====================================================================
    // Serialization helpers
    // =====================================================================

    public synchronized void saveToFile(String filename) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(filename))) {
            oos.writeObject(this);
        }
    }

    public static SudokuBoard loadFromFile(String filename) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(filename))) {
            return (SudokuBoard) ois.readObject();
        }
    }

    // =====================================================================
    // Private utilities
    // =====================================================================

    private int[][] copyBoard() {
        int[][] c = new int[size][size];
        for (int i = 0; i < size; i++) for (int j = 0; j < size; j++) c[i][j] = board[i][j].getValue();
        return c;
    }

    private int[] getBoxStart(int row, int col) {
        return new int[]{row - row%3, col - col%3};
    }

    private boolean isValidTempMove(int[][] b, int row, int col, int value) {
        for (int i = 0; i < size; i++)
            if (b[row][i] == value || b[i][col] == value) return false;
        int[] s = getBoxStart(row, col);
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
            if (b[s[0]+i][s[1]+j] == value) return false;
        return true;
    }

    private int calculateCosmicDripLevel() {
        int n = 0;
        for (int i = 0; i < size; i++) for (int j = 0; j < size; j++) {
            SudokuCell.Strategy s = board[i][j].getStrategy();
            if (s == SudokuCell.Strategy.COSMIC || s == SudokuCell.Strategy.STARFORGE) n++;
        }
        return n;
    }

    private boolean hasUniqueSolution(int[][] b) { return countSolutions(b,0,0,0) == 1; }

    private int countSolutions(int[][] b, int row, int col, int count) {
        if (count > 1) return count;
        if (row == size) return count+1;
        if (col == size) return countSolutions(b,row+1,0,count);
        if (b[row][col] != 0) return countSolutions(b,row,col+1,count);
        for (int n = 1; n <= 9 && count <= 1; n++) {
            if (isValidTempMove(b,row,col,n)) { b[row][col]=n; count=countSolutions(b,row,col+1,count); b[row][col]=0; }
        }
        return count;
    }

    // =====================================================================
    // Getters & setters
    // =====================================================================

    public Long getId()                  { return id; }
    public void setId(Long id)           { this.id = id; }

    @JsonIgnore public SudokuCell[][] getBoard() { return board; }
    @JsonIgnore
    public SudokuCell[][] getBoardCopy() {
        SudokuCell[][] c = new SudokuCell[size][size];
        for (int i=0;i<size;i++) for (int j=0;j<size;j++) c[i][j]=board[i][j].clone();
        return c;
    }
    public SudokuCell getCell(int r,int c) { return board[r][c].clone(); }

    /**
     * True when {@code r,c} is a real cell on this board and is not a clue.
     *
     * <p>The bounds check is defence in depth, and deliberately so. The {@link EnhancedMove}
     * constructor rejects coordinates outside 0..8, so every move that arrives as a record
     * is already in range — but this method also takes raw ints, is public, and is the FIRST
     * thing each apply path calls. Without the guard an out-of-range argument reaches the
     * array index and surfaces as {@link ArrayIndexOutOfBoundsException} from deep inside
     * the board rather than as a rejected move. That exact shape has been shipped here once
     * before: {@code EnhancedMove} used to allow row -1, and the symptom was a raw
     * "Index -1 out of bounds for length 9" over the WebSocket. Returning false routes an
     * off-board coordinate through the ordinary rejected-move path instead.
     */
    public boolean isCellEditable(int r,int c) {
        if (r < 0 || r >= size || c < 0 || c >= size) return false;
        return !board[r][c].isGiven();
    }

    public int getDifficulty()  { return difficulty; }
    public void setDifficulty(int d) { this.difficulty = d; }

    public String getPlayerId() { return playerId; }
    public void setPlayerId(String id) { this.playerId = id; }

    public int  getLives()    { return lives; }
    public void setLives(int l) { this.lives = Math.max(0, l); }
    public int  getMaxLives() { return maxLives; }
    public void setMaxLives(int m) { this.maxLives = Math.max(1,m); }

    public int  getScore()    { return score; }
    public void setScore(int s) { this.score = Math.max(0,s); }

    public boolean isChaosMode()  { return chaosMode; }
    public void setChaosMode(boolean v) { this.chaosMode = v; }
    public boolean isMirrorMode() { return mirrorMode; }
    public void setMirrorMode(boolean v) { this.mirrorMode = v; }
    public boolean isCosmicMode() { return cosmicMode; }
    public void setCosmicMode(boolean v) { this.cosmicMode = v; }
    public int  getCosmicEvents() { return cosmicEvents; }
    public void setCosmicEvents(int n) { this.cosmicEvents = n; }
    public boolean isTimeAttack()  { return timeAttack; }
    public void setTimeAttack(boolean v) { this.timeAttack = v; }
    public boolean isInfiniteMode() { return infiniteMode; }
    public void setInfiniteMode(boolean v) { this.infiniteMode = v; }
    public boolean isTensRule()     { return tensRule; }
    public boolean isDiagonalRules(){ return diagonalRules; }

    public long   getTimeLimitSeconds() { return timeLimitSeconds; }
    public void   setTimeLimitSeconds(long v) { this.timeLimitSeconds = v; }

    public String getGameId()  { return gameId; }
    public void   setGameId(String g) { this.gameId = g; }

    public LocalDateTime getStartTime() { return startTime; }

    public boolean isSolvedState()     { return solved; }

    /** True once a solved board has already paid out its rewards. */
    public boolean isRewardsGranted()  { return rewardsGranted; }
    /** Marks this board as having paid out; see {@link #rewardsGranted}. */
    public void setRewardsGranted(boolean granted) { this.rewardsGranted = granted; }
    public long    getSolveTimeSeconds() { return solveTimeSeconds; }

    /**
     * Jackson (Redis cache) counterpart to the JPA {@code @PostLoad} restore. The field
     * carries {@code @JsonProperty}, so without an explicit setter Jackson wrote straight
     * to it and the transient {@link #solveTime} Duration stayed at zero on the way back
     * out of the cache. Jackson prefers a setter over direct field access, so this is the
     * hook that keeps the cached and the database paths agreeing.
     */
    @JsonProperty("solveTimeSeconds")
    public synchronized void setSolveTimeSeconds(long seconds) {
        this.solveTimeSeconds = Math.max(0L, seconds);
        restoreSolveTimeFromSeconds();
    }
    public int     getMoveCount()       { return moveCount; }

    public int     getHintCount()       { return hintCount; }
    public synchronized void incrementHintCount() { this.hintCount++; }
    /** For rebuilding non-authoritative client boards from a BoardState snapshot. */
    public void setHintCount(int n)     { this.hintCount = Math.max(0, n); }
    /** For rebuilding non-authoritative client boards from a BoardState snapshot. */
    public void setMoveCount(int n)     { this.moveCount = Math.max(0, n); }

    /** True if any cell was filled by the auto-solver — such boards are not
     *  legitimate player solves and must not earn rewards. */
    public synchronized boolean hasAutosolvedCells() {
        for (int i = 0; i < size; i++)
            for (int j = 0; j < size; j++)
                if (board[i][j].getMoveSource() == SudokuCell.MoveSource.AUTOSOLVE) return true;
        return false;
    }

    /** True if any cell holds a value — false only for a blank 9x9 shell
     *  (e.g. a pre-V3 database row that has no cells_json snapshot to restore). */
    public synchronized boolean hasAnyCellValues() {
        for (int i = 0; i < size; i++)
            for (int j = 0; j < size; j++)
                if (board[i][j].getValue() != 0) return true;
        return false;
    }

    public boolean isUsedUndo()        { return usedUndo; }

    public Duration getSolveTime()     { return solveTime == null ? Duration.ZERO : solveTime; }

    public int  getCosmicDripLevel()   { return cosmicDripLevel; }

    public int  getRevives()           { return revives; }

    public synchronized Deque<Move> getMoveHistory() { return new ArrayDeque<>(moveHistory); }

    public synchronized List<EnhancedMove> getReplayHistory() { return new ArrayList<>(replayHistory); }

    // ── Inner types ────────────────────────────────────────────────────────

    /**
     * A recorded move. {@code source} is what the cell became; {@code oldSource} is what it
     * was, captured so undo can restore provenance rather than blanking it.
     *
     * <p>undo()/redo() used the single-argument {@code setValue(int)}, which resets a cell's
     * MoveSource to UNKNOWN. That lost hint/auto-solve provenance — and because
     * {@code hasAutosolvedCells()} is exactly what suppresses rewards on an AI-solved board,
     * an undo/redo round trip laundered an auto-solved board into a "legitimate" solve that
     * paid out gems, streaks and achievements.
     */
    public record Move(int row, int col, int oldVal, int newVal,
                       SudokuCell.MoveSource source, SudokuCell.MoveSource oldSource,
                       boolean mirrored) {
        /** Legacy 5-arg form: the prior source is unknown, so treat it as an initial cell. */
        public Move(int row, int col, int oldVal, int newVal, SudokuCell.MoveSource source) {
            this(row, col, oldVal, newVal, source, SudokuCell.MoveSource.INITIAL, false);
        }
        public Move(int row, int col, int oldVal, int newVal,
                    SudokuCell.MoveSource source, SudokuCell.MoveSource oldSource) {
            this(row, col, oldVal, newVal, source, oldSource, false);
        }
    }

    public record Hint(int row, int col, Object value, Strategy strategy) {
        public enum Strategy { NAKED_SINGLE, CANDIDATE, HIDDEN_SINGLE, POINTING_PAIR }
    }
}
