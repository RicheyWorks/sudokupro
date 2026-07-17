package com.xai.sudokupro.controller;

import com.xai.sudokupro.model.api.BoardState;
import com.xai.sudokupro.model.api.DailyScore;
import com.xai.sudokupro.model.api.DailyStatus;
import com.xai.sudokupro.service.AuthService;
import com.xai.sudokupro.service.daily.DailyPuzzleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Daily puzzle: one shared board per UTC day, per-player copies, streaks, and
 * a fastest-solve leaderboard. Play happens over the ordinary game channels —
 * these endpoints only cover the daily-specific lifecycle.
 */
@RestController
@RequestMapping("/api/daily")
@Validated
@Tag(name = "Daily Puzzle API")
public class DailyPuzzleController {

    private final DailyPuzzleService dailyPuzzleService;
    private final AuthService authService;

    public DailyPuzzleController(DailyPuzzleService dailyPuzzleService, AuthService authService) {
        this.dailyPuzzleService = dailyPuzzleService;
        this.authService = authService;
    }

    @Operation(summary = "The caller's status for today's puzzle: joined, completed, streak")
    @GetMapping
    public ResponseEntity<DailyStatus> status() {
        return ResponseEntity.ok(dailyPuzzleService.status(authService.getCurrentPlayerId()));
    }

    @Operation(summary = "Join today's puzzle (idempotent — returns the existing game if already joined)")
    @PostMapping("/join")
    public ResponseEntity<BoardState> join() {
        return ResponseEntity.ok(BoardState.from(
            dailyPuzzleService.joinDaily(authService.getCurrentPlayerId())));
    }

    @Operation(summary = "Today's fastest solvers")
    @GetMapping("/leaderboard")
    public ResponseEntity<List<DailyScore>> leaderboard(
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok(dailyPuzzleService.leaderboard(limit));
    }
}
