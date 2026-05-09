# Architecture

> System architecture, module boundaries, and how the design addresses each
> non-functional concern from the case spec. Companion to the
> [README](../README.md) (build/run, assumptions, time spent) and the
> [diagrams](diagrams/) folder (visuals + scaling rationale).

## 1. Overview

The Partner Event Gateway is a multi-tenant ingest platform for operational events
from external commerce / logistics partners. Partners authenticate with HMAC-SHA256,
submit events over HTTPS, and the platform durably accepts, asynchronously processes,
audits, and exposes those events for query.

Five properties from the case spec shape the design:

1. **Per-tenant isolation** — partners must not see or affect each other's events.
2. **At-least-once delivery with idempotency** — accepted events must not be lost; duplicates must not double-process.
3. **Independent scaling per event type** — different types have different processing profiles.
4. **Auditability** — every state transition is captured in an immutable, queryable log.
5. **Production-ready operational surface** — observability, graceful shutdown, retries, DLQ, partition lifecycle.

The implementation runs in two topologies from the same codebase:

- **Stage 1 (default, single process)** — `APP_RUNTIME_MODE=CONSUMER_ALL`, all 5
  consumers in one JVM alongside the API. This is what runs locally and what the
  submission ships configured to use.
- **Stage 2 (per-queue Deployments)** — same image, one Deployment per role driven
  by `APP_RUNTIME_MODE`. Each consumer queue scales independently (e.g. via KEDA's
  postgres scaler against `pgmq.metrics().queue_length`). See
  [`diagrams/06-stage2-topology.md`](diagrams/06-stage2-topology.md).

The Stage 1 → 2 transition is configuration-only — no code changes — by design.

## 2. Modular monolith structure

The case spec leaves "modular monolith vs microservice approach" as an open
decision. We pick modular monolith. Packages are organized by **feature module**,
not by technical layer. Each module documents its dependencies in
`package-info.java`; the dependency graph is acyclic.

```
com.example.peg
├── shared/      cross-cutting types (EventType, EventStatus, EventRecord, ...)
├── partner/     identity + HMAC verification + caching request wrapper
├── ingest/      API → events row + outbox row (one transaction)
├── delivery/    outbox poller + pgmq workers + event processor
├── query/       event query API + Specifications-based filter framework
├── audit/       event_audit_log writer + reader
└── platform/    runtime modes, scheduling, configuration, observability
```

**Dependency direction:**

```
ingest ─┐
delivery┼→ shared, audit, platform
query ──┘    └──→ shared, platform
partner ────→ shared, platform
```

`ingest`, `delivery`, and `query` all share the `events` table via
`query.EventRepository` — that cross-module repository is the only shared-state
boundary; every other module owns its data. The audit module is consumed by ingest,
delivery, and query (each writes audit rows) but consumes none of them.

This shape matters because Stage 2 deploys the modules differently. An API pod runs
`ingest` + `query` + `partner` + `audit` writes; a consumer pod runs `delivery` +
`audit` writes. No code change between deployments — only the runtime mode changes
which beans get instantiated.

## 3. Component breakdown

### `partner` module

- **`PartnerAuthFilter`** — `OncePerRequestFilter` registered for `/api/v1/events*`.
  Loads the partner from DB (Caffeine-cached, 60 s TTL), hands the canonical request
  bytes to `HmacVerifier`, sets the resolved `partner_id` as a request attribute on
  success.
- **`HmacVerifier`** — derives the HMAC key from `SHA-256(secret)` (raw bytes), so
  the raw secret never appears in storage or logs. Constant-time comparison via
  `MessageDigest.isEqual`. Anti-replay via timestamp window (±5 min). Secret rotation
  supported through `previous_secret_hash` with an expiry.
- **`CachingRequestWrapper`** — body is read once into a buffer so the filter and
  Spring's `@RequestBody` deserializer can both consume it.

### `ingest` module

- **`PartnerEventsController`** — REST endpoints for partner-facing submit and
  query. The `partner_id` filter on queries is enforced server-side from the auth
  context, not from request parameters.
- **`EventIngestService`** — single transaction containing both `events.insertIfAbsent`
  (with idempotency check) and `outbox.insert`. Returns whether this was a fresh
  insert or a duplicate.

### `delivery` module

- **`OutboxRepository` + `OutboxPoller`** — drain `event_outbox` into pgmq via
  `FOR UPDATE SKIP LOCKED`. Rows are deleted on successful send — see
  [ADR-006](#adr-006-outbox-delete-on-send).
- **`PgmqWorker`** (base) + 5 per-event-type subclasses. Each polls one pgmq queue,
  fans batch processing across virtual threads bounded by a Semaphore, enforces a
  batch deadline below the visibility timeout via
  `CompletableFuture.allOf().orTimeout(VT - 5s)`.
- **`EventProcessor`** — invoked per message. Two short transactions bracket the
  downstream call: tx 1 claims via `tryMarkProcessing` (silent skip if already
  terminal) and commits; the per-type handler runs outside any DB transaction;
  tx 2 marks PROCESSED. On exception (handler, downstream call, or finalize),
  the row is left committed in PROCESSING and pgmq's VT redelivers — the next
  worker reclaims via the `PROCESSING → PROCESSING` rule on `tryMarkProcessing`.
  Final-attempt FAILED + DLQ archive is the worker's decision via
  `read_ct >= maxAttempts`.
- **`DownstreamCallService`** — single seam between handlers and (mocked) downstream
  systems, wrapped with Resilience4j `@Retry` and `@CircuitBreaker`. See
  [ADR-009](#adr-009-circuit-breaker--retry-on-downstream-calls).

### `query` module

- **`EventRepository`** — reads/writes against the partitioned `events` table. All
  state-transition methods write an audit row inside the same transaction. Writes
  use the primary `JdbcTemplate`; `query()` and `count()` use a separate `readJdbc`
  bound to the read replica when configured (see [§ Read/write split](#readwrite-split)).
- **`EventQuery`** — extensible filter specification (partner, type, status, date
  range, business ref, processing outcome).
- **`EventSpecifications`** — composable filter compilation. Adding a filter is one
  line in the registry plus a field on `EventQuery`. See
  [ADR-007](#adr-007-specifications-without-jpa).
- **`InternalEventsController`** — cross-partner query endpoint (no auth, per case
  spec) with the same filter set plus an explicit `partnerId` parameter. Defaults
  the time window to last 90 days when neither `from` nor `to` is supplied so
  partition pruning bounds the scan.

### `audit` module

- **`AuditLogger`** — single fluent entry point: `auditLogger.transition(...)`.
  Called inside the caller's transaction so audit writes are atomic with the
  operational write.
- **`AuditRecord`** — read-side projection. The `event_audit_log` table is
  append-only — there is no update or delete API.

### `platform` module

- **`RuntimeProperties`** — drives the seven-mode topology switch.
- **`ConsumerProperties`** — per-queue concurrency and batch-size caps tuned by
  I/O profile, plus busy/idle poll intervals for the work-conserving loop.
- **`SecurityProperties`** — header names, timestamp skew, algorithm.
- **`SchedulingConfig`** — multi-threaded `TaskScheduler` for cron-style
  scheduled tasks (`OutboxPoller`, `QueueDepthExporter`). Workers no longer
  use it — each runs its own self-paced loop on a virtual thread.
- **`WorkerRegistrationConfig` + `WorkerScheduler`** — programmatic, mode-driven
  worker creation. Scheduler just calls `worker.start()` on each active worker;
  the loop lives inside the worker. Stage 2 transition is a config change here,
  not code.
- **`DataSourceConfig`** — primary writer pool + opt-in replica pool.
- **`QueueDepthExporter`** — `peg.queue.length{queue}` and
  `peg.queue.oldest_msg_age_seconds{queue}` gauges via Micrometer/Prometheus,
  driven from `pgmq.metrics()`.
- **`HikariPoolHealthIndicator`** — surfaces pool pressure as `DEGRADED` (not
  `DOWN` — that would fail readiness probes).

## 4. Storage and processing

### Storage tables

| Concern | Table | Partitioning | Retention |
|---|---|---|---|
| Partner credentials | `partners` | none | unbounded |
| Durable event record | `events` | monthly by `created_at` | 12 months |
| In-flight queue buffer | `pgmq.q_events_*` (5 queues) | daily by `enqueued_at` | 4 days |
| Reliable handoff API → queue | `event_outbox` | none (delete-on-send) | transient |
| Immutable transition log | `event_audit_log` | monthly by `occurred_at` | 24 months |
| DLQ / archived failures | `pgmq.a_events_*` | daily by `archived_at` | 4 days |

Both `events` and pgmq live in the same Postgres instance. That lets us put the
events insert and the outbox insert in a single ACID transaction — the property
the outbox pattern depends on.

### Why monthly partitions for `events` and `event_audit_log`

Daily partitions on these tables would create ~365 children per year per table —
~720 across both — without operational benefit. These tables aren't queues, they're
records. Monthly granularity gives:

- ~12 active partitions per table — readable in `\dt`
- Cheap retention via `DROP PARTITION` (O(1))
- Partition pruning when queries include `created_at` predicates
- Cold-tier archive workflow: `retention_keep_table=true` detaches old partitions
  instead of dropping them, so operations can `pg_dump` and ship to S3 before the
  actual destruction

### Why daily partitions for pgmq queues

Different shape, different choice. Queues are write-heavy at the head, read
constantly, and rotated quickly. Daily granularity matches the operational rhythm:
a queue partition holds 1 day of in-flight messages, and 4-day retention covers a
long weekend.

### Processing flow

1. Partner POSTs HMAC-signed event.
2. `PartnerAuthFilter` verifies and resolves partner ID.
3. `EventIngestService` (one transaction):
   - INSERT events row (`status=RECEIVED`)
   - INSERT outbox row (skipped if duplicate idempotency key)
   - INSERT audit row (`null → RECEIVED`)
4. Returns 200 to partner with `status=RECEIVED, duplicate=false`.
5. `OutboxPoller` (every 250 ms, API pods only):
   - Claims a batch with `FOR UPDATE SKIP LOCKED`
   - For each row: `pgmq.send`, delete outbox row, transition events to PENDING,
     write audit row (`RECEIVED → PENDING`)
6. `PgmqWorker`:
   - Reads batch from pgmq with VT = 30 s
   - Fans out across virtual threads bounded by Semaphore
   - Each task: `tryMarkProcessing` (audit `PENDING → PROCESSING`), run handler,
     `markProcessed` (audit `PROCESSING → PROCESSED`), `pgmq.delete`
   - On final-attempt failure: `pgmq.archive`, `markFailed` (audit
     `PROCESSING → FAILED`)

Visualised: [`diagrams/03-ingest-sequence.md`](diagrams/03-ingest-sequence.md),
[`diagrams/04-consume-sequence.md`](diagrams/04-consume-sequence.md),
[`diagrams/05-state-machine.md`](diagrams/05-state-machine.md).

## 5. How the design addresses each non-functional concern

### Security

- HMAC-SHA256 with per-partner secrets; raw secrets never stored (only
  `SHA-256(secret)`).
- Anti-replay via signed timestamp, ±5 min window.
- Constant-time signature comparison.
- Secret rotation supported (previous secret valid until `previous_secret_expires_at`).
- Authentication filter only on `/api/v1/events*`. Observability and internal
  endpoints are not exposed publicly in production (path-based separation).

### Tenant isolation

- `partner_id` is part of every event row's identity.
- Partner queries enforce `partner_id` from the auth context, not from request
  parameters.
- DB unique constraint on `(partner_id, event_id, created_at)` is per-partner per-month.
- Two partners can reuse the same UUID without collision.

### Idempotency

- `(partner_id, event_id, created_at)` unique constraint blocks duplicate rows
  within a calendar month.
- `Idempotency-Key` header lets partners drive the event ID; without it, server
  generates one.
- Repeated submissions return 200 with `duplicate=true` and the original event's
  current status.
- Worker-side: `tryMarkProcessing` checks the row state — already-terminal rows
  are silently skipped, even if pgmq redelivers.

### Reliability

- **Transactional outbox**: events insert and outbox insert commit atomically.
  Crash before pgmq.send → outbox poller picks up on next run.
- **pgmq visibility timeout**: a worker crash mid-processing leaves the row
  in PROCESSING (the claim transaction has committed; the finalize one never
  ran). pgmq redelivers after VT and the next worker reclaims via the
  `PROCESSING → PROCESSING` rule on `tryMarkProcessing`.
- **DLQ**: messages exceeding `maxAttempts` (default 5) move to the pgmq archive
  table and the events row is marked FAILED.
- **Downstream resilience**: `DownstreamCallService.notify` is wrapped with
  Resilience4j `@Retry` + `@CircuitBreaker` so transient downstream blips are
  absorbed in-process and sustained outages trip a breaker instead of draining
  the worker pool. See [ADR-009](#adr-009-circuit-breaker--retry-on-downstream-calls).
- **Graceful shutdown**: each worker flips its `running` flag in `@PreDestroy`,
  then drains its virtual-thread executor up to `visibility-timeout` seconds —
  the loop exits within one sleep cycle, in-flight handlers complete naturally
  to protect the delete/archive step.

### Concurrency

- Multiple worker pods per queue: pgmq's `FOR UPDATE SKIP LOCKED` is the
  coordination mechanism. No application locks.
- Multiple outbox pollers (one per API pod): same `SKIP LOCKED` claim.
- Per-worker virtual threads bounded by Semaphore: VTs are cheap, DB connections
  expensive.
- Atomic state transitions: every transition is one UPDATE with a status filter,
  not read-then-write.

### Auditability

- Every state transition writes an immutable row to `event_audit_log`.
- Audit writes are atomic with the operational UPDATE — same transaction.
- 24-month retention outlasts the 12-month operational retention, so compliance
  lookups remain available after the events row is gone.
- `actor` column attributes each transition: `"ingest"`, `"outbox-poller"`,
  `"worker:order-created"`, etc.

### Availability and performance

- Stateless API + stateless workers — both horizontally scalable.
- Per-event-type queues let high-volume types (e.g. `OrderCreated`) scale
  independently of low-volume types (`OrderCancelled`).
- Same Docker image runs in 7 runtime modes — no per-role builds.
- Read-replica for the query path is wired opt-in via `REPLICA_DB_URL`; unset →
  reads share the primary pool. See [§ Read/write split](#readwrite-split).
- Daily partitioning + 4-day retention on pgmq queues means cleanup is
  `DROP PARTITION`, not `DELETE` — no autovacuum bloat on hot tables.
- Monthly partitioning with 12 / 24-month retention on operational / audit tables
  means cold-tier archival is `ALTER TABLE … DETACH PARTITION` followed by
  `pg_dump` to S3.
- Both query controllers default the time window to last 90 days when not
  explicitly bounded, so unfiltered queries don't scan all 12 months.
- For the full ladder of scaling levers (config-tunable today through to Kafka
  migration / DB sharding), with current implementation status per lever, see
  [`diagrams/07-scaling-and-tradeoffs.md`](diagrams/07-scaling-and-tradeoffs.md).

### Capacity and scaling to 2K TPS at peak

Per-worker throughput is `concurrency / handler_latency`. With the
work-conserving loop (full batch ⇒ sleep `busy-poll-interval-ms = 20`; partial
or empty ⇒ sleep `poll-interval-ms = 500`) the busy interval is the only
overhead under load.

**Single `CONSUMER_ALL` pod ceiling** (24 total slots across 5 queues, 50 ms
handlers): ~400 msg/s. **2K TPS is a multi-pod target.**

Sizing — per-queue split runtime, one queue per pod, 100 ms p99 handler:

| Queue | Pods | `concurrency` | `batch-size` | `HIKARI_MAX` | Effective msg/s |
|---|---|---|---|---|---|
| `events_order_created` | 5 | 24 | 48 | 30 | ~1100 |
| `events_shipment_updated` | 3 | 16 | 32 | 22 | ~440 |
| `events_return_requested` | 2 | 12 | 24 | 18 | ~220 |
| `events_address_updated` | 2 | 8 | 16 | 14 | ~150 |
| `events_order_cancelled` | 2 | 12 | 24 | 18 | ~220 |
| **Total** | **14** | | | | **~2130 msg/s** |

What this assumes — and what to verify before committing:

- **Handler p99 ≤ 100 ms.** Resilience4j retries (3 × 200 ms × ×2 backoff) push
  worst-case latency to ~1.5 s on transient downstream failures, which collapses
  effective concurrency. Load-test `DownstreamCallService` against the real
  downstream, not the stub.
- **Postgres sustains ~12K writes/sec** (each event = 3–5 writes plus pgmq
  delete; ingest adds outbox + `pgmq.send`). Run `pgbench -j 8 -c 32 -T 60`;
  WAL fsync is the limiter.
- **Outbox poller keeps up.** Currently hardcoded `BATCH_SIZE=50`,
  `POLL_INTERVAL=250 ms` ⇒ 200 msg/s drain per API pod. Promote to
  `app.outbox.*` (lever #5) and bump to `batch=200, interval=100ms` for ~2K
  msg/s drain on a single API pod. Already flagged in
  [`07-scaling-and-tradeoffs.md`](diagrams/07-scaling-and-tradeoffs.md).
- **PgBouncer in front of Postgres.** 14 consumer + 3 API pods × Hikari each
  ≈ 350+ client connections. PgBouncer transaction-mode multiplexes onto a
  small backend pool (50–80) — without it, Postgres connection ceiling is hit
  before throughput.
- **KEDA on `pgmq.queue_length`** drives autoscaling per queue Deployment, so
  these pod counts are *peak* values not steady-state. See
  [`06-stage2-topology.md`](diagrams/06-stage2-topology.md#stage-2-sizing-for-2k-tps).

If traffic is heavily skewed (e.g. 80% `OrderCreated`), shift pods toward the
hot queue rather than scaling everything uniformly. If a single queue still
saturates at 5+ pods (per-heap B-tree contention), shard it by
`hash(partner_id) % N` — lever #18.

### Connection budget

The Hikari pool default is `HIKARI_MAX=40` (override per-pod). The worst-case
in-flight demand sits in `CONSUMER_ALL` mode, which is the configured default for
local / single-pod deploys:

| Holder | Worst-case connections |
|---|---|
| Worker semaphores (8+6+4+2+4) | 24 |
| API request handlers (typical peak) | 5–10 |
| OutboxPoller drain transaction | 1 |
| QueueDepthExporter scrape | 1 |
| **Total** | **31–35** |

40 leaves ~5 connections of headroom for transient slow-downstream events that pin
a transaction. Split-role pods (`API`, `CONSUMER_<TYPE>`) sit far below this — a
single per-type consumer pod tops out at its worker concurrency (≤ 8), so
`HIKARI_MAX=10` is a reasonable per-role override.

Runtime observation: Hikari Micrometer metrics are exposed at
`/actuator/prometheus` (`hikaricp_connections_pending`,
`hikaricp_connections_timeout_total`, etc.). `HikariPoolHealthIndicator` flips
`/actuator/health/hikariPool` to `DEGRADED` (not `DOWN` — that would fail
readiness probes) whenever `threadsAwaitingConnection > 0` on either pool.

### Read/write split

Cross-partner and internal event queries (`EventRepository.query()` and `count()`,
hit by `InternalEventsController`) route to a read replica when `REPLICA_DB_URL` is
set. Writes — and any read inside a write transaction — always go to the primary.
If `REPLICA_DB_URL` is unset, the read template falls back to the primary pool, so
local dev and CI work unchanged on a single Postgres. Wiring lives in
`platform/DataSourceConfig.java`.

### Maintainability

- Adding a new event type: enum value + queue migration + worker subclass +
  concurrency config entry. Five small files.
- Adding a new query filter: one field on `EventQuery` + one entry in
  `EventSpecifications.SPECS`. Two lines.
- Adding a new runtime mode: one enum value + one switch arm in `RuntimeProperties`
  + one Deployment manifest.
- Querying the audit history of any event: `AuditLogger.historyFor(...)`.

## 6. Observability

All three pillars — **logs, metrics, traces** — ship from one Spring Boot process,
correlated by a shared `trace_id`. Endpoints (`management.endpoints`):
`/actuator/health`, `/actuator/prometheus`, `/actuator/metrics`,
`/actuator/circuitbreakers`, `/actuator/retries`. In production these stay off the
partner-facing port — `PartnerAuthFilter` only guards `/api/v1/events*`, so
`/actuator/**` must not be routed from public LBs.

### Logs — SLF4J / Logback ([`logback-spring.xml`](../src/main/resources/logback-spring.xml))

Two profiles: default human-readable console pattern (local / tests), and `json`
profile with `LogstashEncoder` for stdout JSON in prod
(`SPRING_PROFILES_ACTIVE=json`). MDC fields populated at boundaries:

- `trace_id` / `span_id` automatically by Micrometer Tracing
- `partner_id` by `PartnerAuthFilter`
- `event_id` by `PartnerEventsController`
- `queue` / `msg_id` / `event_type` / `partner_id` / `event_id` re-set by
  `PgmqWorker` after payload deserialization

Async logs carry the same identifiers as the originating HTTP request. In prod,
ship stdout via the cluster log agent (Promtail → Loki, Fluent-Bit → ELK, etc.);
`trace_id` is the join key against traces.

### Metrics — Micrometer / Prometheus

Scraped at `/actuator/prometheus`. Common tags `application` and
`role=${APP_RUNTIME_MODE}` are applied globally so per-role pods produce distinct
time series. Built-in: JVM, `http_server_requests_*`, `hikaricp_connections_*`,
Logback events, Resilience4j breaker / retry. App metrics (`peg.*` family):

| Metric | Type | Source | Tells you |
|---|---|---|---|
| `peg.queue.length{queue}` | gauge | `QueueDepthExporter` (API pods only — single source of truth) | Backlog per queue, KEDA input |
| `peg.queue.oldest_msg_age_seconds{queue}` | gauge | `QueueDepthExporter` | Stuck-batch detector |
| `peg.consumer.processed\|failed\|dlq{queue}` | counter | `PgmqWorker` | Throughput, retry rate, paging signal |
| `peg.consumer.duration{queue}` | timer | `PgmqWorker` | Per-message latency histogram |
| `peg.consumer.concurrency.available{queue}` | gauge | `PgmqWorker` | Free Semaphore permits (saturation) |
| `peg.partner_cache.size\|hit_ratio` | gauge | `PartnerCacheConfig` | Auth path cache health |

Local: `curl /actuator/prometheus` is enough. Prod: add a `ServiceMonitor` per
pod. Alert seeds: `oldest_msg_age > 60s` (workers behind), any `dlq` increment,
breaker `OPEN`, `hikaricp_connections_pending > 0` sustained, 5xx rate > 0.

### Traces — Micrometer Tracing → OpenTelemetry → OTLP/HTTP

W3C propagation (`traceparent` / `tracestate`). **Always-on context, opt-in export:**
spans are always created and `trace_id` / `span_id` always flow into MDC, but the
OTLP exporter is only registered when `MANAGEMENT_OTLP_TRACING_ENDPOINT` is set —
so logs stay correlatable locally with no collector running, and there's no
`Connection refused` noise. Sampling: `management.tracing.sampling.probability`
(env `TRACING_SAMPLING`, default `0.1`).

The async boundary is bridged by `TraceContextCarrier`: the HTTP span's W3C headers
are inlined into the `PartnerEventMessage` JSON, then `PgmqWorker` extracts them and
opens a `CONSUMER`-kind span as child of the producer context. One trace covers
`HTTP POST /events → outbox → pgmq → worker → DownstreamCallService`.

Local visualization (optional):

```bash
docker run --rm -p 16686:16686 -p 4318:4318 jaegertracing/all-in-one:latest
MANAGEMENT_OTLP_TRACING_ENDPOINT=http://localhost:4318/v1/traces \
TRACING_SAMPLING=1.0 mvn spring-boot:run
```

Prod: point the env var at an in-cluster OpenTelemetry Collector
(`http://otel-collector.observability:4318/v1/traces`); the collector fans out
to Tempo / Jaeger / Honeycomb / etc.

### Health and correlation

`/actuator/health` aggregates Spring Boot's built-ins (DB ping, disk, Flyway), the
Resilience4j breaker indicator, and `HikariPoolHealthIndicator` — which returns
`DEGRADED` (not `DOWN`, see [§ Connection budget](#connection-budget)) when callers
queue for a connection so the pod stays in service while pool pressure is visible.
Pivots: logs by `partner_id` / `event_id` → `trace_id` → trace UI for end-to-end
timing; queue gauges + `peg.consumer.duration` for worker throughput; breaker /
DLQ counters for downstream issues.

## 7. Architecture decision records (short form)

### ADR-001: HMAC key derivation

**Decision:** Store SHA-256 hex of the partner's secret. Both sides derive raw bytes
from the hash and use those as the HMAC key.

**Why:** The raw secret never appears in the DB, so a SQL-level data leak doesn't
disclose it. An attacker who compromises the DB can still impersonate the partner —
they have the HMAC key — but the secret-as-string is contained.

**Trade-off:** Not the same as a non-recoverable hash (BCrypt, Argon2). Real
production would put the secret in a KMS or sealed secret store and sign on the
gateway side. The current design is a documented case-study simplification —
strictly better than plaintext, worse than KMS-backed.

### ADR-002: Transactional outbox vs direct `pgmq.send`

**Decision:** API writes the events row + outbox row in one transaction; a separate
poller forwards outbox rows into pgmq.

**Atomicity is not the reason.** Because pgmq is just a set of Postgres tables in
the same database, `INSERT INTO events` and `pgmq.send` *can* share a single
transaction — both commit or both roll back. The classical "dual-write" problem
does not exist here. A direct `pgmq.send` inside the ingest transaction would be
atomic by Postgres ACID alone.

**Why outbox anyway.** Three specific properties direct send doesn't provide:

1. **Future-proofing for external brokers.** If pgmq is eventually swapped for
   Kafka, SQS, or any non-Postgres broker, the ingest path stays unchanged. Only
   the relay (`OutboxPoller`) gets rewritten — a single background component, not
   the API hot path.
2. **Shorter ingest transactions and bloat isolation.** Direct send keeps the API
   transaction open across `pgmq.send`'s extension code and pgmq table writes.
   Outbox keeps the API transaction down to two plain INSERTs (events + outbox)
   and moves the pgmq churn — including its eventual vacuum cost — into the
   poller's transaction, off the partner-facing path.
3. **Separation of concerns.** The ingest module never imports pgmq; it writes a
   queue-agnostic JSON payload to `event_outbox`. Only the delivery module knows
   about pgmq, which keeps modular boundaries clean and means ingest's tests don't
   need pgmq running.

**Trade-off:** ~250 ms median forwarding latency between API ack and queue arrival
(one poll interval). Partner sees 200 OK as soon as the row is durably accepted;
queue delivery is eventually consistent. Acceptable for an event ingest gateway.

For the at-a-glance scorecard and the incident-shape comparison see
[`diagrams/07-scaling-and-tradeoffs.md`](diagrams/07-scaling-and-tradeoffs.md).

### ADR-003: Per-event-type queues

**Decision:** One pgmq queue per event type, partitioned daily with 4-day retention.

**Why:** Different event types have different processing profiles. One shared queue
forces shared scaling and head-of-line blocking; per-type queues enable Stage 2's
independent scaling.

**Trade-off:** 5× the operational surface (5 queues, 5 metrics dashboards, 5 worker
classes). In Stage 1 mode this is negligible.

### ADR-004: Virtual threads + Semaphore

**Decision:** Each consumer worker fans batch processing across virtual threads
bounded by a per-worker Semaphore.

**Why:** Message processing is I/O-bound. Virtual threads park on I/O instead of
blocking carriers. The Semaphore caps logical concurrency at the DB-connection
budget.

**Trade-off:** Requires Java 21+ and Hikari 5.1.0+ (for non-pinning compatibility).
Both bundled with Spring Boot 3.2+.

### ADR-005: Same image, 7 runtime modes

**Decision:** One Docker image. `APP_RUNTIME_MODE` env var picks API,
all-consumers, or a single-type consumer.

**Why:** Operationally simpler than per-role builds. Stage 2 deploys the same
image into per-role Deployments.

**Trade-off:** Image is slightly larger than each role strictly needs. Negligible —
JRE + Spring Boot dwarfs the application code.

### ADR-006: Outbox delete-on-send

**Decision:** Delete the outbox row immediately on successful pgmq forward, rather
than setting a `sent_at` timestamp and sweeping later.

**Why:** The events table is the audit source of truth — `audit.historyFor`
returns the canonical timeline of every transition including the
`RECEIVED → PENDING` moment that maps to "outbox forwarded successfully". Keeping
the outbox row adds nothing; the table either stays small forever (delete-on-send)
or grows unboundedly without a sweeper.

**Trade-off:** The pgmq message ID isn't kept anywhere on the application side.
Operations who want to query pgmq directly via the message ID must trace it from
pgmq's own metadata, not the events table. Acceptable — operations rarely need
this in practice.

### ADR-007: Specifications without JPA

**Decision:** The persistence layer is JdbcTemplate + raw SQL throughout — no JPA,
no Hibernate, no Spring Data JPA. Dynamic event filtering is implemented in
`EventSpecifications` as a registry of small functions that compile into SQL
fragments and bound parameters.

**Why no JPA:**

- **pgmq is JDBC-native.** Core operations (`pgmq.send`, `pgmq.read`,
  `pgmq.archive`) are SQL function calls; the outbox poller and workers use
  `SELECT … FOR UPDATE SKIP LOCKED`. JPA can't express these idiomatically — every
  pgmq touchpoint would drop to `@Query(nativeQuery=true)` regardless. Adopting JPA
  would produce a hybrid stack where one stack already does the job.
- **Few tables, no object graph.** Five tables with deliberately no FKs between
  audit, outbox, and events. JPA's value — transparent navigation of `@OneToMany` /
  `@ManyToOne` — has nothing to navigate here.
- **One dependency, one mental model.** Adding JPA means two transaction managers
  (or a chained one), two result-mapping styles, plus Hibernate's runtime overhead
  (first-level cache, dirty checking, lazy proxies) that buys nothing on a
  high-throughput ingest path with no entity graph.
- **Already Postgres-locked.** pgmq is a Postgres extension, so the system is
  committed to Postgres. JPA's main portability promise has nothing to deliver
  here. Postgres-specific SQL (`FOR UPDATE SKIP LOCKED`, partial indexes, `JSONB`,
  partitioning DDL, `pgmq.*` functions) is used freely with no vendor-neutrality
  cost.

**Why hand-rolled Specifications:** The case spec calls for an extensible filter
API. We need the *pattern* — composable optional predicates — not the JPA
implementation of it. `EventSpecifications` is a five-line registry where adding a
filter is one line plus one `EventQuery` field. SQL stays one read away, which
matters for keeping queries partition-prunable on the monthly-partitioned `events`
table.

**Trade-off:** Developers who reach for `JpaSpecificationExecutor` by reflex have
to read the module to recognise the pattern. Mitigated by the registry being five
lines and the design being documented here.

### ADR-008: Monthly partitioning for events and audit

**Decision:** `events` and `event_audit_log` partitioned monthly. pgmq queues
partitioned daily.

**Why:** Different shape, different cadence. The events table is a record that
lives for months; daily partitions there create catalog noise without operational
benefit. Monthly partitions align with the natural operational rhythm: a single
partition is the right granularity to detach for cold storage. pgmq queues are
write-heavy at the head and rotate quickly; daily matches their churn.

**Trade-off:** Within-month idempotency is guaranteed by the unique constraint, but
cross-month duplicate retries (same UUID more than 30 days apart) are not — a
vanishingly rare scenario in practice given that idempotency keys are per-event
and not long-lived.

### ADR-009: Circuit breaker + retry on downstream calls

**Decision:** `DownstreamCallService.notify` is wrapped with Resilience4j `@Retry`
(3 × 200 ms, exponential backoff) and `@CircuitBreaker` (50% failure rate over a
20-call sliding window with `minimum-number-of-calls=10`, opens for 10 s, auto
half-open). Total budget stays well below pgmq's
`visibility-timeout-minus-5s` deadline.

**Why both.** Retry absorbs short-tailed transients (connection resets, 5xx)
in-process so blips don't pay the full pgmq round-trip. The breaker caps the long
tail: a sustained outage would otherwise turn the worker pool into a retry
generator. Together they compose — retry for blips, breaker for outages. 4xx is
excluded from `retry-exceptions` so caller bugs fail fast.

**Why the fallback rethrows.** `onFailure` logs and rethrows so the exception
propagates out of `EventProcessor.process` (the row is left committed in
PROCESSING by the claim transaction; the finalize transaction never runs) and
pgmq redelivery / DLQ keep applying their own outer budget. Two layers,
composed not stacked: Resilience4j is fast and in-process; pgmq is slow,
durable, and survives restarts.

**Trade-off:** Two retry layers can multiply attempts in the worst case; mitigated
by the tight in-process budget (3 × 200 ms ≪ 25 s) and the 4xx exclusion. Also
requires `DownstreamCallService` to stay a separate bean from `EventProcessor` —
Spring AOP proxies don't intercept self-invocation.

### ADR-010: PostgreSQL as the storage choice

**Decision:** PostgreSQL is the storage backend, and the codebase is intentionally
bound to it — JdbcTemplate against Postgres-native SQL, no ORM portability layer.
pgmq (a Postgres extension) is the in-flight queue.

**Why the lock-in is acceptable.** Two existing constraints already pin us to
Postgres: pgmq is a Postgres extension used through raw JDBC, and the schema is
five tables with no entity-graph traversal — JPA's portability layer has nothing
to deliver here (see [ADR-007](#adr-007-specifications-without-jpa)). Hand-rolled
SQL also keeps queries readable and partition-prunable on the monthly-partitioned
`events` table.

**Known trade-off.** With JPA, swapping to MySQL or another OLTP DB would be
roughly a configuration change. Without it, that swap is a non-trivial migration —
every query is rewritten and every Postgres-specific feature (partial indexes,
declarative partitioning, JSONB, `SKIP LOCKED`) needs an equivalent. We accept the
cost because those same features are exploited throughout the design, not
incidentally.

**Survives a future broker change.** When pgmq is eventually replaced by Kafka at
lever #19 in [`diagrams/07-scaling-and-tradeoffs.md`](diagrams/07-scaling-and-tradeoffs.md),
Postgres stays. Events, audit log, and partner credentials are OLTP workloads —
transactional, indexed, partitioned — where Postgres is best-in-class. The broker
swap is a delivery-layer change, not a storage one; the storage decision is
decoupled from the queue decision.
