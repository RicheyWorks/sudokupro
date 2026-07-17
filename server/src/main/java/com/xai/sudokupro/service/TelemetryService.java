package com.xai.sudokupro.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * DifficultyTuner backed by real data: reports how far the population's average
 * recommended difficulty (SmartDifficultyService) sits from the default. Was a
 * stub returning 0 since the module split; the injection is @Lazy because
 * Constants consumes this at startup, before any telemetry exists — the factor
 * simply reads 0 until players have history.
 */
@Service
public class TelemetryService implements com.xai.sudokupro.util.DifficultyTuner {

    private static final Logger logger = LoggerFactory.getLogger(TelemetryService.class);

    private final SmartDifficultyService smartDifficulty;

    public TelemetryService(@org.springframework.context.annotation.Lazy SmartDifficultyService smartDifficulty) {
        this.smartDifficulty = smartDifficulty;
    }

    @Override
    public int getDifficultyAdjustmentFactor() {
        try {
            int factor = smartDifficulty.globalAdjustmentFactor();
            logger.debug("Difficulty adjustment factor from live skill data: {}", factor);
            return factor;
        } catch (Exception e) {
            logger.debug("Telemetry unavailable ({}); reporting neutral factor", e.getMessage());
            return 0;
        }
    }
}
