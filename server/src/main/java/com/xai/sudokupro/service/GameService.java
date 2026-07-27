package com.xai.sudokupro.service;

import com.xai.sudokupro.engine.ChaosEngine;
import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.GameEvent;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.util.Constants;
import com.xai.sudokupro.util.SecureRandomGenerator;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class GameService {
    private static final Logger logger = LoggerFactory.getLogger(GameService.class);
    private static final long REDIS_TTL_MINUTES = 60;
    /** Shipped default for {@link #maxActiveGames}. */
    static final int DEFAULT_MAX_ACTIVE_GAMES = 10_000;
    private static final int  MAX_STREAK_BONUS  = 5;

    /**
     * How many boards this pod keeps cached before evicting the least-recently-used one.
     *
     * <p>A per-pod memory-sizing decision rather than a law of the program, so it is a
     * tunable field instead of a compile-time constant. That also makes eviction reachable
     * in a unit test: the cap test used to read a few hundred games against a hard-wired
     * 10,000 and so never crossed the threshold — it passed with eviction entirely
     * disabled.
     */
    @Value("${sudokupro.game.max-active-games:10000}")
    private int maxActiveGames = DEFAULT_MAX_ACTIVE_GAMES;

    private final AISolverService      aiSolverService;
    private final GameRepository       gameRepository;
    private final MultiplayerBroadcaster multiplayerBroadcaster;
    private final RedisTemplate<String, SudokuBoard> redisTemplate;
    private final SecureRandomGenerator randomGenerator;
    private final AnalyticsService     analyticsService;
    private final AntiCheatEngine      antiCheatEngine;
    private final ChaosEngine          chaosEngine;

    // activeGames is a per-pod CACHE of boards — the authoritative copy lives in
    // Redis/DB (getGame reads through, mutations write through). Player streaks,
    // cosmic points, and input locks live in PlayerStateStore; per-game mutual
    // exclusion (across replicas) in GameLockManager. (Phase 5 / AUDIT P1-7)
    //
    // Cross-replica coherence (pass 15): a cache hit used to be trusted forever —
    // getGame short-circuited on the map, so a board another replica had since
    // written was served, and mutated, from this pod's stale copy. GameLockManager
    // serialises the MUTATIONS but does nothing about the cached COPIES: pod A
    // writes under the lock, pod B then acquires the same lock, cache-hits its old
    // copy, and applies a move to a grid that no longer exists — a lost update the
    // lock was supposed to make impossible. Every write now bumps a per-game
    // version counter in Redis (persistBoard → bumpBoardVersion, ordered AFTER the
    // Redis board write, so a reader that observes the new version always finds the
    // new board behind it), and every getGame validates a cache hit against that
    // counter before trusting it — one Redis GET, the same order of cost the
    // cross-replica lock already pays per acquire. Because both the writer's bump
    // and the reader's validation happen INSIDE the per-game lock, the check is
    // race-free, not best-effort. With Redis down validation degrades to trusting
    // the cache, which is exactly the single-replica deployment GameLockManager
    // already declares itself for in that state.
    private final Map<String, SudokuBoard> activeGames = new ConcurrentHashMap<>();
    // The board version each cached entry was loaded at. Maintained alongside
    // activeGames; compared against Redis "game:ver:<id>" on every cache hit.
    private final Map<String, Long> cachedVersion = new ConcurrentHashMap<>();
    // Last time each cached board was created or read. ConcurrentHashMap has no ordering,
    // so trimActiveGames() used to evict an arbitrary (hash-order) entry while calling it
    // "oldest" — it could throw out the game someone is actively playing and keep a stale
    // one. This gives eviction a real LRU signal.
    private final Map<String, Long> lastAccess = new ConcurrentHashMap<>();
    // Games this pod has actually WRITTEN. Shutdown flushes only these — see shutdown().
    private final Set<String> locallyMutated = ConcurrentHashMap.newKeySet();
    private final PlayerStateStore playerState;
    private final GameLockManager  gameLocks;
    private final Object           creationLock = new Object();

    // Lazily resolved to break constructor cycles: listeners (daily puzzle,
    // duels) need GameService (adoptGame/getGame), GameService needs to tell
    // them about ended games. Optional — absent in plain unit tests.
    private ObjectProvider<GameEndListener> gameEndListeners;

    @Autowired
    public void setGameEndListeners(ObjectProvider<GameEndListener> gameEndListeners) {
        this.gameEndListeners = gameEndListeners;
    }

    // Per-game version counter store for cross-replica cache validation (see the
    // activeGames comment). Setter-injected and optional so plain unit tests can
    // construct GameService without it — absent, validation is disabled and the
    // cache behaves exactly as before, which is correct for single-replica.
    private org.springframework.data.redis.core.StringRedisTemplate versionRedis;

    @Autowired(required = false)
    public void setVersionRedis(org.springframework.data.redis.core.StringRedisTemplate versionRedis) {
        this.versionRedis = versionRedis;
    }

    // Hint-economy charge point (solve rewards flow through the listener hook
    // above; EconomyService implements GameEndListener). Setter-injected and
    // optional so plain unit tests can construct GameService without it.
    private com.xai.sudokupro.service.economy.EconomyService economyService;

    @Autowired(required = false)
    public void setEconomyService(com.xai.sudokupro.service.economy.EconomyService economyService) {
        this.economyService = economyService;
    }

    @Autowired
    public GameService(AISolverService aiSolverService, GameRepository gameRepository,
                       MultiplayerBroadcaster multiplayerBroadcaster,
                       @Qualifier("gameStateRedisTemplate") RedisTemplate<String, SudokuBoard> redisTemplate,
                       SecureRandomGenerator randomGenerator,
                       PlayerStateStore playerState,
                       GameLockManager gameLockManager,
                       AnalyticsService analyticsService,
                       AntiCheatEngine antiCheatEngine,
                       ChaosEngine chaosEngine) {
        this.aiSolverService       = Objects.requireNonNull(aiSolverService);
        this.gameRepository        = Objects.requireNonNull(gameRepository);
        this.multiplayerBroadcaster= Objects.requireNonNull(multiplayerBroadcaster);
        this.redisTemplate         = Objects.requireNonNull(redisTemplate);
        this.randomGenerator       = Objects.requireNonNull(randomGenerator);
        this.playerState           = Objects.requireNonNull(playerState);
        this.gameLocks             = Objects.requireNonNull(gameLockManager);
        this.analyticsService      = Objects.requireNonNull(analyticsService);
        this.antiCheatEngine       = Objects.requireNonNull(antiCheatEngine);
        this.chaosEngine           = Objects.requireNonNull(chaosEngine);
    }

    // =====================================================================
    // createNewGame overloads
    // =====================================================================

    /** Minimal overload used by REST controller and simple callers. */
    public SudokuBoard createNewGame(int difficulty) {
        return createNewGame(difficulty, "anonymous", false, false, false, false, false);
    }

    /** Overload without time-attack / infinite / cosmic flags. */
    public SudokuBoard createNewGame(int difficulty, String playerId,
                                     boolean chaosMode, boolean mirrorMode) {
        return createNewGame(difficulty, playerId, chaosMode, mirrorMode, false, false, false);
    }

    /** Full overload. */
    public SudokuBoard createNewGame(int difficulty, String playerId,
                                     boolean chaosMode, boolean mirrorMode,
                                     boolean timeAttack, boolean infiniteMode,
                                     boolean cosmicMode) {
        validateDifficulty(difficulty);
        String pid = (playerId == null || playerId.isBlank()) ? "anonymous" : playerId;

        // Board construction is expensive (backtracking solver); do it outside the lock.
        String gameId = UUID.randomUUID().toString();
        long timeLimit = timeAttack ? Constants.TIME_ATTACK_SECONDS : 0;
        SudokuBoard board = new SudokuBoard(difficulty, chaosMode, mirrorMode, timeLimit, gameId);
        board.setPlayerId(pid);
        if (infiniteMode) board.setLives(Constants.INFINITE_MODE_LIVES);
        if (cosmicMode)   board.setCosmicEvents(randomGenerator.nextInt(Constants.COSMIC_MODE_EVENTS) + 1);
        board.setCosmicMode(cosmicMode);
        board.setTimeAttack(timeAttack);
        board.setInfiniteMode(infiniteMode);

        // Register and trim under a narrow creation lock so the cap is enforced atomically.
        synchronized (creationLock) {
            activeGames.put(gameId, board);
            touch(gameId);
            trimActiveGames();
        }
        saveToRedis(gameId, board);
        persistBoard(board);
        chaosEngine.onGameEvent("RESET", pid);
        multiplayerBroadcaster.broadcastGameStart(gameId, pid);
        logger.info("Game created id={} player={} difficulty={}", gameId, pid, difficulty);
        return board;
    }

    /**
     * Registers an externally constructed board (e.g. a player's copy of the
     * daily puzzle) exactly as createNewGame would: active set, cap enforcement,
     * Redis write-through, database persist, and game-start broadcast.
     */
    public SudokuBoard adoptGame(SudokuBoard board) {
        Objects.requireNonNull(board, "board");
        String gameId = board.getGameId();
        validateGameId(gameId);
        synchronized (creationLock) {
            activeGames.put(gameId, board);
            touch(gameId);
            trimActiveGames();
        }
        saveToRedis(gameId, board);
        persistBoard(board);
        multiplayerBroadcaster.broadcastGameStart(gameId, board.getPlayerId());
        logger.info("Game adopted id={} player={}", gameId, board.getPlayerId());
        return board;
    }

    // =====================================================================
    // getGame
    // =====================================================================

    public SudokuBoard getGame(String gameId) {
        validateGameId(gameId);
        try (var lock = gameLocks.lock(gameId)) {
            SudokuBoard board = activeGames.get(gameId);
            if (board != null && cachedCopyIsStale(gameId)) {
                // Another replica wrote this game since we cached it. Drop the copy and
                // fall through to the read-through path; the Redis/DB copy behind the
                // new version is complete because writers bump AFTER writing. The
                // locallyMutated flag goes too — flushing this stale copy on shutdown
                // would roll the game back over the other replica's writes, which is
                // the precise failure shutdown()'s guard exists to prevent.
                logger.debug("Cached copy of {} is stale (another replica wrote it) — reloading", gameId);
                activeGames.remove(gameId);
                locallyMutated.remove(gameId);
                board = null;
            }
            if (board == null) {
                board = readFromRedis(gameId);
                if (board == null) {
                    board = gameRepository.findByGameId(gameId);
                    if (board == null) {
                        throw new IllegalArgumentException("Game not found: " + gameId);
                    }
                }
                activeGames.put(gameId, board);
                recordCachedVersion(gameId);
                saveToRedis(gameId, board);
                // The READ path populates the cache too, and used to do so without any
                // cap: only createNewGame/adoptGame trimmed. Abandoned games are never
                // evicted either, so a pod that merely read boards grew activeGames (and
                // GameLockManager's per-game monitors alongside it) until it ran out of
                // memory.
                trimActiveGames();
            }
            touch(gameId);
            return board;
        }
    }

    // =====================================================================
    // getHint
    // =====================================================================

    /** Hint for a specific player — finds their active game, or returns a clear error message. */
    public String getHintForPlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) return "No player specified.";
        String gameId = findActiveGameForPlayer(playerId);
        if (gameId == null) return "No active game found for player.";
        return getHint(gameId);
    }

    /**
     * Safe/idempotent variant of {@link #getHintForPlayer(String)} for the deprecated
     * {@code GET /api/game/hint} with no {@code gameId}.
     */
    public String getHintForPlayerIdempotent(String playerId) {
        if (playerId == null || playerId.isBlank()) return "No player specified.";
        String gameId = findActiveGameForPlayer(playerId);
        if (gameId == null) return "No active game found for player.";
        return getHintIdempotent(gameId, playerId);
    }

    /** Returns the gameId of the player's current active game, or null if none. */
    public String findActiveGameForPlayer(String playerId) {
        return activeGames.entrySet().stream()
            .filter(e -> playerId.equals(e.getValue().getPlayerId()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    }

    /** Hint for a board the caller owns. Prefer {@link #getHint(String, String)}. */
    public String getHint(String gameId) {
        return getHint(gameId, null);
    }

    /**
     * Hint for {@code gameId}, charged to {@code requesterId}.
     *
     * <p>The requester must own the board. Previously this charged
     * {@code board.getPlayerId()} with no caller check, so passing someone else's active
     * gameId drained THEIR wallet 5 gems per call and inflated their hintCount (costing
     * them the clean-solve bonus), while the attacker got the hint for free. Verified
     * live: attacker's balance unchanged, victim's went 15 -> 10.
     *
     * @throws SecurityException if the requester does not own the board (mapped to 403)
     */
    public String getHint(String gameId, String requesterId) {
        validateGameId(gameId);
        try (var lock = gameLocks.lock(gameId)) {
            return purchaseHint(gameId, requesterId);
        }
    }

    /**
     * The hint most recently sold for a game, remembered against the exact grid it was sold
     * for. Bounded by construction: one entry per cached board, discarded by
     * {@link #forget(String)}, {@link #endGame(String, String)} and {@link #trimActiveGames()}
     * alongside the board itself.
     */
    private final Map<String, IssuedHint> lastIssuedHint = new ConcurrentHashMap<>();

    private record IssuedHint(String gridFingerprint, String hint) {}

    /**
     * Safe, idempotent hint read — what {@code GET /api/game/hint} now calls.
     *
     * <p>{@code GET} was charging gems, incrementing {@code hintCount} and writing the board.
     * HTTP defines GET as safe (RFC 9110 §9.2.1) and the ecosystem takes that literally: a
     * reload, a bfcache restore, a {@code <link rel=prefetch>}, an antivirus/proxy URL
     * warm-up, or a crawler that finds the URL in a log all replay it with no user
     * involvement. Each replay bought another hint, and every purchase also raised
     * {@code hintCount}, which costs the player the clean-solve bonus and the
     * {@code CleanSolver} achievement — a penalty applied for something they never did.
     *
     * <p>So the purchase moved to {@code POST} and this method makes the surviving GET
     * genuinely idempotent: while the grid is unchanged it replays the hint already issued
     * for that grid, free, with no board mutation and no write. Asking again after a move
     * (a different grid) is a new purchase, so the feature still works; a player who wants a
     * second hint on the same grid uses POST.
     *
     * <p>The fingerprint covers cell values only, deliberately. {@code hintCount} changes on
     * every purchase, so including it would invalidate the memo the instant it was written
     * and restore the double-charge.
     */
    public String getHintIdempotent(String gameId, String requesterId) {
        validateGameId(gameId);
        try (var lock = gameLocks.lock(gameId)) {
            SudokuBoard board = getGame(gameId);
            requireHintOwner(board, gameId, requesterId);
            IssuedHint prior = lastIssuedHint.get(gameId);
            if (prior != null && prior.gridFingerprint().equals(gridFingerprint(board))) {
                logger.debug("Replaying already-issued hint for {} (grid unchanged) — no charge", gameId);
                return prior.hint();
            }
            return purchaseHint(gameId, requesterId);
        }
    }

    /**
     * Buys a hint. Caller must already hold the game lock.
     *
     * @throws SecurityException if the requester does not own the board (mapped to 403)
     */
    private String purchaseHint(String gameId, String requesterId) {
        {
            SudokuBoard board = getGame(gameId);
            requireHintOwner(board, gameId, requesterId);
            String hint = aiSolverService.getNextLogicalMove(board);
            if (AISolverService.NO_MOVES.equals(hint)) {
                // No logical move exists for this grid. Nothing was delivered and the
                // board was not mutated (incrementHintCount only runs when a hint is
                // found), so nothing is charged, counted in hint analytics, or
                // persisted. "No moves" is a non-blank string, so the isBlank() guard
                // below waved it through: the player paid 5 gems to be told nothing,
                // while hintCount stayed at 0 — a charge with no purchase behind it.
                // Still memoised, so a replayed GET on the same grid stays free.
                lastIssuedHint.put(gameId, new IssuedHint(gridFingerprint(board), hint));
                return hint;
            }
            // Hint economy: charge AFTER computing but BEFORE revealing — a
            // throw here (InsufficientGemsException) means the player pays
            // nothing and learns nothing. Empty hints are free.
            if (economyService != null && hint != null && !hint.isBlank()) {
                economyService.chargeForHint(board.getPlayerId());
            }
            analyticsService.recordEvent(new GameEvent(GameEvent.EventType.HINT, board.getPlayerId(),
                Map.of("hint", hint, "gameId", gameId)));
            // Persist the hint. This was the ONLY mutating method in the class that
            // omitted the saveToRedis/persistBoard pair that applyMove, solveSudoku, undo
            // and redo all perform. AISolverService.getNextLogicalMove calls
            // board.incrementHintCount(), so the charge landed in the database while the
            // count it paid for lived only in this pod's activeGames map. Evict the board
            // (trimActiveGames) or restart the pod before the next move and the hint was
            // forgotten: the board reloaded with hintCount == 0, so EconomyService granted
            // the +5 clean-solve bonus and AchievementService unlocked CleanSolver on a
            // solve that had in fact been hinted — the player was charged for the hint and
            // then rewarded as though they had never taken one.
            saveToRedis(gameId, board);
            persistBoard(board);
            // Remember what this grid state has already been sold, so a replayed GET
            // (prefetch, reload, crawler) returns the same answer instead of buying another.
            lastIssuedHint.put(gameId, new IssuedHint(gridFingerprint(board), hint));
            return hint;
        }
    }

    /** Shared ownership check for both hint paths. */
    private void requireHintOwner(SudokuBoard board, String gameId, String requesterId) {
        if (requesterId != null && board.getPlayerId() != null
                && !requesterId.equals(board.getPlayerId())) {
            throw new SecurityException(
                "Game " + gameId + " belongs to " + board.getPlayerId() + " — hints are for its owner");
        }
    }

    /**
     * Compact identity of the playable grid: the 81 cell values in row-major order.
     *
     * <p>Values only — not {@code hintCount}, not the move counter, not pencil marks. Two
     * requests with the same values get the same next logical move, which is precisely when
     * replaying is honest.
     */
    private String gridFingerprint(SudokuBoard board) {
        SudokuCell[][] grid = board.getBoard();
        StringBuilder sb = new StringBuilder(grid.length * grid.length);
        for (SudokuCell[] row : grid) {
            for (SudokuCell cell : row) sb.append((char) ('0' + cell.getValue()));
        }
        return sb.toString();
    }

    // =====================================================================
    // applyMove
    // =====================================================================

    /**
     * Applies a player's move.
     *
     * @return true if the board actually changed. This used to be void, so the WebSocket
     *         handler had no success signal and broadcast the move regardless — including
     *         when this method dropped it silently because the player was FREEZE-locked.
     *         The victim's client and every peer then held a value the authoritative board
     *         never recorded, with nothing to trigger a resync.
     */
    public boolean applyMove(String gameId, EnhancedMove move, String playerId) {
        validateGameId(gameId); validateMove(move); validatePlayerId(playerId);
        if (isPlayerLocked(playerId)) {
            logger.warn("Player {} is locked, rejecting move", playerId);
            return false;
        }
        boolean applied;
        try (var lock = gameLocks.lock(gameId)) {
            SudokuBoard board = getGame(gameId);

            if (board.isChaosMode() && randomGenerator.chance(0.1)) triggerChaosSwap(board);

            applied = board.applyMove(move, multiplayerBroadcaster);
            antiCheatEngine.recordMove(playerId, false);

            analyticsService.recordEvent(new GameEvent(GameEvent.EventType.MOVE, playerId,
                Map.of("row", String.valueOf(move.row()), "col", String.valueOf(move.col()),
                       "value", String.valueOf(move.newVal()))));

            chaosEngine.onGameEvent("MOVE", playerId);
            saveToRedis(gameId, board);
            persistBoard(board);

            if (board.isSolved()) {
                // Check for suspiciously fast solve only after the board is actually solved.
                // detectCheating(solveTime=0, ...) was called before applyMove, which always
                // returned true (0 < difficulty * 10_000) and blocked every move. The check
                // only makes sense once getSolveTime() reflects the real elapsed duration.
                if (antiCheatEngine.detectCheating(board.getSolveTime().toMillis(), board.getDifficulty())) {
                    antiCheatEngine.flagPlayer(playerId);
                    chaosEngine.onGameEvent("RAGE", playerId);
                }
                // Feed the running suspicion score the AntiCheatScheduler enforces on.
                // Nothing in the application did this: the score's only writer was
                // reachable exclusively through EventEngine.submitEventScore, which has
                // no callers, so every scheduler detector compared 0.0 against the
                // threshold of 75 forever. Scoring here does not itself punish anyone —
                // the immediate flag above is unchanged, and enforcement stays with the
                // scheduler's threshold — it just makes the signal real.
                antiCheatEngine.scoreCompletedGame(board, playerId);
                playerState.incrementStreak(playerId);
                // Make the STREAK_UPDATE analytics branch real: recordEvent has kept a
                // best-streak map for this event type since it was written, but the enum
                // constant only exists as of pass 15 and this is its only emission point.
                analyticsService.recordEvent(new GameEvent(GameEvent.EventType.STREAK_UPDATE, playerId,
                    Map.of("streak", String.valueOf(playerState.getStreak(playerId)))));
                chaosEngine.onGameEvent("STREAK", playerId);
                endGame(gameId, playerId);
            } else if (board.isInfiniteMode() && board.getLives() <= 0) {
                endGame(gameId, playerId);
            }
        }
        return applied;
    }

    // =====================================================================
    // Power-up board effects
    // =====================================================================

    /**
     * Reveals one logically-derivable cell for the board's owner (REVEAL_CELL).
     *
     * <p>Lives here rather than in {@code PowerUpService} because a paid board change
     * has to go through the same lock / broadcast / write-through path every other
     * mutation does. It did not: {@code PowerUpService} called {@code applyExternalMove}
     * — deliberately the NON-broadcasting variant — and then neither
     * {@code saveToRedis} nor {@code persistBoard}, so the revealed digit existed only
     * in one pod's {@code activeGames} entry. Nothing marked the game
     * {@code locallyMutated} either, which is what {@link #shutdown()} keys on, so even
     * the shutdown safety net skipped it. Any cache trim, pod restart, reconnect or read
     * from another replica silently reverted the cell — and after pass 15's version
     * check, the very next write from another replica drops the copy holding it. The
     * player paid 20 gems, watched nothing change (the desktop client re-renders its
     * stale local board), and lost the reveal.
     *
     * @return the move that was revealed
     * @throws SecurityException     if the caller does not own the board
     * @throws IllegalStateException if there is no cell a hint can derive
     */
    public EnhancedMove revealCell(String gameId, String playerId) {
        validateGameId(gameId);
        validatePlayerId(playerId);
        try (var lock = gameLocks.lock(gameId)) {
            SudokuBoard board = getGame(gameId);
            requireOwner(board, playerId);
            EnhancedMove move = aiSolverService.getNextLogicalMoveAsEnhancedMove(board);
            if (move == null) throw new IllegalStateException("No empty cell to reveal");
            // Broadcasting variant: a reveal is a real board change and peers/spectators
            // must see it, exactly as they see an ordinary move.
            if (!board.applyMove(move, multiplayerBroadcaster)) {
                throw new IllegalStateException("The revealed cell could not be applied");
            }
            // A reveal is assistance stronger than a hint: it forfeits the clean-solve
            // bonus the same way hints do.
            board.incrementHintCount();
            saveToRedis(gameId, board);
            persistBoard(board);
            logger.info("Revealed {},{} = {} for {} on {}",
                move.row(), move.col(), move.newVal(), playerId, gameId);
            return move;
        }
    }

    /**
     * Grants one extra life to the board's owner (EXTRA_LIFE).
     *
     * <p>Same defect as {@link #revealCell}: the increment happened on the cached board
     * and was never written anywhere, so in infinite mode the life a player had just
     * bought disappeared on the next reload — and {@code applyMove} ends the game at
     * {@code lives <= 0}.
     *
     * @return the new life count
     */
    public int grantExtraLife(String gameId, String playerId) {
        validateGameId(gameId);
        validatePlayerId(playerId);
        try (var lock = gameLocks.lock(gameId)) {
            SudokuBoard board = getGame(gameId);
            requireOwner(board, playerId);
            int lives = board.getLives() + 1;
            board.setLives(lives);
            saveToRedis(gameId, board);
            persistBoard(board);
            logger.info("Granted an extra life to {} on {} ({} held)", playerId, gameId, lives);
            return lives;
        }
    }

    // =====================================================================
    // endGame / solveSudoku / undo / redo / rewind / reset / lock
    // =====================================================================

    public void endGame(String gameId, String playerId) {
        validateGameId(gameId);
        boolean wasActive = false;
        try (var lock = gameLocks.lock(gameId)) {
            SudokuBoard board = activeGames.remove(gameId);
            lastAccess.remove(gameId);
            locallyMutated.remove(gameId);
            lastIssuedHint.remove(gameId);
            cachedVersion.remove(gameId);
            if (board != null) {
                wasActive = true;
                redisTemplate.delete(redisKey(gameId));
                persistBoard(board);
                GameEvent.EventType type = board.isSolved()
                    ? GameEvent.EventType.SOLVE : GameEvent.EventType.LEAVE;
                analyticsService.recordEvent(new GameEvent(type, playerId,
                    Map.of("solveTimeSeconds", String.valueOf(board.getSolveTime().toSeconds()))));
                multiplayerBroadcaster.broadcastGameEnd(gameId, playerId);
                notifyGameEndListeners(board, playerId);
                logger.info("Game {} ended for player {}", gameId, playerId);
            }
        }
        // Reclaim the per-game monitor only AFTER releasing it. Doing this inside the
        // try-block (as before) removed the map entry while this thread still held the
        // lock, so a concurrent caller minted a fresh ReentrantLock for the same game and
        // two threads could sit in the critical section at once. releaseGame is now a
        // no-op while the lock is in use, so a game that is still busy simply keeps its
        // monitor until the next idle release.
        if (wasActive) {
            gameLocks.releaseGame(gameId);
        }
    }

    // =====================================================================
    // Save / load (explicit persistence)
    // =====================================================================

    /**
     * Explicitly persists the current state of a game (grid included, via the
     * entity's cells_json snapshot) so the player can resume it later — even
     * after a server restart or Redis cache expiry.
     *
     * @throws IllegalArgumentException if the game does not exist
     * @throws SecurityException        if the caller does not own the game
     */
    public SudokuBoard saveGame(String gameId, String playerId) {
        validateGameId(gameId);
        validatePlayerId(playerId);
        try (var lock = gameLocks.lock(gameId)) {
            SudokuBoard board = getGame(gameId);
            requireOwner(board, playerId);
            saveToRedis(gameId, board);
            persistBoard(board);
            logger.info("Game {} explicitly saved by player {}", gameId, playerId);
            return board;
        }
    }

    /** Unfinished, resumable games for a player, newest first (limit capped at 50). */
    public List<SudokuBoard> listSavedGames(String playerId, int limit) {
        validatePlayerId(playerId);
        int capped = Math.max(1, Math.min(limit, 50));
        return gameRepository.findResumableByPlayerId(playerId,
            org.springframework.data.domain.PageRequest.of(0, capped));
    }

    /**
     * Loads a saved game back into the active set (read-through: memory, then
     * Redis, then the database) and hands it back for play.
     *
     * @throws IllegalArgumentException if the game does not exist
     * @throws SecurityException        if the caller does not own the game
     * @throws IllegalStateException    if the game is already solved
     */
    public SudokuBoard resumeGame(String gameId, String playerId) {
        validateGameId(gameId);
        validatePlayerId(playerId);
        try (var lock = gameLocks.lock(gameId)) {
            SudokuBoard board = getGame(gameId); // re-registers in activeGames + Redis
            requireOwner(board, playerId);
            if (board.isSolved()) {
                throw new IllegalStateException("Game already solved: " + gameId);
            }
            if (!board.hasAnyCellValues()) {
                // Rows persisted before the V3 cells_json migration have no grid
                // snapshot: @PostLoad leaves the blank constructor shell. Refuse
                // rather than hand the player an empty, unwinnable board.
                throw new IllegalStateException("Game " + gameId + " has no saved grid to resume");
            }
            logger.info("Game {} resumed by player {}", gameId, playerId);
            return board;
        }
    }

    // =====================================================================
    // Puzzle sharing
    // =====================================================================

    /**
     * Share code for a game: the gzipped cell snapshot, URL-safe Base64. Carries
     * only what the player can already see (values, givens, pencil marks) —
     * never the solution, which exists only in the solver.
     */
    public String exportShareCode(String gameId) {
        return exportShareCode(gameId, null);
    }

    /**
     * Share code for a game the caller owns.
     *
     * <p>This took no requester at all, unlike its siblings {@link #getHint(String, String)}
     * and {@link #solveSudoku(String, String)}, which were both given ownership checks. So
     * it was a second, independent read path onto any player's live grid: competitive game
     * ids are deterministic and usernames are public from the leaderboards, so
     * {@code GET /api/game/duel-<id>:<victim>/share} returned the victim's current cells —
     * including their pencil marks, which are their deduction notes and a direct strategic
     * tell. Verified live against a daily board before the fix.
     */
    public String exportShareCode(String gameId, String requesterId) {
        SudokuBoard board = getGame(gameId);
        if (requesterId != null) requireOwner(board, requesterId);
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            try (var gzip = new java.util.zip.GZIPOutputStream(bytes)) {
                gzip.write(board.snapshotCells().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Share encoding failed", e);
        }
    }

    /** Hard ceiling on the decompressed share payload; a full 81-cell snapshot is ~8KB. */
    private static final int MAX_SHARE_BYTES = 64 * 1024;

    /**
     * A 9x9 Sudoku with a unique solution needs at least 17 clues, so a legitimate shared
     * puzzle has at most 64 givens. Anything above that is not a puzzle.
     */
    private static final int MAX_IMPORTED_GIVENS = 64;

    /**
     * Imports a shared puzzle as a fresh game owned by the caller.
     *
     * <p><b>This was an unlimited currency mint.</b> The share code is attacker-authored:
     * unsigned, unauthenticated, and never checked against anything the server issued.
     * {@code restoreCells} validates only <em>shape</em> — 9x9, values in range — so an
     * attacker could submit a fully completed grid with every cell marked
     * {@code "ms":"PLAYER"}. {@code SudokuBoard.isSolved()} is computed from the grid
     * rather than stored, so the imported board was solved the instant it existed, and
     * {@code POST /{gameId}/end} then paid out in full: the caller genuinely owns the
     * board so the ownership check passes, {@code hasAutosolvedCells()} sees no AUTOSOLVE
     * source because the attacker wrote PLAYER, and the {@code rewardsGranted} replay
     * guard never fires because each import mints a brand-new {@code shared-<uuid>} id.
     * Every reward guard added in passes 1-8 was bypassed at once, from a direction none
     * of them faced. Measured live before the fix: <b>15 to 140 gems in five request
     * pairs</b>, with no race, no timing and no second player involved.
     *
     * <p>Two changes close it. An import now yields the <em>puzzle</em>, not the sender's
     * progress: every non-given cell is cleared, so a grid of 81 non-given values imports
     * as an empty board and earns nothing until the importer actually solves it. And a
     * board claiming more than {@link #MAX_IMPORTED_GIVENS} clues is rejected outright,
     * which blocks the obvious follow-up of marking all 81 cells {@code "g":true}. As a
     * side effect this also stops an import from carrying the sender's pencil marks and
     * partial work, which is what the endpoint's own summary always said it did.
     */
    public SudokuBoard importShareCode(String code, String playerId) {
        validatePlayerId(playerId);
        String cellsJson;
        try {
            byte[] compressed = java.util.Base64.getUrlDecoder().decode(code.trim());
            try (var gzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
                // Bounded read: the 16KB cap on the encoded field says nothing about the
                // decompressed size, and gzip reaches ~1000:1, so readAllBytes() let 12KB
                // of request body expand into ~12MB of String plus a multiple of that to
                // parse. Fifty concurrent requests OOM'd a single-replica pod.
                byte[] raw = gzip.readNBytes(MAX_SHARE_BYTES + 1);
                if (raw.length > MAX_SHARE_BYTES) {
                    throw new IllegalArgumentException("Share code expands beyond the allowed size");
                }
                cellsJson = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed share code", e);
        }
        SudokuCell[][] blank = new SudokuCell[9][9];
        for (int r = 0; r < 9; r++) for (int c = 0; c < 9; c++) blank[r][c] = new SudokuCell();
        SudokuBoard board = new SudokuBoard(blank, false, false, 0,
            "shared-" + UUID.randomUUID().toString().substring(0, 8));
        board.restoreCells(cellsJson); // validates shape; throws on garbage

        int givens = 0;
        SudokuCell[][] grid = board.getBoard();
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                if (grid[r][c].isGiven() && grid[r][c].getValue() != 0) givens++;
            }
        }
        if (givens > MAX_IMPORTED_GIVENS) {
            throw new IllegalArgumentException(
                "Share code is not a playable puzzle: " + givens + " clues");
        }
        // Import the puzzle, not the sender's progress. This is what makes an
        // attacker-authored "already solved" grid worthless.
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                SudokuCell cell = grid[r][c];
                if (cell.isGiven()) continue;
                cell.setValue(0, SudokuCell.MoveSource.INITIAL);
                cell.clearPencilMarks();
                cell.clearConflicts();
            }
        }
        if (board.isSolved()) {
            throw new IllegalArgumentException("Share code is already solved");
        }
        board.setPlayerId(playerId);
        board.setDifficulty(2);
        return adoptGame(board);
    }

    /**
     * True for the competitive game-id namespaces, whose boards are private to one player.
     *
     * <p>Mirrors {@code WebSocketController.isSharedPuzzle}. These ids are deterministic
     * ({@code daily-<date>:<player>}, {@code week-<year-Www>-p<n>:<player>},
     * {@code duel-<id>:<player>}) and usernames are public from the leaderboards and the
     * duel list, so anyone can construct another player's competitive game id exactly.
     */
    public static boolean isCompetitiveGameId(String gameId) {
        return gameId != null
            && (gameId.startsWith("daily-") || gameId.startsWith("week-") || gameId.startsWith("duel-"));
    }

    /**
     * Reads a board on behalf of {@code requesterId}, refusing a competitive board the
     * requester does not own.
     *
     * <p>The WebSocket layer was hardened against exactly this — {@code isSharedPuzzle}
     * closes the spectate channel for the three competitive prefixes, and its comment
     * records a verified live exploit where an attacker watched a victim's 53/81 daily
     * board. The REST read was never given the equivalent check, so a single
     * {@code GET /api/game/daily-<date>:<victim>} bypassed the whole mitigation and
     * returned all 81 cells. Duel boards make it worse than surveillance: both players
     * race copies of the <em>same</em> puzzle, so every value the opponent has entered is
     * a correct answer to copy. Verified live before the fix.
     *
     * @throws SecurityException if the requester does not own a competitive board
     */
    public SudokuBoard getGameForReader(String gameId, String requesterId) {
        SudokuBoard board = getGame(gameId);
        if (requesterId != null
                && isCompetitiveGameId(board.getGameId())
                && !requesterId.equals(board.getPlayerId())) {
            logger.warn("Blocked read of competitive game {} by {}", gameId, requesterId);
            throw new SecurityException("Competitive games are private to their player");
        }
        return board;
    }

    private void requireOwner(SudokuBoard board, String playerId) {
        if (!playerId.equals(board.getPlayerId())) {
            throw new SecurityException("Game " + board.getGameId() + " does not belong to player " + playerId);
        }
    }

    /**
     * Single choke point for database writes of game boards. syncCellsJson()
     * must run first: saves of already-persisted boards go through JPA merge,
     * where only persistent fields reach the row — the transient grid does not
     * (see SudokuBoard#syncCellsJson for why a @PreUpdate callback can't do this).
     */
    /**
     * The single write choke point for boards. Also records that THIS pod mutated the
     * game, which {@link #shutdown()} uses to avoid flushing boards it merely read.
     */
    private void persistBoard(SudokuBoard board) {
        board.syncCellsJson();
        gameRepository.save(board);
        if (board.getGameId() != null) {
            locallyMutated.add(board.getGameId());
            // Every mutation site calls saveToRedis BEFORE persistBoard, so by the time
            // the version moves, both authoritative copies already hold the new state —
            // a replica that sees the new version cannot reload the old board.
            bumpBoardVersion(board.getGameId());
        }
    }

    /** Fans finished games out to feature listeners (daily puzzle, duels, ...). */
    private void notifyGameEndListeners(SudokuBoard board, String playerId) {
        if (gameEndListeners == null) return; // plain unit-test construction
        // Reward-exploit guard: a board the AI solver touched is not a player
        // solve. Without this, POST /solve + /end would win duels, advance
        // streaks, and mint gems. Abandoned (unsolved) games still flow through
        // — the smart-difficulty model wants those signals.
        if (board.isSolved() && board.hasAutosolvedCells()) {
            logger.info("Game {} solved with AI assistance — reward listeners skipped", board.getGameId());
            return;
        }
        // Replay guard: /end re-hydrates a finished board into the active set (its own
        // ownership check calls getGame), so the `activeGames.remove() != null` test in
        // endGame does NOT make this idempotent. Without the flag below, replaying one
        // HTTP request minted currency without limit — measured live at +15 gems and
        // +15 XP per call, 15 -> 135 gems in seven requests.
        if (board.isSolved() && board.isRewardsGranted()) {
            logger.debug("Game {} already paid out — reward listeners skipped", board.getGameId());
            return;
        }
        gameEndListeners.stream().forEach(listener -> {
            try {
                listener.onGameEnded(board, playerId);
            } catch (Exception e) {
                logger.warn("Game-end listener {} failed for game {}: {}",
                    listener.getClass().getSimpleName(), board.getGameId(), e.getMessage());
            }
        });
        // Mark only SOLVED boards. An abandoned game must stay eligible: the player can
        // resume it and finish it properly later, and that completion should still pay.
        if (board.isSolved()) {
            board.setRewardsGranted(true);
            persistBoard(board);
        }
    }

    /** Auto-solve a board the caller owns. Prefer {@link #solveSudoku(String, String)}. */
    public void solveSudoku(String gameId) {
        solveSudoku(gameId, null);
    }

    /**
     * AI-solves {@code gameId} on behalf of {@code requesterId}, who must own the board.
     *
     * <p>This had NO caller check at all, which made it the most damaging endpoint in the
     * API. Competitive game ids are deterministic and usernames are public from the
     * leaderboards ({@code duel-<id>:<player>}, {@code daily-<date>:<player>},
     * {@code week-<year-Www>-p<n>:<player>}), so one request could fill in any player's
     * board — persisted, not just cached. The victim then cannot move (grid full), cannot
     * resume (409), and can never claim the win, because the auto-solve reward guard
     * suppresses their completion. Worse, the shared daily/weekly TEMPLATE rows are
     * ordinary boards too: solving {@code daily-<date>} poisons the puzzle for the entire
     * player base. Verified live — after one attacker request, a brand-new player joining
     * the daily received an 81/81 pre-solved, unwinnable board. The response also returns
     * the completed grid, so it doubled as a solution oracle.
     *
     * @throws SecurityException if the requester does not own the board (mapped to 403)
     */
    public void solveSudoku(String gameId, String requesterId) {
        validateGameId(gameId);
        try (var lock = gameLocks.lock(gameId)) {
            SudokuBoard board = getGame(gameId);
            if (requesterId != null && board.getPlayerId() != null
                    && !requesterId.equals(board.getPlayerId())) {
                throw new SecurityException(
                    "Game " + gameId + " belongs to " + board.getPlayerId() + " — you cannot solve it");
            }
            aiSolverService.solveSudoku(board);
            saveToRedis(gameId, board);
            // Persist like every other mutation — previously the auto-solve
            // lived only in the Redis cache and evaporated with its TTL.
            persistBoard(board);
        }
    }

    /**
     * Undoes the last move on the server-authoritative board. Remote clients
     * hold only a local copy, so undo/redo must round-trip through the server.
     */
    public SudokuBoard undo(String gameId) {
        validateGameId(gameId);
        try (var lock = gameLocks.lock(gameId)) {
            SudokuBoard board = getGame(gameId);
            board.undo();
            saveToRedis(gameId, board);
            persistBoard(board);
            return board;
        }
    }

    /** Redoes the last undone move on the server-authoritative board. */
    public SudokuBoard redo(String gameId) {
        validateGameId(gameId);
        try (var lock = gameLocks.lock(gameId)) {
            SudokuBoard board = getGame(gameId);
            board.redo();
            saveToRedis(gameId, board);
            persistBoard(board);
            return board;
        }
    }

    public void rewindGame(String playerId, int turns) {
        validatePlayerId(playerId);
        activeGames.entrySet().stream()
            .filter(e -> playerId.equals(e.getValue().getPlayerId()))
            .forEach(e -> {
                try (var lock = gameLocks.lock(e.getKey())) {
                    SudokuBoard b = e.getValue();
                    for (int i = 0; i < turns && !b.getMoveHistory().isEmpty(); i++) b.undo();
                }
            });
    }

    public void resetBoard(String playerId) {
        validatePlayerId(playerId);
        activeGames.entrySet().stream()
            .filter(e -> playerId.equals(e.getValue().getPlayerId()))
            .forEach(e -> {
                try (var lock = gameLocks.lock(e.getKey())) {
                    e.getValue().reset();
                }
            });
    }

    public void lockPlayerInput(String playerId, long durationMs) {
        playerState.lockPlayerInput(playerId, durationMs);
        logger.info("Locked player {} for {}ms", playerId, durationMs);
    }

    private boolean isPlayerLocked(String playerId) {
        return playerState.isPlayerLocked(playerId);
    }

    public void alterGameRulesTemporarily(String playerId) {
        activeGames.values().stream()
            .filter(b -> playerId.equals(b.getPlayerId()))
            .forEach(b -> {
                if (randomGenerator.chance(0.5)) b.enableTensRule();
                else b.enableDiagonalRules();
            });
    }

    public void triggerCosmicEvent(SudokuBoard board, String playerId) {
        switch (randomGenerator.nextInt(3)) {
            case 0 -> board.shuffleRandomRow(randomGenerator);
            case 1 -> board.addCosmicHint(aiSolverService);
            case 2 -> board.invertRandomBox(randomGenerator);
        }
        chaosEngine.updateLuck(playerId, 0.05);
        playerState.addCosmicPoints(playerId, 2);
    }

    /** Validate a WebSocket DuelMove. */
    public boolean validateMove(Object move) {
        if (move instanceof EnhancedMove em)
            return em.row() >= 0 && em.row() < 9 && em.col() >= 0 && em.col() < 9
                && em.newVal() >= 0 && em.newVal() <= 9;
        return false;
    }

    // =====================================================================
    // Monitoring helpers (called by SudokuHealthMonitor)
    // =====================================================================

    public int  getActiveGamesCount()           { return activeGames.size(); }
    public Map<String,SudokuBoard> getActiveGames() { return Collections.unmodifiableMap(activeGames); }
    public String getPlayerLuckProfile(String p)    { return chaosEngine.exportLuckProfileJson(p); }
    public int  getPlayerCosmicPoints(String p)     { return playerState.getCosmicPoints(p); }
    public int  getPlayerStreak(String p)           { return playerState.getStreak(p); }

    public void resetPlayerStats(String playerId) {
        playerState.resetPlayer(playerId);
        chaosEngine.resetPlayerState(playerId);
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    @PreDestroy
    public void shutdown() {
        activeGames.forEach((id, board) -> {
            // Only flush games this pod actually wrote. activeGames is a read-through
            // CACHE, so it also holds boards this pod merely looked at (a spectator view,
            // an ownership check, an /api/game/{id} read). Persisting those on shutdown
            // wrote a possibly-stale copy over whatever another replica had since saved —
            // silently rolling a game back. Every mutation already persists inline, so
            // this loop is a safety net for owned boards, not a general flush.
            if (!locallyMutated.contains(id)) {
                logger.debug("Shutdown: skipping read-only cached board {}", id);
                return;
            }
            try {
                persistBoard(board);
            } catch (Exception e) {
                logger.error("Shutdown DB save failed for {}: {}", id, e.getMessage());
            }
            try {
                // Bug D fix: LettuceConnectionFactory may already be stopped during context
                // shutdown; catch and log WARN rather than propagating.
                redisTemplate.delete(redisKey(id));
            } catch (Exception e) {
                logger.warn("Shutdown Redis delete failed for {} (Redis may be stopped): {}", id, e.getMessage());
            }
        });
        logger.info("GameService shutdown complete");
    }

    // =====================================================================
    // Private helpers
    // =====================================================================

    /**
     * Bug C fix: SerializationException from Jackson must not propagate out of saveToRedis.
     * Redis caching is optional — the board is persisted to DB regardless. Any exception
     * is caught and logged as WARN so game creation / move handling never crashes.
     */
    private void saveToRedis(String gameId, SudokuBoard board) {
        try {
            redisTemplate.opsForValue().set(redisKey(gameId), board, REDIS_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.warn("Redis cache write failed for game {} (non-fatal): {}", gameId, e.getMessage());
        }
    }

    /**
     * Reads a cached board, treating any Redis failure as a cache miss.
     *
     * <p>This read was the single Redis touch in the codebase with no try/catch — every
     * other one degrades ({@code saveToRedis}, {@code GameLockManager.acquireRedis},
     * {@code PlayerStateStore}, {@code DailyStateStore}, {@code DuelStateStore},
     * {@code RedisBroadcastRelay} all catch and fall back). So a
     * {@code RedisConnectionFailureException}, a {@code QueryTimeoutException} past the
     * 2s budget, or a {@code SerializationException} on one poisoned entry propagated out
     * of {@code getGame} instead of falling through to the database row sitting right
     * behind it. The controller catches only {@code IllegalArgumentException}, so it
     * surfaced as a 500 — and because Redis was still technically <em>up</em>, the
     * readiness probe passed and the pod kept serving 500s for reads, moves, hints, saves,
     * resumes and ends. A cache is not supposed to be a single point of failure in front
     * of a database that has the answer.
     */
    private SudokuBoard readFromRedis(String gameId) {
        try {
            return redisTemplate.opsForValue().get(redisKey(gameId));
        } catch (Exception e) {
            logger.warn("Redis cache read failed for game {} — falling back to the database: {}",
                gameId, e.getMessage());
            return null;
        }
    }

    private String redisKey(String gameId) {
        return "game:" + gameId;
    }

    // ---- cross-replica cache validation (see the activeGames comment) ----

    private String versionKey(String gameId) {
        return "game:ver:" + gameId;
    }

    /**
     * True when the Redis version counter for {@code gameId} no longer matches the
     * version this pod cached the board at. Caller must hold the game lock, which is
     * what makes the compare race-free against writers (they bump inside the lock too).
     * Any Redis failure reads as "not stale": with Redis down the deployment is
     * single-replica by GameLockManager's own declaration, and the local copy is king.
     */
    private boolean cachedCopyIsStale(String gameId) {
        if (versionRedis == null) return false;
        try {
            String raw = versionRedis.opsForValue().get(versionKey(gameId));
            long current = raw == null ? 0L : Long.parseLong(raw);
            Long cached = cachedVersion.get(gameId);
            return cached == null ? current != 0L : current != cached;
        } catch (Exception e) {
            logger.debug("Board version check failed for {} (treating cache as fresh): {}",
                gameId, e.getMessage());
            return false;
        }
    }

    /** Records the version the board now entering the cache was loaded at. */
    private void recordCachedVersion(String gameId) {
        if (versionRedis == null) return;
        try {
            String raw = versionRedis.opsForValue().get(versionKey(gameId));
            cachedVersion.put(gameId, raw == null ? 0L : Long.parseLong(raw));
        } catch (Exception e) {
            // Can't read the version: leave no entry, so the first validation after
            // Redis recovers reloads once rather than trusting an unknown baseline.
            cachedVersion.remove(gameId);
        }
    }

    /** Advances the shared version counter after a write; our own cache stays fresh. */
    private void bumpBoardVersion(String gameId) {
        if (versionRedis == null) return;
        try {
            Long v = versionRedis.opsForValue().increment(versionKey(gameId));
            // TTL matches the board key so an abandoned game's counter is reaped with it.
            versionRedis.expire(versionKey(gameId), REDIS_TTL_MINUTES, TimeUnit.MINUTES);
            if (v != null) cachedVersion.put(gameId, v);
        } catch (Exception e) {
            logger.debug("Board version bump failed for {} (non-fatal): {}", gameId, e.getMessage());
        }
    }

    /** Marks a cached board as just used, for LRU eviction. */
    private void touch(String gameId) {
        lastAccess.put(gameId, System.nanoTime());
    }

    /** Forgets a board's cache bookkeeping and its per-game monitor (if idle). */
    private void forget(String gameId) {
        activeGames.remove(gameId);
        lastAccess.remove(gameId);
        lastIssuedHint.remove(gameId);
        cachedVersion.remove(gameId);
        gameLocks.releaseGame(gameId);
    }

    private void trimActiveGames() {
        while (activeGames.size() > maxActiveGames) {
            // Genuinely least-recently-used, not "whatever the hash iterator yields first".
            String victim = lastAccess.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseGet(() -> activeGames.keySet().stream().findAny().orElse(null));
            if (victim == null) return;
            activeGames.remove(victim);
            lastAccess.remove(victim);
            locallyMutated.remove(victim);
            lastIssuedHint.remove(victim);
            cachedVersion.remove(victim);
            // Only drops the monitor if nobody holds it — evicting a busy game's lock
            // would let two threads into the same critical section.
            gameLocks.releaseGame(victim);
            logger.warn("Active games limit reached; evicted least-recently-used game {}", victim);
        }
    }

    /**
     * Chaos mode: shuffles two of the player's own entries.
     *
     * <p>This used to swap two arbitrary non-given cells with <b>no legality check</b>,
     * and swapping two different values across a Sudoku almost always creates a duplicate
     * — moving a 5 into a row that already holds one. The result is a board that is no
     * longer a legal Sudoku, and the damage compounds: {@code isValidMove} then rejects
     * the player's own CORRECT moves, because the stray duplicate blocks the value that
     * genuinely belongs there. The player is left unable to finish a game they were
     * playing correctly, with no message explaining why. Found by the live engine, which
     * played chaos games and checked the board's consistency afterwards.
     *
     * <p>Chaos is supposed to disrupt the player, not corrupt the puzzle. A swap is now
     * applied only if the board stays a legal Sudoku afterwards; the candidate pairs are
     * sampled a bounded number of times and, if none is legal, the tick simply does
     * nothing.
     */
    private void triggerChaosSwap(SudokuBoard board) {
        SudokuCell[][] cells = board.getBoard();
        java.util.List<int[]> editable = new java.util.ArrayList<>();
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (!cells[r][c].isGiven() && cells[r][c].getValue() != 0)
                    editable.add(new int[]{r, c});
        if (editable.size() < 2) return;

        for (int attempt = 0; attempt < CHAOS_SWAP_ATTEMPTS; attempt++) {
            int i1 = randomGenerator.nextInt(editable.size());
            int i2 = randomGenerator.nextInt(editable.size());
            if (i1 == i2) continue;
            int[] a = editable.get(i1), b = editable.get(i2);
            int va = cells[a[0]][a[1]].getValue();
            int vb = cells[b[0]][b[1]].getValue();
            if (va == vb) continue;                        // a visible no-op

            if (!swapKeepsBoardLegal(cells, a, b, va, vb)) continue;

            cells[a[0]][a[1]].setValue(vb, SudokuCell.MoveSource.CHAOS);
            cells[b[0]][b[1]].setValue(va, SudokuCell.MoveSource.CHAOS);
            logger.debug("Chaos swap ({},{}) <-> ({},{}) in game {}",
                a[0], a[1], b[0], b[1], board.getGameId());
            return;
        }
        logger.debug("Chaos tick found no legal swap in game {}", board.getGameId());
    }

    private static final int CHAOS_SWAP_ATTEMPTS = 24;

    /** True if putting {@code vb} at {@code a} and {@code va} at {@code b} keeps the grid legal. */
    private boolean swapKeepsBoardLegal(SudokuCell[][] cells, int[] a, int[] b, int va, int vb) {
        int[][] grid = new int[9][9];
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                grid[r][c] = cells[r][c].getValue();
        grid[a[0]][a[1]] = 0;
        grid[b[0]][b[1]] = 0;
        return legalAt(grid, a[0], a[1], vb) && legalAt(grid, b[0], b[1], va);
    }

    private static boolean legalAt(int[][] grid, int row, int col, int value) {
        for (int i = 0; i < 9; i++) {
            if (grid[row][i] == value || grid[i][col] == value) return false;
        }
        int br = row - row % 3, bc = col - col % 3;
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (grid[br + i][bc + j] == value) return false;
        return true;
    }

    private void validateDifficulty(int difficulty) {
        if (difficulty < 1 || difficulty > 5)
            throw new IllegalArgumentException("Difficulty must be 1-5, got: " + difficulty);
    }

    private void validateGameId(String gameId) {
        if (gameId == null || gameId.isBlank())
            throw new IllegalArgumentException("Game ID cannot be null or blank");
    }

    private void validatePlayerId(String playerId) {
        if (playerId == null || playerId.isBlank())
            throw new IllegalArgumentException("Player ID cannot be null or blank");
    }

    private void validateMove(EnhancedMove move) {
        if (move == null)
            throw new IllegalArgumentException("Move cannot be null");
    }
}
