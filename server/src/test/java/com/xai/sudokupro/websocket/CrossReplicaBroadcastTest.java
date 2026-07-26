package com.xai.sudokupro.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xai.sudokupro.config.RedisRelayConfig;
import com.xai.sudokupro.support.ExternalServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Two simulated pods share one real Redis: a broadcast issued on pod A must reach a
 * WebSocket session registered on pod B. That is the entire multi-replica claim of
 * Phase 5 — without it a two-pod deployment silently drops every move, chat message and
 * board sync between players who happened to land on different pods.
 *
 * <p><b>What changed and why.</b> This class was
 * {@code @Testcontainers(disabledWithoutDocker = true)}, so wherever Docker was absent it
 * disabled itself with no signal: two green-looking tests that never published a single
 * message. Redis now comes from configuration ({@link ExternalServices}, defaulting to
 * localhost:6379), and an unreachable Redis FAILS the test with an explanatory message
 * instead of skipping.
 *
 * <p><b>No production logic is re-implemented here.</b> A pod is assembled from the real
 * {@link GameSessionRegistry}, the real {@link RedisBroadcastRelay} and the real
 * {@link RedisRelayConfig#relayListenerContainer} — the same subscription wiring, on the
 * same channel, that a running server uses. The only test-owned pieces are a recording
 * WebSocket session and a warm-up handshake.
 *
 * <p><b>Why the warm-up.</b> {@code RedisMessageListenerContainer.start()} subscribes
 * asynchronously, so a message published immediately afterwards can be dropped by Redis
 * (pub/sub has no backlog). Each pod therefore also subscribes to a private warm-up
 * channel, registered before {@code start()} so both subscriptions go out together; once a
 * warm-up ping comes back, the relay subscription is provably live. Without this the tests
 * would be flaky — and "flaky" here tends to get "fixed" by loosening assertions.
 *
 * <p><b>Why every assertion carries a nonce.</b> The relay channel is a single fixed global
 * name, so anything else attached to the same Redis (a parallel test run, a live server)
 * publishes onto it too. Assertions match on a per-test unique id rather than counting raw
 * deliveries, so foreign traffic can neither satisfy nor break an "exactly once" check.
 */
class CrossReplicaBroadcastTest {

    private static final long DELIVERY_TIMEOUT_MS = 10_000;
    /** Settle window before asserting that no FURTHER copy of a message arrives. */
    private static final long QUIET_MS = 750;

    private final List<AutoCloseable> resources = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void requireRealRedis() {
        ExternalServices.requireRedis();
    }

    @AfterEach
    void tearDown() {
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (Exception e) {
                System.err.println("cleanup failed: " + e);
            }
        }
        resources.clear();
    }

    private record Pod(GameSessionRegistry registry, RedisBroadcastRelay relay) {}

    /** Every TextMessage a given WebSocket session was actually sent. */
    private static final class Recorder {
        private final List<String> payloads = Collections.synchronizedList(new ArrayList<>());

        void record(String payload) {
            payloads.add(payload);
        }

        List<String> snapshot() {
            synchronized (payloads) {
                return new ArrayList<>(payloads);
            }
        }

        int countContaining(String nonce) {
            int n = 0;
            for (String p : snapshot()) {
                if (p.contains(nonce)) n++;
            }
            return n;
        }

        String awaitContaining(String nonce, String failureMessage) throws InterruptedException {
            long deadline = System.currentTimeMillis() + DELIVERY_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                for (String p : snapshot()) {
                    if (p.contains(nonce)) return p;
                }
                Thread.sleep(50);
            }
            return fail(failureMessage + " within " + DELIVERY_TIMEOUT_MS
                + "ms (payloads actually delivered to this session: " + snapshot() + ")");
        }
    }

    /** Builds one pod from the production components and waits until it is really subscribed. */
    private Pod startPod() throws Exception {
        LettuceConnectionFactory cf =
            new LettuceConnectionFactory(ExternalServices.redisHost(), ExternalServices.redisPort());
        cf.afterPropertiesSet();
        resources.add(cf::destroy);

        StringRedisTemplate template = new StringRedisTemplate(cf);
        GameSessionRegistry registry = new GameSessionRegistry(new ObjectMapper());
        RedisBroadcastRelay relay = new RedisBroadcastRelay(registry, template, new ObjectMapper());
        relay.attach();   // exactly what @PostConstruct does in a running pod

        // Production subscription wiring, verbatim — including the channel name it subscribes to.
        RedisMessageListenerContainer container =
            new RedisRelayConfig().relayListenerContainer(cf, relay);

        String warmupChannel = "sudokupro:it:warmup:" + UUID.randomUUID();
        CountDownLatch warm = new CountDownLatch(1);
        container.addMessageListener((message, pattern) -> warm.countDown(), new ChannelTopic(warmupChannel));

        container.afterPropertiesSet();
        container.start();
        resources.add(container::destroy);

        long deadline = System.currentTimeMillis() + DELIVERY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && warm.getCount() > 0) {
            template.convertAndSend(warmupChannel, "ping");
            warm.await(100, TimeUnit.MILLISECONDS);
        }
        assertEquals(0, warm.getCount(),
            "pod never became subscribed to Redis at " + ExternalServices.redisHost() + ":"
                + ExternalServices.redisPort() + " — the test could not prove anything about the relay");

        return new Pod(registry, relay);
    }

    // ------------------------------------------------------------------

    @Test
    void broadcastOnPodAReachesSessionOnPodB() throws Exception {
        Pod a = startPod();
        Pod b = startPod();
        assertNotEquals(a.relay().getOriginId(), b.relay().getOriginId(),
            "two pods must have distinct origin ids, or the relay would filter out real traffic");

        String nonce = UUID.randomUUID().toString();
        String gameId = "game-" + nonce;

        Recorder onB = new Recorder();
        b.registry().register(gameId, "bob-" + nonce, recordingSession(onB));

        Map<String, Object> envelope = Map.of("type", "move", "from", "alice", "payload", "r1c1=5:" + nonce);
        a.registry().broadcastToGame(gameId, null, envelope);

        String received = onB.awaitContaining(nonce, "a game broadcast on pod A never reached pod B");
        assertEquals(envelope, asMap(received),
            "the envelope must cross the relay unchanged — this is the wire format the client parses");
    }

    @Test
    void playerTargetedMessageCrossesPods() throws Exception {
        Pod a = startPod();
        Pod b = startPod();

        String nonce = UUID.randomUUID().toString();
        String player = "carol-" + nonce;

        Recorder onB = new Recorder();
        b.registry().register("game-" + nonce, player, recordingSession(onB));

        // carol is NOT on pod A — local delivery must fail there, and the relay carries it to B.
        Map<String, Object> envelope = Map.of("type", "hint", "from", "server", "payload", nonce);
        assertFalse(a.registry().sendToPlayer(player, envelope),
            "pod A must report no local delivery for a player it does not hold");

        String received = onB.awaitContaining(nonce, "a player-targeted message never reached pod B");
        assertEquals(envelope, asMap(received));

        // No echo loops: give the channel a beat, then confirm exactly one delivery of THIS message.
        Thread.sleep(QUIET_MS);
        assertEquals(1, onB.countContaining(nonce),
            "the relayed message must be delivered exactly once, not echoed back and forth");
    }

    /**
     * The origin filter is the only thing stopping a pod from delivering its own broadcast
     * twice — once locally, and again when Redis hands the publication straight back to it.
     * Both the local and the remote recipient must see exactly one copy.
     */
    @Test
    void originatingPodDeliversItsOwnBroadcastExactlyOnce() throws Exception {
        Pod a = startPod();
        Pod b = startPod();

        String nonce = UUID.randomUUID().toString();
        String gameId = "game-" + nonce;

        Recorder onA = new Recorder();
        Recorder onB = new Recorder();
        a.registry().register(gameId, "alice-" + nonce, recordingSession(onA));
        b.registry().register(gameId, "bob-" + nonce, recordingSession(onB));

        a.registry().broadcastToGame(gameId, null,
            Map.of("type", "chat", "from", "alice", "payload", nonce));

        onB.awaitContaining(nonce, "the remote pod never received the broadcast");
        onA.awaitContaining(nonce, "the originating pod never delivered to its own session");
        Thread.sleep(QUIET_MS);

        assertEquals(1, onA.countContaining(nonce),
            "the originating pod must deliver locally and NOT again when Redis echoes its own "
                + "publication back — otherwise every player on that pod sees duplicated moves");
        assertEquals(1, onB.countContaining(nonce),
            "the remote pod must deliver the relayed broadcast exactly once");
    }

    // ---- helpers ----

    private WebSocketSession recordingSession(Recorder recorder) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn(UUID.randomUUID().toString());
        doAnswer(invocation -> {
            recorder.record(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        return session;
    }

    private Map<String, Object> asMap(String payload) throws Exception {
        return json.readValue(payload, new TypeReference<HashMap<String, Object>>() {});
    }
}
