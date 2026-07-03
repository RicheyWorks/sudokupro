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
}
