package com.example.peg.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventRecordTest {

    @Test
    void recordExposesAllFields() throws Exception {
        UUID eventId = UUID.randomUUID();
        Instant created = Instant.parse("2024-06-01T00:00:00Z");
        Instant processed = Instant.parse("2024-06-01T00:01:00Z");
        ObjectMapper mapper = new ObjectMapper();

        EventRecord r = new EventRecord(
                42L, "p", eventId, EventType.ORDER_CREATED,
                "ORD-1", mapper.readTree("{\"k\":1}"),
                EventStatus.PROCESSED, null, created, processed);

        assertThat(r.id()).isEqualTo(42L);
        assertThat(r.partnerId()).isEqualTo("p");
        assertThat(r.eventId()).isEqualTo(eventId);
        assertThat(r.eventType()).isEqualTo(EventType.ORDER_CREATED);
        assertThat(r.businessRef()).isEqualTo("ORD-1");
        assertThat(r.payload().get("k").intValue()).isEqualTo(1);
        assertThat(r.status()).isEqualTo(EventStatus.PROCESSED);
        assertThat(r.error()).isNull();
        assertThat(r.createdAt()).isEqualTo(created);
        assertThat(r.processedAt()).isEqualTo(processed);
    }
}
