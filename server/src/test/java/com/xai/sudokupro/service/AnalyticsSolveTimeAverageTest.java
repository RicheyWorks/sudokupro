package com.xai.sudokupro.service;

import com.xai.sudokupro.model.GameEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Regression: {@code sudokupro.solve.time.average} did not report an average solve time.
 *
 * <p>{@code solveTimes} holds a CUMULATIVE sum of seconds per player — each SOLVE event
 * merges into the player's running total. {@code getAverageSolveTime()} averaged that
 * map's values directly, so each sample was one player's lifetime total rather than one
 * solve. The figure therefore climbed without bound as regulars kept playing: a player
 * who had finished forty puzzles contributed forty puzzles' worth of seconds as a single
 * data point. A dashboard reading "average solve time: 43 minutes" was really reporting
 * time-spent-per-player-since-restart.
 */
class AnalyticsSolveTimeAverageTest {

    private static AnalyticsService newService() {
        return new AnalyticsService(
            mock(AISolverService.class),
            mock(com.xai.sudokupro.repository.UserRepository.class),
            mock(com.xai.sudokupro.repository.GameRepository.class));
    }

    private static GameEvent solve(String playerId, long seconds) {
        return new GameEvent(GameEvent.EventType.SOLVE, playerId,
            Map.of("solveTimeSeconds", String.valueOf(seconds)));
    }

    @Test
    void averageIsPerSolveNotPerPlayerLifetimeTotal() {
        AnalyticsService analytics = newService();

        // One player grinds four 100-second solves; another does a single 100-second solve.
        for (int i = 0; i < 4; i++) analytics.recordEvent(solve("grinder", 100));
        analytics.recordEvent(solve("casual", 100));

        // Five solves, 500 seconds total -> 100s per solve.
        // The old implementation averaged {grinder: 400, casual: 100} and answered 250.
        assertEquals(100.0, analytics.getAverageSolveTime(), 0.001,
            "the average must be per solve, not an average of per-player totals");
    }

    @Test
    void theAverageDoesNotDriftUpwardAsOnePlayerKeepsPlaying() {
        AnalyticsService analytics = newService();

        analytics.recordEvent(solve("p1", 120));
        double afterOne = analytics.getAverageSolveTime();

        for (int i = 0; i < 30; i++) analytics.recordEvent(solve("p1", 120));
        double afterThirtyOne = analytics.getAverageSolveTime();

        assertEquals(afterOne, afterThirtyOne, 0.001,
            "a player repeating an identical solve time must not move the average; "
                + "it drifted from " + afterOne + " to " + afterThirtyOne + " before the fix");
    }

    @Test
    void noSolvesReportsZeroRatherThanDividingByZero() {
        assertEquals(0.0, newService().getAverageSolveTime(), 0.001);
    }

    @Test
    void resettingAnalyticsClearsTheSolveCountAlongsideTheTotals() {
        AnalyticsService analytics = newService();
        analytics.recordEvent(solve("p1", 300));
        assertTrue(analytics.getAverageSolveTime() > 0);

        analytics.resetAnalytics();

        assertEquals(0.0, analytics.getAverageSolveTime(), 0.001,
            "a stale solve count would divide the next total by the wrong denominator");
    }
}
