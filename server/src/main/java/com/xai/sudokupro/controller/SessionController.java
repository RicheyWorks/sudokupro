package com.xai.sudokupro.controller;

import com.xai.sudokupro.model.api.SessionInfo;
import com.xai.sudokupro.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session bootstrap for non-browser clients (the JavaFX desktop app).
 *
 * <p>A remote client calls {@code GET /api/session} once after connecting:
 * a 401 means bad credentials; a 200 confirms authentication, returns the
 * player id, and — by reading the deferred {@link CsrfToken} — forces the
 * XSRF-TOKEN cookie to be issued so subsequent POSTs can double-submit it.
 */
@RestController
@Tag(name = "Session API", description = "Authentication check + CSRF bootstrap for API clients")
public class SessionController {

    private final AuthService authService;

    public SessionController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "Identify the authenticated player and obtain a CSRF token")
    @GetMapping("/api/session")
    public ResponseEntity<SessionInfo> session(CsrfToken csrfToken) {
        // Reading the token value materializes the deferred token and triggers
        // CookieCsrfTokenRepository to set the XSRF-TOKEN cookie on this response.
        String token = csrfToken != null ? csrfToken.getToken() : null;
        String header = csrfToken != null ? csrfToken.getHeaderName() : "X-XSRF-TOKEN";
        return ResponseEntity.ok(new SessionInfo(authService.getCurrentPlayerId(), header, token));
    }
}
