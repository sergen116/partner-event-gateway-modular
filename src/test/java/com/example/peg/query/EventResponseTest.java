package com.example.peg.query;

import com.example.peg.shared.EventRecord;
import com.example.peg.shared.EventStatus;
import com.example.peg.shared.EventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventResponseTest {

    @Test
    void from_copiesAllFieldsFromRecord() throws Exception {
        UUID eventId = UUID.randomUUID();
        Instant created = Instant.parse("2024-06-01T00:00:00Z");
        Instant processed = Instant.parse("2024-06-01T00:01:00Z");
        ObjectMapper mapper = new ObjectMapper();

        EventRecord r = new EventRecord(
                42L, "p", eventId, EventType.RETURN_REQUESTED,
                "RT-9", mapper.readTree("{\"a\":1}"),
                EventStatus.FAILED, "boom", created, processed);

        EventResponse resp = EventResponse.from(r);

        assertThat(resp.id()).isEqualTo(42L);
        assertThat(resp.partnerId()).isEqualTo("p");
        assertThat(resp.eventId()).isEqualTo(eventId);
        assertThat(resp.eventType()).isEqualTo(EventType.RETURN_REQUESTED);
        assertThat(resp.businessRef()).isEqualTo("RT-9");
        assertThat(resp.payload().get("a").intValue()).isEqualTo(1);
        assertThat(resp.status()).isEqualTo(EventStatus.FAILED);
        assertThat(resp.error()).isEqualTo("boom");
        assertThat(resp.createdAt()).isEqualTo(created);
        assertThat(resp.processedAt()).isEqualTo(processed);
    }
}
