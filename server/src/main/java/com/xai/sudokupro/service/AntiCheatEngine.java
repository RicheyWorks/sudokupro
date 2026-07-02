package com.xai.sudokupro.service;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.model.SudokuCell;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class AntiCheatEngine {
    private static final Logger logger = LoggerFactory.getLogger(AntiCheatEngine.class);

    private static final long MIN_SOLVE_TIME_PER_DIFFICULTY = 10000L;
    private static final int MAX_COSMIC_DRIP_SPIKE = 10;
    private static final int MAX_DUEL_WIN_STREAK = 20;
    private static final double MIN_EXPECTED_MOVE_TIME_MS = 500.0;
    private static final int MAX_MOVE_RATE_PER_MINUTE = 60;
    private static final double MAX_SCORE_VS_PEER_RATIO = 2.5;
    private static final int MIN_MOVES_FOR_COMPLEXITY = 15;
    private static final int MAX_COSMIC_MOVE_RATE = 5;
    private static final double MAX_HINT_EFFICIENCY = 0.9;
    private static final int SUSPICIOUS_IP_THRESHOLD = 3;
    private static final int MAX_COSMIC_STREAK = 10;
    private static final double MIN_PEER_CORRELATION = 0.7;
    private static final int MAX_DEVICE_SWITCHES = 2;
    private static final int MAX_CACHE_SIZE = 10000;

    private final AnalyticsService analyticsService;
    private final UserRepository userRepository;
    private final GameRepository gameRepository;

    private final Map<String, LocalDateTime> lastSolveTimes = new ConcurrentHashMap<>();
    private final Map<String, Integer> consecutiveSolves = new ConcurrentHashMap<>();
    // moveRates tracks moves per player within the current 60-second window.
    // The window start time is stored in moveRateWindowStart; the counter resets when a new window begins.
    private final Map<String, AtomicInteger> moveRates = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> moveRateWindowStart = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> lastMoveTimestamps = new ConcurrentHashMap<>();
    private final Map<String, Integer> cosmicConsistency = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> cosmicMoveRates = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> ipSolveCounts = new ConcurrentHashMap<>();
    private final Map<String, Integer> cosmicStreaks = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> movePatterns = new ConcurrentHashMap<>();
    private final Map<String, Map<String, LocalDateTime>> deviceSwitches = new ConcurrentHashMap<>();

    // Accumulated suspicion score per player.  Each detected signal adds SUSPICION_SIGNAL_WEIGHT;
    // scores decay toward zero each time the player is observed without a red flag.
    // The SUSPICION_THRESHOLD used by callers is 75.0, so ~7 concurrent signals triggers it.
    private final Map<String, Double> suspicionScoreMap = new ConcurrentHashMap<>();
    private static final double SUSPICION_SIGNAL_WEIGHT = 10.0;
    private static final double SUSPICION_DECAY_FACTOR  = 0.9;  // applied on a clean observation

    @Autowired
    public AntiCheatEngine(AnalyticsService analyticsService,
                           UserRepository userRepository,
                           GameRepository gameRepository) {
        this.analyticsService = Objects.requireNonNull(analyticsService);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.gameRepository = Objects.requireNonNull(gameRepository);
    }

    public synchronized boolean detectCheating(long solveTime, int difficulty) {
        // solveTime == 0 means the board is not yet solved (Duration.ZERO is the initial value).
        // Checking an unsolved board always returned true and blocked every move. Defense-in-depth:
        // only flag when there is an actual elapsed time to compare against the threshold.
        if (solveTime <= 0) return false;
        return solveTime < (difficulty * MIN_SOLVE_TIME_PER_DIFFICULTY);
    }

    public synchronized boolean detectCheating(SudokuBoard board, User user) {

        String playerId = String.valueOf(user.getId());
        validatePlayerId(playerId);

        String ip = user.getLastLoginIp() == null ? "unknown" : user.getLastLoginIp();
        String platform = user.getPlatform() == null ? "unknown" : user.getPlatform();

        long solveTime = board.getSolveTime().toMillis();
        int difficulty = estimateDifficulty(board);
        int moves = board.getMoveHistory().size();
        int hints = board.getHintCount();
        int cosmicDrip = board.getCosmicDripLevel();
        int duelWins = user.getDuelWins();

        LocalDateTime now = LocalDateTime.now();

        Map<String, Double> skillScores = analyticsService.getPlayerSkillScores();
        double playerSkill = skillScores.getOrDefault(playerId, 0.0);
        double avgPeerSkill = skillScores.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        int signals = 0;

        if (solveTime < (difficulty * MIN_SOLVE_TIME_PER_DIFFICULTY)) {
            signals++;
        }

        // Flavor/enforcement decoupling (AUDIT P2-3): cosmic-drip levels are driven by
        // FateEntityManager RNG events, so spikes are random outcomes, not evidence of
        // cheating. Logged for observability, but they no longer add enforcement signals.
        if (cosmicDrip > (moves / 5) + MAX_COSMIC_DRIP_SPIKE) {
            logger.debug("Cosmic drip spike for {} (drip={}, moves={}) — flavor metric, not scored", playerId, cosmicDrip, moves);
        }

        if (moves < MIN_MOVES_FOR_COMPLEXITY && hints == 0 && difficulty > 3) {
            signals++;
        }

        if (moves > 0 && solveTime / moves < MIN_EXPECTED_MOVE_TIME_MS) {
            signals++;
        }

        if (duelWins > MAX_DUEL_WIN_STREAK &&
            analyticsService.getDuelWins().getOrDefault(playerId, 0) == duelWins) {
            signals++;
        }

        LocalDateTime lastSolve = lastSolveTimes.get(playerId);
        if (lastSolve != null && now.minusSeconds(60).isBefore(lastSolve)) {
            consecutiveSolves.merge(playerId, 1, Integer::sum);
        } else {
            consecutiveSolves.put(playerId, 1);
        }

        lastSolveTimes.put(playerId, now);

        AtomicInteger moveRate = moveRates.computeIfAbsent(playerId, k -> new AtomicInteger(0));
        if (moveRate.get() > MAX_MOVE_RATE_PER_MINUTE) {
            signals++;
        }

        if (avgPeerSkill > 0 && playerSkill > avgPeerSkill * MAX_SCORE_VS_PEER_RATIO) {
            signals++;
        }

        // (AUDIT P2-3) The former cosmicDrip-vs-cosmicMoves ratio signal was likewise
        // an RNG-outcome comparison and has been removed from enforcement entirely.

        // Update running suspicion score: add weight per signal, or decay if clean.
        if (signals > 0) {
            suspicionScoreMap.merge(playerId,
                signals * SUSPICION_SIGNAL_WEIGHT,
                (existing, added) -> Math.min(100.0, existing + added));
        } else {
            suspicionScoreMap.computeIfPresent(playerId,
                (k, v) -> v * SUSPICION_DECAY_FACTOR < 1.0 ? null : v * SUSPICION_DECAY_FACTOR);
        }

        trimMaps();
        double score = suspicionScoreMap.getOrDefault(playerId, 0.0);
        return score >= SUSPICION_SIGNAL_WEIGHT;  // flagged if at least one active signal
    }

    public synchronized Map<String, Double> getCheatSuspicionScores() {
        return Collections.unmodifiableMap(new HashMap<>(suspicionScoreMap));
    }

    public synchronized void flagPlayer(String playerId) {
        if (playerId == null || playerId.isBlank() || "anonymous".equals(playerId)) {
            logger.debug("Skipping flag for non-persistent playerId: {}", playerId);
            return;
        }
        long userId;
        try {
            userId = Long.parseLong(playerId);
        } catch (NumberFormatException e) {
            logger.warn("Cannot flag player with non-numeric playerId '{}' — no DB record to penalize", playerId);
            return;
        }
        userRepository.findById(userId).ifPresent(user -> {
            user.setCosmicDrip(Math.max(0, user.getCosmicDrip() / 2));
            userRepository.save(user);
            logger.info("Flagged player {}: cosmicDrip halved to {}", playerId, user.getCosmicDrip());
        });
    }

    /** Returns a snapshot of per-player move rates (moves counted in current window). */
    public synchronized Map<String, Integer> getMoveRates() {
        Map<String, Integer> snapshot = new HashMap<>();
        moveRates.forEach((k, v) -> snapshot.put(k, v.get()));
        return snapshot;
    }

    public synchronized Map<String, Integer> getCosmicStreaks() {
        return new HashMap<>(cosmicStreaks);
    }

    public synchronized Map<String, Map<String, Integer>> getIPSolveCounts() {
        return new HashMap<>(ipSolveCounts);
    }

    public synchronized Map<String, Map<String, LocalDateTime>> getDeviceSwitches() {
        return new HashMap<>(deviceSwitches);
    }

    public synchronized void recordMove(String playerId, boolean isCosmic) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = moveRateWindowStart.computeIfAbsent(playerId, k -> now);
        // Reset counter if outside the 60-second window
        if (now.isAfter(windowStart.plusSeconds(60))) {
            moveRates.put(playerId, new AtomicInteger(1));
            moveRateWindowStart.put(playerId, now);
        } else {
            moveRates.computeIfAbsent(playerId, k -> new AtomicInteger(0)).incrementAndGet();
        }
        lastMoveTimestamps.put(playerId, now);
        if (isCosmic) {
            cosmicMoveRates.computeIfAbsent(playerId, k -> new AtomicInteger(0)).incrementAndGet();
            cosmicStreaks.merge(playerId, 1, Integer::sum);
        } else {
            cosmicStreaks.put(playerId, 0);
        }
        trimMaps();
    }

    public synchronized void clearPlayerSuspicion(String playerId) {
        suspicionScoreMap.remove(playerId);
        consecutiveSolves.remove(playerId);
        lastSolveTimes.remove(playerId);
        moveRates.remove(playerId);
        moveRateWindowStart.remove(playerId);
        lastMoveTimestamps.remove(playerId);
        cosmicStreaks.remove(playerId);
        cosmicMoveRates.remove(playerId);
        logger.info("Cleared suspicion data for player {}", playerId);
    }

    private int estimateDifficulty(SudokuBoard board) {
        int filled = 0;
        SudokuCell[][] cells = board.getBoard();
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (cells[r][c].isGiven()) filled++;
        // More givens = easier — map to difficulty 1-5 (inverse of filled count)
        if (filled >= 50) return 1;
        if (filled >= 44) return 2;
        if (filled >= 38) return 3;
        if (filled >= 32) return 4;
        return 5;
    }

    private void validatePlayerId(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("Player ID cannot be null or blank");
        }
    }

    private void trimMaps() {
        trimMap(suspicionScoreMap);
        trimMap(consecutiveSolves);
        trimMap(lastSolveTimes);
        trimMap(moveRates);
        trimMap(moveRateWindowStart);
        trimMap(lastMoveTimestamps);
        trimMap(cosmicStreaks);
        trimMap(cosmicConsistency);
        trimMap(ipSolveCounts);
        trimMap(deviceSwitches);
        trimMap(movePatterns);
    }

    private <K, V> void trimMap(Map<K, V> map) {
        while (map.size() > MAX_CACHE_SIZE) {
            map.remove(map.keySet().iterator().next());
        }
    }
}
