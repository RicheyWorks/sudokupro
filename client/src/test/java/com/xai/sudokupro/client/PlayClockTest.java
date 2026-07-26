package com.xai.sudokupro.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the defect class "a control that reports doing something it does not do".
 *
 * <p>Pause did not pause. The timer thread kept a single {@code startTime} and
 * computed {@code now - startTime}; the pause flag only suppressed the label
 * repaint. So the clock ran through the whole break and the display jumped forward
 * by its entire length the moment play resumed — on a puzzle whose score and
 * leaderboard placement are the time.
 *
 * <p>Every test here drives a fake clock, so it measures the arithmetic rather
 * than waiting on a real one.
 */
class PlayClockTest {

    /** A clock the test moves by hand. */
    private static final class FakeTicker implements PlayClock.Ticker {
        long now = 1_000_000L;

        @Override
        public long nowMillis() {
            return now;
        }

        void advance(long millis) {
            now += millis;
        }
    }

    private final FakeTicker ticker = new FakeTicker();
    private final PlayClock clock = new PlayClock(ticker);

    @Test
    void elapsedCountsFromStart() {
        clock.start();
        ticker.advance(65_000);
        assertEquals(65_000, clock.elapsedMillis());
        assertEquals("01:05", clock.elapsedText());
    }

    /** The reproduction: pause, walk away for five minutes, come back. */
    @Test
    void pausedTimeIsNotCharged() {
        clock.start();
        ticker.advance(30_000);
        clock.pause();
        ticker.advance(300_000);     // five minutes away from the desk
        clock.resume();
        ticker.advance(10_000);

        assertEquals(40_000, clock.elapsedMillis(),
            "Only real play counts — the break must not appear on the clock");
        assertEquals("00:40", clock.elapsedText());
    }

    @Test
    void elapsedIsFrozenWhilePaused() {
        clock.start();
        ticker.advance(20_000);
        clock.pause();

        long atPause = clock.elapsedMillis();
        ticker.advance(90_000);

        assertEquals(atPause, clock.elapsedMillis(), "A paused clock must not advance");
        assertEquals(20_000, clock.elapsedMillis());
    }

    /**
     * The pause button is a toggle, but a stray double-fire (a keyboard repeat, a
     * duplicated handler) must not restart the break and lose the time in between.
     */
    @Test
    void aSecondPauseIsANoOp() {
        clock.start();
        ticker.advance(10_000);
        clock.pause();
        ticker.advance(5_000);
        clock.pause();               // second press while already paused
        ticker.advance(5_000);
        clock.resume();

        assertEquals(10_000, clock.elapsedMillis());
        assertFalse(clock.isPaused());
    }

    @Test
    void resumeWhileRunningIsANoOp() {
        clock.start();
        ticker.advance(10_000);
        clock.resume();
        ticker.advance(5_000);

        assertEquals(15_000, clock.elapsedMillis());
    }

    @Test
    void repeatedPauseAndResumeAccumulateEveryBreak() {
        clock.start();
        for (int i = 0; i < 3; i++) {
            ticker.advance(10_000);
            clock.pause();
            ticker.advance(60_000);
            clock.resume();
        }
        assertEquals(30_000, clock.elapsedMillis());
    }

    @Test
    void startResetsEverythingIncludingAccumulatedPauses() {
        clock.start();
        ticker.advance(10_000);
        clock.pause();
        ticker.advance(10_000);

        clock.start();
        ticker.advance(3_000);

        assertFalse(clock.isPaused(), "A new game starts unpaused");
        assertEquals(3_000, clock.elapsedMillis());
    }

    @Test
    void anUnstartedClockReadsZero() {
        assertEquals(0, clock.elapsedMillis());
        assertFalse(clock.isRunning());
        assertEquals("00:00", clock.elapsedText());
    }

    /**
     * Minutes are not wrapped at 60: a 75-minute Insane game reads 75:00, not
     * 15:00. Formatting is shared with the "Solved in …" line, where wrapping would
     * quietly understate a slow solve.
     */
    @Test
    void formatDoesNotWrapMinutesAtSixty() {
        assertEquals("75:03", PlayClock.format(75L * 60_000 + 3_000));
        assertEquals("00:00", PlayClock.format(0));
        assertEquals("00:00", PlayClock.format(-5_000));
        assertEquals("09:59", PlayClock.format(599_000));
    }
}
