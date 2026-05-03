package com.example.peg.delivery;

import com.example.peg.PgmqPostgresInitializer;
import com.example.peg.shared.EventStatus;
import com.example.peg.shared.EventType;
import com.example.peg.query.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the outbox poller drains rows into pgmq and transitions the
 * corresponding event row from RECEIVED → PENDING. Uses real Postgres + pgmq;
 * downstream calls are mocked so this test stays focused on the outbox path.
 */
@SpringBootTest
@ContextConfiguration(initializers = PgmqPostgresInitializer.class)
class OutboxPollerIT {

    @Autowired OutboxPoller poller;
    @Autowired OutboxRepository outbox;
    @Autowired EventRepository events;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @MockBean DownstreamCallService downstream;

    private static final String PARTNER = "partner-acme";

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM event_audit_log");
        // Drain any pgmq messages from prior tests
        for (EventType t : EventType.values()) {
            try {
                jdbc.update("SELECT pgmq.purge_queue(?)", t.queueName());
            } catch (Exception ignored) {}
        }
    }

    @Test
    void drain_forwardsRowToPgmq_andTransitionsEventToPending() throws Exception {
        UUID eventId = UUID.randomUUID();

        // Insert event in RECEIVED + matching outbox row
        events.insertIfAbsent(PARTNER, eventId, EventType.ORDER_CREATED,
                "ORD-7", mapper.createObjectNode(), "ingest");
        outbox.insert(PARTNER, eventId, EventType.ORDER_CREATED.queueName(),
                mapper.writeValueAsString(mapper.createObjectNode()
                        .put("eventId", eventId.toString())
                        .put("partnerId", PARTNER)));

        int drained = poller.drain();

        assertThat(drained).isEqualTo(1);
        // Outbox row deleted on success
        Long remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_outbox WHERE partner_id = ? AND event_id = ?",
                Long.class, PARTNER, eventId);
        assertThat(remaining).isZero();
        // Events row transitioned RECEIVED → PENDING
        var row = events.findById(PARTNER, eventId).orElseThrow();
        assertThat(row.status()).isEqualTo(EventStatus.PENDING);
        // pgmq received the message
        Long depth = jdbc.queryForObject(
                "SELECT queue_length FROM pgmq.metrics(?)", Long.class,
                EventType.ORDER_CREATED.queueName());
        assertThat(depth).isEqualTo(1L);
    }

    @Test
    void drain_isNoOp_whenOutboxEmpty() {
        int drained = poller.drain();
        assertThat(drained).isZero();
    }

    @Test
    void drain_recordsFailure_andLeavesOutboxRow_whenSendFails() throws Exception {
        UUID eventId = UUID.randomUUID();
        events.insertIfAbsent(PARTNER, eventId, EventType.ORDER_CREATED,
                null, mapper.createObjectNode(), "ingest");
        // Bogus queue name → pgmq.send throws
        outbox.insert(PARTNER, eventId, "events_does_not_exist",
                mapper.writeValueAsString(mapper.createObjectNode()));

        poller.drain();

        // Row stayed put with attempts incremented
        Integer attempts = jdbc.queryForObject(
                "SELECT attempts FROM event_outbox WHERE partner_id = ? AND event_id = ?",
                Integer.class, PARTNER, eventId);
        assertThat(attempts).isEqualTo(1);
        // Event stayed RECEIVED — markPending only runs after successful send
        assertThat(events.findById(PARTNER, eventId).orElseThrow().status())
                .isEqualTo(EventStatus.RECEIVED);
    }
}
