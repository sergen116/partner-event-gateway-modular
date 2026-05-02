package com.example.peg.audit;

import com.example.peg.shared.EventStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * Single entry point for writing audit rows.
 *
 * <p>Callers invoke {@link #transition} from inside the same transaction that
 * performs the state change, so the audit insert is atomic with the
 * operational UPDATE. If the UPDATE rolls back, the audit row rolls back too.
 *
 * <p>Read methods are also exposed here so the audit module owns its data
 * fully. The {@code event_audit_log} table is append-only — there is no
 * update or delete API.
 */
@Component
@RequiredArgsConstructor
public class AuditLogger {

    private final JdbcTemplate jdbc;

    /**
     * Record a state transition. Called inside the caller's transaction so
     * audit writes are atomic with the operational write.
     *
     * @param partnerId   partner that submitted the event
     * @param eventId     partner-supplied or server-generated event identifier
     * @param fromStatus  prior state, or {@code null} for the initial RECEIVED
     * @param toStatus    state the event transitioned into
     * @param actor       component performing the transition (e.g.
     *                    {@code "ingest"}, {@code "outbox-poller"},
     *                    {@code "worker:order-created"}, {@code "processor"})
     * @param error       failure cause when transitioning to FAILED;
     *                    {@code null} otherwise
     */
    public void transition(String partnerId,
                           UUID eventId,
                           EventStatus fromStatus,
                           EventStatus toStatus,
                           String actor,
                           String error) {
        jdbc.update(
                "INSERT INTO event_audit_log " +
                "(partner_id, event_id, from_status, to_status, actor, error) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
                partnerId,
                eventId,
                fromStatus == null ? null : fromStatus.name(),
                toStatus.name(),
                actor,
                error);
    }

    /**
     * Audit history for a single event. Bounded by partition pruning when
     * {@code occurredAfter} is set — only partitions overlapping the time
     * window are scanned.
     *
     * @param partnerId      partner-scoped lookup
     * @param eventId        the event's identifier
     * @param occurredAfter  optional lower bound; pass the event's
     *                       {@code created_at} to prune to one or two
     *                       partitions
     */
    public List<AuditRecord> historyFor(String partnerId, UUID eventId,
                                        java.time.Instant occurredAfter) {
        if (occurredAfter == null) {
            return jdbc.query(
                    "SELECT id, partner_id, event_id, from_status, to_status, " +
                    "       actor, error, occurred_at " +
                    "FROM event_audit_log " +
                    "WHERE partner_id = ? AND event_id = ? " +
                    "ORDER BY occurred_at, id",
                    AUDIT_ROW_MAPPER,
                    partnerId, eventId);
        }
        return jdbc.query(
                "SELECT id, partner_id, event_id, from_status, to_status, " +
                "       actor, error, occurred_at " +
                "FROM event_audit_log " +
                "WHERE partner_id = ? AND event_id = ? AND occurred_at >= ? " +
                "ORDER BY occurred_at, id",
                AUDIT_ROW_MAPPER,
                partnerId, eventId, Timestamp.from(occurredAfter));
    }

    private static final org.springframework.jdbc.core.RowMapper<AuditRecord> AUDIT_ROW_MAPPER =
            (rs, i) -> new AuditRecord(
                    rs.getLong("id"),
                    rs.getString("partner_id"),
                    (UUID) rs.getObject("event_id"),
                    rs.getString("from_status") == null ? null : EventStatus.valueOf(rs.getString("from_status")),
                    EventStatus.valueOf(rs.getString("to_status")),
                    rs.getString("actor"),
                    rs.getString("error"),
                    rs.getTimestamp("occurred_at").toInstant());
}
