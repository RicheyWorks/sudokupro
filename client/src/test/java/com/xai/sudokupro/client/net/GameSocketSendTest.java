package com.xai.sudokupro.client.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the defect class "a send whose outcome nobody looks at".
 *
 * <p>{@code GameSocket.send} used to call {@code ws.sendText(json, true)} and drop
 * the returned future on the floor. Two separate failures came out of that one
 * line:
 * <ul>
 *   <li>{@code sendText} is documented to throw {@code IllegalStateException} if it
 *       is called before the previous send has completed. Two moves typed inside
 *       one network round trip — routine fast play, and a certainty for the "Fix
 *       Conflicts" button, which fires a send per conflicted cell in a tight loop —
 *       blew up on the second, and the board view reported it to the player as an
 *       invalid move.</li>
 *   <li>A send that failed <em>after</em> being accepted was invisible. The local
 *       board had already been updated optimistically, so the client and the server
 *       silently diverged on the move the player thought had landed.</li>
 * </ul>
 */
class GameSocketSendTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** A WebSocket whose sends complete only when the test says so. */
    private static final class ControllableWebSocket implements WebSocket {
        final List<String> sentText = new ArrayList<>();
        final List<CompletableFuture<WebSocket>> sendFutures = new ArrayList<>();
        volatile boolean outputClosed = false;

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            sentText.add(data.toString());
            CompletableFuture<WebSocket> future = new CompletableFuture<>();
            sendFutures.add(future);
            return future;
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
            outputClosed = true;
            return CompletableFuture.completedFuture(this);
        }
        @Override public void request(long n) { }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return outputClosed; }
        @Override public boolean isInputClosed() { return false; }
        @Override public void abort() { outputClosed = true; }
    }

    private GameSocket socketOver(ControllableWebSocket ws) {
        GameSocket socket = new GameSocket(mapper, envelope -> { }, (status, reason) -> { });
        socket.attach(ws);
        return socket;
    }

    /**
     * Reproduction: two moves inside one round trip. The second send must wait, not
     * be handed to a transport that is still busy with the first.
     */
    @Test
    void sendsAreSerialisedOntoThePreviousSend() {
        ControllableWebSocket ws = new ControllableWebSocket();
        GameSocket socket = socketOver(ws);

        socket.send("move", "first");
        socket.send("move", "second");

        assertEquals(1, ws.sentText.size(),
            "The second send must not reach a transport that has not finished the first");

        ws.sendFutures.get(0).complete(ws);

        assertEquals(2, ws.sentText.size(), "…and must go out once the first completes");
        assertTrue(ws.sentText.get(0).contains("first"));
        assertTrue(ws.sentText.get(1).contains("second"));
    }

    /** Ordering is preserved: a queue, not a race. */
    @Test
    void queuedSendsKeepTheOrderTheyWereMadeIn() {
        ControllableWebSocket ws = new ControllableWebSocket();
        GameSocket socket = socketOver(ws);

        socket.send("move", "a");
        socket.send("move", "b");
        socket.send("move", "c");
        ws.sendFutures.get(0).complete(ws);
        ws.sendFutures.get(1).complete(ws);

        assertEquals(3, ws.sentText.size());
        assertTrue(ws.sentText.get(0).contains("\"a\""), ws.sentText.get(0));
        assertTrue(ws.sentText.get(1).contains("\"b\""), ws.sentText.get(1));
        assertTrue(ws.sentText.get(2).contains("\"c\""), ws.sentText.get(2));
    }

    @Test
    void aSendThatFailsOnTheWireIsReportedToTheListener() throws Exception {
        ControllableWebSocket ws = new ControllableWebSocket();
        GameSocket socket = socketOver(ws);
        AtomicReference<String> failedType = new AtomicReference<>();
        AtomicReference<Throwable> failedCause = new AtomicReference<>();
        socket.setSendFailureListener((type, cause) -> {
            failedType.set(type);
            failedCause.set(cause);
        });

        socket.send("move", "lost");
        ws.sendFutures.get(0).completeExceptionally(new java.io.IOException("broken pipe"));

        assertEquals("move", failedType.get(),
            "A move that never reached the server must not vanish silently");
        assertNotNull(failedCause.get());
        assertTrue(failedCause.get() instanceof java.io.IOException, String.valueOf(failedCause.get()));
    }

    /** One dead move must not stop every move after it. */
    @Test
    void aFailedSendDoesNotBlockTheNextOne() {
        ControllableWebSocket ws = new ControllableWebSocket();
        GameSocket socket = socketOver(ws);

        socket.send("move", "doomed");
        ws.sendFutures.get(0).completeExceptionally(new java.io.IOException("broken pipe"));
        socket.send("move", "survivor");

        assertEquals(2, ws.sentText.size(),
            "A failed send left in the chain would swallow every later move");
        assertTrue(ws.sentText.get(1).contains("survivor"));
    }

    @Test
    void sendOnAClosedOutputThrowsAConnectionException() {
        ControllableWebSocket ws = new ControllableWebSocket();
        GameSocket socket = socketOver(ws);
        ws.outputClosed = true;

        assertThrows(ConnectionException.class, () -> socket.send("move", "x"));
        assertTrue(ws.sentText.isEmpty());
    }

    /**
     * A server that never sets {@code last} would otherwise grow the reassembly
     * buffer until the client dies. Dropping the oversized message must also leave
     * the socket able to read the next one.
     */
    @Test
    void anEndlessMessageIsDroppedRatherThanBufferedForever() {
        List<Envelope> received = new ArrayList<>();
        GameSocket socket = new GameSocket(mapper, received::add, (status, reason) -> { });
        ControllableWebSocket ws = new ControllableWebSocket();
        socket.attach(ws);

        String flood = "x".repeat(GameSocket.MAX_MESSAGE_CHARS + 1);
        socket.onText(ws, flood, false);
        socket.onText(ws, "{\"type\":\"chat\",\"from\":\"ann\",\"payload\":\"hi\"}", true);

        assertEquals(1, received.size(), "The valid message after the flood must still parse");
        assertEquals("chat", received.get(0).type());
        assertEquals("hi", received.get(0).payloadText());
    }

    /** Sanity: the encoded frame is the shared {type, payload} envelope the server parses. */
    @Test
    void theWireFormatIsTheSharedEnvelope() throws Exception {
        ControllableWebSocket ws = new ControllableWebSocket();
        GameSocket socket = socketOver(ws);

        socket.send("chat", "hello");
        assertTrue(ws.sendFutures.get(0).completeOnTimeout(ws, 1, TimeUnit.SECONDS) != null);

        var node = mapper.readTree(ws.sentText.get(0));
        assertEquals("chat", node.get("type").asText());
        assertEquals("hello", node.get("payload").asText());
    }
}
