package com.example.peg.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PartnerEventMessageTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void serializeAndDeserialize_roundTrips() throws Exception {
        UUID eventId = UUID.randomUUID();
        Instant t = Instant.parse("2024-06-01T12:00:00Z");
        JsonNode payload = mapper.readTree("{\"foo\":\"bar\"}");

        PartnerEventMessage msg = new PartnerEventMessage(
                eventId, "partner-acme",
                EventType.ORDER_CREATED,
                "ORD-1", payload, t,
                "00-aaaa-bbbb-01", "rojo=00f067aa0ba902b7");

        String json = mapper.writeValueAsString(msg);
        PartnerEventMessage decoded = mapper.readValue(json, PartnerEventMessage.class);

        assertThat(decoded).isEqualTo(msg);
    }

    @Test
    void serialization_omitsNullTraceFields() throws Exception {
        PartnerEventMessage msg = new PartnerEventMessage(
                UUID.randomUUID(), "p", EventType.ADDRESS_UPDATED,
                null, mapper.nullNode(), Instant.now(), null, null);

        String json = mapper.writeValueAsString(msg);

        assertThat(json).doesNotContain("traceparent");
        assertThat(json).doesNotContain("tracestate");
    }
}
