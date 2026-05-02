package com.example.peg.audit;

import com.example.peg.shared.EventStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-side projection of an {@code event_audit_log} row.
 *
 * <p>This type is what callers see when they query the audit history of an
 * event. The audit table is append-only; there's no update or delete API.
 *
 * @param id           database surrogate key
 * @param partnerId    partner that submitted the event
 * @param eventId      partner-supplied or server-generated event identifier
 * @param fromStatus   prior state, or {@code null} on initial RECEIVED
 * @param toStatus     state the event transitioned into
 * @param actor        component that performed the transition
 *                     (e.g. {@code "ingest"}, {@code "outbox-poller"},
 *                     {@code "worker:order-created"})
 * @param error        captured at FAILED transitions; otherwise {@code null}
 * @param occurredAt   moment the transition was recorded
 */
public record AuditRecord(
        long id,
        String partnerId,
        UUID eventId,
        EventStatus fromStatus,
        EventStatus toStatus,
        String actor,
        String error,
        Instant occurredAt
) {}
