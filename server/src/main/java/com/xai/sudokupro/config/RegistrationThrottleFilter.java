package com.xai.sudokupro.config;

import com.xai.sudokupro.service.RegistrationAttemptLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejects {@code POST /api/auth/register} once a client address has spent its account-creation
 * quota ({@link RegistrationAttemptLimiter}).
 *
 * <p>Modelled directly on {@link LoginAttemptFilter}, which is the established pattern in this
 * codebase, and deliberately consistent with it in three ways:
 *
 * <ul>
 *   <li><b>Client address from {@code request.getRemoteAddr()} only.</b> Never from
 *       {@code X-Forwarded-For}. A prior pass established that this deployment has no
 *       header-appending proxy in front of it — the shipped Kubernetes manifests contain only
 *       a ClusterIP Service — so the header is attacker-controlled end to end, and trusting it
 *       would let an attacker rotate it per request and register without limit. When a trusted
 *       proxy genuinely exists, {@code server.forward-headers-strategy=NATIVE} makes Tomcat's
 *       {@code RemoteIpValve} rewrite {@code getRemoteAddr()} itself, so this filter picks up
 *       the real client address without reading the header directly. That is the single
 *       switch that governs both limiters.</li>
 *   <li><b>Narrow matching.</b> Only {@code POST /api/auth/register} is inspected; every other
 *       request passes through untouched, so a throttled address cannot be used to deny
 *       service to health checks, gameplay, or the WebSocket handshake.</li>
 *   <li><b>Not a {@code @Component}.</b> Any {@code Filter} bean Spring Boot discovers is also
 *       auto-registered as a global servlet filter mapped to {@code /*}, which would run it
 *       twice per request and double-count every attempt. {@link SecurityConfig} builds it and
 *       suppresses the automatic registration with a disabled {@code FilterRegistrationBean},
 *       exactly as it does for {@link LoginAttemptFilter}.</li>
 * </ul>
 *
 * <p>The check runs <em>before</em> the attempt is counted, so a client gets exactly
 * {@code maxAttempts} registrations per window rather than {@code maxAttempts - 1}.
 */
public class RegistrationThrottleFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(RegistrationThrottleFilter.class);

    static final String REGISTER_PATH = "/api/auth/register";

    private final RegistrationAttemptLimiter limiter;

    public RegistrationThrottleFilter(RegistrationAttemptLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!isRegistration(request)) {
            chain.doFilter(request, response);
            return;
        }

        String clientAddress = request.getRemoteAddr();

        if (limiter.isThrottled(clientAddress)) {
            logger.warn("Rejecting registration from {}: account-creation quota exhausted "
                + "({} per {}s)", clientAddress, limiter.maxAttempts(), limiter.window().toSeconds());
            response.setStatus(429);
            response.setContentType("application/json");
            // Retry-After lets a well-behaved client back off instead of hot-looping, and is
            // what a CDN or ingress uses to shed the retry storm on our behalf.
            response.setHeader("Retry-After", Long.toString(limiter.window().toSeconds()));
            response.getWriter().write(
                "{\"error\":\"Too many accounts created from this address. Try again later.\"}");
            return;
        }

        limiter.recordAttempt(clientAddress);
        chain.doFilter(request, response);
    }

    private static boolean isRegistration(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        // Derived from the request URI rather than getServletPath(): with the default
        // (root) servlet mapping getServletPath() is the empty string on some containers
        // and on MockMvc, so matching on it silently matched nothing — the filter would
        // have been present, wired, and inert.
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        String contextPath = request.getContextPath();
        String path = (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath))
            ? uri.substring(contextPath.length())
            : uri;
        // Strip any trailing slash and matrix/pathinfo noise before comparing.
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return REGISTER_PATH.equals(path);
    }
}
