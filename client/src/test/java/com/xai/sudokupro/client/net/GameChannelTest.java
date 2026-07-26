package com.xai.sudokupro.client.net;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the defect class "a failed operation leaves the client permanently
 * broken with no way back".
 *
 * <p>Concretely: the desktop client's game switches were written as
 * <em>close the socket, then call the server, then open a new socket</em>. The
 * middle step is the one that fails — a 403 from spectating a competitive board,
 * a blip, a 500 on resume — and it failed after the teardown. The socket field
 * was left null, the previous game stayed on screen looking playable, and no code
 * path anywhere in the client opened a socket except the six switch methods, each
 * of which begins by closing. One refusal bricked the session for the life of the
 * process.
 *
 * <p>Two properties are tested here, and both are load-bearing:
 * <ol>
 *   <li>A failed switch is a <b>no-op</b> — the channel that was working still works.</li>
 *   <li>A channel that dies for any reason has a <b>way back</b> — automatic with
 *       backoff where retrying can help, manual always, and no retry at all where
 *       the server has said "never".</li>
 * </ol>
 */
class GameChannelTest {

    private final List<String> notices = new ArrayList<>();
    private final List<ConnectionState> states = new ArrayList<>();

    private GameChannel channelOver(FakeGameLink.Factory factory, ManualScheduler scheduler,
                                    ReconnectPolicy policy) {
        GameChannel channel = new GameChannel(factory, envelope -> { }, policy, scheduler);
        channel.setOnNotice((type, message) -> notices.add(type + ": " + message));
        channel.setOnState(states::add);
        return channel;
    }

    private GameChannel channelOver(FakeGameLink.Factory factory, ManualScheduler scheduler) {
        return channelOver(factory, scheduler, ReconnectPolicy.DEFAULT);
    }

    // =====================================================================
    // 1. A failed switch must not cost you the channel you had
    // =====================================================================

    /**
     * Reproduction of the shipped bug, exactly: playing game g1, ask to watch a
     * friend, the server refuses with 403. Before the fix the g1 socket was
     * already closed by then and nothing could reopen one.
     */
    @Test
    void aRefusedGameSwitchLeavesTheCurrentChannelConnected() {
        FakeGameLink.Factory factory = new FakeGameLink.Factory();
        ManualScheduler scheduler = new ManualScheduler();
        GameChannel channel = channelOver(factory, scheduler);
        channel.connect("g1");
        FakeGameLink live = factory.last();

        factory.failWith = new ApiException(403, "Competitive games cannot be spectated");
        assertThrows(ApiException.class, () -> channel.connect("daily-2026-07-26:ann"));

        assertTrue(channel.isConnected(), "The game the player was playing must survive a refused switch");
        assertEquals(ConnectionState.CONNECTED, channel.state());
        assertEquals("g1", channel.gameId());
        assertEquals(0, live.closeCalls, "The working link must not be torn down before the new one is up");
        assertDoesNotThrow(() -> channel.send("move", "x"));
        assertEquals(List.of("move"), live.sent);
    }

    /**
     * Even when the switch failure does cost the channel, there must be a way
     * back. This is the property whose complete absence was the finding: the
     * client had no reconnect entry point at all.
     */
    @Test
    void reconnectNowRevivesAChannelThatWentDown() {
        FakeGameLink.Factory factory = new FakeGameLink.Factory();
        ManualScheduler scheduler = new ManualScheduler();
        GameChannel channel = channelOver(factory, scheduler);
        channel.connect("g1");
        channel.close();
        assertFalse(channel.isConnected());

        channel.reconnectNow();

        assertTrue(channel.isConnected());
        assertEquals(ConnectionState.CONNECTED, channel.state());
        assertEquals(List.of("g1", "g1"), factory.requestedGameIds);
    }

    @Test
    void reconnectNowWithoutAnyGameSaysSoRatherThanFailingObscurely() {
        GameChannel channel = channelOver(new FakeGameLink.Factory(), new ManualScheduler());
        ConnectionException e = assertThrows(ConnectionException.class, channel::reconnectNow);
        assertTrue(e.getMessage().contains("No game to reconnect to"), e.getMessage());
    }

    /** The old link is released, but only after the replacement is up. */
    @Test
    void aSuccessfulSwitchClosesThePreviousLink() {
        FakeGameLink.Factory factory = new FakeGameLink.Factory();
        GameChannel channel = channelOver(factory, new ManualScheduler());
        channel.connect("g1");
        FakeGameLink first = factory.last();

        channel.connect("g2");

        assertEquals(1, first.closeCalls, "The previous link must be released once the new one is open");
        assertEquals("g2", channel.gameId());
        assertTrue(factory.last().isOpen());
    }

    // =====================================================================
    // 2. Sending on a dead channel
    // =====================================================================

    /**
     * The message the player sees. It used to be an {@code IllegalStateException}
     * that the board view rendered as "Invalid move: Gameplay channel is not
     * connected" — the wrong diagnosis and no suggested cure.
     */
    @Test
    void sendOnADeadChannelThrowsAConnectionExceptionCarryingTheState() {
        FakeGameLink.Factory factory = new FakeGameLink.Factory();
        ManualScheduler scheduler = new ManualScheduler();
        GameChannel channel = channelOver(factory, scheduler);
        channel.connect("g1");
        factory.last().die(1006, "abnormal closure");

        ConnectionException e = assertThrows(ConnectionException.class, () -> channel.send("move", "x"));

        assertEquals(ConnectionState.RECONNECTING, e.state());
        assertTrue(e.getMessage().toLowerCase().contains("reconnect"), e.getMessage());
    }

    // =====================================================================
    // 3. Automatic recovery
    // =====================================================================

    @Test
    void anUnexpectedCloseSchedulesARetryAndComesBack() {
        FakeGameLink.Factory factory = new FakeGameLink.Factory();
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger resyncs = new AtomicInteger();
        GameChannel channel = channelOver(factory, scheduler);
        channel.setOnResyncNeeded(resyncs::incrementAndGet);
        channel.connect("g1");

        factory.last().die(1006, "abnormal closure");

        assertEquals(ConnectionState.RECONNECTING, channel.state());
        assertEquals(1, scheduler.pendingCount(), "A lost link must schedule a retry");
        assertEquals(Duration.ofSeconds(1), scheduler.delays.get(0));

        scheduler.runNext();

        assertEquals(ConnectionState.CONNECTED, channel.state());
        assertTrue(channel.isConnected());
        assertEquals(List.of("g1", "g1"), factory.requestedGameIds, "It must rejoin the SAME game");
        assertEquals(1, resyncs.get(), "A reconnect can have missed updates, so the board must resync");
    }

    /**
     * 1008 is the server saying the join will never be accepted — an unknown game,
     * or a competitive board that may not be spectated. Retrying is a spin loop
     * against a settled answer, and the reason has to reach the player.
     */
    @Test
    void aPolicyViolationCloseIsNotRetriedAndReportsTheServersReason() {
        FakeGameLink.Factory factory = new FakeGameLink.Factory();
        ManualScheduler scheduler = new ManualScheduler();
        GameChannel channel = channelOver(factory, scheduler);
        channel.connect("daily-2026-07-26:ann");

        factory.last().die(1008, "Competitive games cannot be spectated");

        assertEquals(ConnectionState.FAILED, channel.state());
        assertEquals(0, scheduler.pendingCount(), "A refusal the server will repeat must not be retried");
        assertTrue(notices.stream().anyMatch(n -> n.startsWith("error: ")
            && n.contains("Competitive games cannot be spectated")), notices.toString());
    }

    /** Retrying stops at the budget, and stopping is a state the UI can show. */
    @Test
    void retryingStopsAfterTheBudgetAndLandsInFailed() {
        FakeGameLink.Factory factory = new FakeGameLink.Factory();
        ManualScheduler scheduler = new ManualScheduler();
        ReconnectPolicy policy =
            new ReconnectPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(4), 2.0);
        GameChannel channel = channelOver(factory, scheduler, policy);
        channel.connect("g1");

        factory.failWith = new ApiException("connect refused", new java.io.IOException("down"));
        factory.last().die(1006, "abnormal closure");
        scheduler.drain();

        assertEquals(ConnectionState.FAILED, channel.state());
        // One initial open + three retries, and no more.
        assertEquals(4, factory.requestedGameIds.size(), factory.requestedGameIds.toString());
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)),
            scheduler.delays, "Backoff must grow between retries");
    }

    /** Having given up automatically, the manual path must still work. */
    @Test
    void aFailedChannelCanStillBeRevivedByHand() {
        FakeGameLink.Factory factory = new FakeGameLink.Factory();
        ManualScheduler scheduler = new ManualScheduler();
        GameChannel channel = channelOver(factory, scheduler,
            new ReconnectPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(1), 1.0));
        channel.connect("g1");
        factory.failWith = new ApiException("connect refused", new java.io.IOException("down"));
        factory.last().die(1006, "abnormal closure");
        scheduler.drain();
        assertEquals(ConnectionState.FAILED, channel.state());

        factory.failWith = null;
        channel.reconnectNow();

        assertEquals(ConnectionState.CONNECTED, channel.state());
        assertTrue(channel.isConnected());
    }

    /** Rejected credentials will not fix themselves; the loop must not grind on them. */
    @Test
    void anAuthFailureDuringReconnectIsNotRetried() {
        FakeGameLink.Factory factory = new FakeGameLink.Factory();
        ManualScheduler scheduler = new ManualScheduler();
        GameChannel channel = channelOver(factory, scheduler);
        channel.connect("g1");

        factory.failWith = new ApiException(401, "Authentication failed");
        factory.last().die(1006, "abnormal closure");
        scheduler.drain();

        assertEquals(ConnectionState.FAILED, channel.state());
        assertEquals(2, factory.requestedGameIds.size(), "One retry proves it, a second is noise");
    }

    // =====================================================================
    // 4. A deliberate close is not a lost connection
    // =====================================================================

    /**
     * Every intentional game switch closes the old socket, and the resulting close
     * frame used to be announced as "Connection to game lost" — a scary,
     * meaningless message on a completely successful action.
     */
    @Test
    void aDeliberateCloseNeitherWarnsTheUserNorReconnects() {
        FakeGameLink.Factory factory = new FakeGameLink.Factory();
        ManualScheduler scheduler = new ManualScheduler();
        GameChannel channel = channelOver(factory, scheduler);
        channel.connect("g1");
        FakeGameLink link = factory.last();

        channel.close();
        link.die(1000, "bye");   // the close frame arrives after we asked for it

        assertEquals(ConnectionState.DISCONNECTED, channel.state());
        assertEquals(0, scheduler.pendingCount());
        assertTrue(notices.stream().noneMatch(n -> n.contains("lost")), notices.toString());
    }

    /**
     * The server refuses a competitive spectate <em>after</em> the handshake
     * succeeds, so the close can land in the gap between the link being opened and
     * being installed. Buffering it is the difference between an honest FAILED and
     * a channel that reports CONNECTED while holding a corpse.
     */
    @Test
    void aCloseArrivingBeforeTheLinkIsInstalledIsNotLost() {
        FakeGameLink.Factory factory = new FakeGameLink.Factory();
        ManualScheduler scheduler = new ManualScheduler();
        GameChannel channel = channelOver(factory, scheduler);
        factory.beforeReturning = link -> link.die(1008, "Competitive games cannot be spectated");

        channel.connect("daily-2026-07-26:ann");

        assertEquals(ConnectionState.FAILED, channel.state());
        assertFalse(channel.isConnected());
    }

    // =====================================================================
    // 5. Sends that fail after the transport accepted them
    // =====================================================================

    /**
     * The optimistic local update has already happened, so a send that dies on the
     * wire leaves the two boards disagreeing. Discarding the future — the shipped
     * behaviour — made that silent and permanent.
     */
    @Test
    void aSendThatFailsOnTheWireIsReportedAndTriggersAResync() {
        FakeGameLink.Factory factory = new FakeGameLink.Factory();
        AtomicInteger resyncs = new AtomicInteger();
        GameChannel channel = channelOver(factory, new ManualScheduler());
        channel.setOnResyncNeeded(resyncs::incrementAndGet);
        channel.connect("g1");

        factory.last().failSendOnTheWire("move", new java.io.IOException("broken pipe"));

        assertEquals(1, resyncs.get());
        assertTrue(notices.stream().anyMatch(n -> n.startsWith("error: ") && n.contains("move")),
            notices.toString());
    }

    // =====================================================================
    // 6. Shutdown
    // =====================================================================

    @Test
    void shutdownReleasesTheLinkAndTheRetryScheduler() {
        FakeGameLink.Factory factory = new FakeGameLink.Factory();
        ManualScheduler scheduler = new ManualScheduler();
        GameChannel channel = channelOver(factory, scheduler);
        channel.connect("g1");
        FakeGameLink link = factory.last();

        channel.shutdown();

        assertEquals(1, link.closeCalls);
        assertTrue(scheduler.shutdownCalled,
            "A pending reconnect timer must not outlive the client");
    }
}
