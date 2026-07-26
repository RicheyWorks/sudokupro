package com.xai.sudokupro.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xai.sudokupro.client.net.ConnectionException;
import com.xai.sudokupro.client.net.ConnectionState;
import com.xai.sudokupro.client.net.Envelope;
import com.xai.sudokupro.client.net.GameChannel;
import com.xai.sudokupro.client.net.ServerApi;
import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.api.BoardState;
import com.xai.sudokupro.model.api.EventInfo;
import com.xai.sudokupro.model.api.LeaderboardEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * The UI's single gateway to the game (AUDIT follow-up: client/server network
 * separation). Holds a local, non-authoritative {@link SudokuBoard} rebuilt
 * from server {@link BoardState} snapshots; mutations are sent to the server
 * over the WebSocket channel, and remote updates flow back through it.
 */
public class GameClient implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(GameClient.class);

    private final ServerApi api;
    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final GameChannel channel;

    private volatile SudokuBoard board;

    // Single-slot callbacks, rebound by MainStage when views are recreated.
    private volatile Runnable onBoardChanged = () -> {};
    private volatile java.util.function.BiConsumer<String, String> onChat = (from, text) -> {};
    private volatile Consumer<String> onEvent = s -> {};
    private volatile Notifier notifier = (t, m) -> {};
    private volatile Consumer<ConnectionState> onConnectionState = s -> {};

    public GameClient(ServerApi api) {
        this(api, null);
    }

    /**
     * @param channel the gameplay channel, or null to build the production one over
     *                {@code api}. Injected by tests, which have no WebSocket server.
     */
    public GameClient(ServerApi api, GameChannel channel) {
        this.api = api;
        this.channel = channel != null ? channel : new GameChannel(api::openSocket, this::handleEnvelope);
        this.channel.setOnNotice((type, message) -> notifier.notify(type, message));
        this.channel.setOnState(state -> onConnectionState.accept(state));
        // After a reconnect — or after a send that failed on the wire — the local
        // board may have missed updates, so ask the server for the truth.
        this.channel.setOnResyncNeeded(this::resyncQuietly);
    }

    /** The envelope sink a caller-supplied {@link GameChannel} must be wired to. */
    public Consumer<Envelope> envelopeSink() {
        return this::handleEnvelope;
    }

    // ---- wiring -------------------------------------------------------------

    public void setOnBoardChanged(Runnable r)      { this.onBoardChanged = r != null ? r : () -> {}; }
    /** Receives {@code (speaker, text)} — rendering, including the speaker's name, is the UI's job. */
    public void setOnChat(java.util.function.BiConsumer<String, String> c) {
        this.onChat = c != null ? c : (from, text) -> {};
    }
    public void setOnEvent(Consumer<String> c)     { this.onEvent = c != null ? c : s -> {}; }
    public void setNotifier(Notifier n)            { this.notifier = n != null ? n : (t, m) -> {}; }
    public void setOnConnectionState(Consumer<ConnectionState> c) {
        this.onConnectionState = c != null ? c : s -> {};
    }

    public String playerId()   { return api.playerId(); }
    public SudokuBoard board() { return board; }

    // ---- connection ---------------------------------------------------------

    public ConnectionState connectionState() { return channel.state(); }

    public boolean isConnected() { return channel.isConnected(); }

    /**
     * Rejoins the gameplay channel for the game that is on screen. This is the
     * path that did not exist: nothing outside the six game-switch methods ever
     * opened a socket, and each of those begins by closing the one you have, so a
     * single failure anywhere in a switch left the session with no way back.
     *
     * <p>Blocking — call it off the FX thread.
     */
    public synchronized void reconnect() {
        SudokuBoard current = board;
        String target = current != null ? current.getGameId() : channel.gameId();
        if (target == null) {
            throw new ConnectionException("No game to reconnect to — start or resume a game first");
        }
        channel.connect(target);
        resyncQuietly();
        logger.info("Rejoined game {}", target);
    }

    // ---- game lifecycle -------------------------------------------------------

    /**
     * Adopts a game the server just handed us: the channel is switched first, and
     * the local board is replaced only once that succeeded.
     *
     * <p>Order is the whole point. Every switch used to tear the channel down
     * before the REST call that might fail, so a refusal (spectating a competitive
     * board), a blip, or a 500 left the client with no channel, no way to open one,
     * and the previous game still on screen looking playable. Now a failed switch
     * throws with the player exactly where they were — still connected, still
     * playing the game they had.
     */
    private SudokuBoard adopt(BoardState state) {
        channel.connect(state.gameId());
        SudokuBoard adopted = state.toBoard();
        this.board = adopted;
        return adopted;
    }

    /** Creates a game on the server, rebuilds local state, and (re)joins the gameplay channel. */
    public synchronized SudokuBoard newGame(int difficulty, boolean chaos, boolean mirror) {
        BoardState state = api.newGame(difficulty, chaos, mirror);
        SudokuBoard adopted = adopt(state);
        logger.info("Joined game {} (difficulty {})", state.gameId(), difficulty);
        return adopted;
    }

    /** Re-fetches the authoritative state for the current game. */
    public SudokuBoard refresh() {
        SudokuBoard current = requireBoard();
        board = api.getGame(current.getGameId()).toBoard();
        onBoardChanged.run();
        return board;
    }

    public void endGame() {
        SudokuBoard current = board;
        if (current != null) {
            api.endGame(current.getGameId());
        }
        closeSocket();
    }

    /**
     * Persists the current game server-side (full grid, via the server's
     * cells_json snapshot) WITHOUT leaving it — play continues normally and
     * the game can be resumed later, even after a server restart.
     * (Replaces the legacy semantics that quietly ended the game.)
     */
    public void save() {
        api.saveGame(requireBoard().getGameId());
    }

    /** The player's saved (unfinished, resumable) games, newest first. */
    public List<BoardState> savedGames(int limit) {
        return api.savedGames(limit);
    }

    /**
     * Resumes a previously saved game: the server loads it back into its
     * active set, local state is rebuilt from the returned snapshot, and the
     * gameplay channel is (re)joined under the resumed gameId.
     */
    public synchronized SudokuBoard resumeGame(String gameId) {
        BoardState state = api.resumeGame(gameId);
        SudokuBoard adopted = adopt(state);
        logger.info("Resumed game {}", state.gameId());
        return adopted;
    }

    // ---- moves ------------------------------------------------------------------

    /**
     * Applies a move optimistically to the local board and sends it to the
     * server. The server is authoritative: an invalid move comes back as an
     * "error" envelope and the board is resynced.
     */
    public void applyMove(EnhancedMove move) {
        SudokuBoard current = requireBoard();
        // Clears (newVal 0) skip validation: isValidMove rejects duplicates of the
        // value in row/col/box, which is meaningless for emptying a cell.
        if (move.newVal() != 0 && !current.isValidMove(move.row(), move.col(), move.newVal())) {
            throw new IllegalArgumentException(
                "Invalid move: " + (move.newVal() == 0 ? "clear" : move.newVal())
                + " at (" + (move.row() + 1) + "," + (move.col() + 1) + ")");
        }
        // Send BEFORE the optimistic local update. Applying first meant that a
        // refused send (dead channel) still mutated the local board, so the two
        // sides diverged on the very move the player was told had failed.
        channel.send("move", move);
        current.applyExternalMove(move);
    }

    /**
     * Joins today's shared daily puzzle (idempotent server-side): rebuilds
     * local state from the returned copy and (re)joins its gameplay channel.
     */
    public synchronized SudokuBoard joinDaily() {
        BoardState state = api.joinDaily();
        SudokuBoard adopted = adopt(state);
        logger.info("Joined daily puzzle game {}", state.gameId());
        return adopted;
    }

    /** The caller's daily status: joined/completed/streak. */
    public com.xai.sudokupro.model.api.DailyStatus dailyStatus() {
        return api.dailyStatus();
    }

    /** Today's fastest solvers. */
    public List<com.xai.sudokupro.model.api.DailyScore> dailyLeaderboard(int limit) {
        return api.dailyLeaderboard(limit);
    }

    /**
     * Spectates another player's game: loads its current state read-only and
     * joins the gameplay channel to watch live broadcasts. The server rejects
     * any mutation a spectator tries to send.
     */
    public synchronized SudokuBoard spectate(String gameId) {
        // The 403 that used to brick the session: the server refuses a read of a
        // competitive board, and this call is where that refusal lands.
        BoardState state = api.getGame(gameId);
        SudokuBoard adopted = adopt(state);
        logger.info("Spectating game {}", gameId);
        return adopted;
    }

    // ---- duels -----------------------------------------------------------------

    /** Challenges another player; returns the duel id. */
    public String challengeDuel(String opponent, int difficulty) {
        return api.challengeDuel(opponent, difficulty);
    }

    /** Accepts a duel and enters the race: local board + gameplay channel. */
    public synchronized SudokuBoard acceptDuel(String duelId) {
        BoardState state = api.acceptDuel(duelId);
        SudokuBoard adopted = adopt(state);
        logger.info("Duel {} accepted — playing game {}", duelId, state.gameId());
        return adopted;
    }

    public void declineDuel(String duelId) {
        api.declineDuel(duelId);
    }

    public List<com.xai.sudokupro.model.api.DuelInfo> myDuels() {
        return api.myDuels();
    }

    /** The caller's wallet (gems, xp, level, duel record, hint price). */
    public com.fasterxml.jackson.databind.JsonNode wallet() {
        return api.wallet();
    }

    // ---- tournament / friends / shop passthroughs -------------------------------

    public com.fasterxml.jackson.databind.JsonNode tournamentStatus() { return api.tournamentStatus(); }
    public com.fasterxml.jackson.databind.JsonNode tournamentStandings(int limit) { return api.tournamentStandings(limit); }
    public com.fasterxml.jackson.databind.JsonNode friends() { return api.friends(); }
    public void requestFriend(String name) { api.requestFriend(name); }
    public void acceptFriend(String name) { api.acceptFriend(name); }
    public com.fasterxml.jackson.databind.JsonNode pendingFriends() { return api.pendingFriends(); }
    public com.fasterxml.jackson.databind.JsonNode powerUpShop() { return api.powerUpShop(); }
    public void buyPowerUp(String type) { api.buyPowerUp(type); }
    public void usePowerUp(String type, String gameId, String target) { api.usePowerUp(type, gameId, target); }
    public int recommendedDifficulty() { return api.recommendedDifficulty(); }
    public String activeGameOf(String playerId) { return api.activeGameOf(playerId); }

    /** Joins tournament puzzle n and rejoins the gameplay channel. */
    public synchronized SudokuBoard joinTournament(int puzzle) {
        return adopt(api.joinTournament(puzzle));
    }

    /** Server-side undo — the fresh board arrives as a "board" envelope. */
    public void undo() {
        channel.send("undo", "");
    }

    /** Server-side redo — the fresh board arrives as a "board" envelope. */
    public void redo() {
        channel.send("redo", "");
    }

    // ---- assists -----------------------------------------------------------------

    /** Fetches a hint, then resyncs the board (hints may mutate server state). */
    public String hint() {
        SudokuBoard current = requireBoard();
        String hint = api.hint(current.getGameId());
        board = api.getGame(current.getGameId()).toBoard();
        onBoardChanged.run();
        return hint;
    }

    /** Auto-solves on the server and rebuilds local state from the result. */
    public void solve() {
        SudokuBoard current = requireBoard();
        board = api.solve(current.getGameId()).toBoard();
        onBoardChanged.run();
    }

    // ---- social ---------------------------------------------------------------------

    /**
     * Sends chat text as text.
     *
     * <p>The UI used to pass in a fully rendered line — {@code "[12:04:31] ann: hi"} —
     * and the server relays chat with the sender's name in the envelope's
     * {@code from} field, which the receiving client also prepends. Everyone except
     * the sender saw {@code "ann: [12:04:31] ann: hi"}. Rendering belongs to the
     * display side; the wire carries the message.
     */
    public void sendChat(String text) {
        channel.send("chat", text);
    }

    public List<LeaderboardEntry> leaderboard(int limit) {
        return api.leaderboard(limit);
    }

    public List<EventInfo> activeEvents() {
        return api.activeEvents();
    }

    // ---- incoming ---------------------------------------------------------------------

    private void handleEnvelope(Envelope envelope) {
        try {
            switch (envelope.type()) {
                case "move" -> {
                    EnhancedMove move = mapper.treeToValue(envelope.payload(), EnhancedMove.class);
                    SudokuBoard current = board;
                    if (current != null) current.applyExternalMove(move);
                    onBoardChanged.run();
                }
                case "batch_moves" -> {
                    List<EnhancedMove> moves = mapper.readerForListOf(EnhancedMove.class)
                        .readValue(envelope.payload());
                    SudokuBoard current = board;
                    if (current != null) current.applyBatchMoves(moves);
                    onBoardChanged.run();
                }
                case "board" -> {
                    board = mapper.treeToValue(envelope.payload(), BoardState.class).toBoard();
                    onBoardChanged.run();
                }
                case "chat" -> onChat.accept(envelope.from(), envelope.payloadText());
                case "hint" -> notifier.notify("hint", envelope.payloadText());
                case "error" -> {
                    notifier.notify("error", envelope.payload() != null
                        ? envelope.payload().path("detail").asText(envelope.payloadText())
                        : "Server reported an error");
                    resyncQuietly();
                }
                case "health" -> { /* server liveness ping — nothing to do */ }
                default -> onEvent.accept(describe(envelope));
            }
        } catch (Exception e) {
            logger.warn("Failed to handle [{}] envelope: {}", envelope.type(), e.getMessage());
        }
    }

    /** join/leave/status/event/gameStart/gameEnd → one human-readable line. */
    private String describe(Envelope envelope) {
        return switch (envelope.type()) {
            case "join"  -> envelope.payload() != null && envelope.payload().has("player")
                ? envelope.payload().get("player").asText() + " joined the game"
                : envelope.from() + " joined the game";
            case "leave" -> envelope.from() + " left the game";
            case "status" -> "Game status: " + (envelope.payload() != null
                ? envelope.payload().path("status").asText(envelope.payloadText())
                : envelope.payloadText());
            default -> envelope.payloadText();
        };
    }

    /** After a server-rejected move, or a reconnect, pull the authoritative board so the UI heals. */
    private void resyncQuietly() {
        try {
            if (channel.isConnected()) channel.send("sync", "");
        } catch (Exception e) {
            logger.debug("Resync request failed: {}", e.getMessage());
        }
    }

    // ---- internals -----------------------------------------------------------------------

    private SudokuBoard requireBoard() {
        SudokuBoard current = board;
        if (current == null) throw new IllegalStateException("No active game — create one first");
        return current;
    }

    private void closeSocket() {
        channel.close();
    }

    @Override
    public void close() {
        // shutdown(), not close(): also stops the reconnect scheduler, so nothing
        // is left holding a thread once the window is gone.
        channel.shutdown();
    }
}
