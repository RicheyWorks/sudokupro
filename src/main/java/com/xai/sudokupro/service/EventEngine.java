package com.xai.sudokupro.service;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.util.Constants;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.xai.sudokupro.model.GameEvent;
import com.xai.sudokupro.model.SudokuGenerator;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Cosmic maestro of SudokuPro's event galaxy.
 * Orchestrates daily challenges, cosmic duels, and drip-heavy showdowns with galactic precision—App Store-ready swagger.
 */
@Service
public class EventEngine {
    private static final Logger logger = LoggerFactory.getLogger(EventEngine.class);
    private static final long DAILY_CHALLENGE_INTERVAL_HOURS = 24; // Daily reset
    private static final long COSMIC_DUEL_INTERVAL_MINUTES = 60; // Hourly cosmic duel
    private static final long DRIP_SHOWDOWN_INTERVAL_MINUTES = 15; // 15-min drip frenzy
    private static final int MAX_DUEL_PARTICIPANTS = 10; // Max players per cosmic duel
    private static final int MAX_SHOWDOWN_PARTICIPANTS = 50; // Max players per showdown
    private static final Duration DAILY_CHALLENGE_DURATION = Duration.ofHours(24);
    private static final Duration COSMIC_DUEL_DURATION = Duration.ofMinutes(30);
    private static final Duration DRIP_SHOWDOWN_DURATION = Duration.ofMinutes(10);
    private static final int MAX_ACTIVE_EVENTS = 100; // Cap for active events

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3); // 3 threads for cosmic events
    private final ConcurrentHashMap<String, EventDetails> activeEvents = new ConcurrentHashMap<>(); // Event ID -> details
    private final ConcurrentHashMap<String, Integer> playerEventScores = new ConcurrentHashMap<>(); // Player ID -> event score
    private final ConcurrentHashMap<String, Set<String>> eventParticipants = new ConcurrentHashMap<>(); // Event ID -> player IDs

    private final UserRepository userRepository;
    private final AntiCheatEngine antiCheatEngine;
    private final AnalyticsService analyticsService;
    private final SudokuGenerator sudokuGenerator;
    private final GameRepository gameRepository;
    private final MultiplayerBroadcaster multiplayerBroadcaster;

    @Autowired
    public EventEngine(UserRepository userRepository, AntiCheatEngine antiCheatEngine,
                       AnalyticsService analyticsService, SudokuGenerator sudokuGenerator,
                       GameRepository gameRepository, MultiplayerBroadcaster multiplayerBroadcaster) {
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository cannot be null");
        this.antiCheatEngine = Objects.requireNonNull(antiCheatEngine, "AntiCheatEngine cannot be null");
        this.analyticsService = Objects.requireNonNull(analyticsService, "AnalyticsService cannot be null");
        this.sudokuGenerator = Objects.requireNonNull(sudokuGenerator, "SudokuGenerator cannot be null");
        this.gameRepository = Objects.requireNonNull(gameRepository, "GameRepository cannot be null");
        this.multiplayerBroadcaster = Objects.requireNonNull(multiplayerBroadcaster, "MultiplayerBroadcaster cannot be null");
        startCosmicEvents();
        logger.info("EventEngine initialized with cosmic swagger");
    }

    public void startCosmicEvents() {
        scheduleDailyChallenge();
        scheduleCosmicDuel();
        scheduleDripShowdown();
    }

    /** Triggers a single daily-challenge cycle immediately. Used by EventScheduler. */
    public void triggerDailyChallenge() {
        scheduleDailyChallenge();
    }

    /** Triggers a single cosmic-duel cycle immediately. Used by EventScheduler. */
    public void triggerCosmicDuel() {
        scheduleCosmicDuel();
    }

    /** Triggers a single drip-showdown cycle immediately. Used by EventScheduler. */
    public void triggerDripShowdown() {
        scheduleDripShowdown();
    }

    // Bug fix: @Retryable on a private method is silently ignored by Spring AOP —
    // the proxy cannot intercept private calls, so retries never fire. Annotation removed.
    private void scheduleDailyChallenge() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                logger.info("Cosmic daily challenge ignites—drip levels rising!");
                String eventId = "daily-" + LocalDateTime.now().toString();
                SudokuBoard dailyBoard = sudokuGenerator.generate(
                    Constants.Difficulty.MEDIUM, false, false, System.currentTimeMillis(),
                    true, false, 3 // Cosmic drip factor
                );
                activeEvents.put(eventId, new EventDetails(dailyBoard, LocalDateTime.now().plus(DAILY_CHALLENGE_DURATION)));
                eventParticipants.put(eventId, Collections.synchronizedSet(new HashSet<>()));
                gameRepository.save(dailyBoard);
                multiplayerBroadcaster.broadcastEvent("daily_challenge", "New Cosmic Daily Challenge", dailyBoard.getGameId());
                logger.info("Daily challenge {} unleashed—cosmic grid ID: {}", eventId, dailyBoard.getGameId());
                scheduleEventEnd(eventId, DAILY_CHALLENGE_DURATION);
            } catch (Exception e) {
                logger.error("Cosmic daily challenge failed to ignite: {}", e.getMessage(), e);
                throw e; // Trigger retry
            }
        }, 0, DAILY_CHALLENGE_INTERVAL_HOURS, TimeUnit.HOURS);
    }

    // Bug fix: @Retryable on private methods is ignored by Spring AOP. Annotation removed.
    private void scheduleCosmicDuel() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                logger.info("Cosmic duel erupts—drip warriors assemble!");
                String eventId = "duel-" + LocalDateTime.now().toString();
                SudokuBoard duelBoard = sudokuGenerator.generate(
                    Constants.Difficulty.HARD, true, false, System.currentTimeMillis(),
                    false, true, 5 // High cosmic drip factor
                );
                activeEvents.put(eventId, new EventDetails(duelBoard, LocalDateTime.now().plus(COSMIC_DUEL_DURATION)));
                Set<String> participants = Collections.synchronizedSet(new HashSet<>());
                List<User> eligiblePlayers = userRepository.findActiveStreakersInPeriod(
                    LocalDateTime.now().minusHours(1), LocalDateTime.now(), 3
                ).stream().limit(MAX_DUEL_PARTICIPANTS).collect(Collectors.toList());
                eligiblePlayers.forEach(u -> {
                    String playerId = u.getId().toString();
                    participants.add(playerId);
                    multiplayerBroadcaster.sendToPlayer(playerId, "cosmic_duel", "Cosmic Duel Begins: " + duelBoard.getGameId());
                    logger.info("Player {} joins cosmic duel {}—drip battle ID: {}", u.getUsername(), eventId, duelBoard.getGameId());
                });
                eventParticipants.put(eventId, participants);
                gameRepository.save(duelBoard);
                scheduleEventEnd(eventId, COSMIC_DUEL_DURATION);
            } catch (Exception e) {
                logger.error("Cosmic duel failed to erupt: {}", e.getMessage(), e);
                throw e; // Trigger retry
            }
        }, 0, COSMIC_DUEL_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    // Bug fix: @Retryable on private methods is ignored by Spring AOP. Annotation removed.
    private void scheduleDripShowdown() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                logger.info("Drip showdown explodes—cosmic chaos unleashed!");
                String eventId = "showdown-" + LocalDateTime.now().toString();
                SudokuBoard showdownBoard = sudokuGenerator.generate(
                    Constants.Difficulty.EASY, true, true, System.currentTimeMillis(),
                    false, false, 7 // Max cosmic drip factor
                );
                activeEvents.put(eventId, new EventDetails(showdownBoard, LocalDateTime.now().plus(DRIP_SHOWDOWN_DURATION)));
                eventParticipants.put(eventId, Collections.synchronizedSet(new HashSet<>()));
                gameRepository.save(showdownBoard);
                multiplayerBroadcaster.broadcastEvent("drip_showdown", "Cosmic Drip Showdown Starts", showdownBoard.getGameId());
                logger.info("Drip showdown {} blasts off—cosmic grid ID: {}", eventId, showdownBoard.getGameId());
                scheduleEventEnd(eventId, DRIP_SHOWDOWN_DURATION);
            } catch (Exception e) {
                logger.error("Drip showdown failed to explode: {}", e.getMessage(), e);
                throw e; // Trigger retry
            }
        }, 0, DRIP_SHOWDOWN_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    private void scheduleEventEnd(String eventId, Duration duration) {
        scheduler.schedule(() -> endEvent(eventId), duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void submitEventScore(String playerId, String eventId, SudokuBoard board) {
        validatePlayerId(playerId);
        EventDetails event = activeEvents.get(eventId);
        if (event == null || event.getEndTime().isBefore(LocalDateTime.now())) {
            logger.warn("Player {} submitted score for expired event {}", playerId, eventId);
            return;
        }
        User user = findUserByPlayerId(playerId);
        if (user == null) {
            logger.error("Unknown player {} attempted to submit score for event {}", playerId, eventId);
            return;
        }
        if (antiCheatEngine.detectCheating(board, user)) {
            logger.error("Player {} flagged by cosmic anti-cheat—score rejected for event {}", playerId, eventId);
            return;
        }
        Set<String> participants = eventParticipants.getOrDefault(eventId, Collections.emptySet());
        if (!participants.contains(playerId)) {
            logger.warn("Player {} not a participant in event {}", playerId, eventId);
            return;
        }
        int score = calculateEventScore(board);
        playerEventScores.put(playerId + "-" + eventId, score); // Unique key per player-event
        analyticsService.recordEvent(new GameEvent(GameEvent.EventType.SCORE, playerId, 
            Map.of("eventId", eventId, "score", String.valueOf(score))));
        logger.info("Player {} drips score {} for event {}—cosmic leaderboard updated", user.getUsername(), score, eventId);
        multiplayerBroadcaster.sendToPlayer(playerId, "event_score", "Score Submitted: " + score + " (event: " + eventId + ")");
    }

    private int calculateEventScore(SudokuBoard board) {
        int baseScore = board.isSolved() ? 1000 : 0;
        int dripBonus = board.getCosmicDripLevel() * 50;
        int timePenalty = (int) (board.getSolveTime().toSeconds() / 10);
        int hintPenalty = board.getHintCount() * 100;
        int score = Math.max(0, baseScore + dripBonus - timePenalty - hintPenalty);
        logger.trace("Calculated score for gameId {}: Base={}, Drip={}, Time={}, Hints={}, Total={}", 
            board.getGameId(), baseScore, dripBonus, timePenalty, hintPenalty, score);
        return score;
    }

    public synchronized void endEvent(String eventId) {
        EventDetails event = activeEvents.remove(eventId);
        if (event != null) {
            logger.info("Event {} ends—cosmic grid {} retires", eventId, event.getBoard().getGameId());
            multiplayerBroadcaster.broadcastEvent("event_end", "Event " + eventId + " Concluded", event.getBoard().getGameId());
            distributeRewards(eventId);
            eventParticipants.remove(eventId);
            trimPlayerEventScores();
        } else {
            logger.warn("Attempted to end non-existent event: {}", eventId);
        }
    }

    private void distributeRewards(String eventId) {
        String suffix = "-" + eventId;
        Map<String, Integer> eventScores = playerEventScores.entrySet().stream()
            .filter(e -> e.getKey().endsWith(suffix))
            .collect(Collectors.toMap(
                // Bug fix: indexOf finds the FIRST occurrence of the suffix, which is wrong if
                // the playerId itself contains the same substring.  Use lastIndexOf so we always
                // strip only the trailing "-<eventId>" portion.
                e -> e.getKey().substring(0, e.getKey().lastIndexOf(suffix)),
                Map.Entry::getValue
            ));
        eventScores.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(3) // Top 3 get rewards
            .forEach(e -> {
                String playerId = e.getKey();
                int score = e.getValue();
                User user = findUserByPlayerId(playerId);
                if (user != null) {
                    int gems = score / 100; // 1 gem per 100 points
                    int xp = score / 10; // 10 XP per 100 points
                    user.addGems(gems);
                    user.addXp(xp);
                    userRepository.save(user);
                    logger.info("Cosmic reward drips: {} earns {} gems, {} XP for event {}", user.getUsername(), gems, xp, eventId);
                    multiplayerBroadcaster.sendToPlayer(playerId, "event_reward", "Earned " + gems + " gems, " + xp + " XP (event: " + eventId + ")");
                }
                playerEventScores.remove(playerId + "-" + eventId);
            });
    }

    public synchronized Map<String, EventDetails> getActiveEvents() {
        return new ConcurrentHashMap<>(activeEvents);
    }

    public synchronized Map<String, Integer> getPlayerEventScores() {
        return new ConcurrentHashMap<>(playerEventScores);
    }

    public synchronized Map<String, Set<String>> getEventParticipants() {
        // Shallow copy exposes mutable inner Sets — return a deep unmodifiable snapshot.
        Map<String, Set<String>> snapshot = new HashMap<>();
        eventParticipants.forEach((eventId, participants) ->
            snapshot.put(eventId, Collections.unmodifiableSet(new HashSet<>(participants))));
        return Collections.unmodifiableMap(snapshot);
    }

    public synchronized void joinEvent(String playerId, String eventId) {
        validatePlayerId(playerId);
        EventDetails event = activeEvents.get(eventId);
        if (event == null || event.getEndTime().isBefore(LocalDateTime.now())) {
            logger.warn("Player {} attempted to join expired event {}", playerId, eventId);
            return;
        }
        Set<String> participants = eventParticipants.computeIfAbsent(eventId, k -> Collections.synchronizedSet(new HashSet<>()));
        int maxParticipants = eventId.startsWith("duel-") ? MAX_DUEL_PARTICIPANTS : MAX_SHOWDOWN_PARTICIPANTS;
        if (participants.size() >= maxParticipants) {
            logger.warn("Player {} cannot join event {}—participant limit ({}) reached", playerId, eventId, maxParticipants);
            return;
        }
        User user = findUserByPlayerId(playerId);
        if (user != null && participants.add(playerId)) {
            logger.info("Player {} joins event {}—cosmic grid ID: {}", user.getUsername(), eventId, event.getBoard().getGameId());
            multiplayerBroadcaster.sendToPlayer(playerId, "event_join", "Joined Event " + eventId + ": " + event.getBoard().getGameId());
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
                logger.warn("Cosmic event scheduler forced to shutdown");
            } else {
                logger.info("Cosmic event scheduler gracefully shutdown");
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
            logger.error("Failed to shutdown cosmic event scheduler: {}", e.getMessage(), e);
        }
    }

    private void validatePlayerId(String playerId) {
        if (playerId == null || playerId.trim().isEmpty()) {
            logger.error("Invalid playerId: {}", playerId);
            throw new IllegalArgumentException("Player ID cannot be null or empty");
        }
    }

    private User findUserByPlayerId(String playerId) {
        if (playerId == null || playerId.isBlank() || "anonymous".equals(playerId)) {
            return null;
        }
        try {
            return userRepository.findById(Long.parseLong(playerId)).orElse(null);
        } catch (NumberFormatException e) {
            logger.debug("Skipping non-numeric playerId '{}' in event engine", playerId);
            return null;
        }
    }

    private void trimPlayerEventScores() {
        int MAX_SCORES = 10000;
        while (playerEventScores.size() > MAX_SCORES) {
            playerEventScores.remove(playerEventScores.keySet().iterator().next());
        }
    }

    public static class EventDetails {
        private final SudokuBoard board;
        private final java.time.LocalDateTime endTime;

        public EventDetails(SudokuBoard board, java.time.LocalDateTime endTime) {
            this.board   = Objects.requireNonNull(board);
            this.endTime = Objects.requireNonNull(endTime);
        }

        public SudokuBoard          getBoard()   { return board; }
        public java.time.LocalDateTime getEndTime() { return endTime; }
    }
}
