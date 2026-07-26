package com.xai.sudokupro.client.net;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A {@link GameLink} with no socket behind it, plus the factory that hands them out.
 *
 * <p>Shared by the channel tests. It records what it was asked to send and lets a
 * test kill it with an arbitrary WebSocket close status, which is the whole input
 * space of the reconnect state machine.
 */
final class FakeGameLink implements GameLink {

    final String gameId;
    final CloseListener closeListener;
    final List<String> sent = new ArrayList<>();
    volatile boolean open = true;
    volatile int closeCalls = 0;
    private volatile BiConsumer<String, Throwable> sendFailureListener = (type, cause) -> { };

    FakeGameLink(String gameId, CloseListener closeListener) {
        this.gameId = gameId;
        this.closeListener = closeListener;
    }

    @Override
    public void send(String type, Object payload) {
        if (!open) throw new ConnectionException("Gameplay channel is closed");
        sent.add(type);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        closeCalls++;
        open = false;
    }

    @Override
    public void setSendFailureListener(BiConsumer<String, Throwable> listener) {
        this.sendFailureListener = listener;
    }

    /** The server (or the network) dropped this link. */
    void die(int statusCode, String reason) {
        open = false;
        closeListener.onClose(statusCode, reason);
    }

    /** A send that the transport accepted and then failed to deliver. */
    void failSendOnTheWire(String type, Throwable cause) {
        sendFailureListener.accept(type, cause);
    }

    /** Hands out {@link FakeGameLink}s, recording every open and optionally failing them. */
    static final class Factory implements GameLinkFactory {
        final List<FakeGameLink> opened = new ArrayList<>();
        final List<String> requestedGameIds = new ArrayList<>();
        /** When non-null, {@code open} throws this instead of returning a link. */
        volatile RuntimeException failWith;
        /** When non-null, run against the fresh link before {@code open} returns. */
        volatile Consumer<FakeGameLink> beforeReturning;

        @Override
        public GameLink open(String gameId, Consumer<Envelope> onEnvelope, CloseListener onClose) {
            requestedGameIds.add(gameId);
            RuntimeException failure = failWith;
            if (failure != null) throw failure;
            FakeGameLink link = new FakeGameLink(gameId, onClose);
            opened.add(link);
            Consumer<FakeGameLink> hook = beforeReturning;
            if (hook != null) hook.accept(link);
            return link;
        }

        FakeGameLink last() {
            return opened.get(opened.size() - 1);
        }
    }
}
