# ARCHITECTURE.md — Summary

## Overview
Multi-tenant event gateway: partners → HMAC-SHA256 over HTTPS → durable accept → async process → audit → query. Two topologies from same image: **Stage 1** (`CONSUMER_ALL`, single JVM) and **Stage 2** (per-role Deployments via `APP_RUNTIME_MODE`). Stage 1→2 is config-only.

## Modular monolith — 7 modules
`shared` · `partner` (HMAC) · `ingest` (API → events+outbox) · `delivery` (poller+workers+processor — only module that imports pgmq) · `query` (filter framework, `EventRepository` + `OutboxRepository` — the cross-module write seams) · `audit` (immutable transitions) · `platform` (modes, scheduling, observability; also wires worker beans → imports `delivery`+`query`). Acyclic DAG over feature modules. `ingest` does NOT depend on `delivery` (`OutboxRepository` lives in `query`); `query` is the only direct importer of `audit` (other modules get audit writes transitively via `EventRepository`). Stage 2 deploys API pods (`ingest+query+partner+audit`) and consumer pods (`delivery+query+audit`).

## Storage tables
| Table | Partition | Retention |
|---|---|---|
| `partners` | none | unbounded |
| `events` | monthly by `created_at` | 12 mo |
| `event_audit_log` | monthly by `occurred_at` | 24 mo |
| `event_outbox` | none, delete-on-send | transient |
| `pgmq.q_events_*` (5) | daily | 4 days |
| `pgmq.a_events_*` (DLQ) | daily | 4 days |

**Why monthly for events/audit:** they're records, not queues — daily would create ~720 child tables/yr without benefit. Cheap retention via `DROP PARTITION`, partition pruning when queries filter on date, cold-tier archive via `retention_keep_table=true`.

**Why daily for pgmq:** queues rotate fast, write-heavy at head; daily matches operational rhythm.

**Why logged pgmq (`create_partitioned`, not `create_unlogged`):** unlogged tables are TRUNCATEd on crash recovery — combined with delete-on-send, the queue would be the only surviving copy of in-flight messages. Logged buys: crash-survivable in-flight, DLQ integrity, replication/PITR, viable `DROP PARTITION` retention. Costs: WAL fsync per send (~0.5–2 ms), higher WAL volume, pg_partman dependency.

## Processing flow (recap)
1. Partner POSTs HMAC-signed event.
2. `PartnerAuthFilter` verifies + resolves partner.
3. `EventIngestService` (1 tx): INSERT events (RECEIVED) + INSERT outbox + INSERT audit. Returns 200.
4. `OutboxPoller` every 250ms (API pods): claim batch via SKIP LOCKED → `pgmq.send` → DELETE outbox row → mark PENDING → audit.
5. `PgmqWorker`: read batch (VT=30s) → fan out across virtual threads bounded by Semaphore → `tryMarkProcessing` (tx 1) → handler runs OUTSIDE any tx → `markProcessed` (tx 2) → `pgmq.delete`.

## NFR coverage — talking points

> Ordered to mirror **Backend_Case.pdf § Non-Functional Expectations** verbatim (Security → Tenant Isolation → Reliability → Idempotency → Concurrency → Availability & Performance → Maintainability). Each subsection leads with the case ask (italicised quote), then mechanisms wired today, then known limits/trade-offs. **Auditability** closes — the case classifies it as **Functional Req 6**, not an NFR, but every architectural choice serves it so it lives here for one-stop interview recall.

### 1. Security
> *Case: only authorized partners can submit events; requests should be protected appropriately; secrets/credentials should be handled securely.*

- **Authorization** — HMAC-SHA256 over canonical bytes (`partnerId\ntimestamp\nMETHOD\npath\nbody`); field order/separators/uppercase method must match exactly
- **Stored credential ≠ raw secret** — DB holds `SHA-256(secret)` (hex). Both sides arrive at the same HMAC key by forward computation; SHA-256 never reversed
- **Anti-replay** — timestamp **inside** signed message + ±5 min skew (HMAC alone does NOT provide replay protection)
- **Constant-time compare** — `MessageDigest.isEqual` (not `Arrays.equals` — short-circuits and leaks timing)
- **Secret rotation** — `previous_secret_hash` + `previous_secret_expires_at`; HMAC verify failure on cached partner → invalidate Caffeine entry → reload from DB → retry once. Absorbs rotation windows without operator intervention.
- **Surface scoping** — `PartnerAuthFilter` only on `/api/v1/events*`; observability + `/api/v1/internal/**` deliberately not exposed publicly (path-based separation, not auth-based)
- **Validation** — `@Valid` on DTO; `EventType` enum closes payload to the 5 supported types; `partner_id` always from auth context, never from body
- **TLS** — confidentiality is TLS's job (out of scope for the app code); HMAC is for authenticity + integrity
- **Trade-off (ADR-001)** — DB leak still exposes the HMAC key (attacker can forge to *this* gateway, not other systems where the partner reused secret). Not a non-recoverable hash like bcrypt/Argon2 — those are wrong tool for 256-bit machine secrets (10–100 ms latency, 0 added security against high-entropy input). Production refinement = KMS / sealed secret store.

### 2. Tenant Isolation
> *Case: one partner must not access or affect another partner's data.*

- **Identity in the row** — `partner_id` is part of every event row's identity; resolved from auth context and stamped server-side
- **Server-side enforcement** — partner filter on partner-facing reads is **always** from auth context, **never** from request parameters or body. A partner cannot spoof another's ID.
- **Per-tenant uniqueness** — `UNIQUE (partner_id, event_id, created_at)` lets two partners reuse UUIDs without collision
- **Internal endpoints are deliberate** — `/api/v1/internal/**` cross-partner access is no-auth per case spec but explicit-by-path; it's not a missing-filter accident
- **Limitation — explicit, not implicit** — every repo method binds `partner_id` directly. No `TenantContext` thread-local + Hibernate filter, no AOP interceptor, no `@TenantId`.
- **Reason (ADR-007)** — pgmq is JDBC-native (`pgmq.send`, `pgmq.read`, `FOR UPDATE SKIP LOCKED` — none idiomatic in JPA). Adding JPA *just* for tenant filtering on a 5-table no-FK schema = JDBC + JPA hybrid stack with no upside. Cross-partner surface stays small (one repo method per query) so the explicit binding is cheaper than a second persistence stack.

### 3. Reliability
> *Case: accepted events should not be lost because of application failure, retries, timeouts, or temporary internal failures.*

- **Outbox atomicity (ADR-002)** — events INSERT + outbox INSERT + audit INSERT in one tx. Crash before `pgmq.send` is recoverable; **partner sees 200 OK before pgmq is touched** — the durability contract is "we have it in Postgres", not "the queue accepted it"
- **pgmq visibility timeout** — worker crash mid-processing leaves row PROCESSING (claim tx committed; finalize tx never ran). pgmq redelivers after VT (30 s); next worker reclaims via `PROCESSING → PROCESSING` rule on `tryMarkProcessing`
- **Post-PROCESSED race** — SIGKILL between Tx 2 commit (PROCESSED) and `pgmq.delete` → row PROCESSED in DB but pgmq still has the message. On redelivery `tryMarkProcessing` matches no row → silent skip → `pgmq.delete`. One predicate handles claim + already-done.
- **DLQ (ADR-009)** — `read_ct >= maxAttempts (5)` → `pgmq.archive` + events row marked FAILED + audit row. Forensic data lives in BOTH places (`pgmq.a_events_*` + `events WHERE status='FAILED'`). Replay = re-INSERT outbox row from archive.
- **Downstream resilience (ADR-009)** — Resilience4j `@Retry` (3 × 200 ms exponential) + `@CircuitBreaker` (50% failure rate, 20-call sliding window, opens 10 s, auto half-open). Total in-process budget ≪ pgmq VT-5s deadline (25 s). 4xx excluded from retry-exceptions so caller bugs fail fast.
- **Two retry layers, composed not stacked** — Resilience4j (fast, in-process, transient blips) + pgmq redelivery (slow, durable, sustained outages). Fallback rethrows so pgmq's outer budget keeps applying. `DownstreamCallService` must stay a separate bean (Spring AOP doesn't intercept self-invocation).
- **Graceful shutdown** — workers flip `running` flag in `@PreDestroy`, drain VT executor up to VT seconds; in-flight handlers complete naturally to protect the delete/archive step. Loop exits within one sleep cycle.
- **Batch deadline below VT** — `CompletableFuture.allOf(...).orTimeout(VT-5s = 25s)`. On timeout we **don't** cancel in-flight tasks — letting them finish delete/archive is safer than risking double-delete.
- **Logged pgmq tables** — `pgmq.create_partitioned`, NOT `create_unlogged`. UNLOGGED tables are TRUNCATEd on crash recovery → combined with delete-on-send (ADR-006) the queue would be the **only surviving copy** of in-flight messages, which would also wipe DLQ archives (`pgmq.a_*` inherits the setting). Logged buys: crash-survivable in-flight, DLQ integrity, replication / PITR, viable `DROP PARTITION` retention. Cost: WAL fsync per send (~0.5–2 ms) — hidden behind the 250 ms poll cadence.
- **What CAN be lost** — Postgres-level data loss before WAL fsync. Single failure domain by design at Stage 1 (defended via managed PG + replicas + automated failover in prod; cross-region out of scope per case).

### 4. Idempotency
> *Case: duplicate submissions should be handled safely.*

- **DB-level constraint** — `UNIQUE (partner_id, event_id, created_at)` blocks duplicate rows within the calendar month
- **Header-driven event_id** — `Idempotency-Key` HTTP header drives `event_id`; absent → server generates UUID
- **Repeat behaviour** — repeated submission returns 200 + `duplicate=true` + the original event's **current** status (could be any of 5 states); no state transition triggered, no audit row written
- **SELECT-then-INSERT race** — two concurrent first-time submissions can both pass the dedup SELECT. `INSERT … ON CONFLICT DO NOTHING RETURNING *` is the backstop:
  - 1 row returned → INSERT audit (`null→RECEIVED`) + INSERT outbox
  - 0 rows (race lost) → re-SELECT the winner, skip outbox + audit (the winner already wrote them)
- **Why SELECT first instead of INSERT-first** — unique index includes `created_at` (partition key requirement) so `ON CONFLICT` alone can't dedupe by `(partner_id, event_id)` cleanly across partitions. SELECT on `(partner_id, event_id)` is the partition-aware lookup.
- **Limit (ADR-008)** — cross-month duplicate retries (>30 days apart) not blocked. Vanishingly rare given idempotency keys are short-lived.

### 5. Concurrency
> *Case: account for concurrent submissions, concurrent processing, race conditions, and consistent state transitions.*

- **One coordination primitive** — `FOR UPDATE SKIP LOCKED` (pgmq.queues + outbox table) —————— is the only between-process mechanism. No application locks anywhere (no Redis lock, no `synchronized`, no DB advisory locks).
- **Concurrent submissions** — two concurrent submissions with same Idempotency-Key —————— `INSERT … ON CONFLICT DO NOTHING RETURNING *` —————— Single Outbox record at final
- **Concurrent processing** — `(Virtual Threads + UPDATE SKIP LOCKED)` —————— multiple worker pods per pgmq queue use SKIP LOCKED inside `pgmq.read`. Each pod also fans out per-batch across virtual threads bounded by Semaphore.
- **Atomic state transitions** — every transition is one `UPDATE events SET status='X' WHERE status IN (...)` under PostgreSQL `READ COMMITTED`. Atomic UPDATE → only one of N concurrent updaters wins; losers see `rows=0` and silently skip. **No read-then-write anywhere.**
- **Audit atomic with op** — every `UPDATE` carries an `INSERT INTO event_audit_log` in the **same transaction**. No transition without audit; no audit without transition.
- **Per-worker VTs, Semaphore-bounded (ADR-004)** — VTs are cheap (parking on I/O is free); DB connections aren't. Semaphore caps logical concurrency at the per-pod Hikari budget. `Thread.sleep`, `Semaphore.acquire`, `CompletableFuture.join` all unmount cleanly on Java 21+ with pgjdbc 42.7.2+ and Hikari 5.1.0+ (both moved off `synchronized` to `ResourceLock`).
- **Ingest race walkthrough** — two concurrent first-time POSTs with same Idempotency-Key:
  1. Both pass `SELECT WHERE (partner_id, event_id)` (no row yet)
  2. Both attempt `INSERT … ON CONFLICT DO NOTHING RETURNING *`
  3. Winner gets 1 row, writes outbox + audit, commits
  4. Loser gets 0 rows, re-SELECTs winner's committed row, returns 200 `duplicate=false` for the winner's submission contract (one OK, one effectively wins; both clients see committed state)
- **Consistent State transitions are 5 + 1 self-loop** — RECEIVED → PENDING → PROCESSING → PROCESSED|FAILED, plus PROCESSING → PROCESSING for redelivery. Every transition logged.

### 6. Availability & Performance
> *Case: explain how the solution could support high availability, growing traffic, and efficient reads/writes.*

#### High availability
- **Stateless API + stateless workers** — pod loss never strands work; durable state is in Postgres only (queues are Postgres tables too)
- **Same image, 7 runtime modes (ADR-005)** — `APP_RUNTIME_MODE` selects role. Stage 1→2 is config-only; no per-role builds, no code change to split API and consumer pods
- **(Stage 1) Single Postgres failure domain ** — explicit limit. Production HA = managed Postgres (RDS / CloudSQL) + read replica + automated failover; cross-region DR out of scope per case spec.
- **(Stage 2) Broker-outage tolerance (ADR-002 + ADR-013)** — outbox decouples the partner-facing API from the broker. If pgmq (Stage 1) or Kafka (Stage 2 swap) goes down, ingest still returns 200: events INSERT + outbox INSERT commit to Postgres. Backlog accumulates in `event_outbox`; when the broker recovers, `OutboxPoller` drains it automatically. Partners see no failure, no manual replay needed.

#### Growing traffic
- **Per-event-type queues (ADR-003)** — high-volume types scale independently of low-volume types; no head-of-line blocking. Cost: 5× ops surface (5 queues, 5 worker classes, 5 dashboards) — negligible at Stage 1.
- **KEDA postgres scaler** — drives HPA per consumer Deployment from `pgmq.metrics().queue_length`. Per-queue `targetQueryValue` tunes profile (high-volume: 500 backlog/pod; latency-sensitive `order-cancelled`: 50). **Cost goes where load goes.**
- **PgBouncer configuration** PgBouncer multiplexes those onto a small backend pool (50) at the Postgres side  
- **Stage 2 sizing for 2K TPS sustained** — 14 consumer pods + 3 API pods → ~2130 msg/s (per-queue replica/concurrency table in `07-stage2-topology.md`). 3 API pods × `HIKARI_MAX=12` for ingest at the same TPS.
- **Stage 1 ceiling** — single `CONSUMER_ALL` pod ≈ 400 msg/s. Aggressive tuning (104 slots, `HIKARI_MAX=120`, outbox 200 msg / 100 ms) absorbs **short** 2K peaks but **every dimension of headroom collapses to one process** — single failure domain, no surgical scaling, 120+ connections without PgBouncer, single outbox poller. 2K TPS sustained = multi-pod target.
- **Assumptions to verify before committing 2K** — handler p99 ≤ 100 ms (Resilience4j retries push worst-case to ~1.5 s on transient failures, which collapses effective concurrency); Postgres ~12K writes/sec (`pgbench -j 8 -c 32 -T 60`; WAL fsync is the limiter); outbox poller promoted from constants to `app.outbox.*` (lever #5); PgBouncer multiplexes ~350 clients → 50–80 backends; KEDA manifests deployed.
- **5-layer scaling diagnostic** — name the symptom first. Pick cheapest lever that targets it; don't reach for #18 when #1 isn't tried (`08-scaling-and-tradeoffs.md`).
  - `L1 consumer throughput` - raise Semaphore, add replicas, profile handler
  - `L2 pickup latency` - long-polling via pgmq.read_with_poll for idle-queue pickup; tune busy-poll-interval-ms for hot queues
  - `L3 producer-API` 
    - outbox (already wired), API replicas, per-partner rate limit
    - Seperate outbox table
  - `L4 DB tier` - PgBouncer, read replica, index audit, vacuum tuning
  - `L5 single hot type` 
    - shard the saturated queue by hash(partner_id) % N; 
    - eventually swap that one to Kafka
    
- **L5 escape hatch** — single saturated queue ≠ blanket fix. 
  - Shard *that one* queue by `hash(partner_id) % N` (lever #18). Partner-id key preserves per-tenant ordering and keeps all N shards hot in parallel (random breaks ordering; time-based rotates one at a time = same hot-tail problem). 
  - `Kafka replacement` Eventually swap that one queue to Kafka (lever #19); outbox is the seam that keeps swap cheap (ADR-013).
- **Hot-outbox split — the producer-side mirror of L5** — same shape as queue-side sharding, applied one layer up. When one type dominates outbox writes (three symptoms: tail-page lock contention across pods, autovacuum I/O on the hot table competing with ingest, DELETE-on-send churn dominating the table), split *that one* type into its own table (`event_outbox_order_created`); leave the cool four sharing `event_outbox`. If the per-type heap saturates too, apply `hash(partner_id) % N` to the split table — same shard key as queue-side. Full rationale: ADR-012 § Stage 2 evolution + § Open design decisions › 3 *Why one outbox today (and when to split per type)*.
  - High lock contention.
  - Single-table throughput bottleneck.
  - DELETE I/O and table fragmentation. ------- (OR update outbox.status and make partition + retention INSTEAD OF --- DELETING)
- **Outbox scaling ladder** (cheap → expensive) when load grows past the current bound:
  - **More pollers first** (already wired) — add API pod replicas. Each pod runs its own `OutboxPoller`; `SELECT … FOR UPDATE SKIP LOCKED` coordinates concurrent claims across pods with **zero new code**. Contention stays row-level, not page-level.
  - **Per-type table for the hot type** (Stage 2 evolution, **not deployed**) — when *one* type's volume crosses the per-heap ceiling (three symptoms: tail-page lock contention across all API-pod pollers, autovacuum I/O on the hot table competing with ingest, DELETE-on-send churn dominating the table), split that one into its own table — `event_outbox_order_created` for example. Cool four keep sharing `event_outbox`. Symmetric with the queue-side L5 fix.
  - **`pgmq.send_batch()` unlocks for free at step 2** — every row in `event_outbox_order_created` targets the *same* pgmq queue, so the per-queue grouping complexity that motivates ADR-011's per-row default **disappears for that one table**. Drain loop becomes `pgmq.send_batch(queue, batch)` — one round-trip per batch instead of N. **This reveals ADR-011 + ADR-012 as a coupled design pair**: the per-row default is a *consequence* of the single-table default; splitting the table flips both at once.
  - **Shard the hot per-type table** by `hash(partner_id) % N` if the per-type heap *also* saturates — same shard key as queue-side. Same lever applied one layer up.

#### Efficient reads/writes
- **Partition pruning** — `events`, `event_audit_log` partitioned monthly by `created_at` / `occurred_at`; pgmq queues + DLQ partitioned daily. Date-bounded queries skip cold partitions. **Internal controllers default last 90 days** when neither `from` nor `to` set so unfiltered queries don't scan all 12 months.
- **Indexes lead with selectivity, end with `created_at`** — `(partner_id, created_at DESC)`, `(event_type, status, created_at DESC)`, `(business_ref, created_at DESC) WHERE business_ref NOT NULL` (partial), `(status, created_at DESC)`. Each query gets pruning + index-only-scannable order.
- **Audit indexes** — `(partner_id, event_id, occurred_at)` for `historyFor`, `(to_status, occurred_at DESC)` for failed-in-last-hour dashboards, `(actor, occurred_at DESC)` for forensics
- **No FKs across tables** — outbox→events would lock-contend with API inserts; audit→events would block dropping old events partitions (audit outlives events by 12 mo). Logical association in app, not DB.
- **`DROP PARTITION` not `DELETE`** — retention via `pg_partman_bgw` (60 s tick) is O(1). Daily pgmq + 4-day retention = no autovacuum bloat on hot queue tables. `retention_keep_table=true` detaches monthly events/audit partitions for cold-tier `pg_dump → S3` before destruction.
- **Work-conserving consume loop** — full batch → 20 ms busy-poll; partial/empty → 500 ms idle-poll. Effective busy cycle ≈ `batch_processing_time + 20 ms`, not 500 ms.
- **Per-worker batch sized to ~2× concurrency** — Semaphore fills in one read; mild pipelining; no head-of-line waste.
- **Read/write split (opt-in)** — `EventRepository.query()` / `count()` (cross-partner reads, hit by `InternalEventsController`) route to read replica when `REPLICA_DB_URL` set. Writes and reads-inside-write-tx always primary. Unset → reads share primary pool (local/CI unchanged).
- **Specifications-based filtering** — `EventQuery` + `EventSpecifications.SPECS` registry compiles filter combos to one SQL with bound params. No string concat, no SQL injection surface.

#### Connection budget
- `HIKARI_MAX=40` default. Worst-case `CONSUMER_ALL`: 24 (workers, summed Semaphore) + 5–10 (API) + 1 (outbox poller) + 1 (`QueueDepthExporter`) = 31–35 connections, ~5 headroom for slow-downstream pinning.
- Per-role split pods → `HIKARI_MAX=10` reasonable (a per-type consumer pod tops at its concurrency cap, ≤ 8).
- **PgBouncer transaction-mode ready** — JDBC settings already compatible (`prepareThreshold: 0`, `auto-commit: true`); service deployed only in Stage 2 (32 pods × 6 = 192 clients → ~50 backends).
- Hikari Micrometer metrics (`hikaricp_connections_pending`, `hikaricp_connections_timeout_total`) exposed at `/actuator/prometheus`; `HikariPoolHealthIndicator` flips to DEGRADED when `threadsAwaitingConnection > 0`.

### 7. Maintainability
> *Case: the system should be reasonably extensible for new event types, filters, and future evolution.*

- **Module separation** — `ingest` writes only to `events` + `event_outbox`; only `delivery` imports pgmq. New event type touches `delivery/` only — `ingest/` is unchanged. Also enables ADR-013 broker swap with no ingest impact.
- **Acyclic module DAG** — every module's deps documented in `package-info.java`. ArchUnit-style enforcement is one test away.
- **Stage 1 → Stage 2 = one env var** — same image, role chosen by `APP_RUNTIME_MODE`. App code, image, schema, migrations, metrics, API contract are identical between stages.
- **Add a new event type** — 5 small files: `EventType` enum value + Flyway migration creating `pgmq.q_events_<type>` + `PgmqWorker` subclass + `app.consumer.concurrency` entry + handler. `ingest/` unchanged.
- **Add a new query filter** — 2 lines: one field on `EventQuery` + one entry in `EventSpecifications.SPECS`. ADR-007.
- **Add a new runtime mode** — 1 enum value + 1 switch arm in `RuntimeProperties` + 1 Deployment manifest.
- **Audit history of any event** — `AuditLogger.historyFor(partnerId, eventId)`. One call.
- **Incident isolation (ADR-002)** — Kafka outage → outbox row stays put → partners unaffected. Recovery of processsing layer automatic when kafka returns.
- **Broker future-proofing (ADR-013)** — `event_outbox` schema has zero pgmq dependency. Kafka swap rewrites only `OutboxPoller.sendToPgmq` + `PgmqWorker`; ingest path and tests unchanged. The outbox's *table* is the durable seam — the *poller* is throwaway code.
- **SpecificationExecutor** — devs reaching for `JpaSpecificationExecutor` need to read the registry once. Mitigated by 5-line implementation + this doc.

### Auditability  *(case spec FR-6, not strictly an NFR — kept here because every architectural choice serves it)*
> *Case: trace what happened to an event — when received, which partner, type, current state, success/fail.*

- **Append-only `event_audit_log`** — every state transition writes one row `(from_status, to_status, actor, error, occurred_at)`. Only writer is `AuditLogger.transition`; only deleter is `pg_partman_bgw`. No update API, no delete API.
- **Audit atomic with op** — same transaction as the operational UPDATE → no transition without audit; no audit without transition.
- **24-month retention outlasts events' 12 months** — late compliance lookups still resolve after the events row is gone. Indexed by `(partner_id, event_id, occurred_at)` so `historyFor` is fast even after the events partition is dropped.
- **`actor` column attributes each transition** — `"ingest"`, `"outbox-poller"`, `"worker:order-created"`, etc. Forensic index `(actor, occurred_at DESC)`.
- **Trace correlation** — `trace_id` flows MDC → logs → `event_audit_log` not directly stored, but logs join on `partner_id` / `event_id` → trace UI for end-to-end timing.
- **Example transition counts** — successful event has 4 audit rows (`null→RECEIVED`, `RECEIVED→PENDING`, `PENDING→PROCESSING`, `PROCESSING→PROCESSED`); FAILED has 4 ending in `PROCESSING→FAILED` with reason in `error`; redelivered+recovered has 5+ (one or more `PROCESSING→PROCESSING`).

## Open design decisions — case spec coverage (interview)

> Section covers the **Backend_Case.pdf § Design Guidance — Open for Your Design Decisions** items not already addressed elsewhere in this doc (API surface, request/response, payload, ERD, and service boundaries are covered by the API contract, swagger, ERD section, and the module map above). Six items × three beats each: **choice → why → trade-off / what was rejected**. Closes with a 5-layer scaling-diagnostic ladder and the cross-cutting design principles that produced the choices.

### 1. Modular monolith vs microservice approach
> *Case: "modular monolith vs microservice approach"*

**Choice**: Modular monolith. 7 feature modules (`ingest, delivery, query, audit, partner, platform, shared`), acyclic compile-time deps documented in `package-info.java`, one Docker image, role chosen at runtime by `APP_RUNTIME_MODE` (ADR-005).

**Why monolith over microservices at case scale**:
- Shared DB anyway — events + outbox + audit + pgmq queues live in one Postgres. Splitting into services would *invent* a dual-write problem we don't have today.
- Microservices would add: service mesh / discovery, per-service CI/CD, distributed tracing across N hops, N copies of partner-auth machinery, N pgmq client integrations.
- Stage 1 → Stage 2 transition is config-only — same image, role-per-pod via env var. We get microservice-shaped horizontal scaling per role without paying the operational tax.

**Why modular (not monolithic) layout**:
- Feature modules, not technical layers. New event type touches `delivery/` only — `ingest/` stays untouched. Adding a filter is 2 lines (ADR-007 registry).
- Acyclic DAG is enforceable (ArchUnit-style is one test away).
- Stage 2 deploys subsets cleanly: API pod = `ingest+query+partner+audit-write`; consumer pod = `delivery+audit-write`. Zero code change.

**Trade-offs**:
- **Single-image deploy**: a critical bug in `delivery/` redeploys API pods too at Stage 1. Mitigated at Stage 2 — per-role Deployments roll affected role only.
- **Compile-time coupling on `shared/`**: a breaking primitive change ripples to all modules. Accepted because `shared/` holds rarely-changing types (`EventType`, `EventStatus`, `EventRecord`).

**When to split into services** (the trigger): one module owns its own DB (e.g. dedicated Postgres for pgmq, lever #16) **AND** its release cadence diverges **AND** the ops team can absorb the per-service overhead. Not before.

### 2. Storage choice
> *Case: "storage choice"*

**Choice**: PostgreSQL only — events, audit, outbox, partner credentials, **and** in-flight queues (pgmq extension). One DB, one connection pool, one backup posture (ADR-010).

**Why Postgres for OLTP data**:
- Events + audit + partners are transactional, indexed, partition-friendly — Postgres is best-in-class for that shape.
- JSONB for partner-defined `payload` keeps schema flexibility without giving up SQL queryability or constraints.
- Native declarative partitioning, partial indexes, and `SKIP LOCKED` are exploited throughout — these aren't peripheral features, they carry weight.

**Why pgmq for the queue (not Kafka / SQS / Redis)**:
- Same DB → events INSERT + outbox INSERT can share one transaction. The classical "dual-write" problem doesn't exist; the outbox pattern is here for **seam value** (ADR-002 / ADR-013), not atomicity.
- pgmq operations (`pgmq.send`, `pgmq.read`, `pgmq.archive`) are SQL function calls → no second client lib, no second pool, no second auth surface, no second monitoring stack.
- DLQ via `pgmq.archive` inherits the same partitioning, backup, and PITR posture as everything else.
- `FOR UPDATE SKIP LOCKED` (Postgres-native) is the only inter-process coordination primitive needed — no Redis distributed locks.

**Why one DB instance at Stage 1**:
- 2K-TPS-peak workload fits a single managed Postgres with headroom; PgBouncer handles connection fan-in.
- Splitting into operational + queue DBs (lever #16) is a documented Stage-2+ evolution, not deployed.

**Trade-offs**:
- **Vendor lock-in**: every Postgres-specific feature we use is migration cost if we ever leave. Accepted — those features carry their weight.
- **Single failure domain at Stage 1**: managed PG + replica + automated failover defends production; cross-region out of scope per case.
- **No ORM portability**: JPA's portability promise has nothing to deliver on a 5-table no-FK schema. JdbcTemplate stays (ADR-007).

**Considered and rejected**:
- *Kafka / Redis / SQS as the queue*: introduces dual-write; we'd need outbox to bridge → exactly what we have, but with extra infra to operate and monitor.
- *MongoDB for events*: gives up transactional guarantees we'd want; JSONB + GIN indexes already cover schemaless-payload querying.
- *DynamoDB*: no `SKIP LOCKED` equivalent, no declarative partitioning, secondary-index discipline tax — net loss for this OLTP shape.

### 3. Async processing strategy
> *Case: "async processing strategy"*

**Choice**: Transactional outbox → poller (250 ms cadence, `SKIP LOCKED` claim) → 5 pgmq queues (one per event type) → per-event-type virtual-thread workers with Semaphore-bounded concurrency.

**Why outbox (not direct `pgmq.send` inside the API tx)**:
- ADR-002. Three reasons (atomicity is **not** one — same DB makes both options equivalent on that axis):
  1. **Future-proofing for a Kafka swap** — outbox table is the stable broker-agnostic seam (ADR-013); only `OutboxPoller.sendToPgmq` rewrites on swap.
  2. **Shorter ingest transactions** — API tx = 2 plain INSERTs + 1 audit INSERT, not "INSERT + extension call + pgmq table writes".
  3. **Ingest module stays queue-agnostic** — only `delivery/` imports pgmq; `ingest/` tests don't need pgmq running.
- Cost: +250 ms median forwarding latency. Acceptable: partner contract is *"we have it durably"* (200 OK on commit), not *"the queue accepted it"*.

**Why 5 queues (not 1)**:
- ADR-003. Different processing profiles per type (latencies, priorities, burst patterns).
- One shared queue → shared scaling + head-of-line blocking. Slow `OrderCreated` would back up `OrderCancelled`.
- Per-type queues let KEDA scale each Deployment independently — cost goes where load goes.
- Trade-off: 5× ops surface (5 dashboards, 5 worker classes, 5 ScaledObjects). Negligible at Stage 1.

**Why one outbox today (and when to split per type)** — *the producer-side mirror of per-event-type queues*:
- **Today's structure (ADR-012)** — Single `event_outbox` is a transient buffer; rows deleted on successful `pgmq.send` (ADR-006). Steady-state size bounded by `poll_interval × write_rate` regardless of type mix. OK for initial / Stage-1 load **by assumption** — splitting per type *now* would multiply schema + indexes + poller wiring + migration surface **without** changing that bound. Outbox is `FOR UPDATE SKIP LOCKED` over a short queue and is not the bottleneck at Stage 1 / 2K-TPS-peak.
- **Why per-row `pgmq.send()` today (ADR-011)** — the outbox holds rows destined for **multiple queues** (one per event type). `pgmq.send_batch()` takes a single queue + array of payloads, so using it would require per-queue grouping + a flush buffer in the poller — the Kafka-producer accumulator pattern in miniature, complex code for a non-hot path. Per-row keeps the drain loop a flat `for row : rows { send; delete; markPending }`. Trade-off: N round-trips per batch instead of one per queue-group, accepted because the poller is not the hot path at current sizing.

**Future Kafka swap (ADR-013)** — Kafka's producer accumulator handles per-topic batching **natively**, so the multi-queue grouping problem dissolves entirely. Only `OutboxPoller.sendToPgmq` rewrites; the `event_outbox` schema is broker-neutral so ingest path + tests stay unchanged. The send_batch story above is the Stage-2 stopgap; Kafka is the long-term answer.

**Why this matters in interview** — the producer side has the same structured evolution path as the consumer side (§ Availability & Performance › L5 escape hatch), and the per-type-split-unlocks-batch insight ties ADR-011 + ADR-012 together as a *coupled* pair rather than two unrelated trade-offs.

**Why virtual threads + Semaphore (not platform thread pool)**:
- ADR-004. Handler is I/O-bound (DB writes + downstream HTTP).
- Platform pool: pinned at startup, under-sized → throughput cap, over-sized → memory waste, 5-knob tuning per type.
- VT + Semaphore: VTs are cheap (memory, not OS thread); Semaphore caps the actual scarce resource — DB connections.
- pgjdbc 42.7.2+ + Hikari 5.1.0+ moved off `synchronized` to `ResourceLock` → VTs park cleanly without pinning.
- Cost: Java 21+ requirement (we ship on 21).


### 4. Retry / error handling approach
> *Case: "retry/error handling approach"*

**Choice**: Two retry layers, **composed not stacked** — fast in-process Resilience4j + durable pgmq redelivery.

**Layer 1 — Resilience4j on `DownstreamCallService`** (ADR-009):
- `@Retry`: 3 attempts × 200 ms exponential. Absorbs transient blips (connection resets, 5xx) without paying a pgmq round-trip.
- `@CircuitBreaker`: 50% failure rate over 20-call sliding window, opens 10 s, auto half-open. Stops the worker pool from turning into a retry generator during sustained outages.
- 4xx **excluded** from `retry-exceptions` — caller bugs fail fast, don't burn the budget.
- Total in-process budget ≪ pgmq VT-5s deadline (25 s) — retry never collides with the visibility timeout.
- Fallback **rethrows**: exception propagates out of `EventProcessor.process` → row stays committed in PROCESSING → pgmq's outer redelivery applies.

**Layer 2 — pgmq visibility timeout + `read_ct`**:
- VT = 30 s. Worker crash mid-processing **or** handler timeout → message reappears, next worker reclaims.
- `read_ct >= maxAttempts (5)` → `pgmq.archive` + events row marked FAILED + audit row written.
- DLQ inspection: archive table **and** `events WHERE status='FAILED'` (forensic data in both places). Replay = re-INSERT outbox row from archive.

**Why two layers (not just one)**:
- Resilience4j alone: in-memory state, dies with the pod. Pod crash mid-retry loses retry state — can't tell *"attempt 1 or attempt 4?"*.
- pgmq alone: every retry is a full pod-to-DB round trip + JSON deserialize + connection acquire. Burns DB I/O on transient blips.
- Composed: in-process for blips (cheap); pgmq for outages (durable). Clear ownership, clear timeout budget.

**Why Resilience4j (not Spring `@Retryable`)**:
- `@Retryable` lacks circuit breaker; Resilience4j composes both in one annotation set.
- Resilience4j metrics auto-publish to Micrometer (`resilience4j_retry_calls_total`, `resilience4j_circuitbreaker_state`) → ops dashboard for free.

**Atomic state transitions as the safety net**:
- Every transition is `UPDATE … WHERE status IN (...)`. Read-then-write race conditions can't exist by construction.
- `tryMarkProcessing` accepts both PENDING and PROCESSING — handles redelivery cleanly without read-modify-write.

**Trade-offs**:
- Worst case: 3 in-process × 5 pgmq = 15 attempts before DLQ. Mitigated by 4xx exclusion + tight in-process budget + pgmq's `maxAttempts` cap.
- `DownstreamCallService` MUST stay a separate bean from `EventProcessor` — Spring AOP doesn't intercept self-invocation. Documented in ADR-009.
- DLQ replay is manual today (re-INSERT outbox row from archive). Acceptable for case scope; production = CLI tool.

### 5. Observability
> *Case: "observability"*

**Choice**: Three pillars (logs, metrics, traces) correlated by a single `trace_id`. All ship from one Spring Boot process. Implementation drilldown lives in **§ Observability — 3 pillars** below.

**Logs (SLF4J / Logback)**:
- Two profiles — human-readable for local; `json` profile with `LogstashEncoder` for prod (`SPRING_PROFILES_ACTIVE=json`).
- MDC fields populated at boundaries: `trace_id`, `span_id`, `partner_id`, `event_id`, `queue`, `msg_id`, `event_type`. Async logs carry the same identifiers — trace survives the API → outbox → pgmq → worker handoff.

**Metrics (Micrometer / Prometheus, `/actuator/prometheus`)**:
- Common tags `application` + `role=APP_RUNTIME_MODE` so per-role pods produce distinct time series.
- Built-in: JVM, `http_server_requests_*`, `hikaricp_connections_*`, Resilience4j retry / breaker.
- App-specific (`peg.*`): queue length (KEDA input), oldest-msg-age, processed/failed/dlq counts, consumer duration histogram, free Semaphore permits, partner-cache hit ratio.
- Alert seeds: `oldest_msg_age > 60 s`, any `dlq` increment, breaker `OPEN`, `hikaricp_connections_pending` sustained, 5xx rate > 0.

**Traces (Micrometer Tracing → OpenTelemetry → OTLP/HTTP)**:
- W3C propagation. **Always-on context, opt-in export** — spans always created, OTLP exporter only registers when `MANAGEMENT_OTLP_TRACING_ENDPOINT` is set. Logs stay correlatable locally with no collector running.
- Sampling: `management.tracing.sampling.probability` (env `TRACING_SAMPLING`, default 0.1).
- Async boundary bridged by `TraceContextCarrier`: HTTP span W3C headers inlined into `PartnerEventMessage` JSON; worker opens CONSUMER-kind span as child of producer context. One trace covers `HTTP POST → outbox → pgmq → worker → DownstreamCallService`.

**Health (`/actuator/health`)**:
- DB ping, Flyway, Resilience4j breaker, Hikari indicator.
- `HikariPoolHealthIndicator` returns DEGRADED (not DOWN) when `threadsAwaitingConnection > 0`. Pod stays in service while pool pressure is observable; DOWN would yo-yo it out of the LB.

**Why three pillars (not just one)**:
- Metrics: aggregate signal, no per-event detail.
- Logs: per-event detail, no traffic-shape visibility.
- Traces: per-request causality across the async boundary.
- Pivoting on `trace_id` covers the diagnosis triangle: *what's happening* (metrics) → *where* (traces) → *why* (logs).

**Trade-offs**:
- No log-derived metrics (Loki count queries) — slow + brittle. Micrometer counters are the durable interface.
- No dashboards in repo — deploy-target-specific. Alerts and metric shapes are what we own.

### 6. Deployment / scaling approach
> *Case: "deployment/scaling approach"*

**Choice**: Same Docker image runs **all 7 runtime modes**; deploy shape switched by `APP_RUNTIME_MODE`. Stage 1 (default, ships configured) = single `CONSUMER_ALL` pod. Stage 2 (documented evolution path, **not deployed**) = per-role Deployments + KEDA + PgBouncer. Config-only transition.

**Stage 1**:
- One JVM hosts API + 5 consumer workers + outbox poller + queue-depth exporter.
- `HIKARI_MAX=40` covers worst-case ~31–35 connection demand (24 worker slots + 5–10 API + 1 poller + 1 exporter).
- 1-pod ceiling ~400 msg/s steady; aggressive tuning (104 slots, `HIKARI_MAX=120`, outbox 200 msg/100 ms) absorbs **short** 2K bursts but every dimension of headroom collapses to one process.

**Stage 2**:
- 1 API Deployment (`APP_RUNTIME_MODE=API`, replicas 3).
- 5 consumer Deployments — one per event type (`CONSUMER_ORDER_CREATED`, …, `CONSUMER_ORDER_CANCELLED`).
- KEDA `postgres scaler` queries `pgmq.metrics(queue).queue_length` directly → drives HPA per Deployment with per-type `targetQueryValue` (500 high-volume; 50 latency-sensitive).
- PgBouncer transaction-mode in front of Postgres: 32 pods × 6 = 192 client connections multiplexed onto ~50 backends. Postgres never sees more than 50 active regardless of pod count.
- Worked sizing for 2K TPS sustained: 14 consumer + 3 API pods → ~2130 msg/s.

**Why same image, not per-role builds**:
- ADR-005. One image → one CI pipeline, one SBOM, one CVE patch path.
- Image is slightly larger than each role strictly needs; JRE + Spring Boot dwarf the app code; negligible.

**Why KEDA over native HPA**:
- Natural scaling signal is `pgmq.queue_length` — already in Postgres.
- Native HPA needs metrics-API (custom-metrics-apiserver / Prometheus Adapter); the metric routes through Prometheus first.
- KEDA queries Postgres directly → fewer moving parts, fresher metric.

**Why PgBouncer**:
- Per-pod Hikari pool small (6) for tx-mode compatibility. Without PgBouncer at 32 pods × 6 = 192 client connections, Postgres backend memory + context-switch cost dominates before throughput.
- Transaction-mode (not session-mode) maximizes multiplexing; `prepareThreshold: 0` + `auto-commit: true` already wired to stay tx-mode-compatible.

**Why Postgres-side levers before Kafka**:
- Lever ladder (`08-scaling-and-tradeoffs.md`): #1 concurrency → … → #18 shard → #19 Kafka.
- Most teams never get past #4. Kafka is the last-resort lever, applied surgically to *the one* queue that crosses the per-heap ceiling.
- Outbox layer (ADR-013) keeps that swap localized to `OutboxPoller.sendToPgmq` — ingest path and `event_outbox` schema stay unchanged.

**Outbox structure scaling path** — *producer-side mirror of queue-side L5*:
- Today's structure: single `event_outbox` + per-row `pgmq.send()` is OK at Stage 1 / 2K-TPS-peak (bounded buffer — ADR-012; per-row send because outbox spans multiple queue destinations — ADR-011).
- 4-step ladder when load grows past the single-table bound:
  1. **More API pollers** [wired] — `SELECT … FOR UPDATE SKIP LOCKED` coordinates concurrent claims across pods with **zero new code**.
  2. **Per-type table for the hot type** — split `event_outbox_<hot>` (e.g. `event_outbox_order_created`) only; cool four keep sharing the general `event_outbox`.
  3. **`pgmq.send_batch()` unlocks for free at step 2** — every row in the per-type table targets the *same* queue, so the per-queue grouping complexity that motivates ADR-011's per-row default disappears for that table. **ADR-011 + ADR-012 are a coupled design pair**: splitting one flips both.
  4. **Shard the hot per-type table** by `hash(partner_id) % N` if the per-type heap *also* saturates — same lever as queue-side L5.
- **Future Kafka swap (ADR-013)** — producer accumulator handles per-topic batching natively; multi-queue grouping problem dissolves entirely.
- Full rationale + interview framing: **§ Open design decisions › 3. Async processing strategy** › *"Why one outbox today (and when to split per type)"*.

**Capacity planning rule**:
- Per-worker throughput ≈ `concurrency / handler_p99`. Per-pod = sum across workers. Total = pods × per-pod.
- Verify before committing 2K: handler p99 (Resilience4j retries push worst case to ~1.5 s); Postgres write capacity (~12K writes/s, WAL fsync limiter); outbox poller drain rate (currently constants, lift to `app.outbox.*`).

#### 5-layer scaling diagnostic — name the symptom first

> Diagnose first, then pick cheapest lever that targets the symptom. Full 20-lever ladder + scorecard in `08-scaling-and-tradeoffs.md`.

| Layer | Symptom | Real cause | Cheapest fix that targets it | Don't reach for |
|---|---|---|---|---|
| **L1** consumer throughput | queue depth grows, workers always busy | concurrency × replicas < arrival rate, or slow handler | raise Semaphore (`app.consumer.concurrency.<queue>`); add replicas; profile handler | hourly partitioning (renames contention, doesn't divide it) |
| **L2** pickup latency | depth fine, per-msg latency high | poll interval (idle queues only — busy interval already covers loaded) | long-polling `pgmq.read_with_poll`; tune `busy-poll-interval-ms` | more pods (latency is per-msg, not throughput) |
| **L3** producer / API | API p99 spikes during bursts, 503s | API doing too much per req, or outbox poller behind | outbox already wired; add API replicas; per-partner rate limit | any consumer-side lever |
| **L4** database tier | Postgres pegged regardless of consumer count | underlying instance, hostile workload | PgBouncer; read replica; index audit; vacuum tuning; bigger PG | more consumer pods (makes L4 worse) |
| **L5** single hot event type | one queue saturates, others idle | one pgmq queue = one heap, one B-tree, one autovacuum | shard *that one* queue by `hash(partner_id) % N` (#18); eventually swap that one to Kafka (#19) | spreading load across all 5 queues — only one is the problem |

**Bottlenecks → first cures (quick lookup)**:

| Bottleneck | First lever (cheap) | If insufficient | Last resort |
|---|---|---|---|
| Slow handler | profile handler, raise `Semaphore` | add consumer replicas | shard hot queue by partner_id |
| Outbox poller behind | promote constants to `app.outbox.*`; bigger batch | per-API-pod poller (Stage 2 default) | per-type outbox split (`event_outbox_<hot>`) — also unlocks `pgmq.send_batch()` |
| Hot tail-page contention | check L1 first (don't shard prematurely) | `hash(partner_id) % N` shard the saturated queue | migrate that queue to Kafka |
| DB connection ceiling | `HIKARI_MAX` bump | PgBouncer transaction-mode | dedicated Postgres for pgmq (#16) |
| WAL fsync ceiling | bigger Postgres / faster disk | `pgmq.send_batch` (ADR-011 lever) | move queue tier off the OLTP DB |
| Cross-partner read pressure | enable `REPLICA_DB_URL` | tune covering indexes | dedicated read fleet |
| Cold-tier storage cost | `retention_keep_table=true` already set | `pg_dump` to S3 ship script (#17) | shorter retention windows |

**Trade-offs**:
- **Stage 2 manifests not in repo** — case spec asks "explain how the solution could support" scaling, not deploy it. Helm/KEDA/PgBouncer manifests sketched in `07-stage2-topology.md`, not committed.
- **No cross-region / multi-AZ posture** — out of scope per case.
- **No GitOps / Argo manifests** — deploy-target-specific; the durable interface is the runtime mode + env vars + KEDA scaler config.

### Design principles (heuristics that produced the above)

1. **Diagnose first, then pick the cheapest lever**. Most "the queue is slow" tickets are misdiagnosed (L1 fix applied to L4 problem, etc.). Don't reach for #18 (shard) when #1 (raise concurrency) hasn't been tried.
2. **Postgres-native primitives over distributed-systems infra when DB scale allows**. `SKIP LOCKED`, declarative partitioning, JSONB, partial indexes do the work that Redis / Kafka / distributed locks would otherwise do — for free, atomically, with one ops surface.
3. **Same DB → atomicity for free; outbox is for *seams*, not atomicity** (ADR-002). Don't justify outbox with the wrong reason in interviews.
4. **Two short transactions, not one long one**. Hold DB connections only across SQL — never across handlers, never across downstream HTTP. Resilience falls out of redelivery + atomic UPDATE-with-status-filter, not transaction lifetime.
5. **State transitions are atomic UPDATEs with status filters** — `UPDATE … WHERE status IN (...)`. Race conditions can't exist by construction; no application locks anywhere.
6. **Explicit binding over magic**. `partner_id` bound on every repo method, not auto-injected by AOP. Easier to audit; no surprise leaks. Cost: more typing, fewer surprises (ADR-007).
7. **Documented evolution paths, not deployed lever ladders**. Stage 2 manifests, hot-queue sharding, hot-outbox split, Kafka migration — all sketched as natural extensions of deployed code, not committed. Commit when triggers fire, not before.
8. **One tracing seam, not five**. `TraceContextCarrier` propagates W3C headers through pgmq JSON so a single trace covers `HTTP → outbox → pgmq → worker → downstream`. No per-segment tracing surgery.
9. **Audit atomic with the operational write** — same transaction. No transition without audit; no audit without transition. 24-mo audit retention outliving 12-mo events retention is intentional: late compliance lookups still resolve.
10. **DEGRADED, not DOWN, for pool pressure**. Visible to ops, doesn't yo-yo pods out of the LB.

## Observability — 3 pillars, one trace_id
- **Logs**: SLF4J/Logback, MDC fields `trace_id, span_id, partner_id, event_id, queue, msg_id, event_type` populated at boundaries. JSON profile for prod.
- **Metrics**: Micrometer/Prometheus at `/actuator/prometheus`. Tags `application` + `role=APP_RUNTIME_MODE`. Key gauges: `peg.queue.length`, `peg.queue.oldest_msg_age_seconds`, `peg.consumer.processed|failed|dlq`, `peg.consumer.duration`, `peg.consumer.concurrency.available`, `peg.partner_cache.size|hit_ratio`.
- **Traces**: Micrometer Tracing → OTLP/HTTP. Always-on context, opt-in export via `MANAGEMENT_OTLP_TRACING_ENDPOINT`. `TraceContextCarrier` bridges async — W3C headers inlined into `PartnerEventMessage` JSON, worker opens CONSUMER span as child.
- **Health**: `/actuator/health` aggregates DB ping, Flyway, breaker, Hikari (DEGRADED ≠ DOWN).

## ADRs — short form

| # | Decision | Why | Trade-off |
|---|---|---|---|
| **001** | Store `SHA-256(secret)` in DB | Raw secret never disclosed by SQL leak | Not BCrypt — DB leak still exposes HMAC key; production = KMS |
| **002** | Outbox vs direct `pgmq.send` | Future-proofing for Kafka/SQS; shorter ingest tx; ingest stays queue-agnostic. **Atomicity is NOT the reason** (same DB → both possible) | +250 ms median forwarding latency |
| **003** | Per-event-type queues (5) | Different processing profiles, independent scaling, no head-of-line blocking | 5× ops surface |
| **004** | Virtual threads + Semaphore | Handler is I/O-bound; VTs park, Semaphore caps DB connection demand | Java 21+ / Hikari 5.1.0+ required |
| **005** | Same image, 7 runtime modes | One image, role chosen by env var | Image slightly larger than each role needs |
| **006** | Outbox delete-on-send | Events table is audit source of truth, outbox row adds nothing; bounded steady-state | pgmq msg_id not retained on app side. **Stage 2 evolution**: time-partition outbox + DROP PARTITION (not deployed) |
| **007** | Specifications without JPA | pgmq is JDBC-native; 5 tables no entity graph; `@Query(nativeQuery)` needed everywhere; one mental model. Hand-rolled `EventSpecifications.SPECS` registry | Devs reaching for `JpaSpecificationExecutor` need to read; tenant filtering stays explicit |
| **008** | Monthly partitions for events+audit, daily for pgmq | Different shape, different cadence | Cross-month duplicate retries (>30 days) not blocked — vanishingly rare |
| **009** | Resilience4j retry + breaker on downstream | Retry absorbs transients; breaker caps long tail; total budget < VT-5s; 4xx excluded; fallback rethrows so pgmq redelivery still applies | Two retry layers can multiply (mitigated by 3×200ms ≪ 25s); DownstreamCallService must stay separate bean (Spring AOP self-invoke) |
| **010** | PostgreSQL only, no ORM portability | Already Postgres-locked via pgmq; partial indexes, partitioning, JSONB, SKIP LOCKED used freely | Swapping DB = non-trivial migration. Survives broker change (Kafka swap = delivery layer, not storage) |
| **011** | Per-row `pgmq.send` not `send_batch` | Outbox holds many queues; batched would need Kafka-producer accumulator pattern + per-queue grouping/flush logic. Per-row keeps loop a flat for-each | N round-trips per batch instead of one per queue-group. Acceptable: not hot path. **Real bottleneck is the synchronous chain (send + delete + markPending)** — Kafka swap removes all three, batch swap only fixes one |
| **012** | Single `event_outbox` table for all types | Transient buffer, bounded by `poll × write_rate`; splitting multiplies surface without changing bound. Audit lives in `events`+`event_audit_log`, not outbox | If one type dominates: lock contention on tail page, autovacuum competing, DELETE I/O. **Stage 2 evolution**: `event_outbox_<hot_type>` (not deployed) |
| **013** | Outbox layer is broker-agnostic | `event_outbox` schema has zero pgmq dependency. Only `OutboxPoller.sendToPgmq` + `PgmqWorker` reference pgmq. Broker swap = rewrite poller, schema/ingest unchanged | Poller's SKIP LOCKED claim duplicates pgmq's internal claim — paid to keep table broker-neutral |
