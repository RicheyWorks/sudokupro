package com.xai.sudokupro.client.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {

    @Test
    void rejectsBlankBaseUrl() {
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig("", "admin", "pw"));
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig(null, "admin", "pw"));
    }

    @Test
    void prependsHttpSchemeWhenMissing() {
        ServerConfig config = new ServerConfig("localhost:8080", "admin", "pw");
        assertEquals("http://localhost:8080", config.baseUrl());
    }

    @Test
    void preservesExplicitHttpsScheme() {
        ServerConfig config = new ServerConfig("https://sudokupro.example.com", "admin", "pw");
        assertEquals("https://sudokupro.example.com", config.baseUrl());
    }

    @Test
    void stripsTrailingSlashes() {
        ServerConfig config = new ServerConfig("http://localhost:8080///", "admin", "pw");
        assertEquals("http://localhost:8080", config.baseUrl());
    }

    @Test
    void httpUriJoinsPathOntoBaseUrl() {
        ServerConfig config = new ServerConfig("http://localhost:8080", "admin", "pw");
        assertEquals("http://localhost:8080/api/game/g1", config.httpUri("/api/game/g1").toString());
    }

    /**
     * The server percent-DECODES the handshake's gameId, so the client must
     * percent-ENCODE it. Splicing the raw id in was correct only by accident — for
     * a bare UUID and for the colon in a daily id — and handed {@code URI.create}
     * a chance to throw mid-game-switch on anything else.
     */
    @Test
    void wsUriPercentEncodesTheGameId() {
        ServerConfig config = new ServerConfig("http://localhost:8080", "admin", "pw");

        assertEquals("ws://localhost:8080/ws/game?gameId=daily-2026-07-26%3Aann",
            config.wsUri("daily-2026-07-26:ann").toString());
    }

    /** An id with a query-significant character must round-trip, not truncate the URI. */
    @Test
    void wsUriEscapesCharactersThatWouldOtherwiseEndTheParameter() {
        ServerConfig config = new ServerConfig("http://localhost:8080", "admin", "pw");
        java.net.URI uri = config.wsUri("weird&id=1 x");

        assertEquals("gameId=weird%26id%3D1%20x", uri.getRawQuery());
        assertEquals("weird&id=1 x",
            java.net.URLDecoder.decode(uri.getRawQuery().substring("gameId=".length()),
                java.nio.charset.StandardCharsets.UTF_8),
            "it must decode back to the id we asked for");
    }

    @Test
    void wsUriSwapsHttpForWsAndAppendsGameId() {
        ServerConfig config = new ServerConfig("http://localhost:8080", "admin", "pw");
        assertEquals("ws://localhost:8080/ws/game", config.wsUri(null).toString());
        assertEquals("ws://localhost:8080/ws/game?gameId=g1", config.wsUri("g1").toString());
    }

    @Test
    void wsUriSwapsHttpsForWss() {
        ServerConfig config = new ServerConfig("https://sudokupro.example.com", "admin", "pw");
        assertEquals("wss://sudokupro.example.com/ws/game", config.wsUri(null).toString());
    }

    @Test
    void toStringNeverIncludesThePassword() {
        ServerConfig config = new ServerConfig("http://localhost:8080", "admin", "super-secret");
        assertFalse(config.toString().contains("super-secret"));
    }
}
