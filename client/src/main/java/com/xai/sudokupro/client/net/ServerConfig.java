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

    /**
     * ws:// or wss:// URI for the gameplay channel, optionally joining a game.
     *
     * <p>The id is percent-encoded. The server percent-DECODES the handshake's
     * {@code gameId} parameter, so an unencoded client is only correct by accident —
     * it happens to work for a bare UUID and for the colon in
     * {@code daily-2026-07-26:someuser}, and stops working the moment an id contains
     * a character that means something in a query string. Splicing raw text into a
     * URI also hands {@link URI#create} a chance to throw
     * {@code IllegalArgumentException} mid-game-switch, which is a much worse
     * failure than an escaped character.
     */
    public URI wsUri(String gameId) {
        String ws = baseUrl.replaceFirst("^http", "ws") + "/ws/game";
        return URI.create(gameId == null ? ws : ws + "?gameId=" + encodeQueryValue(gameId));
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** RFC 3986 percent-encoding of everything outside the unreserved set. */
    static String encodeQueryValue(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (byte b : raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
            }
        }
        return out.toString();
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
