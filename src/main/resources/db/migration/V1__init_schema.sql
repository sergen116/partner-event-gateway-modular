-- =============================================================
-- V1__init_schema.sql
-- Extensions, partners, events (partitioned), outbox.
-- The audit log lives in V4 to keep migrations small per concern.
-- =============================================================

CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;
CREATE SCHEMA IF NOT EXISTS partman;
CREATE EXTENSION IF NOT EXISTS pg_partman SCHEMA partman;

-- Partners --------------------------------------------------------
-- secret_hash holds SHA-256 hex of the partner's secret. The partner sends
-- HMAC-SHA256 with SHA-256(secret) as the key; the gateway verifies with the
-- same derived key. The raw secret never appears in storage or logs.
CREATE TABLE IF NOT EXISTS partners (
    partner_id                  TEXT PRIMARY KEY,
    secret_hash                 TEXT NOT NULL,
    previous_secret_hash        TEXT,
    previous_secret_expires_at  TIMESTAMPTZ,
    active                      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Events ----------------------------------------------------------
-- Partitioned monthly by created_at. The primary key must include the
-- partition key per Postgres native partitioning rules, hence (id, created_at).
-- The (partner_id, event_id) idempotency constraint is also a partitioned
-- unique index that includes created_at — so duplicate detection within the
-- same calendar month is enforced. Cross-month duplicate retries are vanishingly
-- rare in practice (idempotency keys are per-event, not long-lived) and are
-- caught operationally rather than by the constraint.
CREATE TABLE IF NOT EXISTS events (
    id              BIGSERIAL,
    partner_id      TEXT NOT NULL REFERENCES partners(partner_id),
    event_id        UUID NOT NULL,
    event_type      TEXT NOT NULL,
    business_ref    TEXT,
    payload         JSONB,
    status          TEXT NOT NULL DEFAULT 'RECEIVED',
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ,

    PRIMARY KEY (id, created_at),
    CONSTRAINT uq_events_partner_event UNIQUE (partner_id, event_id, created_at),
    CONSTRAINT ck_events_status CHECK (
        status IN ('RECEIVED', 'PENDING', 'PROCESSING', 'PROCESSED', 'FAILED')
    )
) PARTITION BY RANGE (created_at);

-- Indexes are created on the parent and propagate to all partitions.
-- Every index includes created_at last so the planner can use them with
-- partition pruning.

-- Partner query path: "show my events, newest first."
CREATE INDEX IF NOT EXISTS ix_events_partner_created
    ON events (partner_id, created_at DESC);

-- Internal cross-partner queries by type/status.
CREATE INDEX IF NOT EXISTS ix_events_type_status_created
    ON events (event_type, status, created_at DESC);

-- Business-reference lookup (partner support: "what happened to ORD-123").
CREATE INDEX IF NOT EXISTS ix_events_business_ref_created
    ON events (business_ref, created_at DESC) WHERE business_ref IS NOT NULL;

-- "What's currently pending / recently failed" — operations dashboards.
CREATE INDEX IF NOT EXISTS ix_events_status_created
    ON events (status, created_at DESC);

-- Set up monthly partitioning via pg_partman.
-- premake=4 creates 4 future months upfront; pg_partman_bgw maintains the
-- rolling window. start_partition is the current month; partman handles
-- creating later months automatically.
SELECT partman.create_parent(
    p_parent_table => 'public.events',
    p_control      => 'created_at',
    p_interval     => '1 month',
    p_premake      => 4
);

-- Retention: 12 months. Once a partition's range is entirely older than 12
-- months, pg_partman_bgw DETACHes it from `events` (O(1)) and leaves the
-- underlying table standalone — retention_keep_table=true preserves it for
-- cold-tier archival rather than dropping it outright. Operations pg_dump
-- the detached table to S3, then DROP TABLE manually. retention_keep_index
-- =false drops indexes on the detached table to save disk.
UPDATE partman.part_config
SET
    retention                = '12 months',
    retention_keep_table     = true,
    retention_keep_index     = false,
    infinite_time_partitions = true
WHERE parent_table = 'public.events';

-- Outbox ----------------------------------------------------------
-- Transient buffer between API ingest and pgmq.send. Rows are deleted
-- immediately on successful send (see OutboxPoller). Steady-state size is
-- bounded by polling interval × write rate (~250 rows at 1k/sec, 250ms poll).
-- Therefore: not partitioned. The events table is the long-term audit source.
CREATE TABLE IF NOT EXISTS event_outbox (
    id              BIGSERIAL PRIMARY KEY,
    partner_id      TEXT NOT NULL,
    event_id        UUID NOT NULL,
    queue_name      TEXT NOT NULL,
    payload         JSONB NOT NULL,
    attempts        INT NOT NULL DEFAULT 0,
    last_error_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Poller claims with: SELECT FROM event_outbox ORDER BY id LIMIT N FOR UPDATE SKIP LOCKED
-- All rows are unsent (sent rows are deleted), so a plain index works.
CREATE INDEX IF NOT EXISTS ix_outbox_id ON event_outbox (id);
