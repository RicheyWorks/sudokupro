package com.xai.sudokupro.client.net;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * How hard, and how often, to try getting the gameplay channel back.
 *
 * <p>Pure decision logic with no clock, no threads and no sockets, so it can be
 * tested exhaustively; {@link GameChannel} owns the timing and the I/O.
 *
 * <p>Two rules earn their keep:
 * <ul>
 *   <li><b>Capped exponential backoff.</b> A server restart is back in seconds; a
 *       laptop that closed its lid is not. A fixed short interval turns the second
 *       case into a spin loop against a dead host.</li>
 *   <li><b>Some failures are permanent.</b> The server closes with {@code 1008} when
 *       it will never accept this join — an unknown game id, or a competitive board
 *       that may not be spectated — and answers the handshake with 401/403 when the
 *       credentials are wrong. Retrying those is pure noise; the loop must stop and
 *       say why, leaving the manual reconnect for when the player has fixed it.</li>
 * </ul>
 */
public final class ReconnectPolicy {

    /** WebSocket close code the server uses for refusals a retry cannot change. */
    public static final int POLICY_VIOLATION = 1008;

    /** Six tries over roughly a minute: survives a server restart, gives up on a dead network. */
    public static final ReconnectPolicy DEFAULT =
        new ReconnectPolicy(6, Duration.ofSeconds(1), Duration.ofSeconds(30), 2.0);

    private final int maxAttempts;
    private final long initialMillis;
    private final long maxMillis;
    private final double multiplier;

    public ReconnectPolicy(int maxAttempts, Duration initialDelay, Duration maxDelay, double multiplier) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (initialDelay.isNegative() || initialDelay.isZero()) {
            throw new IllegalArgumentException("initialDelay must be positive");
        }
        if (maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must be >= initialDelay");
        }
        if (multiplier < 1.0) throw new IllegalArgumentException("multiplier must be >= 1.0");
        this.maxAttempts = maxAttempts;
        this.initialMillis = initialDelay.toMillis();
        this.maxMillis = maxDelay.toMillis();
        this.multiplier = multiplier;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Delay before the given 1-based attempt: {@code initial * multiplier^(attempt-1)},
     * never longer than {@code maxDelay}.
     */
    public Duration delayFor(int attempt) {
        if (attempt < 1) throw new IllegalArgumentException("attempt must be >= 1");
        double millis = initialMillis;
        for (int i = 1; i < attempt && millis < maxMillis; i++) {
            millis *= multiplier;
        }
        // One cap, in one place. An earlier version also returned early inside the
        // loop, which meant the ceiling was expressed twice and a mutation to either
        // copy left the other one still enforcing it — the shape of a rule nothing
        // can be shown to depend on.
        return Duration.ofMillis(Math.min(maxMillis, (long) millis));
    }

    /** Whether attempt {@code attempt} should be made after the link closed with {@code closeStatus}. */
    public boolean shouldRetryAfterClose(int attempt, int closeStatus) {
        if (closeStatus == POLICY_VIOLATION) return false;
        return attempt <= maxAttempts;
    }

    /** Whether attempt {@code attempt} should be made after a reconnect threw {@code failure}. */
    public boolean shouldRetryAfterFailure(int attempt, Throwable failure) {
        if (isPermanent(failure)) return false;
        return attempt <= maxAttempts;
    }

    /**
     * True for failures no amount of waiting will clear: rejected credentials
     * (401) and rejected access (403).
     */
    public static boolean isPermanent(Throwable failure) {
        Throwable t = unwrap(failure);
        return t instanceof ApiException api && api.isAuthFailure();
    }

    private static Throwable unwrap(Throwable t) {
        Throwable current = t;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
               && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
