package com.xai.sudokupro.client.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The same reconnect guarantees as {@link GameChannelTest}, but over a real
 * WebSocket handshake instead of a fake link.
 *
 * <p>{@link GameSocket#open} is the one part of the channel a fake cannot reach,
 * and it is the part the reconnect policy leans on hardest: a refused upgrade has
 * to arrive as an {@link ApiException} carrying the HTTP status (or the loop
 * retries credentials that will never be accepted), and a server close has to
 * arrive with its status code (or the loop cannot tell "not now" from "never").
 * Both were true of the shipped code in neither case — every handshake failure
 * collapsed into a status-less message, and the close callback was a bare
 * {@code Runnable}.
 *
 * <p>{@link RawWebSocketServer} is a sixty-line RFC 6455 responder; the client
 * module has no servlet container and the JDK ships only a WebSocket client.
 */
class GameChannelTransportTest {

    private RawWebSocketServer server;
    private HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = new RawWebSocketServer();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
    }

    private GameLinkFactory factoryOver(RawWebSocketServer target) {
        return (gameId, onEnvelope, onClose) -> GameSocket.open(
            httpClient, mapper, URI.create(target.uriBase() + "/ws/game?gameId=" + gameId),
            "Basic YW5uOnB3", onEnvelope, onClose);
    }

    @Test
    void aRealHandshakeProducesAnOpenChannelCarryingTheCredentials() throws Exception {
        GameChannel channel = new GameChannel(factoryOver(server), envelope -> { });

        channel.connect("g1");

        assertTrue(server.awaitFirstHandshake(5_000));
        assertTrue(channel.isConnected());
        assertEquals(ConnectionState.CONNECTED, channel.state());
        assertEquals(List.of("Basic YW5uOnB3"), server.seenAuthHeaders());
        assertTrue(server.seenRequestLines().get(0).contains("gameId=g1"),
            server.seenRequestLines().toString());
        channel.shutdown();
    }

    /**
     * A refused upgrade must carry its HTTP status all the way to the policy. With
     * the status erased — the shipped behaviour — a wrong password looked exactly
     * like a server that was merely down, and the loop retried it to exhaustion.
     */
    @Test
    void arefusedUpgradeArrivesAsAnApiExceptionWithTheHttpStatus() {
        server.refuseWithStatus = 401;
        GameChannel channel = new GameChannel(factoryOver(server), envelope -> { });

        ApiException e = assertThrows(ApiException.class, () -> channel.connect("g1"));

        assertEquals(401, e.status());
        assertTrue(e.isAuthFailure());
        assertTrue(ReconnectPolicy.DEFAULT.shouldRetryAfterFailure(1, e) == false,
            "A rejected credential must stop the loop, not feed it");
        channel.shutdown();
    }

    /**
     * The competitive-spectate refusal, as the server really performs it: the
     * handshake succeeds and the close frame follows. It has to land as FAILED with
     * the server's own reason, and it must not be retried.
     */
    @Test
    void aServerCloseAfterTheHandshakeArrivesWithItsStatusAndReason() throws Exception {
        server.closeImmediatelyWith = 1008;
        server.closeReason = "Competitive games cannot be spectated";
        List<String> notices = new ArrayList<>();
        ManualScheduler scheduler = new ManualScheduler();
        GameChannel channel = new GameChannel(factoryOver(server), envelope -> { },
            ReconnectPolicy.DEFAULT, scheduler);
        channel.setOnNotice((type, message) -> notices.add(type + ": " + message));

        channel.connect("daily-2026-07-26:ann");

        long deadline = System.currentTimeMillis() + 5_000;
        while (channel.state() != ConnectionState.FAILED && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertEquals(ConnectionState.FAILED, channel.state());
        assertEquals(0, scheduler.pendingCount(), "1008 must not be retried");
        assertTrue(notices.stream().anyMatch(n -> n.contains("Competitive games cannot be spectated")),
            notices.toString());
        channel.shutdown();
    }

    /** The whole point, over a real socket: the connection dies and comes back by itself. */
    @Test
    void aDroppedConnectionIsAutomaticallyRejoined() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger resyncs = new AtomicInteger();
        GameChannel channel = new GameChannel(factoryOver(server), envelope -> { },
            new ReconnectPolicy(3, Duration.ofMillis(10), Duration.ofMillis(50), 2.0), scheduler);
        channel.setOnResyncNeeded(resyncs::incrementAndGet);
        channel.connect("g1");
        assertTrue(server.awaitFirstHandshake(5_000));

        server.dropAllConnections();

        long deadline = System.currentTimeMillis() + 5_000;
        while (scheduler.pendingCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, scheduler.pendingCount(), "A dropped TCP connection must schedule a rejoin");

        scheduler.runNext();

        assertEquals(ConnectionState.CONNECTED, channel.state());
        assertEquals(2, server.handshakeCount(), "The rejoin is a second, real handshake");
        assertEquals(1, resyncs.get());
        channel.shutdown();
    }

    /** A connect to a port nobody is listening on must throw, not hang or return a dead channel. */
    @Test
    void anUnreachableServerFailsTheConnectRatherThanReturningADeadChannel() throws IOException {
        RawWebSocketServer dead = new RawWebSocketServer();
        String base = dead.uriBase();
        dead.close();

        GameChannel channel = new GameChannel(
            (gameId, onEnvelope, onClose) -> GameSocket.open(httpClient, mapper,
                URI.create(base + "/ws/game?gameId=" + gameId), "Basic x", onEnvelope, onClose),
            envelope -> { });

        assertThrows(ApiException.class, () -> channel.connect("g1"));
        assertFalse(channel.isConnected());
        channel.shutdown();
    }
}
