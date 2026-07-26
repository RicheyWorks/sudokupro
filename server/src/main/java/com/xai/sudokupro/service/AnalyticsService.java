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

    // ---- Skill-score model (see getPlayerSkillScores) ----
    /** Weights sum to 100, so the score is directly a 0-100 scale. */
    private static final double WEIGHT_SPEED = 45.0;
    private static final double WEIGHT_HINT_FRUGALITY = 30.0;
    private static final double WEIGHT_MOVE_ECONOMY = 25.0;
    /** Average seconds per solve at which the speed component is worth half its weight. */
    private static final double REFERENCE_SOLVE_SECONDS = 300.0;
    /** Moves per solve at which the economy component is worth half its weight. */
    private static final double REFERENCE_MOVES_PER_SOLVE = 60.0;

    private final Map<String, Integer> mistakeHeatmap = new ConcurrentHashMap<>();
    private final Map<String, Integer> cosmicDripHeatmap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> cellMistakeHeatmap = new ConcurrentHashMap<>();
    private final Map<String, Integer> hintUsage = new ConcurrentHashMap<>();
    // Cumulative seconds spent solving, per player, and the number of solves that make it
    // up. The count is what makes getAverageSolveTime() an average per SOLVE rather than
    // an average of per-player totals.
    private final Map<String, Long> solveTimes = new ConcurrentHashMap<>();
    private final Map<String, Integer> solveCounts = new ConcurrentHashMap<>();
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
            solveCounts.merge(playerId, 1, Integer::sum);

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

    /**
     * Mean seconds per solve across all recorded solves.
     *
     * <p>This averaged {@code solveTimes.values()} directly, but that map holds a
     * CUMULATIVE sum per player, not one entry per solve. So the "average solve time"
     * reported to the {@code sudokupro.solve.time.average} gauge was really the average
     * lifetime total per player, and it climbed without bound as players kept playing —
     * a regular who had solved forty puzzles contributed forty puzzles' worth of seconds
     * as a single sample. Dividing the summed seconds by the summed solve count gives the
     * per-solve figure the metric name promises.
     */
    public synchronized double getAverageSolveTime() {
        long totalSolves = solveCounts.values().stream().mapToLong(Integer::longValue).sum();
        if (totalSolves == 0) return 0.0;
        long totalSeconds = solveTimes.values().stream().mapToLong(Long::longValue).sum();
        return (double) totalSeconds / totalSolves;
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
        solveCounts.clear();
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
        trim(solveCounts);
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

    /**
     * Per-player skill score on a 0-100 scale, derived from the play data this service
     * actually receives.
     *
     * <p>This was a stub — {@code return new HashMap<>();} — parked under the
     * "COMPATIBILITY METHODS" banner. Three production callers depend on it and all three
     * were silently inert: {@link LeaderboardService#getTopPlayersCombinedPaged(int, int)}
     * ranks by this map, so the combined leaderboard returned {@code []} for every request
     * ever made; {@link AntiCheatEngine#detectCheating(SudokuBoard, User)} compares a player
     * against {@code peerAverage * 2.5}, which can never trigger when every score is absent;
     * and {@code AntiCheatScheduler.checkSkillScoreAnomalies} iterated an empty map every
     * 60 seconds. None of them logged anything, so the failure mode was a feature that
     * looked implemented and returned nothing.
     *
     * <h4>What the score is made of</h4>
     * Three per-solve rates, each normalised into {@code (0,1]} and weighted to sum to 100:
     * <ul>
     *   <li><b>Speed ({@value #WEIGHT_SPEED})</b> — {@code ref / (ref + averageSecondsPerSolve)},
     *       with {@code ref = }{@value #REFERENCE_SOLVE_SECONDS}s. A player averaging the
     *       reference time scores half of this component; the curve is smooth and needs no
     *       clamping at either end.</li>
     *   <li><b>Hint frugality ({@value #WEIGHT_HINT_FRUGALITY})</b> —
     *       {@code 1 / (1 + hintsPerSolve)}. Solving unaided is the top of the scale; one
     *       hint per puzzle halves the component.</li>
     *   <li><b>Move economy ({@value #WEIGHT_MOVE_ECONOMY})</b> —
     *       {@code ref / (ref + movesPerSolve)}, {@code ref = }{@value #REFERENCE_MOVES_PER_SOLVE}.
     *       A 9x9 grid needs roughly 30-50 placements, so a player spending hundreds of moves
     *       per finished puzzle is guessing and correcting.</li>
     * </ul>
     *
     * <p>Every component is a <em>rate per solve</em>, never a total. That is deliberate: a
     * score built from lifetime sums ranks by hours played and climbs forever for regulars,
     * which is exactly the defect {@link #getAverageSolveTime()} used to have.
     *
     * <h4>Who is scored</h4>
     * Only players with at least one recorded SOLVE. Moves and hints alone are evidence of
     * activity, not of skill, and emitting a 0.0 for them would seat them on the combined
     * leaderboard below every real player — a ranking claim the data does not support.
     *
     * <h4>What is deliberately not in the formula</h4>
     * {@code duelWins}, {@code duelLosses} and {@code streakRecords} are <em>unreachable</em>:
     * {@link #recordEvent} only fills them for event types {@code "DUEL_WIN"},
     * {@code "DUEL_LOSS"} and {@code "STREAK_UPDATE"}, and {@link GameEvent.EventType} has no
     * such constants (it is MOVE, HINT, SOLVE, JOIN, LEAVE, SCORE), so those branches cannot
     * execute. Weighting a permanently-empty signal would have re-created the bug being
     * fixed here. {@code cosmicDripHeatmap} is unreachable for the same practical reason —
     * nothing sets the {@code "cosmic"} payload flag on a MOVE. And {@code mistakeHeatmap}
     * is used strictly as a move counter, which is what {@link #recordEvent} increments it
     * for; reading it as "mistakes" would encode a meaning it does not carry. Duel skill is
     * separately and correctly served by the duel leaderboard, which reads persisted
     * {@code User.duelWins}.
     *
     * @return player id to score in {@code (0,100]}; players with no completed puzzle are absent
     */
    public synchronized Map<String, Double> getPlayerSkillScores() {
        Map<String, Double> scores = new HashMap<>();
        for (Map.Entry<String, Integer> entry : solveCounts.entrySet()) {
            String playerId = entry.getKey();
            int solves = entry.getValue() == null ? 0 : entry.getValue();
            if (solves <= 0) continue;

            double avgSolveSeconds = (double) solveTimes.getOrDefault(playerId, 0L) / solves;
            double hintsPerSolve   = (double) hintUsage.getOrDefault(playerId, 0) / solves;
            double movesPerSolve   = (double) mistakeHeatmap.getOrDefault(playerId, 0) / solves;

            double speed     = REFERENCE_SOLVE_SECONDS / (REFERENCE_SOLVE_SECONDS + Math.max(0.0, avgSolveSeconds));
            double frugality = 1.0 / (1.0 + Math.max(0.0, hintsPerSolve));
            double economy   = REFERENCE_MOVES_PER_SOLVE / (REFERENCE_MOVES_PER_SOLVE + Math.max(0.0, movesPerSolve));

            double score = WEIGHT_SPEED * speed
                         + WEIGHT_HINT_FRUGALITY * frugality
                         + WEIGHT_MOVE_ECONOMY * economy;

            scores.put(playerId, Math.max(0.0, Math.min(100.0, score)));
        }
        return scores;
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

    public Map<String, LocalDateTime> getLastEventTimestamps() {
        return Collections.unmodifiableMap(lastEventTimestamps);
    }

    public Map<String, Integer> getHintUsage() {
        return Collections.unmodifiableMap(hintUsage);
    }

    public Map<String, Integer> getStreakRecords() {
        return Collections.unmodifiableMap(streakRecords);
    }

    /**
     * Returns the average cosmic-drip level for players active since {@code since}.
     * Looks up the cosmicDripHeatmap for each active player and averages the values.
     */
    public synchronized double getAverageCosmicDripActiveUsers(LocalDateTime since) {
        return lastEventTimestamps.entrySet().stream()
                .filter(e -> e.getValue().isAfter(since))
                .mapToInt(e -> cosmicDripHeatmap.getOrDefault(e.getKey(), 0))
                .average()
                .orElse(0.0);
    }

    /**
     * Returns win-rate per player as wins / (wins + losses).
     * Players with no recorded outcomes are excluded from the map.
     */
    public synchronized Map<String, Double> getPlayerWinRates() {
        Map<String, Double> rates = new HashMap<>();
        Set<String> allPlayers = new HashSet<>(duelWins.keySet());
        allPlayers.addAll(duelLosses.keySet());
        for (String playerId : allPlayers) {
            int wins   = duelWins.getOrDefault(playerId, 0);
            int losses = duelLosses.getOrDefault(playerId, 0);
            int total  = wins + losses;
            if (total > 0) {
                rates.put(playerId, (double) wins / total);
            }
        }
        return rates;
    }
}
