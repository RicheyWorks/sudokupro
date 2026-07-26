package com.xai.sudokupro.client.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link GameSocket}'s message handling directly (package-private constructor)
 * rather than through a live WebSocket handshake — there's no lightweight in-JVM
 * WebSocket server available to the client module, and the interesting behavior here
 * is envelope framing/parsing, not the transport itself.
 */
class GameSocketTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Minimal no-op WebSocket — GameSocket's listener callbacks only ever call request(). */
    private static final class FakeWebSocket implements WebSocket {
        @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public void request(long n) { }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return false; }
        @Override public boolean isInputClosed() { return false; }
        @Override public void abort() { }
    }

    @Test
    void dispatchesWellFormedEnvelopeToConsumer() {
        List<Envelope> received = new ArrayList<>();
        GameSocket socket = new GameSocket(mapper, received::add, null);

        socket.onOpen(new FakeWebSocket());
        socket.onText(new FakeWebSocket(),
            "{\"type\":\"move\",\"from\":\"richmond\",\"payload\":{\"row\":1,\"col\":2,\"newVal\":5}}", true);

        assertEquals(1, received.size());
        Envelope envelope = received.get(0);
        assertEquals("move", envelope.type());
        assertEquals("richmond", envelope.from());
        assertEquals(5, envelope.payload().get("newVal").asInt());
    }

    @Test
    void bufferedAcrossMultipleFramesUntilLast() {
        List<Envelope> received = new ArrayList<>();
        GameSocket socket = new GameSocket(mapper, received::add, null);
        FakeWebSocket ws = new FakeWebSocket();

        String full = "{\"type\":\"chat\",\"from\":\"richmond\",\"payload\":\"hi there\"}";
        socket.onText(ws, full.substring(0, 10), false);
        assertTrue(received.isEmpty(), "Must not dispatch until the final frame arrives");
        socket.onText(ws, full.substring(10), true);

        assertEquals(1, received.size());
        assertEquals("chat", received.get(0).type());
        assertEquals("hi there", received.get(0).payloadText());
    }

    @Test
    void malformedJsonIsDroppedNotThrown() {
        List<Envelope> received = new ArrayList<>();
        GameSocket socket = new GameSocket(mapper, received::add, null);

        assertDoesNotThrow(() -> socket.onText(new FakeWebSocket(), "{not valid json", true));
        assertTrue(received.isEmpty());
    }

    @Test
    void missingTypeAndFromDefaultToUnknownRatherThanFailing() {
        List<Envelope> received = new ArrayList<>();
        GameSocket socket = new GameSocket(mapper, received::add, null);

        socket.onText(new FakeWebSocket(), "{\"payload\":null}", true);

        assertEquals(1, received.size());
        assertEquals("unknown", received.get(0).type());
        assertEquals("unknown", received.get(0).from());
    }

    /**
     * The close callback carries the WebSocket status, because the reconnect policy
     * needs it: 1008 is the server saying "never", anything else is "not right now".
     */
    @Test
    void closeCallbackReportsTheStatusCodeAndReason() {
        AtomicInteger closeCount = new AtomicInteger();
        AtomicInteger seenStatus = new AtomicInteger();
        List<String> seenReason = new ArrayList<>();
        GameSocket socket = new GameSocket(mapper, e -> { }, (status, reason) -> {
            closeCount.incrementAndGet();
            seenStatus.set(status);
            seenReason.add(reason);
        });

        socket.onClose(new FakeWebSocket(), 1008, "Competitive games cannot be spectated");

        assertEquals(1, closeCount.get());
        assertEquals(1008, seenStatus.get());
        assertEquals(List.of("Competitive games cannot be spectated"), seenReason);
    }

    /**
     * One link death is one report. The JDK calls onError and then onClose for the
     * same broken connection, so reporting both burns two of the reconnect budget's
     * attempts (and shows the player two "connection lost" notices) for one event.
     */
    @Test
    void oneDeadLinkIsReportedExactlyOnceEvenWhenOnErrorPrecedesOnClose() {
        AtomicInteger closeCount = new AtomicInteger();
        GameSocket socket = new GameSocket(mapper, e -> { }, (status, reason) -> closeCount.incrementAndGet());

        socket.onError(new FakeWebSocket(), new RuntimeException("boom"));
        socket.onClose(new FakeWebSocket(), WebSocket.NORMAL_CLOSURE, "bye");

        assertEquals(1, closeCount.get());
    }

    /**
     * A link can die without a close frame at all — a TCP reset, a proxy timeout, a
     * laptop lid. The JDK reports that through onError, and if that path does not
     * report the loss the channel sits believing it is live forever.
     */
    @Test
    void aTransportErrorWithNoCloseFrameIsStillReportedAsALostLink() {
        List<Integer> statuses = new ArrayList<>();
        GameSocket socket = new GameSocket(mapper, e -> { },
            (status, reason) -> statuses.add(status));

        socket.onError(new FakeWebSocket(), new java.io.IOException("connection reset"));

        assertEquals(List.of(CloseListener.TRANSPORT_ERROR), statuses);
    }

    /** A deliberate close is ours, so it must not be reported as a lost connection. */
    @Test
    void aDeliberateCloseIsNotReportedAsAConnectionLoss() {
        AtomicInteger closeCount = new AtomicInteger();
        GameSocket socket = new GameSocket(mapper, e -> { }, (status, reason) -> closeCount.incrementAndGet());

        socket.close();
        socket.onClose(new FakeWebSocket(), WebSocket.NORMAL_CLOSURE, "bye");

        assertEquals(0, closeCount.get(),
            "Every intentional game switch closes the socket; each one used to tell the "
            + "player their connection had been lost");
    }

    @Test
    void sendBeforeConnectionEstablishedThrowsRatherThanNullPointerException() {
        GameSocket socket = new GameSocket(mapper, e -> { }, null);
        ConnectionException e = assertThrows(ConnectionException.class, () -> socket.send("move", "payload"));
        assertTrue(e.getMessage().contains("closed"));
    }

    @Test
    void isOpenIsFalseBeforeConnectionEstablished() {
        GameSocket socket = new GameSocket(mapper, e -> { }, null);
        assertFalse(socket.isOpen());
    }
}
