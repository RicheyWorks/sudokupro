package com.xai.sudokupro.service;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.util.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Cosmic sentinel of SudokuPro's grid galaxy.
 * Scans for cheaters with dripping precision—runs cosmic checks, flags sus players, and logs it all for the App Store spotlight.
 */
@Service
public class AntiCheatScheduler {
    private static final Logger logger = LoggerFactory.getLogger(AntiCheatScheduler.class);
    private static final long SCAN_INTERVAL_MS = 60000; // 1 minute
    private static final long COSMIC_DRIP_CHECK_WINDOW_HOURS = 1; // 1-hour window for drip spikes
    private static final int COSMIC_DRIP_THRESHOLD = 20; // Max drip in window without flagging
    private static final int SUSPICION_SCORE_THRESHOLD = 75; // Score to auto-flag
    private static final int COSMIC_STREAK_THRESHOLD = 10; // Max cosmic streak before flagging
    private static final int MOVE_RATE_THRESHOLD = 60; // Max moves per minute
    private static final int IP_SOLVE_THRESHOLD = 3; // Max solves from same IP
    private static final int DEVICE_SWITCH_THRESHOLD = 2; // Max device switches in 1 hour
    private static final int SKILL_SCORE_ANOMALY_THRESHOLD = 50; // Max skill score deviation
    private static final int MAX_CACHE_SIZE = 10000; // Cap for in-memory maps

    private final UserRepository userRepository;
    private final AntiCheatEngine antiCheatEngine;
    private final AnalyticsService analyticsService;
    private final GameRepository gameRepository;

    private final Map<String, Integer> flaggedPlayers = new ConcurrentHashMap<>(); // Player ID -> flag count
    private final Map<String, LocalDateTime> lastCosmicDripSpike = new ConcurrentHashMap<>(); // Player ID -> last drip spike time
    private final Map<String, LocalDateTime> lastBoardCheck = new ConcurrentHashMap<>(); // Player ID -> last board check time

    @Autowired
    public AntiCheatScheduler(UserRepository userRepository, AntiCheatEngine antiCheatEngine, 
                              AnalyticsService analyticsService, GameRepository gameRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository cannot be null");
        this.antiCheatEngine = Objects.requireNonNull(antiCheatEngine, "AntiCheatEngine cannot be null");
        this.analyticsService = Objects.requireNonNull(analyticsService, "AnalyticsService cannot be null");
        this.gameRepository = Objects.requireNonNull(gameRepository, "GameRepository cannot be null");
        logger.info("AntiCheatScheduler initialized with dependencies");
    }

    @Scheduled(fixedRate = SCAN_INTERVAL_MS)
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 5000))
    public void scanForCheaters() {
        MDC.put("thread", "cosmic-anti-cheat");
        logger.info("Initiating cosmic cheat scan—dripping justice across the grid...");

        LocalDateTime cutoff = LocalDateTime.now().minusHours(COSMIC_DRIP_CHECK_WINDOW_HOURS);
        Map<String, Double> suspicionScores = antiCheatEngine.getCheatSuspicionScores();

        try {
            // 1. High-point solvers in last hour
            List<User> potentialCheaters = userRepository.findPotentialCheatersByPoints(
                Constants.SOLVER_DETECTION_THRESHOLD * 1000, cutoff
            );
            checkHighPointSolvers(potentialCheaters, suspicionScores);

            // 2. Cosmic drip spike check
            Map<String, Integer> cosmicDrip = analyticsService.getCosmicDripHeatmap();
            checkCosmicDripSpikes(cosmicDrip, cutoff, suspicionScores);

            // 3. Move rate outliers
            Map<String, Integer> moveRates = antiCheatEngine.getMoveRates();
            checkMoveRateOutliers(moveRates, suspicionScores);

            // 4. Cosmic streak anomalies
            Map<String, Integer> cosmicStreaks = antiCheatEngine.getCosmicStreaks();
            checkCosmicStreaks(cosmicStreaks, suspicionScores);

            // 5. IP clustering check
            Map<String, Map<String, Integer>> ipSolves = antiCheatEngine.getIPSolveCounts();
            checkIPClustering(ipSolves, suspicionScores);

            // 6. Device switch anomalies
            Map<String, Map<String, LocalDateTime>> deviceSwitches = antiCheatEngine.getDeviceSwitches();
            checkDeviceSwitches(deviceSwitches, cutoff, suspicionScores);

            // 7. Skill score anomalies vs. peers
            Map<String, Double> skillScores = analyticsService.getPlayerSkillScores();
            checkSkillScoreAnomalies(skillScores, suspicionScores);

            // 8. Board-specific pattern analysis
            checkBoardPatterns(cutoff, suspicionScores);

            trimMaps();
            logger.info("Cosmic cheat scan complete—{} players flagged, justice drips eternal", flaggedPlayers.size());
        } catch (Exception e) {
            logger.error("Cosmic cheat scan failed: {}", e.getMessage(), e);
            throw e; // Trigger retry
        } finally {
            MDC.clear();
        }
    }

    private void checkHighPointSolvers(List<User> potentialCheaters, Map<String, Double> suspicionScores) {
        potentialCheaters.forEach(u -> {
            String playerId = u.getId().toString();
            int points = u.getPoints();
            double suspicionScore = suspicionScores.getOrDefault(playerId, 0.0);
            if (suspicionScore >= SUSPICION_SCORE_THRESHOLD) {
                flagPlayer(u, "High points in short time: " + points + ", Suspicion Score: " + suspicionScore);
            } else {
                logger.warn("Potential high-point cheater: {} - Points: {} (Suspicion: {})", u.getUsername(), points, suspicionScore);
            }
        });
    }

    private void checkCosmicDripSpikes(Map<String, Integer> cosmicDrip, LocalDateTime cutoff, Map<String, Double> suspicionScores) {
        cosmicDrip.entrySet().stream()
            .filter(e -> e.getValue() > COSMIC_DRIP_THRESHOLD)
            .filter(e -> analyticsService.getLastEventTimestamps().getOrDefault(e.getKey(), cutoff).isAfter(cutoff))
            .forEach(e -> {
                String playerId = e.getKey();
                User user = userRepository.findById(Long.parseLong(playerId)).orElse(null);
                if (user != null) {
                    double suspicionScore = suspicionScores.getOrDefault(playerId, 0.0);
                    LocalDateTime lastSpike = lastCosmicDripSpike.getOrDefault(playerId, cutoff);
                    if (suspicionScore >= SUSPICION_SCORE_THRESHOLD || now().minusMinutes(30).isBefore(lastSpike)) {
                        flagPlayer(user, "Cosmic drip spike: " + e.getValue() + ", Suspicion Score: " + suspicionScore);
                        lastCosmicDripSpike.put(playerId, now());
                    } else {
                        logger.warn("Cosmic drip anomaly: {} - Drip: {} (Suspicion: {})", user.getUsername(), e.getValue(), suspicionScore);
                    }
                }
            });
    }

    private void checkMoveRateOutliers(Map<String, Integer> moveRates, Map<String, Double> suspicionScores) {
        moveRates.entrySet().stream()
            .filter(e -> e.getValue() > MOVE_RATE_THRESHOLD)
            .forEach(e -> {
                String playerId = e.getKey();
                User user = userRepository.findById(Long.parseLong(playerId)).orElse(null);
                if (user != null) {
                    double suspicionScore = suspicionScores.getOrDefault(playerId, 0.0);
                    if (suspicionScore >= SUSPICION_SCORE_THRESHOLD) {
                        flagPlayer(user, "Excessive move rate: " + e.getValue() + " moves/min, Suspicion Score: " + suspicionScore);
                    } else {
                        logger.warn("Move rate outlier: {} - Rate: {} moves/min (Suspicion: {})", user.getUsername(), e.getValue(), suspicionScore);
                    }
                }
            });
    }

    private void checkCosmicStreaks(Map<String, Integer> cosmicStreaks, Map<String, Double> suspicionScores) {
        cosmicStreaks.entrySet().stream()
            .filter(e -> e.getValue() > COSMIC_STREAK_THRESHOLD)
            .forEach(e -> {
                String playerId = e.getKey();
                User user = userRepository.findById(Long.parseLong(playerId)).orElse(null);
                if (user != null) {
                    double suspicionScore = suspicionScores.getOrDefault(playerId, 0.0);
                    if (suspicionScore >= SUSPICION_SCORE_THRESHOLD) {
                        flagPlayer(user, "Cosmic streak anomaly: " + e.getValue() + ", Suspicion Score: " + suspicionScore);
                    } else {
                        logger.warn("Cosmic streak anomaly: {} - Streak: {} (Suspicion: {})", user.getUsername(), e.getValue(), suspicionScore);
                    }
                }
            });
    }

    private void checkIPClustering(Map<String, Map<String, Integer>> ipSolves, Map<String, Double> suspicionScores) {
        ipSolves.entrySet().stream()
            .filter(e -> e.getValue().values().stream().mapToInt(Integer::intValue).sum() > IP_SOLVE_THRESHOLD)
            .forEach(e -> {
                String ip = e.getKey();
                e.getValue().forEach((playerId, count) -> {
                    User user = userRepository.findById(Long.parseLong(playerId)).orElse(null);
                    if (user != null) {
                        double suspicionScore = suspicionScores.getOrDefault(playerId, 0.0);
                        if (suspicionScore >= SUSPICION_SCORE_THRESHOLD) {
                            flagPlayer(user, "IP clustering: " + count + " solves from IP " + ip + ", Suspicion Score: " + suspicionScore);
                        } else {
                            logger.warn("IP clustering: {} - Solves: {} from IP {} (Suspicion: {})", user.getUsername(), count, ip, suspicionScore);
                        }
                    }
                });
            });
    }

    private void checkDeviceSwitches(Map<String, Map<String, LocalDateTime>> deviceSwitches, LocalDateTime cutoff, 
                                    Map<String, Double> suspicionScores) {
        deviceSwitches.entrySet().stream()
            .filter(e -> e.getValue().entrySet().stream()
                .filter(se -> se.getValue().isAfter(cutoff))
                .count() > DEVICE_SWITCH_THRESHOLD)
            .forEach(e -> {
                String playerId = e.getKey();
                User user = userRepository.findById(Long.parseLong(playerId)).orElse(null);
                if (user != null) {
                    double suspicionScore = suspicionScores.getOrDefault(playerId, 0.0);
                    long switchCount = e.getValue().entrySet().stream()
                        .filter(se -> se.getValue().isAfter(cutoff))
                        .count();
                    if (suspicionScore >= SUSPICION_SCORE_THRESHOLD) {
                        flagPlayer(user, "Device switch anomaly: " + switchCount + " switches, Suspicion Score: " + suspicionScore);
                    } else {
                        logger.warn("Device switch anomaly: {} - Switches: {} (Suspicion: {})", user.getUsername(), switchCount, suspicionScore);
                    }
                }
            });
    }

    private void checkSkillScoreAnomalies(Map<String, Double> skillScores, Map<String, Double> suspicionScores) {
        double avgSkill = skillScores.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        skillScores.entrySet().stream()
            .filter(e -> e.getValue() > avgSkill + SKILL_SCORE_ANOMALY_THRESHOLD)
            .forEach(e -> {
                String playerId = e.getKey();
                User user = userRepository.findById(Long.parseLong(playerId)).orElse(null);
                if (user != null) {
                    double suspicionScore = suspicionScores.getOrDefault(playerId, 0.0);
                    if (suspicionScore >= SUSPICION_SCORE_THRESHOLD) {
                        flagPlayer(user, "Skill score anomaly: " + e.getValue() + " vs peer avg " + avgSkill + ", Suspicion Score: " + suspicionScore);
                    } else {
                        logger.warn("Skill score anomaly: {} - Score: {} vs peer avg {} (Suspicion: {})", user.getUsername(), e.getValue(), avgSkill, suspicionScore);
                    }
                }
            });
    }

    private void checkBoardPatterns(LocalDateTime cutoff, Map<String, Double> suspicionScores) {
        List<SudokuBoard> recentBoards = gameRepository.findActiveUnfinishedGames(cutoff, null)
            .stream()
            .filter(b -> b.getStartTime().isAfter(cutoff))
            .collect(Collectors.toList());
        recentBoards.forEach(board -> {
            String playerId = board.getGameId(); // Assuming gameId ties to playerId for simplicity
            User user = userRepository.findById(Long.parseLong(playerId)).orElse(null);
            if (user != null) {
                double suspicionScore = suspicionScores.getOrDefault(playerId, 0.0);
                int cosmicMoves = (int) board.getMoveHistory().stream()
                    .filter(m -> board.getCell(m.row(), m.col()).getStrategy() == SudokuCell.Strategy.COSMIC)
                    .count();
                int totalMoves = board.getMoveHistory().size();
                double cosmicRatio = totalMoves > 0 ? (double) cosmicMoves / totalMoves : 0.0;
                LocalDateTime lastCheck = lastBoardCheck.getOrDefault(playerId, cutoff);
                if (cosmicRatio > 0.5 && totalMoves > 10 && suspicionScore >= SUSPICION_SCORE_THRESHOLD && now().minusMinutes(30).isBefore(lastCheck)) {
                    flagPlayer(user, "Board pattern anomaly: Cosmic ratio " + cosmicRatio + ", Total Moves: " + totalMoves + 
                        ", Suspicion Score: " + suspicionScore);
                    lastBoardCheck.put(playerId, now());
                } else if (cosmicRatio > 0.5 && totalMoves > 10) {
                    logger.warn("Board pattern anomaly: {} - Cosmic ratio: {}, Moves: {} (Suspicion: {})", 
                        user.getUsername(), cosmicRatio, totalMoves, suspicionScore);
                }
            }
        });
    }

    private void flagPlayer(User user, String reason) {
        String playerId = user.getId().toString();
        flaggedPlayers.merge(playerId, 1, Integer::sum);
        antiCheatEngine.flagPlayer(playerId);
        logger.error("Cosmic justice served: {} flagged - Reason: {} (Flag Count: {})", user.getUsername(), reason, flaggedPlayers.get(playerId));
    }

    public synchronized Map<String, Integer> getFlaggedPlayers() {
        return new HashMap<>(flaggedPlayers);
    }

    public synchronized void clearFlaggedPlayer(String playerId) {
        validatePlayerId(playerId);
        flaggedPlayers.remove(playerId);
        antiCheatEngine.clearPlayerSuspicion(playerId);
        lastCosmicDripSpike.remove(playerId);
        lastBoardCheck.remove(playerId);
        logger.info("Cosmic mercy granted: Player {} unflagged—drip slate wiped clean", playerId);
    }

    private LocalDateTime now() {
        return LocalDateTime.now();
    }

    public synchronized Map<String, LocalDateTime> getLastCosmicDripSpikes() {
        return new HashMap<>(lastCosmicDripSpike);
    }

    public synchronized Map<String, LocalDateTime> getLastBoardChecks() {
        return new HashMap<>(lastBoardCheck);
    }

    private void validatePlayerId(String playerId) {
        if (playerId == null || playerId.trim().isEmpty()) {
            logger.error("Invalid playerId: {}", playerId);
            throw new IllegalArgumentException("Player ID cannot be null or empty");
        }
    }

    private void trimMaps() {
        trimMap(flaggedPlayers);
        trimMap(lastCosmicDripSpike);
        trimMap(lastBoardCheck);
    }

    private <K, V> void trimMap(Map<K, V> map) {
        if (map.size() > MAX_CACHE_SIZE) {
            int excess = map.size() - MAX_CACHE_SIZE;
            Iterator<K> iterator = map.keySet().iterator();
            for (int i = 0; i < excess && iterator.hasNext(); i++) {
                iterator.next();
                iterator.remove();
            }
            logger.debug("Trimmed map from {} to {} entries", map.size() + excess, MAX_CACHE_SIZE);
        }
    }
}
