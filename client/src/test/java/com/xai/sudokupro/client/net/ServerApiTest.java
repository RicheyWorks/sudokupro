package com.xai.sudokupro.client.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link ServerApi} against a real (loopback) HTTP server rather than mocks —
 * java.net.http.HttpClient is constructed internally with no seam to inject a fake, so a
 * throwaway {@link HttpServer} (JDK built-in, no extra test dependency) is the simplest
 * way to verify request construction (auth header, CSRF echo) and response handling.
 */
class ServerApiTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private HttpServer startServer() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        s.setExecutor(null);
        return s;
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private ServerConfig configFor(HttpServer s) {
        return new ServerConfig("http://localhost:" + s.getAddress().getPort(), "admin", "secret");
    }

    @Test
    void connectBootstrapsPlayerIdAndCsrfFromSession() throws IOException {
        server = startServer();
        server.createContext("/api/session", ex ->
            respond(ex, 200, "{\"playerId\":\"richmond\",\"csrfHeaderName\":\"X-XSRF-TOKEN\",\"csrfToken\":\"tok-123\"}"));
        server.start();

        ServerApi api = new ServerApi(configFor(server));
        String playerId = api.connect();

        assertEquals("richmond", playerId);
        assertEquals("richmond", api.playerId());
    }

    @Test
    void postRequestsCarryBasicAuthAndEchoTheBootstrappedCsrfToken() throws IOException {
        server = startServer();
        server.createContext("/api/session", ex ->
            respond(ex, 200, "{\"playerId\":\"richmond\",\"csrfHeaderName\":\"X-XSRF-TOKEN\",\"csrfToken\":\"tok-123\"}"));

        AtomicReference<String> seenAuth = new AtomicReference<>();
        AtomicReference<String> seenCsrf = new AtomicReference<>();
        server.createContext("/api/game/new", ex -> {
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            seenCsrf.set(ex.getRequestHeaders().getFirst("X-XSRF-TOKEN"));
            respond(ex, 200, "{\"gameId\":\"g1\",\"playerId\":\"richmond\",\"difficulty\":1,"
                + "\"chaosMode\":false,\"mirrorMode\":false,\"solved\":false,\"lives\":3,\"score\":0,"
                + "\"hintCount\":0,\"moveCount\":0,\"cells\":[]}");
        });
        server.start();

        ServerApi api = new ServerApi(configFor(server));
        api.connect();
        var board = api.newGame(1, false, false);

        assertEquals("g1", board.gameId());
        assertEquals("tok-123", seenCsrf.get());
        assertNotNull(seenAuth.get());
        assertTrue(seenAuth.get().startsWith("Basic "));
    }

    @Test
    void connectWith401SurfacesAsAuthFailureWithGenericMessage() throws IOException {
        server = startServer();
        server.createContext("/api/session", ex -> respond(ex, 401, "{}"));
        server.start();

        ServerApi api = new ServerApi(configFor(server));
        ApiException e = assertThrows(ApiException.class, api::connect);

        assertEquals(401, e.status());
        assertTrue(e.isAuthFailure());
        assertEquals("Authentication failed — check username/password.", e.getMessage());
    }

    @Test
    void nonAuthErrorSurfacesTheServersProblemDetail() throws IOException {
        server = startServer();
        server.createContext("/api/session", ex ->
            respond(ex, 200, "{\"playerId\":\"richmond\",\"csrfHeaderName\":\"X-XSRF-TOKEN\",\"csrfToken\":\"tok-123\"}"));
        server.createContext("/api/game/missing-game", ex ->
            respond(ex, 404, "{\"type\":\"about:blank\",\"title\":\"Unknown Game\",\"detail\":\"Game not found: missing-game\"}"));
        server.start();

        ServerApi api = new ServerApi(configFor(server));
        api.connect();
        ApiException e = assertThrows(ApiException.class, () -> api.getGame("missing-game"));

        assertEquals(404, e.status());
        assertFalse(e.isAuthFailure());
        assertEquals("Game not found: missing-game", e.getMessage());
    }

    @Test
    void hintParsesTheHintFieldFromTheResponseBody() throws IOException {
        server = startServer();
        server.createContext("/api/session", ex ->
            respond(ex, 200, "{\"playerId\":\"richmond\",\"csrfHeaderName\":\"X-XSRF-TOKEN\",\"csrfToken\":\"tok-123\"}"));
        server.createContext("/api/game/hint", ex -> respond(ex, 200, "{\"hint\":\"Try row 3, column 4\"}"));
        server.start();

        ServerApi api = new ServerApi(configFor(server));
        api.connect();

        assertEquals("Try row 3, column 4", api.hint("g1"));
    }

    @Test
    void leaderboardDeserializesTheFullList() throws IOException {
        server = startServer();
        server.createContext("/api/session", ex ->
            respond(ex, 200, "{\"playerId\":\"richmond\",\"csrfHeaderName\":\"X-XSRF-TOKEN\",\"csrfToken\":\"tok-123\"}"));
        server.createContext("/api/leaderboard", ex -> respond(ex, 200,
            "[{\"rank\":1,\"username\":\"richmond\",\"sortValue\":900,\"tier\":\"Cosmic\","
                + "\"cosmicDrip\":50,\"hypeMeter\":10,\"duelWins\":3}]"));
        server.start();

        ServerApi api = new ServerApi(configFor(server));
        api.connect();
        List<com.xai.sudokupro.model.api.LeaderboardEntry> board = api.leaderboard(10);

        assertEquals(1, board.size());
        assertEquals("richmond", board.get(0).username());
        assertEquals(1, board.get(0).rank());
    }

    @Test
    void unreachableServerWrapsTheIOExceptionInApiException() {
        // Nothing listening on this port — connection refused.
        ServerConfig config = new ServerConfig("http://localhost:1", "admin", "secret");
        ServerApi api = new ServerApi(config);

        ApiException e = assertThrows(ApiException.class, api::connect);
        assertTrue(e.getMessage().contains("Cannot reach server"));
    }
}
