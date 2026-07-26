package com.xai.sudokupro.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Defect class: <b>a protection that exists for one entry point and was never applied to the
 * sibling entry point that needs it just as much.</b>
 *
 * <p>{@code POST /api/auth/register} is {@code permitAll} and CSRF-exempt (it necessarily
 * precedes credentials), and had no rate limit of any kind: a single client could mint
 * accounts as fast as it could open sockets. Each one is a real {@code users} row with a
 * starting gem balance, a leaderboard identity, and a friend/duel inbox — so the endpoint was
 * a free database-growth and Sybil primitive, and the accounts it produces are exactly what
 * {@code LoginAttemptLimiter}'s own Javadoc describes an attacker using to reset the
 * brute-force counter.
 *
 * <p>These tests assert the WIRING through the real {@link com.xai.sudokupro.config.SecurityConfig}
 * chain (hence the {@code dev} profile — {@code SecurityConfig} is {@code @Profile("!test")}
 * and is switched off under the {@code test} profile, so a slice test there would prove
 * nothing). They also pin the client-address derivation: the limiter must key on the real
 * peer address and must not let an attacker-supplied {@code X-Forwarded-For} mint a fresh
 * quota — the same spoofing hole a prior pass fixed for the login limiter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:regthrottle;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "sudokupro.ui.enabled=false",
    // Small quota so the test is fast and deterministic; the production default is larger.
    "sudokupro.security.register.max-attempts=3",
    "sudokupro.security.register.window-seconds=120"
})
class RegistrationThrottleTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * A per-run, per-test source address.
     *
     * <p>Load-bearing: the limiter is Redis-backed (shared with other work in this
     * environment) and keyed by client address, so a fixed address such as 127.0.0.1 would
     * inherit counters from other test classes and from previous runs inside the window. A
     * unique address per test method makes each quota private.
     */
    private static String uniqueAddress() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        // TEST-NET-3 (203.0.113.0/24) is reserved for documentation and never routable.
        return "203.0.113." + r.nextInt(1, 255) + ":" + r.nextLong(1L << 40);
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private String uniqueName() {
        return "thr" + Long.toString(ThreadLocalRandom.current().nextLong(1L << 44), 36);
    }

    private org.springframework.test.web.servlet.ResultActions register(String address) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
            .with(from(address))
            .contentType("application/json")
            .content("{\"username\":\"" + uniqueName() + "\",\"password\":\"password123\"}"));
    }

    /** The core defect: unlimited account creation from one client. */
    @Test
    void registrationIsThrottledPerClientAddress() throws Exception {
        String attacker = uniqueAddress();

        register(attacker).andExpect(status().isCreated());
        register(attacker).andExpect(status().isCreated());
        register(attacker).andExpect(status().isCreated());

        // Fourth attempt inside the window: the quota is spent.
        register(attacker).andExpect(status().isTooManyRequests());
        // ...and it stays spent — a throttle that opens again on the next request is no throttle.
        register(attacker).andExpect(status().isTooManyRequests());
    }

    /** Throttling must be per-client, not a global kill switch on registration. */
    @Test
    void anUnrelatedClientKeepsItsOwnQuota() throws Exception {
        String noisy = uniqueAddress();
        String innocent = uniqueAddress();

        register(noisy).andExpect(status().isCreated());
        register(noisy).andExpect(status().isCreated());
        register(noisy).andExpect(status().isCreated());
        register(noisy).andExpect(status().isTooManyRequests());

        register(innocent).andExpect(status().isCreated());
    }

    /**
     * The client address must come from the real peer, never from a request header.
     *
     * <p>A prior pass found {@code X-Forwarded-For} spoofable here (the shipped Kubernetes
     * manifests have no header-appending proxy, so the header was attacker-controlled end to
     * end) and fixed the login limiter by keying on {@code request.getRemoteAddr()} with
     * {@code server.forward-headers-strategy=NONE}. If registration derived its key from the
     * header instead, an attacker would rotate the header and register without limit — the
     * throttle would be present and useless, which is this project's signature defect.
     */
    @Test
    void spoofedForwardedForCannotMintAFreshQuota() throws Exception {
        String attacker = uniqueAddress();

        register(attacker).andExpect(status().isCreated());
        register(attacker).andExpect(status().isCreated());
        register(attacker).andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                .with(from(attacker))
                .header("X-Forwarded-For", "198.51.100.77")
                .contentType("application/json")
                .content("{\"username\":\"" + uniqueName() + "\",\"password\":\"password123\"}"))
            .andExpect(status().isTooManyRequests());
    }

    /**
     * Rejected attempts must count too. If only successful registrations were counted, an
     * attacker could spend the endpoint's cost (request handling, username lookup, and the
     * 409/400 response that also confirms whether a username exists) without limit, and could
     * enumerate the user table for free.
     */
    @Test
    void invalidAttemptsConsumeTheQuotaToo() throws Exception {
        String attacker = uniqueAddress();
        String tooShort = "{\"username\":\"" + uniqueName() + "\",\"password\":\"short\"}";

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/auth/register")
                    .with(from(attacker))
                    .contentType("application/json")
                    .content(tooShort))
                .andExpect(status().isBadRequest());
        }

        register(attacker).andExpect(status().isTooManyRequests());
    }
}
