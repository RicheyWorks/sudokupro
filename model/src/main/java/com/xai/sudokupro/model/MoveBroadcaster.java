package com.xai.sudokupro.model;

/**
 * Transport-agnostic hook for announcing applied moves (AUDIT P1-2).
 * Lets the model stay free of server/websocket dependencies: the server's
 * MultiplayerBroadcaster implements this and fans out over WebSocket sessions.
 */
@FunctionalInterface
public interface MoveBroadcaster {
    void sendMove(String gameId, EnhancedMove move);
}
