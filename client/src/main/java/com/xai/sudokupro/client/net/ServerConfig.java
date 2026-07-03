package com.xai.sudokupro.client.net;

import java.net.URI;

/**
 * Connection settings for the remote SudokuPro server.
 *
 * <p>Resolution order: explicit constructor args → {@code SUDOKUPRO_SERVER} /
 * {@code SUDOKUPRO_USER} / {@code SUDOKUPRO_PASS} environment variables →
 * defaults ({@code http://localhost:8080}, {@code admin}).
 */
public record ServerConfig(String baseUrl, String username, String password) {

    public ServerConfig {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl required");
        baseUrl = baseUrl.replaceAll("/+$", "");
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            baseUrl = "http://" + baseUrl;
        }
    }

    /** Settings from the environment, for headless/dev use. Password may be empty. */
    public static ServerConfig fromEnvironment() {
        return new ServerConfig(
            env("SUDOKUPRO_SERVER", "http://localhost:8080"),
            env("SUDOKUPRO_USER", "admin"),
            env("SUDOKUPRO_PASS", ""));
    }

    public URI httpUri(String path) {
        return URI.create(baseUrl + path);
    }

    /** ws:// or wss:// URI for the gameplay channel, optionally joining a game. */
    public URI wsUri(String gameId) {
        String ws = baseUrl.replaceFirst("^http", "ws") + "/ws/game";
        return URI.create(gameId == null ? ws : ws + "?gameId=" + gameId);
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    @Override
    public String toString() {
        return "ServerConfig[" + baseUrl + ", user=" + username + "]"; // never print the password
    }
}
