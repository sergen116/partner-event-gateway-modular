# Architecture

> System architecture, module boundaries, and how the design addresses each
> non-functional concern from the case spec. Companion to the [README](../README.md)
> (build/run) and the [diagrams](diagrams/) folder.

## 1. Overview

The Partner Event Gateway is a multi-tenant ingest platform for operational events from
external commerce/logistics partners. Partners authenticate with HMAC-SHA256, submit events
over HTTPS, and the platform durably accepts, asynchronously processes, audits, and
exposes those events for query.

The design is shaped by five properties from the case spec:

1. **Per-tenant isolation** — partners must not see or affect each other's events.
2. **At-least-once delivery with idempotency** — accepted events must not be lost; duplicates must not double-process.
3. **Independent scaling per event type** — different types have different processing profiles.
4. **Auditability** — every state transition is captured in an immutable, queryable log.
5. **Production-ready operational surface** — observability, graceful shutdown, retries, DLQ, partition lifecycle.

The implementation runs in two topologies from the same codebase:

- **Stage 1 (default, single process)** — `APP_RUNTIME_MODE=CONSUMER_ALL`, all 5 consumers
  in one JVM alongside the API. This is what runs locally and what the submission ships
  configured to use.
- **Stage 2 (per-queue Deployments)** — same image, one Deployment per role driven by
  `APP_RUNTIME_MODE`. Each consumer queue scales independently (e.g. via KEDA's postgres
  scaler against `pgmq.metrics().queue_length`).

The Stage 1→2 transition is configuration-only — no code changes — by design.

## 2. Modular monolith structure

The case spec leaves "modular monolith vs microservice approach" as an open
design decision. We pick modular monolith and implement it accordingly.
Packages are organized by **feature module**, not by technical layer. Each
module has explicit dependencies documented in its `package-info.java`, and
the dependency graph is acyclic.

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
`query.EventRepository`. That cross-module repository is the only shared-state
boundary; every other module owns its data. The audit module is consumed by
ingest, delivery, and query (each writes audit rows) but consumes none of them.

This shape matters because Stage 2 deploys the modules differently. An API
pod runs `ingest` + `query` + `partner` + `audit` writes; a consumer pod runs
`delivery` + `audit` writes. No code change between deployments — only the
runtime mode changes which beans get instantiated.

## 3. Component breakdown

### `partner` module

- `PartnerAuthFilter` — `OncePerRequestFilter` registered for `/api/v1/events*`.
  Loads the partner from DB, hands the canonical request bytes to `HmacVerifier`,
  on success sets the resolved `partner_id` as a request attribute.
- `HmacVerifier` — derives the HMAC key from `SHA-256(secret)` (raw bytes), so
  the raw secret never appears in storage or logs. Constant-time comparison via
  `MessageDigest.isEqual`. Anti-replay via timestamp window (±5 min). Secret
  rotation supported through `previous_secret_hash` with an expiry.
- `CachingRequestWrapper` — body is read once into a buffer so the filter and
  Spring's `@RequestBody` deserializer can both consume it.

### `ingest` module

- `PartnerEventsController` — REST endpoints for partner-facing submit and
  query. The `partner_id` filter on queries is enforced server-side from the
  auth context, not from request parameters.
- `EventIngestService` — single transaction containing both
  `events.insertIfAbsent` (with idempotency check) and `outbox.insert`.
  Returns whether this was a fresh insert or a duplicate.

### `delivery` module

- `OutboxRepository` + `OutboxPoller` — drain `event_outbox` into pgmq via
  `FOR UPDATE SKIP LOCKED`. **Rows are deleted on successful send** — see
  [ADR-006](#adr-006-outbox-delete-on-send) below.
- `PgmqWorker` (base) + 5 per-event-type subclasses. Each polls one pgmq
  queue, fans batch processing across virtual threads bounded by a Semaphore,
  enforces a batch deadline below the visibility timeout via
  `CompletableFuture.allOf().orTimeout(VT - 5s)`.
- `EventProcessor` — invoked per message. Claims via `tryMarkProcessing` (silent
  skip if already terminal), dispatches to a per-type handler, then marks
  PROCESSED. On exception, the transaction rolls back and pgmq's VT redelivers.
  Final-attempt FAILED + DLQ archive is the worker's decision via `read_ct >= maxAttempts`.

### `query` module

- `EventRepository` — reads/writes against partitioned `events` table.
  All state-transition methods write an audit row inside the same transaction.
- `EventQuery` — extensible filter specification (partner, type, status, date
  range, business ref, processing outcome).
- `EventSpecifications` — composable filter compilation. Adding a new filter
  is one line in the registry plus a field on `EventQuery`. See
  [ADR-007](#adr-007-specifications-without-jpa) below.
- `InternalEventsController` — cross-partner query endpoint (no auth, per case
  spec) with the same filter set plus an explicit `partnerId` parameter.
  Defaults the time window to last 90 days when neither `from` nor `to` is
  supplied so partition pruning bounds the scan.

### `audit` module

- `AuditLogger` — single fluent entry point: `auditLogger.transition(...)`.
  Called inside the caller's transaction so audit writes are atomic with the
  operational write.
- `AuditRecord` — read-side projection. The `event_audit_log` table is
  append-only — there is no update or delete API.

### `platform` module

- `RuntimeProperties` — drives the seven-mode topology switch.
- `ConsumerProperties` — per-queue concurrency caps tuned by I/O profile.
- `SecurityProperties` — header names, timestamp skew, algorithm.
- `SchedulingConfig` — multi-threaded `TaskScheduler` (default would serialize
  the 5 worker poll loops).
- `WorkerRegistrationConfig` + `WorkerScheduler` — programmatic, mode-driven
  worker creation. Stage 2 transition is a config change here, not code.
- `QueueDepthExporter` — `peg.queue.length{queue}` and
  `peg.queue.oldest_msg_age_seconds{queue}` gauges via Micrometer/Prometheus.

## 4. Storage and processing approach

### Storage tables

| Concern | Table | Partitioning | Retention |
|---|---|---|---|
| Partner credentials | `partners` | none | unbounded |
| Durable event record | `events` | monthly by `created_at` | 12 months |
| In-flight queue buffer | `pgmq.q_events_*` (5 queues) | daily by `enqueued_at` | 4 days |
| Reliable handoff API → queue | `event_outbox` | none (delete-on-send) | transient |
| Immutable transition log | `event_audit_log` | monthly by `occurred_at` | 24 months |
| DLQ / archived failures | `pgmq.a_events_*` | daily by `archived_at` | 4 days |

Both `events` and pgmq live in the same Postgres instance, which lets us put
the events insert and the outbox insert in a single ACID transaction — the
property the outbox pattern depends on.

### Why monthly partitions for `events` and `event_audit_log`

Daily partitions on these tables would create ~365 children per year per
table, multiplied across two tables = ~720 partitions just for the
operational and audit stores. That's catalog noise without a corresponding
operational benefit — these tables aren't queues, they're records.

Monthly partitions give:

- 12 active partitions for events (12-month retention) + a few future ones — readable in `\dt`
- Cheap retention via `DROP PARTITION` (O(1) regardless of partition size)
- Partition pruning when queries include `created_at` predicates
- Cold-tier archive workflow: `retention_keep_table=true` detaches old partitions instead of dropping them, so operations can `pg_dump` and ship to S3 before the actual destruction

### Why daily partitions for pgmq queues

Different shape, different choice. Queues are write-heavy at the head, read
constantly, and rotated quickly. Daily granularity matches the operational
rhythm: a queue partition holds 1 day of in-flight messages, and 4-day
retention covers a full long weekend.

### Processing flow

1. Partner POSTs HMAC-signed event.
2. `PartnerAuthFilter` verifies and resolves partner ID.
3. `EventIngestService` (in one transaction):
   - Inserts events row (`status=RECEIVED`)
   - Inserts outbox row
   - Writes audit row (`null → RECEIVED`)
4. Returns 200 to partner.
5. `OutboxPoller` (every 250ms):
   - Claims a batch with `FOR UPDATE SKIP LOCKED`
   - For each row: `pgmq.send`, delete outbox row, transition events to PENDING, write audit row (`RECEIVED → PENDING`)
6. `PgmqWorker`:
   - Reads batch from pgmq with VT=30s
   - Fans out across virtual threads bounded by Semaphore
   - Each task: `tryMarkProcessing` (audit `PENDING → PROCESSING`), run handler, `markProcessed` (audit `PROCESSING → PROCESSED`), `pgmq.delete`
   - On final-attempt failure: `pgmq.archive`, `markFailed` (audit `PROCESSING → FAILED`)

## 5. How the design addresses each non-functional concern

### Security

- HMAC-SHA256 with per-partner secrets; raw secrets never stored.
- Anti-replay via signed timestamp, ±5 min window.
- Constant-time signature comparison.
- Secret rotation supported (previous secret valid until expiry).
- Authentication filter only on `/api/v1/events*`; observability and
  internal endpoints are not exposed publicly in production (path-based separation).

### Tenant isolation

- `partner_id` is part of every event row's identity.
- Partner queries enforce `partner_id` from the auth context, not request parameters.
- DB unique constraint on `(partner_id, event_id, created_at)` is per-partner per-month.
- Two partners can reuse the same UUID without collision.

### Idempotency

- `(partner_id, event_id, created_at)` unique constraint blocks duplicate rows
  within a calendar month.
- `Idempotency-Key` header lets partners drive the event ID; without it, server generates one.
- Repeated submissions return 200 with `duplicate=true` and the original event's status.
- Worker-side: `tryMarkProcessing` checks the row state — already-terminal rows
  are silently skipped, even if pgmq redelivers.

### Reliability

- Transactional outbox: events insert and outbox insert commit atomically.
  Crash before pgmq.send → outbox poller picks up on next run.
- pgmq visibility timeout: a worker crash mid-processing reverts state via
  transaction rollback; pgmq redelivers after VT.
- DLQ: messages exceeding `maxAttempts` move to the pgmq archive table and
  the events row is marked FAILED.
- Downstream resilience: `DownstreamCallService.notify` is wrapped with
  Resilience4j `@Retry` + `@CircuitBreaker` so transient downstream blips
  are absorbed in-process and sustained outages trip a breaker instead of
  draining the worker pool. Rationale and budgets in
  [ADR-009](#adr-009-circuit-breaker--retry-on-downstream-calls).
- Graceful shutdown: TaskScheduler awaits VT+5s for in-flight polls; workers
  drain virtual-thread executors in `@PreDestroy`.

### Concurrency

- Multiple worker pods per queue: pgmq's `FOR UPDATE SKIP LOCKED` is the
  coordination mechanism. No application locks.
- Multiple outbox pollers (one per API pod): same `SKIP LOCKED` claim.
- Per-worker virtual threads bounded by Semaphore: VTs cheap, DB connections
  expensive.
- Atomic state transitions: every transition is one UPDATE with a status filter,
  not read-then-write.

### Auditability

- Every state transition writes an immutable row to `event_audit_log`.
- Audit writes are atomic with the operational UPDATE — same transaction.
- 24-month retention outlasts the 12-month operational retention, so
  compliance lookups remain available after the events row is gone.
- `actor` column attributes each transition to its source: `"ingest"`,
  `"outbox-poller"`, `"worker:order-created"`, etc.

### Availability / performance

- Stateless API + stateless workers — both horizontally scalable.
- Per-event-type queues let high-volume types (e.g. `OrderCreated`) scale
  independently of low-volume types (`OrderCancelled`).
- The same Docker image runs in 7 runtime modes — no per-role builds.
- Read-replica for the query path is documented as Stage 3.
- Daily partitioning + 4-day retention on pgmq queues means cleanup is
  `DROP PARTITION`, not `DELETE` — no autovacuum bloat on hot tables.
- Monthly partitioning with 12/24-month retention on operational/audit
  tables means cold-tier archival is an `ALTER TABLE … DETACH PARTITION`
  followed by `pg_dump` to S3.
- Query-side partition pruning: both controllers default the time window to
  last 90 days when not explicitly bounded, so unfiltered queries don't
  scan all 12 months of partitions.
- For the full ladder of scaling levers (config-tunable today through to
  Kafka migration / DB sharding), with current implementation status per
  lever, see [`diagrams/11-scaling-levers.md`](diagrams/11-scaling-levers.md).

### Maintainability

- Adding a new event type: enum value + queue migration + worker subclass
  + concurrency config entry. Five small files.
- Adding a new query filter: one field on `EventQuery` + one entry in
  `EventSpecifications.SPECS`. Two lines.
- Adding a new runtime mode: one enum value + one switch arm in
  `RuntimeProperties` + one Deployment manifest.
- Adding a new audit-row consumer: query `AuditLogger.historyFor(...)`.

## 6. Architecture decision records (short form)

### ADR-001: HMAC key derivation

**Decision:** Store SHA-256 hex of the partner's secret. Both sides derive
raw bytes from the hash and use those as the HMAC key.

**Why:** The raw secret never appears in the DB, so a SQL-level data leak
doesn't disclose it. An attacker who compromises the DB can still impersonate
the partner — they have the HMAC key — but at least the secret-as-string is
contained.

**Trade-off:** Not the same as a non-recoverable hash (BCrypt, Argon2). Real
production would put the secret in a KMS or sealed secret store and sign on
the gateway side. The current design is the documented case-study
simplification — strictly better than plaintext, worse than KMS-backed.

### ADR-002: Transactional outbox vs direct pgmq.send

**Decision:** API writes events row + outbox row in one transaction; a
separate poller forwards outbox rows into pgmq.

**Atomicity is not the reason.** Because pgmq is just a set of Postgres
tables in the same database, `INSERT INTO events` and `pgmq.send` *can*
share a single transaction — both commit or both roll back. The classical
"dual-write" problem (DB write + message broker call to a different
system) does not exist here. A direct `pgmq.send` inside the ingest
transaction would be atomic by Postgres ACID alone.

**Why outbox anyway.** Three specific properties that direct send doesn't
provide:

1. **Future-proofing for external brokers.** If we eventually swap pgmq
   for Kafka, SQS, or any non-Postgres broker, the ingest path stays
   unchanged. Only the relay (`OutboxPoller`) needs to be rewritten — a
   single background component, not the API hot path.
2. **Shorter ingest transactions and bloat isolation.** Direct send keeps
   the API transaction open across `pgmq.send`'s extension code and pgmq
   table writes. Outbox keeps the API transaction down to two plain
   INSERTs (events + outbox) and moves the pgmq churn — including its
   eventual vacuum cost — into the poller's transaction, off the
   partner-facing path.
3. **Separation of concerns.** The ingest module never imports pgmq; it
   writes a queue-agnostic JSON payload to `event_outbox`. Only the
   delivery module knows about pgmq, which keeps modular boundaries clean
   and means ingest's tests don't need pgmq running.

**Trade-off:** ~250 ms median forwarding latency between API ack and
queue arrival (one poll interval). Partner sees 200 OK as soon as the row
is durably accepted; queue delivery is eventually consistent. Acceptable
for an event ingest gateway.

For the long-form per-concern breakdown — six operational distinctions,
an incident simulator, and the at-a-glance scorecard — see
[`docs/diagrams/08-outbox-vs-direct-pgmq.md`](diagrams/08-outbox-vs-direct-pgmq.md)
and [`docs/diagrams/10-outbox-scorecard.md`](diagrams/10-outbox-scorecard.md).

### ADR-003: Per-event-type queues

**Decision:** One pgmq queue per event type, partitioned daily with 4-day
retention.

**Why:** Different event types have different processing profiles. One queue
forces shared scaling and head-of-line blocking; per-type queues enable
Stage 2's independent scaling.

**Trade-off:** 5x the operational surface (5 queues, 5 metrics dashboards,
5 worker classes). In Stage 1 mode this is negligible.

### ADR-004: Virtual threads + Semaphore

**Decision:** Each consumer worker fans batch processing across virtual
threads bounded by a per-worker Semaphore.

**Why:** Message processing is I/O-bound. Virtual threads park on I/O
instead of blocking carriers. The Semaphore caps logical concurrency at the
DB-connection budget.

**Trade-off:** Requires Java 21+ and Hikari 5.1.0+ (for non-pinning
compatibility). Both bundled with Spring Boot 3.2+.

### ADR-005: Same image, 7 runtime modes

**Decision:** One Docker image. `APP_RUNTIME_MODE` env var picks API,
all-consumers, or a single-type consumer.

**Why:** Operationally simpler than per-role builds. Stage 2 deploys the
same image into per-role Deployments.

**Trade-off:** Image is slightly larger than each role strictly needs.
Negligible — JRE + Spring Boot dwarfs the application code.

### ADR-006: Outbox delete-on-send

**Decision:** Delete the outbox row immediately on successful pgmq forward,
rather than setting a `sent_at` timestamp and sweeping later.

**Why:** The events table is the audit source of truth — `audit.historyFor`
returns the canonical timeline of every transition including the
`RECEIVED → PENDING` moment that maps to "outbox forwarded successfully."
Keeping the outbox row adds nothing; the table either stays small forever
(delete-on-send) or grows unboundedly without a sweeper.

**Trade-off:** The pgmq message ID isn't kept anywhere on the application
side. Operations who want to query pgmq directly via the message ID must
trace it from pgmq's own metadata, not the events table. Acceptable —
operations rarely need this in practice.

### ADR-007: Specifications without JPA

**Decision:** The persistence layer is JdbcTemplate + raw SQL throughout —
no JPA, no Hibernate, no Spring Data JPA. Dynamic event filtering is
implemented in `EventSpecifications` as a registry of small functions that
compile into SQL fragments and bound parameters.

**Why no JPA at all:**

- **pgmq is JDBC-native.** Core operations (`pgmq.send`, `pgmq.read`,
  `pgmq.archive`) are SQL function calls; the outbox poller and workers
  use `SELECT … FOR UPDATE SKIP LOCKED`. JPA can't express these
  idiomatically — every pgmq touch point would drop to
  `@Query(nativeQuery=true)` regardless. Adopting JPA would produce a
  hybrid stack (JPA for entities + JDBC for pgmq) where one stack already
  does the job.
- **Few tables, no object graph.** Five tables (`partners`, `events`,
  `event_outbox`, `event_audit_log`, plus pgmq's internal tables) with
  deliberately no FKs between the audit, outbox, and events tables (see
  the ERD doc). JPA's value — transparent navigation of `@OneToMany` /
  `@ManyToOne` — has nothing to navigate here.
- **One dependency, one mental model.** Adding JPA means two transaction
  managers (or a chained one), two result-mapping styles, plus Hibernate's
  runtime overhead (first-level cache, dirty checking, lazy proxies) that
  buys nothing on a high-throughput ingest path with no entity graph.
- **Already Postgres-locked.** pgmq is a Postgres extension, so the system
  is committed to Postgres permanently. JPA's main portability promise —
  "swap the DB vendor without rewriting queries" — has nothing to deliver
  here. Postgres-specific SQL (`FOR UPDATE SKIP LOCKED`, partial indexes,
  `JSONB`, partitioning DDL, `pgmq.*` functions) is used freely with no
  vendor-neutrality cost.

**Why hand-rolled Specifications:** The case spec calls for an extensible
filter API. We needed the *pattern* — composable optional predicates — not
the JPA implementation of it. `EventSpecifications` is a five-line registry
where adding a filter is one line plus one `EventQuery` field. SQL stays
one read away, which matters for keeping queries partition-prunable on the
monthly-partitioned `events` table.

**Trade-off:** Developers who reach for `JpaSpecificationExecutor` by
reflex have to read the module to recognise the pattern. Mitigated by the
registry being literally five lines and the design being documented here.

### ADR-008: Monthly partitioning for events and audit

**Decision:** `events` and `event_audit_log` partitioned monthly. pgmq
queues partitioned daily.

**Why:** Different shape, different cadence. The events table is a record
that lives for months; daily partitions there create catalog noise without
operational benefit. Monthly partitions align with the natural operational
rhythm: a single partition is the right granularity to detach for cold
storage. pgmq queues are write-heavy at the head and rotate quickly; daily
matches their churn.

**Trade-off:** Within-month idempotency is guaranteed by the unique
constraint, but cross-month duplicate retries (same UUID more than 30 days
apart) are not — a vanishingly rare scenario in practice given that
idempotency keys are per-event and not long-lived.

### ADR-009: Circuit breaker + retry on downstream calls

**Decision:** `DownstreamCallService.notify` is wrapped with Resilience4j
`@Retry` (3 × 200 ms, exponential backoff) and `@CircuitBreaker` (50%
failure rate over 20 calls, opens for 10 s, auto half-open). Total budget
stays well below pgmq's visibility-timeout-minus-5s deadline.

**Why both.** Retry absorbs short-tailed transients (connection resets,
5xx) in-process so blips don't pay the full pgmq round-trip. The breaker
caps the long tail: a sustained outage would otherwise turn the worker
pool into a retry generator. Together they compose — retry for blips,
breaker for outages. 4xx is excluded from `retry-exceptions` so caller
bugs fail fast.

**Why the fallback rethrows.** `onFailure` logs and rethrows so the
`EventProcessor` transaction rolls back and pgmq redelivery / DLQ keep
applying their own outer budget. Two layers, composed not stacked:
Resilience4j is fast and in-process; pgmq is slow, durable, and survives
restarts.

**Trade-off:** Two retry layers can multiply attempts in the worst case;
mitigated by the tight in-process budget (3 × 200 ms ≪ 25 s) and the 4xx
exclusion. Also requires `DownstreamCallService` to stay a separate bean
from `EventProcessor` — Spring AOP proxies don't intercept self-invocation.

### ADR-010: PostgreSQL as the storage choice

**Decision:** PostgreSQL is the storage backend, and the codebase is
intentionally bound to it — JdbcTemplate against Postgres-native SQL, no
ORM portability layer. pgmq (a Postgres extension) is the in-flight queue.

**Why the lock-in is acceptable.** Two existing constraints already pin
us to Postgres: pgmq is a Postgres extension used through raw JDBC
(`pgmq.send`, `pgmq.read`, `FOR UPDATE SKIP LOCKED`), and the schema is
five tables with no entity-graph traversal — JPA's portability layer has
nothing to deliver here (see
[ADR-007](#adr-007-specifications-without-jpa)). Hand-rolled SQL also
keeps queries readable and partition-prunable on the monthly-partitioned
`events` table.

**Known trade-off.** With JPA, swapping to MySQL or another OLTP DB would
be roughly a configuration change. Without it, that swap is a non-trivial
migration — every query is rewritten and every Postgres-specific feature
(partial indexes, declarative partitioning, JSONB, `SKIP LOCKED`) needs an
equivalent. We accept the cost because those same features are exploited
throughout the design, not incidentally.

**Survives a future broker change.** When pgmq is eventually replaced by
Kafka at lever #19 in
[`diagrams/11-scaling-levers.md`](diagrams/11-scaling-levers.md),
Postgres stays. Events, audit log, and partner credentials are OLTP
workloads — transactional, indexed, partitioned — where Postgres is
best-in-class. The broker swap is a delivery-layer change, not a storage
one; the storage decision is decoupled from the queue decision.
