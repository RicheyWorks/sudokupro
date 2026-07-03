package com.xai.sudokupro.client.net;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xai.sudokupro.model.api.BoardState;
import com.xai.sudokupro.model.api.EventInfo;
import com.xai.sudokupro.model.api.LeaderboardEntry;
import com.xai.sudokupro.model.api.SessionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

/**
 * REST + WebSocket access to a remote SudokuPro server (AUDIT follow-up:
 * client/server network separation). Authentication is HTTP Basic; CSRF uses
 * the double-submit cookie pattern bootstrapped by {@code GET /api/session}.
 */
public class ServerApi {

    private static final Logger logger = LoggerFactory.getLogger(ServerApi.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final ServerConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String basicAuth;

    private volatile String csrfHeaderName = "X-XSRF-TOKEN";
    private volatile String csrfToken;
    private volatile String playerId;

    public ServerApi(ServerConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
            .cookieHandler(new CookieManager())     // carries XSRF-TOKEN + session cookies
            .connectTimeout(TIMEOUT)
            .build();
        this.mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.basicAuth = "Basic " + Base64.getEncoder().encodeToString(
            (config.username() + ":" + config.password()).getBytes(StandardCharsets.UTF_8));
    }

    // ---- session ---------------------------------------------------------

    /**
     * Validates credentials and bootstraps CSRF. Must be called once before any
     * mutating request. Throws {@link ApiException} (401) on bad credentials.
     */
    public String connect() {
        SessionInfo info = get("/api/session", SessionInfo.class);
        this.csrfHeaderName = info.csrfHeaderName() != null ? info.csrfHeaderName() : "X-XSRF-TOKEN";
        this.csrfToken = info.csrfToken();
        this.playerId = info.playerId();
        logger.info("Connected to {} as {}", config.baseUrl(), playerId);
        return playerId;
    }

    public String playerId() {
        return playerId;
    }

    public ServerConfig config() {
        return config;
    }

    // ---- game lifecycle (REST) --------------------------------------------

    public BoardState newGame(int difficulty, boolean chaos, boolean mirror) {
        return post("/api/game/new?difficulty=" + difficulty + "&chaos=" + chaos + "&mirror=" + mirror,
            BoardState.class);
    }

    public BoardState getGame(String gameId) {
        return get("/api/game/" + gameId, BoardState.class);
    }

    public BoardState solve(String gameId) {
        return post("/api/game/" + gameId + "/solve", BoardState.class);
    }

    public void endGame(String gameId) {
        post("/api/game/" + gameId + "/end", Void.class);
    }

    public String hint(String gameId) {
        JsonNode node = get("/api/game/hint?gameId=" + gameId, JsonNode.class);
        return node.path("hint").asText("No hint available.");
    }

    public List<LeaderboardEntry> leaderboard(int limit) {
        return get("/api/leaderboard?limit=" + limit, new TypeReference<List<LeaderboardEntry>>() {});
    }

    public List<EventInfo> activeEvents() {
        return get("/api/events", new TypeReference<List<EventInfo>>() {});
    }

    // ---- gameplay channel (WebSocket) --------------------------------------

    /**
     * Opens the authenticated gameplay WebSocket, joining {@code gameId}.
     * Envelopes are delivered on the HttpClient's executor threads.
     */
    public GameSocket openSocket(String gameId, Consumer<Envelope> onEnvelope, Runnable onClose) {
        return GameSocket.open(httpClient, mapper, config.wsUri(gameId), basicAuth, onEnvelope, onClose);
    }

    // ---- plumbing ----------------------------------------------------------

    private <T> T get(String path, Class<T> type) {
        return exchange(request(path).GET().build(), type);
    }

    private <T> T get(String path, TypeReference<T> type) {
        String body = exchange(request(path).GET().build(), String.class);
        try {
            return mapper.readValue(body, type);
        } catch (IOException e) {
            throw new ApiException("Malformed response from " + path, e);
        }
    }

    private <T> T post(String path, Class<T> type) {
        HttpRequest.Builder builder = request(path).POST(HttpRequest.BodyPublishers.noBody());
        if (csrfToken != null) builder.header(csrfHeaderName, csrfToken);
        return exchange(builder.build(), type);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(config.httpUri(path))
            .timeout(TIMEOUT)
            .header("Authorization", basicAuth)
            .header("Accept", "application/json");
    }

    @SuppressWarnings("unchecked")
    private <T> T exchange(HttpRequest request, Class<T> type) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ApiException("Cannot reach server at " + config.baseUrl() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Request interrupted", e);
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new ApiException(status, errorDetail(status, response.body(), request.uri()));
        }
        if (type == Void.class) return null;
        if (type == String.class) return (T) response.body();
        try {
            return mapper.readValue(response.body(), type);
        } catch (IOException e) {
            throw new ApiException("Malformed response from " + request.uri(), e);
        }
    }

    private String errorDetail(int status, String body, URI uri) {
        if (status == 401) return "Authentication failed — check username/password.";
        if (status == 403) return "Access denied (403) for " + uri.getPath();
        try {
            JsonNode node = mapper.readTree(body);
            if (node.has("detail")) return node.get("detail").asText();
        } catch (IOException ignored) {
            // fall through to the generic message
        }
        return "Server returned HTTP " + status + " for " + uri.getPath();
    }
}
