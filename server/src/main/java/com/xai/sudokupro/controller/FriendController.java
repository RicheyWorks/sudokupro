package com.xai.sudokupro.controller;

import com.xai.sudokupro.service.AuthService;
import com.xai.sudokupro.service.social.FriendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Friends & presence: request/accept/decline/remove and an online-flagged list. */
@RestController
@RequestMapping("/api/friends")
@Tag(name = "Friends API")
public class FriendController {

    private final FriendService friendService;
    private final AuthService authService;

    public FriendController(FriendService friendService, AuthService authService) {
        this.friendService = friendService;
        this.authService = authService;
    }

    @Operation(summary = "The caller's friends with online flags")
    @GetMapping
    public ResponseEntity<Object> friends() {
        return ResponseEntity.ok(friendService.friendsOf(authService.getCurrentPlayerId()));
    }

    @Operation(summary = "Incoming friend requests")
    @GetMapping("/pending")
    public ResponseEntity<Object> pending() {
        return ResponseEntity.ok(friendService.pendingFor(authService.getCurrentPlayerId()));
    }

    @Operation(summary = "Send a friend request")
    @PostMapping("/request/{playerId}")
    public ResponseEntity<Object> request(@PathVariable String playerId) {
        try {
            friendService.request(authService.getCurrentPlayerId(), playerId);
            return ResponseEntity.ok(Map.of("status", "requested"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("title", "Invalid Request", "detail", e.getMessage()));
        }
    }

    @Operation(summary = "Accept a pending friend request")
    @PostMapping("/accept/{playerId}")
    public ResponseEntity<Object> accept(@PathVariable String playerId) {
        try {
            friendService.accept(authService.getCurrentPlayerId(), playerId);
            return ResponseEntity.ok(Map.of("status", "friends"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("title", "No Such Request", "detail", e.getMessage()));
        }
    }

    @Operation(summary = "Decline a pending friend request")
    @PostMapping("/decline/{playerId}")
    public ResponseEntity<Void> decline(@PathVariable String playerId) {
        friendService.decline(authService.getCurrentPlayerId(), playerId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Remove a friend (both directions)")
    @DeleteMapping("/{playerId}")
    public ResponseEntity<Void> remove(@PathVariable String playerId) {
        friendService.remove(authService.getCurrentPlayerId(), playerId);
        return ResponseEntity.noContent().build();
    }
}
