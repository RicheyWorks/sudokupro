package com.xai.sudokupro.client.net;

/**
 * Notified when a {@link GameLink} goes away.
 *
 * <p>The close <em>status</em> matters and used to be discarded: the server closes
 * with {@code 1008} for refusals that retrying cannot fix (unknown game, a
 * competitive board that may not be spectated), and with everything else for
 * losses that a retry does fix. A reconnect loop that cannot tell those apart
 * either hammers a game it will never be allowed to join, or gives up on a
 * connection that would have come straight back.
 */
@FunctionalInterface
public interface CloseListener {

    /** Synthetic status used when the link died without a WebSocket close frame. */
    int TRANSPORT_ERROR = -1;

    void onClose(int statusCode, String reason);
}
