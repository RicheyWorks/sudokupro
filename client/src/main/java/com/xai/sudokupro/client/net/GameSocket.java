package com.xai.sudokupro.client.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The gameplay WebSocket channel. Sends and receives the shared envelope
 * format {@code {"type", "from", "payload"}}; incoming envelopes are handed to
 * the consumer on the HTTP client's executor threads (callers marshal to the
 * UI thread themselves).
 */
public final class GameSocket implements WebSocket.Listener, GameLink {

    private static final Logger logger = LoggerFactory.getLogger(GameSocket.class);

    /**
     * Ceiling on a reassembled message. {@code onText} accumulates continuation
     * frames into one buffer, and without a bound a server that never sets
     * {@code last} grows it until the client dies of an OutOfMemoryError.
     */
    static final int MAX_MESSAGE_CHARS = 1_000_000;

    private final ObjectMapper mapper;
    private final Consumer<Envelope> onEnvelope;
    private final CloseListener onClose;
    private final StringBuilder partial = new StringBuilder();
    private final AtomicBoolean closeReported = new AtomicBoolean(false);
    private final Object sendLock = new Object();
    private volatile WebSocket webSocket;
    private volatile BiConsumer<String, Throwable> sendFailureListener = (type, cause) -> { };

    /**
     * Serializes outbound frames.
     *
     * <p>{@code WebSocket.sendText} throws {@code IllegalStateException} if it is
     * invoked before the previous send has completed, and the previous code both
     * called it directly from the FX thread and threw away the returned future. Two
     * moves typed inside one network round trip — ordinary fast play, and a
     * certainty for "Fix Conflicts", which fires up to 81 sends in a tight loop —
     * therefore blew up on the second one and were reported to the player as an
     * invalid move. Chaining each send onto the previous future both fixes the
     * ordering and gives the failure somewhere to be noticed.
     */
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    GameSocket(ObjectMapper mapper, Consumer<Envelope> onEnvelope, CloseListener onClose) {
        this.mapper = mapper;
        this.onEnvelope = onEnvelope;
        this.onClose = onClose;
    }

    /** Installs the transport. Called by {@link #open} after the handshake, and by tests. */
    void attach(WebSocket ws) {
        this.webSocket = ws;
    }

    static GameSocket open(HttpClient httpClient, ObjectMapper mapper, URI uri,
                           String basicAuth, Consumer<Envelope> onEnvelope, CloseListener onClose) {
        GameSocket socket = new GameSocket(mapper, onEnvelope, onClose);
        try {
            socket.attach(httpClient.newWebSocketBuilder()
                .header("Authorization", basicAuth)
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .buildAsync(uri, socket)
                .get(20, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("WebSocket connect interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw handshakeFailure(uri, cause);
        }
        logger.info("Gameplay channel open: {}", uri);
        return socket;
    }

    /**
     * Turns a failed upgrade into an {@link ApiException} that carries the HTTP
     * status where there is one. Without the status, a rejected login and a
     * server that is simply down are indistinguishable to the reconnect policy,
     * and it would sit there retrying credentials that will never be accepted.
     */
    private static ApiException handshakeFailure(URI uri, Throwable cause) {
        if (cause instanceof WebSocketHandshakeException handshake && handshake.getResponse() != null) {
            int status = handshake.getResponse().statusCode();
            String detail = switch (status) {
                case 401 -> "Authentication failed — check username/password.";
                case 403 -> "Access denied (403) — this game cannot be joined with these credentials.";
                default -> "the server answered HTTP " + status;
            };
            return new ApiException(status, "WebSocket connect to " + uri + " failed: " + detail);
        }
        return new ApiException("WebSocket connect to " + uri + " failed: " + cause.getMessage(), cause);
    }

    /** Sends one envelope; payload may be any Jackson-serializable object. */
    @Override
    public void send(String type, Object payload) {
        WebSocket ws = this.webSocket;
        if (ws == null || ws.isOutputClosed()) {
            throw new ConnectionException("Gameplay channel is closed");
        }
        String json;
        try {
            json = mapper.writeValueAsString(Map.of("type", type, "payload", payload));
        } catch (Exception e) {
            throw new ApiException("Failed to encode [" + type + "]: " + e.getMessage(), e);
        }
        synchronized (sendLock) {
            sendChain = sendChain
                .thenCompose(ignored -> ws.sendText(json, true))
                // handle(), NOT whenComplete(): the failure has to be reported and then
                // absorbed. whenComplete re-raises it into the chain, and since every
                // later send is chained onto this future, one undeliverable move would
                // silently swallow every move queued behind it.
                .handle((ignored, failure) -> {
                    if (failure != null) reportSendFailure(type, failure);
                    return (Object) null;
                });
        }
    }

    @Override
    public void setSendFailureListener(BiConsumer<String, Throwable> listener) {
        this.sendFailureListener = listener != null ? listener : (type, cause) -> { };
    }

    private void reportSendFailure(String type, Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
            ? failure.getCause() : failure;
        logger.warn("Send of [{}] failed: {}", type, cause.toString());
        try {
            sendFailureListener.accept(type, cause);
        } catch (RuntimeException e) {
            logger.debug("Send-failure listener threw: {}", e.getMessage());
        }
    }

    @Override
    public boolean isOpen() {
        WebSocket ws = this.webSocket;
        return ws != null && !ws.isOutputClosed() && !ws.isInputClosed();
    }

    @Override
    public void close() {
        // Mark the close as ours BEFORE asking for it, so the resulting close frame
        // is not reported as a lost connection. Every intentional game switch used
        // to tell the player "Connection to game lost".
        closeReported.set(true);
        WebSocket ws = this.webSocket;
        if (ws != null && !ws.isOutputClosed()) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
    }

    // ---- WebSocket.Listener ------------------------------------------------

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        if (partial.length() + data.length() > MAX_MESSAGE_CHARS) {
            logger.warn("Dropping an oversized message after {} chars", partial.length());
            partial.setLength(0);
            ws.request(1);
            return null;
        }
        partial.append(data);
        if (last) {
            String message = partial.toString();
            partial.setLength(0);
            dispatch(message);
        }
        ws.request(1);
        return null;
    }

    @Override
    public void onOpen(WebSocket ws) {
        ws.request(1);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        logger.info("Gameplay channel closed: {} {}", statusCode, reason);
        reportClose(statusCode, reason);
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        logger.error("Gameplay channel error: {}", error.getMessage());
        reportClose(CloseListener.TRANSPORT_ERROR, String.valueOf(error.getMessage()));
    }

    /** Reports the death of this link exactly once — onError is often followed by onClose. */
    private void reportClose(int statusCode, String reason) {
        if (!closeReported.compareAndSet(false, true)) return;
        if (onClose != null) onClose.onClose(statusCode, reason);
    }

    private void dispatch(String message) {
        try {
            JsonNode node = mapper.readTree(message);
            Envelope envelope = new Envelope(
                node.path("type").asText("unknown"),
                node.path("from").asText("unknown"),
                node.get("payload"));
            onEnvelope.accept(envelope);
        } catch (Exception e) {
            logger.warn("Dropping malformed envelope: {}", e.getMessage());
        }
    }
}
