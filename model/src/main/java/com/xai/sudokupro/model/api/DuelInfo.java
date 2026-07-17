package com.xai.sudokupro.model.api;

/**
 * Wire representation of a duel from the CALLER's perspective: {@code gameId}
 * is the caller's own board for this duel (null until the duel is accepted).
 */
public record DuelInfo(
    String duelId,
    String challenger,
    String opponent,
    String status,
    String winner,
    String gameId
) {}
