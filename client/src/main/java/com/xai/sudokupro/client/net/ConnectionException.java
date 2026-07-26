package com.xai.sudokupro.client.net;

/**
 * The gameplay channel is not usable right now.
 *
 * <p>Distinct from {@link ApiException} on purpose. The UI used to receive a bare
 * {@code IllegalStateException} for "socket is dead" and render it through the
 * move-failure path, so a player whose connection had dropped was told
 * <em>"Invalid move: Gameplay channel is not connected"</em> — blaming their
 * perfectly legal 7 for a network problem, and offering no hint that reconnecting
 * was the thing to do.
 */
public class ConnectionException extends RuntimeException {

    private final ConnectionState state;

    public ConnectionException(String message) {
        this(message, ConnectionState.DISCONNECTED);
    }

    public ConnectionException(String message, ConnectionState state) {
        super(message);
        this.state = state;
    }

    public ConnectionException(String message, Throwable cause) {
        super(message, cause);
        this.state = ConnectionState.DISCONNECTED;
    }

    /** The channel state at the moment the call was refused. */
    public ConnectionState state() {
        return state;
    }
}
