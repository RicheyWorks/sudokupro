package com.xai.sudokupro.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns Spring's cache abstraction on and supplies a cache manager that expires and bounds
 * what it holds.
 *
 * <h2>Why this exists</h2>
 * The project carried around a hundred {@code @Cacheable}/{@code @CacheEvict} annotations
 * across {@code UserRepository}, {@code GameRepository}, the leaderboard/economy/retention
 * repository fragments and {@code LeaderboardService} — and no {@code @EnableCaching}
 * anywhere. With no {@code CacheManager} bean and no cache interceptor in the call path, every
 * one of them was decoration.
 *
 * <h2>Why enabling it needed an audit first, not just a switch</h2>
 * Several of those annotations were incorrect, and were harmless only because they were dead.
 * Arming them unchanged would have shipped four live defects at once; each was fixed at the
 * annotation before this class was added, and each is pinned by a test in
 * {@code CachingWiringTest}:
 * <ul>
 *   <li><b>{@code findByUsername}</b> was cached. It caches {@code Optional.empty()} as well as
 *       hits, and the writers that create a user go through {@code save()}, which the three
 *       existing {@code @CacheEvict}s (all on bulk {@code @Modifying} updates) did not cover.
 *       Registering a player would have cached "no such user" and made the next login 401;
 *       {@code EconomyService.walletFor} would have re-inserted, hit the V9 unique index, and
 *       failed its own recovery read as a 500. Annotation removed: this is a single indexed
 *       lookup on the authentication and wallet paths, and no cache is worth that.</li>
 *   <li><b>{@code findByGameId}</b> was cached. It is the read-through that
 *       {@code GameService.getGame} uses to rehydrate a board evicted from the per-pod map, so
 *       a cached copy silently discards every move and hint since. Annotation removed.</li>
 *   <li><b>Leaderboards were cached twice</b>, at the service and again at the repository, and
 *       {@code LeaderboardService.updateScore}'s {@code @CacheEvict} only names the service
 *       caches. Eviction would have fired, looked correct, and the stale rows would have come
 *       straight back up from the layer below. The repository-level annotations on the queries
 *       the service already caches were removed, leaving one owner per cache.</li>
 *   <li><b>Keys computed from {@code now()}</b> ({@code countActiveUsersSince},
 *       {@code findActiveUnfinishedGames} — both called by schedulers with
 *       {@code LocalDateTime.now().minus(...)}) can never produce a hit, only a new entry per
 *       tick; the second holds up to 500 board entities each time. Annotations removed.</li>
 *   <li><b>Paged keys used {@code #pageable.pageNumber} alone</b>, so a size-1 and a size-100
 *       read of page 0 collide. Every such key now uses {@code #pageable}, whose
 *       {@code equals}/{@code hashCode} cover page, size and sort.</li>
 * </ul>
 *
 * <h2>Why every cache has a TTL</h2>
 * What remains is overwhelmingly read-only analytics and leaderboard queries with no mutation
 * path that could evict them. On a plain {@code ConcurrentMapCacheManager} those entries would
 * be stale forever and never freed. A TTL turns "stale forever" into "stale for at most N
 * seconds" and bounds the memory, which is what makes an eviction-less read cache defensible.
 * The leaderboard caches keep a real {@code @CacheEvict} on the write path as well, so the TTL
 * there is only a backstop.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private static final Logger logger = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    @ConfigurationProperties(prefix = "sudokupro.cache")
    public CacheSettings cacheSettings() {
        return new CacheSettings();
    }

    @Bean
    public CacheManager cacheManager(CacheSettings settings) {
        logger.info("Cache manager active: default TTL {}s, max {} entries per cache, overrides {}",
            settings.getDefaultTtlSeconds(), settings.getMaxEntries(), settings.getTtlSeconds());
        return new TtlCacheManager(settings);
    }

    /** Bindable settings for {@code sudokupro.cache.*}. */
    public static class CacheSettings {
        /**
         * Applies to any cache without an explicit override. Sixty seconds is chosen so that an
         * analytics read with no eviction path is at worst a minute behind the database.
         */
        private long defaultTtlSeconds = 60;

        /** Hard ceiling per cache, so a key that varies per call cannot exhaust the heap. */
        private int maxEntries = 2_000;

        /** Per-cache TTL overrides, keyed by cache name. */
        private Map<String, Long> ttlSeconds = new HashMap<>();

        public long getDefaultTtlSeconds() { return defaultTtlSeconds; }
        public void setDefaultTtlSeconds(long defaultTtlSeconds) { this.defaultTtlSeconds = defaultTtlSeconds; }
        public int getMaxEntries() { return maxEntries; }
        public void setMaxEntries(int maxEntries) { this.maxEntries = maxEntries; }
        public Map<String, Long> getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(Map<String, Long> ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    }

    /**
     * Creates {@link TtlCache} instances on demand.
     *
     * <p>Caches are created lazily rather than from a fixed name list on purpose: with a fixed
     * list, a {@code @Cacheable} naming a cache that is not on it throws
     * {@code IllegalArgumentException: Cannot find cache named ...} at runtime — turning a
     * missed configuration entry into a 500 on a working endpoint.
     */
    static final class TtlCacheManager implements CacheManager {

        private final CacheSettings settings;
        private final Map<String, Cache> caches = new ConcurrentHashMap<>();

        TtlCacheManager(CacheSettings settings) {
            this.settings = settings;
        }

        @Override
        public Cache getCache(String name) {
            return caches.computeIfAbsent(name, n -> new TtlCache(n,
                settings.getTtlSeconds().getOrDefault(n, settings.getDefaultTtlSeconds()) * 1000L,
                settings.getMaxEntries()));
        }

        @Override
        public Collection<String> getCacheNames() {
            return Collections.unmodifiableSet(caches.keySet());
        }
    }
}
