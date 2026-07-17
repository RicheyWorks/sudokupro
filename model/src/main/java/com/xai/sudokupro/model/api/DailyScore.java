package com.xai.sudokupro.model.api;

/**
 * One row of the daily-puzzle leaderboard: who solved today's puzzle and how
 * fast. Rank is 1-based and assigned by the server.
 */
public record DailyScore(
    int rank,
    String playerId,
    long solveTimeSeconds
) {}
