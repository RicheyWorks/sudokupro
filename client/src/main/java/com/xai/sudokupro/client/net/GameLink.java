package com.xai.sudokupro.client.net;

import java.util.function.BiConsumer;

/**
 * A live gameplay channel to the server, as {@link GameChannel} sees it.
 *
 * <p>Exists as an interface purely so the reconnect state machine can be driven
 * against a fake in a unit test: there is no lightweight in-JVM WebSocket server
 * available to this module, and the interesting behaviour — what happens to the
 * channel when a game switch fails, and how it comes back — has nothing to do
 * with the transport.
 */
public interface GameLink extends AutoCloseable {

    /** Queues one envelope for delivery. Throws {@link ConnectionException} if the link is already dead. */
    void send(String type, Object payload);

    boolean isOpen();

    @Override
    void close();

    /**
     * Registers a listener for sends that were accepted locally but later failed
     * on the wire. Without this, a lost move is invisible to both the player and
     * the client's own state: the optimistic local update stands, the server
     * never saw it, and the two boards silently diverge.
     *
     * @param listener receives {@code (envelopeType, cause)}
     */
    default void setSendFailureListener(BiConsumer<String, Throwable> listener) {
        // no-op by default: fakes and links with synchronous delivery have nothing to report
    }
}
