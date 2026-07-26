package com.xai.sudokupro.config;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.LeaderboardService;
import com.xai.sudokupro.service.economy.EconomyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Defect class: <b>annotations that were inert, and the correctness bugs they were hiding.</b>
 *
 * <p>Roughly a hundred {@code @Cacheable}/{@code @CacheEvict} annotations sat across the
 * repositories and {@code LeaderboardService} with no {@code @EnableCaching} anywhere, so
 * Spring created no {@code CacheManager}, registered no cache interceptor, and none of them
 * ever fired. That is the "written but never connected" pattern — but turning it on blind
 * would have been worse than leaving it off, because several of those annotations were
 * <em>wrong</em> and were only harmless while dead:
 *
 * <ul>
 *   <li>{@code UserRepository.findByUsername} was {@code @Cacheable}. It caches
 *       {@code Optional.empty()} too, and the writers that create a user
 *       ({@code AccountService.register}, {@code EconomyService.walletFor}) go through
 *       {@code save()}, which no eviction covered. Registering a player therefore poisoned the
 *       cache with "no such user" and the next login failed — and wallet provisioning failed
 *       with a hard 500.</li>
 *   <li>{@code GameRepository.findByGameId} was {@code @Cacheable}. That is the read-through
 *       path {@code GameService.getGame} uses to rehydrate a board evicted from the per-pod
 *       map, so a cached copy means silently losing every move and hint made since.</li>
 *   <li>Leaderboard queries were cached at BOTH the service and repository layer, and
 *       {@code updateScore}'s {@code @CacheEvict} only knows about the service layer — so the
 *       eviction would have looked correct and changed nothing.</li>
 *   <li>Every {@code Pageable} key used {@code #pageable.pageNumber} alone, so a size-1 and a
 *       size-100 read of page 0 collide.</li>
 * </ul>
 *
 * <p>Each test below asserts something that can only be true when caching is genuinely
 * active AND the annotation it covers is correct.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:cachewiring;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "sudokupro.ui.enabled=false",
    "sudokupro.security.register.max-attempts=1000000",
    // A cache whose only purpose is to prove entries really do expire.
    "sudokupro.cache.ttl-seconds.ttlProbe=1"
})
class CachingWiringTest {

    @Autowired private ApplicationContext context;
    @Autowired private CacheManager cacheManager;
    @Autowired private LeaderboardService leaderboardService;
    @Autowired private UserRepository userRepository;
    @Autowired private GameRepository gameRepository;
    @Autowired private EconomyService economyService;
    @Autowired private MockMvc mockMvc;

    private static String unique(String prefix) {
        return prefix + Long.toString(ThreadLocalRandom.current().nextLong(1L << 44), 36);
    }

    private User player(String name, int points) {
        User u = new User(null, name);
        u.setPoints(points);
        return userRepository.saveAndFlush(u);
    }

    // ---- wiring -------------------------------------------------------------

    /**
     * The wiring assertion. Without {@code @EnableCaching} there is no {@code CacheManager}
     * bean at all, and even with one present a {@code NoOpCacheManager} would accept every
     * {@code put} and return nothing — so this checks that a real entry lands in a real cache
     * as a side effect of a normal service call.
     */
    @Test
    void cachingIsEnabledAndTheHttpLeaderboardIsActuallyCached() {
        assertThat(context.getBeanNamesForType(CacheManager.class))
            .as("no CacheManager bean: @EnableCaching is missing, so every @Cacheable in the "
                + "project is decoration")
            .isNotEmpty();
        assertThat(cacheManager)
            .as("a NoOpCacheManager silently discards everything — present but useless")
            .isNotInstanceOf(NoOpCacheManager.class);

        player(unique("cw"), 4242);
        leaderboardService.getPublicLeaderboard(7);

        Cache cache = cacheManager.getCache("publicLeaderboard");
        assertThat(cache).as("the publicLeaderboard cache was never created").isNotNull();
        assertThat(cache.get("public-7"))
            .as("calling the @Cacheable method stored nothing — the cache interceptor is not "
                + "in the call path")
            .isNotNull();
    }

    /** A cache with no expiry and no eviction is a memory leak that serves stale data forever. */
    @Test
    void cacheEntriesExpire() throws Exception {
        Cache probe = cacheManager.getCache("ttlProbe");
        assertThat(probe).isNotNull();
        probe.put("k", "v");
        assertThat(probe.get("k")).isNotNull();

        Thread.sleep(1400);

        assertThat(probe.get("k"))
            .as("entry outlived its configured 1s TTL — the cache never expires anything")
            .isNull();
    }

    // ---- the armed landmine -------------------------------------------------

    /**
     * {@code GET /api/leaderboard} is the only leaderboard exposed over HTTP, and it is served
     * by {@code @Cacheable("publicLeaderboard")}. Enabling caching arms it: if scoring does
     * not evict, the public leaderboard freezes on its first warm read forever.
     *
     * <p>This also catches the double-caching trap. {@code getPublicLeaderboard} delegates to
     * {@code LeaderboardRepository.findTopUsersByPoints}, which carried its own
     * {@code @Cacheable} that {@code updateScore}'s evict list knows nothing about — so the
     * service-level eviction would fire, look correct, and the stale rows would come straight
     * back up from the layer below.
     */
    @Test
    void scoringEvictsTheLeaderboardAllTheWayDown() {
        String name = unique("evict");
        User p = player(name, 90_000);       // high enough to top this shared H2 instance

        List<LeaderboardService.LeaderboardSnapshot> warm = leaderboardService.getPublicLeaderboard(3);
        assertThat(warm).extracting(LeaderboardService.LeaderboardSnapshot::username).contains(name);
        int pointsWhenWarm = warm.stream()
            .filter(s -> name.equals(s.username())).findFirst().orElseThrow().sortValue();

        leaderboardService.updateScore(p.getId(), 5_000);

        int pointsAfter = leaderboardService.getPublicLeaderboard(3).stream()
            .filter(s -> name.equals(s.username())).findFirst().orElseThrow().sortValue();

        assertThat(pointsAfter)
            .as("the public leaderboard served stale points after a score update — either the "
                + "@CacheEvict misses this cache, or a repository-level cache underneath it "
                + "hands the same rows back")
            .isEqualTo(pointsWhenWarm + 5_000);
    }

    /**
     * Registration writes through {@code save()}, which no eviction covered, after a lookup
     * that cached {@code Optional.empty()}. With caching on and {@code findByUsername} still
     * {@code @Cacheable}, the account exists in the database and does not exist as far as
     * authentication is concerned: the login returns 401 forever.
     */
    @Test
    void aFreshlyRegisteredPlayerCanAuthenticate() throws Exception {
        String name = unique("reg");

        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content("{\"username\":\"" + name + "\",\"password\":\"password123\"}"))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/economy/wallet").with(httpBasic(name, "password123")))
            .andExpect(status().isOk());
    }

    /**
     * The same negative-caching bug on the gameplay path. {@code EconomyService.walletFor} is
     * a check-then-insert: a cached {@code Optional.empty()} makes the second call re-insert,
     * hit the V9 unique index, and then fail its own recovery read — surfacing as
     * "wallet vanished after a unique-constraint conflict", a 500 on an ordinary hint request.
     */
    @Test
    void walletProvisioningSurvivesRepeatedLookups() {
        String name = unique("wallet");

        User first = economyService.walletFor(name);
        assertThatCode(() -> {
            User second = economyService.walletFor(name);
            assertThat(second.getId()).isEqualTo(first.getId());
        }).doesNotThrowAnyException();
    }

    /**
     * The live game read path must not be cached. {@code GameService.getGame} calls
     * {@code findByGameId} to rehydrate a board that has been evicted from the per-pod map;
     * a cached copy means every move and hint since the cache filled is silently discarded.
     */
    @Test
    void theLiveGameReadPathIsNotCached() {
        String gameId = unique("game-");
        SudokuBoard board = new SudokuBoard(1, false, false, 0, gameId);
        board.setPlayerId("cachetest");
        gameRepository.saveAndFlush(board);

        SudokuBoard warm = gameRepository.findByGameId(gameId);
        assertThat(warm).isNotNull();
        int before = warm.getHintCount();

        // Mutate through a DIFFERENT instance. Writing through `warm` would mutate the very
        // object a cache is holding, so a stale cache would still return the new value and
        // the assertion would pass with the bug present.
        SudokuBoard other = gameRepository.findById(warm.getId()).orElseThrow();
        other.incrementHintCount();
        gameRepository.saveAndFlush(other);

        assertThat(gameRepository.findByGameId(gameId).getHintCount())
            .as("the board was served from cache — a rehydrated game would lose every move "
                + "and hint made since the cache filled")
            .isEqualTo(before + 1);
    }

    /**
     * Every paged cache key used {@code #pageable.pageNumber} alone, so "page 0, size 1" and
     * "page 0, size 5" are the same key and the second caller gets the first caller's page.
     */
    @Test
    void pageSizeIsPartOfThePagedCacheKey() {
        for (int i = 0; i < 6; i++) {
            player(unique("pg"), 1000 + i);
        }

        List<User> one = userRepository.findTopUsersNative(PageRequest.of(0, 1));
        List<User> five = userRepository.findTopUsersNative(PageRequest.of(0, 5));

        assertThat(one).hasSize(1);
        assertThat(five)
            .as("a page-size-5 read returned the cached page-size-1 result: the cache key "
                + "ignores the page size")
            .hasSize(5);
    }
}
