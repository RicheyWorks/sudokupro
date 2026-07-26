package com.xai.sudokupro.client.net;

/** Where the gameplay channel currently stands, for display and for policy decisions. */
public enum ConnectionState {

    /** No link, and none wanted — before the first game, or after a deliberate close. */
    DISCONNECTED,

    /** Live: moves, undo/redo and chat will reach the server. */
    CONNECTED,

    /** The link dropped and a retry is scheduled; play is paused but nothing is lost yet. */
    RECONNECTING,

    /**
     * Retrying stopped — either the budget ran out or the server refused in a way
     * a retry cannot fix. A manual {@link GameChannel#reconnectNow()} is still available;
     * this is a dead end for the automatic loop, not for the player.
     */
    FAILED
}
