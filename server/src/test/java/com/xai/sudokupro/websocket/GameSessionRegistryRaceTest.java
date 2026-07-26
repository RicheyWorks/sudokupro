package com.xai.sudokupro.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Regression: a concurrent join and leave could permanently mute the joining player.
 *
 * <p>{@code unregister} removed the game's entry after a separate {@code isEmpty()} check:
 * <pre>
 *     sessions.remove(session);
 *     if (sessions.isEmpty()) gameSessions.remove(gameId);
 * </pre>
 * A {@code register} landing on that same Set in between left the new session inside a Set
 * no longer referenced by {@code gameSessions}. The socket was open and the player believed
 * they had joined, but {@code deliverToGameLocal} found no entry — so they received no
 * moves, no chat, no board syncs and no leave events for the rest of the connection, and
 * never recovered, because a later join creates a different Set.
 *
 * <p>Reproduced under contention before the fix: 5 of 25,621 broadcasts missed a live,
 * registered session. The production trigger is entirely ordinary — one player leaving a
 * game at the same moment another joins it.
 */
class GameSessionRegistryRaceTest {

    private static WebSocketSession openSession(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        lenient().when(s.getId()).thenReturn(id);
        lenient().when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    void aRegisteredSessionAlwaysReceivesBroadcastsDespiteConcurrentChurn() throws Exception {
        GameSessionRegistry registry = new GameSessionRegistry(new ObjectMapper());
        String gameId = "race-game";

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger missed = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();

        // Churn: a second player repeatedly joining and leaving the same game.
        Thread churn = new Thread(() -> {
            int i = 0;
            while (!stop.get()) {
                WebSocketSession transient_ = openSession("churn-" + (i++));
                registry.register(gameId, "churn-player", transient_);
                registry.unregister(gameId, "churn-player", transient_);
            }
        });
        churn.setDaemon(true);
        churn.start();

        for (int round = 0; round < 3000; round++) {
            WebSocketSession stable = openSession("stable-" + round);
            registry.register(gameId, "stable-player", stable);
            try {
                // The session is registered and open, so a broadcast must reach it.
                attempts.incrementAndGet();
                if (!registry.gameHasSession(gameId, stable)) missed.incrementAndGet();
            } finally {
                registry.unregister(gameId, "stable-player", stable);
            }
        }
        stop.set(true);
        churn.join(5_000);

        assertEquals(0, missed.get(),
            missed.get() + " of " + attempts.get()
                + " registered sessions were unreachable — the join was lost to an orphan Set");
    }

    /** The entry must still be dropped once genuinely empty, or the map leaks per game. */
    @Test
    void theGameEntryIsReleasedWhenTheLastSessionLeaves() {
        GameSessionRegistry registry = new GameSessionRegistry(new ObjectMapper());
        WebSocketSession a = openSession("a");
        WebSocketSession b = openSession("b");

        registry.register("g1", "p1", a);
        registry.register("g1", "p2", b);
        assertTrue(registry.gameHasSession("g1", a));

        registry.unregister("g1", "p1", a);
        assertFalse(registry.gameHasSession("g1", a));
        assertTrue(registry.gameHasSession("g1", b), "the other player must be unaffected");

        registry.unregister("g1", "p2", b);
        assertFalse(registry.gameHasSession("g1", b));
        assertEquals(0, registry.trackedGameCount(), "an empty game must not linger in the map");
    }

    /** Broadcasting to a game with no sessions must be a harmless no-op. */
    @Test
    void broadcastingToAnEmptyGameIsHarmless() {
        GameSessionRegistry registry = new GameSessionRegistry(new ObjectMapper());
        assertDoesNotThrow(() -> registry.broadcastToGame("nobody-here", null, Map.of("type", "ping")));
    }
}
