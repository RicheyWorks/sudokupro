package com.xai.sudokupro.config;

import com.xai.sudokupro.service.LoginAttemptLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejects HTTP Basic auth attempts from a remote address that has failed login too many
 * times recently ({@link LoginAttemptLimiter}). Runs before Spring Security's
 * {@code BasicAuthenticationFilter} so a locked-out address never reaches authentication.
 *
 * Only inspects requests that actually carry a {@code Authorization: Basic} header — every
 * other request (health checks, the WebSocket handshake, anonymous-permitted paths) passes
 * through untouched, so a flagged address can't be used to lock those out.
 *
 * <p>Deliberately NOT a {@code @Component}: this is wired into the Spring Security chain
 * only (see {@link SecurityConfig}). Any bean of type {@code Filter} that Spring Boot finds
 * gets auto-registered as a second, global servlet filter mapped to {@code /*} — see the
 * {@code FilterRegistrationBean} with {@code setEnabled(false)} in {@link SecurityConfig}
 * that suppresses that for this one.
 */
public class LoginAttemptFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(LoginAttemptFilter.class);

    private final LoginAttemptLimiter limiter;

    public LoginAttemptFilter(LoginAttemptLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Basic ", 0, 6)) {
            String remoteAddress = request.getRemoteAddr();
            // Scope the check to the account being attempted, matching how the counter is
            // now recorded. Keyed on the address alone, one successful login to any
            // account cleared the counter for every account at that address.
            String username = basicAuthUsername(auth);
            if (limiter.isBlocked(remoteAddress, username)) {
                logger.warn("Rejecting login attempt from {}: too many recent failures", remoteAddress);
                response.setStatus(429);
                response.setContentType("application/json");
                response.getWriter().write(
                    "{\"error\":\"Too many failed login attempts. Try again later.\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** The username half of a Basic credential, or null if it cannot be read. */
    private static String basicAuthUsername(String header) {
        try {
            String decoded = new String(java.util.Base64.getDecoder().decode(header.substring(6).trim()),
                java.nio.charset.StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            return colon < 0 ? decoded : decoded.substring(0, colon);
        } catch (Exception e) {
            return null;   // malformed header: authentication will reject it anyway
        }
    }
}
