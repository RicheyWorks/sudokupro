package com.xai.sudokupro.engine;

public class LuckProfile {
    public String playerId;
    public double averageReactionTime;
    public double clutchMoments;
    public double fatigueEvents;
    public double luckFactor;
    public long seedSnapshot;

    public LuckProfile(String playerId) {
        this.playerId = playerId;
        this.averageReactionTime = 0.0;
        this.clutchMoments = 0.0;
        this.fatigueEvents = 0.0;
        this.luckFactor = 0.0;
        this.seedSnapshot = 0L;
    }
}
