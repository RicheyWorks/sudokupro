package com.xai.sudokupro.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.repository.leaderboard.LeaderboardRepository;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two defect classes that both let a leaderboard tell the truth about the wrong rows.
 *
 * <p><b>1. Enforcement applied to one code path out of five.</b> Only
 * {@code getTopPlayersCombinedPaged} filtered players the anti-cheat pipeline had flagged.
 * The points, drip, hype and duel boards — and the public board, which is the only one
 * {@code LeaderboardController} actually serves over HTTP — did not, so a cheater the
 * pipeline had already caught kept their top-10 slot on every board a player is likely to
 * look at. A partial ban is worse than no ban: it makes the anti-cheat dashboard look like
 * it is working.
 *
 * <p>What "flagged" means here was verified against the pipeline rather than assumed.
 * {@code AntiCheatEngine.flagPlayer} does <em>not</em> set any persistent marker — there is
 * no flag column on {@code User}; it halves the player's {@code cosmicDrip} and returns.
 * {@code AntiCheatScheduler} keeps a private {@code flaggedPlayers} counter that no other
 * service can reach. The single piece of state every stage of the pipeline agrees on is
 * {@code AntiCheatEngine.getCheatSuspicionScores()}: the scheduler's eight detectors all
 * gate on it, {@code MetricsScheduler} counts suspicious players from it, and the combined
 * board already filtered on it. That map, at the threshold the combined board already used,
 * is therefore the definition applied to every board.
 *
 * <p><b>2. A collection fetch join paginated in memory.</b> {@code findTopUsersByPoints} and
 * {@code findTopCosmicDrippers} combine {@code @EntityGraph} over a collection with a
 * {@code Pageable}. Hibernate cannot express that as a single LIMITed SQL statement, so it
 * silently loads the <em>entire</em> ordered result set and slices it in Java, warning
 * HHH90003004. The page is correct and the query is a full table read: a request for the
 * top 10 of a million players materialises a million {@code User} entities plus their
 * collections into heap, on the request thread, on the endpoint most likely to be hot.
 */
class LeaderboardFairnessTest {

    /** Real User entities — construction is cheap, so no mock. */
    private static User user(long id, String name, int points) {
        User u = new User(id, name);
        u.setPoints(points);
        return u;
    }

    // ==================================================================
    // 1. Flagged players must be excluded from every board
    // ==================================================================

    @Nested
    class FlaggedPlayerExclusion {

        private UserRepository userRepository;
        private LeaderboardRepository leaderboardRepository;
        private AnalyticsService analyticsService;
        private AntiCheatEngine antiCheatEngine;
        private EventEngine eventEngine;
        private LeaderboardService service;

        /** id 2 is the cheater the pipeline already caught. */
        private static final String FLAGGED = "2";

        @BeforeEach
        void setUp() {
            userRepository = mock(UserRepository.class);
            leaderboardRepository = mock(LeaderboardRepository.class);
            analyticsService = mock(AnalyticsService.class);
            antiCheatEngine = mock(AntiCheatEngine.class);
            eventEngine = mock(EventEngine.class);
            service = new LeaderboardService(userRepository, leaderboardRepository, analyticsService,
                    antiCheatEngine, mock(MultiplayerBroadcaster.class), eventEngine);

            List<User> threePlayers = List.of(
                    user(1, "clean-top", 900), user(2, "cheater", 800), user(3, "clean-bottom", 700));

            // Every board's underlying query happily returns the flagged player: exclusion is
            // the service's job, because suspicion scores live in memory, not in the database.
            when(leaderboardRepository.findTopUsersByPoints(any(Pageable.class))).thenReturn(threePlayers);
            when(leaderboardRepository.findTopCosmicDrippers(anyInt(), any(Pageable.class))).thenReturn(threePlayers);
            when(leaderboardRepository.findHypeLegends(anyInt(), any(Pageable.class))).thenReturn(threePlayers);
            when(leaderboardRepository.findTopDuelists(any(Pageable.class))).thenReturn(threePlayers);
            when(leaderboardRepository.findActiveStreakCosmonauts(anyInt(), any(LocalDateTime.class), any(Pageable.class)))
                    .thenReturn(threePlayers);
            when(userRepository.findAllById(anyIterable())).thenReturn(threePlayers);
            when(analyticsService.getPlayerSkillScores()).thenReturn(Map.of("1", 30.0, "2", 20.0, "3", 10.0));
            when(eventEngine.getPlayerEventScores()).thenReturn(Map.of("1-e", 30, "2-e", 20, "3-e", 10));
        }

        private void flagWithSuspicion(double score) {
            when(antiCheatEngine.getCheatSuspicionScores()).thenReturn(Map.of(FLAGGED, score));
        }

        /** Every board that returns player rows, keyed by the name used in failure messages. */
        private Map<String, Supplier<List<LeaderboardService.LeaderboardSnapshot>>> allBoards() {
            return new java.util.LinkedHashMap<>(Map.of(
                    "points", () -> service.getTopPlayersByPointsPaged(0, 10),
                    "cosmic drip", () -> service.getTopPlayersByCosmicDripPaged(0, 10),
                    "hype", () -> service.getTopPlayersByHypePaged(0, 10),
                    "duels", () -> service.getTopDuelistsPaged(0, 10),
                    "combined", () -> service.getTopPlayersCombinedPaged(0, 10),
                    "recent", () -> service.getTopRecentPlayersPaged(0, 10),
                    "event", () -> service.getTopEventPlayersPaged("e", 0, 10),
                    "public", () -> service.getPublicLeaderboard(10)));
        }

        /**
         * Reproduction of the P2: before the fix only "combined" excluded the cheater, so
         * this failed listing seven of the eight boards.
         */
        @Test
        void aFlaggedPlayerAppearsOnNoBoard() {
            flagWithSuspicion(96.0);

            List<String> leaking = new ArrayList<>();
            allBoards().forEach((name, board) -> {
                boolean present = board.get().stream().anyMatch(s -> "cheater".equals(s.username()));
                if (present) leaking.add(name);
            });

            assertEquals(List.of(), leaking,
                    "a player flagged by the anti-cheat pipeline is still listed on: " + leaking);
        }

        /** The clean players are untouched — the filter removes the cheater, not the board. */
        @Test
        void cleanPlayersSurviveTheFilterOnEveryBoard() {
            flagWithSuspicion(96.0);

            allBoards().forEach((name, board) -> {
                List<String> names = board.get().stream()
                        .map(LeaderboardService.LeaderboardSnapshot::username).toList();
                assertEquals(List.of("clean-top", "clean-bottom"), names, name + " board");
            });
        }

        /**
         * With nobody flagged, every board is unchanged — guards against a filter that drops
         * rows it should keep (for example by treating an absent suspicion entry as flagged).
         */
        @Test
        void nobodyFlaggedMeansNobodyRemoved() {
            when(antiCheatEngine.getCheatSuspicionScores()).thenReturn(Map.of());

            allBoards().forEach((name, board) ->
                    assertEquals(3, board.get().size(), name + " board dropped an unflagged player"));
        }

        /**
         * All boards share one threshold. 75.0 is the documented boundary and is <em>not</em>
         * flagged (the combined board has always kept it; {@code MetricsScheduler} counts
         * suspicious players the same way, with a strict {@code >}); anything above it is.
         */
        @Test
        void everyBoardUsesTheSameSuspicionThreshold() {
            flagWithSuspicion(75.0);
            allBoards().forEach((name, board) ->
                    assertEquals(3, board.get().size(), name + " board excluded a player exactly at the threshold"));

            flagWithSuspicion(75.5);
            allBoards().forEach((name, board) ->
                    assertEquals(2, board.get().size(), name + " board kept a player above the threshold"));
        }

        /**
         * Ranks are renumbered after the exclusion. Leaving the flagged player's slot behind
         * as a gap would advertise "someone was removed from position 2" and would also make
         * the displayed rank disagree with the row's position on the page.
         */
        @Test
        void ranksStayContiguousAfterAFlaggedPlayerIsRemoved() {
            flagWithSuspicion(96.0);

            List<Integer> ranks = service.getTopPlayersByPointsPaged(0, 10).stream()
                    .map(LeaderboardService.LeaderboardSnapshot::rank).toList();

            assertEquals(List.of(1, 2), ranks);
        }

        /** The page offset still applies once rows have been filtered out. */
        @Test
        void filteringDoesNotBreakRankNumberingOnLaterPages() {
            flagWithSuspicion(96.0);

            List<Integer> ranks = service.getTopPlayersByPointsPaged(3, 5).stream()
                    .map(LeaderboardService.LeaderboardSnapshot::rank).toList();

            assertEquals(List.of(16, 17), ranks);
        }
    }

    // ==================================================================
    // 2. A page must be a page, not a full table read
    // ==================================================================

    @Nested
    @DataJpaTest
    @ActiveProfiles("test")
    class CollectionFetchPagination {

        private static final int POPULATION = 24;
        private static final int PAGE_SIZE = 3;

        @Autowired private UserRepository userRepository;
        @Autowired private TestEntityManager em;

        @BeforeEach
        void populate() {
            for (int i = 1; i <= POPULATION; i++) {
                User u = new User(null, "player-" + i);
                u.setPoints(i * 100);
                u.setCosmicDrip(1000 + i);   // all comfortably above the drip board's threshold
                // Two wins, not a win and a loss: incrementDuelLosses() appends a SECOND
                // MatchRecord on top of the one recordMatch already added (a pre-existing
                // model defect, outside this slice), which would make the expected
                // collection size a moving target here.
                u.recordMatch(true);         // gives every user matchHistory rows to fetch-join
                u.recordMatch(true);
                em.persist(u);
            }
            em.flush();
            em.clear();
        }

        private Statistics statistics() {
            Statistics stats = em.getEntityManager().getEntityManagerFactory()
                    .unwrap(SessionFactory.class).getStatistics();
            stats.setStatisticsEnabled(true);
            stats.clear();
            return stats;
        }

        /** Captures Hibernate's own complaint while {@code body} runs. */
        private List<String> hibernateWarningsDuring(Runnable body) {
            LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
            ch.qos.logback.classic.Logger hibernate = ctx.getLogger("org.hibernate");
            Level previous = hibernate.getLevel();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            hibernate.setLevel(Level.WARN);
            hibernate.addAppender(appender);
            try {
                body.run();
            } finally {
                hibernate.detachAppender(appender);
                hibernate.setLevel(previous);
            }
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }

        /**
         * Reproduction, measured rather than argued. Before the fix this asked for the top 3
         * of 24 players and Hibernate loaded all 24 {@code User} entities
         * ({@code entityLoadCount} = 24, i.e. the whole table) while logging HHH90003004
         * "firstResult/maxResults specified with collection fetch; applying in memory".
         * The ratio is the population size, so on production data this is a full table read
         * per leaderboard request.
         */
        @Test
        void pointsPageLoadsOnlyThePageNotTheWholeTable() {
            Statistics stats = statistics();
            List<User> page = new ArrayList<>();

            List<String> warnings = hibernateWarningsDuring(
                    () -> page.addAll(userRepository.findTopUsersByPoints(PageRequest.of(0, PAGE_SIZE))));

            assertEquals(PAGE_SIZE, page.size(), "page size");
            assertEquals(PAGE_SIZE, stats.getEntityLoadCount(),
                    "a page of " + PAGE_SIZE + " must load " + PAGE_SIZE + " users out of " + POPULATION
                            + ", not the whole table");
            assertTrue(warnings.stream().noneMatch(w -> w.contains("HHH90003004")),
                    "Hibernate is still paginating in memory: " + warnings);
        }

        /** Same defect on the cosmic-drip board, which fetch-joins {@code achievements}. */
        @Test
        void dripPageLoadsOnlyThePageNotTheWholeTable() {
            Statistics stats = statistics();
            List<User> page = new ArrayList<>();

            List<String> warnings = hibernateWarningsDuring(
                    () -> page.addAll(userRepository.findTopCosmicDrippers(50, PageRequest.of(0, PAGE_SIZE))));

            assertEquals(PAGE_SIZE, page.size(), "page size");
            assertEquals(PAGE_SIZE, stats.getEntityLoadCount(),
                    "a page of " + PAGE_SIZE + " must load " + PAGE_SIZE + " users out of " + POPULATION);
            assertTrue(warnings.stream().noneMatch(w -> w.contains("HHH90003004")),
                    "Hibernate is still paginating in memory: " + warnings);
        }

        /**
         * The two-query rewrite must not change what the caller sees: same ordering, same
         * page boundaries, no duplicates and no gaps across consecutive pages.
         */
        @Test
        void pagingStillWalksTheWholeRankingInPointsOrder() {
            List<String> walked = new ArrayList<>();
            for (int p = 0; p * PAGE_SIZE < POPULATION; p++) {
                userRepository.findTopUsersByPoints(PageRequest.of(p, PAGE_SIZE))
                        .forEach(u -> walked.add(u.getUsername()));
            }

            List<String> expected = new ArrayList<>();
            for (int i = POPULATION; i >= 1; i--) expected.add("player-" + i);  // points = i * 100 DESC

            assertEquals(expected, walked);
        }

        /** A page past the end is empty, not an error and not a wrapped-around first page. */
        @Test
        void pagingPastTheEndIsEmpty() {
            assertEquals(List.of(), userRepository.findTopUsersByPoints(PageRequest.of(99, PAGE_SIZE)));
            assertEquals(List.of(), userRepository.findTopCosmicDrippers(50, PageRequest.of(99, PAGE_SIZE)));
        }

        /**
         * The whole point of the {@code @EntityGraph} is that the collection comes back
         * initialised; the two-query rewrite has to keep it, or every row on the leaderboard
         * turns into a lazy-load round trip (or a LazyInitializationException outside the
         * transaction).
         */
        @Test
        void theFetchGraphStillInitialisesTheCollection() {
            List<User> page = userRepository.findTopUsersByPoints(PageRequest.of(0, PAGE_SIZE));
            assertFalse(page.isEmpty());

            // Detach everything: an uninitialised lazy @ElementCollection can no longer be
            // loaded after this point, so reading it throws instead of silently working.
            em.clear();

            for (User u : page) {
                assertEquals(2, u.getMatchHistory().size(),
                        "matchHistory must be fetched with the page, not lazily afterwards");
            }
        }
    }
}
