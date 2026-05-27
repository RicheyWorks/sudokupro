package com.xai.sudokupro.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Sends push notifications via the FCM Legacy HTTP API.
 *
 * The legacy endpoint (fcm.googleapis.com/fcm/send) authenticates with a server key
 * using the "Authorization: key=<server-key>" header — no OAuth2 token required.
 *
 * If fcm.server-key is blank (e.g. local dev without FCM credentials), all send
 * calls are silently skipped rather than failing at startup.
 */
@Service
public class PushNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(PushNotificationService.class);
    private static final String FCM_LEGACY_URL = "https://fcm.googleapis.com/fcm/send";
    private static final Tags GLOBAL_TAGS = Tags.of("app", "SudokuPro");

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MeterRegistry meterRegistry;
    private final String serverKey;

    @Autowired
    public PushNotificationService(MeterRegistry meterRegistry,
                                   @Value("${fcm.server-key:}") String serverKey) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "MeterRegistry cannot be null");
        this.serverKey = serverKey;
        if (serverKey == null || serverKey.isBlank()) {
            logger.warn("PushNotificationService: fcm.server-key is not configured — push notifications disabled");
        } else {
            logger.info("PushNotificationService initialized (FCM legacy endpoint)");
        }
    }

    /**
     * Sends a push notification to a FCM topic or registration token.
     *
     * @param playerId The player's FCM registration token (device-specific) or topic name.
     * @param message  The notification body text.
     * @param topic    If non-null, sends to this FCM topic; otherwise sends to playerId as a token.
     */
    public void send(String playerId, String message, String topic) {
        validatePlayerId(playerId);
        validateMessage(message);

        if (serverKey == null || serverKey.isBlank()) {
            logger.debug("Push skipped (no FCM server key configured) for player {}", playerId);
            return;
        }

        try {
            Map<String, Object> notification = new HashMap<>();
            notification.put("title", "SudokuPro");
            notification.put("body", message);

            Map<String, Object> body = new HashMap<>();
            body.put("notification", notification);
            if (topic != null && !topic.isBlank()) {
                body.put("to", "/topics/" + topic);
            } else {
                body.put("to", playerId);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // Legacy FCM API uses "key=<server-key>" — not a Bearer token.
            headers.set(HttpHeaders.AUTHORIZATION, "key=" + serverKey);

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
            ResponseEntity<String> response = restTemplate.postForEntity(FCM_LEGACY_URL, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                meterRegistry.counter("sudokupro.push.sent",
                    Tags.concat(GLOBAL_TAGS, Tags.of("status", "success"))).increment();
                logger.info("Push sent to player {} (topic: {}): {}", playerId, topic, message);
            } else {
                meterRegistry.counter("sudokupro.push.sent",
                    Tags.concat(GLOBAL_TAGS, Tags.of("status", "failure"))).increment();
                logger.error("Push failed for player {} — {} {}", playerId,
                    response.getStatusCode(), response.getBody());
            }

        } catch (Exception e) {
            meterRegistry.counter("sudokupro.push.sent",
                Tags.concat(GLOBAL_TAGS, Tags.of("status", "error"))).increment();
            logger.error("Push error for player {} (topic: {}): {}", playerId, topic, e.getMessage(), e);
        }
    }

    private void validatePlayerId(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("Player ID cannot be null or empty");
        }
    }

    private void validateMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Message cannot be null or empty");
        }
    }
}
