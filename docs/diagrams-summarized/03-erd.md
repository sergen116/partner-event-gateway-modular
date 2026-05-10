# 02-erd.md — Summary

4 application tables. pgmq tables are managed by the extension (separate).

## Tables
- **`partners`**: `partner_id` PK, `secret_hash` (SHA-256 hex), `previous_secret_hash` + expiry (rotation), `active`.
- **`events`**: `id BIGSERIAL`, `partner_id`, `event_id UUID`, `event_type`, `business_ref`, `payload JSONB`, `status`, `error`, `created_at`, `processed_at`. **PK `(id, created_at)`** because Postgres requires partition key in PK.
- **`event_audit_log`**: append-only. `(from_status, to_status, actor, error, occurred_at)`. **PK `(id, occurred_at)`**.
- **`event_outbox`**: `id`, `partner_id`, `event_id`, `queue_name`, `payload`, `attempts`, `last_error_at`, `created_at`. Not partitioned, delete-on-send.

## Partitioning
| Table | Key | Interval | Retention |
|---|---|---|---|
| events | created_at | 1 mo | 12 mo |
| event_audit_log | occurred_at | 1 mo | 24 mo |
`pg_partman_bgw` runs every 60s, drops or detaches (`retention_keep_table=true`) old partitions. Detach enables `pg_dump`-to-S3 cold tier before destruction.

## Key constraints/indexes on `events`
- **`UNIQUE (partner_id, event_id, created_at)`** — idempotency anchor. Combined with `INSERT … ON CONFLICT DO NOTHING`, dupes within calendar month are inert at DB level.
- `CHECK (status IN ('RECEIVED','PENDING','PROCESSING','PROCESSED','FAILED'))`.
- Indexes lead with selective col, end with `created_at` for partition pruning: `(partner_id, created_at DESC)`, `(event_type, status, created_at DESC)`, `(business_ref, created_at DESC) WHERE business_ref NOT NULL` (partial), `(status, created_at DESC)`.

## Audit indexes
- `(partner_id, event_id, occurred_at)` — `historyFor` lookup.
- `(to_status, occurred_at DESC)` — failed-in-last-hour dashboards.
- `(actor, occurred_at DESC)` — forensics.
**Append-only**: only writer is `AuditLogger.transition`; only deleter is `pg_partman_bgw`.

## Why `events.id` AND `event_id`
`id BIGSERIAL` = DB surrogate for ordering/joins. `event_id UUID` = partner-supplied (or server-gen) public identity for idempotency. Different concerns.

## Why no FK from `event_outbox` → `events`
Outbox is write-and-forget. FK = read-through to events on every row + lock contention with API inserts. Poller doesn't need referential integrity.

## Why no FK from `event_audit_log` → `events`
Audit outlives events by 12 mo. FK would prevent dropping old events partitions. Logical association by `(partner_id, event_id)`; integrity in app (single writer).
