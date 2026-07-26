package com.xai.sudokupro.controller;

import com.xai.sudokupro.model.api.LeaderboardEntry;
import com.xai.sudokupro.service.LeaderboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public leaderboard, exposed for remote clients (AUDIT follow-up: client/server separation). */
@RestController
@Validated
@Tag(name = "Leaderboard API")
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    public LeaderboardController(LeaderboardService leaderboardService) {
        this.leaderboardService = leaderboardService;
    }

    @Operation(summary = "Top players by combined points")
    @GetMapping("/api/leaderboard")
    public ResponseEntity<List<LeaderboardEntry>> leaderboard(
            @RequestParam(defaultValue = "5") @Min(1) @Max(100) int limit) {
        List<LeaderboardEntry> entries = leaderboardService.getPublicLeaderboard(limit).stream()
            .map(s -> new LeaderboardEntry(s.rank(), s.username(), s.sortValue(), s.tier(),
                s.cosmicDrip(), s.hypeMeter(), s.duelWins()))
            .toList();
        return ResponseEntity.ok(entries);
    }

    /**
     * Parameter-validation failures are the caller's fault, not the server's.
     *
     * <p>The class-level {@code @Validated} makes Spring validate {@code @Min}/{@code @Max}
     * through an AOP proxy, which raises {@code ConstraintViolationException}. That
     * exception has NO default Spring MVC mapping, so every out-of-range {@code limit}
     * surfaced as an HTTP 500 with a stack trace in the log — verified live:
     * {@code GET /api/leaderboard?limit=0}, {@code ?limit=-5} and {@code ?limit=101} all
     * escaped the dispatcher as 500. A client cannot tell "you asked for something
     * impossible" from "the server is broken", and 500s are what monitoring pages on.
     *
     * <p>{@link SudokuGameController} already carries this handler for exactly the same
     * reason; this controller (and {@link MetaGameController}) were missed.
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(
        jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(
            jakarta.validation.ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
            .map(v -> v.getPropertyPath() + " " + v.getMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("invalid request parameter");
        return ResponseEntity.badRequest()
            .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
            .body(java.util.Map.of("title", "Invalid Parameter", "detail", detail));
    }
}
