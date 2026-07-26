package com.xai.sudokupro.client;

import com.xai.sudokupro.client.net.ApiException;
import com.xai.sudokupro.client.net.CloseListener;
import com.xai.sudokupro.client.net.ConnectionException;
import com.xai.sudokupro.client.net.ConnectionState;
import com.xai.sudokupro.client.net.Envelope;
import com.xai.sudokupro.client.net.GameChannel;
import com.xai.sudokupro.client.net.GameLink;
import com.xai.sudokupro.client.net.GameLinkFactory;
import com.xai.sudokupro.client.net.ReconnectPolicy;
import com.xai.sudokupro.client.net.ServerApi;
import com.xai.sudokupro.client.net.ServerConfig;
import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.SudokuCell;
import com.xai.sudokupro.model.SudokuCellView;
import com.xai.sudokupro.model.api.BoardState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the worst defect in the desktop client, at the level the player meets it:
 * <b>one failed game switch and the session is over</b>.
 *
 * <p>The sequence, verbatim from the shipped code. You are playing game g1. You
 * open Friends and click "watch" on someone who is halfway through today's daily.
 * {@code spectate()} closed your socket, then called {@code GET /api/game/…},
 * which the server refuses with 403 because competitive boards may not be
 * spectated. The exception propagated to a background thread that logged it and
 * showed a toast. Your socket field was now null; your old board was still on
 * screen, still looking playable; and the only code in the entire client that
 * opens a socket is the six switch methods, each of which begins by closing.
 * Every move, undo, redo and chat threw for the rest of the process.
 *
 * <p>These tests drive the real {@link GameClient} over a stubbed
 * {@link ServerApi} and a {@link GameChannel} on a fake transport, so the
 * ordering — which is the actual bug — is exercised in production code.
 */
class GameClientSwitchTest {

    /** A board snapshot of the shape the server sends. */
    private static BoardState board(String gameId) {
        List<List<SudokuCellView>> cells = new ArrayList<>();
        for (int r = 0; r < 9; r++) {
            List<SudokuCellView> row = new ArrayList<>();
            for (int c = 0; c < 9; c++) row.add(new SudokuCellView(new SudokuCell()));
            cells.add(row);
        }
        return new BoardState(gameId, "ann", 2, false, false, false, 3, 0, 0, 0, cells);
    }

    /** A ServerApi whose REST calls are scripted. Nothing here touches a network. */
    private static final class StubApi extends ServerApi {
        RuntimeException getGameFailure;
        RuntimeException newGameFailure;

        StubApi() {
            super(new ServerConfig("http://localhost:1", "ann", "pw"));
        }

        @Override
        public BoardState getGame(String gameId) {
            if (getGameFailure != null) throw getGameFailure;
            return board(gameId);
        }

        @Override
        public BoardState newGame(int difficulty, boolean chaos, boolean mirror) {
            if (newGameFailure != null) throw newGameFailure;
            return board("g-new");
        }

        @Override
        public String playerId() {
            return "ann";
        }
    }

    /** A link with no socket behind it. */
    private static final class StubLink implements GameLink {
        final List<String> sent = new ArrayList<>();
        final CloseListener closeListener;
        boolean open = true;

        StubLink(CloseListener closeListener) {
            this.closeListener = closeListener;
        }

        @Override public void send(String type, Object payload) {
            if (!open) throw new ConnectionException("closed");
            sent.add(type);
        }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
        @Override public void setSendFailureListener(BiConsumer<String, Throwable> listener) { }
    }

    private static final class StubFactory implements GameLinkFactory {
        final List<StubLink> opened = new ArrayList<>();
        RuntimeException failWith;

        @Override
        public GameLink open(String gameId, Consumer<Envelope> onEnvelope, CloseListener onClose) {
            if (failWith != null) throw failWith;
            StubLink link = new StubLink(onClose);
            opened.add(link);
            return link;
        }

        StubLink last() {
            return opened.get(opened.size() - 1);
        }
    }

    private StubApi api;
    private StubFactory factory;
    private GameClient client;
    private final List<String> notices = new ArrayList<>();

    @BeforeEach
    void setUp() {
        api = new StubApi();
        factory = new StubFactory();
        GameChannel channel = new GameChannel(factory, envelope -> { },
            new ReconnectPolicy(2, Duration.ofSeconds(1), Duration.ofSeconds(2), 2.0),
            (task, delay) -> { /* no automatic retries in these tests */ });
        client = new GameClient(api, channel);
        // The channel is constructed before the client, so wire its envelope sink in
        // the same way the production constructor does.
        channel.setOnResyncNeeded(() -> { });
        client.setNotifier((type, message) -> notices.add(type + ": " + message));
    }

    /** The reproduction, end to end. */
    @Test
    void aRefusedSpectateLeavesTheChannelUsable() {
        client.newGame(2, false, false);
        StubLink live = factory.last();
        assertTrue(client.isConnected());

        api.getGameFailure = new ApiException(403, "Competitive games cannot be spectated");
        ApiException refusal = assertThrows(ApiException.class,
            () -> client.spectate("daily-2026-07-26:bob"));
        assertEquals(403, refusal.status());

        assertTrue(client.isConnected(), "A refused spectate must not cost you your own game");
        assertEquals(ConnectionState.CONNECTED, client.connectionState());
        assertTrue(live.isOpen());
        assertDoesNotThrow(() -> client.applyMove(new EnhancedMove(0, 0, 0, 5, SudokuCell.MoveSource.PLAYER)));
        assertEquals(List.of("move"), live.sent);
    }

    /** The board must not change either: you are still playing the game you were playing. */
    @Test
    void aFailedSwitchLeavesTheOldBoardAndChannelInPlace() {
        client.newGame(2, false, false);
        String before = client.board().getGameId();

        factory.failWith = new ApiException("connect refused", new java.io.IOException("down"));
        assertThrows(ApiException.class, () -> client.spectate("g-other"));

        assertEquals(before, client.board().getGameId(),
            "A switch that never completed must not leave the wrong board on screen");
    }

    /** After any loss, the player has a way back — this is what did not exist. */
    @Test
    void reconnectRejoinsTheGameThatIsOnScreen() {
        client.newGame(2, false, false);
        String gameId = client.board().getGameId();
        factory.last().open = false;
        assertFalse(client.isConnected());

        client.reconnect();

        assertTrue(client.isConnected());
        assertEquals(gameId, client.board().getGameId());
        assertEquals(List.of("sync"), factory.last().sent,
            "A rejoin must pull the authoritative board — it may have missed updates");
    }

    @Test
    void reconnectWithoutAGameReportsItRatherThanThrowingSomethingOpaque() {
        assertThrows(ConnectionException.class, () -> client.reconnect());
    }

    /**
     * The optimistic local update must not happen when the send could not. Applying
     * first meant the board the player sees gained a digit the server never heard
     * about, on the very move they were told had failed.
     */
    @Test
    void aMoveThatCannotBeSentIsNotAppliedLocally() {
        client.newGame(2, false, false);
        factory.last().open = false;

        assertThrows(ConnectionException.class,
            () -> client.applyMove(new EnhancedMove(4, 4, 0, 7, SudokuCell.MoveSource.PLAYER)));

        assertEquals(0, client.board().getBoard()[4][4].getValue(),
            "A move that never left the client must not be on the client's board either");
    }

    /**
     * A move that fails because the channel is down must be reported as a
     * connection problem, not as an invalid move — the board view keys its wording
     * off this exception type.
     */
    @Test
    void aMoveOnADeadChannelThrowsConnectionExceptionNotIllegalState() {
        client.newGame(2, false, false);
        factory.last().open = false;

        assertThrows(ConnectionException.class,
            () -> client.applyMove(new EnhancedMove(0, 0, 0, 5, SudokuCell.MoveSource.PLAYER)));
        assertThrows(ConnectionException.class, () -> client.undo());
        assertThrows(ConnectionException.class, () -> client.sendChat("hello"));
    }

    /** Chat goes over the wire as the message; the speaker's name is added by the display. */
    @Test
    void incomingChatIsHandedOverUnlabelled() {
        List<String> chats = new ArrayList<>();
        client.setOnChat((from, text) -> chats.add(from + "|" + text));
        client.newGame(2, false, false);

        client.envelopeSink().accept(new Envelope("chat", "bob",
            new com.fasterxml.jackson.databind.node.TextNode("gg")));

        assertEquals(List.of("bob|gg"), chats,
            "The payload must be the message alone, or every peer sees the name twice");
    }

    /** A successful switch does move you, and releases the old link. */
    @Test
    void aSuccessfulSwitchAdoptsTheNewGame() {
        client.newGame(2, false, false);
        StubLink first = factory.last();

        client.spectate("g-watch");

        assertEquals("g-watch", client.board().getGameId());
        assertFalse(first.isOpen(), "The previous link is released once the new one is up");
        assertTrue(client.isConnected());
    }
}
