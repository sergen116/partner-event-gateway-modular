package com.example.peg.ingest;

import com.example.peg.shared.EventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubmitEventRequestTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void deserializes_fromWireJson() throws Exception {
        String json = """
                {"eventType":"OrderCreated","businessRef":"ORD-1","payload":{"x":1}}
                """;
        SubmitEventRequest req = mapper.readValue(json, SubmitEventRequest.class);
        assertThat(req.eventType()).isEqualTo(EventType.ORDER_CREATED);
        assertThat(req.businessRef()).isEqualTo("ORD-1");
        assertThat(req.payload().get("x").intValue()).isEqualTo(1);
    }

    @Test
    void deserializes_withMissingBusinessRef() throws Exception {
        SubmitEventRequest req = mapper.readValue(
                "{\"eventType\":\"OrderCreated\",\"payload\":null}", SubmitEventRequest.class);
        assertThat(req.businessRef()).isNull();
    }

    @Test
    void submitEventResponse_recordIsValueLike() {
        var a = new SubmitEventResponse(java.util.UUID.randomUUID(),
                com.example.peg.shared.EventStatus.PENDING, false,
                java.time.Instant.parse("2024-06-01T00:00:00Z"));
        assertThat(a.duplicate()).isFalse();
        assertThat(a.status()).isEqualTo(com.example.peg.shared.EventStatus.PENDING);
    }
}
