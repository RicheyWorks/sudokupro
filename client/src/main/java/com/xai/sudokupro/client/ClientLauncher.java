package com.xai.sudokupro.client;

import com.xai.sudokupro.ui.MainStage;

/**
 * Desktop entry point (AUDIT follow-up: client/server network separation).
 *
 * <p>The client no longer embeds the server: it is a pure JavaFX application
 * that talks to a running SudokuPro server over REST and WebSocket. Start a
 * server first ({@code docker compose up} or
 * {@code mvn -pl server -am spring-boot:run}), then launch this class.
 *
 * <p>Connection defaults come from {@code SUDOKUPRO_SERVER}, {@code SUDOKUPRO_USER}
 * and {@code SUDOKUPRO_PASS}; the welcome screen lets the player override them.
 */
public final class ClientLauncher {

    private ClientLauncher() {}

    public static void main(String[] args) {
        javafx.application.Application.launch(MainStage.class, args);
    }
}
