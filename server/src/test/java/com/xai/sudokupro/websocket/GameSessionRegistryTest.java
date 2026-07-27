package com.xai.sudokupro.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Broadcast-scoping tests for the converged WebSocket stack (AUDIT P2-2):
 * messages must reach exactly the right game's sessions over raw WebSocket.
 */
@ExtendWith(MockitoExtension.class)
class GameSessionRegistryTest {

    @Mock private WebSocketSession alice;
    @Mock private WebSocketSession bob;
    @Mock private WebSocketSession carol; // different game

    private GameSessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new GameSessionRegistry(new ObjectMapper());
        lenient().when(alice.isOpen()).thenReturn(true);
        lenient().when(bob.isOpen()).thenReturn(true);
        lenient().when(carol.isOpen()).thenReturn(true);
        registry.register("game-1", "alice", alice);
        registry.register("game-1", "bob", bob);
        registry.register("game-2", "carol", carol);
    }

    @Test
    void broadcastIsScopedToGameAndExcludesSender() throws Exception {
        registry.broadcastToGame("game-1", alice, Map.of("type", "move", "from", "alice", "payload", "x"));

        verify(bob).sendMessage(any(TextMessage.class));
        verify(alice, never()).sendMessage(any());  // sender excluded
        verify(carol, never()).sendMessage(any());  // other game untouched
    }

    @Test
    void broadcastToAllReachesEveryOpenSession() throws Exception {
        registry.broadcastToAll(Map.of("type", "health", "from", "server", "payload", "PING"));

        verify(alice).sendMessage(any(TextMessage.class));
        verify(bob).sendMessage(any(TextMessage.class));
        verify(carol).sendMessage(any(TextMessage.class));
    }

    @Test
    void sendToPlayerTargetsOneSession() throws Exception {
        assertTrue(registry.sendToPlayer("bob", Map.of("type", "hint", "from", "server", "payload", "try r1c1")));
        verify(bob).sendMessage(any(TextMessage.class));
        verify(alice, never()).sendMessage(any());

        assertFalse(registry.sendToPlayer("nobody", Map.of("type", "hint", "from", "server", "payload", "x")),
            "Unknown player must report non-delivery");
    }

    @Test
    void unregisterStopsDelivery() throws Exception {
        registry.unregister("game-1", "bob", bob);

        registry.broadcastToGame("game-1", null, Map.of("type", "move", "from", "server", "payload", "x"));
        verify(alice).sendMessage(any(TextMessage.class));
        verify(bob, never()).sendMessage(any());
        assertFalse(registry.sendToPlayer("bob", Map.of("type", "hint", "from", "server", "payload", "x")));
    }

    /**
     * Two tabs is the ordinary case, and it used to make a player permanently unreachable.
     *
     * <p>{@code playerSessions} mapped a player to a SINGLE session. Opening a second tab
     * overwrote the first; closing that second tab removed the entry outright — while tab
     * one was still open and still playing. From then on, for the whole life of that
     * connection, the player was invisible: {@code isOnline} reported them offline to every
     * friend, and every player-targeted message — friend request, duel challenge, "You WON
     * the duel", achievement unlock, streak reminder — was dropped by
     * {@code deliverToPlayerLocal}. {@code sendToPlayer}'s boolean is swallowed by
     * MultiplayerBroadcaster, so nothing logged a failure and the push fallback never
     * engaged either.
     */
    @Test
    void closingOneOfTwoTabsLeavesThePlayerReachableOnTheOther() throws Exception {
        WebSocketSession bobSecondTab = mock(WebSocketSession.class);
        lenient().when(bobSecondTab.isOpen()).thenReturn(true);
        registry.register("game-1", "bob", bobSecondTab);

        registry.unregister("game-1", "bob", bobSecondTab);

        assertTrue(registry.isOnline("bob"),
            "one open socket is enough to be online — closing a second tab is not logging out");
        assertTrue(registry.sendToPlayer("bob", Map.of("type", "duel", "from", "server", "payload", "x")),
            "the surviving tab must still receive player-targeted messages");
        verify(bob).sendMessage(any(TextMessage.class));
    }

    /** A player-targeted message reaches every tab; delivering to only one is the same bug in miniature. */
    @Test
    void aPlayerTargetedMessageReachesEveryOpenTab() throws Exception {
        WebSocketSession bobSecondTab = mock(WebSocketSession.class);
        when(bobSecondTab.isOpen()).thenReturn(true);
        registry.register("game-1", "bob", bobSecondTab);

        assertTrue(registry.sendToPlayer("bob", Map.of("type", "hint", "from", "server", "payload", "x")));

        verify(bob).sendMessage(any(TextMessage.class));
        verify(bobSecondTab).sendMessage(any(TextMessage.class));
    }

    /** Presence only clears when the LAST socket closes. */
    @Test
    void presenceClearsOnlyWhenTheLastSocketCloses() {
        WebSocketSession bobSecondTab = mock(WebSocketSession.class);
        registry.register("game-1", "bob", bobSecondTab);

        registry.unregister("game-1", "bob", bobSecondTab);
        assertTrue(registry.isOnline("bob"));

        registry.unregister("game-1", "bob", bob);
        assertFalse(registry.isOnline("bob"), "with no sockets left the player is offline");
    }
}
