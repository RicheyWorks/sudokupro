package com.xai.sudokupro.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * Security configuration for SudokuPro's grid empire.
 * Protects admin endpoints and sets the stage for cosmic-scale authentication.
 */
@Configuration
@EnableWebSecurity
@Profile("!test") // Disable in test profile to simplify unit testing
public class SecurityConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/admin/**").hasRole("ADMIN") // Broaden to all admin endpoints
                .requestMatchers("/actuator/health", "/actuator/info").permitAll() // Allow health checks
                .requestMatchers("/ws/**").permitAll() // WebSocket endpoint for multiplayer
                .anyRequest().authenticated() // Tighten default access
            )
            .httpBasic(httpBasic -> httpBasic
                .realmName("SudokuPro Realm") // Custom realm name
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED) // Optimize for REST + WebSocket
            )
            // CSRF is re-enabled with a cookie-based token repository so browser clients
            // (including the SPA / WebSocket upgrade) can read the XSRF-TOKEN cookie and
            // echo it in the X-XSRF-TOKEN request header.
            // WebSocket upgrade requests can't carry custom headers, so the /ws/** path is
            // exempted; the WebSocket handshake itself is protected by same-origin policy.
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .ignoringRequestMatchers("/ws/**")
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessHandler(logoutSuccessHandler())
                .invalidateHttpSession(true)
                .clearAuthentication(true)
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, resp, authEx) -> {
                    logger.warn("Unauthorized access attempt: {}", authEx.getMessage());
                    resp.sendError(401, "Unauthorized: Please log in to access SudokuPro.");
                })
            )
            // Future OAuth2 integration placeholder
            /*
            .oauth2Login(oauth -> oauth
                .loginPage("/oauth2/authorization/sudoku")
                .defaultSuccessUrl("/dashboard")
            )
            */
            // Response security headers
            // CSP allows same-origin resources plus WebSocket connections for the game.
            // X-XSS-Protection is intentionally omitted: it is deprecated in modern
            // browsers and can itself be exploited via the auditor reflection vector.
            // Content-Security-Policy + nosniff provide equivalent/better protection.
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; " +
                    "script-src 'self'; " +
                    "style-src 'self' 'unsafe-inline'; " +
                    "connect-src 'self' ws: wss:; " +  // allow WebSocket game connections
                    "img-src 'self' data:; " +
                    "frame-ancestors 'none'"            // stronger than X-Frame-Options
                ))
                .frameOptions(frame -> frame.deny())
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31_536_000)        // 1 year
                )
                .contentTypeOptions(Customizer.withDefaults()) // X-Content-Type-Options: nosniff
                .referrerPolicy(referrer -> referrer
                    .policy(org.springframework.security.web.header.writers
                        .ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                )
            );

        logger.info("Security filter chain configured with basic auth; OAuth2 upgrade pending.");
        return http.build();
    }

    @Bean
    public LogoutSuccessHandler logoutSuccessHandler() {
        return (request, response, authentication) -> {
            String username = authentication != null ? authentication.getName() : "anonymous";
            logger.info("User {} logged out successfully.", username);
            response.setStatus(200);
            response.setContentType("application/json");
            // Escape backslashes then quotes so a malicious username can't inject JSON.
            String safeUsername = username.replace("\\", "\\\\").replace("\"", "\\\"");
            response.getWriter().write("{\"message\":\"Logout successful\",\"user\":\"" + safeUsername + "\"}");
        };
    }
}
