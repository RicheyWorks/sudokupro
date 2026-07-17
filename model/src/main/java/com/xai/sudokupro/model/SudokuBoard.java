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
     * {@code {"v":value,"g":given,"ms":moveSource,"pm":[...],"cf":[...]}}.
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
        solve(solved, 0, 0);
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

    public synchronized boolean isValidMove(int row, int col, int value) {
        boolean valid = checkRow(row, value) && checkCol(col, value) && checkBox(row, col, value);
        if (!valid) {
            String key = row + "," + col;
            heatmapMistakeCounter.merge(key, 1, Integer::sum);
        }
        return valid;
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
            return;
        }
        int oldVal = board[row][col].getValue();
        board[row][col].setValue(value, source);
        Move move = new Move(row, col, oldVal, value, source);
        moveHistory.push(move);
        replayHistory.add(new EnhancedMove(row, col, oldVal, value, source));
        redoStack.clear();
        moveCount++;
        if (mirrorMode) applyMirrorMove(row, col, value, source);
        cosmicDripLevel = calculateCosmicDripLevel();
        if (isSolved()) {
            solveTime = Duration.between(startTime, LocalDateTime.now());
            solved = true;
            solveTimeSeconds = solveTime.getSeconds();
        }
    }

    /** Apply an external move and optionally broadcast. */
    public synchronized void applyMove(EnhancedMove move, MoveBroadcaster broadcaster) {
        applyExternalMove(move);
        if (broadcaster != null) broadcaster.sendMove(gameId, move);
    }

    public synchronized void applyExternalMove(EnhancedMove move) {
        if (move == null || !isCellEditable(move.row(), move.col())
                || !isValidMove(move.row(), move.col(), move.newVal())) return;
        int oldVal = board[move.row()][move.col()].getValue();
        board[move.row()][move.col()].setValue(move.newVal(), move.source());
        moveHistory.push(new Move(move.row(), move.col(), oldVal, move.newVal(), move.source()));
        replayHistory.add(move);
        redoStack.clear();
        moveCount++;
        if (mirrorMode) applyMirrorMove(move.row(), move.col(), move.newVal(), move.source());
        cosmicDripLevel = calculateCosmicDripLevel();
        if (isSolved()) {
            solveTime = Duration.between(startTime, LocalDateTime.now());
            solved = true;
            solveTimeSeconds = solveTime.getSeconds();
        }
    }

    public synchronized void applyBatchMoves(List<EnhancedMove> moves) {
        if (moves == null || moves.isEmpty()) return;
        for (EnhancedMove m : moves) {
            if (isCellEditable(m.row(), m.col()) && isValidMove(m.row(), m.col(), m.newVal())) {
                int oldVal = board[m.row()][m.col()].getValue();
                board[m.row()][m.col()].setValue(m.newVal(), m.source());
                moveHistory.push(new Move(m.row(), m.col(), oldVal, m.newVal(), m.source()));
                replayHistory.add(m);
                if (mirrorMode) applyMirrorMove(m.row(), m.col(), m.newVal(), m.source());
            }
        }
        redoStack.clear();
        moveCount += moves.size();
        cosmicDripLevel = calculateCosmicDripLevel();
        if (isSolved()) {
            solveTime = Duration.between(startTime, LocalDateTime.now());
            solved = true;
            solveTimeSeconds = solveTime.getSeconds();
        }
    }

    private void applyMirrorMove(int row, int col, int value, SudokuCell.MoveSource source) {
        int mr = size - 1 - row, mc = size - 1 - col;
        if (isCellEditable(mr, mc) && isValidMove(mr, mc, value)) {
            int oldVal = board[mr][mc].getValue();
            board[mr][mc].setValue(value, source);
            moveHistory.push(new Move(mr, mc, oldVal, value, source));
            replayHistory.add(new EnhancedMove(mr, mc, oldVal, value, source));
        }
    }

    public synchronized void undo() {
        if (moveHistory.isEmpty()) return;
        Move move = moveHistory.pop();
        board[move.row()][move.col()].setValue(move.oldVal());
        redoStack.push(move);
        usedUndo = true;
        cosmicDripLevel = calculateCosmicDripLevel();
    }

    /** Returns the re-applied move, or null if nothing to redo. */
    public synchronized EnhancedMove redo() {
        if (redoStack.isEmpty()) return null;
        Move move = redoStack.pop();
        board[move.row()][move.col()].setValue(move.newVal());
        moveHistory.push(move);
        EnhancedMove em = new EnhancedMove(move.row(), move.col(), move.oldVal(), move.newVal(), move.source());
        replayHistory.add(em);
        cosmicDripLevel = calculateCosmicDripLevel();
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
        if (row == size) return true;
        if (col == size) return solve(b, row + 1, 0);
        if (b[row][col] != 0) return solve(b, row, col + 1);
        for (int n = 1; n <= 9; n++) {
            if (isValidTempMove(b, row, col, n)) {
                b[row][col] = n;
                if (solve(b, row, col + 1)) return true;
                b[row][col] = 0;
            }
        }
        return false;
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
        List<EnhancedMove> snapshot;
        synchronized (this) {
            reset();
            snapshot = new java.util.ArrayList<>(replayHistory);
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

    public synchronized void jumpToMove(int index) {
        if (index < 0 || index >= replayHistory.size()) return;
        reset();
        applyBatchMoves(replayHistory.subList(0, index + 1));
    }

    public synchronized void loadReplayFromJson(String json) {
        try {
            List<Map<String,Object>> timeline = MAPPER.readValue(json, new TypeReference<>(){});
            reset();
            List<EnhancedMove> moves = timeline.stream().map(d -> new EnhancedMove(
                ((Number)d.get("row")).intValue(), ((Number)d.get("col")).intValue(),
                ((Number)d.get("from")).intValue(), ((Number)d.get("to")).intValue(),
                SudokuCell.MoveSource.valueOf((String)d.get("source"))
            )).collect(Collectors.toList());
            applyBatchMoves(moves);
        } catch (Exception e) { throw new IllegalArgumentException("Invalid replay JSON", e); }
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

    public boolean isCellEditable(int r,int c) { return !board[r][c].isGiven(); }

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
    public long    getSolveTimeSeconds() { return solveTimeSeconds; }
    public int     getMoveCount()       { return moveCount; }

    public int     getHintCount()       { return hintCount; }
    public synchronized void incrementHintCount() { this.hintCount++; }
    /** For rebuilding non-authoritative client boards from a BoardState snapshot. */
    public void setHintCount(int n)     { this.hintCount = Math.max(0, n); }
    /** For rebuilding non-authoritative client boards from a BoardState snapshot. */
    public void setMoveCount(int n)     { this.moveCount = Math.max(0, n); }

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

    public record Move(int row, int col, int oldVal, int newVal, SudokuCell.MoveSource source) {}

    public record Hint(int row, int col, Object value, Strategy strategy) {
        public enum Strategy { NAKED_SINGLE, CANDIDATE, HIDDEN_SINGLE, POINTING_PAIR }
    }
}
