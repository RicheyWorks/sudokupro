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
