package com.xai.sudokupro.client.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Owns the gameplay channel's whole life: opening it, swapping it when the player
 * changes games, noticing when it dies, and getting it back.
 *
 * <p><b>The defect this class exists to fix.</b> Every game switch used to be
 * written as "close the old socket, then do the REST call, then open a new
 * socket":
 *
 * <pre>{@code
 * closeSocket();                       // the working channel is gone
 * BoardState state = api.getGame(id);  // 403 — competitive board, spectating refused
 * socket = api.openSocket(...);        // never reached
 * }</pre>
 *
 * <p>The REST call is exactly the step most likely to fail, and it failed
 * <em>after</em> the teardown. The field was left null, the local board was left
 * showing the game the player was still happily playing, and nothing anywhere in
 * the client ever called {@code openSocket} again except the six switch methods —
 * each of which starts by closing. So one refused spectate, one dropped Wi-Fi
 * moment, one 500 on resume, and the session was bricked: every move, undo, redo
 * and chat threw for the rest of the process's life, and the only escape was
 * abandoning the game.
 *
 * <p>Two changes close it, and both matter:
 * <ol>
 *   <li><b>Acquire, then swap.</b> {@link #connect(String)} opens the new link
 *       <em>first</em> and only closes the old one once the new one is up. A failed
 *       switch is now a no-op: the player is still connected to the game they were
 *       playing.</li>
 *   <li><b>A real way back.</b> An unexpected close starts an automatic backoff
 *       loop against the same game (see {@link ReconnectPolicy}), and
 *       {@link #reconnectNow()} is always available for the player to trigger by
 *       hand. Swallowing the error would have hidden the symptom and kept the
 *       dead end.</li>
 * </ol>
 *
 * <p>A <em>deliberate</em> close does not trigger any of this, which also removes
 * the "Connection to game lost" the player was shown on every intentional game
 * switch.
 */
public final class GameChannel implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(GameChannel.class);

    /** Delayed execution seam, so reconnect timing is deterministic under test. */
    public interface Scheduler {
        void schedule(Runnable task, Duration delay);

        default void shutdown() { }
    }

    private final GameLinkFactory factory;
    private final Consumer<Envelope> envelopes;
    private final ReconnectPolicy policy;
    private final Scheduler scheduler;

    private final Object lock = new Object();
    private GameLink link;
    private Attachment current;
    private String gameId;
    private int attempt;
    private ConnectionState state = ConnectionState.DISCONNECTED;

    private volatile Consumer<ConnectionState> onState = s -> { };
    private volatile BiConsumer<String, String> onNotice = (type, message) -> { };
    private volatile Runnable onResyncNeeded = () -> { };

    public GameChannel(GameLinkFactory factory, Consumer<Envelope> envelopes) {
        this(factory, envelopes, ReconnectPolicy.DEFAULT, daemonScheduler());
    }

    public GameChannel(GameLinkFactory factory, Consumer<Envelope> envelopes,
                       ReconnectPolicy policy, Scheduler scheduler) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.envelopes = Objects.requireNonNull(envelopes, "envelopes");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    // ---- wiring -------------------------------------------------------------

    /** Notified on every state transition (for a connection indicator). */
    public void setOnState(Consumer<ConnectionState> listener) {
        this.onState = listener != null ? listener : s -> { };
    }

    /** Player-facing messages: {@code ("ui"|"error", text)}. */
    public void setOnNotice(BiConsumer<String, String> listener) {
        this.onNotice = listener != null ? listener : (type, message) -> { };
    }

    /**
     * Run when the client's local board may have missed updates — after a
     * successful reconnect, and after a send that failed on the wire. The owner
     * asks the server for a full resync.
     */
    public void setOnResyncNeeded(Runnable listener) {
        this.onResyncNeeded = listener != null ? listener : () -> { };
    }

    // ---- lifecycle ----------------------------------------------------------

    /**
     * Joins {@code gameId}, replacing any current link.
     *
     * <p>The new link is opened before the old one is touched, so a failure here
     * throws with the channel exactly as it was.
     */
    public void connect(String gameId) {
        Objects.requireNonNull(gameId, "gameId");
        doConnect(gameId);
        notifyState(ConnectionState.CONNECTED);
    }

    private void doConnect(String targetGameId) {
        Attachment attachment = new Attachment();
        // Opened OUTSIDE the swap: if this throws, everything below is skipped and
        // the channel the player is using survives untouched.
        GameLink fresh = factory.open(targetGameId, envelopes, attachment);
        fresh.setSendFailureListener(this::sendFailed);

        GameLink previous;
        synchronized (lock) {
            previous = this.link;
            this.link = fresh;
            this.current = attachment;
            this.gameId = targetGameId;
            this.attempt = 0;
            this.state = ConnectionState.CONNECTED;
        }
        closeQuietly(previous);

        // The link may already have been closed by the server between the handshake
        // and this line (the server refuses a competitive spectate that way). The
        // attachment buffered it; replay it now that it is the installed link.
        attachment.install();
    }

    /** Whether a send would reach the server right now. */
    public boolean isConnected() {
        synchronized (lock) {
            return link != null && link.isOpen();
        }
    }

    public ConnectionState state() {
        synchronized (lock) {
            return state;
        }
    }

    /** The game this channel is on (or was last on), for a manual reconnect. */
    public String gameId() {
        synchronized (lock) {
            return gameId;
        }
    }

    /**
     * Sends one envelope.
     *
     * @throws ConnectionException when the channel is down — carrying the state so
     *                             the caller can say "reconnecting" rather than "invalid move"
     */
    public void send(String type, Object payload) {
        GameLink target;
        ConnectionState currentState;
        synchronized (lock) {
            target = link;
            currentState = state;
        }
        if (target == null || !target.isOpen()) {
            throw new ConnectionException(describeUnavailable(currentState), currentState);
        }
        target.send(type, payload);
    }

    private static String describeUnavailable(ConnectionState state) {
        return switch (state) {
            case RECONNECTING -> "Not connected — reconnecting to the game, your move was not sent";
            case FAILED -> "Not connected — reconnect to the game to keep playing";
            default -> "Not connected to a game — start, resume or rejoin one first";
        };
    }

    /**
     * Reconnects to the current game immediately, bypassing the backoff. This is
     * the player's manual escape hatch and the thing whose absence made a single
     * failed switch terminal.
     */
    public void reconnectNow() {
        String target;
        synchronized (lock) {
            target = gameId;
        }
        if (target == null) {
            throw new ConnectionException("No game to reconnect to — start or resume a game first");
        }
        doConnect(target);
        notifyState(ConnectionState.CONNECTED);
        onResyncNeeded.run();
    }

    /** Leaves the game deliberately: no "connection lost" notice, no reconnect attempts. */
    @Override
    public void close() {
        GameLink dying;
        synchronized (lock) {
            dying = link;
            link = null;
            current = null;          // makes every in-flight close/retry callback a no-op
            attempt = 0;
            state = ConnectionState.DISCONNECTED;
        }
        closeQuietly(dying);
        if (dying != null) notifyState(ConnectionState.DISCONNECTED);
    }

    /** Close plus scheduler teardown — application shutdown only. */
    public void shutdown() {
        close();
        scheduler.shutdown();
    }

    // ---- reconnect state machine -------------------------------------------

    /**
     * One opened link's close callback. Buffers a close that arrives before the
     * link has been installed, so the very common "handshake succeeds, server
     * refuses a moment later" case cannot be lost in the gap.
     */
    private final class Attachment implements CloseListener {
        private boolean installed;
        private boolean closed;
        private int code;
        private String reason;

        @Override
        public void onClose(int statusCode, String closeReason) {
            boolean handleNow;
            synchronized (this) {
                this.code = statusCode;
                this.reason = closeReason;
                this.closed = true;
                handleNow = installed;
            }
            if (handleNow) linkClosed(this, statusCode, closeReason);
        }

        void install() {
            int bufferedCode;
            String bufferedReason;
            synchronized (this) {
                installed = true;
                if (!closed) return;
                bufferedCode = code;
                bufferedReason = reason;
            }
            linkClosed(this, bufferedCode, bufferedReason);
        }
    }

    private void linkClosed(Attachment attachment, int statusCode, String reason) {
        String targetGame;
        int nextAttempt;
        synchronized (lock) {
            if (attachment != current) return;   // a link we already replaced, or a deliberate close
            targetGame = gameId;
            nextAttempt = ++attempt;
            state = ConnectionState.RECONNECTING;
        }
        if (!policy.shouldRetryAfterClose(nextAttempt, statusCode)) {
            fail(refusalMessage(statusCode, reason));
            return;
        }
        logger.info("Gameplay channel lost ({} {}), reconnect attempt {} to {}",
            statusCode, reason, nextAttempt, targetGame);
        onNotice.accept("ui", "Connection to the game was lost — reconnecting…");
        notifyState(ConnectionState.RECONNECTING);
        scheduler.schedule(() -> attemptReconnect(attachment, targetGame), policy.delayFor(nextAttempt));
    }

    private static String refusalMessage(int statusCode, String reason) {
        String detail = reason == null || reason.isBlank() ? "the server closed the channel" : reason;
        if (statusCode == ReconnectPolicy.POLICY_VIOLATION) {
            return "The server refused this game: " + detail;
        }
        return "Could not stay connected: " + detail;
    }

    private void attemptReconnect(Attachment expected, String targetGame) {
        synchronized (lock) {
            if (expected != current) return;   // superseded by an explicit connect() or close()
        }
        try {
            doConnect(targetGame);
            logger.info("Gameplay channel reconnected to {}", targetGame);
            onNotice.accept("ui", "Reconnected to the game");
            notifyState(ConnectionState.CONNECTED);
            onResyncNeeded.run();
        } catch (RuntimeException e) {
            int nextAttempt;
            synchronized (lock) {
                if (expected != current) return;
                nextAttempt = ++attempt;
            }
            if (!policy.shouldRetryAfterFailure(nextAttempt, e)) {
                fail("Could not reconnect to the game: " + e.getMessage());
                return;
            }
            logger.info("Reconnect attempt {} to {} failed: {}", nextAttempt, targetGame, e.getMessage());
            scheduler.schedule(() -> attemptReconnect(expected, targetGame), policy.delayFor(nextAttempt));
        }
    }

    private void fail(String message) {
        synchronized (lock) {
            state = ConnectionState.FAILED;
        }
        logger.warn("Gameplay channel gave up: {}", message);
        onNotice.accept("error", message);
        notifyState(ConnectionState.FAILED);
    }

    /**
     * An envelope the transport accepted and then failed to deliver. The local
     * board has already been updated optimistically, so the two sides have
     * diverged and only a resync can settle it.
     */
    private void sendFailed(String type, Throwable cause) {
        logger.warn("Send of [{}] failed on the wire: {}", type, cause.getMessage());
        onNotice.accept("error", "Your " + type + " did not reach the server — resyncing the board");
        onResyncNeeded.run();
    }

    private void notifyState(ConnectionState newState) {
        try {
            onState.accept(newState);
        } catch (RuntimeException e) {
            logger.debug("Connection-state listener threw: {}", e.getMessage());
        }
    }

    private static void closeQuietly(GameLink target) {
        if (target == null) return;
        try {
            target.close();
        } catch (RuntimeException e) {
            logger.debug("Closing the previous gameplay link failed: {}", e.getMessage());
        }
    }

    /**
     * Daemon-threaded so a pending reconnect can never keep the JVM alive after the
     * window closes — the desktop client's whole shutdown path is daemon threads
     * plus {@link #shutdown()}.
     */
    private static Scheduler daemonScheduler() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sudokupro-reconnect");
            thread.setDaemon(true);
            return thread;
        });
        return new Scheduler() {
            @Override
            public void schedule(Runnable task, Duration delay) {
                executor.schedule(task, Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
            }

            @Override
            public void shutdown() {
                executor.shutdownNow();
            }
        };
    }
}
