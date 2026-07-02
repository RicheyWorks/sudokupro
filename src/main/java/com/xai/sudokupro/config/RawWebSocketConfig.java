package com.xai.sudokupro.config;

import com.xai.sudokupro.controller.WebSocketController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Raw WebSocket handler registration.
 * Kept separate from WebSocketConfig (STOMP) so both can coexist:
 *   - /ws/game  → WebSocketController (raw, used by the JavaFX client)
 *   - /ws/stomp → STOMP broker (used by MultiplayerBroadcaster via SimpMessagingTemplate)
 */
@Configuration
@EnableWebSocket
public class RawWebSocketConfig implements WebSocketConfigurer {

    private final WebSocketController webSocketController;

    // Bug fix: setAllowedOrigins("*") allows any origin to open a WebSocket, bypassing
    // browser same-origin checks. Default to localhost for dev; production should set
    // sudokupro.ws.allowed-origins to the deployed frontend origin in application.properties.
    @Value("${sudokupro.ws.allowed-origins:http://localhost:8080}")
    private String[] allowedOrigins;

    @Autowired
    public RawWebSocketConfig(WebSocketController webSocketController) {
        this.webSocketController = webSocketController;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(webSocketController, "/ws/game")
                .setAllowedOrigins(allowedOrigins);
    }
}
