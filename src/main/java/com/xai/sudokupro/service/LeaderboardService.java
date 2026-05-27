package com.xai.sudokupro.service;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.repository.leaderboard.LeaderboardRepository;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.xai.sudokupro.repository.UserSummary;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Cosmic curator of SudokuPro's galactic rankings.
 * Masters leaderboards with points, drip, hype, duels, and dynamic cosmic metrics—dripping precision for App Store glory.
 */
@Service
public class LeaderboardService {
    private static final Logger logger = LoggerFactory.getLogger(LeaderboardService.class);
    private static final int DEFAULT_TOP_LIMIT = 10; // Default top players limit
    private static final int MAX_TOP_LIMIT = 100; // Max allowed limit for top players
    private static final int COSMIC_DRIP_THRESHOLD = 50; // Threshold for cosmic drip leaderboard
    private static final int HYPE_METER_THRESHOLD = 100; // Threshold for hype leaderboard
    private static final double SUSPICION_THRESHOLD = 75.0; // Threshold to exclude cheaters
    private static final int RECENT_ACTIVITY_DAYS = 7; // Window for recent activity rankings
    private static final int TIER_BRONZE_THRESHOLD = 1000; // Points for Bronze tier
    private static final int TIER_SILVER_THRESHOLD = 5000; // Points for Silver tier
    private static final int TIER_GOLD_THRESHOLD = 10000; // Points for Gold tier
    private static final int TIER_COSMIC_THRESHOLD = 25000; // Points for Cosmic tier

    private final UserRepository userRepository;
    private final LeaderboardRepository leaderboardRepository;
    private final AnalyticsService analyticsService;
    private final AntiCheatEngine antiCheatEngine;
    private final MultiplayerBroadcaster multiplayerBroadcaster;
    // Fix 1: eventEngine was referenced (getPlayerEventScores) but never declared — compile error.
    private final EventEngine eventEngine;

    private final Map<String, Integer> scoreDeltas = new ConcurrentHashMap<>(); // Player ID -> recent points delta

    @Autowired
    public LeaderboardService(UserRepository userRepository, LeaderboardRepository leaderboardRepository,
                              AnalyticsService analyticsService, AntiCheatEngine antiCheatEngine,
                              MultiplayerBroadcaster multiplayerBroadcaster, EventEngine eventEngine) {
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository cannot be null");
        this.leaderboardRepository = Objects.requireNonNull(leaderboardRepository, "LeaderboardRepository cannot be null");
        this.analyticsService = Objects.requireNonNull(analyticsService, "AnalyticsService cannot be null");
        this.antiCheatEngine = Objects.requireNonNull(antiCheatEngine, "AntiCheatEngine cannot be null");
        this.multiplayerBroadcaster = Objects.requireNonNull(multiplayerBroadcaster, "MultiplayerBroadcaster cannot be null");
        this.eventEngine = Objects.requireNonNull(eventEngine, "EventEngine cannot be null");
        logger.info("LeaderboardService initialized with cosmic dependencies");
    }

    @Transactional
@CacheEvict(value = {"topPlayers", "leaderboardSummary"}, allEntries = true)
public void updateScore(Long userId, int points) {
    validateUserId(userId);
    validatePoints(points);

    safeExecute(() -> {
        Optional<User> userOpt = userRepository.findById(userId);
        User user = userOpt.orElseGet(() -> {
            User newUser = new User(userId, "Player_" + userId);
            logger.info("New user created for leaderboard: Player_{}", userId);
            return newUser;
        });
        int oldPoints = user.getPoints();
        user.addPoints(points);
        userRepository.save(user);
        int delta = user.getPoints() - oldPoints;
        scoreDeltas.merge(userId.toString(), delta, Integer::sum);
        logger.debug("Score updated for user {}: +{} points, new total: {}, delta: {}", userId, points, user.getPoints(), delta);
        broadcastLeaderboardUpdate(user, delta);
    }, "score update for user " + userId);
}

    @Cacheable(value = "topPlayers", key = "'points-' + #page + '-' + #size")
    public List<LeaderboardSnapshot> getTopPlayersByPointsPaged(int page, int size) {
        validatePagination(page, size);
        return safeFetch(() -> {
            List<User> topPlayers = leaderboardRepository.findTopUsersByPoints(PageRequest.of(page, size));
            List<LeaderboardSnapshot> snapshots = mapToSnapshots(topPlayers, page, size, "points");
            logLeaderboardFetch("points", snapshots.size());
            return snapshots;
        }, "top players by points");
    }

    @Cacheable(value = "topPlayers", key = "'drip-' + #page + '-' + #size")
    public List<LeaderboardSnapshot> getTopPlayersByCosmicDripPaged(int page, int size) {
        validatePagination(page, size);
        return safeFetch(() -> {
            List<User> topPlayers = leaderboardRepository.findTopCosmicDrippers(
                COSMIC_DRIP_THRESHOLD, PageRequest.of(page, size));
            List<LeaderboardSnapshot> snapshots = mapToSnapshots(topPlayers, page, size, "cosmicDrip");
            logLeaderboardFetch("cosmic drip", snapshots.size());
            return snapshots;
        }, "top players by cosmic drip");
    }

    @Cacheable(value = "topPlayers", key = "'hype-' + #page + '-' + #size")
    public List<LeaderboardSnapshot> getTopPlayersByHypePaged(int page, int size) {
        validatePagination(page, size);
        return safeFetch(() -> {
            List<User> topPlayers = leaderboardRepository.findHypeLegends(
                HYPE_METER_THRESHOLD, PageRequest.of(page, size));
            List<LeaderboardSnapshot> snapshots = mapToSnapshots(topPlayers, page, size, "hypeMeter");
            logLeaderboardFetch("hype meter", snapshots.size());
            return snapshots;
        }, "top players by hype meter");
    }

    @Cacheable(value = "topPlayers", key = "'duels-' + #page + '-' + #size")
    public List<LeaderboardSnapshot> getTopDuelistsPaged(int page, int size) {
        validatePagination(page, size);
        return safeFetch(() -> {
            List<User> topPlayers = leaderboardRepository.findTopDuelists(PageRequest.of(page, size));
            List<LeaderboardSnapshot> snapshots = mapToSnapshots(topPlayers, page, size, "duelWins");
            logLeaderboardFetch("duel wins", snapshots.size());
            return snapshots;
        }, "top duelists");
    }

    @Cacheable(value = "topPlayers", key = "'combined-' + #page + '-' + #size")
    public List<LeaderboardSnapshot> getTopPlayersCombinedPaged(int page, int size) {
        validatePagination(page, size);
        return safeFetch(() -> {
            Map<String, Double> skillScores = analyticsService.getPlayerSkillScores();
            Map<String, Double> suspicionScores = antiCheatEngine.getCheatSuspicionScores();

            // Sort the in-memory skill-score map, filter suspicious IDs, then page.
            // Only the page's IDs are loaded from the DB via findAllById.
            // Fix 3: guard Long.parseLong — non-numeric player IDs (e.g. "anonymous") would
            // throw NumberFormatException and abort the entire leaderboard fetch.
            List<Long> pageIds = skillScores.entrySet().stream()
                .filter(e -> suspicionScores.getOrDefault(e.getKey(), 0.0) <= SUSPICION_THRESHOLD)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .skip((long) page * size)
                .limit(size)
                .flatMap(e -> {
                    try { return java.util.stream.Stream.of(Long.parseLong(e.getKey())); }
                    catch (NumberFormatException ex) {
                        logger.warn("Skipping non-numeric player ID in combined leaderboard: {}", e.getKey());
                        return java.util.stream.Stream.empty();
                    }
                })
                .collect(Collectors.toList());

            if (pageIds.isEmpty()) {
                return Collections.emptyList();
            }

            // findAllById returns rows in arbitrary order; re-sort to match skill-score ranking.
            Map<Long, User> userMap = userRepository.findAllById(pageIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
            List<User> topPlayers = pageIds.stream()
                .map(userMap::get)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
            List<LeaderboardSnapshot> snapshots = mapToSnapshots(topPlayers, page, size, "combined");
            logLeaderboardFetch("combined skill", snapshots.size());
            return snapshots;
        }, "top combined players");
    }

    @Cacheable(value = "topPlayers", key = "'recent-' + #page + '-' + #size")
    public List<LeaderboardSnapshot> getTopRecentPlayersPaged(int page, int size) {
        validatePagination(page, size);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RECENT_ACTIVITY_DAYS);
        return safeFetch(() -> {
            List<User> topPlayers = leaderboardRepository.findActiveUsersSince(cutoff, 
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "points")));
            List<LeaderboardSnapshot> snapshots = mapToSnapshots(topPlayers, page, size, "points");
            logLeaderboardFetch("recent activity", snapshots.size());
            return snapshots;
        }, "top recent players");
    }

    @Cacheable(value = "topPlayers", key = "'event-' + #eventId + '-' + #page + '-' + #size")
    public List<LeaderboardSnapshot> getTopEventPlayersPaged(String eventId, int page, int size) {
        validateEventId(eventId);
        validatePagination(page, size);
        return safeFetch(() -> {
            Map<String, Integer> eventScores = eventEngine.getPlayerEventScores().entrySet().stream()
                .filter(e -> e.getKey().endsWith("-" + eventId))
                .collect(Collectors.toMap(
                    e -> e.getKey().substring(0, e.getKey().indexOf("-" + eventId)),
                    Map.Entry::getValue
                ));
            // Fix 3 (cont.): same guard for event leaderboard player IDs.
            List<Long> eventPlayerIds = eventScores.keySet().stream()
                .flatMap(id -> {
                    try { return java.util.stream.Stream.of(Long.parseLong(id)); }
                    catch (NumberFormatException ex) {
                        logger.warn("Skipping non-numeric player ID in event leaderboard: {}", id);
                        return java.util.stream.Stream.empty();
                    }
                })
                .collect(Collectors.toList());
            List<User> topPlayers = userRepository.findAllById(eventPlayerIds).stream()
                .sorted((u1, u2) -> Integer.compare(
                    eventScores.getOrDefault(u2.getId().toString(), 0), 
                    eventScores.getOrDefault(u1.getId().toString(), 0)))
                .skip(page * size)
                .limit(size)
                .collect(Collectors.toList());
            List<LeaderboardSnapshot> snapshots = mapToSnapshots(topPlayers, page, size, "eventScore", eventScores);
            logLeaderboardFetch("event " + eventId, snapshots.size());
            return snapshots;
        }, "top event players for event " + eventId);
    }

    @Cacheable(value = "leaderboardSummary", key = "'summary-' + #page + '-' + #size")
    public List<UserSummary> getLeaderboardSummaryPaged(int page, int size) {
        validatePagination(page, size);
        return safeFetch(() -> {
            List<UserSummary> summary = leaderboardRepository.getCosmicLeaderboardSummary(PageRequest.of(page, size));
            logger.debug("Retrieved leaderboard summary for {} players", summary.size());
            return summary;
        }, "leaderboard summary");
    }

    @Transactional(readOnly = true)
    public Map<String, Double> getLeaderboardStats() {
        return safeFetch(() -> {
            Map<String, Double> stats = leaderboardRepository.getLeaderboardStatsSince(
                LocalDateTime.now().minusDays(RECENT_ACTIVITY_DAYS));
            logger.debug("Retrieved leaderboard stats: {}", stats);
            return stats;
        }, "leaderboard stats");
    }

    public LeaderboardSnapshot getPlayerRank(Long userId) {
        validateUserId(userId);
        return safeFetch(() -> {
            Optional<User> userOpt = userRepository.findById(userId);
            if (!userOpt.isPresent()) {
                logger.warn("Player {} not found for ranking", userId);
                return null;
            }
            User user = userOpt.get();
            // Rank = number of users with strictly more points + 1.
            // A single COUNT aggregate avoids loading all rows.
            long rank = userRepository.countByPointsGreaterThan(user.getPoints()) + 1;
            LeaderboardSnapshot snapshot = new LeaderboardSnapshot(
                user.getUsername(), user.getPoints(), (int) rank, getTier(user.getPoints()),
                user.getCosmicDrip(), user.getHypeMeter(), user.getDuelWins(),
                scoreDeltas.getOrDefault(userId.toString(), 0));
            logger.debug("Retrieved rank {} for player {}", rank, userId);
            return snapshot;
        }, "player rank for " + userId);
    }

    @Cacheable(value = "publicLeaderboard", key = "'public-' + #limit")
    public List<LeaderboardSnapshot> getPublicLeaderboard(int limit) {
        validateLimit(limit);
        return safeFetch(() -> {
            List<User> topPlayers = leaderboardRepository.findTopUsersByPoints(PageRequest.of(0, limit));
            List<LeaderboardSnapshot> snapshots = mapToSnapshots(topPlayers, 0, limit, "points");
            logLeaderboardFetch("public", snapshots.size());
            return snapshots;
        }, "public leaderboard");
    }

    private <T> T safeFetch(Supplier<T> action, String context) {
        try {
            T result = action.get();
            return result;
        } catch (Exception e) {
            logger.error("Failed to retrieve {}: {}", context, e.getMessage(), e);
            throw new RuntimeException(context + " retrieval failed", e);
        }
    }

    private void safeExecute(Runnable action, String context) {
        try {
            action.run();
        } catch (Exception e) {
            logger.error("Failed to execute {}: {}", context, e.getMessage(), e);
            throw new RuntimeException(context + " execution failed", e);
        }
    }

    private List<LeaderboardSnapshot> mapToSnapshots(List<User> users, int page, int size, String sortBy) {
        return mapToSnapshots(users, page, size, sortBy, Collections.emptyMap());
    }

    private List<LeaderboardSnapshot> mapToSnapshots(List<User> users, int page, int size, String sortBy, 
                                                    Map<String, Integer> eventScores) {
        return users.stream()
            .map(u -> {
                String playerId = u.getId().toString();
                int rank = page * size + users.indexOf(u) + 1;
                int sortValue;
                switch (sortBy) {
                    case "cosmicDrip": sortValue = u.getCosmicDrip(); break;
                    case "hypeMeter": sortValue = u.getHypeMeter(); break;
                    case "duelWins": sortValue = u.getDuelWins(); break;
                    case "eventScore": sortValue = eventScores.getOrDefault(playerId, 0); break;
                    default: sortValue = u.getPoints(); break;
                }
                return new LeaderboardSnapshot(
                    u.getUsername(), sortValue, rank, getTier(u.getPoints()),
                    u.getCosmicDrip(), u.getHypeMeter(), u.getDuelWins(),
                    scoreDeltas.getOrDefault(playerId, 0));
            })
            .collect(Collectors.toList());
    }

    private String getTier(int points) {
        if (points >= TIER_COSMIC_THRESHOLD) return "Cosmic";
        if (points >= TIER_GOLD_THRESHOLD) return "Gold";
        if (points >= TIER_SILVER_THRESHOLD) return "Silver";
        if (points >= TIER_BRONZE_THRESHOLD) return "Bronze";
        return "Unranked";
    }

    private void broadcastLeaderboardUpdate(User user, int delta) {
        // Fix 2: broadcastLeaderboardUpdate() does not exist on MultiplayerBroadcaster — compile error.
        // Use broadcastEvent() which is the correct existing API.
        String playerId = user.getId().toString();
        String message = String.format(
            "{\"playerId\":\"%s\",\"points\":%d,\"cosmicDrip\":%d,\"hypeMeter\":%d,\"duelWins\":%d,\"delta\":%d}",
            playerId, user.getPoints(), user.getCosmicDrip(), user.getHypeMeter(), user.getDuelWins(), delta);
        multiplayerBroadcaster.broadcastEvent("leaderboardUpdate", message, null);
        logger.trace("Broadcast leaderboard update for player {} with delta {}", playerId, delta);
    }

    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            logger.error("Invalid userId: {}", userId);
            throw new IllegalArgumentException("User ID must be a positive number");
        }
    }

    private void validatePoints(int points) {
        if (points < 0) {
            logger.error("Invalid points value: {}", points);
            throw new IllegalArgumentException("Points cannot be negative");
        }
    }

    private void validatePagination(int page, int size) {
        if (page < 0) {
            logger.error("Invalid page: {}", page);
            throw new IllegalArgumentException("Page must be non-negative");
        }
        if (size <= 0 || size > MAX_TOP_LIMIT) {
            logger.warn("Invalid size: {}. Must be between 1 and {}", size, MAX_TOP_LIMIT);
            throw new IllegalArgumentException("Size must be between 1 and " + MAX_TOP_LIMIT);
        }
    }

    private void validateEventId(String eventId) {
        if (eventId == null || eventId.trim().isEmpty()) {
            logger.error("Invalid eventId: {}", eventId);
            throw new IllegalArgumentException("Event ID cannot be null or empty");
        }
    }

    private void logLeaderboardFetch(String type, int size) {
        logger.debug("Retrieved top {} players by {}", size, type);
    }

    public record LeaderboardSnapshot(
        String username, 
        int sortValue, 
        int rank, 
        String tier, 
        int cosmicDrip, 
        int hypeMeter, 
        int duelWins, 
        int pointsDelta
    ) {}
}
