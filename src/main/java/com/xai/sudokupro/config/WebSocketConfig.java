package com.xai.sudokupro.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP/SimpMessagingTemplate configuration.
 * Enabling @EnableWebSocketMessageBroker causes Spring to create a SimpMessagingTemplate bean,
 * which MultiplayerBroadcaster injects and uses for topic/queue broadcasts.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // Mirrors RawWebSocketConfig — set sudokupro.ws.allowed-origins in application.properties
    // for production. Defaults to localhost only so the dev build works out of the box.
    @Value("${sudokupro.ws.allowed-origins:http://localhost:8080}")
    private String[] allowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // In-memory broker handles /topic (broadcast) and /queue (player-targeted) destinations.
        config.enableSimpleBroker("/topic", "/queue");
        // Messages sent from clients to @MessageMapping methods are prefixed with /app.
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // STOMP clients connect here (plain WebSocket).
        registry.addEndpoint("/ws/stomp").setAllowedOrigins(allowedOrigins);
        // SockJS fallback for browsers that don't support native WebSocket.
        registry.addEndpoint("/ws/stomp").setAllowedOrigins(allowedOrigins).withSockJS();
    }
}
