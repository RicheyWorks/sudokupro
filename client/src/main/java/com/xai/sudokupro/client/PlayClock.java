package com.xai.sudokupro.client;

/**
 * The game clock, with a Pause that actually pauses.
 *
 * <p>The desktop client's timer thread recorded a single {@code startTime} and
 * derived the display from {@code now - startTime}, with the pause flag only
 * suppressing the label update. So the clock never stopped: it merely stopped
 * being drawn, and the instant the player resumed, the display jumped forward by
 * the whole length of the break. A five-minute interruption cost five minutes on
 * a puzzle whose entire point is the time.
 *
 * <p>Pure logic with an injected time source and no threads, so the pause
 * arithmetic can be tested without waiting for a real clock — the reason the
 * original lived inside a JavaFX thread body and was never covered.
 */
public final class PlayClock {

    /** Time source, so tests can move time by hand. */
    @FunctionalInterface
    public interface Ticker {
        long nowMillis();
    }

    private final Ticker ticker;

    private long startedAt;
    private long pausedAt;
    private long pausedTotal;
    private boolean running;
    private boolean paused;

    public PlayClock() {
        this(System::currentTimeMillis);
    }

    public PlayClock(Ticker ticker) {
        this.ticker = ticker;
    }

    /** Starts (or restarts) from zero. */
    public synchronized void start() {
        startedAt = ticker.nowMillis();
        pausedAt = 0;
        pausedTotal = 0;
        running = true;
        paused = false;
    }

    /** Freezes the elapsed count. A second call is a no-op. */
    public synchronized void pause() {
        if (!running || paused) return;
        pausedAt = ticker.nowMillis();
        paused = true;
    }

    /** Resumes, charging nothing for the time spent paused. A call while running is a no-op. */
    public synchronized void resume() {
        if (!running || !paused) return;
        pausedTotal += ticker.nowMillis() - pausedAt;
        pausedAt = 0;
        paused = false;
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    /** Milliseconds of actual play, excluding every paused interval. */
    public synchronized long elapsedMillis() {
        if (!running) return 0L;
        long end = paused ? pausedAt : ticker.nowMillis();
        return Math.max(0L, end - startedAt - pausedTotal);
    }

    /** The elapsed time as {@code MM:SS} (minutes are not wrapped at 60). */
    public synchronized String elapsedText() {
        return format(elapsedMillis());
    }

    /** {@code MM:SS} for a duration in milliseconds. */
    public static String format(long millis) {
        long safe = Math.max(0L, millis);
        return String.format("%02d:%02d", safe / 60_000, (safe % 60_000) / 1000);
    }
}
