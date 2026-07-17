package com.xai.sudokupro.controller;

import com.xai.sudokupro.model.api.BoardState;
import com.xai.sudokupro.service.AuthService;
import com.xai.sudokupro.service.daily.WeeklyTournamentService;
import com.xai.sudokupro.service.duel.SeasonService;
import com.xai.sudokupro.service.economy.InsufficientGemsException;
import com.xai.sudokupro.service.economy.PowerUpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Meta-game surface: weekly tournament, quarterly seasons, and the power-up shop. */
@RestController
@Validated
@Tag(name = "Meta-game API")
public class MetaGameController {

    private final WeeklyTournamentService tournament;
    private final SeasonService seasons;
    private final PowerUpService powerUps;
    private final AuthService authService;

    public MetaGameController(WeeklyTournamentService tournament, SeasonService seasons,
                              PowerUpService powerUps, AuthService authService) {
        this.tournament = tournament;
        this.seasons = seasons;
        this.powerUps = powerUps;
        this.authService = authService;
    }

    // ---- weekly tournament ----

    @Operation(summary = "The caller's tournament progress this week")
    @GetMapping("/api/tournament")
    public ResponseEntity<Object> tournamentStatus() {
        return ResponseEntity.ok(tournament.status(authService.getCurrentPlayerId()));
    }

    @Operation(summary = "Join puzzle 1-5 of this week's tournament")
    @PostMapping("/api/tournament/{puzzle}/join")
    public ResponseEntity<Object> joinTournament(@PathVariable @Min(1) @Max(5) int puzzle) {
        return ResponseEntity.ok(BoardState.from(
            tournament.join(authService.getCurrentPlayerId(), puzzle)));
    }

    @Operation(summary = "Weekly standings — players who finished all five, fastest total first")
    @GetMapping("/api/tournament/standings")
    public ResponseEntity<Object> standings(
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok(tournament.standings(limit));
    }

    // ---- seasons ----

    @Operation(summary = "Current season id and end date (triggers due rollovers)")
    @GetMapping("/api/season")
    public ResponseEntity<Object> season() {
        return ResponseEntity.ok(seasons.current());
    }

    // ---- power-up shop ----

    @Operation(summary = "Catalog prices and the caller's inventory")
    @GetMapping("/api/powerups")
    public ResponseEntity<Object> shop() {
        return ResponseEntity.ok(Map.of(
            "catalog", PowerUpService.CATALOG,
            "inventory", powerUps.inventory(authService.getCurrentPlayerId())));
    }

    @Operation(summary = "Buy a power-up with gems")
    @PostMapping("/api/powerups/buy/{type}")
    public ResponseEntity<Object> buy(@PathVariable String type) {
        try {
            int held = powerUps.buy(authService.getCurrentPlayerId(), type);
            return ResponseEntity.ok(Map.of("type", type, "held", held));
        } catch (InsufficientGemsException e) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(Map.of("title", "Not Enough Gems", "detail", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("title", "Unknown Power-Up", "detail", e.getMessage()));
        }
    }

    @Operation(summary = "Use a held power-up (gameId for EXTRA_LIFE/REVEAL_CELL, target for FREEZE)")
    @PostMapping("/api/powerups/use/{type}")
    public ResponseEntity<Object> use(@PathVariable String type,
                                      @RequestParam(required = false) String gameId,
                                      @RequestParam(required = false) String target) {
        try {
            powerUps.use(authService.getCurrentPlayerId(), type, gameId, target);
            return ResponseEntity.ok(Map.of("status", "used", "type", type));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("title", "Not Your Game", "detail", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("title", "Cannot Use", "detail", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("title", "Bad Request", "detail", e.getMessage()));
        }
    }
}
