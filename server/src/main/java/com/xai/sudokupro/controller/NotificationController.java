package com.xai.sudokupro.controller;

import com.xai.sudokupro.service.AuthService;
import com.xai.sudokupro.service.push.DeviceTokenStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Push device-token registration for the authenticated player. The token is a
 * provider-issued (FCM) registration token from a mobile/desktop client; the
 * server only stores and forwards it — it is never interpreted.
 */
@RestController
@RequestMapping("/api/notifications")
@Validated
@Tag(name = "Notifications API", description = "Push device-token registration")
public class NotificationController {

    private static final Logger logger = LoggerFactory.getLogger(NotificationController.class);

    private final DeviceTokenStore deviceTokenStore;
    private final AuthService authService;

    public NotificationController(DeviceTokenStore deviceTokenStore, AuthService authService) {
        this.deviceTokenStore = deviceTokenStore;
        this.authService = authService;
    }

    public record DeviceTokenRequest(@NotBlank @Size(max = 4096) String token) {}

    @Operation(summary = "Register (or replace) the caller's push device token")
    @PostMapping("/device-token")
    public ResponseEntity<Object> register(@RequestBody @Validated DeviceTokenRequest request) {
        String playerId = authService.getCurrentPlayerId();
        deviceTokenStore.register(playerId, request.token());
        logger.info("Registered push device token for player {}", playerId);
        return ResponseEntity.ok(Map.of("status", "registered"));
    }

    @Operation(summary = "Remove the caller's push device token (opt out of push)")
    @DeleteMapping("/device-token")
    public ResponseEntity<Void> unregister() {
        String playerId = authService.getCurrentPlayerId();
        deviceTokenStore.remove(playerId);
        logger.info("Removed push device token for player {}", playerId);
        return ResponseEntity.noContent().build();
    }
}
