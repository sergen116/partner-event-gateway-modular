# Scaling levers — consolidated inventory

> Every documented scaling lever in one place, ladder-ordered, with what each one
> targets, what it does *not* fix, and its current status in this repo.

This doc is the **inventory**. For the diagnostic decision tree see
[`09-actual-scaling-bottlenecks.md`](09-actual-scaling-bottlenecks.md). For the
write-distribution proof of why partitioning is not sharding see
[`07-scaling-bottlenecks.md`](07-scaling-bottlenecks.md). For the Stage 2 deployment
shape that backs levers #2, #10, and #12 see
[`06-stage2-topology.md`](06-stage2-topology.md).

> **The single rule:** *Diagnose the bottleneck first, then pick the cheapest lever
> that targets it. Don't reach for #19 when #1 isn't tried.*

The four levers a reader is most likely to remember on their own — Kafka-over-pgmq,
per-queue Deployments, partner sharding, DB sharding — sit at the **expensive end**
of this ladder (#18–#20). Most teams never get past #1–#9.

## The lever inventory

Layer column refers to the bottleneck layers from
[`09-actual-scaling-bottlenecks.md`](09-actual-scaling-bottlenecks.md):

- **L1** consumer throughput · **L2** pickup latency · **L3** producer / API ·
  **L4** database tier · **L5** single hot event type

| # | Lever | Targets | Does NOT fix | Cost | Status in this repo |
|---|---|---|---|---|---|
| 1 | Raise per-worker `Semaphore` concurrency | L1 | L2, L5 | config | **wired** — `app.consumer.concurrency.<queue>` (`ConsumerProperties`, `application.yml:74`) |
| 2 | Add consumer replicas (Stage 2 Deployments) | L1 | L2, L5 | config + manifest | **partial** — `APP_RUNTIME_MODE` switch wired (`RuntimeProperties`, `WorkerRegistrationConfig`); k8s manifests not in repo |
| 3 | Increase `pgmq.read` batch size | L1 | L2 | config | **wired** — `app.consumer.batch-size` (`application.yml:71`) |
| 4 | Long-polling via `pgmq.read_with_poll` | L2 | L1 | small code change | **not wired** — `PgmqWorker.readBatch` (`PgmqWorker.java:151`) calls `pgmq.read`, not `pgmq.read_with_poll` |
| 5 | Faster outbox poll loop / bigger batch | L3 | L4 ceiling | small refactor | **not wired** — `BATCH_SIZE=50` and `POLL_INTERVAL=250ms` are hardcoded constants in `OutboxPoller.java:35-36`; would need extraction to `app.outbox.*` |
| 6 | Profile + optimize per-message handler | L1 | anything else | real work — biggest single win | **per-handler** — case-by-case, no shared lever |
| 7 | Add API replicas (HPA on CPU) | L3 | L1, L4 | config + manifest | **partial** — `APP_RUNTIME_MODE=API` wired; HPA manifest not in repo |
| 8 | Per-partner rate limit (token bucket → 429) | L3, blast-radius | anything else | small code change | **not wired** — would slot into `partner.PartnerAuthFilter` |
| 9 | Bump `HIKARI_MAX` | L3, L4 | L5 ceiling | config | **wired** — `HIKARI_MAX` env var (`application.yml:14`) |
| 10 | KEDA postgres scaler on `pgmq.metrics().queue_length` | L1 autoscale | L2, L4 | config + manifest | **partial** — `QueueDepthExporter` publishes Micrometer gauges; KEDA scaler manifest not in repo. See [`06-stage2-topology.md`](06-stage2-topology.md) |
| 11 | Read replica for query API path | L4 (query side) | ingest path | infra change | **wired** — opt-in via `REPLICA_DB_URL`; `query()`/`count()` route to `readJdbc` (`platform/DataSourceConfig.java`), writes always use primary, unset → fallback to primary pool |
| 12 | PgBouncer transaction-mode pooling | L4 | L5 ceiling | infra change | **partial** — JDBC settings are already PgBouncer-compatible (`prepareThreshold: 0`, `auto-commit: true` in `application.yml:18-20`); no PgBouncer service in `docker-compose.yml`. See [`06-stage2-topology.md`](06-stage2-topology.md) |
| 13 | Index audit + covering indexes (`EXPLAIN ANALYZE`) | L4 | L5 | real work | **per-query** — case-by-case |
| 14 | Vacuum tuning on hot pgmq tables | L4 | anything else | Postgres config | **not configured** — defaults only |
| 15 | Bigger Postgres instance | L4 | L5 (eventually) | $$$ | **infra** — N/A in repo |
| 16 | Dedicated Postgres for pgmq | L4, blast-radius | L5 | infra | **noted** in `09`; not deployed |
| 17 | Cold-tier archive of detached partitions to S3 | storage cost / catalog | throughput | scripted infra | **partial** — `retention_keep_table=true` is set on `events`, `event_audit_log`, and pgmq queues (`V1__init_schema.sql:91`, `V4__init_audit_log.sql:65`), so partitions detach instead of dropping; the S3 dump job itself is not implemented |
| 18 | Shard hot queue by `hash(partner_id) % N` | L5 | cross-type contention | real refactor | **not wired** — surgical, only when one type saturates |
| 19 | Migrate hot queue to Kafka | L5 | L1–L4 | significant work | **not wired** — last resort. ADR-002 / [`08-outbox-vs-direct-pgmq.md`](08-outbox-vs-direct-pgmq.md) documents why the outbox pattern keeps this swap cheap |
| 20 | Shard the operational DB | L4 ceiling beyond a single Postgres | anything cheaper | major refactor | **not wired** — only at huge scale |

## Quick-pick by symptom

Pair these with the diagnostic flowchart in
[`09-actual-scaling-bottlenecks.md`](09-actual-scaling-bottlenecks.md). Don't pick a
lever before naming the symptom.

| Symptom | Try first | Don't reach for |
|---|---|---|
| Queue depth grows unboundedly, workers always busy | #1, #3, #6 → then #2 / #10 | #18–#20 |
| Per-message latency too high (depth fine) | #4 — long-polling. Nothing else moves this. | More pods (#2) — pods don't help individuals |
| API p99 spikes during bursts | #5, #7, #8, #9 | Any consumer-side lever |
| Postgres pegged regardless of consumer count | #11, #12, #13, #14 → then #15 / #16 | #2 (more consumers makes DB load worse) |
| One queue saturates, the other four idle | #18 — shard the saturated queue only | Anything that spreads load across all 5 queues equally |

## Two anti-patterns this inventory exists to prevent

- **Hourly partitioning is not a throughput fix.** Concurrent writes still target
  the latest partition; finer time slicing renames where the contention lives, it
  doesn't divide it. See [`07-scaling-bottlenecks.md`](07-scaling-bottlenecks.md)
  for the write-distribution proof.
- **More consumer pods do not fix per-message latency.** Latency is a per-message
  property; only #4 (long-polling) moves it. See `09` Layer 2.

## Cross-reference

- **Diagnose:** [`09-actual-scaling-bottlenecks.md`](09-actual-scaling-bottlenecks.md)
- **Why partitioning isn't sharding:** [`07-scaling-bottlenecks.md`](07-scaling-bottlenecks.md)
- **Stage 2 topology** (PgBouncer + KEDA in context): [`06-stage2-topology.md`](06-stage2-topology.md)
- **Why outbox keeps the Kafka swap cheap (ADR-002):** [`../ARCHITECTURE.md`](../ARCHITECTURE.md), [`08-outbox-vs-direct-pgmq.md`](08-outbox-vs-direct-pgmq.md)
