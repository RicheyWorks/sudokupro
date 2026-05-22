package com.xai.sudokupro.service;

import com.xai.sudokupro.model.Notification;
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
    private final PushNotificationService pushNotificationService;
    private final MeterRegistry meterRegistry;

    private final ConcurrentLinkedQueue<Notification> notificationQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, LocalDateTime> lastNotificationTimes = new ConcurrentHashMap<>();

    @Autowired
    public NotificationService(MultiplayerBroadcaster multiplayerBroadcaster,
                               PushNotificationService pushNotificationService,
                               MeterRegistry meterRegistry) {

        this.multiplayerBroadcaster = Objects.requireNonNull(multiplayerBroadcaster);
        this.pushNotificationService = Objects.requireNonNull(pushNotificationService);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);

        meterRegistry.gauge("sudokupro.notification.queue.size", notificationQueue, Queue::size);
    }

    @Async
    public void sendNotification(String playerId, String message) {
        validatePlayerId(playerId);
        validateMessage(message);

        Notification notification = new Notification(playerId, message, LocalDateTime.now().toString());

        try {
            multiplayerBroadcaster.sendToPlayer(playerId, "notification", message);

            if (shouldSendPush(playerId)) {
                queuePushNotification(notification);
                lastNotificationTimes.put(playerId, LocalDateTime.now());
            }

            trimQueue();

        } catch (Exception e) {
            queuePushNotification(notification);
        }
    }

    @Async
    public void sendTypedNotification(String playerId, String type, String message) {
        validatePlayerId(playerId);
        validateType(type);
        validateMessage(message);

        Notification notification = new Notification(playerId, type + ": " + message, LocalDateTime.now().toString());

        try {
            multiplayerBroadcaster.sendToPlayer(playerId, type, message);

            if (shouldSendPush(playerId)) {
                queuePushNotification(notification);
                lastNotificationTimes.put(playerId, LocalDateTime.now());
            }

            trimQueue();

        } catch (Exception e) {
            queuePushNotification(notification);
        }
    }

    @Async
    public void broadcastNotification(String type, String message) {
        validateType(type);
        validateMessage(message);

        try {
            multiplayerBroadcaster.broadcastEvent(type, message, null);
        } catch (Exception ignored) {
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

        String playerId = notification.playerId();
        String message = notification.message();

        pushNotificationService.send(playerId, message, null);
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
            throw new IllegalArgumentException("Invalid type");
        }
    }
}
