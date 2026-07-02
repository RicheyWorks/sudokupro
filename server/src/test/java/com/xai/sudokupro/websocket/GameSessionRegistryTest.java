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
}
