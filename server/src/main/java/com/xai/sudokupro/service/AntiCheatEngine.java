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
    private final Map<String, AtomicInteger> cosmicMoveRates = new ConcurrentHashMap<>();
    // ip -> (playerId -> solves) and playerId -> (platform -> last seen). Both are read by
    // AntiCheatScheduler; both are written by detectCheating(board, user).
    private final Map<String, Map<String, Integer>> ipSolveCounts = new ConcurrentHashMap<>();
    private final Map<String, Integer> cosmicStreaks = new ConcurrentHashMap<>();
    private final Map<String, Map<String, LocalDateTime>> deviceSwitches = new ConcurrentHashMap<>();
    // Removed: cosmicConsistency and movePatterns. Neither was ever written or read —
    // they were declared, trimmed on every observation, and nothing else.

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

        // Observation bookkeeping. `ip` and `platform` were computed above and then
        // dropped on the floor, which left ipSolveCounts and deviceSwitches permanently
        // empty — so AntiCheatScheduler's IP-clustering and device-switch detectors read
        // empty maps and could never fire, however blatant the behaviour.
        ipSolveCounts.computeIfAbsent(ip, k -> new ConcurrentHashMap<>()).merge(playerId, 1, Integer::sum);
        deviceSwitches.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(platform, now);

        int signals = 0;

        // solveTime == 0 means "no elapsed time recorded", not "solved instantly": it is
        // the initial value on an unsolved board, and it was also what every board
        // rehydrated from the database or Redis reported before SudokuBoard restored the
        // transient Duration from solveTimeSeconds. Scoring it would generate two signals
        // (this one and the per-move-time one below) from a serialization artefact. The
        // single-argument overload already guards this way; this one did not.
        if (solveTime > 0 && solveTime < (difficulty * MIN_SOLVE_TIME_PER_DIFFICULTY)) {
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

        if (solveTime > 0 && moves > 0 && solveTime / moves < MIN_EXPECTED_MOVE_TIME_MS) {
            signals++;
        }

        // A burst of duel wins, not a lifetime total.
        //
        // This used to require the in-memory analytics count to be EXACTLY EQUAL to the
        // player's persisted lifetime count, and both to exceed the threshold. Those two
        // numbers measure different things: analytics counts what THIS process has seen
        // since it started (and trims its maps), while User.duelWins is the lifetime
        // total across every replica since the account was created. They coincide only
        // for a player whose entire duel history happened inside one pod's uptime with no
        // trimming — so after any restart, and on every pod in a multi-replica
        // deployment, the detector could not fire at all. Pass 15 made the analytics side
        // reachable; this makes the comparison mean something.
        //
        // The rate is the signal worth having. A veteran with thousands of lifetime wins
        // is not suspicious; twenty wins in one short window is. Known limitation, stated
        // rather than hidden: analytics is per-pod, so an attacker spread across replicas
        // is undercounted — the same caveat every other in-memory detector here carries.
        int recentDuelWins = analyticsService.getDuelWins().getOrDefault(playerId, 0);
        if (recentDuelWins > MAX_DUEL_WIN_STREAK) {
            logger.debug("Duel-win burst for {}: {} wins observed recently (lifetime {})",
                playerId, recentDuelWins, duelWins);
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

    /**
     * Scores a completed board against the player who solved it, feeding the running
     * suspicion score that {@link AntiCheatScheduler} enforces on.
     *
     * <p>This exists because the anti-cheat subsystem was structurally inert. The only
     * writer of {@code suspicionScoreMap} is {@link #detectCheating(SudokuBoard, User)},
     * and its sole caller was {@code EventEngine.submitEventScore} — which has no callers
     * of its own anywhere in the codebase: no controller maps to it, no service invokes
     * it. So the map was always empty, {@link #getCheatSuspicionScores()} always returned
     * nothing, and all eight detectors in AntiCheatScheduler compared
     * {@code 0.0 >= 75} every 60 seconds and took the else branch. Every detector was
     * reachable, none could ever fire; the scheduler paged through up to 500 boards and
     * the whole user table each minute to reach a decision it could not make. The
     * {@code sudokupro.suspicious.players} gauge read a flat zero for the same reason,
     * so the dashboard confirmed the system was working.
     *
     * <p>Resolving the {@link User} here rather than at the call site keeps GameService's
     * dependency graph unchanged — this class already holds the repository.
     *
     * @return true if the player currently carries at least one active suspicion signal
     */
    public synchronized boolean scoreCompletedGame(SudokuBoard board, String playerId) {
        if (board == null || playerId == null || playerId.isBlank() || "anonymous".equals(playerId)) {
            return false;
        }
        long userId;
        try {
            userId = Long.parseLong(playerId);
        } catch (NumberFormatException e) {
            logger.debug("Skipping cheat scoring for non-numeric playerId '{}'", playerId);
            return false;
        }
        return userRepository.findById(userId)
            .map(user -> detectCheating(board, user))
            .orElse(false);
    }

    public synchronized Map<String, Double> getCheatSuspicionScores() {
        return Collections.unmodifiableMap(new HashMap<>(suspicionScoreMap));
    }

    /**
     * Records an anti-cheat flag against {@code playerId} and applies the drip penalty.
     *
     * <p>This used to halve {@code cosmicDrip} and return. Nothing recorded that the account
     * had been flagged at all: the only "flag" was an entry in
     * {@link AntiCheatScheduler}'s {@code flaggedPlayers} map — one bean, one pod, erased by
     * any restart or rolling deploy and never visible to the other replicas. So the penalty
     * was durable and recurring (the scheduler re-flags every 60 seconds, halving the balance
     * again each time) while the decision behind it was not recorded anywhere a human could
     * read, and "is this player flagged, and since when?" had no answer after a redeploy.
     *
     * <p>The flag now lives on the {@code users} row: a count that accumulates, a first
     * sighting that is never overwritten, and a last sighting that advances. It is queryable
     * via {@link com.xai.sudokupro.repository.UserRepository#findFlaggedPlayers()} and cleared
     * by {@link #clearPlayerSuspicion(String)}, which is the moderation "false positive" path.
     *
     * <p>Scope note: this is additive. The in-memory {@code suspicionScoreMap} exposed by
     * {@link #getCheatSuspicionScores()} and its threshold are the enforcement signal consumed
     * elsewhere and are deliberately left exactly as they were.
     */
    public synchronized void flagPlayer(String playerId) {
        Long userId = persistentUserId(playerId);
        if (userId == null) return;
        userRepository.findById(userId).ifPresent(user -> {
            user.setCosmicDrip(Math.max(0, user.getCosmicDrip() / 2));
            user.recordCheatFlag(LocalDateTime.now());
            userRepository.save(user);
            logger.info("Flagged player {} (flag #{}, first seen {}): cosmicDrip halved to {}",
                playerId, user.getCheatFlagCount(), user.getFirstFlaggedAt(), user.getCosmicDrip());
        });
    }

    /**
     * Whether a durable anti-cheat flag is currently held against {@code playerId}.
     *
     * <p>Reads the database rather than any in-process map on purpose: the point of the
     * persisted flag is that it answers correctly on a pod that did not make the decision.
     */
    public boolean isFlagged(String playerId) {
        Long userId = persistentUserId(playerId);
        if (userId == null) return false;
        return userRepository.findById(userId).map(User::isCheatFlagged).orElse(false);
    }

    /**
     * Resolves a playerId to a persistent user id, or null when there is no row behind it
     * (anonymous play, daily/duel template pseudo-players, blank ids).
     */
    private Long persistentUserId(String playerId) {
        if (playerId == null || playerId.isBlank() || "anonymous".equals(playerId)) {
            logger.debug("Skipping flag operation for non-persistent playerId: {}", playerId);
            return null;
        }
        try {
            return Long.parseLong(playerId);
        } catch (NumberFormatException e) {
            logger.debug("Non-numeric playerId '{}' has no DB record to flag", playerId);
            return null;
        }
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
        // The moderation "false positive" path (AntiCheatScheduler.clearFlaggedPlayer) is the
        // only caller. Clearing only the in-memory signal would leave the durable flag set
        // forever, so an exonerated player would still read as flagged to every other pod
        // and to whoever looks at the record next.
        Long userId = persistentUserId(playerId);
        if (userId != null) {
            userRepository.findById(userId).ifPresent(user -> {
                if (user.isCheatFlagged()) {
                    user.clearCheatFlags();
                    userRepository.save(user);
                }
            });
        }
        logger.info("Cleared suspicion data and anti-cheat flags for player {}", playerId);
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
        trimMap(cosmicMoveRates);
        trimMap(ipSolveCounts);
        trimMap(deviceSwitches);
    }

    private <K, V> void trimMap(Map<K, V> map) {
        while (map.size() > MAX_CACHE_SIZE) {
            map.remove(map.keySet().iterator().next());
        }
    }
}
