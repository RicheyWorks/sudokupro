package com.xai.sudokupro.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a single event in SudokuPro's cosmic grid battles.
 * Tracks moves, hints, and solves with divine timing and optional payload.
 */
@JsonPropertyOrder({"type", "playerId", "timestamp", "payload"}) // Consistent JSON order
public class GameEvent {

    public enum EventType {
        MOVE, HINT, SOLVE, JOIN, LEAVE, SCORE;

        // Custom deserializer could be added if needed
    }

    @JsonProperty("type")
    @JsonSerialize(using = ToStringSerializer.class)
    @JsonDeserialize(using = com.fasterxml.jackson.databind.JsonDeserializer.None.class) // Placeholder; custom deserializer optional
    private EventType type; // Enum for type safety

    @JsonProperty("playerId")
    private String playerId;

    @JsonProperty("timestamp")
    private long timestamp; // Epoch millis, ISO 8601 compatible

    @JsonProperty("payload")
    private Map<String, Object> payload; // Optional metadata

    // Constructors
    public GameEvent() {
        this.timestamp = Instant.now().toEpochMilli();
    }

    public GameEvent(EventType type, String playerId) {
        this.type = type;
        this.playerId = playerId;
        this.timestamp = Instant.now().toEpochMilli();
    }

    public GameEvent(EventType type, String playerId, Map<String, Object> payload) {
        this.type = type;
        this.playerId = playerId;
        this.timestamp = Instant.now().toEpochMilli();
        this.payload = payload;
    }

    // Utility method
    public static GameEvent create(String type, String playerId) {
        return new GameEvent(EventType.valueOf(type.toUpperCase()), playerId);
    }

    public static GameEvent create(String type, String playerId, Map<String, Object> payload) {
        return new GameEvent(EventType.valueOf(type.toUpperCase()), playerId, payload);
    }

    // Getters and Setters
    public EventType getType() {
        return type;
    }

    public void setType(EventType type) {
        this.type = type;
    }

    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    // Equals, HashCode, and ToString
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GameEvent that = (GameEvent) o;
        return timestamp == that.timestamp &&
               type == that.type &&
               Objects.equals(playerId, that.playerId) &&
               Objects.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, playerId, timestamp, payload);
    }

    @Override
    public String toString() {
        return "GameEvent{" +
               "type=" + type +
               ", playerId='" + playerId + '\'' +
               ", timestamp=" + timestamp +
               ", payload=" + (payload != null ? payload : "none") +
               '}';
    }

    // Future replay serialization (e.g., in WebSocketController or GameService)
    /*
    List<GameEvent> eventLog = new ArrayList<>();
    objectMapper.writeValue(new File("replay.json"), eventLog);
    */

    // Future REST API endpoint (e.g., in SudokuGameController)
    /*
    @GetMapping("/replay/{id}")
    public ResponseEntity<List<GameEvent>> getReplay(@PathVariable String id) {
        // Fetch from GameService or Redis
    }
    */
}
