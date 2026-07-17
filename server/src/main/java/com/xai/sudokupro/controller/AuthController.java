package com.xai.sudokupro.controller;

import com.xai.sudokupro.service.AuthService;
import com.xai.sudokupro.service.auth.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Self-service player accounts: register (unauthenticated) and change password. */
@RestController
@RequestMapping("/api/auth")
@Validated
@Tag(name = "Auth API")
public class AuthController {

    private final AccountService accountService;
    private final AuthService authService;

    public AuthController(AccountService accountService, AuthService authService) {
        this.accountService = accountService;
        this.authService = authService;
    }

    public record RegisterRequest(@NotBlank String username, @NotBlank String password) {}
    public record PasswordChangeRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    @Operation(summary = "Register a new player account (no authentication required)")
    @PostMapping("/register")
    public ResponseEntity<Object> register(@RequestBody @Validated RegisterRequest request) {
        try {
            accountService.register(request.username(), request.password());
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("status", "registered", "username", request.username()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(Map.of("title", "Invalid Registration", "detail", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("title", "Username Taken", "detail", e.getMessage()));
        }
    }

    @Operation(summary = "Change the caller's password")
    @PostMapping("/password")
    public ResponseEntity<Object> changePassword(@RequestBody @Validated PasswordChangeRequest request) {
        try {
            accountService.changePassword(authService.getCurrentPlayerId(),
                request.currentPassword(), request.newPassword());
            return ResponseEntity.ok(Map.of("status", "changed"));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("title", "Wrong Password", "detail", e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest()
                .body(Map.of("title", "Cannot Change Password", "detail", e.getMessage()));
        }
    }
}
