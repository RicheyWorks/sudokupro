package com.xai.sudokupro.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stub event journal for SudokuPro. Records named events per entity and
 * provides lightweight player summaries. Extend with persistence when ready.
 */
@Component
public class MemoryBank {

    private static final Logger logger = LoggerFactory.getLogger(MemoryBank.class);

    /**
     * Record a named event with an impact score for the given entity.
     *
     * @param entityId  player ID or "system"
     * @param eventName human-readable event label
     * @param impact    signed impact value (positive = beneficial, negative = detrimental)
     */
    public void recordEvent(String entityId, String eventName, double impact) {
        logger.debug("MemoryBank event [{}] '{}' impact={}", entityId, eventName, impact);
    }

    /**
     * Return a brief human-readable history summary for a player.
     * Stub implementation — extend with real persistence when ready.
     *
     * @param playerId the player whose history to summarise
     * @return summary string
     */
    public String summarizePlayer(String playerId) {
        return "Player " + playerId + ": no recorded history yet.";
    }
}
