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
    private static final int  MAX_ACTIVE_GAMES  = 10_000;
    private static final int  MAX_STREAK_BONUS  = 5;

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
    private final Map<String, SudokuBoard> activeGames = new ConcurrentHashMap<>();
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

        // Register and trim under a narrow creation lock so MAX_ACTIVE_GAMES is enforced atomically.
        synchronized (creationLock) {
            activeGames.put(gameId, board);
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
            if (board == null) {
                board = redisTemplate.opsForValue().get(redisKey(gameId));
                if (board == null) {
                    board = gameRepository.findByGameId(gameId);
                    if (board == null) {
                        throw new IllegalArgumentException("Game not found: " + gameId);
                    }
                }
                activeGames.put(gameId, board);
                saveToRedis(gameId, board);
            }
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

    /** Returns the gameId of the player's current active game, or null if none. */
    public String findActiveGameForPlayer(String playerId) {
        return activeGames.entrySet().stream()
            .filter(e -> playerId.equals(e.getValue().getPlayerId()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    }

    public String getHint(String gameId) {
        validateGameId(gameId);
        try (var lock = gameLocks.lock(gameId)) {
            SudokuBoard board = getGame(gameId);
            String hint = aiSolverService.getNextLogicalMove(board);
            // Hint economy: charge AFTER computing but BEFORE revealing — a
            // throw here (InsufficientGemsException) means the player pays
            // nothing and learns nothing. Empty hints are free.
            if (economyService != null && hint != null && !hint.isBlank()) {
                economyService.chargeForHint(board.getPlayerId());
            }
            analyticsService.recordEvent(new GameEvent(GameEvent.EventType.HINT, board.getPlayerId(),
                Map.of("hint", hint, "gameId", gameId)));
            return hint;
        }
    }

    // =====================================================================
    // applyMove
    // =====================================================================

    public void applyMove(String gameId, EnhancedMove move, String playerId) {
        validateGameId(gameId); validateMove(move); validatePlayerId(playerId);
        if (isPlayerLocked(playerId)) {
            logger.warn("Player {} is locked, rejecting move", playerId);
            return;
        }
        try (var lock = gameLocks.lock(gameId)) {
            SudokuBoard board = getGame(gameId);

            if (board.isChaosMode() && randomGenerator.chance(0.1)) triggerChaosSwap(board);

            board.applyMove(move, multiplayerBroadcaster);
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
                playerState.incrementStreak(playerId);
                chaosEngine.onGameEvent("STREAK", playerId);
                endGame(gameId, playerId);
            } else if (board.isInfiniteMode() && board.getLives() <= 0) {
                endGame(gameId, playerId);
            }
        }
    }

    // =====================================================================
    // endGame / solveSudoku / undo / redo / rewind / reset / lock
    // =====================================================================

    public void endGame(String gameId, String playerId) {
        validateGameId(gameId);
        try (var lock = gameLocks.lock(gameId)) {
            SudokuBoard board = activeGames.remove(gameId);
            if (board != null) {
                gameLocks.releaseGame(gameId);   // drop the per-game local lock entry
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
    private void persistBoard(SudokuBoard board) {
        board.syncCellsJson();
        gameRepository.save(board);
    }

    /** Fans finished games out to feature listeners (daily puzzle, duels, ...). */
    private void notifyGameEndListeners(SudokuBoard board, String playerId) {
        if (gameEndListeners == null) return; // plain unit-test construction
        gameEndListeners.stream().forEach(listener -> {
            try {
                listener.onGameEnded(board, playerId);
            } catch (Exception e) {
                logger.warn("Game-end listener {} failed for game {}: {}",
                    listener.getClass().getSimpleName(), board.getGameId(), e.getMessage());
            }
        });
    }

    public void solveSudoku(String gameId) {
        validateGameId(gameId);
        try (var lock = gameLocks.lock(gameId)) {
            SudokuBoard board = getGame(gameId);
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

    private String redisKey(String gameId) {
        return "game:" + gameId;
    }

    private void trimActiveGames() {
        // Called under creationLock. Remove oldest entries when over limit.
        while (activeGames.size() > MAX_ACTIVE_GAMES) {
            String oldest = activeGames.keySet().iterator().next();
            activeGames.remove(oldest);
            gameLocks.releaseGame(oldest);
            logger.warn("Active games limit reached; evicted game {}", oldest);
        }
    }

    private void triggerChaosSwap(SudokuBoard board) {
        // Pick two random non-given cells and swap their values.
        SudokuCell[][] cells = board.getBoard();
        java.util.List<int[]> editable = new java.util.ArrayList<>();
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (!cells[r][c].isGiven()) editable.add(new int[]{r, c});
        if (editable.size() < 2) return;
        int i1 = randomGenerator.nextInt(editable.size());
        int i2 = randomGenerator.nextInt(editable.size());
        if (i1 == i2) return;
        int[] a = editable.get(i1), b = editable.get(i2);
        int tmp = cells[a[0]][a[1]].getValue();
        cells[a[0]][a[1]].setValue(cells[b[0]][b[1]].getValue(), SudokuCell.MoveSource.CHAOS);
        cells[b[0]][b[1]].setValue(tmp, SudokuCell.MoveSource.CHAOS);
        logger.debug("Chaos swap ({},{}) <-> ({},{}) in game {}", a[0], a[1], b[0], b[1], board.getGameId());
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
