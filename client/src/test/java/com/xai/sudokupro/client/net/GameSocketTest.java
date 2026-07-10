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

    @Test
    void onCloseAndOnErrorBothInvokeTheCloseCallback() {
        AtomicInteger closeCount = new AtomicInteger();
        GameSocket socket = new GameSocket(mapper, e -> { }, closeCount::incrementAndGet);

        socket.onClose(new FakeWebSocket(), WebSocket.NORMAL_CLOSURE, "bye");
        assertEquals(1, closeCount.get());

        socket.onError(new FakeWebSocket(), new RuntimeException("boom"));
        assertEquals(2, closeCount.get());
    }

    @Test
    void sendBeforeConnectionEstablishedThrowsRatherThanNullPointerException() {
        GameSocket socket = new GameSocket(mapper, e -> { }, null);
        ApiException e = assertThrows(ApiException.class, () -> socket.send("move", "payload"));
        assertTrue(e.getMessage().contains("closed"));
    }

    @Test
    void isOpenIsFalseBeforeConnectionEstablished() {
        GameSocket socket = new GameSocket(mapper, e -> { }, null);
        assertFalse(socket.isOpen());
    }
}
