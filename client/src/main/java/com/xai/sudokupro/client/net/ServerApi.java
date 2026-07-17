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

    /**
     * Registers a new player account. Static and unauthenticated: it runs
     * before any credentials exist, so no Authorization header may be sent
     * (Basic credentials for an unknown user would 401 even on permitAll).
     */
    public static void register(String baseUrl, String username, String password) {
        try {
            String body = new ObjectMapper().writeValueAsString(
                java.util.Map.of("username", username, "password", password));
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/api/auth/register"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 201) return;
            String detail;
            try {
                detail = new ObjectMapper().readTree(response.body()).path("detail").asText("HTTP " + status);
            } catch (IOException e) {
                detail = "HTTP " + status;
            }
            throw new ApiException(status, detail);
        } catch (IOException e) {
            throw new ApiException("Cannot reach server at " + baseUrl + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Registration interrupted", e);
        }
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

    // ---- save / load --------------------------------------------------------

    /** Explicitly persists the game server-side so it can be resumed later. */
    public void saveGame(String gameId) {
        post("/api/game/" + gameId + "/save", Void.class);
    }

    /** The caller's unfinished, resumable games, newest first. */
    public List<BoardState> savedGames(int limit) {
        return get("/api/game/saved?limit=" + limit, new TypeReference<List<BoardState>>() {});
    }

    /** Resumes a previously saved game and returns its current state. */
    public BoardState resumeGame(String gameId) {
        return post("/api/game/" + gameId + "/resume", BoardState.class);
    }

    // ---- daily puzzle -------------------------------------------------------

    /** The caller's status for today's shared puzzle. */
    public com.xai.sudokupro.model.api.DailyStatus dailyStatus() {
        return get("/api/daily", com.xai.sudokupro.model.api.DailyStatus.class);
    }

    /** Joins today's puzzle (idempotent) and returns the caller's board. */
    public BoardState joinDaily() {
        return post("/api/daily/join", BoardState.class);
    }

    /** Today's fastest solvers. */
    public List<com.xai.sudokupro.model.api.DailyScore> dailyLeaderboard(int limit) {
        return get("/api/daily/leaderboard?limit=" + limit,
            new TypeReference<List<com.xai.sudokupro.model.api.DailyScore>>() {});
    }

    // ---- duels ---------------------------------------------------------------

    /** Challenges another player; returns the duel id they must accept. */
    public String challengeDuel(String opponent, int difficulty) {
        try {
            String body = mapper.writeValueAsString(
                java.util.Map.of("opponent", opponent, "difficulty", difficulty));
            JsonNode node = postJson("/api/duel/challenge", body, JsonNode.class);
            return node.path("duelId").asText();
        } catch (IOException e) {
            throw new ApiException("Malformed challenge request", e);
        }
    }

    /** Accepts a pending duel; returns the caller's board — the race starts now. */
    public BoardState acceptDuel(String duelId) {
        return post("/api/duel/" + duelId + "/accept", BoardState.class);
    }

    /** Declines a pending duel. */
    public void declineDuel(String duelId) {
        post("/api/duel/" + duelId + "/decline", Void.class);
    }

    /** The caller's duels (pending, active, finished). */
    public List<com.xai.sudokupro.model.api.DuelInfo> myDuels() {
        return get("/api/duel", new TypeReference<List<com.xai.sudokupro.model.api.DuelInfo>>() {});
    }

    // ---- sharing / social -------------------------------------------------------

    /** Share code for a game (gzipped grid, never the solution). */
    public String shareCode(String gameId) {
        return get("/api/game/" + gameId + "/share", JsonNode.class).path("code").asText();
    }

    /** Imports a shared puzzle as a fresh game of your own. */
    public BoardState importShared(String code) {
        try {
            return postJson("/api/game/import",
                mapper.writeValueAsString(java.util.Map.of("code", code)), BoardState.class);
        } catch (IOException e) {
            throw new ApiException("Malformed import request", e);
        }
    }

    /** The caller's friends with online flags (raw JSON rows: playerId, online). */
    public JsonNode friends() {
        return get("/api/friends", JsonNode.class);
    }

    public void requestFriend(String playerId) {
        post("/api/friends/request/" + playerId, Void.class);
    }

    public void acceptFriend(String playerId) {
        post("/api/friends/accept/" + playerId, Void.class);
    }

    /** Incoming friend requests (array of player names). */
    public JsonNode pendingFriends() {
        return get("/api/friends/pending", JsonNode.class);
    }

    // ---- tournament / shop / adaptive difficulty ---------------------------------

    public JsonNode tournamentStatus() {
        return get("/api/tournament", JsonNode.class);
    }

    public BoardState joinTournament(int puzzle) {
        return post("/api/tournament/" + puzzle + "/join", BoardState.class);
    }

    public JsonNode tournamentStandings(int limit) {
        return get("/api/tournament/standings?limit=" + limit, JsonNode.class);
    }

    /** Catalog prices and the caller's inventory. */
    public JsonNode powerUpShop() {
        return get("/api/powerups", JsonNode.class);
    }

    public void buyPowerUp(String type) {
        post("/api/powerups/buy/" + type, Void.class);
    }

    public void usePowerUp(String type, String gameId, String target) {
        StringBuilder q = new StringBuilder("/api/powerups/use/").append(type);
        char sep = '?';
        if (gameId != null) { q.append(sep).append("gameId=").append(gameId); sep = '&'; }
        if (target != null) { q.append(sep).append("target=").append(target); }
        post(q.toString(), Void.class);
    }

    /** The adaptive model's recommended difficulty (1-4) for the caller. */
    public int recommendedDifficulty() {
        return get("/api/game/recommended-difficulty", JsonNode.class).path("difficulty").asInt(2);
    }

    /** A player's current active game id, for spectating. Throws 404 ApiException if idle. */
    public String activeGameOf(String playerId) {
        return get("/api/game/active-of/" + playerId, JsonNode.class).path("gameId").asText();
    }

    // ---- economy ---------------------------------------------------------------

    /** The caller's wallet (gems, xp, level, duel record, hint price). */
    public JsonNode wallet() {
        return get("/api/economy/wallet", JsonNode.class);
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

    private <T> T postJson(String path, String jsonBody, Class<T> type) {
        HttpRequest.Builder builder = request(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
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
