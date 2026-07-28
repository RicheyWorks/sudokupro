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

    /**
     * Deliberately NOT {@code @Cacheable}, though it used to be.
     *
     * <p>This is the lookup behind authentication ({@code AccountService.loadUserByUsername}),
     * registration, password change, wallet provisioning, friends and duels. Caching it was
     * safe only while {@code @EnableCaching} was absent and the annotation was inert. Armed,
     * it breaks two things immediately:
     *
     * <ul>
     *   <li>It caches {@code Optional.empty()}. {@code AccountService.register} looks the name
     *       up (miss, "no such user" cached), then writes with {@code save()}. The account is
     *       then in the database and absent from the cache, so the very next login gets
     *       {@code UsernameNotFoundException} → 401, for the whole TTL.</li>
     *   <li>{@code EconomyService.walletFor} is a check-then-insert over the same lookup. A
     *       cached empty makes it insert again, hit the V9 unique index, and then fail its own
     *       recovery read — "wallet vanished after a unique-constraint conflict", a 500 on an
     *       ordinary hint request.</li>
     * </ul>
     *
     * <p>The three {@code @CacheEvict}s that existed covered only the bulk {@code @Modifying}
     * updates below; every other writer goes through {@code save()}, which no annotation
     * intercepts. Making the eviction story complete would mean tracking every writer of a JPA
     * entity through a heap cache, for a single indexed single-row lookup. Not worth it — so
     * the cache is gone rather than half-correct.
     */
    Optional<User> findByUsername(String username);

    /**
     * Atomically deducts {@code cost} gems, but only if the player can afford it.
     * Returns the number of rows updated: 1 on success, 0 if the balance was too low.
     *
     * <p>Required because the read-modify-write in {@code EconomyService.chargeForHint}
     * loses updates. Hint charging is serialized only by the PER-GAME lock, so requests
     * against DIFFERENT games never contend: they all read the same balance and all write
     * back the same decremented value. Verified live — six concurrent hint requests across
     * six games were all served for a total of 15 gems instead of 30, i.e. six hints for
     * the price of three. Pushing the compare-and-decrement into a single SQL statement
     * makes it atomic regardless of how many game locks are involved, without needing
     * optimistic-lock retries on the whole aggregate.
     */
    @Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.gems = u.gems - :cost WHERE u.username = :username AND u.gems >= :cost")
    int deductGemsIfAffordable(@Param("username") String username, @Param("cost") int cost);

    /**
     * Adds one directed edge to the friend graph, atomically and idempotently.
     * Returns 1 if the edge was created, 0 if it already existed or the user is unknown.
     *
     * <p>{@code User.friends} is an {@code @ElementCollection}, and Hibernate's update
     * strategy for one is <b>delete every row for this user, then reinsert the whole set</b>.
     * So {@code a.addFriend(b); save(a)} does not append an edge — it replaces the entire
     * friend list with a snapshot read earlier in the transaction. Two accepts touching the
     * same person concurrently (which is exactly what a popular player gets) each read the
     * list, each add their own edge, and the second write erases the first. There is no
     * {@code @Version} on {@code User}, so nothing detects it.
     *
     * <p>Worse than a lost row: accept writes BOTH directions, and the two saves are
     * independent. Losing one of them leaves a <em>one-way friendship</em> — the friend
     * appears in one player's list and not the other's, with no request left pending to
     * retry from, and no UI path back to a consistent state. Confirmed against the mapping
     * and the generated SQL, not inferred.
     *
     * <p>Written as one statement per edge so the outcome does not depend on what else the
     * transaction has read. The {@code NOT EXISTS} guard makes a repeated accept a no-op
     * rather than a duplicate row.
     */
    @Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO user_friends (user_id, friends) "
        + "SELECT u.id, CAST(:friendId AS BIGINT) FROM users u WHERE u.id = :userId "
        + "AND NOT EXISTS (SELECT 1 FROM user_friends f "
        + "WHERE f.user_id = :userId AND f.friends = :friendId)",
        nativeQuery = true)
    int linkFriend(@Param("userId") Long userId, @Param("friendId") Long friendId);

    /**
     * Removes one directed edge from the friend graph. Returns the number of rows deleted.
     * Companion to {@link #linkFriend}, and needed for the same reason: the read-modify-write
     * in {@code FriendService.remove} rewrote the whole collection from a stale snapshot,
     * so un-friending one person could silently restore an edge someone else had just
     * removed — or drop one they had just added.
     */
    @Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM user_friends WHERE user_id = :userId AND friends = :friendId",
        nativeQuery = true)
    int unlinkFriend(@Param("userId") Long userId, @Param("friendId") Long friendId);

    /** True when {@code userId} already lists {@code friendId}. Reads the join table directly. */
    @Query(value = "SELECT COUNT(*) FROM user_friends WHERE user_id = :userId AND friends = :friendId",
        nativeQuery = true)
    long countFriendEdge(@Param("userId") Long userId, @Param("friendId") Long friendId);

    /**
     * Spends one held unit of a power-up, atomically, only if the player still holds one.
     * Returns 1 when a unit was consumed, 0 when the player held none.
     *
     * <p>Required for the same reason {@link #deductGemsIfAffordable} is: {@code PowerUpService.use}
     * read the whole {@code powerUps} map, decremented in Java, and saved the entire wallet
     * row — the exact read-modify-write its sibling {@code buy} was already converted away
     * from. Two concurrent uses both observed {@code held = 1} and both succeeded, so a
     * player could FREEZE two opponents or reveal two cells off one purchase; and the
     * full-row save reverted any gem charge that committed in between, wholesale.
     *
     * <p>Pushing the compare-and-decrement into one statement makes "do you hold one" a
     * single atomic decision. The {@code power_ups > 0} guard is what a concurrent second
     * call fails against, and the row lock the {@code UPDATE} takes is what serialises the
     * two, so the outcome does not depend on what either transaction read earlier.
     */
    @Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE user_power_ups SET power_ups = power_ups - 1 "
        + "WHERE power_ups_key = :type AND power_ups > 0 "
        + "AND user_id = (SELECT id FROM users WHERE username = :username)",
        nativeQuery = true)
    int decrementPowerUpIfHeld(@Param("username") String username, @Param("type") String type);

    /**
     * Returns one unit of a power-up the player still has a row for. Returns 1 if the row
     * was incremented, 0 if no such row exists.
     *
     * <p>The compensating action for {@link #decrementPowerUpIfHeld}: when a use is reserved
     * but the board effect then refuses (not your game, no active duel, no derivable cell),
     * the reserved unit is handed back so a rejected use costs nothing. Only ever called on
     * a row {@code decrementPowerUpIfHeld} just decremented, so the row is known to exist.
     */
    @Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE user_power_ups SET power_ups = power_ups + 1 "
        + "WHERE power_ups_key = :type "
        + "AND user_id = (SELECT id FROM users WHERE username = :username)",
        nativeQuery = true)
    int incrementPowerUp(@Param("username") String username, @Param("type") String type);

    /**
     * Adds {@code amount} gems and {@code xp} atomically, in the database.
     *
     * <p>Companion to {@link #deductGemsIfAffordable}, and needed for the same reason.
     * That method exists because read-modify-write on {@code gems} loses updates — but the
     * reward path still did {@code wallet.setGems(wallet.getGems() + earned)} followed by
     * {@code save(wallet)}, which flushes a full-row UPDATE with a value computed from a
     * snapshot read earlier in the transaction. A concurrent hint charge committing in
     * between was silently reverted: alice at 15 gems, a solve payout reading 15 and
     * computing 45, a hint charge taking her to 10, then the payout writing 45 — the hint
     * was free. Reachable any time a player has two games in flight, because the game locks
     * are per-game and do not serialise wallet writes.
     */
    @Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.gems = u.gems + :amount, u.xp = u.xp + :xp WHERE u.username = :username")
    int creditGemsAndXp(@Param("username") String username,
                        @Param("amount") int amount,
                        @Param("xp") int xp);

    /**
     * Atomic achievement-unlock payout: gems, xp, hype and cosmic drip in one statement.
     *
     * <p>Same rationale as {@link #creditGemsAndXp}: AchievementService pays rewards from
     * a {@link com.xai.sudokupro.service.GameEndListener} that runs concurrently with the
     * solve payout and any in-flight hint charges, so a read-modify-write on the entity
     * would silently revert whichever credit committed in between. Covers the two flavour
     * counters as well because {@code User.checkAchievement} — the other unlock path,
     * whose reward this mirrors — has always paid hype and drip alongside gems and xp.
     */
    @Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.gems = u.gems + :gems, u.xp = u.xp + :xp, "
         + "u.hypeMeter = u.hypeMeter + :hype, u.cosmicDrip = u.cosmicDrip + :drip "
         + "WHERE u.username = :username")
    int creditAchievementReward(@Param("username") String username,
                                @Param("gems") int gems,
                                @Param("xp") int xp,
                                @Param("hype") int hype,
                                @Param("drip") int drip);

    /** Targeted level write, so recomputing level never rewrites the gem balance. */
    @Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.level = :level WHERE u.username = :username AND u.level < :level")
    int updateLevel(@Param("username") String username, @Param("level") int level);

    /**
     * Pulls every rated player's duel rating halfway back to 1000, and returns how many
     * rows were touched.
     *
     * <p>Season rollover previously looped the same top-100 page it used for the podium,
     * so from rank 101 down nobody was reset. The un-compressed tail kept its full rating
     * and, season after season, permanently outranked the compressed head — inverting the
     * ladder the reset exists to refresh. One statement covers everyone and avoids paging
     * a large ladder through the application.
     */
    @Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.duelRating = (u.duelRating + 1000) / 2 "
         + "WHERE u.duelWins > 0 OR u.duelLosses > 0")
    int softResetDuelRatings();

    /**
     * Duel ladder: highest-rated players first (rematch/ladder feature).
     *
     * <p>The tie-break is load-bearing, not cosmetic. {@code ORDER BY duelRating DESC}
     * alone leaves equal-rated rows in whatever order Postgres happens to produce, which
     * is not stable across executions — and {@link #softResetDuelRatings()} manufactures
     * the ties, because {@code (rating + 1000) / 2} is INTEGER division and so collapses
     * adjacent ratings onto the same value every season (1016 and 1017 both land on 1008).
     * {@code SeasonService} takes the top three from this query to award
     * {@code SeasonChampion} badges, so five players tied at 1008 meant an arbitrary three
     * of them got crowned and the podium order shuffled between refreshes.
     */
    @Query("SELECT u FROM User u WHERE u.duelWins > 0 OR u.duelLosses > 0 "
         + "ORDER BY u.duelRating DESC, u.duelWins DESC, u.id ASC")
    List<User> findDuelLadder(org.springframework.data.domain.Pageable pageable);

    /**
     * Accounts anti-cheat currently holds a flag against, most recently flagged first.
     *
     * <p>Deliberately NOT {@code @Cacheable}: this is a moderation read, and a stale answer
     * means acting on a flag that has since been cleared (or missing one just raised).
     */
    @Query("SELECT u FROM User u WHERE u.cheatFlagCount > 0 ORDER BY u.lastFlaggedAt DESC, u.id ASC")
    List<User> findFlaggedPlayers();

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

    /**
     * Not cached. Both callers ({@code MetricsScheduler}, {@code RedisSyncScheduler}) pass
     * {@code LocalDateTime.now().minus(...)}, so the key was different on every single call:
     * the cache could never produce a hit, only accumulate one dead entry per scheduler tick.
     */
    @Transactional(readOnly = true)
    @Query("SELECT COUNT(u) FROM User u WHERE u.lastLogin >= :cutoff")
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
    @Query("SELECT COALESCE(SUM(u.points), 0) FROM User u WHERE u.themePreference = :theme")
    @Cacheable(value = "totalPointsByTheme", key = "#theme")
    Long getTotalPointsByTheme(@Param("theme") String theme);

    @Transactional(readOnly = true)
    @Query("SELECT COUNT(u) FROM User u WHERE u.streak > 0 AND u.duelWins > 0 AND u.gems > 0")
    @Cacheable(value = "multiActiveUsersCount")
    long countMultiActiveUsers();

    @EntityGraph(attributePaths = {"matchHistory"})
    @Query("SELECT u FROM User u WHERE u.cosmicDrip >= :minDrip AND u.lastLogin >= :since ORDER BY u.cosmicDrip DESC, u.hypeMeter DESC")
    @Cacheable(value = "activeCosmicDrippers", key = "#minDrip + '-' + #since + '-' + #pageable")
    List<User> findActiveCosmicDrippers(@Param("minDrip") int minDrip, @Param("since") LocalDateTime since, Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.hypeMeter >= :minHype AND u.fanCount >= :minFans ORDER BY u.hypeMeter DESC, u.cosmicDrip DESC")
    @Cacheable(value = "hypeFanIcons", key = "#minHype + '-' + #minFans + '-' + #pageable")
    List<User> findHypeFanIcons(@Param("minHype") int minHype, @Param("minFans") int minFans, Pageable pageable);

    @Transactional(readOnly = true)
    @Query("SELECT AVG(u.cosmicDrip) FROM User u WHERE u.lastLogin >= :since")
    @Cacheable(value = "avgCosmicDripActiveUsers", key = "#since")
    double getAverageCosmicDripActiveUsers(@Param("since") LocalDateTime since);

    @EntityGraph(attributePaths = {"matchHistory"})
    @Query("SELECT u FROM User u WHERE u.lastLogin BETWEEN :start AND :end AND SIZE(u.matchHistory) >= :minMatches " +
           "ORDER BY u.duelWins DESC, u.cosmicDrip DESC")
    @Cacheable(value = "matchHeavyPlayers", key = "#start + '-' + #end + '-' + #minMatches + '-' + #pageable")
    List<User> findMatchHeavyPlayersInPeriod(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, 
                                             @Param("minMatches") int minMatches, Pageable pageable);

    @Transactional(readOnly = true)
    @Query(value = "SELECT u.* FROM users u " +
           "WHERE u.last_login > :since AND EXISTS (SELECT 1 FROM user_match_history mh WHERE mh.user_id = u.id AND mh.match_won = true) " +
           "AND u.points BETWEEN :minPoints AND :maxPoints " +
           "ORDER BY u.hype_meter DESC, u.cosmic_drip DESC", nativeQuery = true)
    @Cacheable(value = "activeDuelWinnersByPoints", key = "#since + '-' + #minPoints + '-' + #maxPoints + '-' + #pageable")
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
    @Cacheable(value = "activeSocialPlayers", key = "#minFriends + '-' + #since + '-' + #pageable")
    List<User> findActiveSocialPlayers(@Param("minFriends") int minFriends, @Param("since") LocalDateTime since, Pageable pageable);

    @Transactional(readOnly = true)
    @Query("SELECT AVG(u.xp) as avgXp, AVG(u.level) as avgLevel, COUNT(u) as activeCount " +
           "FROM User u WHERE u.lastLogin > :since AND u.streak > 0")
    @Cacheable(value = "streakRetentionStats", key = "#since")
    Map<String, Number> getStreakRetentionStats(@Param("since") LocalDateTime since);

    @Query("SELECT u FROM User u WHERE u.powerUps[:powerUp] >= :minCount AND u.lastLogin > :since " +
           "ORDER BY u.powerUps[:powerUp] DESC, u.points DESC")
    @Cacheable(value = "powerUpActiveUsers", key = "#powerUp + '-' + #minCount + '-' + #since + '-' + #pageable")
    List<User> findPowerUpActiveUsers(@Param("powerUp") String powerUp, @Param("minCount") int minCount, 
                                      @Param("since") LocalDateTime since, Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.cosmicDrip > :minDrip AND u.points > :minPoints AND u.lastLogin < :cutoff " +
           "ORDER BY u.cosmicDrip DESC, u.points DESC")
    @Cacheable(value = "dripPointInactiveUsers", key = "#minDrip + '-' + #minPoints + '-' + #cutoff + '-' + #pageable")
    List<User> findDripPointInactiveUsers(@Param("minDrip") int minDrip, @Param("minPoints") int minPoints, 
                                          @Param("cutoff") LocalDateTime cutoff, Pageable pageable);
}
