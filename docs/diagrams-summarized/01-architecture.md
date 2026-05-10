# ARCHITECTURE.md — Summary

## Overview
Multi-tenant event gateway: partners → HMAC-SHA256 over HTTPS → durable accept → async process → audit → query. Two topologies from same image: **Stage 1** (`CONSUMER_ALL`, single JVM) and **Stage 2** (per-role Deployments via `APP_RUNTIME_MODE`). Stage 1→2 is config-only.

## Modular monolith — 7 modules
`shared` · `partner` (HMAC) · `ingest` (API → events+outbox) · `delivery` (poller+workers+processor) · `query` (filter framework, EventRepository — only cross-module shared-state boundary) · `audit` (immutable transitions) · `platform` (modes, scheduling, observability). Acyclic DAG. Stage 2 deploys API pods (`ingest+query+partner+audit`) and consumer pods (`delivery+audit`).

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
- **Worker-side idempotency** — `tryMarkProcessing` is `UPDATE … WHERE status IN ('PENDING','PROCESSING')`. Already-terminal rows (PROCESSED/FAILED) match no row → silent skip + `pgmq.delete`, even if pgmq redelivers after a post-PROCESSED race.
- **Limit (ADR-008)** — cross-month duplicate retries (>30 days apart) not blocked. Vanishingly rare given idempotency keys are short-lived.

### 5. Concurrency
> *Case: account for concurrent submissions, concurrent processing, race conditions, and consistent state transitions.*

- **One coordination primitive** — `FOR UPDATE SKIP LOCKED` is the only between-process mechanism. No application locks anywhere (no Redis lock, no `synchronized`, no DB advisory locks).
- **Concurrent submissions** — multiple API pods, each with its own outbox poller, all coordinate against `event_outbox` via the same SKIP LOCKED claim. Tail-page contention is row-level, not page-level.
- **Concurrent processing** — multiple worker pods per pgmq queue use SKIP LOCKED inside `pgmq.read`. Each pod also fans out per-batch across virtual threads bounded by Semaphore.
- **Atomic state transitions** — every transition is one `UPDATE events SET status='X' WHERE status IN (...)` under PostgreSQL `READ COMMITTED`. Atomic UPDATE → only one of N concurrent updaters wins; losers see `rows=0` and silently skip. **No read-then-write anywhere.**
- **Audit atomic with op** — every `UPDATE` carries an `INSERT INTO event_audit_log` in the **same transaction**. No transition without audit; no audit without transition.
- **Per-worker VTs, Semaphore-bounded (ADR-004)** — VTs are cheap (parking on I/O is free); DB connections aren't. Semaphore caps logical concurrency at the per-pod Hikari budget. `Thread.sleep`, `Semaphore.acquire`, `CompletableFuture.join` all unmount cleanly on Java 21+ with pgjdbc 42.7.2+ and Hikari 5.1.0+ (both moved off `synchronized` to `ResourceLock`).
- **Ingest race walkthrough** — two concurrent first-time POSTs with same Idempotency-Key:
  1. Both pass `SELECT WHERE (partner_id, event_id)` (no row yet)
  2. Both attempt `INSERT … ON CONFLICT DO NOTHING RETURNING *`
  3. Winner gets 1 row, writes outbox + audit, commits
  4. Loser gets 0 rows, re-SELECTs winner's committed row, returns 200 `duplicate=false` for the winner's submission contract (one OK, one effectively wins; both clients see committed state)
- **Worker reentry rule** — `tryMarkProcessing` accepts both PENDING **and** PROCESSING. Any post-failure redelivery (handler exception, downstream timeout, SIGKILL between Tx 1 and Tx 2, finalize fail) cleanly reclaimed.
- **State transitions are 5 + 1 self-loop** — RECEIVED → PENDING → PROCESSING → PROCESSED|FAILED, plus PROCESSING → PROCESSING for redelivery. Every transition logged.

### 6. Availability & Performance
> *Case: explain how the solution could support high availability, growing traffic, and efficient reads/writes.*

#### High availability
- **Stateless API + stateless workers** — pod loss never strands work; durable state is in Postgres only (queues are Postgres tables too)
- **Same image, 7 runtime modes (ADR-005)** — `APP_RUNTIME_MODE` selects role. Stage 1→2 is config-only; no per-role builds, no code change to split API and consumer pods
- **`HikariPoolHealthIndicator` returns DEGRADED, not DOWN** — pool pressure is observable in `/actuator/health` without flapping readiness probes (DOWN would yo-yo pods out of service under load)
- **Health aggregation** — `/actuator/health` includes DB ping, Flyway, Resilience4j breaker, Hikari (DEGRADED ≠ DOWN); `/actuator/circuitbreakers`, `/actuator/retries`, `/actuator/prometheus` for ops drill-down
- **Single Postgres failure domain (Stage 1)** — explicit limit. Production HA = managed Postgres (RDS / CloudSQL) + read replica + automated failover; cross-region DR out of scope per case spec.

#### Growing traffic
- **Per-event-type queues (ADR-003)** — high-volume types scale independently of low-volume types; no head-of-line blocking. Cost: 5× ops surface (5 queues, 5 worker classes, 5 dashboards) — negligible at Stage 1.
- **KEDA postgres scaler** — drives HPA per consumer Deployment from `pgmq.metrics().queue_length`. Per-queue `targetQueryValue` tunes profile (high-volume: 500 backlog/pod; latency-sensitive `order-cancelled`: 50). **Cost goes where load goes.**
- **Stage 2 sizing for 2K TPS sustained** — 14 consumer pods + 3 API pods → ~2130 msg/s (per-queue replica/concurrency table in `07-stage2-topology.md`). 3 API pods × `HIKARI_MAX=12` for ingest at the same TPS.
- **Stage 1 ceiling** — single `CONSUMER_ALL` pod ≈ 400 msg/s. Aggressive tuning (104 slots, `HIKARI_MAX=120`, outbox 200 msg / 100 ms) absorbs **short** 2K peaks but **every dimension of headroom collapses to one process** — single failure domain, no surgical scaling, 120+ connections without PgBouncer, single outbox poller. 2K TPS sustained = multi-pod target.
- **Assumptions to verify before committing 2K** — handler p99 ≤ 100 ms (Resilience4j retries push worst-case to ~1.5 s on transient failures, which collapses effective concurrency); Postgres ~12K writes/sec (`pgbench -j 8 -c 32 -T 60`; WAL fsync is the limiter); outbox poller promoted from constants to `app.outbox.*` (lever #5); PgBouncer multiplexes ~350 clients → 50–80 backends; KEDA manifests deployed.
- **5-layer scaling diagnostic** — name the symptom first (L1 consumer throughput / L2 pickup latency / L3 producer-API / L4 DB tier / L5 single hot type). Pick cheapest lever that targets it; don't reach for #18 when #1 isn't tried (`08-scaling-and-tradeoffs.md`).
- **L5 escape hatch** — single saturated queue ≠ blanket fix. Shard *that one* queue by `hash(partner_id) % N` (lever #18). Partner-id key preserves per-tenant ordering and keeps all N shards hot in parallel (random breaks ordering; time-based rotates one at a time = same hot-tail problem). Eventually swap that one queue to Kafka (lever #19); outbox is the seam that keeps swap cheap (ADR-013).
- **Hot-outbox split** — if one event type's volume crosses the per-heap ceiling at the outbox layer too, split *that one* type into its own table (`event_outbox_order_created`); leave the cool four sharing `event_outbox`. Symmetric with the queue-side fix (ADR-012 § Stage 2 evolution).

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
- **Incident isolation (ADR-002)** — pgmq outage → outbox row stays put → partners unaffected. Recovery automatic when pgmq returns.
- **Broker future-proofing (ADR-013)** — `event_outbox` schema has zero pgmq dependency. Kafka swap rewrites only `OutboxPoller.sendToPgmq` + `PgmqWorker`; ingest path and tests unchanged. The outbox's *table* is the durable seam — the *poller* is throwaway code.
- **No JPA reflex tax** — devs reaching for `JpaSpecificationExecutor` need to read the registry once. Mitigated by 5-line implementation + this doc.

### Auditability  *(case spec FR-6, not strictly an NFR — kept here because every architectural choice serves it)*
> *Case: trace what happened to an event — when received, which partner, type, current state, success/fail.*

- **Append-only `event_audit_log`** — every state transition writes one row `(from_status, to_status, actor, error, occurred_at)`. Only writer is `AuditLogger.transition`; only deleter is `pg_partman_bgw`. No update API, no delete API.
- **Audit atomic with op** — same transaction as the operational UPDATE → no transition without audit; no audit without transition.
- **24-month retention outlasts events' 12 months** — late compliance lookups still resolve after the events row is gone. Indexed by `(partner_id, event_id, occurred_at)` so `historyFor` is fast even after the events partition is dropped.
- **`actor` column attributes each transition** — `"ingest"`, `"outbox-poller"`, `"worker:order-created"`, etc. Forensic index `(actor, occurred_at DESC)`.
- **Trace correlation** — `trace_id` flows MDC → logs → `event_audit_log` not directly stored, but logs join on `partner_id` / `event_id` → trace UI for end-to-end timing.
- **Example transition counts** — successful event has 4 audit rows (`null→RECEIVED`, `RECEIVED→PENDING`, `PENDING→PROCESSING`, `PROCESSING→PROCESSED`); FAILED has 4 ending in `PROCESSING→FAILED` with reason in `error`; redelivered+recovered has 5+ (one or more `PROCESSING→PROCESSING`).

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
