package com.xai.sudokupro.controller;

import com.xai.sudokupro.model.api.BoardState;
import com.xai.sudokupro.model.api.DuelInfo;
import com.xai.sudokupro.service.AuthService;
import com.xai.sudokupro.service.duel.DuelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Head-to-head duels: challenge, accept/decline, and list. Play itself runs on
 * the ordinary game/WebSocket machinery — each duelist owns a normal game.
 */
@RestController
@RequestMapping("/api/duel")
@Validated
@Tag(name = "Duel API")
public class DuelController {

    private final DuelService duelService;
    private final AuthService authService;

    public DuelController(DuelService duelService, AuthService authService) {
        this.duelService = duelService;
        this.authService = authService;
    }

    public record ChallengeRequest(@NotBlank String opponent,
                                   @Min(1) @Max(4) Integer difficulty) {}

    @Operation(summary = "Challenge another player to a duel")
    @PostMapping("/challenge")
    public ResponseEntity<Object> challenge(@RequestBody @Validated ChallengeRequest request) {
        try {
            String duelId = duelService.challenge(authService.getCurrentPlayerId(),
                request.opponent(), request.difficulty() == null ? 2 : request.difficulty());
            return ResponseEntity.ok(Map.of("duelId", duelId, "status", "PENDING"));
        } catch (IllegalArgumentException e) {
            return problem(HttpStatus.BAD_REQUEST, "Invalid Challenge", e.getMessage());
        }
    }

    @Operation(summary = "Accept a pending duel — returns your board; the race starts now")
    @PostMapping("/{duelId}/accept")
    public ResponseEntity<Object> accept(@PathVariable String duelId) {
        try {
            return ResponseEntity.ok(BoardState.from(
                duelService.accept(duelId, authService.getCurrentPlayerId())));
        } catch (SecurityException e) {
            return problem(HttpStatus.FORBIDDEN, "Not Your Duel", e.getMessage());
        } catch (IllegalStateException e) {
            return problem(HttpStatus.CONFLICT, "Duel Not Pending", e.getMessage());
        } catch (IllegalArgumentException e) {
            return problem(HttpStatus.NOT_FOUND, "Unknown Duel", e.getMessage());
        }
    }

    @Operation(summary = "Decline a pending duel")
    @PostMapping("/{duelId}/decline")
    public ResponseEntity<Object> decline(@PathVariable String duelId) {
        try {
            duelService.decline(duelId, authService.getCurrentPlayerId());
            return ResponseEntity.noContent().build();
        } catch (SecurityException e) {
            return problem(HttpStatus.FORBIDDEN, "Not Your Duel", e.getMessage());
        } catch (IllegalStateException e) {
            return problem(HttpStatus.CONFLICT, "Duel Not Pending", e.getMessage());
        } catch (IllegalArgumentException e) {
            return problem(HttpStatus.NOT_FOUND, "Unknown Duel", e.getMessage());
        }
    }

    @Operation(summary = "The caller's duels (pending, active, finished)")
    @GetMapping
    public ResponseEntity<List<DuelInfo>> myDuels() {
        return ResponseEntity.ok(duelService.duelsFor(authService.getCurrentPlayerId()));
    }

    private ResponseEntity<Object> problem(HttpStatus status, String title, String detail) {
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(Map.of("type", "https://sudokupro.com/errors/duel", "title", title, "detail", detail));
    }
}
