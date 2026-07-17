package com.xai.sudokupro.service;

import com.xai.sudokupro.service.push.DeviceTokenStore;
import com.xai.sudokupro.service.push.PushSender;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Push wiring: WebSocket delivery always happens; FCM delivery fires only when
 * the provider is enabled, a device token exists, and the per-player cooldown
 * allows it. Dead tokens are dropped. (@Async methods run synchronously here —
 * plain unit test, no Spring proxying.)
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private MultiplayerBroadcaster broadcaster;
    @Mock private PushSender pushSender;
    @Mock private DeviceTokenStore deviceTokenStore;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(broadcaster, new SimpleMeterRegistry(),
            pushSender, deviceTokenStore);
    }

    @Test
    void pushFiresWhenEnabledAndTokenRegistered() {
        when(pushSender.isEnabled()).thenReturn(true);
        when(deviceTokenStore.find("richmond")).thenReturn(Optional.of("tok-1"));
        when(pushSender.send("tok-1", "SudokuPro", "Your duel starts now", "NOTIFICATION"))
            .thenReturn(PushSender.PushResult.SENT);

        service.sendNotification("richmond", "Your duel starts now");

        verify(broadcaster).sendToPlayer("richmond", "notification", "Your duel starts now");
        verify(pushSender).send("tok-1", "SudokuPro", "Your duel starts now", "NOTIFICATION");
    }

    @Test
    void cooldownSuppressesASecondPushButNotTheWebSocketSend() {
        when(pushSender.isEnabled()).thenReturn(true);
        when(deviceTokenStore.find("richmond")).thenReturn(Optional.of("tok-1"));
        when(pushSender.send(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(PushSender.PushResult.SENT);

        service.sendNotification("richmond", "first");
        service.sendNotification("richmond", "second"); // within the 5-minute cooldown

        verify(pushSender, times(1)).send(anyString(), anyString(), anyString(), anyString());
        verify(broadcaster, times(2)).sendToPlayer(eq("richmond"), eq("notification"), anyString());
    }

    @Test
    void deadTokenIsRemovedFromTheStore() {
        when(pushSender.isEnabled()).thenReturn(true);
        when(deviceTokenStore.find("richmond")).thenReturn(Optional.of("dead-tok"));
        when(pushSender.send(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(PushSender.PushResult.INVALID_TOKEN);

        service.sendNotification("richmond", "hello");

        verify(deviceTokenStore).remove("richmond");
    }

    @Test
    void disabledProviderNeverTouchesTheTokenStore() {
        when(pushSender.isEnabled()).thenReturn(false);

        service.sendNotification("richmond", "hello");

        verify(deviceTokenStore, never()).find(anyString());
        verify(pushSender, never()).send(anyString(), anyString(), anyString(), anyString());
        verify(broadcaster).sendToPlayer("richmond", "notification", "hello");
    }

    @Test
    void missingTokenMeansNoSendAttempt() {
        when(pushSender.isEnabled()).thenReturn(true);
        when(deviceTokenStore.find("richmond")).thenReturn(Optional.empty());

        service.sendNotification("richmond", "hello");

        verify(pushSender, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void typedNotificationForwardsTheTypeToPush() {
        when(pushSender.isEnabled()).thenReturn(true);
        when(deviceTokenStore.find("richmond")).thenReturn(Optional.of("tok-1"));
        when(pushSender.send(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(PushSender.PushResult.SENT);

        service.sendTypedNotification("richmond", "DUEL", "Duel challenge!");

        verify(pushSender).send("tok-1", "SudokuPro", "Duel challenge!", "DUEL");
    }
}
