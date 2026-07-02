package com.xai.sudokupro.model;

import java.time.LocalDateTime;

public record Notification(
        String playerId,
        String type,
        String message,
        LocalDateTime timestamp
) {
    /** Convenience constructor — timestamps itself. */
    public Notification(String playerId, String type, String message) {
        this(playerId, type, message, LocalDateTime.now());
    }
}
