package com.xai.sudokupro.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the P1 the live engine's fault-injection suite found:
 * {@code REDIS-DOWN-CANNOT-PLAY}.
 *
 * <p><b>How it was reproduced.</b> {@code engine/live_engine.py --only L28 --fault-injection}
 * stops Redis mid-session and then tries to keep playing. Reads and game creation survived,
 * but a full game could not be played to completion. The server log showed the reason, once
 * per move:
 *
 * <pre>
 * 10:40:34.257 WARN GameService : Redis cache write failed for game 208e00e3 (non-fatal): Redis command timed out
 * 10:40:44.265 WARN GameService : Redis cache write failed for game 208e00e3 (non-fatal): Redis command timed out
 * </pre>
 *
 * <p>Ten seconds per move. Every Redis touch in this codebase is already wrapped in a
 * try/catch with a local fallback, and the design notes call the service "gracefully
 * degrading without Redis" — but Lettuce's default {@code DisconnectedBehavior} <b>queues</b>
 * commands while disconnected, hoping for a reconnect. The catch block was therefore
 * unreachable until the command timeout fired. The fallback was correct and dead. Worse,
 * each queued move holds a request thread, so a Redis blip escalates into thread-pool
 * exhaustion rather than a cache miss.
 *
 * <p>Note that {@code spring.data.redis.timeout=2000ms} was already set and did not save it:
 * that property is not applied as a Lettuce {@code TimeoutOptions} fixed timeout for
 * commands issued while the connection is down.
 *
 * <p><b>Why this test and not a boot test.</b> No test could see the defect, because every
 * test that exercises the Redis fallbacks mocks the template to throw immediately — that is,
 * they all assume exactly the behaviour this class is what finally makes true. Asserting on
 * the customizer's output is the assertion that would have failed before the fix and passes
 * after; a Spring Boot slice would not, since the bug is invisible unless a real Redis is
 * really down.
 */
class RedisFailFastConfigTest {

    /** Builds the customizer with the same defaults the {@code @Value} annotations declare. */
    private static LettuceClientConfigurationBuilderCustomizer customizer(long commandMs, long connectMs) {
        RedisFailFastConfig config = new RedisFailFastConfig();
        ReflectionTestUtils.setField(config, "commandTimeoutMs", commandMs);
        ReflectionTestUtils.setField(config, "connectTimeoutMs", connectMs);
        return config.redisFailFastCustomizer();
    }

    private static LettuceClientConfiguration apply(long commandMs, long connectMs) {
        var builder = LettuceClientConfiguration.builder();
        customizer(commandMs, connectMs).customize(builder);
        return builder.build();
    }

    /**
     * The finding itself. {@code DEFAULT} queues commands while the connection is down,
     * which is what made a move take ten seconds instead of failing over to the local path.
     */
    @Test
    void commandsAreRejectedRatherThanQueuedWhileRedisIsDown() {
        ClientOptions options = apply(500, 500).getClientOptions().orElseThrow(
            () -> new AssertionError("no ClientOptions were applied — the customizer did nothing"));

        assertEquals(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS,
            options.getDisconnectedBehavior(),
            "with anything but REJECT_COMMANDS a disconnected Lettuce client queues the "
                + "command instead of throwing, so GameService's fallback never runs and "
                + "every move blocks for the full command timeout");
    }

    /**
     * A rejected command is only useful if the give-up point is short. Ten seconds was the
     * measured stall; the ceiling here is deliberately far below any human-visible delay
     * because every caller has a local path and waiting is strictly worse than failing.
     */
    @Test
    void theCommandTimeoutIsShortEnoughToBeInvisible() {
        ClientOptions options = apply(500, 500).getClientOptions().orElseThrow(AssertionError::new);
        TimeoutOptions timeouts = options.getTimeoutOptions();

        assertNotNull(timeouts, "no TimeoutOptions — commands would use the driver default");
        assertTrue(timeouts.isTimeoutCommands(), "command timeouts must actually be enforced");

        Duration commandTimeout = apply(500, 500).getCommandTimeout();
        assertTrue(commandTimeout.compareTo(Duration.ofSeconds(1)) <= 0,
            "a Redis command must give up in under a second so the local fallback can run; "
                + "measured stall before this fix was ~10s per move, got " + commandTimeout);
    }

    /** The initial dial must be bounded too, or a cold start against a dead Redis hangs. */
    @Test
    void theConnectTimeoutIsBounded() {
        ClientOptions options = apply(500, 500).getClientOptions().orElseThrow(AssertionError::new);
        Duration connect = options.getSocketOptions().getConnectTimeout();

        assertTrue(connect.compareTo(Duration.ofSeconds(2)) <= 0,
            "connect must not hang; got " + connect);
        assertEquals(Duration.ofMillis(500), connect,
            "the configured connect timeout should reach the socket options verbatim");
    }

    /**
     * Failing fast must not mean staying failed. Rejecting while down is only acceptable
     * because the client reconnects on its own once Redis returns — which the live suite
     * confirms with "the board is readable again after Redis recovers".
     */
    @Test
    void theClientStillReconnectsOnItsOwnWhenRedisComesBack() {
        ClientOptions options = apply(500, 500).getClientOptions().orElseThrow(AssertionError::new);

        assertTrue(options.isAutoReconnect(),
            "REJECT_COMMANDS without auto-reconnect would make one Redis restart permanent");
        assertTrue(options.isCancelCommandsOnReconnectFailure(),
            "a queue drained into a half-open connection after a failed reconnect reintroduces "
                + "exactly the stall this class exists to remove");
    }

    /** The timeouts are properties, so a deployment can tune them without a rebuild. */
    @Test
    void theTimeoutsAreConfigurableRatherThanHardCoded() {
        LettuceClientConfiguration tuned = apply(1_200, 900);

        assertEquals(Duration.ofMillis(1_200), tuned.getCommandTimeout());
        assertEquals(Duration.ofMillis(900),
            tuned.getClientOptions().orElseThrow(AssertionError::new)
                 .getSocketOptions().getConnectTimeout());
    }
}
