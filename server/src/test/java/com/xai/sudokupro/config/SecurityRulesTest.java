package com.xai.sudokupro.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the REAL {@link SecurityConfig} rules.
 *
 * <p>Coverage gap this closes: {@code SecurityConfig} is annotated
 * {@code @Profile("!test")}, so every other test in this suite runs with it switched OFF
 * and gets Spring Boot's default "secure everything" fallback instead. Nothing verified
 * the actual {@code permitAll} matchers, the CSRF configuration, or the admin-role rule —
 * a change that accidentally exposed {@code /api/**} or locked out {@code /play/} would
 * have passed CI. This test runs under the {@code dev} profile (which satisfies
 * {@code !test}, and disables SecretsGuard) against in-memory H2.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sectest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "sudokupro.ui.enabled=false",
    // These suites mint fixture accounts in bulk from a single (mock) client address,
    // which is exactly what RegistrationAttemptLimiter exists to stop in production.
    // Lift the quota here so the throttle does not turn fixture setup into 429s; the
    // throttle itself is exercised for real by security/RegistrationThrottleTest.
    "sudokupro.security.register.max-attempts=1000000"
})
class SecurityRulesTest {

    @Autowired
    private MockMvc mockMvc;

    // ---- public surface ----

    @Test
    void webClientShellIsPublic() throws Exception {
        mockMvc.perform(get("/play/")).andExpect(status().isOk());
        mockMvc.perform(get("/play")).andExpect(status().isOk());
        mockMvc.perform(get("/play/app.js")).andExpect(status().isOk());
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        // Assert REACHABILITY, not liveness: this test is about the permitAll matcher.
        // The aggregate /actuator/health includes the Redis indicator, so it legitimately
        // reports 503 when Redis is absent (which is also why the k8s LIVENESS probe should
        // point at a group that excludes external dependencies — a Redis blip should not
        // crash-loop every pod). What must never happen here is a 401.
        int status = mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();
        org.junit.jupiter.api.Assertions.assertNotEquals(401, status,
            "health must be reachable without authentication");
        org.junit.jupiter.api.Assertions.assertTrue(status == 200 || status == 503,
            "expected an actuator health verdict, got " + status);
    }

    /**
     * Ensures the player account the authenticating tests use actually exists.
     *
     * <p>It was previously created as a side effect of {@code registrationIsPublicAndCsrfExempt},
     * so any test that authenticated depended on JUnit's method order. That is why the new
     * admin-role test first failed with 401 instead of 403 — it was asserting against an
     * account that did not exist yet, which would have masked the very rule it checks.
     * Registering here is idempotent: a duplicate returns 409, which we ignore.
     */
    @org.junit.jupiter.api.BeforeEach
    void ensureTestPlayerExists() throws Exception {
        mockMvc.perform(post("/api/auth/register")
            .contentType("application/json")
            .content("{\"username\":\"secrules1\",\"password\":\"password123\"}"));
    }

    @Test
    void registrationIsPublicAndCsrfExempt() throws Exception {
        // Registration precedes any credentials or session by definition, so it is both
        // permitAll and CSRF-exempt. Sending no CSRF token must NOT be a 403.
        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content("{\"username\":\"secrules-fresh\",\"password\":\"password123\"}"))
            .andExpect(status().isCreated());
    }


    /**
     * Regression: the Kubernetes probes target the health GROUPS, not the aggregate.
     * SecurityConfig permitted only the exact "/actuator/health" path, so
     * /actuator/health/liveness and /actuator/health/readiness answered 401 — pods would
     * never become ready and liveness would restart them forever. Verified live before the
     * fix: both group endpoints returned HTTP 401.
     */
    @Test
    void kubernetesProbeEndpointsAreReachableWithoutAuthentication() throws Exception {
        for (String probe : new String[]{"/actuator/health/liveness", "/actuator/health/readiness"}) {
            int status = mockMvc.perform(get(probe)).andReturn().getResponse().getStatus();
            org.junit.jupiter.api.Assertions.assertNotEquals(401, status,
                probe + " must be reachable by kubelet, which sends no credentials");
        }
    }

    // ---- protected surface ----

    @Test
    void gameAndEconomyApisRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/economy/wallet")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/game/saved")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/friends")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/session")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpointsAreNotReachableAnonymously() throws Exception {
        mockMvc.perform(get("/admin/constants")).andExpect(status().isUnauthorized());
    }

    /**
     * The admin ROLE rule, as distinct from merely requiring a login.
     *
     * <p>The class javadoc claims to cover "the admin-role rule", but the anonymous test
     * above only proves that /admin needs *some* authentication. A mutation audit weakened
     * {@code hasRole("ADMIN")} to {@code authenticated()} and the suite stayed green —
     * meaning any registered player could have reached {@code /admin/constants}, which
     * exposes the platform's economy and XP configuration.
     */
    @Test
    void adminEndpointsAreForbiddenToAnOrdinaryAuthenticatedPlayer() throws Exception {
        mockMvc.perform(get("/admin/constants")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .httpBasic("secrules1", "password123")))
            .andExpect(status().isForbidden());
    }

    /**
     * Path-shape variants must not slip past the matcher. Spring's StrictHttpFirewall
     * blocks some of these outright (400) — that is an acceptable refusal; what must never
     * happen is a 200 reaching the handler.
     */
    @Test
    void adminPathVariantsDoNotBypassTheRule() throws Exception {
        String[] variants = {
            "/admin/constants/", "/ADMIN/constants", "/admin//constants",
            "/admin/./constants", "/admin/constants.json", "/./admin/constants"
        };
        for (String path : variants) {
            int status = mockMvc.perform(get(path)
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                        .httpBasic("secrules1", "password123")))
                .andReturn().getResponse().getStatus();
            org.junit.jupiter.api.Assertions.assertNotEquals(200, status,
                "an ordinary player reached the admin handler via " + path);
        }
    }

    @Test
    void mutatingApiCallsWithoutACsrfTokenAreRejected() throws Exception {
        // Authenticated but tokenless: must fail CSRF, not sail through.
        mockMvc.perform(post("/api/game/new?difficulty=1")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .httpBasic("secrules1", "password123")))
            .andExpect(status().isForbidden());
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        mockMvc.perform(get("/play/"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .header().string("X-Frame-Options", "DENY"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .header().exists("Content-Security-Policy"));
    }
}
