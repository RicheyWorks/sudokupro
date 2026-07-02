package com.xai.sudokupro.repository.retention;

import com.xai.sudokupro.model.User;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Cosmic retention oracle of SudokuPro.
 * Tracks newbies, cheaters, churn risks, and drip lords with galactic JPA precision.
 *
 * <p>Marked {@code @NoRepositoryBean} so Spring Data JPA does not instantiate it as a
 * standalone bean. It is composed into {@link com.xai.sudokupro.repository.UserRepository}
 * which provides the single concrete implementation.
 */
@NoRepositoryBean
public interface RetentionStatsRepository extends JpaRepository<User, Long> {

    @EntityGraph(attributePaths = {"matchHistory"})
    @Query("SELECT u FROM User u WHERE u.level < :levelCap ORDER BY u.level ASC")
    @Cacheable(value = "newbies", key = "#levelCap + '-' + #pageable.pageNumber")
    List<User> findNewbies(@Param("levelCap") int levelCap, Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.points > :pointsThreshold AND u.lastLogin > :since ORDER BY u.points DESC")
    @Cacheable(value = "potentialCheatersByPoints", key = "#pointsThreshold + '-' + #since")
    List<User> findPotentialCheatersByPoints(@Param("pointsThreshold") int pointsThreshold,
                                             @Param("since") LocalDateTime since);

    @Query("SELECT u FROM User u WHERE u.lastLogin < :cutoff AND u.level >= :minLevel ORDER BY u.lastLogin ASC")
    @Cacheable(value = "churnRisks", key = "#cutoff + '-' + #minLevel + '-' + #pageable.pageNumber")
    List<User> findChurnRisks(@Param("cutoff") LocalDateTime cutoff,
                             @Param("minLevel") int minLevel,
                             Pageable pageable);

    @EntityGraph(attributePaths = {"achievements"})
    @Query("SELECT u FROM User u WHERE u.lastLogin > :since AND u.cosmicDrip >= :minDrip ORDER BY u.cosmicDrip DESC, u.hypeMeter DESC")
    @Cacheable(value = "activeDripLords", key = "#since + '-' + #minDrip + '-' + #pageable.pageNumber")
    List<User> findActiveDripLords(@Param("since") LocalDateTime since,
                                  @Param("minDrip") int minDrip,
                                  Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.lastLogin BETWEEN :start AND :end AND u.duelWins >= :minWins " +
           "ORDER BY u.duelWins DESC, u.points DESC")
    @Cacheable(value = "engagedDuelists", key = "#start + '-' + #end + '-' + #minWins + '-' + #pageable.pageNumber")
    List<User> findEngagedDuelistsInPeriod(@Param("start") LocalDateTime start,
                                           @Param("end") LocalDateTime end,
                                           @Param("minWins") int minWins,
                                           Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.lastLogin > :since AND u.streak >= :minStreak AND u.level < :levelCap " +
           "ORDER BY u.streak DESC, u.cosmicDrip DESC")
    @Cacheable(value = "streakingNewbies", key = "#since + '-' + #minStreak + '-' + #levelCap + '-' + #pageable.pageNumber")
    List<User> findStreakingNewbies(@Param("since") LocalDateTime since,
                                   @Param("minStreak") int minStreak,
                                   @Param("levelCap") int levelCap,
                                   Pageable pageable);

    @Transactional(readOnly = true)
    @Query(value = "SELECT u.* FROM users u " +
           "WHERE u.last_login > :since " +
           "AND EXISTS (SELECT 1 FROM match_history mh WHERE mh.user_id = u.id AND mh.timestamp > :since) " +
           "ORDER BY u.hype_meter DESC, u.cosmic_drip DESC",
           nativeQuery = true)
    @Cacheable(value = "activeMatchPlayers", key = "#since + '-' + #pageable.pageNumber")
    List<User> findActiveMatchPlayers(@Param("since") LocalDateTime since, Pageable pageable);

    // New Queries

    @EntityGraph(attributePaths = {"powerUps"})
    @Query("SELECT u FROM User u WHERE u.lastLogin > :since AND u.powerUps[:powerUp] >= :minCount " +
           "ORDER BY u.powerUps[:powerUp] DESC, u.hypeMeter DESC")
    @Cacheable(value = "powerUpRetainees", key = "#since + '-' + #powerUp + '-' + #minCount + '-' + #pageable.pageNumber")
    List<User> findPowerUpRetainees(@Param("since") LocalDateTime since,
                                   @Param("powerUp") String powerUp,
                                   @Param("minCount") int minCount,
                                   Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.lastLogin > :since AND SIZE(u.achievements) >= :minAchievements " +
           "ORDER BY u.points DESC, u.cosmicDrip DESC")
    @Cacheable(value = "achievementRetainees", key = "#since + '-' + #minAchievements + '-' + #pageable.pageNumber")
    List<User> findAchievementRetainees(@Param("since") LocalDateTime since,
                                        @Param("minAchievements") int minAchievements,
                                        Pageable pageable);

    @Transactional(readOnly = true)
    @Query("SELECT COUNT(u) as activeUsers, AVG(u.streak) as avgStreak, AVG(u.cosmicDrip) as avgDrip " +
           "FROM User u WHERE u.lastLogin > :since")
    Map<String, Number> getRetentionStatsSince(@Param("since") LocalDateTime since);

    @Query("SELECT u FROM User u WHERE u.lastLogin > :since AND u.fanCount >= :minFans " +
           "ORDER BY u.hypeMeter DESC, u.cosmicDrip DESC")
    @Cacheable(value = "fanEngagedPlayers", key = "#since + '-' + #minFans + '-' + #pageable.pageNumber")
    List<User> findFanEngagedPlayers(@Param("since") LocalDateTime since,
                                    @Param("minFans") int minFans,
                                    Pageable pageable);
}
