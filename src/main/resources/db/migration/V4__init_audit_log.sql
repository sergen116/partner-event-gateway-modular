-- =============================================================
-- V4__init_audit_log.sql
-- Immutable state-transition log. One row per status change.
-- Partitioned monthly by occurred_at; 24-month retention.
-- =============================================================

-- The audit log is append-only. It is the canonical record of "what happened
-- to this event, when, and who did it". The events table reflects current
-- state; this table reflects history.
--
-- Why a separate table:
--   • events.status is mutable (it's the state machine's current value);
--     audit rows are immutable (one per transition, never updated/deleted)
--   • Audit retention can outlive event retention without forcing the events
--     table to keep terminal rows around
--   • Compliance/regulatory queries ("show every transition for partner X
--     in March") run on the audit log and don't touch the operational table
--
-- Why monthly partitions: matches the events table's partition cadence,
-- so retention/archival policies are applied uniformly.

CREATE TABLE IF NOT EXISTS event_audit_log (
    id              BIGSERIAL,
    partner_id      TEXT NOT NULL,
    event_id        UUID NOT NULL,
    from_status     TEXT,
    to_status       TEXT NOT NULL,
    actor           TEXT NOT NULL,
    error           TEXT,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (id, occurred_at),
    CONSTRAINT ck_audit_to_status CHECK (
        to_status IN ('RECEIVED', 'PENDING', 'PROCESSING', 'PROCESSED', 'FAILED')
    )
) PARTITION BY RANGE (occurred_at);

-- Per-event audit trail: "show every transition for this specific event."
CREATE INDEX IF NOT EXISTS ix_audit_partner_event_occurred
    ON event_audit_log (partner_id, event_id, occurred_at);

-- Operational dashboard: "show all FAILED transitions in the last hour."
CREATE INDEX IF NOT EXISTS ix_audit_to_status_occurred
    ON event_audit_log (to_status, occurred_at DESC);

-- Forensics: "what was the consumer worker for OrderCreated doing yesterday?"
CREATE INDEX IF NOT EXISTS ix_audit_actor_occurred
    ON event_audit_log (actor, occurred_at DESC);

-- Monthly partitioning, mirroring the events table.
SELECT partman.create_parent(
    p_parent_table => 'public.event_audit_log',
    p_control      => 'occurred_at',
    p_interval     => '1 month',
    p_premake      => 4
);

-- 24-month retention. Audit data outlives operational data — by the time the
-- events row's partition is dropped (12 months), there are still 12 more
-- months of audit history available for compliance lookups.
UPDATE partman.part_config
SET
    retention                = '24 months',
    retention_keep_table     = true,
    retention_keep_index     = false,
    infinite_time_partitions = true
WHERE parent_table = 'public.event_audit_log';
