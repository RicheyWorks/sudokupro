package com.xai.sudokupro.repository.leaderboard;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserSummary;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Cosmic leaderboard oracle of SudokuPro.
 * Ranks grid warriors by points, duels, drip, and hype with galactic-tier JPA precision.
 */
public interface LeaderboardRepository extends JpaRepository<User, Long> {

    @EntityGraph(attributePaths = {"matchHistory"})
    @Query("SELECT u FROM User u ORDER BY u.points DESC")
    @Cacheable(value = "topUsersByPoints", key = "#pageable.pageNumber")
    List<User> findTopUsersByPoints(Pageable pageable);

    @Query("SELECT u FROM User u ORDER BY u.duelWins DESC")
    @Cacheable(value = "topDuelists", key = "#pageable.pageNumber")
    List<User> findTopDuelists(Pageable pageable);

    @Query("SELECT u FROM User u ORDER BY u.level DESC, u.points DESC")
    @Cacheable(value = "topUsersByLevel", key = "#pageable.pageNumber")
    List<User> findTopUsersByLevel(Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.duelWins > :winsThreshold ORDER BY u.duelWins DESC")
    @Cacheable(value = "duelMasters", key = "#winsThreshold")
    List<User> findDuelMasters(@Param("winsThreshold") int winsThreshold);

    @Query("SELECT u FROM User u WHERE u.duelWins > 0 AND u.points < :pointsLimit ORDER BY u.duelWins DESC")
    @Cacheable(value = "underdogDuelists", key = "#pointsLimit")
    List<User> findUnderdogDuelists(@Param("pointsLimit") int pointsLimit);

    @Query("SELECT u FROM User u WHERE u.lastLogin BETWEEN :start AND :end ORDER BY u.points DESC")
    @Cacheable(value = "activeLeaders", key = "#start + '-' + #end")
    List<User> findActiveLeadersInPeriod(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT u FROM User u WHERE u.duelWins >= :wins AND u.streak >= :streak ORDER BY u.duelWins DESC")
    @Cacheable(value = "duelStreakMasters", key = "#wins + '-' + #streak")
    List<User> findDuelStreakMasters(@Param("wins") int wins, @Param("streak") int streak);

    @Query("SELECT u FROM User u WHERE u.points < :points AND u.duelWins > :wins ORDER BY u.duelWins DESC")
    @Cacheable(value = "lowPointsHighWins", key = "#points + '-' + #wins")
    List<User> findLowPointsHighWins(@Param("points") int points, @Param("wins") int wins);

    @EntityGraph(attributePaths = {"powerUps"})
    @Query("SELECT u FROM User u WHERE u.duelWins >= :minWins AND u.gems >= :minGems ORDER BY u.duelWins DESC")
    @Cacheable(value = "richDuelists", key = "#minWins + '-' + #minGems")
    List<User> findRichDuelists(@Param("minWins") int minWins, @Param("minGems") int minGems);

    @Query("SELECT u FROM User u WHERE u.points > :points AND u.level < :level ORDER BY u.points DESC")
    @Cacheable(value = "overachievers", key = "#points + '-' + #level")
    List<User> findOverachievers(@Param("points") int points, @Param("level") int level);

    @Query("SELECT u FROM User u WHERE u.duelWins = 0 AND u.level >= :level ORDER BY u.level DESC")
    @Cacheable(value = "pvpShyVeterans", key = "#level")
    List<User> findPvPShyVeterans(@Param("level") int level);

    @EntityGraph(attributePaths = {"achievements"})
    @Query("SELECT u FROM User u WHERE u.cosmicDrip >= :minDrip ORDER BY u.cosmicDrip DESC, u.hypeMeter DESC")
    @Cacheable(value = "topCosmicDrippers", key = "#minDrip + '-' + #pageable.pageNumber")
    List<User> findTopCosmicDrippers(@Param("minDrip") int minDrip, Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.hypeMeter >= :minHype ORDER BY u.hypeMeter DESC, u.points DESC")
    @Cacheable(value = "hypeLegends", key = "#minHype + '-' + #pageable.pageNumber")
    List<User> findHypeLegends(@Param("minHype") int minHype, Pageable pageable);

    @EntityGraph(attributePaths = {"friends"})
    @Query("SELECT u FROM User u WHERE SIZE(u.friends) >= :minFriends AND u.fanCount >= :minFans " +
           "ORDER BY (u.hypeMeter + u.cosmicDrip) DESC")
    @Cacheable(value = "socialCosmicIcons", key = "#minFriends + '-' + #minFans + '-' + #pageable.pageNumber")
    List<User> findSocialCosmicIcons(@Param("minFriends") int minFriends, @Param("minFans") int minFans, Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.streak >= :minStreak AND u.lastLogin > :since " +
           "ORDER BY u.streak DESC, u.cosmicDrip DESC")
    @Cacheable(value = "activeStreakCosmonauts", key = "#minStreak + '-' + #since + '-' + #pageable.pageNumber")
    List<User> findActiveStreakCosmonauts(@Param("minStreak") int minStreak, @Param("since") LocalDateTime since, Pageable pageable);

    @Transactional(readOnly = true)
    @Query(value = "SELECT u.username AS username, u.level AS level, u.points AS points, u.duel_wins AS duelWins, " +
           "u.cosmic_drip AS cosmicDrip, u.hype_meter AS hypeMeter " +
           "FROM users u " +
           "ORDER BY (u.points * 0.5 + u.duel_wins * 1.5 + u.cosmic_drip * 0.8 + u.hype_meter * 0.7) DESC",
           nativeQuery = true)
    @Cacheable(value = "cosmicLeaderboardSummary", key = "#pageable.pageNumber")
    List<UserSummary> getCosmicLeaderboardSummary(Pageable pageable);

    @EntityGraph(attributePaths = {"achievements"})
    @Query("SELECT u FROM User u WHERE SIZE(u.achievements) >= :minAchievements " +
           "ORDER BY u.points DESC, u.cosmicDrip DESC")
    @Cacheable(value = "achievementHunters", key = "#minAchievements + '-' + #pageable.pageNumber")
    List<User> findAchievementHunters(@Param("minAchievements") int minAchievements, Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.xp >= :minXp AND u.level >= :minLevel " +
           "ORDER BY (u.points + u.xp) DESC")
    @Cacheable(value = "xpPointLeaders", key = "#minXp + '-' + #minLevel + '-' + #pageable.pageNumber")
    List<User> findXpPointLeaders(@Param("minXp") int minXp, @Param("minLevel") int minLevel, Pageable pageable);

    @Transactional(readOnly = true)
    @Query("SELECT AVG(u.points) as avgPoints, AVG(u.duelWins) as avgDuelWins, AVG(u.cosmicDrip) as avgCosmicDrip " +
           "FROM User u WHERE u.lastLogin > :since")
    Map<String, Double> getLeaderboardStatsSince(@Param("since") LocalDateTime since);
}
