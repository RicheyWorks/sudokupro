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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
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

    /**
     * The per-player push cooldown map must not grow with every player ever notified.
     *
     * <p>It was write-only: every distinct player who received a push added a key and
     * nothing removed it, so on a long-lived server the map tracked the total player
     * population rather than the currently-cooling-down one. It is not a cache — it is a
     * five-minute window, and an entry older than that window is indistinguishable from an
     * absent one ({@code shouldSendPush} returns true for both), so evicting stale entries
     * cannot change a decision.
     *
     * <p>This is why the {@link java.time.Clock} seam exists: the leak is a function of
     * elapsed time, and every other test in this class runs inside a single instant. A test
     * that cannot make time pass cannot see an entry fail to expire.
     */
    @Test
    void theCooldownMapDoesNotGrowWithEveryPlayerEverNotified() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-26T12:00:00Z"));
        NotificationService timed = new NotificationService(broadcaster, new SimpleMeterRegistry(),
            pushSender, deviceTokenStore, clock);
        when(pushSender.isEnabled()).thenReturn(true);
        when(deviceTokenStore.find(anyString())).thenReturn(Optional.of("tok"));
        when(pushSender.send(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(PushSender.PushResult.SENT);

        int perWave = 700;   // over RATE_LIMIT_SWEEP_THRESHOLD, so a sweep is reached
        for (int i = 0; i < perWave; i++) timed.sendNotification("wave1-player-" + i, "hello");
        assertEquals(perWave, timed.rateLimitWindowSize(),
            "everyone notified in the last five minutes is legitimately still cooling down");

        // An hour later, none of wave 1 can affect a decision any more.
        clock.advance(Duration.ofHours(1));
        for (int i = 0; i < perWave; i++) timed.sendNotification("wave2-player-" + i, "hello");

        assertTrue(timed.rateLimitWindowSize() <= perWave,
            "hour-old cooldowns must be evicted rather than accumulated — held "
                + timed.rateLimitWindowSize() + " after " + (perWave * 2) + " distinct players");
    }

    /**
     * Eviction must not weaken the cooldown itself: a player stamped moments ago is still
     * suppressed even when a sweep runs.
     */
    @Test
    void sweepingDoesNotDropAStillActiveCooldown() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-26T12:00:00Z"));
        NotificationService timed = new NotificationService(broadcaster, new SimpleMeterRegistry(),
            pushSender, deviceTokenStore, clock);
        when(pushSender.isEnabled()).thenReturn(true);
        when(deviceTokenStore.find(anyString())).thenReturn(Optional.of("tok"));
        when(pushSender.send(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(PushSender.PushResult.SENT);

        timed.sendNotification("richmond", "first");
        // Drive enough traffic to trigger a sweep, all inside the cooldown window.
        for (int i = 0; i < 700; i++) timed.sendNotification("filler-" + i, "hello");

        clock.advance(Duration.ofMinutes(1));
        timed.sendNotification("richmond", "second");

        verify(pushSender, times(1))
            .send(eq("tok"), anyString(), argThat(m -> m.startsWith("first") || m.startsWith("second")), anyString());
    }

    /** A cooldown that has genuinely expired lets the next push through. */
    @Test
    void anExpiredCooldownAllowsTheNextPush() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-26T12:00:00Z"));
        NotificationService timed = new NotificationService(broadcaster, new SimpleMeterRegistry(),
            pushSender, deviceTokenStore, clock);
        when(pushSender.isEnabled()).thenReturn(true);
        when(deviceTokenStore.find("richmond")).thenReturn(Optional.of("tok-1"));
        when(pushSender.send(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(PushSender.PushResult.SENT);

        timed.sendNotification("richmond", "first");
        clock.advance(Duration.ofMinutes(6));
        timed.sendNotification("richmond", "second");

        verify(pushSender).send("tok-1", "SudokuPro", "first", "NOTIFICATION");
        verify(pushSender).send("tok-1", "SudokuPro", "second", "NOTIFICATION");
    }

    /** A clock the test drives, so cooldown expiry is observable at all. */
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration by) { this.now = this.now.plus(by); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
