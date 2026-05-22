package com.xai.sudokupro.service;

import com.xai.sudokupro.model.Notification;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Service for sending push notifications via Firebase Cloud Messaging (FCM) HTTP v1 API.
 * Delivers real-time alerts to players with cosmic precision.
 */
@Service
public class PushNotificationService {
    private static final Logger logger = LoggerFactory.getLogger(PushNotificationService.class);
    private static final String FCM_API_URL_TEMPLATE = "https://fcm.googleapis.com/v1/projects/%s/messages:send";
    private static final Tags GLOBAL_TAGS = Tags.of("app", "SudokuPro");

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MeterRegistry meterRegistry;

    private final String fcmApiUrl;
    private final String serverKey;

    @Autowired
    public PushNotificationService(MeterRegistry meterRegistry,
                                   @Value("${fcm.project-id}") String projectId,
                                   @Value("${fcm.server-key}") String serverKey) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "MeterRegistry cannot be null");
        this.fcmApiUrl = String.format(
            FCM_API_URL_TEMPLATE,
            Objects.requireNonNull(projectId, "FCM Project ID cannot be null")
        );
        this.serverKey = Objects.requireNonNull(serverKey, "FCM Server Key cannot be null");
        logger.info("PushNotificationService initialized with FCM endpoint: {}", fcmApiUrl);
    }

    /**
     * Sends a push notification to a player or topic via FCM.
     * @param playerId The player's ID (used as token or topic if null).
     * @param message The notification message.
     * @param topic Optional topic (e.g., "event", "duel"); defaults to playerId if null.
     */
    public void send(String playerId, String message, String topic) {
        validatePlayerId(playerId);
        validateMessage(message);

        try {
            Map<String, Object> body = new HashMap<>();
            Map<String, Object> notification = new HashMap<>();
            notification.put("title", "SudokuPro");
            notification.put("body", message);

            Map<String, Object> messageMap = new HashMap<>();
            messageMap.put("notification", notification);
            messageMap.put("topic", topic != null ? topic : playerId);

            body.put("message", messageMap);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.AUTHORIZATION, serverKey);

            HttpEntity<String> entity =
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers);

            ResponseEntity<String> response =
                restTemplate.postForEntity(fcmApiUrl, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                meterRegistry.counter(
                    "sudokupro.push.sent",
                    Tags.concat(GLOBAL_TAGS, Tags.of("status", "success"))
                ).increment();
                logger.info("Push notification sent to {} (topic: {}): {}", playerId, topic, message);
            } else {
                meterRegistry.counter(
                    "sudokupro.push.sent",
                    Tags.concat(GLOBAL_TAGS, Tags.of("status", "failure"))
                ).increment();
                logger.error("Push notification failed: {} - {}", response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            meterRegistry.counter(
                "sudokupro.push.sent",
                Tags.concat(GLOBAL_TAGS, Tags.of("status", "error"))
            ).increment();
            logger.error("Failed to send push notification to {} (topic: {}): {}", playerId, topic, e.getMessage(), e);
        }
    }

    private void validatePlayerId(String playerId) {
        if (playerId == null || playerId.trim().isEmpty()) {
            logger.error("Invalid playerId: {}", playerId);
            throw new IllegalArgumentException("Player ID cannot be null or empty");
        }
    }

    private void validateMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            logger.error("Invalid message: {}", message);
            throw new IllegalArgumentException("Message cannot be null or empty");
        }
    }
}
