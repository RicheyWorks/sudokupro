package com.xai.sudokupro.client.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * The gameplay WebSocket channel. Sends and receives the shared envelope
 * format {@code {"type", "from", "payload"}}; incoming envelopes are handed to
 * the consumer on the HTTP client's executor threads (callers marshal to the
 * UI thread themselves).
 */
public final class GameSocket implements WebSocket.Listener, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(GameSocket.class);

    private final ObjectMapper mapper;
    private final Consumer<Envelope> onEnvelope;
    private final Runnable onClose;
    private final StringBuilder partial = new StringBuilder();
    private volatile WebSocket webSocket;

    private GameSocket(ObjectMapper mapper, Consumer<Envelope> onEnvelope, Runnable onClose) {
        this.mapper = mapper;
        this.onEnvelope = onEnvelope;
        this.onClose = onClose;
    }

    static GameSocket open(HttpClient httpClient, ObjectMapper mapper, URI uri,
                           String basicAuth, Consumer<Envelope> onEnvelope, Runnable onClose) {
        GameSocket socket = new GameSocket(mapper, onEnvelope, onClose);
        try {
            socket.webSocket = httpClient.newWebSocketBuilder()
                .header("Authorization", basicAuth)
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .buildAsync(uri, socket)
                .get(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("WebSocket connect interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ApiException("WebSocket connect to " + uri + " failed: " + cause.getMessage(), cause);
        }
        logger.info("Gameplay channel open: {}", uri);
        return socket;
    }

    /** Sends one envelope; payload may be any Jackson-serializable object. */
    public void send(String type, Object payload) {
        WebSocket ws = this.webSocket;
        if (ws == null || ws.isOutputClosed()) {
            throw new ApiException("Gameplay channel is closed", null);
        }
        try {
            String json = mapper.writeValueAsString(Map.of("type", type, "payload", payload));
            ws.sendText(json, true);
        } catch (Exception e) {
            throw new ApiException("Failed to send [" + type + "]: " + e.getMessage(), e);
        }
    }

    public boolean isOpen() {
        WebSocket ws = this.webSocket;
        return ws != null && !ws.isOutputClosed() && !ws.isInputClosed();
    }

    @Override
    public void close() {
        WebSocket ws = this.webSocket;
        if (ws != null && !ws.isOutputClosed()) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
    }

    // ---- WebSocket.Listener ------------------------------------------------

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
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
        if (onClose != null) onClose.run();
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        logger.error("Gameplay channel error: {}", error.getMessage());
        if (onClose != null) onClose.run();
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
