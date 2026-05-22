package com.xai.sudokupro.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.LocalDateTime;

/**
 * Cosmic snapshot of a SudokuPro player's glory.
 * Projects key stats—username, level, points, wins, drip, and hype—for galactic leaderboards with divine precision.
 */
@JsonPropertyOrder({"username", "level", "points", "duelWins", "winRate", "cosmicDrip", "hypeMeter", "streak", "fanCount", "xp", "achievementCount", "lastLogin"})
public interface UserSummary {
    @JsonProperty("username")
    String getUsername();

    @JsonProperty("level")
    int getLevel();

    @JsonProperty("points")
    int getPoints();

    @JsonProperty("duelWins")
    int getDuelWins();

    @JsonProperty("winRate")
    double getWinRate(); // Duel win percentage

    @JsonProperty("cosmicDrip")
    int getCosmicDrip();  // Cosmic flair earned

    @JsonProperty("hypeMeter")
    int getHypeMeter();   // Hype from wins and fans

    @JsonProperty("streak")
    int getStreak();      // Current solve streak

    @JsonProperty("fanCount")
    int getFanCount();    // Number of fans

    @JsonProperty("xp")
    int getXp();          // Experience points

    @JsonProperty("achievementCount")
    int getAchievementCount(); // Number of unlocked achievements

    @JsonProperty("lastLogin")
    LocalDateTime getLastLogin(); // Last login timestamp
}
