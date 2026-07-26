package com.xai.sudokupro.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The zero-install browser client must be reachable at the URL the README tells
 * people to open: {@code http://localhost:8080/play/}.
 *
 * <p>Regression: {@code /play/index.html} and {@code /play/app.js} resolved fine, but
 * {@code /play/} itself did not — Spring Boot only resolves a directory welcome page
 * for the context root, never for a static sub-directory. So the documented entry
 * point 404'd, and because Boot forwards a 404 to {@code /error} (which required
 * authentication), an anonymous visitor saw a bare 401 and the client looked like it
 * was behind a login wall it was explicitly built to sit in front of. Confirmed
 * against a live server: {@code /play/} → 401, {@code /play/index.html} → 200.
 *
 * <p>Scope note: this verifies the {@link WebClientMvcConfig} mapping only.
 * {@link SecurityConfig} is annotated {@code @Profile("!test")}, so it is switched off
 * in every profile-based test — meaning the real {@code permitAll} matchers are not
 * covered anywhere in this suite. Asserting them needs a test that loads the real
 * security config rather than Boot's default "secure everything" fallback.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = WebClientEntryPointTest.MvcOnlyConfig.class)
class WebClientEntryPointTest {

    @EnableWebMvc
    @Configuration
    static class MvcOnlyConfig {
        @Bean
        WebClientMvcConfig webClientMvcConfig() {
            return new WebClientMvcConfig();
        }
    }

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    @Test
    void playSlashForwardsToTheWebClientShell() throws Exception {
        mockMvc.perform(get("/play/"))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl("/play/index.html"));
    }

    @Test
    void playWithoutTrailingSlashAlsoForwardsToTheWebClientShell() throws Exception {
        mockMvc.perform(get("/play"))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl("/play/index.html"));
    }
}
