package com.xai.sudokupro.config;

import com.xai.sudokupro.controller.WebSocketController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Raw WebSocket handler registration — the single WebSocket stack (AUDIT P2-2).
 * /ws/game → WebSocketController; server broadcasts flow through GameSessionRegistry.
 * The parallel STOMP broker (/ws/stomp) was removed: no client ever subscribed to it.
 */
@Configuration
@EnableWebSocket
public class RawWebSocketConfig implements WebSocketConfigurer {

    private static final org.slf4j.Logger logger =
        org.slf4j.LoggerFactory.getLogger(RawWebSocketConfig.class);

    private final WebSocketController webSocketController;

    // setAllowedOrigins("*") would let any site open a gameplay socket with the victim's
    // cookie, so the origin list stays explicit. But the previous default of
    // "http://localhost:8080" was set NOWHERE — not in application.properties, not in
    // docker-compose.yml, not in the Kubernetes manifests — which meant the handshake
    // returned 403 in any deployment that was not literally localhost:8080. It failed
    // closed, so it was never a security hole; it silently broke multiplayer in production
    // instead, and nothing in the test suite or the harnesses noticed because they all run
    // against localhost.
    //
    // The default is now "same origin": an empty list, which
    // setAllowedOriginPatterns interprets together with the explicit patterns below. A
    // deployment that serves the web client from its own origin — the shipped setup, since
    // /play is served by this very server — now works with no configuration at all, and a
    // separately-hosted frontend still has to be named explicitly.
    @Value("${sudokupro.ws.allowed-origins:}")
    private String[] allowedOrigins;

    @Autowired
    public RawWebSocketConfig(WebSocketController webSocketController) {
        this.webSocketController = webSocketController;
    }

    // Frame-size limits are set PER SESSION in WebSocketController.afterConnectionEstablished
    // (session.setTextMessageSizeLimit / setBinaryMessageSizeLimit). A
    // ServletServerContainerFactoryBean was tried here first and is the wrong tool twice
    // over: it configures the JSR-356 container, which Spring's raw WebSocket stack does
    // not consult (frames were still cut off at the 8 KB default), and it requires a real
    // embedded servlet container, so it broke every MockMvc @SpringBootTest context.

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var registration = registry.addHandler(webSocketController, "/ws/game")
                .addInterceptors(new GameIdHandshakeInterceptor());

        String[] configured = java.util.Arrays.stream(allowedOrigins)
            .filter(o -> o != null && !o.isBlank())
            .toArray(String[]::new);

        if (configured.length > 0) {
            logger.info("WebSocket origins restricted to {}", java.util.Arrays.toString(configured));
            registration.setAllowedOrigins(configured);
        } else {
            // Same-origin only: the browser sends Origin, and Spring compares it against
            // the request's own host. A non-browser client (the JavaFX desktop app) sends
            // no Origin header at all and is unaffected either way.
            logger.info("WebSocket origins default to same-origin "
                + "(set sudokupro.ws.allowed-origins for a separately-hosted frontend)");
            registration.setAllowedOriginPatterns("");
        }
    }

    /**
     * Copies an optional {@code ?gameId=...} handshake query parameter into the
     * session attributes so WebSocketController joins that game instead of
     * creating a new one — this is how a remote client reconnects to the game
     * it created over REST.
     *
     * <p>The value is percent-DECODED before it is stored. It previously was not, and the
     * attribute is later used as a game id verbatim, so any id containing a character a
     * conscientious client escapes could never be joined. That is not hypothetical: daily
     * and duel ids embed a player name after a colon — {@code daily-2026-07-26:someuser} —
     * and the web client builds its socket URL with {@code encodeURIComponent}, so the
     * server received {@code daily-2026-07-26%3Asomeuser}, found no such game, and closed
     * the socket with {@code 1008 Unknown game}. Every id from the ordinary "new game" flow
     * is a bare UUID, which survives encoding unchanged — which is exactly why this stayed
     * hidden while breaking both multiplayer modes.
     */
    static final class GameIdHandshakeInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            String gameId = decodeOrNull(UriComponentsBuilder.fromUri(request.getURI())
                .build().getQueryParams().getFirst("gameId"));
            if (gameId != null && !gameId.isBlank()) {
                attributes.put("gameId", gameId);
            }
            return true;
        }

        /**
         * Decodes the raw parameter, or returns null if it cannot be decoded.
         *
         * <p>A syntactically broken escape is already rejected upstream by
         * {@link java.net.URI}, so the catch is defence in depth rather than a live path —
         * but an exception escaping {@code beforeHandshake} would turn a bad query string
         * into a failed upgrade with a stack trace, and an id nobody can decode is not an
         * id worth joining. Starting a fresh game is the better failure.
         */
        private static String decodeOrNull(String raw) {
            if (raw == null) return null;
            try {
                return UriUtils.decode(raw, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                logger.debug("Ignoring an undecodable gameId handshake parameter: {}",
                    e.getMessage());
                return null;
            }
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
            // no-op
        }
    }
}
