package com.xai.sudokupro.controller;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.service.AuthService;
import com.xai.sudokupro.service.economy.EconomyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The caller's wallet: gems, XP, level, duel record, and the current hint price. */
@RestController
@RequestMapping("/api/economy")
@Tag(name = "Economy API")
public class EconomyController {

    private final EconomyService economyService;
    private final AuthService authService;

    public EconomyController(EconomyService economyService, AuthService authService) {
        this.economyService = economyService;
        this.authService = authService;
    }

    @Operation(summary = "The caller's wallet and the current hint price")
    @GetMapping("/wallet")
    public ResponseEntity<Object> wallet() {
        User user = economyService.walletFor(authService.getCurrentPlayerId());
        return ResponseEntity.ok(Map.of(
            "playerId", user.getUsername(),
            "gems", user.getGems(),
            "xp", user.getXp(),
            "level", user.getLevel(),
            "duelWins", user.getDuelWins(),
            "duelLosses", user.getDuelLosses(),
            "hintCost", economyService.hintCost()
        ));
    }
}
