package com.xai.sudokupro.service;

import com.xai.sudokupro.model.SudokuBoard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cosmic timer of SudokuPro's event galaxy.
 * Triggers daily challenges, cosmic duels, and drip showdowns with galactic precision—App Store-ready orchestration.
 */
@Service
public class EventScheduler {
    private static final Logger logger = LoggerFactory.getLogger(EventScheduler.class);
    private static final long DAILY_CHALLENGE_INTERVAL_MS = 24 * 60 * 60 * 1000; // 24 hours
    private static final long COSMIC_DUEL_INTERVAL_MS = 60 * 60 * 1000; // 1 hour
    private static final long DRIP_SHOWDOWN_INTERVAL_MS = 15 * 60 * 1000; // 15 minutes
    private static final int MAX_RETRIES = 3; // Retry attempts for event triggers

    private final EventEngine eventEngine;
    private final ConcurrentHashMap<String, LocalDateTime> lastEventTriggers = new ConcurrentHashMap<>(); // Event type -> last trigger time

    @Autowired
    public EventScheduler(EventEngine eventEngine) {
        this.eventEngine = Objects.requireNonNull(eventEngine, "EventEngine cannot be null");
        initializeLastEventTriggers();
        logger.info("EventScheduler initialized with cosmic precision");
    }

    private void initializeLastEventTriggers() {
        lastEventTriggers.put("daily_challenge", LocalDateTime.now().minusHours(24));
        lastEventTriggers.put("cosmic_duel", LocalDateTime.now().minusHours(1));
        lastEventTriggers.put("drip_showdown", LocalDateTime.now().minusMinutes(15));
    }

    @Scheduled(fixedRate = DAILY_CHALLENGE_INTERVAL_MS)
    @Retryable(maxAttempts = MAX_RETRIES, backoff = @Backoff(delay = 5000))
    public void triggerDailyChallenge() {
        MDC.put("thread", "daily-challenge");
        logger.info("Triggering cosmic daily challenge—drip levels rising!");
        try {
            LocalDateTime lastTrigger = lastEventTriggers.getOrDefault("daily_challenge", LocalDateTime.MIN);
            if (LocalDateTime.now().isAfter(lastTrigger.plusHours(23))) { // Ensure ~24-hour gap
                eventEngine.startCosmicEvents(); // Trigger all events, including daily
                lastEventTriggers.put("daily_challenge", LocalDateTime.now());
                logger.info("Cosmic daily challenge triggered successfully");
            } else {
                logger.debug("Skipping daily challenge—last triggered at {}", lastTrigger);
            }
        } catch (Exception e) {
            logger.error("Failed to trigger cosmic daily challenge: {}", e.getMessage(), e);
            throw e; // Trigger retry
        } finally {
            MDC.clear();
        }
    }

    @Scheduled(fixedRate = COSMIC_DUEL_INTERVAL_MS)
    @Retryable(maxAttempts = MAX_RETRIES, backoff = @Backoff(delay = 5000))
    public void triggerCosmicDuel() {
        MDC.put("thread", "cosmic-duel");
        logger.info("Triggering cosmic duel—drip warriors assemble!");
        try {
            LocalDateTime lastTrigger = lastEventTriggers.getOrDefault("cosmic_duel", LocalDateTime.MIN);
            if (LocalDateTime.now().isAfter(lastTrigger.plusMinutes(55))) { // Ensure ~1-hour gap
                eventEngine.startCosmicEvents(); // Trigger all events, including duel
                lastEventTriggers.put("cosmic_duel", LocalDateTime.now());
                logger.info("Cosmic duel triggered successfully");
            } else {
                logger.debug("Skipping cosmic duel—last triggered at {}", lastTrigger);
            }
        } catch (Exception e) {
            logger.error("Failed to trigger cosmic duel: {}", e.getMessage(), e);
            throw e; // Trigger retry
        } finally {
            MDC.clear();
        }
    }

    @Scheduled(fixedRate = DRIP_SHOWDOWN_INTERVAL_MS)
    @Retryable(maxAttempts = MAX_RETRIES, backoff = @Backoff(delay = 5000))
    public void triggerDripShowdown() {
        MDC.put("thread", "drip-showdown");
        logger.info("Triggering drip showdown—cosmic chaos unleashed!");
        try {
            LocalDateTime lastTrigger = lastEventTriggers.getOrDefault("drip_showdown", LocalDateTime.MIN);
            if (LocalDateTime.now().isAfter(lastTrigger.plusMinutes(14))) { // Ensure ~15-minute gap
                eventEngine.startCosmicEvents(); // Trigger all events, including showdown
                lastEventTriggers.put("drip_showdown", LocalDateTime.now());
                logger.info("Drip showdown triggered successfully");
            } else {
                logger.debug("Skipping drip showdown—last triggered at {}", lastTrigger);
            }
        } catch (Exception e) {
            logger.error("Failed to trigger drip showdown: {}", e.getMessage(), e);
            throw e; // Trigger retry
        } finally {
            MDC.clear();
        }
    }

    public synchronized void triggerEventNow(String eventType) {
        validateEventType(eventType);
        MDC.put("thread", "event-" + eventType);
        logger.info("Manually triggering cosmic event: {}", eventType);
        try {
            eventEngine.startCosmicEvents(); // Trigger all events, relying on EventEngine's internal logic
            lastEventTriggers.put(eventType, LocalDateTime.now());
            logger.info("Cosmic event {} triggered manually", eventType);
        } catch (Exception e) {
            logger.error("Failed to manually trigger event {}: {}", eventType, e.getMessage(), e);
            throw new RuntimeException("Event trigger failed", e);
        } finally {
            MDC.clear();
        }
    }

    public synchronized Map<String, LocalDateTime> getLastEventTriggers() {
        return new ConcurrentHashMap<>(lastEventTriggers);
    }

    public synchronized Map<String, SudokuBoard> getActiveEvents() {
        return eventEngine.getActiveEvents().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getBoard(), (e1, e2) -> e1, ConcurrentHashMap::new));
    }

    private void validateEventType(String eventType) {
        if (!List.of("daily_challenge", "cosmic_duel", "drip_showdown").contains(eventType)) {
            logger.error("Invalid event type: {}", eventType);
            throw new IllegalArgumentException("Unknown event type: " + eventType);
        }
    }
}
