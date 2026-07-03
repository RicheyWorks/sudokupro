package com.xai.sudokupro.client;

/**
 * Local notification sink. Replaces the server-side NotificationService the UI
 * used when the client and server shared a JVM — player-facing messages are a
 * pure client concern in the networked architecture.
 */
@FunctionalInterface
public interface Notifier {

    /** @param type e.g. "ui", "game", "hint", "error" — used for display styling only. */
    void notify(String type, String message);
}
