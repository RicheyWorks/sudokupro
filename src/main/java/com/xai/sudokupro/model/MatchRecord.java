package com.xai.sudokupro.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Column;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a single match record in a user's cosmic duel history for SudokuPro.
 * Chronicles victories and defeats with temporal precision.
 */
@Embeddable
@JsonPropertyOrder({"timestamp", "won"}) // Consistent JSON order
public class MatchRecord implements Serializable {

    @Column(name = "match_timestamp")
    @JsonProperty("timestamp")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss") // ISO 8601
    private LocalDateTime timestamp;

    @Column(name = "match_won")
    @JsonProperty("won")
    private boolean won;

    // Constructors
    public MatchRecord() {
        this.timestamp = LocalDateTime.now(); // Default to now
    }

    public MatchRecord(LocalDateTime timestamp, boolean won) {
        this.timestamp = timestamp != null ? timestamp : LocalDateTime.now();
        this.won = won;
    }

    // Factory method
    public static MatchRecord of(LocalDateTime timestamp, boolean won) {
        return new MatchRecord(timestamp, won);
    }

    // Getters and Setters
    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public boolean isWon() {
        return won;
    }

    public void setWon(boolean won) {
        this.won = won;
    }

    // To Map for serialization
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("timestamp", timestamp != null ? timestamp.toString() : null);
        map.put("won", won);
        return map;
    }

    // Replay-friendly export
    public String toReplayString() {
        return (won ? "Victory" : "Defeat") + " at " + (timestamp != null ? timestamp : "unknown");
    }

    // Equals, HashCode, and ToString
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MatchRecord that = (MatchRecord) o;
        return won == that.won && Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timestamp, won);
    }

    @Override
    public String toString() {
        return "MatchRecord[timestamp=" + (timestamp != null ? timestamp : "null") + ", won=" + won + "]";
    }

    // Future polish ideas
    /*
    - Confidence scoring or ranking (for gamification):
      @JsonProperty("score") private Integer matchScore;
    - Match UUID (for referencing or replaying):
      @Column(name = "match_id", nullable = false) private UUID matchId = UUID.randomUUID();
    - Embedded JSON schema sample (for Swagger/docs):
      {
        "timestamp": "2025-03-28T21:00:00",
        "won": true
      }
    */
}
