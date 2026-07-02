package com.xai.sudokupro.config;

import com.xai.sudokupro.websocket.RedisBroadcastRelay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Subscribes RedisBroadcastRelay to the cross-replica broadcast channel (Phase 5).
 *
 * The subscription is started asynchronously with retry: RedisMessageListenerContainer
 * fails hard on start() when Redis is unreachable, and the app must boot without Redis
 * (single-replica degraded mode). Once Redis comes up, the retry loop attaches the
 * subscription and cross-replica fan-out becomes live.
 */
@Configuration
public class RedisRelayConfig {

    private static final Logger logger = LoggerFactory.getLogger(RedisRelayConfig.class);
    private static final long RETRY_SECONDS = 15;

    @Bean
    public RedisMessageListenerContainer relayListenerContainer(RedisConnectionFactory connectionFactory,
                                                                RedisBroadcastRelay relay) {
        // Started by relaySubscriptionStarter below, with retry — not by the lifecycle
        // (start() fails hard when Redis is unreachable, and the app must boot without it).
        RedisMessageListenerContainer container = new RedisMessageListenerContainer() {
            @Override public boolean isAutoStartup() { return false; }
        };
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
            (message, pattern) -> relay.onMessage(new String(message.getBody(), StandardCharsets.UTF_8)),
            new ChannelTopic(RedisBroadcastRelay.CHANNEL));
        return container;
    }

    @Bean
    public ApplicationRunner relaySubscriptionStarter(RedisMessageListenerContainer container) {
        return args -> {
            ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-relay-subscribe");
                t.setDaemon(true);
                return t;
            });
            Runnable attempt = new Runnable() {
                @Override public void run() {
                    try {
                        container.start();
                        logger.info("Cross-replica broadcast relay subscribed to Redis");
                        ses.shutdown();
                    } catch (Exception e) {
                        logger.debug("Relay subscription unavailable (Redis down?) — retrying in {}s: {}",
                            RETRY_SECONDS, e.getMessage());
                        ses.schedule(this, RETRY_SECONDS, TimeUnit.SECONDS);
                    }
                }
            };
            ses.execute(attempt);
        };
    }
}
