package com.xai.sudokupro.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stub telemetry service.
 * Wire in real analytics data here when ready.
 */
@Service
public class TelemetryService {

    private static final Logger logger = LoggerFactory.getLogger(TelemetryService.class);

    /**
     * Returns an XP/points adjustment factor derived from live telemetry.
     * Returns 0 (no adjustment) until real data pipeline is connected.
     */
    public int getDifficultyAdjustmentFactor() {
        logger.debug("TelemetryService: getDifficultyAdjustmentFactor called — returning 0 (stub)");
        return 0;
    }
}
