package com.example.peg.audit;

import com.example.peg.PgmqPostgresInitializer;
import com.example.peg.shared.EventStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit log integration tests. Verifies that the partitioned
 * {@code event_audit_log} table accepts writes, returns them in occurred-at
 * order, and that the {@code occurredAfter} hint produces the right read
 * shape (partition-pruning input).
 */
@SpringBootTest
@ContextConfiguration(initializers = PgmqPostgresInitializer.class)
class AuditLoggerIT {

    @Autowired AuditLogger audit;
    @Autowired JdbcTemplate jdbc;

    private static final String PARTNER = "partner-acme";

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM event_audit_log");
    }

    @Test
    void transitionsAreReturnedInOccurredAtOrder() {
        UUID eventId = UUID.randomUUID();
        audit.transition(PARTNER, eventId, null, EventStatus.RECEIVED, "ingest", null);
        audit.transition(PARTNER, eventId, EventStatus.RECEIVED, EventStatus.PENDING, "outbox-poller", null);
        audit.transition(PARTNER, eventId, EventStatus.PENDING, EventStatus.PROCESSING, "worker:foo", null);
        audit.transition(PARTNER, eventId, EventStatus.PROCESSING, EventStatus.PROCESSED, "worker:foo", null);

        var history = audit.historyFor(PARTNER, eventId, null);

        assertThat(history).hasSize(4);
        assertThat(history).extracting("toStatus").containsExactly(
                EventStatus.RECEIVED,
                EventStatus.PENDING,
                EventStatus.PROCESSING,
                EventStatus.PROCESSED);
        assertThat(history).extracting("actor").containsExactly(
                "ingest", "outbox-poller", "worker:foo", "worker:foo");
        assertThat(history.get(0).fromStatus()).isNull();
        assertThat(history.get(1).fromStatus()).isEqualTo(EventStatus.RECEIVED);
    }

    @Test
    void failedTransitionCarriesError() {
        UUID eventId = UUID.randomUUID();
        audit.transition(PARTNER, eventId, EventStatus.PROCESSING, EventStatus.FAILED,
                "worker:foo", "downstream timeout");

        var row = audit.historyFor(PARTNER, eventId, null).get(0);
        assertThat(row.error()).isEqualTo("downstream timeout");
    }

    @Test
    void differentEventsAreIsolated() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        audit.transition(PARTNER, a, null, EventStatus.RECEIVED, "ingest", null);
        audit.transition(PARTNER, b, null, EventStatus.RECEIVED, "ingest", null);
        audit.transition(PARTNER, b, EventStatus.RECEIVED, EventStatus.PENDING, "outbox-poller", null);

        assertThat(audit.historyFor(PARTNER, a, null)).hasSize(1);
        assertThat(audit.historyFor(PARTNER, b, null)).hasSize(2);
    }

    @Test
    void occurredAfterHintNarrowsResultSet() {
        UUID eventId = UUID.randomUUID();
        audit.transition(PARTNER, eventId, null, EventStatus.RECEIVED, "ingest", null);
        audit.transition(PARTNER, eventId, EventStatus.RECEIVED, EventStatus.PENDING, "outbox-poller", null);

        // All rows
        assertThat(audit.historyFor(PARTNER, eventId, null)).hasSize(2);

        // Hint at "right now minus a generous window" still returns everything
        Instant longAgo = Instant.now().minus(Duration.ofDays(7));
        assertThat(audit.historyFor(PARTNER, eventId, longAgo)).hasSize(2);

        // Hint set to the future returns nothing
        Instant future = Instant.now().plus(Duration.ofDays(1));
        assertThat(audit.historyFor(PARTNER, eventId, future)).isEmpty();
    }
}
