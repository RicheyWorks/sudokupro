package com.xai.sudokupro.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

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
            .csrf(csrf -> csrf.disable()) // Safe for REST APIs; revisit if UI needs it
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
            // Future security headers for browser frontend
            /*
            .headers(headers -> headers
                .contentSecurityPolicy("default-src 'self'")
                .xssProtection(xss -> xss.block(true))
                .frameOptions().sameOrigin()
            )
            */;

        logger.info("Security filter chain configured with basic auth; OAuth2 upgrade pending.");
        return http.build();
    }

    @Bean
    public LogoutSuccessHandler logoutSuccessHandler() {
        return (request, response, authentication) -> {
            String username = authentication != null ? authentication.getName() : "anonymous";
            logger.info("User {} logged out successfully.", username);
            response.setStatus(200); // Success status on logout
            // Optional REST-compliant response for international/API clients
            response.setContentType("application/json");
            response.getWriter().write("{\"message\": \"Logout successful\", \"user\": \"" + username + "\"}");
        };
    }
}
