package com.xai.sudokupro.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xai.sudokupro.client.net.Envelope;
import com.xai.sudokupro.client.net.GameSocket;
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

    private volatile SudokuBoard board;
    private volatile GameSocket socket;

    // Single-slot callbacks, rebound by MainStage when views are recreated.
    private volatile Runnable onBoardChanged = () -> {};
    private volatile Consumer<String> onChat = s -> {};
    private volatile Consumer<String> onEvent = s -> {};
    private volatile Notifier notifier = (t, m) -> {};

    public GameClient(ServerApi api) {
        this.api = api;
    }

    // ---- wiring -------------------------------------------------------------

    public void setOnBoardChanged(Runnable r)      { this.onBoardChanged = r != null ? r : () -> {}; }
    public void setOnChat(Consumer<String> c)      { this.onChat = c != null ? c : s -> {}; }
    public void setOnEvent(Consumer<String> c)     { this.onEvent = c != null ? c : s -> {}; }
    public void setNotifier(Notifier n)            { this.notifier = n != null ? n : (t, m) -> {}; }

    public String playerId()   { return api.playerId(); }
    public SudokuBoard board() { return board; }

    // ---- game lifecycle -------------------------------------------------------

    /** Creates a game on the server, rebuilds local state, and (re)joins the gameplay channel. */
    public synchronized SudokuBoard newGame(int difficulty, boolean chaos, boolean mirror) {
        closeSocket();
        BoardState state = api.newGame(difficulty, chaos, mirror);
        board = state.toBoard();
        socket = api.openSocket(state.gameId(), this::handleEnvelope,
            () -> notifier.notify("ui", "Connection to game lost"));
        logger.info("Joined game {} (difficulty {})", state.gameId(), difficulty);
        return board;
    }

    /** Re-fetches the authoritative state for the current game (e.g. Load button). */
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
     * Legacy Save semantics: persists the game's current state server-side
     * (the server writes through to the database on end). The gameplay channel
     * stays open; the next interaction transparently reloads the game.
     */
    public void save() {
        api.endGame(requireBoard().getGameId());
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
        current.applyExternalMove(move);
        requireSocket().send("move", move);
    }

    /** Server-side undo — the fresh board arrives as a "board" envelope. */
    public void undo() {
        requireSocket().send("undo", "");
    }

    /** Server-side redo — the fresh board arrives as a "board" envelope. */
    public void redo() {
        requireSocket().send("redo", "");
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

    public void sendChat(String text) {
        requireSocket().send("chat", text);
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
                case "chat" -> onChat.accept(envelope.from() + ": " + envelope.payloadText());
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

    /** After a server-rejected move, pull the authoritative board so the UI heals. */
    private void resyncQuietly() {
        try {
            GameSocket s = socket;
            if (s != null && s.isOpen()) s.send("sync", "");
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

    private GameSocket requireSocket() {
        GameSocket s = socket;
        if (s == null || !s.isOpen()) throw new IllegalStateException("Gameplay channel is not connected");
        return s;
    }

    private void closeSocket() {
        GameSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception e) {
                logger.debug("Socket close failed: {}", e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        closeSocket();
    }
}
