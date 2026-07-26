package com.xai.sudokupro.config;

import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.lang.Nullable;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An in-heap {@link Cache} with a per-entry time-to-live and a hard entry ceiling.
 *
 * <h2>Why not {@code ConcurrentMapCache}</h2>
 * Spring's built-in {@code ConcurrentMapCacheManager} never expires or bounds anything: an
 * entry lives until something explicitly evicts it. Most of this project's caches have no
 * eviction path at all (analytics and retention queries that nothing mutates through an
 * annotated method), so on that manager they would be a permanent memory leak serving
 * permanently stale data — strictly worse than the inert annotations we started with. A TTL
 * turns "stale forever" into "stale for at most N seconds", which is the property that makes
 * an eviction-less read cache safe.
 *
 * <h2>Why not Caffeine</h2>
 * Caffeine is the usual answer, but it is not a dependency of this project and adding one for
 * a few hundred cached rows is not worth the supply-chain and build cost. This is deliberately
 * small: expiry is checked on read, and the ceiling is enforced on write.
 *
 * <p>Eviction under pressure is insertion-ordered (oldest write first) rather than LRU. For a
 * bounded read cache in front of a database that is the conservative choice: it cannot keep a
 * hot-but-ancient entry alive past the point where a cold one would have been refreshed.
 *
 * <p>Null results are stored as a sentinel so that a method legitimately returning {@code null}
 * is cached rather than re-executed on every call (Spring's {@code allowNullValues} default).
 */
public class TtlCache implements Cache {

    /** Stands in for a cached {@code null}; distinguishes "cached nothing" from "not cached". */
    private static final Object NULL_SENTINEL = new Object();

    private final String name;
    private final long ttlMillis;
    private final int maxEntries;
    private final ConcurrentMap<Object, Entry> store = new ConcurrentHashMap<>();
    /** Monotonic write counter; gives insertion order without relying on clock resolution. */
    private final AtomicLong sequence = new AtomicLong();

    public TtlCache(String name, long ttlMillis, int maxEntries) {
        this.name = name;
        this.ttlMillis = ttlMillis;
        this.maxEntries = maxEntries;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return store;
    }

    @Override
    @Nullable
    public ValueWrapper get(Object key) {
        Entry entry = live(key);
        return entry == null ? null : new SimpleValueWrapper(unwrap(entry.value));
    }

    @Override
    @SuppressWarnings("unchecked")
    @Nullable
    public <T> T get(Object key, @Nullable Class<T> type) {
        Entry entry = live(key);
        if (entry == null) return null;
        Object value = unwrap(entry.value);
        if (value != null && type != null && !type.isInstance(value)) {
            throw new IllegalStateException(
                "Cached value for key '" + key + "' in cache '" + name + "' is not of type " + type);
        }
        return (T) value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        Entry entry = live(key);
        if (entry != null) return (T) unwrap(entry.value);
        T loaded;
        try {
            loaded = valueLoader.call();
        } catch (Exception e) {
            throw new ValueRetrievalException(key, valueLoader, e);
        }
        put(key, loaded);
        return loaded;
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        store.put(key, new Entry(value == null ? NULL_SENTINEL : value,
            System.currentTimeMillis() + ttlMillis, sequence.incrementAndGet()));
        enforceCeiling();
    }

    @Override
    @Nullable
    public ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
        ValueWrapper existing = get(key);
        if (existing != null) return existing;
        put(key, value);
        return null;
    }

    @Override
    public void evict(Object key) {
        store.remove(key);
    }

    @Override
    public boolean evictIfPresent(Object key) {
        return store.remove(key) != null;
    }

    @Override
    public void clear() {
        store.clear();
    }

    @Override
    public boolean invalidate() {
        boolean hadEntries = !store.isEmpty();
        store.clear();
        return hadEntries;
    }

    /** Live entry for {@code key}, removing it first if it has expired. */
    @Nullable
    private Entry live(Object key) {
        Entry entry = store.get(key);
        if (entry == null) return null;
        if (System.currentTimeMillis() >= entry.expiresAtMillis) {
            // Conditional remove: never discard a fresher value a concurrent put installed
            // between the read above and here.
            store.remove(key, entry);
            return null;
        }
        return entry;
    }

    @Nullable
    private static Object unwrap(Object value) {
        return value == NULL_SENTINEL ? null : value;
    }

    private void enforceCeiling() {
        if (store.size() <= maxEntries) return;
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> now >= e.getValue().expiresAtMillis);
        while (store.size() > maxEntries) {
            Map.Entry<Object, Entry> oldest = store.entrySet().stream()
                .min(java.util.Comparator.comparingLong(e -> e.getValue().sequence))
                .orElse(null);
            if (oldest == null) return;
            store.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private static final class Entry {
        final Object value;
        final long expiresAtMillis;
        final long sequence;

        Entry(Object value, long expiresAtMillis, long sequence) {
            this.value = value;
            this.expiresAtMillis = expiresAtMillis;
            this.sequence = sequence;
        }
    }
}
