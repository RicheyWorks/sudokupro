package com.xai.sudokupro.repository.leaderboard;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserSummary;
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
 * Cosmic leaderboard oracle of SudokuPro.
 * Ranks grid warriors by points, duels, drip, and hype with galactic-tier JPA precision.
 *
 * <p>Marked {@code @NoRepositoryBean} so Spring Data JPA does not instantiate it as a
 * standalone bean. It is composed into {@link com.xai.sudokupro.repository.UserRepository}
 * which provides the single concrete implementation.
 */
@NoRepositoryBean
public interface LeaderboardRepository extends JpaRepository<User, Long> {

    // Every ordering below carries `u.id ASC` as a final tie-break. Without it, rows with
    // an equal sort key come back in whatever order Postgres produces, which is not stable
    // between executions — so a paginated leaderboard could show the same player on two
    // pages and skip another entirely, and consecutive refreshes reshuffled tied ranks.
    //
    // Caching is now genuinely on (see CacheConfig), so the annotations below are live and
    // were audited before being armed. Two things changed here:
    //   * Keys keyed on `#pageable.pageNumber` alone made a size-10 and a size-100 read of
    //     page 0 collide, so the second caller got the first caller's page. They now key on
    //     `#pageable`, whose equals/hashCode cover page, size and sort.
    //   * The queries LeaderboardService already caches at the service layer lost their
    //     repository-level @Cacheable. Caching both layers made updateScore's @CacheEvict
    //     cosmetic: it evicts the service cache, and the stale rows come back up from here.
    // findSocialCosmicIcons and findAchievementHunters still pair @EntityGraph with a
    // Pageable and so still paginate in memory. They have no production caller anywhere in
    // the codebase, so they are left alone rather than rewritten speculatively.

    /**
     * Top players by points, one page, with {@code matchHistory} fetched.
     *
     * <p>Bug fix — a page that was really a full table read. This was a single
     * {@code @EntityGraph} + {@code Pageable} method. Hibernate cannot push
     * {@code firstResult/maxResults} into SQL when the query fetch-joins a collection (the
     * LIMIT would cut the join rows, not the roots), so it silently ran the query unlimited,
     * materialised every matching {@code User} plus its collection, and sliced the list in
     * Java — logging {@code HHH90003004: firstResult/maxResults specified with collection
     * fetch; applying in memory}. Measured on H2 with a 24-row table, asking for the top 3
     * loaded 24 {@code User} entities; the ratio is the table size, so in production the
     * hottest leaderboard endpoint pulled the entire user table into heap on the request
     * thread for every cache miss, and the cost grew linearly with signups while the page
     * stayed at 10 rows.
     *
     * <p>The standard remedy, applied here: query 1 pages the ids (no fetch join, so the
     * database does the LIMIT), query 2 loads exactly those roots with the fetch graph
     * (no pagination, so nothing to apply in memory). Two round trips, bounded work.
     * The ordering is repeated in query 2 because {@code IN} does not preserve it.
     */
    default List<User> findTopUsersByPoints(Pageable pageable) {
        List<Long> ids = findTopUserIdsByPoints(pageable);
        return ids.isEmpty() ? List.of() : findUsersByIdsOrderedByPoints(ids);
    }

    @Query("SELECT u.id FROM User u ORDER BY u.points DESC, u.id ASC")
    List<Long> findTopUserIdsByPoints(Pageable pageable);


    @EntityGraph(attributePaths = {"matchHistory"})
    // Not cached at the repository layer: LeaderboardService already caches this
    // result, and only the service-level cache is named by updateScore /
    // refreshLeaderboard's @CacheEvict. Caching both layers made that eviction
    // cosmetic — it would fire, and the stale rows would come straight back up.
    @Query("SELECT u FROM User u WHERE u.id IN :ids ORDER BY u.points DESC, u.id ASC")
    List<User> findUsersByIdsOrderedByPoints(@Param("ids") List<Long> ids);

    @Query("SELECT u FROM User u ORDER BY u.duelWins DESC, u.id ASC")
    // Not cached here: LeaderboardService.getTopDuelistsPaged already caches this result, and only the service-level cache is named by
    // updateScore/refreshLeaderboard's @CacheEvict. Caching both layers made that
    // eviction cosmetic — it would fire, and the stale rows would come straight back
    // up from here.
    List<User> findTopDuelists(Pageable pageable);

    @Query("SELECT u FROM User u ORDER BY u.level DESC, u.points DESC, u.id ASC")
    @Cacheable(value = "topUsersByLevel", key = "#pageable")
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

    /**
     * Same two-query shape as {@link #findTopUsersByPoints(Pageable)}, and for the same
     * reason: this fetch-joined {@code achievements} alongside a {@code Pageable}, so
     * Hibernate loaded every player above the drip threshold to return ten of them.
     */
    default List<User> findTopCosmicDrippers(int minDrip, Pageable pageable) {
        List<Long> ids = findTopCosmicDripperIds(minDrip, pageable);
        return ids.isEmpty() ? List.of() : findUsersByIdsOrderedByCosmicDrip(ids);
    }

    @Query("SELECT u.id FROM User u WHERE u.cosmicDrip >= :minDrip "
         + "ORDER BY u.cosmicDrip DESC, u.hypeMeter DESC, u.id ASC")
    List<Long> findTopCosmicDripperIds(@Param("minDrip") int minDrip, Pageable pageable);

    @EntityGraph(attributePaths = {"achievements"})
    // Not cached at the repository layer: LeaderboardService already caches this
    // result, and only the service-level cache is named by updateScore /
    // refreshLeaderboard's @CacheEvict. Caching both layers made that eviction
    // cosmetic — it would fire, and the stale rows would come straight back up.
    @Query("SELECT u FROM User u WHERE u.id IN :ids ORDER BY u.cosmicDrip DESC, u.hypeMeter DESC, u.id ASC")
    List<User> findUsersByIdsOrderedByCosmicDrip(@Param("ids") List<Long> ids);

    @Query("SELECT u FROM User u WHERE u.hypeMeter >= :minHype ORDER BY u.hypeMeter DESC, u.points DESC")
    // Not cached here: LeaderboardService.getTopPlayersByHypePaged already caches this result, and only the service-level cache is named by
    // updateScore/refreshLeaderboard's @CacheEvict. Caching both layers made that
    // eviction cosmetic — it would fire, and the stale rows would come straight back
    // up from here.
    List<User> findHypeLegends(@Param("minHype") int minHype, Pageable pageable);

    @EntityGraph(attributePaths = {"friends"})
    @Query("SELECT u FROM User u WHERE SIZE(u.friends) >= :minFriends AND u.fanCount >= :minFans " +
           "ORDER BY (u.hypeMeter + u.cosmicDrip) DESC")
    @Cacheable(value = "socialCosmicIcons", key = "#minFriends + '-' + #minFans + '-' + #pageable")
    List<User> findSocialCosmicIcons(@Param("minFriends") int minFriends, @Param("minFans") int minFans, Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.streak >= :minStreak AND u.lastLogin > :since " +
           "ORDER BY u.streak DESC, u.cosmicDrip DESC")
    // Not cached here: LeaderboardService.getTopRecentPlayersPaged already caches this result, and only the service-level cache is named by
    // updateScore/refreshLeaderboard's @CacheEvict. Caching both layers made that
    // eviction cosmetic — it would fire, and the stale rows would come straight back
    // up from here.
    List<User> findActiveStreakCosmonauts(@Param("minStreak") int minStreak, @Param("since") LocalDateTime since, Pageable pageable);

    @Transactional(readOnly = true)
    @Query(value = "SELECT u.username AS username, u.level AS level, u.points AS points, " +
           "u.duel_wins AS duelWins, u.cosmic_drip AS cosmicDrip, u.hype_meter AS hypeMeter, " +
           "u.streak AS streak, u.fan_count AS fanCount, u.xp AS xp, u.last_login AS lastLogin, " +
           "CASE WHEN (u.duel_wins + u.duel_losses) > 0 " +
           "     THEN CAST(u.duel_wins AS FLOAT) / (u.duel_wins + u.duel_losses) " +
           "     ELSE 0.0 END AS winRate, " +
           "(SELECT COUNT(*) FROM user_achievements ua WHERE ua.user_id = u.id) AS achievementCount " +
           "FROM users u " +
           "ORDER BY (u.points * 0.5 + u.duel_wins * 1.5 + u.cosmic_drip * 0.8 + u.hype_meter * 0.7) DESC",
           nativeQuery = true)
    // Not cached here: LeaderboardService.getLeaderboardSummaryPaged already caches this result, and only the service-level cache is named by
    // updateScore/refreshLeaderboard's @CacheEvict. Caching both layers made that
    // eviction cosmetic — it would fire, and the stale rows would come straight back
    // up from here.
    List<UserSummary> getCosmicLeaderboardSummary(Pageable pageable);

    @EntityGraph(attributePaths = {"achievements"})
    @Query("SELECT u FROM User u WHERE SIZE(u.achievements) >= :minAchievements " +
           "ORDER BY u.points DESC, u.cosmicDrip DESC")
    @Cacheable(value = "achievementHunters", key = "#minAchievements + '-' + #pageable")
    List<User> findAchievementHunters(@Param("minAchievements") int minAchievements, Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.xp >= :minXp AND u.level >= :minLevel " +
           "ORDER BY (u.points + u.xp) DESC")
    @Cacheable(value = "xpPointLeaders", key = "#minXp + '-' + #minLevel + '-' + #pageable")
    List<User> findXpPointLeaders(@Param("minXp") int minXp, @Param("minLevel") int minLevel, Pageable pageable);

    @Transactional(readOnly = true)
    @Query("SELECT AVG(u.points) as avgPoints, AVG(u.duelWins) as avgDuelWins, AVG(u.cosmicDrip) as avgCosmicDrip " +
           "FROM User u WHERE u.lastLogin > :since")
    Map<String, Double> getLeaderboardStatsSince(@Param("since") LocalDateTime since);
}
