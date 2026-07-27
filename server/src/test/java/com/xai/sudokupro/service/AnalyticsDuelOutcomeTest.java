package com.xai.sudokupro.service;

import com.xai.sudokupro.model.GameEvent;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.util.SecureRandomGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * End-to-end through the analytics maps that were unreachable until pass 15:
 * recordEvent's DUEL_WIN / DUEL_LOSS / STREAK_UPDATE branches dispatched on strings
 * that no {@link GameEvent.EventType} constant could produce, so
 * {@code getPlayerWinRates()} and {@code getDuelWins()} were permanently empty —
 * the {@code sudokupro.duel.win.rate.average} gauge read 0.0 forever, and the
 * anti-cheat cross-check compared persisted wins against a map that could never
 * match. These tests pin the now-live path from event to consumer.
 */
class AnalyticsDuelOutcomeTest {

    private AnalyticsService analytics;

    @BeforeEach
    void setUp() {
        analytics = new AnalyticsService(
            new AISolverService(new SecureRandomGenerator(new SimpleMeterRegistry())),
            mock(UserRepository.class), mock(GameRepository.class));
    }

    private void duel(String winner, String loser) {
        analytics.recordEvent(new GameEvent(GameEvent.EventType.DUEL_WIN, winner, Map.of("opponent", loser)));
        analytics.recordEvent(new GameEvent(GameEvent.EventType.DUEL_LOSS, loser, Map.of("opponent", winner)));
    }

    @Test
    void duelOutcomesProduceWinCountsAndWinRates() {
        duel("alice", "bob");
        duel("alice", "bob");
        duel("bob", "alice");

        assertEquals(2, analytics.getDuelWins().get("alice"));
        assertEquals(1, analytics.getDuelWins().get("bob"));

        Map<String, Double> rates = analytics.getPlayerWinRates();
        assertEquals(2.0 / 3.0, rates.get("alice"), 1e-9);
        assertEquals(1.0 / 3.0, rates.get("bob"), 1e-9);
    }

    @Test
    void playersWithNoDuelHistoryStayOutOfTheRateMap() {
        analytics.recordEvent(new GameEvent(GameEvent.EventType.SOLVE, "solo",
            Map.of("solveTimeSeconds", "120")));

        assertTrue(analytics.getPlayerWinRates().isEmpty(),
            "solo players must not appear with a fabricated 0% or 100% duel rate");
    }

    @Test
    void streakUpdatesKeepTheBestStreakPerPlayer() {
        analytics.recordEvent(new GameEvent(GameEvent.EventType.STREAK_UPDATE, "alice", Map.of("streak", "3")));
        analytics.recordEvent(new GameEvent(GameEvent.EventType.STREAK_UPDATE, "alice", Map.of("streak", "7")));
        analytics.recordEvent(new GameEvent(GameEvent.EventType.STREAK_UPDATE, "alice", Map.of("streak", "5")));

        assertEquals(7, analytics.getStreakRecords().get("alice"),
            "the record is the MAX streak seen, not the latest");
    }
}
