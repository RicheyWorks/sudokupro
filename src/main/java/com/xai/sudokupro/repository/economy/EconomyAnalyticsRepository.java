package com.xai.sudokupro.repository.economy;

import com.xai.sudokupro.model.User;
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
 * Cosmic ledger of SudokuPro's economy.
 * Analyzes gem hoards, triple threats, and galactic drip with divine JPA precision.
 */
public interface EconomyAnalyticsRepository extends JpaRepository<User, Long> {

    @Query("SELECT u FROM User u WHERE u.gems < :threshold ORDER BY u.gems ASC")
    @Cacheable(value = "lowGemUsers", key = "#threshold")
    List<User> findLowGemUsers(@Param("threshold") int threshold);

    @EntityGraph(attributePaths = {"achievements", "powerUps"})
    @Query("SELECT u FROM User u WHERE u.level >= :level ORDER BY u.gems DESC")
    @Cacheable(value = "topGemHoarders", key = "#level + '-' + #pageable.pageNumber")
    List<User> findTopGemHoardersByLevel(@Param("level") int level, Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.gems >= :minGems AND u.points >= :minPoints AND u.duelWins >= :minWins")
    @Cacheable(value = "tripleThreats", key = "#minGems + '-' + #minPoints + '-' + #minWins")
    List<User> findTripleThreats(@Param("minGems") int minGems,
                                @Param("minPoints") int minPoints,
                                @Param("minWins") int minWins);

    @Query(value = "SELECT * FROM users ORDER BY points DESC", nativeQuery = true)
    @Cacheable(value = "topUsers", key = "#pageable.pageNumber")
    List<User> findTopUsersNative(Pageable pageable);

    @EntityGraph(attributePaths = {"matchHistory"})
    @Query("SELECT u FROM User u WHERE u.cosmicDrip >= :minDrip ORDER BY u.cosmicDrip DESC, u.points DESC")
    @Cacheable(value = "cosmicDrippers", key = "#minDrip + '-' + #pageable.pageNumber")
    List<User> findCosmicDrippers(@Param("minDrip") int minDrip, Pageable pageable);

    @Query("SELECT u FROM User u WHERE SIZE(u.friends) >= :minFriends ORDER BY u.hypeMeter DESC")
    @Cacheable(value = "socialInfluencers", key = "#minFriends + '-' + #pageable.pageNumber")
    List<User> findSocialInfluencers(@Param("minFriends") int minFriends, Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.level BETWEEN :minLevel AND :maxLevel AND u.gems >= :minGems " +
           "ORDER BY (u.duelWins * 1.5 + u.points * 0.5 + u.cosmicDrip) DESC")
    @Cacheable(value = "cosmicElite", key = "#minLevel + '-' + #maxLevel + '-' + #minGems + '-' + #pageable.pageNumber")
    List<User> findCosmicEliteByLevelRange(@Param("minLevel") int minLevel,
                                           @Param("maxLevel") int maxLevel,
                                           @Param("minGems") int minGems,
                                           Pageable pageable);

    @Query(value = "SELECT u.* FROM users u " +
           "JOIN (SELECT user_id, COUNT(*) as match_count, AVG(CASE WHEN won THEN 1.0 ELSE 0.0 END) as win_rate " +
           "FROM match_history GROUP BY user_id HAVING match_count >= :minMatches) mh " +
           "ON u.id = mh.user_id WHERE u.hype_meter >= :minHype ORDER BY mh.win_rate DESC, u.points DESC",
           nativeQuery = true)
    @Cacheable(value = "hypeMatchMasters", key = "#minMatches + '-' + #minHype + '-' + #pageable.pageNumber")
    List<User> findHypeMatchMasters(@Param("minMatches") int minMatches,
                                   @Param("minHype") int minHype,
                                   Pageable pageable);

    // New Queries

    @EntityGraph(attributePaths = {"powerUps"})
    @Query("SELECT u FROM User u WHERE u.powerUps[:powerUp] >= :minCount ORDER BY u.powerUps[:powerUp] DESC")
    @Cacheable(value = "powerUpUsers", key = "#powerUp + '-' + #minCount + '-' + #pageable.pageNumber")
    List<User> findPowerUpUsers(@Param("powerUp") String powerUp,
                               @Param("minCount") int minCount,
                               Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.lastLogin >= :since ORDER BY u.points DESC, u.cosmicDrip DESC")
    @Cacheable(value = "activeUsers", key = "#since + '-' + #pageable.pageNumber")
    List<User> findActiveUsersSince(@Param("since") LocalDateTime since, Pageable pageable);

    @Transactional(readOnly = true)
    @Query("SELECT AVG(u.gems) as avgGems, AVG(u.points) as avgPoints, AVG(u.cosmicDrip) as avgDrip " +
           "FROM User u WHERE u.level >= :minLevel")
    Map<String, Double> getEconomyStatsByLevel(@Param("minLevel") int minLevel);

    @Query("SELECT u FROM User u WHERE u.xp >= :minXp AND u.level <= :maxLevel " +
           "ORDER BY (u.xp / u.level) DESC")
    @Cacheable(value = "xpEfficiency", key = "#minXp + '-' + #maxLevel + '-' + #pageable.pageNumber")
    List<User> findXpEfficientUsers(@Param("minXp") int minXp,
                                   @Param("maxLevel") int maxLevel,
                                   Pageable pageable);
}
