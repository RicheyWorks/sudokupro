package com.xai.sudokupro.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Makes a down Redis fail FAST instead of stalling every request behind it.
 *
 * <p>Every Redis touch in this codebase is wrapped in a try/catch that falls back to a
 * local path, and the design notes describe the service as degrading gracefully without
 * Redis. That was only half true. Lettuce's default
 * {@link ClientOptions.DisconnectedBehavior#DEFAULT} <b>queues</b> commands while the
 * connection is down, waiting for a reconnect, so the catch block never ran until the
 * command timeout expired. The fallback was correct and unreachable.
 *
 * <p>Measured by the live engine's fault-injection suite (engine/live_engine.py, L28):
 * with Redis stopped mid-session, a board was still readable and a new game could still
 * be created, but <b>a full game could not be played</b> — every move blocked for roughly
 * ten seconds on {@code saveToRedis} before logging "Redis cache write failed
 * (non-fatal)". Ten seconds per move is not degraded service; it is an outage that also
 * ties up a request thread per move, so a Redis blip becomes a thread-pool exhaustion
 * incident. None of the unit tests could see it — they mock Redis to throw immediately,
 * which is precisely the behaviour this class now makes real.
 *
 * <p>{@code REJECT_COMMANDS} makes a disconnected client throw straight away, which is
 * what the fallbacks were written to handle. The short connect timeout bounds the initial
 * dial, and {@code cancelCommandsOnReconnectFailure} stops a queue draining into a
 * half-open connection after the fact.
 *
 * <p>This is a customizer rather than a replacement factory so Boot keeps owning the
 * connection details in {@code spring.data.redis.*}; it applies in every profile,
 * including the ones where {@link RedisConfig} is inactive.
 */
@Configuration
public class RedisFailFastConfig {

    private static final Logger logger = LoggerFactory.getLogger(RedisFailFastConfig.class);

    /**
     * How long a single Redis command may take before the caller gives up and uses its
     * fallback. Deliberately short: every consumer here has a local path, so waiting is
     * strictly worse than failing.
     */
    @Value("${sudokupro.redis.command-timeout-ms:500}")
    private long commandTimeoutMs;

    /** How long to wait for the initial TCP connect. */
    @Value("${sudokupro.redis.connect-timeout-ms:500}")
    private long connectTimeoutMs;

    @Bean
    public LettuceClientConfigurationBuilderCustomizer redisFailFastCustomizer() {
        return builder -> {
            ClientOptions options = ClientOptions.builder()
                // The whole point: do not sit on a queued command hoping for a reconnect.
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .cancelCommandsOnReconnectFailure(true)
                .autoReconnect(true)          // still recover on its own once Redis returns
                .socketOptions(SocketOptions.builder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .build())
                .timeoutOptions(TimeoutOptions.builder()
                    .fixedTimeout(Duration.ofMillis(commandTimeoutMs))
                    .build())
                .build();

            builder.clientOptions(options)
                   .commandTimeout(Duration.ofMillis(commandTimeoutMs));

            logger.info("Redis configured to fail fast: command timeout {}ms, connect timeout {}ms, "
                + "commands rejected while disconnected", commandTimeoutMs, connectTimeoutMs);
        };
    }
}
