package com.example.peg.audit;

import com.example.peg.shared.EventStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRecordTest {

    @Test
    void recordExposesAllFields() {
        UUID id = UUID.randomUUID();
        Instant t = Instant.parse("2024-06-01T00:00:00Z");
        AuditRecord r = new AuditRecord(
                42L, "p", id,
                EventStatus.PROCESSING, EventStatus.FAILED,
                "worker:foo", "boom", t);

        assertThat(r.id()).isEqualTo(42L);
        assertThat(r.partnerId()).isEqualTo("p");
        assertThat(r.eventId()).isEqualTo(id);
        assertThat(r.fromStatus()).isEqualTo(EventStatus.PROCESSING);
        assertThat(r.toStatus()).isEqualTo(EventStatus.FAILED);
        assertThat(r.actor()).isEqualTo("worker:foo");
        assertThat(r.error()).isEqualTo("boom");
        assertThat(r.occurredAt()).isEqualTo(t);
    }
}
