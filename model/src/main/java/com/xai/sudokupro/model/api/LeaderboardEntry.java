package com.xai.sudokupro.model.api;

/** Wire representation of one public leaderboard row (shared server/client). */
public record LeaderboardEntry(
    int rank,
    String username,
    int sortValue,
    String tier,
    int cosmicDrip,
    int hypeMeter,
    int duelWins
) {}
