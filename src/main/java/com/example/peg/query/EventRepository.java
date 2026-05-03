package com.example.peg.query;

import com.example.peg.audit.AuditLogger;
import com.example.peg.shared.EventRecord;
import com.example.peg.shared.EventStatus;
import com.example.peg.shared.EventType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes against the partitioned {@code events} table.
 *
 * <h2>Partitioning interaction</h2>
 * The table is partitioned monthly by {@code created_at}. Two consequences:
 *
 * <ul>
 *   <li><strong>Reads should include time bounds</strong> so the planner can prune
 *       partitions. {@link EventQuery} provides {@code from}/{@code to} and the
 *       {@link EventSpecifications} helper applies them. A query without
 *       bounds scans all active partitions — still bounded by retention, but
 *       slower than necessary.</li>
 *   <li><strong>State transitions don't carry {@code created_at}</strong> in the
 *       caller's signature, so an {@code UPDATE WHERE partner_id=? AND event_id=?}
 *       must consult the partitioned unique index. This is N point-index lookups
 *       where N = partition count (≤12 in steady state). Microsecond cost in
 *       practice; documented here so the choice isn't accidentally re-litigated.</li>
 * </ul>
 *
 * <h2>Audit integration</h2>
 * Every state-transition method writes an {@link AuditLogger} row inside the
 * caller's transaction. If the operational UPDATE rolls back, the audit row
 * rolls back too.
 *
 * <h2>Read/write split</h2>
 * {@code jdbc} carries every write and every read that participates in a
 * write transaction (notably the {@link #findById} idempotency probes inside
 * {@link #insertIfAbsent}). {@code readJdbc} is bound to the read replica
 * (or aliased to the primary when no replica is configured) and is only
 * acceptable for the cross-partner listing path: {@link #query} and
 * {@link #count}. Replication lag would corrupt idempotency if the writers
 * ever moved to it.
 */
@Repository
@RequiredArgsConstructor
public class EventRepository {

    private final JdbcTemplate jdbc;
    @Qualifier("readJdbc")
    private final JdbcTemplate readJdbc;
    private final ObjectMapper mapper;
    private final AuditLogger audit;

    private final RowMapper<EventRecord> rowMapper = (rs, i) -> new EventRecord(
            rs.getLong("id"),
            rs.getString("partner_id"),
            (UUID) rs.getObject("event_id"),
            EventType.valueOf(rs.getString("event_type")),
            rs.getString("business_ref"),
            readJson(rs.getObject("payload")),
            EventStatus.valueOf(rs.getString("status")),
            rs.getString("error"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("processed_at") == null ? null : rs.getTimestamp("processed_at").toInstant());

    /**
     * Insert with idempotency. If {@code (partner_id, event_id)} already exists
     * within the calendar month of the new row, the insert is a no-op and the
     * existing row is returned.
     *
     * <p>Writes a {@code RECEIVED} audit row on first insert.
     */
    public InsertResult insertIfAbsent(String partnerId, UUID eventId, EventType type,
                                       String businessRef, JsonNode payload,
                                       String actor) {
        // The unique constraint on `events` must include `created_at` (partition
        // key requirement on a partitioned table), so ON CONFLICT can't deduplicate
        // by (partner_id, event_id) alone. Look up first; treat ON CONFLICT as the
        // race-condition backstop for concurrent inserts within the same partition.
        Optional<EventRecord> existing = findById(partnerId, eventId);
        if (existing.isPresent()) {
            return new InsertResult(existing.get(), false);
        }

        List<EventRecord> inserted = jdbc.query(
                "INSERT INTO events (partner_id, event_id, event_type, business_ref, payload, status) " +
                "VALUES (?, ?, ?, ?, ?::jsonb, 'RECEIVED') " +
                "ON CONFLICT (partner_id, event_id, created_at) DO NOTHING " +
                "RETURNING *",
                rowMapper, partnerId, eventId, type.name(), businessRef, writeJson(payload));

        if (!inserted.isEmpty()) {
            audit.transition(partnerId, eventId, null, EventStatus.RECEIVED, actor, null);
            return new InsertResult(inserted.getFirst(), true);
        }

        // Race within the same partition+microsecond: another tx inserted between
        // our findById and INSERT. Look up the winner.
        EventRecord row = findById(partnerId, eventId)
                .orElseThrow(() -> new IllegalStateException("event missing after insert conflict"));
        return new InsertResult(row, false);
    }

    /** Lookup by partner+event_id. Scans partitioned unique index. */
    public Optional<EventRecord> findById(String partnerId, UUID eventId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM events WHERE partner_id = ? AND event_id = ?",
                    rowMapper, partnerId, eventId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Lookup with a {@code created_at} hint that lets the planner prune to a
     * single partition. Use this when the caller already knows when the event
     * was created (e.g. from a prior {@link #findById} or from the message
     * payload).
     */
    public Optional<EventRecord> findById(String partnerId, UUID eventId, Instant createdAtHint) {
        if (createdAtHint == null) return findById(partnerId, eventId);
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM events " +
                    "WHERE partner_id = ? AND event_id = ? " +
                    "  AND created_at >= ? - INTERVAL '1 hour' " +
                    "  AND created_at <= ? + INTERVAL '1 hour'",
                    rowMapper,
                    partnerId, eventId,
                    Timestamp.from(createdAtHint), Timestamp.from(createdAtHint)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Atomic transition RECEIVED → PENDING. Called by the outbox poller after
     * successful pgmq forward.
     */
    public void markPending(String partnerId, UUID eventId, String actor) {
        int rows = jdbc.update(
                "UPDATE events SET status = 'PENDING' " +
                "WHERE partner_id = ? AND event_id = ? AND status = 'RECEIVED'",
                partnerId, eventId);
        if (rows == 1) {
            audit.transition(partnerId, eventId, EventStatus.RECEIVED, EventStatus.PENDING, actor, null);
        }
    }

    /**
     * Atomic claim PENDING/PROCESSING → PROCESSING. Returns true if this caller
     * transitioned the row.
     *
     * <p>{@code PROCESSING → PROCESSING} is allowed for redelivery after a
     * worker crash (visibility timeout expired, prior tx rolled back).
     */
    public boolean tryMarkProcessing(String partnerId, UUID eventId, String actor) {
        int rows = jdbc.update(
                "UPDATE events SET status = 'PROCESSING' " +
                "WHERE partner_id = ? AND event_id = ? AND status IN ('PENDING', 'PROCESSING')",
                partnerId, eventId);
        if (rows == 1) {
            audit.transition(partnerId, eventId, EventStatus.PENDING, EventStatus.PROCESSING, actor, null);
        }
        return rows == 1;
    }

    public void markProcessed(String partnerId, UUID eventId, String actor) {
        int rows = jdbc.update(
                "UPDATE events SET status = 'PROCESSED', processed_at = NOW(), error = NULL " +
                "WHERE partner_id = ? AND event_id = ? AND status = 'PROCESSING'",
                partnerId, eventId);
        if (rows == 1) {
            audit.transition(partnerId, eventId, EventStatus.PROCESSING, EventStatus.PROCESSED, actor, null);
        }
    }

    public void markFailed(String partnerId, UUID eventId, String error, String actor) {
        String trimmed = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
        int rows = jdbc.update(
                "UPDATE events SET status = 'FAILED', processed_at = NOW(), error = ? " +
                "WHERE partner_id = ? AND event_id = ? AND status IN ('PROCESSING', 'PENDING')",
                trimmed, partnerId, eventId);
        if (rows == 1) {
            audit.transition(partnerId, eventId, EventStatus.PROCESSING, EventStatus.FAILED, actor, trimmed);
        }
    }

    public List<EventRecord> query(EventQuery q) {
        EventSpecifications.SqlFragment frag = EventSpecifications.compile(q);
        String sql = "SELECT * FROM events" + frag.whereClause()
                + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
        List<Object> args = new ArrayList<>(frag.args());
        args.add(q.getSize());
        args.add((long) q.getPage() * q.getSize());
        return readJdbc.query(sql, rowMapper, args.toArray());
    }

    public long count(EventQuery q) {
        EventSpecifications.SqlFragment frag = EventSpecifications.compile(q);
        String sql = "SELECT COUNT(*) FROM events" + frag.whereClause();
        Long n = readJdbc.queryForObject(sql, Long.class, frag.args().toArray());
        return n == null ? 0 : n;
    }

    private String writeJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node == null ? mapper.nullNode() : node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload serialization failed", e);
        }
    }

    private JsonNode readJson(Object pgo) {
        try {
            String s = pgo instanceof PGobject p ? p.getValue() : (pgo == null ? null : pgo.toString());
            return s == null ? mapper.nullNode() : mapper.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException("payload deserialization failed", e);
        }
    }

    public record InsertResult(EventRecord row, boolean wasInserted) {}
}
