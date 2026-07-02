package com.xai.sudokupro.config;

import com.xai.sudokupro.service.AISolverService;
import com.xai.sudokupro.service.GameService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Engine health via Actuator (AUDIT P2-1). Replaces SudokuHealthMonitor's 1,500-line
 * hand-rolled checker: infrastructure checks (db, redis, disk space) are provided by
 * Spring Boot's autoconfigured HealthIndicators; this adds the one thing Boot can't
 * know — whether the game engine itself still works.
 *
 * Contributes to /actuator/health, which the Docker HEALTHCHECK and k8s probes hit.
 */
@Component("gameEngine")
public class GameEngineHealthIndicator implements HealthIndicator {

    private final AISolverService aiSolverService;
    private final GameService gameService;

    public GameEngineHealthIndicator(AISolverService aiSolverService, GameService gameService) {
        this.aiSolverService = aiSolverService;
        this.gameService = gameService;
    }

    @Override
    public Health health() {
        try {
            // Solver self-test on a fixed board — no real game state touched.
            String hint = aiSolverService.getNextLogicalMoveForTestBoard();
            if (hint == null || hint.isEmpty()) {
                return Health.down().withDetail("reason", "solver returned no move for test board").build();
            }
            int active = gameService.getActiveGamesCount();
            return Health.up()
                .withDetail("solver", "ok")
                .withDetail("activeGames", active)
                .build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
