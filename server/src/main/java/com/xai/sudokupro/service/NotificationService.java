package com.xai.sudokupro.service;

import com.xai.sudokupro.model.Notification;
import com.xai.sudokupro.service.push.DeviceTokenStore;
import com.xai.sudokupro.service.push.PushSender;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private static final int MAX_QUEUE_SIZE = 1000;
    private static final long PUSH_RATE_LIMIT_MINUTES = 5;

    private final MultiplayerBroadcaster multiplayerBroadcaster;
    private final MeterRegistry meterRegistry;
    private final PushSender pushSender;
    private final DeviceTokenStore deviceTokenStore;

    private final ConcurrentLinkedQueue<Notification> notificationQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, LocalDateTime> lastNotificationTimes = new ConcurrentHashMap<>();

    @Autowired
    public NotificationService(MultiplayerBroadcaster multiplayerBroadcaster,
                               MeterRegistry meterRegistry,
                               PushSender pushSender,
                               DeviceTokenStore deviceTokenStore) {

        this.multiplayerBroadcaster = Objects.requireNonNull(multiplayerBroadcaster);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
        this.pushSender = Objects.requireNonNull(pushSender);
        this.deviceTokenStore = Objects.requireNonNull(deviceTokenStore);

        meterRegistry.gauge("sudokupro.notification.queue.size", notificationQueue, Queue::size);
    }

    @Async
    public void sendNotification(String playerId, String message) {
        validatePlayerId(playerId);
        validateMessage(message);

        Notification notification = new Notification(playerId, "NOTIFICATION", message);

        try {
            multiplayerBroadcaster.sendToPlayer(playerId, "notification", message);

            if (shouldSendPush(playerId)) {
                queuePushNotification(notification);
                lastNotificationTimes.put(playerId, LocalDateTime.now());
            }

            trimQueue();

        } catch (Exception e) {
            logger.warn("WebSocket send failed for player {} — falling back to push queue: {}", playerId, e.getMessage(), e);
            queuePushNotification(notification);
        }
    }

    @Async
    public void sendTypedNotification(String playerId, String type, String message) {
        validatePlayerId(playerId);
        validateType(type);
        validateMessage(message);

        Notification notification = new Notification(playerId, type, message);

        try {
            multiplayerBroadcaster.sendToPlayer(playerId, type, message);

            if (shouldSendPush(playerId)) {
                queuePushNotification(notification);
                lastNotificationTimes.put(playerId, LocalDateTime.now());
            }

            trimQueue();

        } catch (Exception e) {
            logger.warn("WebSocket typed send failed for player {} type '{}' — falling back to push queue: {}", playerId, type, e.getMessage(), e);
            queuePushNotification(notification);
        }
    }

    @Async
    public void broadcastNotification(String type, String message) {
        validateType(type);
        validateMessage(message);

        try {
            multiplayerBroadcaster.broadcastEvent(type, message, null);
        } catch (Exception e) {
            logger.error("Broadcast failed for type '{}': {}", type, e.getMessage(), e);
        }
    }

    public List<String> getPendingPushMessages() {
        return notificationQueue.stream()
            .map(Notification::toString)
            .collect(Collectors.toList());
    }

    private void queuePushNotification(Notification notification) {
        if (notificationQueue.size() >= MAX_QUEUE_SIZE) {
            notificationQueue.poll();
        }

        notificationQueue.offer(notification);

        // Rate-limit guard kept from the earlier bug fix: when the WebSocket path
        // fails, pushes must still respect the 5-minute cooldown. Actual delivery
        // is the FCM HTTP-v1 integration (the legacy server-key API removed under
        // AUDIT P1-5 was replaced, not restored).
        String playerId = notification.playerId();
        if (shouldSendPush(playerId)) {
            lastNotificationTimes.put(playerId, LocalDateTime.now());
            deliverPush(notification);
        }
    }

    /** Attempts real push delivery; quietly a no-op when FCM is not configured. */
    private void deliverPush(Notification notification) {
        if (!pushSender.isEnabled()) {
            logger.debug("Push suppressed for {} — no push provider configured", notification.playerId());
            return;
        }
        String playerId = notification.playerId();
        deviceTokenStore.find(playerId).ifPresentOrElse(token -> {
            PushSender.PushResult result =
                pushSender.send(token, "SudokuPro", notification.message(), notification.type());
            if (result == PushSender.PushResult.INVALID_TOKEN) {
                // Dead registration (app uninstalled, token rotated) — drop it so
                // we stop paying for doomed sends.
                deviceTokenStore.remove(playerId);
            }
            logger.debug("Push to {} → {}", playerId, result);
        }, () -> logger.debug("Push skipped for {} — no device token registered", playerId));
    }

    private boolean shouldSendPush(String playerId) {
        LocalDateTime last = lastNotificationTimes.get(playerId);
        return last == null || last.isBefore(LocalDateTime.now().minusMinutes(PUSH_RATE_LIMIT_MINUTES));
    }

    private void trimQueue() {
        while (notificationQueue.size() > MAX_QUEUE_SIZE) {
            notificationQueue.poll();
        }
    }

    private void validatePlayerId(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("Invalid playerId");
        }
    }

    private void validateMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Invalid message");
        }
    }

    private void validateType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Invalid notification type");
        }
    }
}
