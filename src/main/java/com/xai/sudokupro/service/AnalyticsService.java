package com.xai.sudokupro.service;

import com.xai.sudokupro.model.GameEvent;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.domain.PageRequest;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsService.class);
    private static final int MAX_CACHE_SIZE = 10000;

    private final Map<String, Integer> mistakeHeatmap = new ConcurrentHashMap<>();
    private final Map<String, Integer> cosmicDripHeatmap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> cellMistakeHeatmap = new ConcurrentHashMap<>();
    private final Map<String, Integer> hintUsage = new ConcurrentHashMap<>();
    private final Map<String, Long> solveTimes = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> lastEventTimestamps = new ConcurrentHashMap<>();
    private final Map<String, Integer> duelWins = new ConcurrentHashMap<>();
    private final Map<String, Integer> duelLosses = new ConcurrentHashMap<>();
    private final Map<String, Integer> streakRecords = new ConcurrentHashMap<>();

    private final AISolverService aiSolverService;
    private final UserRepository userRepository;
    private final GameRepository gameRepository;

    @Autowired
    public AnalyticsService(AISolverService aiSolverService,
                            UserRepository userRepository,
                            GameRepository gameRepository) {
        this.aiSolverService = Objects.requireNonNull(aiSolverService);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.gameRepository = Objects.requireNonNull(gameRepository);
    }

    public synchronized void recordEvent(GameEvent event) {

        String playerId = event.getPlayerId();
        validatePlayerId(playerId);

        lastEventTimestamps.put(playerId, LocalDateTime.now());

        Map<String, Object> payload = event.getPayload();
        String eventType = String.valueOf(event.getType());

        if ("MOVE".equals(eventType)) {
            mistakeHeatmap.merge(playerId, 1, Integer::sum);

            Map<String, Integer> playerCellHeatmap =
                    cellMistakeHeatmap.computeIfAbsent(playerId, k -> new HashMap<>());

            String row = String.valueOf(payload.getOrDefault("row", "0"));
            String col = String.valueOf(payload.getOrDefault("col", "0"));
            String cellKey = row + "," + col;

            playerCellHeatmap.merge(cellKey, 1, Integer::sum);

            boolean cosmic = Boolean.parseBoolean(String.valueOf(payload.getOrDefault("cosmic", "false")));
            if (cosmic) {
                cosmicDripHeatmap.merge(playerId, 1, Integer::sum);
            }

        } else if ("HINT".equals(eventType)) {
            hintUsage.merge(playerId, 1, Integer::sum);

        } else if ("SOLVE".equals(eventType)) {
            long solveTime = Long.parseLong(String.valueOf(payload.getOrDefault("solveTimeSeconds", "0")));
            solveTimes.merge(playerId, solveTime, Long::sum);

        } else if ("DUEL_WIN".equals(eventType)) {
            duelWins.merge(playerId, 1, Integer::sum);

        } else if ("DUEL_LOSS".equals(eventType)) {
            duelLosses.merge(playerId, 1, Integer::sum);

        } else if ("STREAK_UPDATE".equals(eventType)) {
            int streak = Integer.parseInt(String.valueOf(payload.getOrDefault("streak", "0")));
            streakRecords.merge(playerId, streak, Math::max);

        } else {
            logger.warn("Unknown event type {}", event.getType());
        }

        trimMaps();
    }

    public synchronized Map<String, Integer> getCosmicCellHotspots(String playerId) {

        validatePlayerId(playerId);

        // Fetch only the most recent game for this player — avoids full table scan
        List<SudokuBoard> boards = gameRepository.findByPlayerId(playerId, PageRequest.of(0, 1));

        if (!boards.isEmpty()) {
            Map<String, Integer> aiHotspots = aiSolverService.getCosmicHotspotMap();

            Map<String, Integer> playerCells =
                    cellMistakeHeatmap.getOrDefault(playerId, new HashMap<>());

            Map<String, Integer> combined = new HashMap<>(aiHotspots);
            playerCells.forEach((cell, count) -> combined.merge(cell, count, Integer::sum));

            return combined;
        }

        return new HashMap<>();
    }

    public synchronized double getAverageSolveTime() {
        return solveTimes.values().stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);
    }

    public synchronized Map<String, Integer> getActivePlayers(LocalDateTime since) {
        return lastEventTimestamps.entrySet().stream()
                .filter(e -> e.getValue().isAfter(since))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> mistakeHeatmap.getOrDefault(e.getKey(), 0)
                ));
    }

    public synchronized void resetAnalytics() {
        mistakeHeatmap.clear();
        cosmicDripHeatmap.clear();
        cellMistakeHeatmap.clear();
        hintUsage.clear();
        solveTimes.clear();
        lastEventTimestamps.clear();
        duelWins.clear();
        duelLosses.clear();
        streakRecords.clear();
    }

    private void validatePlayerId(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("Player ID cannot be null or empty");
        }
    }

    private void trimMaps() {
        trim(mistakeHeatmap);
        trim(cosmicDripHeatmap);
        trim(cellMistakeHeatmap);
        trim(hintUsage);
        trim(solveTimes);
        trim(lastEventTimestamps);
        trim(duelWins);
        trim(duelLosses);
        trim(streakRecords);
    }

    private <K, V> void trim(Map<K, V> map) {
        while (map.size() > MAX_CACHE_SIZE) {
            Iterator<K> it = map.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    // ---- COMPATIBILITY METHODS ----

    public void logEvent(String type, Map<String, Object> data) {}

    public Map<String, Double> getPlayerSkillScores() {
        return new HashMap<>();
    }

    public Map<String, Integer> getDuelWins() {
        return Collections.unmodifiableMap(duelWins);
    }

    public Map<String, Integer> getMistakeHeatmap() {
        return Collections.unmodifiableMap(mistakeHeatmap);
    }

    public Map<String, Integer> getCosmicDripHeatmap() {
        return Collections.unmodifiableMap(cosmicDripHeatmap);
    }

    public Map<String, Long> getSolveTimes() {
        return Collections.unmodifiableMap(solveTimes);
    }

    public Map<String, Integer> getHintUsage() {
        return Collections.unmodifiableMap(hintUsage);
    }
}
