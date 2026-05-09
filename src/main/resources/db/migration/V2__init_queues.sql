-- =============================================================
-- V2__init_queues.sql
-- One partitioned pgmq queue per event type.
-- Daily partitions, 4-day retention.
-- =============================================================

-- These tables are intentionally logged (WAL-backed), not unlogged.
-- pgmq.create_unlogged would be faster on write but Postgres TRUNCATEs
-- unlogged tables on crash recovery, which would break at-least-once
-- delivery (the outbox row is deleted on pgmq.send) and wipe the DLQ
-- (pgmq.a_*) exactly when forensics matter. See docs/ARCHITECTURE.md
-- "Why logged pgmq tables".

-- pg_partman background worker drops partitions older than retention.
-- It must be configured in postgresql.conf:
--   shared_preload_libraries = 'pg_partman_bgw'
--   pg_partman_bgw.dbname    = 'events'
-- Without it, you must call partman.run_maintenance() periodically.

DO $$
BEGIN
    PERFORM pgmq.create_partitioned('events_order_created',     '1 day', '4 days');
    PERFORM pgmq.create_partitioned('events_shipment_updated',  '1 day', '4 days');
    PERFORM pgmq.create_partitioned('events_return_requested',  '1 day', '4 days');
    PERFORM pgmq.create_partitioned('events_address_updated',   '1 day', '4 days');
    PERFORM pgmq.create_partitioned('events_order_cancelled',   '1 day', '4 days');
EXCEPTION WHEN OTHERS THEN
    -- create_partitioned is idempotent in newer pgmq versions; older ones throw.
    -- Swallow "already exists" so re-running migrations is safe.
    IF SQLERRM NOT LIKE '%already exists%' THEN
        RAISE;
    END IF;
END $$;
