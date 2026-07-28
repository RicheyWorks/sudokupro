package com.xai.sudokupro.service;

import com.xai.sudokupro.model.SudokuBoard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Cosmic timer of SudokuPro's event galaxy — daily challenges, cosmic duels, drip showdowns.
 *
 * <p><b>Firing is gated on a per-PERIOD claim, not an in-memory "last fired" timestamp.</b>
 * The previous guard was a {@code ConcurrentHashMap} seeded at construction to
 * {@code now - interval} — so on the very first tick after boot the elapsed check always
 * passed, and <em>every process restart fired a fresh daily challenge</em>. A routine deploy,
 * a crash-loop, or a rolling restart therefore minted extra daily puzzles at will. And the
 * map was per-pod, so on a multi-replica deployment all N replicas fired independently: the
 * "~24-hour gap" was enforced only within one pod's uptime and coordinated across none of
 * them.
 *
 * <p>The fix is the shape {@code SeasonService} already uses for exactly-once cross-replica
 * work: derive the period the event belongs to (the calendar day, the hour, the 15-minute
 * window), and claim it with a Redis {@code SET NX}. Whichever replica wins the claim fires;
 * everyone else — including the same pod on a later tick, and the same pod after a restart —
 * finds the marker present and skips. With Redis unreachable it degrades to a per-pod map that
 * still dedupes within a replica, so an outage can only cause an event to be MISSED on some
 * replicas, never fired twice on one.
 */
@Service
public class EventScheduler {
    private static final Logger logger = LoggerFactory.getLogger(EventScheduler.class);
    private static final long DAILY_CHALLENGE_INTERVAL_MS = 24 * 60 * 60 * 1000; // 24 hours
    private static final long COSMIC_DUEL_INTERVAL_MS = 60 * 60 * 1000; // 1 hour
    private static final long DRIP_SHOWDOWN_INTERVAL_MS = 15 * 60 * 1000; // 15 minutes
    private static final int MAX_RETRIES = 3; // Retry attempts for event triggers

    /** Redis SET NX marker per event-period; whoever sets it first owns that period. */
    private static final String CLAIM_KEY = "sudokupro:event:fired:";

    private final EventEngine eventEngine;
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final AtomicBoolean degradedLogged = new AtomicBoolean(false);

    /** Degrade-mode dedup: event type -> the last period id this pod fired for it. */
    private final Map<String, String> localClaimed = new ConcurrentHashMap<>();

    @Autowired
    public EventScheduler(EventEngine eventEngine, StringRedisTemplate redis, Clock clock) {
        this.eventEngine = Objects.requireNonNull(eventEngine, "EventEngine cannot be null");
        this.redis = Objects.requireNonNull(redis, "StringRedisTemplate cannot be null");
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
        logger.info("EventScheduler initialized with cosmic precision");
    }

    @Scheduled(fixedRate = DAILY_CHALLENGE_INTERVAL_MS)
    @Retryable(maxAttempts = MAX_RETRIES, backoff = @Backoff(delay = 5000))
    public void triggerDailyChallenge() {
        fireIfClaimed("daily_challenge", dayPeriod(), Duration.ofDays(2), eventEngine::triggerDailyChallenge);
    }

    @Scheduled(fixedRate = COSMIC_DUEL_INTERVAL_MS)
    @Retryable(maxAttempts = MAX_RETRIES, backoff = @Backoff(delay = 5000))
    public void triggerCosmicDuel() {
        fireIfClaimed("cosmic_duel", hourPeriod(), Duration.ofHours(3), eventEngine::triggerCosmicDuel);
    }

    @Scheduled(fixedRate = DRIP_SHOWDOWN_INTERVAL_MS)
    @Retryable(maxAttempts = MAX_RETRIES, backoff = @Backoff(delay = 5000))
    public void triggerDripShowdown() {
        fireIfClaimed("drip_showdown", quarterHourPeriod(), Duration.ofMinutes(45), eventEngine::triggerDripShowdown);
    }

    /**
     * Fires {@code action} exactly once for {@code periodId}, across every replica.
     *
     * <p>The claim happens BEFORE the action, so a claim that loses the race does no work.
     * {@code @Retryable} rethrow is preserved for a genuine engine failure — but note the
     * claim is only taken once the action is about to run, and is not released on failure:
     * a transient failure after a successful claim means that period is skipped rather than
     * retried into a double-fire on the retry. That is the safe direction for a
     * player-visible broadcast.
     */
    private void fireIfClaimed(String eventType, String periodId, Duration ttl, Runnable action) {
        MDC.put("thread", "event-" + eventType);
        try {
            if (!claimPeriod(eventType, periodId, ttl)) {
                logger.debug("Skipping {} for period {} — already claimed", eventType, periodId);
                return;
            }
            logger.info("Triggering cosmic event {} for period {}", eventType, periodId);
            action.run();
            logger.info("Cosmic event {} triggered for period {}", eventType, periodId);
        } catch (Exception e) {
            logger.error("Failed to trigger {}: {}", eventType, e.getMessage(), e);
            throw e; // @Retryable
        } finally {
            MDC.clear();
        }
    }

    /**
     * Claims {@code periodId} for {@code eventType}. Returns true for the one caller that
     * wins the period, false for everyone else.
     */
    private boolean claimPeriod(String eventType, String periodId, Duration ttl) {
        String key = CLAIM_KEY + eventType + ":" + periodId;
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", ttl));
        } catch (Exception e) {
            degrade(e);
            // Single-replica dedup: fire once per period on this pod. compute() makes the
            // check-and-set atomic so two scheduler threads cannot both win.
            boolean[] won = {false};
            localClaimed.compute(eventType, (k, prev) -> {
                if (periodId.equals(prev)) return prev;
                won[0] = true;
                return periodId;
            });
            return won[0];
        }
    }

    private String dayPeriod() {
        return "D:" + LocalDate.now(clock);
    }

    private String hourPeriod() {
        return "H:" + (Instant.now(clock).getEpochSecond() / 3600);
    }

    private String quarterHourPeriod() {
        return "Q:" + (Instant.now(clock).getEpochSecond() / 900);
    }

    /**
     * Fires an event immediately, on operator request, regardless of whether its period has
     * already been claimed — and then claims the current period so the scheduled tick does
     * not also fire it. A manual fire IS the fire for that period.
     */
    public synchronized void triggerEventNow(String eventType) {
        validateEventType(eventType);
        MDC.put("thread", "event-" + eventType);
        logger.info("Manually triggering cosmic event: {}", eventType);
        try {
            switch (eventType) {
                case "daily_challenge" -> {
                    eventEngine.triggerDailyChallenge();
                    claimPeriod(eventType, dayPeriod(), Duration.ofDays(2));
                }
                case "cosmic_duel" -> {
                    eventEngine.triggerCosmicDuel();
                    claimPeriod(eventType, hourPeriod(), Duration.ofHours(3));
                }
                case "drip_showdown" -> {
                    eventEngine.triggerDripShowdown();
                    claimPeriod(eventType, quarterHourPeriod(), Duration.ofMinutes(45));
                }
            }
            logger.info("Cosmic event {} triggered manually", eventType);
        } catch (Exception e) {
            logger.error("Failed to manually trigger event {}: {}", eventType, e.getMessage(), e);
            throw new RuntimeException("Event trigger failed", e);
        } finally {
            MDC.clear();
        }
    }

    /** Test/observability hook: the last period id this pod fired per event type (degrade mode). */
    public Map<String, String> getLocalClaims() {
        return new ConcurrentHashMap<>(localClaimed);
    }

    public synchronized Map<String, SudokuBoard> getActiveEvents() {
        return eventEngine.getActiveEvents().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getBoard(), (e1, e2) -> e1, ConcurrentHashMap::new));
    }

    private void degrade(Exception e) {
        if (degradedLogged.compareAndSet(false, true)) {
            logger.warn("EventScheduler: Redis unavailable — event dedup is per-pod only. "
                + "Fine for a single replica; multiple replicas may each fire once per period. Cause: {}",
                e.getMessage());
        }
    }

    private void validateEventType(String eventType) {
        if (!List.of("daily_challenge", "cosmic_duel", "drip_showdown").contains(eventType)) {
            logger.error("Invalid event type: {}", eventType);
            throw new IllegalArgumentException("Unknown event type: " + eventType);
        }
    }
}
