package com.example.peg.query;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Reads/writes for the {@code event_outbox} table.
 *
 * <p>The outbox is a transient buffer: rows live only between API ack and
 * successful pgmq forward. On successful send, the row is deleted. The
 * {@code events} table is the long-term record, not this one.
 *
 * <p>Steady-state size is bounded by polling interval × write rate. At 1k
 * events/sec and a 250ms poll interval, the table averages ~250 rows. The
 * table is intentionally not partitioned.
 */
@Repository
@RequiredArgsConstructor
public class OutboxRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<OutboxRow> ROW = (rs, i) -> new OutboxRow(
            rs.getLong("id"),
            rs.getString("partner_id"),
            (UUID) rs.getObject("event_id"),
            rs.getString("queue_name"),
            rs.getString("payload"),
            rs.getInt("attempts"));

    public void insert(String partnerId, UUID eventId, String queueName, String payloadJson) {
        jdbc.update(
                "INSERT INTO event_outbox (partner_id, event_id, queue_name, payload) " +
                "VALUES (?, ?, ?, ?::jsonb)",
                partnerId, eventId, queueName, payloadJson);
    }

    /**
     * Locks and returns up to {@code limit} rows for forwarding. SKIP LOCKED
     * means multiple poller instances grab disjoint batches without
     * coordinating.
     */
    public List<OutboxRow> claimBatch(int limit) {
        return jdbc.query(
                "SELECT id, partner_id, event_id, queue_name, payload::text, attempts " +
                "FROM event_outbox " +
                "ORDER BY id " +
                "LIMIT ? " +
                "FOR UPDATE SKIP LOCKED",
                ROW, limit);
    }

    /**
     * Delete the row after successful pgmq forward. The events table holds
     * the durable record; the outbox row was just delivery machinery.
     */
    public void deleteSent(long id) {
        jdbc.update("DELETE FROM event_outbox WHERE id = ?", id);
    }

    /**
     * Increment attempts and timestamp the failure. Row stays in the outbox
     * for retry on the next poll.
     */
    public void recordFailure(long id) {
        jdbc.update(
                "UPDATE event_outbox SET attempts = attempts + 1, last_error_at = NOW() WHERE id = ?",
                id);
    }

    public record OutboxRow(long id, String partnerId, UUID eventId,
                            String queueName, String payload, int attempts) {}
}
