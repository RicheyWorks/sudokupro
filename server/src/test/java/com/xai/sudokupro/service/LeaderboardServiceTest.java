package com.xai.sudokupro.service;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.repository.leaderboard.LeaderboardRepository;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LeaderboardService}.
 *
 * <p><b>Defect classes this class protects against</b> (each has at least one test that
 * fails against the pre-fix production code):
 * <ul>
 *   <li><b>Rank arithmetic / integer overflow</b> — the page offset used to number ranks was
 *       computed as {@code page * size} in {@code int}. A caller-supplied page number in the
 *       tens of millions overflows to a negative offset, producing negative ranks and, on the
 *       event leaderboard, an {@code IllegalArgumentException} out of {@code Stream.skip}.</li>
 *   <li><b>A player appearing twice with the same rank</b> — rank was derived from
 *       {@code List.indexOf(user)}, which returns the <i>first</i> matching position. A result
 *       list that contains the same entity instance twice (exactly what a collection
 *       {@code @EntityGraph} fetch can yield) numbered both occurrences identically. It is
 *       also O(n^2).</li>
 *   <li><b>A cache that is never invalidated</b> — {@code updateScore} evicted
 *       {@code topPlayers} and {@code leaderboardSummary} but not {@code publicLeaderboard},
 *       which is the only leaderboard actually served over HTTP
 *       ({@code LeaderboardController}). Once populated it never refreshed.</li>
 *   <li><b>Unbounded in-memory growth</b> — {@code scoreDeltas} accumulated one entry per
 *       player that ever scored and was never cleared or capped.</li>
 *   <li><b>Boundary handling</b> — page/size/limit validation, empty result sets, ties,
 *       tier thresholds, non-numeric player ids, suspicion filtering, and DB-down paths.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaderboardServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private LeaderboardRepository leaderboardRepository;
    @Mock private AnalyticsService analyticsService;
    @Mock private AntiCheatEngine antiCheatEngine;
    @Mock private MultiplayerBroadcaster multiplayerBroadcaster;
    @Mock private EventEngine eventEngine;

    private LeaderboardService service;

    @BeforeEach
    void setUp() {
        service = newService();
    }

    private LeaderboardService newService() {
        return new LeaderboardService(userRepository, leaderboardRepository, analyticsService,
                antiCheatEngine, multiplayerBroadcaster, eventEngine);
    }

    /** Real User entity — construction is cheap, so no mock. */
    private static User user(long id, String name, int points) {
        User u = new User(id, name);
        u.setPoints(points);
        return u;
    }

    // ------------------------------------------------------------------
    // Rank numbering
    // ------------------------------------------------------------------

    /**
     * A page's ranks must be the absolute positions of the rows: for page 2 / size 5 the
     * three returned rows are the 11th, 12th and 13th overall.
     */
    @Test
    void ranksAreAbsolutePositionsWithinThePage() {
        when(leaderboardRepository.findTopUsersByPoints(any(Pageable.class)))
                .thenReturn(List.of(user(1, "a", 300), user(2, "b", 200), user(3, "c", 100)));

        List<LeaderboardService.LeaderboardSnapshot> page = service.getTopPlayersByPointsPaged(2, 5);

        assertEquals(List.of(11, 12, 13), page.stream().map(LeaderboardService.LeaderboardSnapshot::rank).toList());
    }

    /** First page starts at rank 1, not 0 and not 2. */
    @Test
    void firstPageStartsAtRankOne() {
        when(leaderboardRepository.findTopUsersByPoints(any(Pageable.class)))
                .thenReturn(List.of(user(1, "a", 300), user(2, "b", 200)));

        List<LeaderboardService.LeaderboardSnapshot> page = service.getTopPlayersByPointsPaged(0, 10);

        assertEquals(1, page.get(0).rank());
        assertEquals(2, page.get(1).rank());
    }

    /**
     * Reproduction of the duplicate-rank defect: the repository returns the same entity
     * instance twice (a collection {@code @EntityGraph} fetch without DISTINCT does exactly
     * this). Rank came from {@code indexOf}, so both occurrences were numbered 1 and the
     * third row jumped to 3 — ranks were neither unique nor contiguous.
     */
    @Test
    void repeatedEntityInstanceStillGetsItsOwnPositionalRank() {
        User dup = user(1, "dup", 300);
        when(leaderboardRepository.findTopUsersByPoints(any(Pageable.class)))
                .thenReturn(List.of(dup, user(2, "b", 200), dup));

        List<Integer> ranks = service.getTopPlayersByPointsPaged(0, 10).stream()
                .map(LeaderboardService.LeaderboardSnapshot::rank).toList();

        assertEquals(List.of(1, 2, 3), ranks, "each row on a page occupies its own rank slot");
    }

    /**
     * Reproduction of the int-overflow defect: {@code page * size} for page=30,000,000 and
     * size=100 is 3e9, which wraps to -1,294,967,296 as an {@code int}. Ranks came back
     * negative. Page is only validated as non-negative, so this is caller-reachable.
     */
    @Test
    void hugePageNumberDoesNotProduceNegativeRanks() {
        when(leaderboardRepository.findTopUsersByPoints(any(Pageable.class)))
                .thenReturn(List.of(user(1, "a", 300)));

        int rank = service.getTopPlayersByPointsPaged(30_000_000, 100).get(0).rank();

        assertTrue(rank > 0, "rank must never be negative; got " + rank);
    }

    /**
     * Same overflow, different symptom: the event leaderboard pages in memory with
     * {@code Stream.skip(page * size)}. A negative argument makes {@code skip} throw
     * {@code IllegalArgumentException}, which {@code safeFetch} rethrows as a RuntimeException
     * — a 500 where an empty page is the correct answer.
     */
    @Test
    void hugePageNumberOnEventLeaderboardReturnsEmptyPageInsteadOfThrowing() {
        when(eventEngine.getPlayerEventScores()).thenReturn(Map.of("1-e1", 5));
        when(userRepository.findAllById(any())).thenReturn(List.of(user(1, "a", 10)));

        assertEquals(List.of(), service.getTopEventPlayersPaged("e1", 30_000_000, 100));
    }

    // ------------------------------------------------------------------
    // Pagination / limit validation
    // ------------------------------------------------------------------

    @Test
    void negativePageIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.getTopPlayersByPointsPaged(-1, 10));
    }

    @Test
    void zeroAndNegativePageSizeAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.getTopPlayersByPointsPaged(0, 0));
        assertThrows(IllegalArgumentException.class, () -> service.getTopPlayersByPointsPaged(0, -5));
    }

    @Test
    void pageSizeAboveTheHardMaximumIsRejectedButTheMaximumItselfIsAccepted() {
        assertThrows(IllegalArgumentException.class, () -> service.getTopPlayersByPointsPaged(0, 101));

        when(leaderboardRepository.findTopUsersByPoints(any(Pageable.class))).thenReturn(List.of());
        assertEquals(List.of(), service.getTopPlayersByPointsPaged(0, 100), "size=100 is inside the limit");
    }

    @Test
    void everyPagedLeaderboardValidatesItsPagination() {
        assertThrows(IllegalArgumentException.class, () -> service.getTopPlayersByCosmicDripPaged(0, 0));
        assertThrows(IllegalArgumentException.class, () -> service.getTopPlayersByHypePaged(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> service.getTopDuelistsPaged(0, 1000));
        assertThrows(IllegalArgumentException.class, () -> service.getTopPlayersCombinedPaged(0, -1));
        assertThrows(IllegalArgumentException.class, () -> service.getTopRecentPlayersPaged(-2, 10));
        assertThrows(IllegalArgumentException.class, () -> service.getLeaderboardSummaryPaged(0, 0));
        assertThrows(IllegalArgumentException.class, () -> service.getTopEventPlayersPaged("e1", 0, 0));
    }

    @Test
    void publicLeaderboardLimitBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> service.getPublicLeaderboard(0));
        assertThrows(IllegalArgumentException.class, () -> service.getPublicLeaderboard(-1));
        assertThrows(IllegalArgumentException.class, () -> service.getPublicLeaderboard(101));

        when(leaderboardRepository.findTopUsersByPoints(any(Pageable.class))).thenReturn(List.of(user(1, "a", 1)));
        assertEquals(1, service.getPublicLeaderboard(1).size(), "limit=1 is valid");
        assertEquals(1, service.getPublicLeaderboard(100).size(), "limit=100 is valid");
    }

    @Test
    void emptyRepositoryYieldsAnEmptyListNotNull() {
        when(leaderboardRepository.findTopUsersByPoints(any(Pageable.class))).thenReturn(List.of());
        assertEquals(List.of(), service.getPublicLeaderboard(10));
        assertEquals(List.of(), service.getTopPlayersByPointsPaged(0, 10));
    }

    // ------------------------------------------------------------------
    // updateScore / score deltas
    // ------------------------------------------------------------------

    @Test
    void updateScoreRejectsInvalidUserIdsAndNegativePoints() {
        assertThrows(IllegalArgumentException.class, () -> service.updateScore(null, 10));
        assertThrows(IllegalArgumentException.class, () -> service.updateScore(0L, 10));
        assertThrows(IllegalArgumentException.class, () -> service.updateScore(-3L, 10));
        assertThrows(IllegalArgumentException.class, () -> service.updateScore(1L, -1));
        verifyNoInteractions(userRepository);
    }

    /** Zero points is a legal no-op update, not a validation error. */
    @Test
    void updateScoreAcceptsZeroPoints() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1, "a", 50)));
        assertDoesNotThrow(() -> service.updateScore(1L, 0));
        verify(userRepository).save(any(User.class));
    }

    @Test
    void updateScoreCreatesAndPersistsAnUnknownPlayer() {
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        service.updateScore(7L, 10);

        verify(userRepository).save(argThat(u -> u.getId() == 7L && "Player_7".equals(u.getUsername())));
    }

    @Test
    void updateScoreBroadcastsTheChange() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1, "a", 0)));

        service.updateScore(1L, 10);

        verify(multiplayerBroadcaster).broadcastEvent(eq("leaderboardUpdate"), contains("\"playerId\":\"1\""), isNull());
    }

    /** A repository failure must not be swallowed — it is wrapped and rethrown. */
    @Test
    void updateScorePropagatesRepositoryFailure() {
        when(userRepository.findById(1L)).thenThrow(new IllegalStateException("db down"));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.updateScore(1L, 10));
        assertNotNull(ex.getCause());
    }

    /**
     * Reproduction of the unbounded-growth defect: {@code scoreDeltas} gained one permanent
     * entry per distinct player and was never trimmed, so a long-lived process leaked one
     * map entry per player that ever scored.
     */
    @Test
    @SuppressWarnings("unchecked")
    void scoreDeltaMapIsBounded() {
        when(userRepository.findById(any())).thenAnswer(inv -> Optional.of(user(inv.getArgument(0), "p", 0)));

        for (long id = 1; id <= 12_000; id++) {
            service.updateScore(id, 1);
        }

        Map<String, Integer> deltas =
                (Map<String, Integer>) ReflectionTestUtils.getField(service, "scoreDeltas");
        assertNotNull(deltas);
        assertTrue(deltas.size() <= 10_000,
                "scoreDeltas must stay bounded; grew to " + deltas.size());
    }

    /** {@code refreshLeaderboard} starts a new delta window rather than accumulating forever. */
    @Test
    @SuppressWarnings("unchecked")
    void refreshLeaderboardResetsTheDeltaWindow() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1, "a", 0)));
        service.updateScore(1L, 10);

        Map<String, Integer> deltas =
                (Map<String, Integer>) ReflectionTestUtils.getField(service, "scoreDeltas");
        assertEquals(Integer.valueOf(10), deltas.get("1"), "delta recorded before the refresh");

        service.refreshLeaderboard();

        assertTrue(deltas.isEmpty(), "refresh must clear the delta window");
    }

    // ------------------------------------------------------------------
    // getPlayerRank
    // ------------------------------------------------------------------

    @Test
    void playerRankIsOneMoreThanTheNumberOfPlayersAhead() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1, "a", 500)));
        when(userRepository.countByPointsGreaterThan(500)).thenReturn(4L);

        assertEquals(5, service.getPlayerRank(1L).rank());
    }

    /** The top player is rank 1, never rank 0. */
    @Test
    void topPlayerIsRankOne() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1, "a", 900)));
        when(userRepository.countByPointsGreaterThan(900)).thenReturn(0L);

        assertEquals(1, service.getPlayerRank(1L).rank());
    }

    /** Two players on identical points share a rank — standard competition ranking. */
    @Test
    void tiedPlayersShareTheSameRank() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1, "a", 500)));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2, "b", 500)));
        when(userRepository.countByPointsGreaterThan(500)).thenReturn(3L);

        assertEquals(service.getPlayerRank(1L).rank(), service.getPlayerRank(2L).rank());
        assertEquals(4, service.getPlayerRank(1L).rank());
    }

    @Test
    void unknownPlayerHasNoRank() {
        when(userRepository.findById(42L)).thenReturn(Optional.empty());
        assertNull(service.getPlayerRank(42L));
    }

    @Test
    void playerRankValidatesTheUserId() {
        assertThrows(IllegalArgumentException.class, () -> service.getPlayerRank(null));
        assertThrows(IllegalArgumentException.class, () -> service.getPlayerRank(0L));
        assertThrows(IllegalArgumentException.class, () -> service.getPlayerRank(-1L));
    }

    // ------------------------------------------------------------------
    // Tier thresholds — hand-derived boundary table
    // ------------------------------------------------------------------

    @Test
    void tierBoundaries() {
        assertEquals("Unranked", tierFor(0));
        assertEquals("Unranked", tierFor(999));
        assertEquals("Bronze", tierFor(1_000));
        assertEquals("Bronze", tierFor(4_999));
        assertEquals("Silver", tierFor(5_000));
        assertEquals("Silver", tierFor(9_999));
        assertEquals("Gold", tierFor(10_000));
        assertEquals("Gold", tierFor(24_999));
        assertEquals("Cosmic", tierFor(25_000));
        assertEquals("Cosmic", tierFor(1_000_000));
    }

    private String tierFor(int points) {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1, "a", points)));
        when(userRepository.countByPointsGreaterThan(anyInt())).thenReturn(0L);
        return service.getPlayerRank(1L).tier();
    }

    // ------------------------------------------------------------------
    // Sort field selection
    // ------------------------------------------------------------------

    /**
     * Each board must expose its own sort field as {@code sortValue}; a copy/paste slip that
     * reported points on the drip board would otherwise be invisible.
     */
    @Test
    void eachBoardReportsItsOwnSortValue() {
        User u = user(1, "a", 111);
        u.setCosmicDrip(222);
        u.setHypeMeter(333);
        u.setDuelWins(444);

        when(leaderboardRepository.findTopUsersByPoints(any(Pageable.class))).thenReturn(List.of(u));
        when(leaderboardRepository.findTopCosmicDrippers(anyInt(), any(Pageable.class))).thenReturn(List.of(u));
        when(leaderboardRepository.findHypeLegends(anyInt(), any(Pageable.class))).thenReturn(List.of(u));
        when(leaderboardRepository.findTopDuelists(any(Pageable.class))).thenReturn(List.of(u));

        assertEquals(111, service.getTopPlayersByPointsPaged(0, 10).get(0).sortValue());
        assertEquals(222, service.getTopPlayersByCosmicDripPaged(0, 10).get(0).sortValue());
        assertEquals(333, service.getTopPlayersByHypePaged(0, 10).get(0).sortValue());
        assertEquals(444, service.getTopDuelistsPaged(0, 10).get(0).sortValue());
    }

    /** The drip and hype boards must ask the repository for their documented thresholds. */
    @Test
    void dripAndHypeBoardsUseTheirThresholds() {
        when(leaderboardRepository.findTopCosmicDrippers(anyInt(), any(Pageable.class))).thenReturn(List.of());
        when(leaderboardRepository.findHypeLegends(anyInt(), any(Pageable.class))).thenReturn(List.of());

        service.getTopPlayersByCosmicDripPaged(0, 10);
        service.getTopPlayersByHypePaged(0, 10);

        verify(leaderboardRepository).findTopCosmicDrippers(eq(50), any(Pageable.class));
        verify(leaderboardRepository).findHypeLegends(eq(100), any(Pageable.class));
    }

    // ------------------------------------------------------------------
    // Combined (skill + anti-cheat) board
    // ------------------------------------------------------------------

    /** Ranking order comes from the skill scores, not from the order the DB returns rows in. */
    @Test
    void combinedBoardOrdersBySkillScoreDescendingRegardlessOfDbOrder() {
        Map<String, Double> skills = new LinkedHashMap<>();
        skills.put("1", 10.0);
        skills.put("2", 30.0);
        skills.put("3", 20.0);
        when(analyticsService.getPlayerSkillScores()).thenReturn(skills);
        when(antiCheatEngine.getCheatSuspicionScores()).thenReturn(Map.of());
        when(userRepository.findAllById(any()))
                .thenReturn(List.of(user(1, "one", 1), user(2, "two", 2), user(3, "three", 3)));

        List<String> names = service.getTopPlayersCombinedPaged(0, 10).stream()
                .map(LeaderboardService.LeaderboardSnapshot::username).toList();

        assertEquals(List.of("two", "three", "one"), names);
    }

    /** Suspicion above the 75.0 threshold excludes a player; exactly 75.0 does not. */
    @Test
    void combinedBoardExcludesFlaggedCheatersAtTheThreshold() {
        when(analyticsService.getPlayerSkillScores()).thenReturn(Map.of("1", 10.0, "2", 20.0, "3", 30.0));
        when(antiCheatEngine.getCheatSuspicionScores()).thenReturn(Map.of("3", 75.5, "2", 75.0));
        when(userRepository.findAllById(any())).thenAnswer(inv -> {
            List<Long> ids = new ArrayList<>();
            ((Iterable<Long>) inv.getArgument(0)).forEach(ids::add);
            return ids.stream().map(id -> user(id, "u" + id, 1)).toList();
        });

        List<String> names = service.getTopPlayersCombinedPaged(0, 10).stream()
                .map(LeaderboardService.LeaderboardSnapshot::username).toList();

        assertEquals(List.of("u2", "u1"), names, "75.5 is over the threshold, 75.0 is not");
    }

    /** A non-numeric player id (e.g. "anonymous") must be skipped, not abort the whole board. */
    @Test
    void combinedBoardSkipsNonNumericPlayerIds() {
        when(analyticsService.getPlayerSkillScores()).thenReturn(Map.of("anonymous", 99.0, "5", 1.0));
        when(antiCheatEngine.getCheatSuspicionScores()).thenReturn(Map.of());
        when(userRepository.findAllById(any())).thenReturn(List.of(user(5, "five", 1)));

        List<String> names = service.getTopPlayersCombinedPaged(0, 10).stream()
                .map(LeaderboardService.LeaderboardSnapshot::username).toList();

        assertEquals(List.of("five"), names);
    }

    /** Paging past the end of the skill map is an empty page, and must not touch the DB. */
    @Test
    void combinedBoardPagesPastTheEndWithoutQueryingTheDatabase() {
        when(analyticsService.getPlayerSkillScores()).thenReturn(Map.of("1", 10.0));
        when(antiCheatEngine.getCheatSuspicionScores()).thenReturn(Map.of());

        assertEquals(List.of(), service.getTopPlayersCombinedPaged(5, 10));
        verify(userRepository, never()).findAllById(any());
    }

    /** Only the ids on the requested page are loaded — no N+1 / full-table load. */
    @Test
    void combinedBoardLoadsOnlyThePagesIdsInOneQuery() {
        Map<String, Double> skills = new LinkedHashMap<>();
        for (long id = 1; id <= 40; id++) {
            skills.put(String.valueOf(id), (double) id);
        }
        when(analyticsService.getPlayerSkillScores()).thenReturn(skills);
        when(antiCheatEngine.getCheatSuspicionScores()).thenReturn(Map.of());
        when(userRepository.findAllById(any())).thenAnswer(inv -> {
            List<Long> ids = new ArrayList<>();
            ((Iterable<Long>) inv.getArgument(0)).forEach(ids::add);
            assertEquals(5, ids.size(), "exactly one page of ids should be loaded");
            return ids.stream().map(id -> user(id, "u" + id, 1)).toList();
        });

        service.getTopPlayersCombinedPaged(1, 5);

        verify(userRepository, times(1)).findAllById(any());
        verify(userRepository, never()).findAll();
    }

    /** Ids on the page that no longer exist in the DB are dropped, not rendered as nulls. */
    @Test
    void combinedBoardToleratesDeletedUsers() {
        when(analyticsService.getPlayerSkillScores()).thenReturn(Map.of("1", 10.0, "2", 20.0));
        when(antiCheatEngine.getCheatSuspicionScores()).thenReturn(Map.of());
        when(userRepository.findAllById(any())).thenReturn(List.of(user(1, "one", 1)));

        List<LeaderboardService.LeaderboardSnapshot> page = service.getTopPlayersCombinedPaged(0, 10);

        assertEquals(1, page.size());
        assertEquals("one", page.get(0).username());
    }

    // ------------------------------------------------------------------
    // Event board
    // ------------------------------------------------------------------

    @Test
    void eventIdIsValidated() {
        assertThrows(IllegalArgumentException.class, () -> service.getTopEventPlayersPaged(null, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> service.getTopEventPlayersPaged("", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> service.getTopEventPlayersPaged("   ", 0, 10));
    }

    /**
     * Only scores for the requested event count, ordering is by event score, and the
     * snapshot's sortValue is the event score (not the player's points).
     */
    @Test
    void eventBoardRanksByEventScoreForTheRequestedEventOnly() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("1-e1", 5);
        scores.put("2-e1", 9);
        scores.put("3-e2", 1000);          // different event — must be ignored
        scores.put("anonymous-e1", 7);     // non-numeric — must be skipped
        when(eventEngine.getPlayerEventScores()).thenReturn(scores);
        when(userRepository.findAllById(any())).thenAnswer(inv -> {
            List<Long> ids = new ArrayList<>();
            ((Iterable<Long>) inv.getArgument(0)).forEach(ids::add);
            assertFalse(ids.contains(3L), "a player from another event must not be loaded");
            return ids.stream().map(id -> user(id, "u" + id, 42)).toList();
        });

        List<LeaderboardService.LeaderboardSnapshot> page = service.getTopEventPlayersPaged("e1", 0, 10);

        assertEquals(List.of("u2", "u1"), page.stream()
                .map(LeaderboardService.LeaderboardSnapshot::username).toList());
        assertEquals(9, page.get(0).sortValue(), "sortValue is the event score");
        assertEquals(5, page.get(1).sortValue());
    }

    @Test
    void eventBoardWithNoScoresIsEmpty() {
        when(eventEngine.getPlayerEventScores()).thenReturn(Map.of());
        when(userRepository.findAllById(any())).thenReturn(List.of());
        assertEquals(List.of(), service.getTopEventPlayersPaged("e1", 0, 10));
    }

    // ------------------------------------------------------------------
    // Recent board
    // ------------------------------------------------------------------

    @Test
    void recentBoardFallsBackToPointsWhenNobodyIsActive() {
        when(leaderboardRepository.findActiveStreakCosmonauts(anyInt(), any(), any(Pageable.class)))
                .thenReturn(List.of());
        when(leaderboardRepository.findTopUsersByPoints(any(Pageable.class)))
                .thenReturn(List.of(user(1, "fallback", 10)));

        assertEquals("fallback", service.getTopRecentPlayersPaged(0, 10).get(0).username());
    }

    @Test
    void recentBoardDoesNotFallBackWhenActivePlayersExist() {
        when(leaderboardRepository.findActiveStreakCosmonauts(anyInt(), any(), any(Pageable.class)))
                .thenReturn(List.of(user(1, "active", 10)));

        assertEquals("active", service.getTopRecentPlayersPaged(0, 10).get(0).username());
        verify(leaderboardRepository, never()).findTopUsersByPoints(any(Pageable.class));
    }

    // ------------------------------------------------------------------
    // Failure propagation
    // ------------------------------------------------------------------

    @Test
    void databaseFailureIsWrappedWithContextNotSwallowed() {
        when(leaderboardRepository.findTopUsersByPoints(any(Pageable.class)))
                .thenThrow(new IllegalStateException("db down"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.getPublicLeaderboard(10));
        assertTrue(ex.getMessage().contains("public leaderboard"), "context lost: " + ex.getMessage());
        assertEquals("db down", ex.getCause().getMessage());
    }

    @Test
    void leaderboardStatsFailureIsWrapped() {
        when(leaderboardRepository.getLeaderboardStatsSince(any())).thenThrow(new IllegalStateException("boom"));
        assertThrows(RuntimeException.class, () -> service.getLeaderboardStats());
    }

    @Test
    void leaderboardStatsArePassedThroughUnmodified() {
        Map<String, Double> stats = Map.of("avgPoints", 12.5);
        when(leaderboardRepository.getLeaderboardStatsSince(any())).thenReturn(stats);
        assertEquals(stats, service.getLeaderboardStats());
    }

    @Test
    void summaryBoardDelegatesToTheRepository() {
        when(leaderboardRepository.getCosmicLeaderboardSummary(any(Pageable.class)))
                .thenReturn(Collections.emptyList());
        assertEquals(List.of(), service.getLeaderboardSummaryPaged(0, 10));
        verify(leaderboardRepository).getCosmicLeaderboardSummary(any(Pageable.class));
    }

    // ------------------------------------------------------------------
    // Cache invalidation
    // ------------------------------------------------------------------

    /**
     * Reproduction of the stale-cache defect. {@code getPublicLeaderboard} is
     * {@code @Cacheable("publicLeaderboard")} and is the only leaderboard exposed over HTTP,
     * but {@code updateScore}'s {@code @CacheEvict} listed only {@code topPlayers} and
     * {@code leaderboardSummary}. Once warm, the public board never changed again.
     *
     * <p>Driven through a real Spring cache proxy because the defect lives entirely in the
     * annotation metadata. (Note the application does not currently switch caching on, so the
     * defect is dormant in production but ships as a landmine.)
     */
    @Test
    void updateScoreEvictsThePublicLeaderboardCache() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(CachingTestConfig.class);
            ctx.registerBean(LeaderboardService.class, this::newService);
            ctx.refresh();
            LeaderboardService cached = ctx.getBean(LeaderboardService.class);

            when(leaderboardRepository.findTopUsersByPoints(any(Pageable.class)))
                    .thenReturn(List.of(user(1, "a", 100)));
            assertEquals(100, cached.getPublicLeaderboard(5).get(0).sortValue());

            when(leaderboardRepository.findTopUsersByPoints(any(Pageable.class)))
                    .thenReturn(List.of(user(1, "a", 999)));
            assertEquals(100, cached.getPublicLeaderboard(5).get(0).sortValue(), "second read is served from cache");

            when(userRepository.findById(1L)).thenReturn(Optional.of(user(1, "a", 100)));
            cached.updateScore(1L, 10);

            assertEquals(999, cached.getPublicLeaderboard(5).get(0).sortValue(),
                    "a score change must invalidate the public leaderboard cache");
        }
    }

    /** {@code refreshLeaderboard} clears every leaderboard cache, including the public one. */
    @Test
    void refreshLeaderboardEvictsEveryCache() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(CachingTestConfig.class);
            ctx.registerBean(LeaderboardService.class, this::newService);
            ctx.refresh();
            LeaderboardService cached = ctx.getBean(LeaderboardService.class);

            when(leaderboardRepository.findTopUsersByPoints(any(Pageable.class)))
                    .thenReturn(List.of(user(1, "a", 100)));
            assertEquals(100, cached.getPublicLeaderboard(5).get(0).sortValue());
            assertEquals(100, cached.getTopPlayersByPointsPaged(0, 5).get(0).sortValue());

            when(leaderboardRepository.findTopUsersByPoints(any(Pageable.class)))
                    .thenReturn(List.of(user(1, "a", 999)));
            cached.refreshLeaderboard();

            assertEquals(999, cached.getPublicLeaderboard(5).get(0).sortValue());
            assertEquals(999, cached.getTopPlayersByPointsPaged(0, 5).get(0).sortValue());
        }
    }

    @Configuration
    @EnableCaching
    static class CachingTestConfig {
        @org.springframework.context.annotation.Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("topPlayers", "leaderboardSummary", "publicLeaderboard");
        }
    }
}
