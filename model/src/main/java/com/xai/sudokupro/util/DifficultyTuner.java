package com.xai.sudokupro.util;

/**
 * Server-side hook for telemetry-driven difficulty adjustment (AUDIT P1-2).
 * Keeps Constants (shared model config) from depending on the server's
 * TelemetryService directly.
 */
@FunctionalInterface
public interface DifficultyTuner {
    int getDifficultyAdjustmentFactor();
}
