package com.xai.sudokupro.client.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EnvelopeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void payloadTextReturnsRawStringForTextualPayload() {
        Envelope envelope = new Envelope("chat", "richmond", new TextNode("hello"));
        assertEquals("hello", envelope.payloadText());
    }

    @Test
    void payloadTextStringifiesNonTextualPayload() throws Exception {
        var payload = mapper.readTree("{\"row\":1,\"col\":2,\"newVal\":5}");
        Envelope envelope = new Envelope("move", "richmond", payload);
        assertEquals(payload.toString(), envelope.payloadText());
    }

    @Test
    void payloadTextReturnsEmptyStringForNullPayload() {
        Envelope envelope = new Envelope("leave", "richmond", null);
        assertEquals("", envelope.payloadText());
    }
}
