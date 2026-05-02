/**
 * Immutable audit log of event state transitions.
 *
 * <p>Every transition in the {@link com.example.peg.shared.EventStatus state
 * machine} writes a row here. The {@code event_audit_log} table is partitioned
 * monthly with 24-month retention — twice the events table's retention — so
 * compliance lookups remain available after the operational row is gone.
 *
 * <p>Public API surface:
 * <ul>
 *   <li>{@link AuditLogger} — single fluent entry point: {@code auditLogger.transition(...)}</li>
 *   <li>{@link AuditRecord} — DTO returned by query methods</li>
 * </ul>
 *
 * <p>{@code AuditLogger} is called inside the same transaction as the state
 * transition itself, so audit writes are atomic with the operational write.
 * If the operational UPDATE rolls back, the audit row rolls back too.
 *
 * <p>Depends on: {@code shared}, {@code platform}. No dependency on any
 * feature module — every module that records transitions calls into this
 * one.
 */
package com.example.peg.audit;
