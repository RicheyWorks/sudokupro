package com.xai.sudokupro.client.net;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One WebSocket message in the wire format shared with the server:
 * {@code {"type": ..., "from": ..., "payload": ...}}.
 */
public record Envelope(String type, String from, JsonNode payload) {

    public String payloadText() {
        if (payload == null) return "";
        return payload.isTextual() ? payload.asText() : payload.toString();
    }
}
