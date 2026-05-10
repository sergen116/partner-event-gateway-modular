# 06-stage2-topology.md — Summary

K8s topology where each role is its own Deployment, autoscaled by KEDA on `pgmq.metrics().queue_length`.

## Topology
- **API Deployment** (`APP_RUNTIME_MODE=API`, replicas: 3).
- **5 consumer Deployments** (one per event type), e.g. `consumer-order-created` (min: 2, max: 10).
- **PgBouncer** transaction-mode in front of Postgres.
- **KEDA operator** uses postgres scaler to query `pgmq.metrics()` directly → drives HPA per consumer Deployment.

## Why PgBouncer
Per-pod Hikari pool small (6) for tx-mode compat. Total clients at peak: 32 pods × 6 = 192 → multiplexed to ~50 backend. Postgres never sees more than 50 active regardless of pod count.

## Why KEDA over native HPA
Natural metric (`pgmq.queue_length`) lives in Postgres. Native HPA needs metrics-API/Prometheus Adapter routing. KEDA postgres scaler queries Postgres directly. Fewer moving parts.

## What scales how
Each consumer has its own ScaledObject with thresholds tuned to event-type profile:
- High-volume types: `targetQueryValue: 500` (per-pod backlog before scaling).
- Latency-sensitive (e.g. order-cancelled): `targetQueryValue: 50` — scale aggressively.
A burst on OrderCreated scales `consumer-order-created` 2→10 without touching the other 4. **Cost goes where load goes.**

## Stage 1 sizing for 2K TPS (peak only)
Single `CONSUMER_ALL` pod, default 24 worker slots → ~400 msg/s. Aggressive tuning can absorb a **short** 2K-TPS peak, but every dimension of headroom collapses to one process. Tuning needed: total slots 24→104, batch-size 2× concurrency, busy-poll 20→5ms, `HIKARI_MAX` 40→120, outbox poller 50/250ms→200/100ms. Throughput math: `104 × (1/handler_lat)`. Holds at 2K only if p99 ≤ 50ms; at 100ms p99 → ~1040 msg/s.
**Why not recommended for sustained**: single failure domain, no surgical scaling, 120+ connection overhead without PgBouncer, single outbox poller bottleneck.

## Stage 2 sizing for 2K TPS (sustained)
| Deployment | Replicas | Concurrency | Batch | HIKARI_MAX | Effective |
|---|---|---|---|---|---|
| order-created | 5 | 24 | 48 | 30 | ~1100 |
| shipment-updated | 3 | 16 | 32 | 22 | ~440 |
| return-requested | 2 | 12 | 24 | 18 | ~220 |
| address-updated | 2 | 8 | 16 | 14 | ~150 |
| order-cancelled | 2 | 12 | 24 | 18 | ~220 |
| **Total** | **14** | | | | **~2130** |
+ 3 API pods × `HIKARI_MAX=12`. PgBouncer multiplexes ~350 clients → 50–80 backend. KEDA drives autoscaling = peak counts (steady-state runs near `min`).

## What to verify before committing
- Postgres write capacity (~12K writes/s @ 2K TPS). pgbench, WAL fsync limits.
- Real-handler p99 (Resilience4j retries push worst-case to ~1.5s).
- Outbox drain — currently 200 msg/s per pod, promote to `app.outbox.*`.

## When one event type still saturates
Per-event-type Deployments solve cross-type imbalance. They DON'T solve a single saturated queue (one heap, one B-tree, one autovacuum). Symptom: hot consumer pinned at max=10, queue depth still climbs, others idle.

**Lever**: shard *that one* queue into N children (`order_created_shard_0..15`), routing by `hash(partner_id) % N` at insert. Result: N heaps, N hot tails, writes distribute by 1/N. KEDA scales 16 ScaledObjects with same `targetQueryValue`. Surgical, not blanket. When pgmq sharding becomes operationally heavier than the alternative → migrate that one queue to Kafka.

**Why partner_id key**: uncorrelated with time (all shards stay hot in parallel — time-based rotates one at a time = same hot-tail problem). Per-partner ordering preserved (every event for partner X lands on same shard).

## When the single outbox saturates (hot-queue outbox split)
Once one event type dominates volume, single-table outbox shows three failure modes (mirroring queue-side):
1. **Lock contention** on tail page across all API pods.
2. **Single-table throughput bottleneck** — one heap/B-tree/autovacuum.
3. **DELETE I/O + bloat** from delete-on-send churn.

**Stage 2 evolution (not deployed)**: split *that one* type into its own table (`event_outbox_order_created`); leave the cool four sharing `event_outbox`. Each `OutboxPoller` bound to one table → one heap, independent autovacuum. If per-type heap also saturates → same `hash(partner_id) % N` shard key applies. Symmetric with queue-side fix.

## What doesn't change between stages
App code, Docker image, schema, migrations, metrics, API contract. Only diff: which `APP_RUNTIME_MODE` each pod gets.

## Stage 2 is documented, not deployed
Per case spec ("explain how the solution could support" scaling). Helm/KEDA/PgBouncer manifests not in repo — sketched as natural extension.
