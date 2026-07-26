package com.xai.sudokupro.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the WebSocket handshake's {@code ?gameId=} parameter.
 *
 * <p><b>How the defect was found.</b> A front-end pass could not join a daily or duel game
 * over the socket. The web client builds its handshake URL with
 * {@code encodeURIComponent(gameId)}, and daily/duel ids contain a colon —
 * {@code daily-2026-07-26:someuser} — which encodes to {@code daily-2026-07-26%3Asomeuser}.
 * The interceptor read the query parameter without decoding it, so the session attribute
 * became the literal string with {@code %3A} in it, no such game existed, and the server
 * closed the socket with {@code 1008 Unknown game}. Measured directly against a running
 * server: the escaped form was refused, the raw colon connected.
 *
 * <p>Every id the ordinary "new game" flow produces is a bare UUID, which survives
 * percent-encoding unchanged — that is why this went unnoticed. Only the two id shapes that
 * embed a player name were affected, and those are exactly the multiplayer modes.
 *
 * <p>The client was changed to stop escaping the colon, which is legal in a query string,
 * but that only papers over it: any client that percent-encodes correctly — which is the
 * conservative thing for a client to do — would still fail. A handshake parameter has to be
 * decoded by the server. Hence the fix here rather than only there.
 *
 * <p>The malformed-input case is the second half: {@code UriComponents.decode()} throws
 * {@link IllegalArgumentException} on a truncated or invalid escape, and an exception
 * escaping {@code beforeHandshake} turns a garbage query string into a failed upgrade with
 * a stack trace in the log. That is trivially reachable by anyone typing a URL.
 */
class GameIdHandshakeInterceptorTest {

    private final RawWebSocketConfig.GameIdHandshakeInterceptor interceptor =
        new RawWebSocketConfig.GameIdHandshakeInterceptor();

    /** Runs the interceptor against a handshake to the given URI and returns the attributes. */
    private Map<String, Object> handshake(String uri) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create(uri));
        Map<String, Object> attributes = new HashMap<>();

        boolean proceed = interceptor.beforeHandshake(
            request, mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attributes);

        assertTrue(proceed, "the interceptor must never refuse the upgrade itself");
        return attributes;
    }

    /** The finding. A daily game id round-trips through percent-encoding intact. */
    @Test
    void aPercentEncodedGameIdIsDecodedBeforeItReachesTheSession() {
        Map<String, Object> attributes =
            handshake("wss://host/ws/game?gameId=daily-2026-07-26%3Asomeuser");

        assertEquals("daily-2026-07-26:someuser", attributes.get("gameId"),
            "the handshake attribute is looked up as a game id verbatim, so an undecoded "
                + "%3A means the game is never found and the socket closes with 1008");
    }

    /** Duel ids have the same shape and the same failure. */
    @Test
    void aPercentEncodedDuelGameIdIsAlsoDecoded() {
        assertEquals("duel-abc123:challenger",
            handshake("wss://host/ws/game?gameId=duel-abc123%3Achallenger").get("gameId"));
    }

    /** A client that does not escape the colon must keep working — it is legal in a query. */
    @Test
    void anUnescapedColonStillWorks() {
        assertEquals("daily-2026-07-26:someuser",
            handshake("wss://host/ws/game?gameId=daily-2026-07-26:someuser").get("gameId"));
    }

    /** The common case: a plain UUID is unaffected either way. */
    @Test
    void aPlainUuidGameIdIsPassedThroughUnchanged() {
        assertEquals("208e00e3-9f41-4f0e-8c2a-1b7d5e6a0011",
            handshake("wss://host/ws/game?gameId=208e00e3-9f41-4f0e-8c2a-1b7d5e6a0011")
                .get("gameId"));
    }

    /** %2B must become '+', not a space — the two are not interchangeable in a path segment. */
    @Test
    void encodedPlusIsDecodedAsAPlusRatherThanASpace() {
        assertEquals("game+one",
            handshake("wss://host/ws/game?gameId=game%2Bone").get("gameId"));
    }

    /**
     * The interceptor must not throw for anything that can actually reach it.
     *
     * <p>Worth being precise about what this does and does not prove. A syntactically
     * broken escape such as {@code %zz} is rejected by {@link java.net.URI} itself — the
     * request never gets as far as the interceptor — so a test feeding it one would only be
     * testing {@code URI.create}. The production code still catches
     * {@code IllegalArgumentException} as defence in depth, and that catch is deliberately
     * NOT claimed as covered here. What is covered is the reachable surface: odd but legal
     * query strings, which must decode or be ignored, never blow up the upgrade.
     */
    @Test
    void oddButLegalQueryStringsNeverFailTheHandshake() {
        String[] legalButAwkward = {
            "wss://host/ws/game?gameId=%25",              // an escaped percent sign
            "wss://host/ws/game?gameId=a%2Fb",            // an escaped slash
            "wss://host/ws/game?gameId=%E2%9C%93",        // multi-byte UTF-8
            "wss://host/ws/game?gameId=x&gameId=y",       // repeated parameter
            "wss://host/ws/game?other=1",                 // parameter absent
            "wss://host/ws/game?gameId",                  // parameter with no value at all
        };

        for (String uri : legalButAwkward) {
            assertDoesNotThrow(() -> handshake(uri), "handshake blew up on " + uri);
        }
    }

    /** A repeated parameter takes the first value — not a concatenation, not the last. */
    @Test
    void aRepeatedGameIdParameterTakesTheFirstValue() {
        assertEquals("first",
            handshake("wss://host/ws/game?gameId=first&gameId=second").get("gameId"),
            "picking the last value would let a crafted URL override a legitimate id");
    }

    /** No parameter at all means "create a new game", which is signalled by an absent key. */
    @Test
    void noGameIdParameterLeavesTheAttributeUnset() {
        assertFalse(handshake("wss://host/ws/game").containsKey("gameId"));
    }

    /** An empty or whitespace-only value is not a game id. */
    @Test
    void aBlankGameIdIsTreatedAsAbsent() {
        assertFalse(handshake("wss://host/ws/game?gameId=").containsKey("gameId"));
        assertFalse(handshake("wss://host/ws/game?gameId=%20%20").containsKey("gameId"),
            "an id that is only whitespace once decoded is still blank");
    }
}
