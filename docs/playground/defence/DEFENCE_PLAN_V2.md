# 30-Minute Case Defence Plan v2.3 — Partner Event Gateway

Revised from `DEFENCE_PLAN.md` (v1 → v2 closed the case-coverage gaps from `DEFENCE_PLAN_AUDIT.md`; v2 → v2.1 made NFR coverage explicit; v2.1 → v2.2 added the consume-sequence walkthrough; v2.2 → v2.3 makes Open-design-decision coverage explicit via a closing matrix card paired with the NFR matrix). v2 changes:

- Seg 1 names the **five event types** explicitly (Functional Req — Supported Event Types).
- Seg 2 adds a **30-second ERD walkthrough** (Deliverable 5 — data model).
- Seg 3 adds explicit **request / event-type validation** beat (Functional Req 2).
- Seg 4 demo budget is reshuffled: Demo D is split into **D1 (isolation)** + **D2 (querying with pagination + filters)** — this is the biggest gap (Functional Req 5).
- Seg 4 calls out the **FAILED lifecycle state** explicitly via state-machine diagram + integration test (Functional Req 3 — "failed during processing").
- Seg 6 covers **assumptions and time spent** as required by the README deliverable.

v2.1 changes:

- Seg 1 names the **case PDF's 7 NFRs verbatim** (Security · Tenant Isolation · Reliability · Idempotency · Concurrency · Availability & Performance · Maintainability) + Auditability as cross-cutting; adds an 8-anchor mechanism map.
- Every beat in Seg 2, Seg 3, and Seg 4 carries compact **`[NFR]` tags** — interviewer sees coverage explicitly.
- Seg 5 is **NFR-led**: 5a Availability & Performance · 5b Maintainability · 5c trade-offs · 5d **closing NFR coverage matrix card (~30 sec)**.
- Q&A rows tagged + 3 NFR-anchored rows appended; Q&A budget tightened 4:00 → 3:30 to absorb the matrix.
- Cross-link from Seg 1 to `docs/diagrams-summarized/01-architecture.md § NFR coverage` for verbose talking points.

v2.2 changes:

- **Seg 3b consume-sequence walkthrough added (1.5 min, 0:10–0:11:30)** — `04-consume-sequence.md` gets the same dedicated treatment as `03-ingest-sequence.md`. Five points cover pgmq.read claim, two-tx claim/finalize, handler-outside-tx, structural recovery via VT redelivery. Tags `[Rel][Conc][Idem][Aud]`.
- `04-consume-sequence.md` added to pre-talk browser tabs list.
- Seg 4 demo budget compressed 9:00 → 7:30: Demo C 1:00 → 0:30, Demo D1 1:00 → 0:30, Demo E 1:00 → 0:30 (FAILED narrative absorbed by Seg 3b).

v2.3 changes (this revision):

- **Seg 5c reframed: "3 trade-offs to defend" → "Open design decisions matrix"** — single 6-row table mapping each case-PDF Open item (Monolith vs microservice · Storage · Async · Retry / error · Observability · Deployment / scaling) to **choice → where addressed → defended trade-off**. Same 1:30 budget; matrix's trade-off column subsumes the old prose-form trade-offs.
- **Paired closing recap**: Seg 5c (Open decisions matrix) + Seg 5d (NFR coverage matrix) sit adjacent — together they prove dual coverage of case PDF § Open for Your Design Decisions + § Non-Functional Expectations in <2 min.
- Seg 1 footer adds a parallel cross-link to `01-architecture.md § Open design decisions` (matches the existing NFR coverage cross-link).
- "If cut short" priority list reframes the closer as `5d + 5c` paired matrices.
- **No demo cut needed** — swap-not-add design preserves the 7:30 demo budget.

Total time still 30 min. Stable layout: Seg 1 (3) · Seg 2 (3) · Seg 3 (4) · **Seg 3b (1:30)** · Seg 4 (7:30) · Seg 5 (5:30) · Seg 6 (2) · Q&A (3:30).

---

## Pre-talk checklist (do 5 min before the call)

```bash
# Terminal 1 — DB up, app up
docker compose up -d postgres
./mvnw spring-boot:run

# Terminal 2 — already streaming JSON-pretty logs from spring-boot:run

# Terminal 3 — psql session, ready for live SQL during Demo B
psql "postgresql://peg:peg@localhost:5432/peg"

# Browser tabs (left → right):
# 1. docs/case/Backend_Case.pdf
# 2. docs/diagrams/01-system-overview.md           (rendered)
# 3. docs/diagrams/02-erd.md                       (Seg 2)
# 4. docs/diagrams/03-ingest-sequence.md           (Seg 3)
# 5. docs/diagrams/04-consume-sequence.md          (NEW v2.2 — for Seg 3b)
# 6. docs/diagrams/05-state-machine.md             (referenced from Seg 3b + Demo E)
# 7. docs/diagrams/06-stage2-topology.md           (Seg 5a)
# 8. http://localhost:8080/swagger-ui/index.html
# 9. http://localhost:8080/actuator/prometheus
# Postman: collection open, partner-acme env selected, one request pre-signed
```

Pre-seed at least **6 events across both partners and at least 3 event types** before the call so Demo D2's filters return non-empty pages without you manually loading data live.

---

## Segment 1 — Framing the problem (3 min, 0:00–0:03)

**Say (~60 sec):**
> "The case is a multi-tenant event gateway for five operational event types — **OrderCreated, ShipmentStatusUpdated, ReturnRequested, DeliveryAddressUpdated, OrderCancelled**. The case PDF lists seven non-functional expectations — **Security, Tenant Isolation, Reliability, Idempotency, Concurrency, Availability & Performance, Maintainability** — plus FR-6 Auditability as a cross-cutting property. Every architectural decision in this submission lands on one or more of those eight."

**Mechanism map** (~15 sec — read at half-speed so the interviewer can latch each NFR to its anchor):
> "HMAC + hashed secret · `partner_id` from auth context · outbox + DLQ + breaker · unique constraint + `ON CONFLICT` · `SKIP LOCKED` + atomic `UPDATE` · per-event-type queues + KEDA · feature modules + 5-line filter registry · append-only audit log atomic with each op."

Then point to **`docs/diagrams/01-system-overview.md`** and walk left→right in 90 seconds:
- Partner → `PartnerAuthFilter` (HMAC) → `EventIngestService` (one txn: events + outbox + audit)
- `OutboxPoller` → **5 pgmq queues, one per event type** → 5 workers → `EventProcessor` → downstream
- Internal users → `InternalEventsController` (cross-partner query)

**Don't** dive into code yet. Just topology.

> **Verbose NFR talking points**: `docs/diagrams-summarized/01-architecture.md § NFR coverage — talking points`. Mirror the same case-PDF order; jump there if the interviewer drills before Seg 5 lands.
>
> **Verbose Open design decisions talking points**: `docs/diagrams-summarized/01-architecture.md § Open design decisions`. Six items × choice → why → trade-off, in case-PDF order. Closing recap card lives at Seg 5c.

---

## Segment 2 — Module layout, ERD, the modular monolith choice (3 min, 0:03–0:06)

Switch to your IDE, show `src/main/java/com/example/peg/` tree.

**Module talking points (one sentence each):**
- "Packages are by **feature module** — `ingest`, `delivery`, `query`, `audit`, `partner`, `platform`, `shared`."
- "Dependency graph is acyclic and documented per-module in `package-info.java`."
- "Same code, two deployment shapes: Stage 1 = one JVM (`CONSUMER_ALL`), Stage 2 = per-role pods. Stage transition is one env var, **no code change**."

**Then 30 sec on the data model — open `docs/diagrams/02-erd.md`:**
> "Three tables: `events` (partitioned by `created_at`, unique on `(partner_id, event_id, created_at)` — **idempotency anchor + per-tenant uniqueness `[Idem][Iso]`**), `event_audit_log` (one row per state transition, written in the same txn — **`[Aud][Rel]`**), `event_outbox` (pgmq dispatch staging — **broker-agnostic seam, `[Rel][Maint]`**). Partitioning is on `created_at` because that's the dimension that grows monotonically and is what cold-tier archival keys on."

Open `docs/ARCHITECTURE.md` § 2 only if interviewer asks for the dependency diagram.

---

## Segment 3 — Walk the ingest path with the sequence diagram (4 min, 0:06–0:10)

Open **`docs/diagrams/03-ingest-sequence.md`**. Walk it top to bottom.

**Land these specific points:**
1. **HMAC auth** `[Sec]` — `SHA-256(secret)` derivation, ±5 min timestamp window, constant-time compare. Mention secret rotation field. (ADR-001)
2. **Validation** `[Sec][Iso]` — *"Request schema is bean-validated (`@Valid` on the DTO), event type is validated against the `EventType` enum on deserialization, partner identity comes from the auth context not the body — so a partner cannot spoof another's `partnerId` even by lying in the payload."* (Covers Functional Req 1 + Req 2.)
3. **Idempotency** `[Idem][Conc]` — `(partner_id, event_id, created_at)` unique. `SELECT` first, then `INSERT ON CONFLICT`, then re-`SELECT` on race loss. Show the diagram's "race lost" branch.
4. **One transaction** `[Rel][Aud]` — events row + audit row + outbox row. Atomic commit; partner sees 200 OK before pgmq is touched.
5. **Outbox poller decoupling** `[Rel][Maint]` — *"I deliberately picked outbox over direct `pgmq.send` even though pgmq is in the same DB. Atomicity wasn't the reason — three things were: future-proofing for Kafka, shorter ingest transactions, and keeping the ingest module queue-agnostic. Cost: ~250 ms median forwarding latency."* (ADR-002 — your strongest design call, lead with it if asked.)

---

## Segment 3b — Walk the consume path with the sequence diagram (1.5 min, 0:10–0:11:30)

Open **`docs/diagrams/04-consume-sequence.md`**. Walk it top to bottom — five points, ~18 sec each. Tags: **`[Rel][Conc][Idem][Aud]`** (this single beat lands four NFRs).

**Land these specific points:**

1. **`pgmq.read(queue, vt=30s, qty=batch-size)`** `[Rel][Conc]` — claim with visibility timeout. Message invisible to other workers for 30 s. Fan out across **virtual threads bounded by Semaphore** — *"VTs are cheap, DB connections aren't."* (ADR-004; pgjdbc 42.7.2+ + Hikari 5.1.0+ both moved off `synchronized` so VTs park, don't pin.)

2. **Tx 1 — claim** `[Conc][Idem]` — `tryMarkProcessing` is `UPDATE events SET status='PROCESSING' WHERE status IN ('PENDING','PROCESSING')`. Atomic UPDATE, commits, **releases the DB connection**. *"Already-terminal rows match no row → silent skip + `pgmq.delete`. One predicate handles both claim and post-PROCESSED redelivery."*

3. **Handler runs OUTSIDE any DB transaction** `[Rel]` — including the downstream HTTP call. Connection held only for SQL, **never** across the seconds-long Resilience4j retry / breaker budget. *"Two short transactions, not one long one — that's why connection budget survives load."*

4. **Tx 2 — finalize** `[Rel][Aud]` — `markProcessed` UPDATE + `event_audit_log` row, atomic. Then `pgmq.delete` releases the message. *"Audit atomic with the operational write — no transition without audit, no audit without transition."*

5. **Recovery is structural, not coded** `[Rel][Conc]` — handler crash mid-processing → row left committed in PROCESSING → pgmq VT expires → next worker reclaims via the **PROCESSING-or-PROCESSING rule** on `tryMarkProcessing`. `read_ct >= maxAttempts (5)` → `pgmq.archive` + events row marked FAILED + audit row written. **No application locks, no read-then-write, no retry state in pod memory.**

**Optional 15-sec backup callouts** (only if the interviewer drills):
- **Batch deadline** = `CompletableFuture.allOf(...).orTimeout(VT-5s = 25s)`. On timeout we **don't** cancel in-flight tasks — letting them finish delete/archive avoids double-delete.
- **Work-conserving loop**: full batch → 20 ms busy-poll, partial/empty → 500 ms idle-poll. Effective busy cycle ≈ `batch_processing_time + 20 ms`, not 500 ms.

---

## Segment 4 — Live demo (7.5 min, 0:11:30–0:19) ← the part most candidates botch

Five demos, in this order. Don't improvise.

### Demo A — Happy path (1.5 min) `[Rel][Aud]`
1. In Postman, send `POST /api/v1/events` with `partner-acme`, event type `OrderCreated`. *(HMAC pre-request script signs it.)*
2. Show 200 OK with `status: RECEIVED, duplicate: false`.
3. Run `GET /api/v1/events/{eventId}` → status now `PROCESSED`.
4. Switch to terminal — show the trace: `RECEIVED → PENDING → PROCESSING → PROCESSED`. Point at the `trace_id` MDC field threading through.

### Demo B — Idempotency + auditability (2 min) `[Idem][Aud]`
1. Re-send the **exact same** request (same `Idempotency-Key`).
2. Show 200 OK with `duplicate: true` and the original event's current status.
3. **Quick SQL** in psql terminal:
   ```sql
   SELECT event_id, status FROM events WHERE event_id = '<id>';
   SELECT * FROM event_audit_log WHERE event_id = '<id>' ORDER BY occurred_at;
   ```
4. *"One events row, full audit trail. This is the auditability requirement — every transition logged in the same transaction as the operational write."*

### Demo C — HMAC rejection (0.5 min) `[Sec]`
1. Tamper body in Postman → 401, no DB writes (~15 s).
2. Old timestamp → 401, replay protection (~15 s). Don't dwell — point is "both fail-paths are wired, no DB side-effect".

### Demo D1 — Tenant isolation (0.5 min) `[Iso]`
1. POST as `partner-globex` (`ShipmentStatusUpdated`) — quick.
2. `GET /api/v1/events` as `partner-acme` → only ACME's events visible.
3. One-line punchline: *"Server-side from auth context, not request params. ACME can't read Globex's data even with a forged `partnerId` query param."*

### Demo D2 — Querying: pagination + dynamic filters (2.5 min) `[Maint]` (FR-5) ← NEW
This demo directly addresses **Functional Requirement 5** (pagination, configurable page size, dynamic filtering, extensibility). Hit the internal endpoint so you can show *all* filters in one place.

Run these in sequence — Postman or `curl`:

1. **Pagination + page size:**
   ```
   GET /api/v1/internal/events?page=0&size=2
   GET /api/v1/internal/events?page=1&size=2
   ```
   Point at `totalItems`, `totalPages`, and the page navigation. *"Configurable page size, configurable page index."*

2. **Filter by event type + status:**
   ```
   GET /api/v1/internal/events?eventType=OrderCreated&status=PROCESSED
   ```
   *"Two of the case-required filters composing cleanly."*

3. **Filter by date interval + partner:**
   ```
   GET /api/v1/internal/events?partnerId=partner-acme&from=2026-05-09T00:00:00Z&to=2026-05-09T23:59:59Z
   ```
   *"Date-bound queries. The `from`/`to` filter is also what enables Postgres partition pruning — the planner skips partitions outside the range."*

4. **Business reference filter (case-required, easy to miss):**
   ```
   GET /api/v1/internal/events?businessRef=ORDER-12345
   ```
   *"Business reference identifier is a first-class filter — every event carries one, indexed."*

5. **Extensibility one-liner — flip to the IDE for 15 sec:**
   - Open `query/EventSpecifications.java`. Show the `SPECS` registry list.
   - *"Adding a new filter is two lines: add a field to `EventQuery`, add an entry to this registry. The repository builds the WHERE clause from this list — no controller, no SQL string concatenation, no risk of injection."*

### Demo E — Metrics (0.5 min) `[Rel][A&P]`
- One sentence on FAILED: *"Already covered in Seg 3b §5 — `read_ct >= 5` → `pgmq.archive` + FAILED + audit row. End-to-end test is `ProcessingFailureIT`. Don't trigger live (~30 s for retry walk)."* Don't re-narrate.
- Open `http://localhost:8080/actuator/prometheus`, grep `peg_consumer_processed_total | peg_consumer_failed_total | peg_queue_length`. *"Queue length is what KEDA scales on in Stage 2."*

---

## Segment 5 — Availability & Performance, Maintainability, trade-offs, NFR matrix (5:30, 0:19–0:24:30)

Re-titled to be NFR-led: Seg 3 + Seg 4 already covered Security, Tenant Isolation, Reliability, Idempotency, Concurrency, Auditability. Seg 5 closes the remaining two NFRs (Availability & Performance, Maintainability) and lands the trade-offs.

### 5a — Availability & Performance (~2 min) `[A&P]`

Open **`docs/diagrams/06-stage2-topology.md`**.

**HA story (~30 sec):**
> "Stateless API + stateless workers — pod loss never strands work because durable state lives in Postgres only. Same image runs all seven runtime modes via `APP_RUNTIME_MODE`. `HikariPoolHealthIndicator` returns DEGRADED, not DOWN, so pool pressure is observable without flapping readiness probes. Single failure domain at Stage 1 is a deliberate boundary — production HA = managed Postgres + read replica + automated failover; cross-region DR is out of scope per case."

**Growing traffic (~60 sec — the 2K TPS story):**
> "Stage 1 single pod tops out around 400 msg/s. 2K TPS is a multi-pod target: per-event-type Deployments, KEDA on `pgmq.metrics().queue_length`, PgBouncer multiplexes ~350 client connections to 50–80 backends so consumer pod count doesn't exhaust Postgres. Sizing in the doc — 5 pods on `OrderCreated`, smaller on the rest, ~14 consumer pods + 3 API pods → ~2130 msg/s. KEDA `targetQueryValue` is per-queue: 500 backlog/pod for high-volume types, 50 for `OrderCancelled` so latency-sensitive types scale aggressively. **Cost goes where load goes.**"

> "If a single queue still saturates at 5+ pods (per-heap B-tree contention), shard *that one* by `hash(partner_id) % N` — partner-id key keeps all shards hot in parallel and preserves per-tenant ordering. Eventually swap that one to Kafka via lever #19; the outbox (ADR-013) is the seam that keeps the swap cheap — only `OutboxPoller.sendToPgmq` rewrites, ingest path unchanged."

**Efficient reads/writes (~30 sec):**
> "Monthly partitions on `events` and `event_audit_log`, daily on pgmq queues — date-bounded queries skip cold partitions automatically; internal endpoints default to last 90 days when `from`/`to` aren't supplied. Indexes lead with selectivity, end with `created_at` for partition pruning. Work-conserving consume loop: 20 ms busy-poll on full batches, 500 ms idle when partial. Read replica is opt-in via `REPLICA_DB_URL` — cross-partner reads route there, writes always primary. `EventSpecifications` compiles bound-param SQL — no concat, no injection surface."

### 5b — Maintainability (~1.5 min) `[Maint]`

File-count answers, no IDE flip needed unless asked:

| Add a... | Touches |
|---|---|
| **New event type** | 5 small files: `EventType` enum + Flyway pgmq migration + `PgmqWorker` subclass + `app.consumer.concurrency` entry + handler. **`ingest/` unchanged.** |
| **New filter** | 2 lines: field on `EventQuery` + entry in `EventSpecifications.SPECS`. (Already shown live in Demo D2.) |
| **New runtime mode** | 1 enum value + 1 switch arm in `RuntimeProperties` + 1 Deployment manifest. |
| **Stage 1 → Stage 2** | One env var (`APP_RUNTIME_MODE`). Same image, schema, migrations, metrics, API contract. **No code change.** |
| **Broker swap (Kafka)** | Rewrite `OutboxPoller.sendToPgmq` only. `event_outbox` schema and ingest path zero pgmq dependency (ADR-013). The outbox's *table* is the durable seam — the *poller* is throwaway code. |

> "And the audit history of any event is one call — `AuditLogger.historyFor(partnerId, eventId)`."

### 5c — Open design decisions matrix (~1.5 min, NEW v2.3)

> *"Same order as case PDF § Open for Your Design Decisions — six items, choice → where addressed → defended trade-off."*

| # | Case Open item | Choice | Where addressed in this talk | Defended trade-off |
|---|---|---|---|---|
| 1 | **Monolith vs microservice** | Modular monolith, 7 feature modules, same image / 7 runtime modes (ADR-005) | Seg 2 module list; Seg 5b Maintainability | Stage 1 single-image deploy ripples; Stage 2 per-role Deployments roll independently |
| 2 | **Storage choice** | Postgres only — events + audit + outbox + pgmq queues (ADR-010) | Seg 2 ERD; Seg 3 §4 one-tx; Seg 3b consume | Vendor lock-in embraced — `SKIP LOCKED` + JSONB + pgmq + declarative partitioning carry their weight |
| 3 | **Async processing strategy** | Outbox → poller (250 ms) → 5 pgmq queues → VT workers + Semaphore (ADR-002, 003, 004) | Seg 3 §5 outbox; Seg 3b consume walkthrough | +250 ms forwarding latency; 5× ops surface; Java 21+ requirement |
| 4 | **Retry / error handling** | Resilience4j (in-process, 3 × 200 ms + breaker) composed with pgmq redelivery (durable, VT 30 s, `read_ct >= 5` → DLQ) (ADR-009) | Seg 3b §5 recovery; Demo E metrics | Up to 3 × 5 = 15 attempts before DLQ; mitigated by 4xx exclusion + tight in-process budget |
| 5 | **Observability** | 3 pillars / one trace_id; always-on context, opt-in OTLP export; `TraceContextCarrier` bridges async boundary | Demo A trace_id MDC; Demo E `peg_*` metrics; `/actuator/prometheus` | No log-derived metrics (Loki count queries) — Micrometer counters are the durable interface; no dashboards in repo |
| 6 | **Deployment / scaling** | Same image / 7 runtime modes (ADR-005); Stage 1 single pod / Stage 2 per-role + KEDA postgres scaler + PgBouncer tx-mode | Seg 5a Availability & Performance | Stage 2 manifests not deployed (per case spec — *"explain how the solution could support"*); cross-region OOS |

> *"All six share four cross-cutting principles: **diagnose first, then pick cheapest lever**; **Postgres-native primitives over distributed-systems infra** when DB scale allows; **two short tx, not one long one** (connections held only across SQL); **explicit binding over magic** (`partner_id` bound on every repo call, no AOP). Verbose: `01-architecture.md § Open design decisions` + `§ Design principles`. Deeper scaling rationale: `08-scaling-and-tradeoffs.md` (5-layer diagnostic + 20-lever ladder) — don't open unless asked."*

### 5d — NFR coverage matrix (~30 sec, closing recap)

> "Same order as the case spec — every NFR wired and shown today, here's the audit trail."

| # | NFR (case PDF order) | Where shown | Mechanism |
|---|---|---|---|
| 1 | **Security** | Seg 3 §1, Demo C | HMAC-SHA256 + `SHA-256(secret)` + ±5 min skew + constant-time + rotation |
| 2 | **Tenant Isolation** | Seg 3 §2, Demo D1 | `partner_id` from auth context; unique includes `partner_id`; explicit binding (ADR-007) |
| 3 | **Reliability** | Seg 3 §4–§5, Demo A, Demo E | Outbox tx + pgmq VT redelivery + DLQ + Resilience4j + graceful shutdown + logged pgmq |
| 4 | **Idempotency** | Seg 3 §3, Demo B | Unique constraint + `ON CONFLICT DO NOTHING` + worker `tryMarkProcessing` skip |
| 5 | **Concurrency** | Seg 3 §3–§4, state-machine ref | `SKIP LOCKED` + atomic `UPDATE … WHERE status IN (...)` + Semaphore-bounded VTs |
| 6 | **Availability & Performance** | Seg 5a, Demo E metrics | Stateless pods + KEDA + Stage 2 sizing + partition pruning + read replica + L5 shard |
| 7 | **Maintainability** | Seg 5b, Demo D2 registry flip | Feature modules + 5-line filter add + 1-env-var stage flip + ADR-013 broker seam |
| — | **Auditability** (FR-6) | Seg 2 ERD, Demo B SQL | Append-only `event_audit_log`, atomic with op, 24-mo retention > events 12-mo |

> *"That's the full coverage. Happy to drill into any row."*

---

## Segment 6 — Assumptions, limits, what's missing, what I'd do next (2 min, 0:24:30–0:26:30)

Be upfront. Interviewers respect honesty here far more than coverage.

**Say:**
> "**Time spent:** roughly X days end-to-end *(fill in your actual number)* — most of it on the ingest atomicity story and the Stage-2 scaling doc, not on the coding itself.
>
> **Assumptions worth flagging:**
> 1. Internal user authentication is out of scope per the case spec — the internal endpoint is unauthenticated in this build, would sit behind mTLS or an internal IdP in production.
> 2. The downstream system is mocked — Resilience4j retry/circuit-breaker scaffolding is real, the call itself is a stub.
> 3. Partner secrets are bootstrapped via Flyway seed for the demo — production would source them from a secret manager.
>
> **Three things deliberately not in the repo, called out in the README:**
> 1. Stage 2 k8s manifests — described in `06-stage2-topology.md`, not committed.
> 2. Cold-tier S3 archival job — partitions detach, but the `pg_dump`/ship script isn't there.
> 3. Per-partner rate limiting — would slot into `PartnerAuthFilter` as a Caffeine token bucket; called out as lever #8."

---

## Q&A buffer (3.5 min, 0:26:30–0:30)

The matrix card in Seg 5d closed the structured walk. Q&A is shorter (30 sec ceded to the matrix) but the matrix itself preempts the most common "did you cover X?" questions.

Pre-rehearsed answers:

| Likely question | Your one-line answer |
|---|---|
| Why pgmq and not Kafka/RabbitMQ? `[A&P]` | "At case scale, pgmq lets the events insert and queue insert share one transaction in one DB. Kafka becomes lever #19 when a single queue saturates per-heap contention." |
| Why outbox if pgmq is in the same DB? `[Rel][Maint]` | (See ADR-002 talking points above.) |
| Why `create_partitioned` and not `create_unlogged` for the pgmq queues? `[Rel]` | "Unlogged tables get TRUNCATEd by Postgres on crash recovery. After the outbox row is deleted on `pgmq.send` (ADR-006), the queue table is the only copy of an in-flight message — and the archive table (DLQ) inherits the same setting. Unlogged would silently break at-least-once and wipe forensics exactly when you need them. The trade-off — WAL fsync per `pgmq.send` — is hidden behind the 250 ms outbox poll cadence anyway. Documented in ARCHITECTURE.md §4 'Why logged pgmq tables'." |
| What if Postgres goes down? `[A&P][Rel]` | "Single failure domain by design at case scale. HA = managed Postgres + read replica (already wired opt-in via `REPLICA_DB_URL`); cross-region is out of scope." |
| How do you handle poison messages? `[Rel]` | "`read_ct >= maxAttempts` (default 5) → `pgmq.archive` + events row marked FAILED. DLQ is the pgmq archive table; replay = re-insert into outbox." |
| How does ordering work? `[Conc][A&P]` | "Per-event-type queues, no in-queue ordering guarantee. Per-partner ordering: shard by `hash(partner_id) % N`, covered in `06-stage2-topology.md`." |
| Why virtual threads? `[Conc][A&P]` | "Handler is I/O-bound. VT parks on I/O without burning a carrier. Semaphore caps logical concurrency at the DB connection budget — VTs are cheap, connections aren't. pgjdbc 42.7.2+ and Hikari 5.1.0+ moved off `synchronized` so no pinning." |
| How is tenant isolation enforced concretely? `[Iso]` | "Partner from auth context, never request params. Unique constraint includes `partner_id`. Explicit binding per repo method — no AOP / `@TenantId` because pgmq forces JDBC and adding JPA *just* for tenant filtering is hybrid-stack waste (ADR-007). Cross-partner reads are deliberate, scoped to `/api/v1/internal/**`. Demoed in D1." |
| Concurrency — race conditions? `[Conc]` | "`SKIP LOCKED` for inter-process coordination; atomic `UPDATE … WHERE status IN (...)` for state transitions, no read-then-write anywhere. Ingest race: `INSERT … ON CONFLICT DO NOTHING` is the backstop, loser re-SELECTs the winner." |
| HA story if a consumer pod dies mid-message? `[Rel]` | "pgmq VT (30 s) → message reappears → next worker reclaims via `PROCESSING → PROCESSING` rule on `tryMarkProcessing`. Two-tx claim/finalize is what makes that safe; finalize tx never ran on the dead pod, so the row is still in `PROCESSING` for the redelivery to take." |
| How is a new filter added? `[Maint]` | "Field on `EventQuery`, entry in the `EventSpecifications.SPECS` registry. Demoed in Demo D2." |
| Test coverage? | "JaCoCo-enforced ≥ 80% line coverage. Testcontainers for full submit→outbox→pgmq→consume flow, HMAC reject paths, tenant isolation, DLQ, audit atomicity, **and the FAILED lifecycle path**." |

---

## Tactical tips (unchanged)

1. **Show diagrams, not code, for architecture questions.** Drop into code only when asked something specific — except the `EventSpecifications` registry, which earns its 15 sec in Demo D2.
2. **Have the Postman demo pre-signed and pre-warmed.** Cold app startup in front of an interviewer kills momentum.
3. **Pre-seed data** before the call — Demo D2 needs filterable rows.
4. **If something fails live**, say so, don't hide it.
5. **Use the exact phrase "trade-off" 3+ times.** Signals you understand engineering ≠ "best".
6. **Don't read your own ADRs.** Reference them and move on.

If they cut you short, the ranked priorities are: **Seg 1 (framing) > Seg 4 Demo A+B+D2 > Seg 5d (NFR matrix) + Seg 5c (Open decisions matrix) [paired closing recap] > Seg 3b (consume walk — 4 NFRs in 90 s) > Seg 5a/b > everything else**. The two matrix cards together prove dual coverage (NFRs + Open design decisions) in <2 min — the highest-leverage minute of the talk. **Don't skip 5c or 5d even if 5a/5b have to be trimmed.**

---

## What changed from v1 → v2 (cheat-sheet)

| Change | Why | Where |
|---|---|---|
| Five event types named | Functional Req — Supported Event Types | Seg 1 + Demo A + Demo D1 |
| ERD walkthrough (30 sec) | Deliverable 5 — data model | Seg 2 |
| Validation beat (request + event type) | Functional Req 2 — "validate the request / validate the event type" | Seg 3 §2 |
| Demo D split into D1 (isolation) + D2 (querying) | Functional Req 5 — pagination, page size, dynamic filtering, extensibility | Seg 4 |
| Business reference filter demoed | Case spec lists it as a required filter | Demo D2 |
| Filter extensibility shown via `SPECS` registry | Case spec — "design should be extensible so that new filtering criteria can be added later" | Demo D2 |
| FAILED lifecycle stated explicitly | Functional Req 3 — "failed during processing" | Demo E + Q&A |
| Time spent + assumptions called out | README deliverable | Seg 6 |
| Demo budget 8 → 9 min, Q&A 5 → 4 min | Make room for D2 without overrun | Time budget |

## What changed from v2 → v2.1 (NFR coverage cheat-sheet)

| Change | Why | Where |
|---|---|---|
| Seg 1 names case-PDF 7 NFRs verbatim + adds 8-anchor mechanism map | Vocabulary alignment with case spec; previously distilled to 5 candidate-chosen concerns | Seg 1 |
| `[NFR]` tags on every beat (`[Sec][Iso][Rel][Idem][Conc][A&P][Maint][Aud]`) | Explicit signposting — interviewer sees coverage without inferring | Seg 2, Seg 3, Seg 4 demos |
| Seg 5 reframed: 5a Availability & Performance + 5b Maintainability + 5c trade-offs | Two NFRs not naturally covered in Seg 3/4 get their own beat; trade-offs frame as defending NFR choices | Seg 5a, 5b, 5c |
| **Seg 5d NFR coverage matrix card** added (~30 sec) | Closing recap proves all 7 NFRs + Auditability covered, in case-PDF order. Single-page audit trail. | Seg 5d (new) |
| Q&A rows tagged with `[NFR]`; 3 new NFR-anchored rows added | Fast scan when interviewer drills; no NFR left orphaned in Q&A | Q&A buffer |
| Q&A budget 4:00 → 3:30 to absorb matrix card | Matrix card is higher leverage than the marginal 30 sec of Q&A buffer | Time budget |
| Cross-link from Seg 1 footer to `01-architecture.md § NFR coverage` | Verbose talking points one click away if interviewer drills before Seg 5 lands | Seg 1 footer |

## What changed from v2.1 → v2.2 (consume walkthrough cheat-sheet)

| Change | Why | Where |
|---|---|---|
| **Seg 3b consume-sequence walkthrough added (1.5 min)** — five points across pgmq.read claim, two-tx claim/finalize, handler-outside-tx, structural recovery via VT redelivery + PROCESSING-or-PROCESSING rule | Consume path was previously covered only piecewise (Demo A outcome, Demo E narrative, Q&A items). One coherent walkthrough lets a single beat land 4 NFRs (`[Rel][Conc][Idem][Aud]`) — higher leverage per 90 sec than any Seg 5 sub-beat | Seg 3b (new), 0:10–0:11:30 |
| `04-consume-sequence.md` added to pre-talk browser tabs list | Was the small omission flagged when reviewing v2.1 — Seg 3b can't open it from a fumble | Pre-talk checklist |
| Demo C compressed 1:00 → 0:30 (HMAC reject) | Both tamper + replay are ~15 s each; the punchline is "both fail-paths wired, no DB side-effect", not the steps | Seg 4 |
| Demo D1 compressed 1:00 → 0:30 (Tenant isolation) | The *concept* is the punchline ("server-side from auth context"), not the click-through | Seg 4 |
| Demo E reduced 1:00 → 0:30 — FAILED narrative absorbed by Seg 3b §5; metrics-only beat | Seg 3b is now the single source of truth for the consume-path failure story; Demo E doesn't need to re-narrate | Seg 4 |
| Seg 4 demo budget 9:00 → 7:30; Seg 4 starts at 0:11:30 instead of 0:10 | Funds Seg 3b within the 30-min total | Time budget |
| "If cut short" priority list adds `Seg 3b consume walk` between matrix and 5a/b/c | Seg 3b's per-second NFR yield (4 NFRs / 90 s) ranks above any single Seg 5 sub-beat | Tactical tips |

## What changed from v2.2 → v2.3 (Open design decisions cheat-sheet)

| Change | Why | Where |
|---|---|---|
| **Seg 5c reframed: "Three trade-offs to defend" → "Open design decisions matrix"** — single 6-row table covering case-PDF Open items in order (Monolith vs microservice · Storage · Async · Retry · Observability · Deployment) with **choice → where addressed in this talk → defended trade-off** | Case PDF § Open for Your Design Decisions explicitly asks the candidate to address each. Coverage was implicit (most via Seg 2/3/3b/5a/Demo E); now interview-facing and proves coverage in one card | Seg 5c |
| **Trade-off content preserved** in the matrix's *Defended trade-off* column — Outbox-vs-direct, Postgres-only, JdbcTemplate-no-JPA, per-event-type queues, retry-budget, observability — all fold into the new 6-row table | The old prose-form 5-row trade-offs table folded directly; SHA-256-not-KMS lives in Seg 3 §1 HMAC beat (Security NFR, not an Open item) | Seg 5c rows 2/3/4/5/6 |
| **Paired closing recap structure**: Seg 5c (Open decisions matrix) + Seg 5d (NFR coverage matrix) sit adjacent | Mirrors case PDF's two-section structure (§ Open for Your Design Decisions + § Non-Functional Expectations); together prove dual coverage in <2 min | Seg 5c → Seg 5d |
| **Seg 1 footer adds parallel cross-link** to `01-architecture.md § Open design decisions` | Matches the existing NFR coverage cross-link; verbose talking points one click away if interviewer drills *why* before Seg 5c lands | Seg 1 footer |
| "If cut short" priority list reframes closer as `5d + 5c` paired matrices | The two cards together are <2 min and prove coverage of both case-PDF design sections — highest-leverage minute of the talk | Tactical tips |
| **No demo cut** — swap-not-add design preserves 7:30 demo budget | Matrix's trade-off column subsumes the old prose-form 5c content; user's "cut if needed" was conditional and the swap removes the need | Time budget |
