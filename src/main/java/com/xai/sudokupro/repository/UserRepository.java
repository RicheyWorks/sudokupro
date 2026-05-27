package com.xai.sudokupro.repository;
import java.util.Map;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.economy.EconomyAnalyticsRepository;
import com.xai.sudokupro.repository.leaderboard.LeaderboardRepository;
import com.xai.sudokupro.repository.retention.RetentionStatsRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Galactic nexus of SudokuPro's user data.
 * Masters player stats, cosmic drip, and retention with divine JPA precision—fueling leaderboards, duels, and hype.
 */
public interface UserRepository extends JpaRepository<User, Long>,
        LeaderboardRepository,
        EconomyAnalyticsRepository,
        RetentionStatsRepository {

    @Cacheable(value = "userByUsername", key = "#username")
    Optional<User> findByUsername(String username);

    @Query("SELECT u FROM User u WHERE u.streak >= :streakThreshold")
    @Cacheable(value = "usersByStreak", key = "#streakThreshold")
    List<User> findByStreakGreaterThanEqual(@Param("streakThreshold") int streakThreshold);

    @Query("SELECT u FROM User u WHERE u.lastLogin < :cutoff")
    @Cacheable(value = "usersByLastLoginBefore", key = "#cutoff")
    List<User> findByLastLoginBefore(@Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT u FROM User u WHERE u.gems >= :gemThreshold")
    @Cacheable(value = "usersByGems", key = "#gemThreshold")
    List<User> findByGemsGreaterThanEqual(@Param("gemThreshold") int gemThreshold);

    @Query("SELECT u FROM User u WHERE u.level BETWEEN :minLevel AND :maxLevel")
    @Cacheable(value = "usersByLevelRange", key = "#minLevel + '-' + #maxLevel")
    List<User> findByLevelBetween(@Param("minLevel") int minLevel, @Param("maxLevel") int maxLevel);

    @Transactional(readOnly = true)
    @Query("SELECT COUNT(u) FROM User u WHERE u.lastLogin >= :cutoff")
    @Cacheable(value = "activeUserCount", key = "#cutoff")
    long countActiveUsersSince(@Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT u FROM User u WHERE u.themePreference = :themePreference")
    @Cacheable(value = "usersByTheme", key = "#themePreference")
    List<User> findByThemePreference(@Param("themePreference") String themePreference);

    @Query("SELECT u FROM User u WHERE u.points >= :minPoints AND u.points <= :maxPoints")
    @Cacheable(value = "usersByPointsRange", key = "#minPoints + '-' + #maxPoints")
    List<User> findByPointsRange(@Param("minPoints") int minPoints, @Param("maxPoints") int maxPoints);

    @Transactional(readOnly = true)
    @Query("SELECT COUNT(u) FROM User u WHERE u.gems > 0")
    @Cacheable(value = "usersWithGemsCount")
    long countUsersWithGems();

    @EntityGraph(attributePaths = {"achievements"})
    @Query("SELECT u FROM User u WHERE u.level >= :level AND u.streak >= :streak")
    @Cacheable(value = "elitePlayers", key = "#level + '-' + #streak")
    List<User> findElitePlayers(@Param("level") int level, @Param("streak") int streak);

    @Transactional(readOnly = true)
    @Query("SELECT AVG(u.points) FROM User u")
    @Cacheable(value = "averagePoints")
    double getAveragePoints();

    @Query("SELECT u FROM User u WHERE u.streak = :exactStreak")
    @Cacheable(value = "usersByExactStreak", key = "#exactStreak")
    List<User> findByExactStreak(@Param("exactStreak") int exactStreak);

    @Query("SELECT u FROM User u WHERE u.lastLogin IS NULL")
    @Cacheable(value = "neverLoggedInUsers")
    List<User> findNeverLoggedIn();

    @Transactional(readOnly = true)
    @Query("SELECT COUNT(u) FROM User u WHERE u.level > :levelThreshold")
    @Cacheable(value = "highLevelUserCount", key = "#levelThreshold")
    long countHighLevelUsers(@Param("levelThreshold") int levelThreshold);

    @Query("SELECT u FROM User u WHERE u.points > u.gems * :multiplier ORDER BY u.points DESC")
    @Cacheable(value = "pointHeavyUsers", key = "#multiplier")
    List<User> findPointHeavyUsers(@Param("multiplier") int multiplier);

    @Transactional(readOnly = true)
    @Query("SELECT SUM(u.duelWins) FROM User u")
    @Cacheable(value = "totalDuelWins")
    long getTotalDuelWins();

    /** Sum of all gems across every user — replaces findAll() + stream sum in schedulers. */
    @Transactional(readOnly = true)
    @Query("SELECT COALESCE(SUM(u.gems), 0) FROM User u")
    long getTotalGems();

    /**
     * Count users whose points fall within [min, max).
     * Used by MetricsScheduler to compute tier gauge values without loading all rows.
     */
    @Transactional(readOnly = true)
    @Query("SELECT COUNT(u) FROM User u WHERE u.points >= :min AND u.points < :max")
    long countUsersInPointsRange(@Param("min") int min, @Param("max") int max);

    /**
     * Count users whose points are at least {@code min}.
     * Used for the unbounded top tier (Cosmic).
     */
    @Transactional(readOnly = true)
    @Query("SELECT COUNT(u) FROM User u WHERE u.points >= :min")
    long countUsersWithMinPoints(@Param("min") int min);

    /**
     * Count users whose points strictly exceed {@code points}.
     * Used by LeaderboardService to compute a single player's rank without loading all rows.
     */
    @Transactional(readOnly = true)
    long countByPointsGreaterThan(int points);

    @Transactional(readOnly = true)
    @Query("SELECT AVG(u.gems) FROM User u WHERE u.lastLogin >= :cutoff")
    @Cacheable(value = "avgGemsActiveUsers", key = "#cutoff")
    double getAverageGemsActiveUsers(@Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT u FROM User u WHERE u.streak > 0 AND u.lastLogin < :cutoff ORDER BY u.streak DESC")
    @Cacheable(value = "streakAtRisk", key = "#cutoff")
    List<User> findStreakAtRisk(@Param("cutoff") LocalDateTime cutoff);

    @Transactional(readOnly = true)
    @Query("SELECT COUNT(u) FROM User u WHERE u.points = 0 AND u.lastLogin >= :cutoff")
    @Cacheable(value = "inactiveNewbiesCount", key = "#cutoff")
    long countInactiveNewbies(@Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT u FROM User u WHERE u.themePreference != 'default' AND u.level >= :level")
    @Cacheable(value = "customThemeVeterans", key = "#level")
    List<User> findCustomThemeVeterans(@Param("level") int level);

    @Transactional(readOnly = true)
    @Query("SELECT AVG(u.level) FROM User u WHERE u.duelWins > 0")
    @Cacheable(value = "avgLevelDuelists")
    double getAverageLevelDuelists();

    @Query("SELECT u FROM User u WHERE u.lastLogin >= :start AND u.lastLogin <= :end AND u.streak >= :streak")
    @Cacheable(value = "activeStreakers", key = "#start + '-' + #end + '-' + #streak")
    List<User> findActiveStreakersInPeriod(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, 
                                           @Param("streak") int streak);

    @Transactional(readOnly = true)
    @Query("SELECT COUNT(u) FROM User u WHERE u.gems >= :threshold AND u.lastLogin < :cutoff")
    @Cacheable(value = "richInactiveUsersCount", key = "#threshold + '-' + #cutoff")
    long countRichInactiveUsers(@Param("threshold") int threshold, @Param("cutoff") LocalDateTime cutoff);

    @Transactional(readOnly = true)
    @Query("SELECT SUM(u.points) FROM User u WHERE u.themePreference = :theme")
    @Cacheable(value = "totalPointsByTheme", key = "#theme")
    long getTotalPointsByTheme(@Param("theme") String theme);

    @Transactional(readOnly = true)
    @Query("SELECT COUNT(u) FROM User u WHERE u.streak > 0 AND u.duelWins > 0 AND u.gems > 0")
    @Cacheable(value = "multiActiveUsersCount")
    long countMultiActiveUsers();

    @EntityGraph(attributePaths = {"matchHistory"})
    @Query("SELECT u FROM User u WHERE u.cosmicDrip >= :minDrip AND u.lastLogin >= :since ORDER BY u.cosmicDrip DESC, u.hypeMeter DESC")
    @Cacheable(value = "activeCosmicDrippers", key = "#minDrip + '-' + #since + '-' + #pageable.pageNumber")
    List<User> findActiveCosmicDrippers(@Param("minDrip") int minDrip, @Param("since") LocalDateTime since, Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.hypeMeter >= :minHype AND u.fanCount >= :minFans ORDER BY u.hypeMeter DESC, u.cosmicDrip DESC")
    @Cacheable(value = "hypeFanIcons", key = "#minHype + '-' + #minFans + '-' + #pageable.pageNumber")
    List<User> findHypeFanIcons(@Param("minHype") int minHype, @Param("minFans") int minFans, Pageable pageable);

    @Transactional(readOnly = true)
    @Query("SELECT AVG(u.cosmicDrip) FROM User u WHERE u.lastLogin >= :since")
    @Cacheable(value = "avgCosmicDripActiveUsers", key = "#since")
    double getAverageCosmicDripActiveUsers(@Param("since") LocalDateTime since);

    @EntityGraph(attributePaths = {"matchHistory"})
    @Query("SELECT u FROM User u WHERE u.lastLogin BETWEEN :start AND :end AND SIZE(u.matchHistory) >= :minMatches " +
           "ORDER BY u.duelWins DESC, u.cosmicDrip DESC")
    @Cacheable(value = "matchHeavyPlayers", key = "#start + '-' + #end + '-' + #minMatches + '-' + #pageable.pageNumber")
    List<User> findMatchHeavyPlayersInPeriod(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, 
                                             @Param("minMatches") int minMatches, Pageable pageable);

    @Transactional(readOnly = true)
    @Query(value = "SELECT u.* FROM users u " +
           "WHERE u.last_login > :since AND EXISTS (SELECT 1 FROM match_history mh WHERE mh.user_id = u.id AND mh.won = true) " +
           "AND u.points BETWEEN :minPoints AND :maxPoints " +
           "ORDER BY u.hype_meter DESC, u.cosmic_drip DESC", nativeQuery = true)
    @Cacheable(value = "activeDuelWinnersByPoints", key = "#since + '-' + #minPoints + '-' + #maxPoints + '-' + #pageable.pageNumber")
    List<User> findActiveDuelWinnersByPoints(@Param("since") LocalDateTime since, @Param("minPoints") int minPoints, 
                                             @Param("maxPoints") int maxPoints, Pageable pageable);

    @Transactional(readOnly = true)
    @Query("SELECT COUNT(u) FROM User u WHERE u.powerUps['hint'] > 0 OR u.powerUps['undo'] > 0 OR u.powerUps['timeBoost'] > 0 OR u.powerUps['cosmicReveal'] > 0")
    @Cacheable(value = "powerUpUsersCount")
    long countPowerUpUsers();

    // New Queries
    @EntityGraph(attributePaths = {"friends"})
    @Query("SELECT u FROM User u WHERE SIZE(u.friends) >= :minFriends AND u.lastLogin > :since " +
           "ORDER BY u.hypeMeter DESC, u.cosmicDrip DESC")
    @Cacheable(value = "activeSocialPlayers", key = "#minFriends + '-' + #since + '-' + #pageable.pageNumber")
    List<User> findActiveSocialPlayers(@Param("minFriends") int minFriends, @Param("since") LocalDateTime since, Pageable pageable);

    @Transactional(readOnly = true)
    @Query("SELECT AVG(u.xp) as avgXp, AVG(u.level) as avgLevel, COUNT(u) as activeCount " +
           "FROM User u WHERE u.lastLogin > :since AND u.streak > 0")
    @Cacheable(value = "streakRetentionStats", key = "#since")
    Map<String, Number> getStreakRetentionStats(@Param("since") LocalDateTime since);

    @Query("SELECT u FROM User u WHERE u.powerUps[:powerUp] >= :minCount AND u.lastLogin > :since " +
           "ORDER BY u.powerUps[:powerUp] DESC, u.points DESC")
    @Cacheable(value = "powerUpActiveUsers", key = "#powerUp + '-' + #minCount + '-' + #since + '-' + #pageable.pageNumber")
    List<User> findPowerUpActiveUsers(@Param("powerUp") String powerUp, @Param("minCount") int minCount, 
                                      @Param("since") LocalDateTime since, Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.cosmicDrip > :minDrip AND u.points > :minPoints AND u.lastLogin < :cutoff " +
           "ORDER BY u.cosmicDrip DESC, u.points DESC")
    @Cacheable(value = "dripPointInactiveUsers", key = "#minDrip + '-' + #minPoints + '-' + #cutoff + '-' + #pageable.pageNumber")
    List<User> findDripPointInactiveUsers(@Param("minDrip") int minDrip, @Param("minPoints") int minPoints, 
                                          @Param("cutoff") LocalDateTime cutoff, Pageable pageable);
}
