package com.xai.sudokupro.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression tests for a WebSocket heartbeat that was written but never wired.
 *
 * <p><b>The defect.</b> {@code MultiplayerBroadcaster.broadcastHealthPing()} existed, and
 * the web client had a {@code case 'health':} arm waiting for it, but a repository-wide
 * search for the method name returned exactly one hit — its own declaration. Nothing
 * scheduled it, nothing called it. Both ends of a heartbeat were implemented and the wire
 * between them was absent.
 *
 * <p><b>Why it matters.</b> A black-holed TCP connection leaves {@code readyState === OPEN}
 * and fires no event, so without traffic from the server a client sits on a dead socket
 * believing it is connected. That was measured in a real browser: >20 seconds on a dead
 * socket with the connection badge still reading healthy. A periodic server frame also
 * stops proxies reaping the connection as idle, which is the usual way long-lived sockets
 * die in production.
 *
 * <p><b>Why one of these tests is structural.</b> This project has been bitten before by
 * exactly this shape: {@code SecretsGuard} was a correct, well-tested class that Spring
 * never instantiated, so every one of its tests passed while the guard did nothing in a
 * running server. Testing that {@code broadcastHealthPing()} sends the right envelope would
 * repeat that mistake precisely — it is not what was broken. What was broken is that nobody
 * called it, so there is a test asserting the annotation is present.
 */
class HealthPingWiringTest {

    private final GameSessionRegistry registry = mock(GameSessionRegistry.class);
    private final MultiplayerBroadcaster broadcaster = new MultiplayerBroadcaster(
        registry, new ObjectMapper());

    /**
     * The actual finding: the method must be invoked by something. An annotation assertion
     * is a weak form of evidence in general, but here it is the exact thing that was
     * missing, and it is the only part a unit test can observe without booting a context.
     */
    @Test
    void theHealthPingIsActuallyScheduled() throws NoSuchMethodException {
        Method ping = MultiplayerBroadcaster.class.getMethod("broadcastHealthPing");
        Scheduled scheduled = ping.getAnnotation(Scheduled.class);

        assertNotNull(scheduled,
            "broadcastHealthPing existed for a long time with no caller anywhere in the "
                + "repository; without @Scheduled it is dead code again and clients go back "
                + "to sitting on half-open sockets");
        assertFalse(scheduled.fixedRateString().isEmpty(),
            "the interval must be a property so a deployment can tune or disable it");
        assertTrue(scheduled.fixedRateString().contains("sudokupro.ws.health-ping-ms"),
            "expected the documented property name, got " + scheduled.fixedRateString());
    }

    /** The default cadence has to be well inside the idle timeout of a typical proxy. */
    @Test
    void theDefaultIntervalIsShortEnoughToKeepAConnectionAlive() throws NoSuchMethodException {
        String spec = MultiplayerBroadcaster.class.getMethod("broadcastHealthPing")
            .getAnnotation(Scheduled.class).fixedRateString();

        // "${sudokupro.ws.health-ping-ms:20000}" — pull the default out of the placeholder.
        long defaultMs = Long.parseLong(spec.substring(spec.indexOf(':') + 1, spec.indexOf('}')));

        assertTrue(defaultMs > 0, "a non-positive rate would fail to start the scheduler");
        assertTrue(defaultMs <= 60_000,
            "60s is the shortest idle timeout in common proxy defaults; a heartbeat slower "
                + "than that does not keep the connection alive. Got " + defaultMs + "ms");
    }

    /** The envelope has to be the shape the client's 'health' arm expects. */
    @Test
    void thePingIsSentToEveryOpenSocket() {
        broadcaster.broadcastHealthPing();

        verify(registry).broadcastToAll(
            Map.of("type", "health", "from", "server", "payload", "PING"));
        assertEquals(1, broadcaster.getHealthPingsSent());
    }

    /**
     * One unwritable session must not stop the heartbeat for everyone else, and must not
     * spew a stack trace every interval forever.
     */
    @Test
    void aFailedBroadcastIsSwallowedRatherThanEscapingTheScheduledMethod() {
        doThrow(new IllegalStateException("session closed"))
            .when(registry).broadcastToAll(any());

        assertDoesNotThrow(broadcaster::broadcastHealthPing,
            "an exception escaping a @Scheduled method logs a stack trace on every tick");
        assertEquals(0, broadcaster.getHealthPingsSent(),
            "a failed broadcast must not be counted as a delivered heartbeat");
    }

    /** The counter is the observable proof the wiring runs in a live server. */
    @Test
    void successfulPingsAreCounted() {
        broadcaster.broadcastHealthPing();
        broadcaster.broadcastHealthPing();
        broadcaster.broadcastHealthPing();

        assertEquals(3, broadcaster.getHealthPingsSent());
    }

    /** The heartbeat must not be mistaken for application traffic in the rate metric. */
    @Test
    void theHeartbeatDoesNotInflateTheMessageRateMetric() {
        broadcaster.getMessageRatePerSecond();   // reset the window
        broadcaster.broadcastHealthPing();

        assertEquals(0, broadcaster.getMessageRatePerSecond(),
            "counting keep-alives as messages would make an idle server look busy and "
                + "hide a real drop in traffic");
    }
}
