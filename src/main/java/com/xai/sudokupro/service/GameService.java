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
import org.springframework.beans.factory.annotation.Autowired;
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

    private final Map<String, SudokuBoard> activeGames      = new ConcurrentHashMap<>();
    private final Map<String, Integer>     playerStreaks     = new ConcurrentHashMap<>();
    private final Map<String, Integer>     playerCosmicPts  = new ConcurrentHashMap<>();
    private final Map<String, Long>        lockedPlayers    = new ConcurrentHashMap<>();

    @Autowired
    public GameService(AISolverService aiSolverService, GameRepository gameRepository,
                       MultiplayerBroadcaster multiplayerBroadcaster,
                       RedisTemplate<String, SudokuBoard> redisTemplate,
                       SecureRandomGenerator randomGenerator,
                       AnalyticsService analyticsService,
                       AntiCheatEngine antiCheatEngine,
                       ChaosEngine chaosEngine) {
        this.aiSolverService       = Objects.requireNonNull(aiSolverService);
        this.gameRepository        = Objects.requireNonNull(gameRepository);
        this.multiplayerBroadcaster= Objects.requireNonNull(multiplayerBroadcaster);
        this.redisTemplate         = Objects.requireNonNull(redisTemplate);
        this.randomGenerator       = Objects.requireNonNull(randomGenerator);
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
    public synchronized SudokuBoard createNewGame(int difficulty, String playerId,
                                                   boolean chaosMode, boolean mirrorMode,
                                                   boolean timeAttack, boolean infiniteMode,
                                                   boolean cosmicMode) {
        validateDifficulty(difficulty);
        String pid = (playerId == null || playerId.isBlank()) ? "anonymous" : playerId;

        String gameId = UUID.randomUUID().toString();
        long timeLimit = timeAttack ? Constants.TIME_ATTACK_SECONDS : 0;

        SudokuBoard board = new SudokuBoard(difficulty, chaosMode, mirrorMode, timeLimit, gameId);
        board.setPlayerId(pid);
        if (infiniteMode) board.setLives(Constants.INFINITE_MODE_LIVES);
        if (cosmicMode)   board.setCosmicEvents(randomGenerator.nextInt(Constants.COSMIC_MODE_EVENTS) + 1);
        board.setCosmicMode(cosmicMode);
        board.setTimeAttack(timeAttack);
        board.setInfiniteMode(infiniteMode);

        activeGames.put(gameId, board);
        saveToRedis(gameId, board);
        gameRepository.save(board);
        aiSolverService.setCurrentBoard(board);
        chaosEngine.onGameEvent("RESET", pid);
        multiplayerBroadcaster.broadcastGameStart(gameId, pid);
        logger.info("Game created id={} player={} difficulty={}", gameId, pid, difficulty);
        trimActiveGames();
        return board;
    }

    // =====================================================================
    // getGame
    // =====================================================================

    public synchronized SudokuBoard getGame(String gameId) {
        validateGameId(gameId);
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

    // =====================================================================
    // getHint
    // =====================================================================

    /** No-arg version for REST controller (uses last active board). */
    public String getHint() {
        if (activeGames.isEmpty()) return "No active game.";
        String gameId = activeGames.keySet().iterator().next();
        return getHint(gameId);
    }

    public synchronized String getHint(String gameId) {
        validateGameId(gameId);
        SudokuBoard board = getGame(gameId);
        aiSolverService.setCurrentBoard(board);
        String hint = aiSolverService.getNextLogicalMove(board);
        analyticsService.recordEvent(new GameEvent(GameEvent.EventType.HINT, board.getPlayerId(),
            Map.of("hint", hint, "gameId", gameId)));
        return hint;
    }

    // =====================================================================
    // applyMove
    // =====================================================================

    public synchronized void applyMove(String gameId, EnhancedMove move, String playerId) {
        validateGameId(gameId); validateMove(move); validatePlayerId(playerId);
        if (isPlayerLocked(playerId)) {
            logger.warn("Player {} is locked, rejecting move", playerId);
            return;
        }
        SudokuBoard board = getGame(gameId);

        // Optionally detect cheat
        if (antiCheatEngine.detectCheating(board.getSolveTime().toMillis(), board.getDifficulty())) {
            antiCheatEngine.flagPlayer(playerId);
            chaosEngine.onGameEvent("RAGE", playerId);
            return;
        }

        if (board.isChaosMode() && randomGenerator.chance(0.1)) triggerChaosSwap(board);

        board.applyMove(move, multiplayerBroadcaster);
        antiCheatEngine.recordMove(playerId, false);

        analyticsService.recordEvent(new GameEvent(GameEvent.EventType.MOVE, playerId,
            Map.of("row", String.valueOf(move.row()), "col", String.valueOf(move.col()),
                   "value", String.valueOf(move.newVal()))));

        chaosEngine.onGameEvent("MOVE", playerId);
        saveToRedis(gameId, board);
        gameRepository.save(board);

        if (board.isSolved()) {
            playerStreaks.merge(playerId, 1, Integer::sum);
            chaosEngine.onGameEvent("STREAK", playerId);
            endGame(gameId, playerId);
        } else if (board.isInfiniteMode() && board.getLives() <= 0) {
            endGame(gameId, playerId);
        }
    }

    // =====================================================================
    // endGame / solveSudoku / rewind / reset / lock
    // =====================================================================

    public synchronized void endGame(String gameId, String playerId) {
        validateGameId(gameId);
        SudokuBoard board = activeGames.remove(gameId);
        if (board != null) {
            redisTemplate.delete(redisKey(gameId));
            gameRepository.save(board);
            GameEvent.EventType type = board.isSolved()
                ? GameEvent.EventType.SOLVE : GameEvent.EventType.LEAVE;
            analyticsService.recordEvent(new GameEvent(type, playerId,
                Map.of("solveTimeSeconds", String.valueOf(board.getSolveTime().toSeconds()))));
            multiplayerBroadcaster.broadcastGameEnd(gameId, playerId);
            logger.info("Game {} ended for player {}", gameId, playerId);
        }
    }

    public synchronized void solveSudoku(String gameId) {
        validateGameId(gameId);
        SudokuBoard board = getGame(gameId);
        aiSolverService.setCurrentBoard(board);
        aiSolverService.solveSudoku();
        saveToRedis(gameId, board);
    }

    public synchronized void rewindGame(String playerId, int turns) {
        validatePlayerId(playerId);
        activeGames.values().stream()
            .filter(b -> playerId.equals(b.getPlayerId()))
            .forEach(b -> { for (int i = 0; i < turns && !b.getMoveHistory().isEmpty(); i++) b.undo(); });
    }

    public synchronized void resetBoard(String playerId) {
        validatePlayerId(playerId);
        activeGames.values().stream()
            .filter(b -> playerId.equals(b.getPlayerId()))
            .forEach(SudokuBoard::reset);
    }

    public void lockPlayerInput(String playerId, long durationMs) {
        lockedPlayers.put(playerId, System.currentTimeMillis() + durationMs);
        logger.info("Locked player {} for {}ms", playerId, durationMs);
    }

    private boolean isPlayerLocked(String playerId) {
        Long until = lockedPlayers.get(playerId);
        if (until == null) return false;
        if (System.currentTimeMillis() > until) { lockedPlayers.remove(playerId); return false; }
        return true;
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
        playerCosmicPts.merge(playerId, 2, Integer::sum);
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
    public int  getPlayerCosmicPoints(String p)     { return playerCosmicPts.getOrDefault(p, 0); }
    public int  getPlayerStreak(String p)           { return playerStreaks.getOrDefault(p, 0); }

    public void resetPlayerStats(String playerId) {
        playerStreaks.remove(playerId); playerCosmicPts.remove(playerId);
        chaosEngine.resetPlayerState(playerId);
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    @PreDestroy
    public void shutdown() {
        activeGames.forEach((id, board) -> {
            try { gameRepository.save(board); redisTemplate.delete(redisKey(id)); }
            catch (Exception e) { logger.error("Shutdown save failed for {}: {}", id, e.getMessage()); }
        });
        activeGames.clear();
        logger.info("GameService shutdown complete");
    }

    // =====================================================================
    // Private helpers
    // =====================================================================

    private void validateDifficulty(int d) {
        if (d < Constants.MIN_DIFFICULTY || d > Constants.MAX_DIFFICULTY)
            throw new IllegalArgumentException("Difficulty must be " + Constants.MIN_DIFFICULTY + "-" + Constants.MAX_DIFFICULTY);
    }
    private void validateGameId(String id)  { if (id==null||id.isBlank()) throw new IllegalArgumentException("gameId blank"); }
    private void validatePlayerId(String id){ if (id==null||id.isBlank()) throw new IllegalArgumentException("playerId blank"); }
    private void validateMove(EnhancedMove m) {
        if (m==null||m.row()<0||m.row()>8||m.col()<0||m.col()>8||m.newVal()<0||m.newVal()>9)
            throw new IllegalArgumentException("Invalid move: " + m);
    }
    private void saveToRedis(String id, SudokuBoard b) {
        redisTemplate.opsForValue().set(redisKey(id), b, REDIS_TTL_MINUTES, TimeUnit.MINUTES);
    }
    private String redisKey(String id) { return "sudoku:game:" + id; }
    private void triggerChaosSwap(SudokuBoard b) {
        b.swapRows(randomGenerator.nextInt(9), randomGenerator.nextInt(9));
    }
    private void trimActiveGames() {
        if (activeGames.size() > MAX_ACTIVE_GAMES) {
            Iterator<Map.Entry<String,SudokuBoard>> it = activeGames.entrySet().iterator();
            while (activeGames.size() > MAX_ACTIVE_GAMES && it.hasNext()) {
                Map.Entry<String,SudokuBoard> e = it.next();
                gameRepository.save(e.getValue()); it.remove();
            }
        }
    }
}
