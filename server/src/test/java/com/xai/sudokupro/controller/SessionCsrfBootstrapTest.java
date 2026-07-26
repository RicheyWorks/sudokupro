package com.xai.sudokupro.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;


import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SessionController}'s CSRF bootstrap contract, in a deliberately ISOLATED
 * application context.
 *
 * <h2>Defect class</h2>
 * {@code GET /api/session} exists so a non-browser client (the JavaFX desktop app) can
 * learn the CSRF header name and force the {@code XSRF-TOKEN} cookie to be issued. The
 * handler only achieves that as a side effect of READING the deferred
 * {@link org.springframework.security.web.csrf.CsrfToken}: if that read is dropped or
 * short-circuited, the JSON body still looks plausible while the cookie is never written,
 * and every subsequent POST from that client fails CSRF with a 403 that looks like an
 * authorization bug. This test pins the header name, the token, and the cookie together.
 *
 * <h2>Why this cannot live in {@link ControllerSliceTestBase}'s shared context</h2>
 * {@code SecurityMockMvcRequestPostProcessors.csrf()} does not just decorate one request:
 * it swaps the {@code CsrfTokenRepository} on the shared {@code CsrfFilter} bean for a
 * {@code TestCsrfTokenRepository} wrapping {@code HttpSessionCsrfTokenRepository}, and that
 * swap persists for the lifetime of the application context. Any sibling test class that
 * calls {@code csrf()} therefore permanently changes the header name this endpoint reports
 * from the configured {@code X-XSRF-TOKEN} to {@code X-CSRF-TOKEN} and stops the cookie
 * being written — observed exactly that way while writing this suite. A distinct
 * {@code spring.datasource.url} gives this class its own context cache key, so what it
 * asserts is {@code SecurityConfig}'s real {@code CookieCsrfTokenRepository} behaviour and
 * not an artefact of which sibling test happened to run first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:csrfboot;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
class SessionCsrfBootstrapTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;

    private String player;

    @BeforeEach
    void registerOwnFixture() throws Exception {
        // This class never relies on an account another test method created.
        player = "csrfboot" + java.util.UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content("{\"username\":\"" + player + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isCreated());
    }

    @Test
    void sessionReportsTheConfiguredHeaderAndIssuesTheXsrfCookie() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/api/session")
                .with(httpBasic(player, PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.playerId").value(player))
            // CookieCsrfTokenRepository.withHttpOnlyFalse() -> the double-submit header the
            // desktop client must echo.
            .andExpect(jsonPath("$.csrfHeaderName").value("X-XSRF-TOKEN"))
            .andReturn().getResponse();

        var payload = JSON.readTree(response.getContentAsString());
        // hasNonNull first: NullNode.asText() yields the STRING "null", which is not blank,
        // so a blank check alone would not notice the token going missing.
        org.junit.jupiter.api.Assertions.assertTrue(payload.hasNonNull("csrfToken"),
            "csrfToken was null in the session payload: " + payload);
        String bodyToken = payload.get("csrfToken").asText();
        assertFalse(bodyToken.isBlank(), "no CSRF token in the session payload");

        var cookie = response.getCookie("XSRF-TOKEN");
        assertNotNull(cookie, "the XSRF-TOKEN cookie was not issued — the deferred token was never read");
        assertFalse(cookie.getValue().isBlank(), "the XSRF-TOKEN cookie is empty");
        assertFalse(cookie.isHttpOnly(), "the cookie must be script-readable for the double-submit pattern");

        // The body value and the cookie value DELIBERATELY differ under Spring Security 6:
        // the cookie holds the raw token while XorCsrfTokenRequestAttributeHandler (the
        // default) hands the handler a BREACH-masked, per-response encoding of it. Both
        // clients in this repo — ServerApi.java and static/play/app.js — echo the BODY
        // value, which is the one the filter can un-mask. Pinning the inequality documents
        // that "just send the cookie back" is not the contract here.
        org.junit.jupiter.api.Assertions.assertNotEquals(cookie.getValue(), bodyToken,
            "body token is no longer BREACH-masked — re-check what the clients must echo");
    }

    /**
     * End-to-end proof that the bootstrap is usable, with no {@code csrf()} test helper
     * involved: replay exactly what {@code ServerApi} does — carry the XSRF-TOKEN cookie and
     * echo the token from the response BODY in the header the payload named.
     */
    @Test
    void theBootstrappedTokenActuallySatisfiesTheCsrfFilter() throws Exception {
        MockHttpServletResponse bootstrap = mockMvc.perform(get("/api/session")
                .with(httpBasic(player, PASSWORD)))
            .andExpect(status().isOk()).andReturn().getResponse();
        var cookie = bootstrap.getCookie("XSRF-TOKEN");
        assertNotNull(cookie, "no cookie to double-submit");
        var payload = JSON.readTree(bootstrap.getContentAsString());
        String headerName = payload.get("csrfHeaderName").asText();
        String bodyToken = payload.get("csrfToken").asText();

        mockMvc.perform(post("/api/game/new?difficulty=1")
                .with(httpBasic(player, PASSWORD))
                .cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", cookie.getValue()))
                .header(headerName, bodyToken))
            .andExpect(status().isOk());

        // ...and the same call without the header is refused, so the 200 above was earned
        // by the token and not by CSRF being off.
        mockMvc.perform(post("/api/game/new?difficulty=1")
                .with(httpBasic(player, PASSWORD))
                .cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", cookie.getValue())))
            .andExpect(status().isForbidden());
    }

    @Test
    void sessionIsNotReachableAnonymously() throws Exception {
        mockMvc.perform(get("/api/session")).andExpect(status().isUnauthorized());
    }
}
