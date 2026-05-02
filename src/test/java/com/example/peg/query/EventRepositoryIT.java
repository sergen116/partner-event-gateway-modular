package com.example.peg.query;

import com.example.peg.PgmqPostgresInitializer;
import com.example.peg.audit.AuditLogger;
import com.example.peg.shared.EventStatus;
import com.example.peg.shared.EventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Integration tests for the events repository against a real Testcontainers
 * Postgres with pgmq + pg_partman. Covers idempotent insert, atomic state
 * transitions (each writes an audit row), the Specifications-driven dynamic
 * query, and pagination.
 */
@SpringBootTest
@ContextConfiguration(initializers = PgmqPostgresInitializer.class)
class EventRepositoryIT {

    @Autowired EventRepository events;
    @Autowired AuditLogger audit;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private static final String PARTNER_A = "partner-acme";
    private static final String PARTNER_B = "partner-globex";
    private static final String ACTOR = "test";

    @BeforeEach
    void cleanEvents() {
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM event_audit_log");
    }

    @Test
    void insertIfAbsent_insertsOnFirstCall_andWritesAuditRow() {
        UUID id = UUID.randomUUID();
        ObjectNode payload = mapper.createObjectNode().put("k", "v");

        var result = events.insertIfAbsent(PARTNER_A, id, EventType.ORDER_CREATED,
                "ORD-1", payload, ACTOR);

        assertThat(result.wasInserted()).isTrue();
        assertThat(result.row().status()).isEqualTo(EventStatus.RECEIVED);
        assertThat(result.row().eventId()).isEqualTo(id);

        // Audit row written for the RECEIVED transition.
        var history = audit.historyFor(PARTNER_A, id, null);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).fromStatus()).isNull();
        assertThat(history.get(0).toStatus()).isEqualTo(EventStatus.RECEIVED);
        assertThat(history.get(0).actor()).isEqualTo(ACTOR);
    }

    @Test
    void insertIfAbsent_isIdempotent_andDoesNotDoubleAudit() {
        UUID id = UUID.randomUUID();
        ObjectNode payload = mapper.createObjectNode().put("k", "v");

        events.insertIfAbsent(PARTNER_A, id, EventType.ORDER_CREATED, "ORD-1", payload, ACTOR);
        var second = events.insertIfAbsent(PARTNER_A, id, EventType.ORDER_CREATED, "ORD-1", payload, ACTOR);

        assertThat(second.wasInserted()).isFalse();
        assertThat(second.row().eventId()).isEqualTo(id);

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM events WHERE partner_id = ? AND event_id = ?",
                Long.class, PARTNER_A, id);
        assertThat(count).isEqualTo(1);

        // Audit row written only on first insert.
        assertThat(audit.historyFor(PARTNER_A, id, null)).hasSize(1);
    }

    @Test
    void differentPartners_canReuseSameEventId() {
        UUID sharedId = UUID.randomUUID();
        ObjectNode payload = mapper.createObjectNode().put("k", "v");

        var a = events.insertIfAbsent(PARTNER_A, sharedId, EventType.ORDER_CREATED, "ORD-1", payload, ACTOR);
        var b = events.insertIfAbsent(PARTNER_B, sharedId, EventType.ORDER_CREATED, "ORD-1", payload, ACTOR);

        assertThat(a.wasInserted()).isTrue();
        assertThat(b.wasInserted()).isTrue();
    }

    @Test
    void fullLifecycle_writesAuditRowAtEachTransition() {
        UUID id = UUID.randomUUID();
        events.insertIfAbsent(PARTNER_A, id, EventType.ORDER_CREATED, null,
                mapper.createObjectNode(), "ingest");
        events.markPending(PARTNER_A, id, "outbox-poller");
        boolean claimed = events.tryMarkProcessing(PARTNER_A, id, "worker:order-created");
        assertThat(claimed).isTrue();
        events.markProcessed(PARTNER_A, id, "worker:order-created");

        var history = audit.historyFor(PARTNER_A, id, null);
        assertThat(history).hasSize(4);
        assertThat(history).extracting("toStatus")
                .containsExactly(
                        EventStatus.RECEIVED,
                        EventStatus.PENDING,
                        EventStatus.PROCESSING,
                        EventStatus.PROCESSED);
        assertThat(history).extracting("actor")
                .containsExactly("ingest", "outbox-poller", "worker:order-created", "worker:order-created");
    }

    @Test
    void tryMarkProcessing_succeeds_onPendingRow_andSecondCallStillSucceeds() {
        UUID id = UUID.randomUUID();
        events.insertIfAbsent(PARTNER_A, id, EventType.ORDER_CREATED, null,
                mapper.createObjectNode(), ACTOR);
        events.markPending(PARTNER_A, id, ACTOR);

        boolean claimed = events.tryMarkProcessing(PARTNER_A, id, ACTOR);
        assertThat(claimed).isTrue();

        boolean reclaim = events.tryMarkProcessing(PARTNER_A, id, ACTOR);
        assertThat(reclaim).as("PROCESSING -> PROCESSING is allowed for crash recovery").isTrue();
    }

    @Test
    void tryMarkProcessing_fails_onProcessedRow() {
        UUID id = UUID.randomUUID();
        events.insertIfAbsent(PARTNER_A, id, EventType.ORDER_CREATED, null,
                mapper.createObjectNode(), ACTOR);
        events.markPending(PARTNER_A, id, ACTOR);
        events.tryMarkProcessing(PARTNER_A, id, ACTOR);
        events.markProcessed(PARTNER_A, id, ACTOR);

        boolean reclaim = events.tryMarkProcessing(PARTNER_A, id, ACTOR);
        assertThat(reclaim).as("terminal rows must not be reclaimed").isFalse();
    }

    @Test
    void tryMarkProcessing_fails_onReceivedRow() {
        // RECEIVED means the event hasn't been published to pgmq yet.
        // A worker can never legitimately try to process it.
        UUID id = UUID.randomUUID();
        events.insertIfAbsent(PARTNER_A, id, EventType.ORDER_CREATED, null,
                mapper.createObjectNode(), ACTOR);

        boolean claimed = events.tryMarkProcessing(PARTNER_A, id, ACTOR);
        assertThat(claimed).isFalse();
    }

    @Test
    void markFailed_recordsErrorAndAuditsTransition() {
        UUID id = UUID.randomUUID();
        events.insertIfAbsent(PARTNER_A, id, EventType.ORDER_CREATED, null,
                mapper.createObjectNode(), ACTOR);
        events.markPending(PARTNER_A, id, ACTOR);
        events.tryMarkProcessing(PARTNER_A, id, ACTOR);
        events.markFailed(PARTNER_A, id, "downstream timeout", "worker:order-created");

        var row = events.findById(PARTNER_A, id).orElseThrow();
        assertThat(row.status()).isEqualTo(EventStatus.FAILED);
        assertThat(row.error()).isEqualTo("downstream timeout");

        var history = audit.historyFor(PARTNER_A, id, null);
        var failedTransition = history.get(history.size() - 1);
        assertThat(failedTransition.toStatus()).isEqualTo(EventStatus.FAILED);
        assertThat(failedTransition.error()).isEqualTo("downstream timeout");
    }

    @Test
    void query_filtersByPartner() {
        events.insertIfAbsent(PARTNER_A, UUID.randomUUID(), EventType.ORDER_CREATED, "A1", mapper.createObjectNode(), ACTOR);
        events.insertIfAbsent(PARTNER_A, UUID.randomUUID(), EventType.SHIPMENT_UPDATED, "A2", mapper.createObjectNode(), ACTOR);
        events.insertIfAbsent(PARTNER_B, UUID.randomUUID(), EventType.ORDER_CREATED, "B1", mapper.createObjectNode(), ACTOR);

        Instant from = Instant.now().minus(Duration.ofHours(1));
        var partnerARows = events.query(EventQuery.builder().partnerId(PARTNER_A).fromCreatedAt(from).build());
        var partnerBRows = events.query(EventQuery.builder().partnerId(PARTNER_B).fromCreatedAt(from).build());

        assertThat(partnerARows).hasSize(2);
        assertThat(partnerBRows).hasSize(1);
        assertThat(events.count(EventQuery.builder().partnerId(PARTNER_A).fromCreatedAt(from).build()))
                .isEqualTo(2);
    }

    @Test
    void query_filtersByEventType() {
        events.insertIfAbsent(PARTNER_A, UUID.randomUUID(), EventType.ORDER_CREATED, null, mapper.createObjectNode(), ACTOR);
        events.insertIfAbsent(PARTNER_A, UUID.randomUUID(), EventType.ORDER_CANCELLED, null, mapper.createObjectNode(), ACTOR);

        Instant from = Instant.now().minus(Duration.ofHours(1));
        var created = events.query(EventQuery.builder()
                .partnerId(PARTNER_A)
                .eventType(EventType.ORDER_CREATED)
                .fromCreatedAt(from)
                .build());

        assertThat(created).hasSize(1);
        assertThat(created.get(0).eventType()).isEqualTo(EventType.ORDER_CREATED);
    }

    @Test
    void query_filtersByBusinessRef() {
        events.insertIfAbsent(PARTNER_A, UUID.randomUUID(), EventType.ORDER_CREATED, "ORD-77", mapper.createObjectNode(), ACTOR);
        events.insertIfAbsent(PARTNER_A, UUID.randomUUID(), EventType.SHIPMENT_UPDATED, "ORD-77", mapper.createObjectNode(), ACTOR);
        events.insertIfAbsent(PARTNER_A, UUID.randomUUID(), EventType.ORDER_CREATED, "ORD-99", mapper.createObjectNode(), ACTOR);

        Instant from = Instant.now().minus(Duration.ofHours(1));
        var rows = events.query(EventQuery.builder()
                .partnerId(PARTNER_A)
                .businessRef("ORD-77")
                .fromCreatedAt(from)
                .build());

        assertThat(rows).hasSize(2);
    }

    @Test
    void query_paginationWorks() {
        for (int i = 0; i < 25; i++) {
            events.insertIfAbsent(PARTNER_A, UUID.randomUUID(),
                    EventType.ORDER_CREATED, "ORD-" + i, mapper.createObjectNode(), ACTOR);
        }

        Instant from = Instant.now().minus(Duration.ofHours(1));
        var page0 = events.query(EventQuery.builder().partnerId(PARTNER_A).page(0).size(10).fromCreatedAt(from).build());
        var page1 = events.query(EventQuery.builder().partnerId(PARTNER_A).page(1).size(10).fromCreatedAt(from).build());
        var page2 = events.query(EventQuery.builder().partnerId(PARTNER_A).page(2).size(10).fromCreatedAt(from).build());

        assertThat(page0).hasSize(10);
        assertThat(page1).hasSize(10);
        assertThat(page2).hasSize(5);
        assertThat(events.count(EventQuery.builder().partnerId(PARTNER_A).fromCreatedAt(from).build()))
                .isEqualTo(25);
    }

    @Test
    void query_appliesProcessingOutcomeFilter() {
        UUID processedId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        events.insertIfAbsent(PARTNER_A, processedId, EventType.ORDER_CREATED, "P1", mapper.createObjectNode(), ACTOR);
        events.markPending(PARTNER_A, processedId, ACTOR);
        events.tryMarkProcessing(PARTNER_A, processedId, ACTOR);
        events.markProcessed(PARTNER_A, processedId, ACTOR);

        events.insertIfAbsent(PARTNER_A, failedId, EventType.ORDER_CREATED, "F1", mapper.createObjectNode(), ACTOR);
        events.markPending(PARTNER_A, failedId, ACTOR);
        events.tryMarkProcessing(PARTNER_A, failedId, ACTOR);
        events.markFailed(PARTNER_A, failedId, "boom", ACTOR);

        Instant from = Instant.now().minus(Duration.ofHours(1));
        var processedOnly = events.query(EventQuery.builder()
                .partnerId(PARTNER_A)
                .processingOutcome(EventStatus.PROCESSED)
                .fromCreatedAt(from)
                .build());
        var failedOnly = events.query(EventQuery.builder()
                .partnerId(PARTNER_A)
                .processingOutcome(EventStatus.FAILED)
                .fromCreatedAt(from)
                .build());

        assertThat(processedOnly).hasSize(1);
        assertThat(processedOnly.get(0).eventId()).isEqualTo(processedId);
        assertThat(failedOnly).hasSize(1);
        assertThat(failedOnly.get(0).eventId()).isEqualTo(failedId);
    }
}
