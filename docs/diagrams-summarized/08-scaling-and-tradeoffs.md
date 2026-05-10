# 07-scaling-and-tradeoffs.md — Summary

Diagnose first, then pick cheapest lever that targets it.

## Outbox vs direct pgmq.send — scorecard

| Concern | Direct `pgmq.send` | Outbox |
|---|---|---|
| Atomicity (same DB) | = | = |
| Durability after API ack | = | = |
| API p99 latency | − correlated with queue load | + predictable |
| Lock contention with consumers | − hot pgmq pages | + separate table |
| Behaviour during pgmq incident | − cascading 500s | + partners unaffected |
| Coupling to queue tech | − in ingest path | + isolated to poller |
| Code complexity | + ~5 lines | − ~100 lines |
| Latency from accept to queue | + immediate | − +1 poll interval |

**Atomicity is equivalent in same-DB.** What differs: 200 OK contract — direct = "queue accepted it"; outbox = "we have it, you can stop worrying".

**Incident shape (60s pgmq hiccup at 500 req/s = 30k events)**:
- Direct: ALL partners 5xx, 30k requests fail, 0 durable, manual partner retries.
- Outbox: NONE 5xx, 0 fail, 30k durable, poller drains automatically on recovery.

## 5-layer scaling diagnostic — name the symptom first

| Layer | Real cause | Try first | Don't reach for |
|---|---|---|---|
| **L1** consumer throughput (queue grows, workers busy) | concurrency × replicas < arrival, or slow handler | raise Semaphore, add replicas, profile handler | hourly partitioning (renames, doesn't divide) |
| **L2** pickup latency (depth fine, per-msg latency high) | poll interval (idle queues only — busy interval covers loaded) | long-polling `pgmq.read_with_poll`; tune busy-poll | more pods (latency is per-msg) |
| **L3** producer/API (API p99 spikes during bursts, 503s) | API doing too much, or outbox poller behind | outbox (wired), API replicas, per-partner rate limit | any consumer-side lever |
| **L4** database tier (PG pegged regardless of consumers) | underlying instance, hostile workload | PgBouncer, read replica, index audit, vacuum tuning | more consumer pods (makes L4 worse) |
| **L5** single hot event type (one queue saturates, others idle) | one pgmq queue = one heap/B-tree | shard saturated queue by `hash(partner_id) % N`; eventually swap that one to Kafka | spreading across all 5 — only one is the problem |

## 20-lever inventory (cost-ordered, condensed)

**Wired**: #1 per-worker Semaphore concurrency, #3 per-queue batch size, #3a work-conserving poll, #9 `HIKARI_MAX`, #11 read replica (opt-in via `REPLICA_DB_URL`).

**Partial**: #2 consumer replicas (mode wired, no manifests), #7 API replicas (mode wired, no HPA), #10 KEDA (gauges published, manifests not deployed), #12 PgBouncer (JDBC compat set, no service), #17 cold-tier archive (`retention_keep_table=true`, no S3 ship script).

**Not wired**: #4 long-polling `pgmq.read_with_poll`, #5 faster outbox config (currently constants in `OutboxPoller`), #8 per-partner rate limit, #13 covering indexes (per-query), #14 vacuum tuning on hot pgmq tables, #15 bigger Postgres, #16 dedicated Postgres for pgmq, #17a hot-type outbox split, #18 hot-queue sharding (`hash(partner_id) % N`), #19 hottest queue → Kafka, #20 shard operational DB.

**Rule**: never reach for #18 when #1 isn't tried.

## Partitioning vs sharding — easy to confuse

|  | Time partitioning (daily) | Sharding (`hash(partner_id) % N`) |
|---|---|---|
| Routing key | `created_at` (monotonic) | `hash(partner_id)` (uncorrelated with time) |
| Children receiving writes NOW | **1** | **all N** |
| Effect on hot-tail contention | none — moves it | scales by **1/N** |
| Solves | retention via DROP, vacuum-free archives, partition pruning | hot-tail B-tree contention, per-tenant blast radius, per-partner ordering |

> **Time partitioning splits the *history* of writes; sharding splits the *concurrent stream* of writes. Contention is a property of concurrency, not history.**

If proposed fix is "smaller partitions": ask whether concurrent writes are spread across more children or still concentrated on the latest one. Concentrated → contention point unmoved.

## Why `partner_id` is the right shard key (when L5 hits)
- **Random** → breaks per-tenant ordering.
- **Time-based** → rotates one shard at a time = same hot-tail problem.
- **`hash(partner_id) % N`** → distributes evenly AND keeps every event for partner X on same shard → one consumer processes that stream in order.

Surgical, not blanket. If pgmq sharding becomes operationally heavier than alternative → that one queue migrates to Kafka (lever #19).
