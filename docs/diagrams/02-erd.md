# Entity-Relationship Diagram

Application tables. pgmq's internal tables (`pgmq.q_*`, `pgmq.a_*`) are shown
separately because they're managed by the extension, not by the application.

```mermaid
erDiagram
    partners ||--o{ events : "submits"
    events ||--o{ event_audit_log : "transitions"
    events ||--o| event_outbox : "has unsent row"

    partners {
        TEXT        partner_id          PK
        TEXT        secret_hash         "SHA-256 hex of secret"
        TEXT        previous_secret_hash "for rotation"
        TIMESTAMPTZ previous_secret_expires_at
        BOOLEAN     active
        TIMESTAMPTZ created_at
    }

    events {
        BIGSERIAL   id                  PK_part
        TEXT        partner_id          FK
        UUID        event_id
        TEXT        event_type          "ORDER_CREATED, ..."
        TEXT        business_ref        "partner's order/shipment id"
        JSONB       payload
        TEXT        status              "RECEIVED, PENDING, PROCESSING, PROCESSED, FAILED"
        TEXT        error
        TIMESTAMPTZ created_at          PK_part_key
        TIMESTAMPTZ processed_at
    }

    event_audit_log {
        BIGSERIAL   id                  PK_part
        TEXT        partner_id
        UUID        event_id
        TEXT        from_status         "null on initial RECEIVED"
        TEXT        to_status
        TEXT        actor               "ingest, outbox-poller, worker:order-created..."
        TEXT        error               "captured on FAILED transitions"
        TIMESTAMPTZ occurred_at         PK_part_key
    }

    event_outbox {
        BIGSERIAL   id                  PK
        TEXT        partner_id
        UUID        event_id
        TEXT        queue_name
        JSONB       payload             "serialized PartnerEventMessage"
        INT         attempts
        TIMESTAMPTZ last_error_at
        TIMESTAMPTZ created_at
    }
```

## Partitioning

Two of these tables are partitioned. The `_part` suffix on primary keys
denotes that the partition key is part of the PK by Postgres requirement.

| Table | Partition key | Interval | Retention | Cleanup |
|---|---|---|---|---|
| `events` | `created_at` | 1 month | 12 months | `DROP PARTITION` (retention_keep_table=true → cold-tier ready) |
| `event_audit_log` | `occurred_at` | 1 month | 24 months | `DROP PARTITION` (retention_keep_table=true) |

`pg_partman_bgw` runs every 60 seconds and drops partitions older than the
retention window. `retention_keep_table=true` means partitions are detached
rather than destroyed — operations can `pg_dump` the detached partition and
ship to S3 before the actual table drop, supporting cold-tier archival.

## Constraints and indexes

### `events`

- **Primary key:** `(id, created_at)` — partition key must be part of the PK
  in native partitioning.
- **`UNIQUE (partner_id, event_id, created_at)`** — the idempotency anchor.
  Combined with `INSERT … ON CONFLICT DO NOTHING`, this means duplicate
  submissions within the same calendar month are inert at the database
  level. Cross-month duplicate retries are vanishingly rare given typical
  idempotency-key lifetimes.
- `CHECK (status IN ('RECEIVED','PENDING','PROCESSING','PROCESSED','FAILED'))`.
- `INDEX (partner_id, created_at DESC)` — partner query path; partition-prunable.
- `INDEX (event_type, status, created_at DESC)` — internal filter combinations.
- `INDEX (business_ref, created_at DESC) WHERE business_ref IS NOT NULL` —
  partial index for partner support lookups.
- `INDEX (status, created_at DESC)` — "all currently-pending" / "recently-failed" dashboards.

All indexes lead with the most-selective column and end with `created_at` so
the planner can use them with partition pruning.

### `event_audit_log`

- **Primary key:** `(id, occurred_at)`.
- `CHECK (to_status IN ('RECEIVED','PENDING','PROCESSING','PROCESSED','FAILED'))`.
- `INDEX (partner_id, event_id, occurred_at)` — per-event audit trail. The
  primary read pattern (`AuditLogger.historyFor`).
- `INDEX (to_status, occurred_at DESC)` — operational dashboards
  ("show all FAILED in the last hour").
- `INDEX (actor, occurred_at DESC)` — forensics
  ("what was the order-created worker doing yesterday").

The audit table is **append-only**. There is no UPDATE or DELETE API surface.
The only writer is `AuditLogger.transition`; the only deleter is
`pg_partman_bgw` dropping old partitions.

### `event_outbox`

- **Not partitioned.** Steady-state size is bounded by polling interval ×
  write rate (~250 rows at 1k events/sec, 250ms poll). Rows are deleted on
  successful send.
- `INDEX (id)` — the poller's `ORDER BY id LIMIT N FOR UPDATE SKIP LOCKED` query.

## pgmq tables (managed by the extension)

For each event type, pgmq creates two partitioned tables:

```
pgmq.q_events_order_created          — active queue (daily partitions, 4 day retention)
pgmq.a_events_order_created          — archive (DLQ destination)
```

Both are partitioned on `enqueued_at`/`archived_at` by `pg_partman`.

## Why `events.id` AND `event_id`

`id BIGSERIAL` is the database surrogate key — used internally for ordering
and joins. `event_id UUID` is partner-supplied (or server-generated) and is
the public identity used for idempotency. Different concerns, different
columns.

## Why no FK from `event_outbox` to `events`

The outbox is a write-and-forget integration table. A FK would force the
poller to read through to `events` for each row and create lock contention
with the API's inserts. The poller doesn't need referential integrity — it
just needs to forward the payload it already has.

## Why no FK from `event_audit_log` to `events`

The audit table outlives the events table by 12 months. A foreign key would
prevent dropping old events partitions while audit rows still reference
them. Logical association is by `(partner_id, event_id)`; integrity is
maintained by the application (`AuditLogger` is the only writer) rather
than by the database.
