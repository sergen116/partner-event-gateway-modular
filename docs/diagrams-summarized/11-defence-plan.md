# Defence Plan — Summary

30-min interview script. Condensed companion to `DEFENCE_PLAN_V2.md` (long-form source).

## Pre-talk checklist (5 min before)
- DB up, app up (`docker compose up -d postgres && ./mvnw spring-boot:run`).
- psql session ready.
- Browser tabs in order: case PDF, `01-architecture` (NFR talking points cheat-sheet), `02-system-overview`, `03-erd`, `04-ingest-sequence`, `05-consume-sequence` (NEW v2.2 — for Seg 3b), `06-state-machine`, `07-stage2-topology`, swagger-ui, `/actuator/prometheus`.
- Postman: collection open, partner-acme env, one request **pre-signed**.
- **Pre-seed ≥6 events across both partners and ≥3 event types** so Demo D2 filters return non-empty.

## 6 segments + Seg 3b — total 30 min (5d matrix card slotted into Seg 5; Seg 3b consume walk added in v2.2)

Layout: Seg 1 (3) · Seg 2 (3) · Seg 3 (4) · **Seg 3b (1:30)** · Seg 4 (7:30) · Seg 5 (5:30) · Seg 6 (2) · Q&A (3:30) = 30:00.

### Seg 1 (3m) — Frame the problem
Name the case PDF's **7 NFRs verbatim**: **Security · Tenant Isolation · Reliability · Idempotency · Concurrency · Availability & Performance · Maintainability** — plus FR-6 **Auditability** as cross-cutting. Every decision lands on one or more.

**Mechanism map** (15 sec, half-speed): "HMAC + hashed secret · `partner_id` from auth context · outbox + DLQ + breaker · unique constraint + `ON CONFLICT` · `SKIP LOCKED` + atomic `UPDATE` · per-event-type queues + KEDA · feature modules + 5-line filter registry · append-only audit log atomic with each op."

Five event types: **OrderCreated, ShipmentStatusUpdated, ReturnRequested, DeliveryAddressUpdated, OrderCancelled**.

Walk system overview left→right (90 sec) — topology only, not code.

> **Verbose NFR talking points**: `01-architecture.md § NFR coverage` — same case-PDF order. Jump there if interviewer drills before Seg 5.
>
> **Verbose Open design decisions talking points**: `01-architecture.md § Open design decisions` — six items × choice → why → trade-off. Closing recap card lives at Seg 5c.

### Seg 2 (3m) — Module layout, ERD, monolith choice
- Show feature modules (`ingest, delivery, query, audit, partner, platform, shared`).
- Acyclic, documented in `package-info.java`.
- Same image, two shapes via `APP_RUNTIME_MODE`. **No code change between Stage 1 & 2.** `[Maint]`
- 30 sec on ERD: `events` partitioned monthly, **unique on `(partner_id, event_id, created_at)` `[Idem][Iso]`**, **audit one-row-per-transition same tx `[Aud][Rel]`**, **outbox staging — broker-agnostic seam `[Rel][Maint]`**.

### Seg 3 (4m) — Walk ingest sequence diagram
Land 5 points:
1. **HMAC** `[Sec]`: `SHA-256(secret)` derivation, ±5 min window, constant-time compare, rotation field. (ADR-001)
2. **Validation** `[Sec][Iso]`: `@Valid` on DTO, `EventType` enum, `partner_id` from auth context not body — partner can't spoof another's id.
3. **Idempotency** `[Idem][Conc]`: SELECT first, INSERT ON CONFLICT, re-SELECT on race loss.
4. **One transaction** `[Rel][Aud]`: events + audit + outbox atomic. 200 OK before pgmq touched.
5. **Outbox decoupling** `[Rel][Maint]`: NOT for atomicity (same DB). Three reasons: future-proofing for Kafka, shorter ingest tx, ingest stays queue-agnostic. Cost: +250ms median. **(ADR-002 — strongest design call.)**

### Seg 3b (1.5m) — Walk consume sequence diagram
Open `05-consume-sequence.md`. 5 points (~18s each). Tags: `[Rel][Conc][Idem][Aud]` — single beat, four NFRs.
1. `pgmq.read(vt=30s)` claim → fan out across VTs bounded by Semaphore (ADR-004 — VTs cheap, connections not; pgjdbc 42.7.2+ + Hikari 5.1.0+ park, don't pin).
2. **Tx 1 (claim)**: `tryMarkProcessing` = `UPDATE … WHERE status IN ('PENDING','PROCESSING')`. Atomic, commits, **releases the connection**. One predicate handles claim + post-PROCESSED redelivery.
3. **Handler runs OUTSIDE any DB tx** — including downstream HTTP. *"Two short tx, not one long one — that's why connection budget survives load."*
4. **Tx 2 (finalize)**: PROCESSED + audit row, atomic. Then `pgmq.delete`. Audit atomic with op write.
5. **Recovery is structural**: handler crash → row stays PROCESSING → pgmq VT redelivers → next worker reclaims via **PROCESSING-or-PROCESSING rule**. `read_ct >= 5` → `pgmq.archive` + FAILED. **No app locks, no read-then-write.**

Backup (only if drilled): batch deadline = `orTimeout(VT-5s)` — don't cancel in-flight; work-conserving loop (20ms busy / 500ms idle).

### Seg 4 (7.5m) — Live demo (most candidates botch)
- **Demo A (1.5m) Happy path** `[Rel][Aud]`: POST as ACME OrderCreated → 200 RECEIVED → GET → PROCESSED → show `trace_id` MDC.
- **Demo B (2m) Idempotency + auditability** `[Idem][Aud]`: re-send same `Idempotency-Key` → 200 `duplicate=true`. SQL: one events row, full audit trail.
- **Demo C (0.5m) HMAC reject** `[Sec]`: tamper body → 401, no DB writes (~15s). Old timestamp → 401 replay (~15s). Don't dwell.
- **Demo D1 (0.5m) Tenant isolation** `[Iso]`: POST as Globex; GET as ACME → only ACME's events. One-line punchline: *"Server-side from auth context, not request params."*
- **Demo D2 (2.5m) Querying** `[Maint]` (FR-5): pagination (`page=0&size=2`), filter combos (`eventType=OrderCreated&status=PROCESSED`), date interval + partner, `businessRef`. Then 15-sec IDE flip to `EventSpecifications.SPECS` registry: *"Adding a filter is two lines — field on EventQuery + entry in registry. No SQL concatenation, no injection risk."*
- **Demo E (0.5m) Metrics only** `[Rel][A&P]`: FAILED already covered in Seg 3b §5 + `ProcessingFailureIT`; don't re-narrate. Open `/actuator/prometheus`, grep `peg_consumer_processed_total | peg_consumer_failed_total | peg_queue_length`. *"Queue length = KEDA input."*

### Seg 5 (5:30) — Availability & Performance, Maintainability, trade-offs, NFR matrix
Re-titled NFR-led: Seg 3+4 already covered Sec/Iso/Rel/Idem/Conc/Aud. Seg 5 closes the remaining two NFRs and lands the trade-offs + matrix.

#### 5a — Availability & Performance (~2 min) `[A&P]`
- **HA**: stateless API + workers, durable state in Postgres only. Same image, 7 runtime modes. Hikari **DEGRADED ≠ DOWN**. Single failure domain at Stage 1 — managed PG + replica + automated failover in prod; cross-region out of scope.
- **Growing traffic (2K TPS story)**: Stage 1 single pod ~400 msg/s; Stage 2 = 14 consumer pods + 3 API pods → ~2130 msg/s. KEDA on `pgmq.queue_length`, per-queue `targetQueryValue` (500 high-volume, 50 latency-sensitive). PgBouncer multiplexes ~350 clients → 50–80 backends. **Cost goes where load goes.**
- **L5 escape hatch**: shard saturated queue by `hash(partner_id) % N` — preserves per-tenant ordering, all shards stay hot in parallel. Eventually Kafka via lever #19; outbox (ADR-013) is the seam.
- **Efficient reads/writes**: monthly partitions on events/audit + daily on pgmq → partition pruning; indexes lead with selectivity end with `created_at`; work-conserving consume loop (20ms busy / 500ms idle); read replica opt-in via `REPLICA_DB_URL`; `EventSpecifications` = bound-param SQL, no injection.

#### 5b — Maintainability (~1.5 min) `[Maint]`
| Add a... | Touches |
|---|---|
| New event type | 5 small files: `EventType` enum + Flyway pgmq migration + `PgmqWorker` subclass + `app.consumer.concurrency` entry + handler. **`ingest/` unchanged.** |
| New filter | 2 lines: `EventQuery` field + `EventSpecifications.SPECS` entry. (Already shown in Demo D2.) |
| New runtime mode | 1 enum + 1 switch arm in `RuntimeProperties` + 1 Deployment manifest. |
| Stage 1 → Stage 2 | One env var. Same image, schema, migrations, metrics, API contract. **No code change.** |
| Broker swap (Kafka) | Rewrite `OutboxPoller.sendToPgmq` only. `event_outbox` schema + ingest = zero pgmq dependency (ADR-013). |

Audit history of any event = `AuditLogger.historyFor(partnerId, eventId)`. One call.

#### 5c — Open design decisions matrix (~1.5 min, NEW v2.3)
> *"Same order as case PDF § Open for Your Design Decisions — six items, choice → where addressed → defended trade-off."*

| # | Open item | Choice | Where addressed | Defended trade-off |
|---|---|---|---|---|
| 1 | **Monolith vs microservice** | Modular monolith, 7 modules, same image / 7 runtime modes (ADR-005) | Seg 2 modules; Seg 5b Maint | Stage 1 single-image deploy ripples; Stage 2 splits per-role |
| 2 | **Storage choice** | Postgres only (events + audit + outbox + pgmq) (ADR-010) | Seg 2 ERD; Seg 3 §4; Seg 3b consume | Vendor lock-in embraced — `SKIP LOCKED` + JSONB + pgmq carry weight |
| 3 | **Async processing** | Outbox → 5 pgmq queues → VT workers + Semaphore (ADR-002, 003, 004). **Stage-2 scaling ladder**: more pollers (SKIP LOCKED) → per-type table for hot → `send_batch` unlocks → shard by `hash(partner_id) % N` | Seg 3 §5; Seg 3b consume walk | +250ms latency; 5× ops surface. **ADR-011 + ADR-012 coupled**: per-row send is a consequence of single-table; splitting flips both |
| 4 | **Retry / error handling** | Resilience4j (in-process) + pgmq redelivery (durable) (ADR-009) | Seg 3b §5; Demo E | Up to 3 × 5 = 15 attempts max; mitigated by 4xx exclusion |
| 5 | **Observability** | 3 pillars / one trace_id; always-on context, opt-in OTLP | Demo A trace_id; Demo E `peg_*` metrics; `/actuator/prometheus` | No log-derived metrics; no dashboards in repo |
| 6 | **Deployment / scaling** | Same image / 7 modes (ADR-005). **Consumer-side**: Stage 1 single pod / Stage 2 per-role + KEDA + PgBouncer. **Producer-side outbox ladder** (symmetric with consumer L5): more pollers → per-type table for hot → `send_batch` unlocks → shard by `hash(partner_id) % N` → Kafka per-topic batching (ADR-013) | Seg 5a A&P; `01-architecture.md § 6` | Stage 2 manifests not deployed; cross-region OOS |

> *"Cross-cutting principles: diagnose first; Postgres-native primitives over distributed-systems infra; two short tx not one long; explicit binding over magic. Verbose: `01-architecture.md § Open design decisions` + `§ Design principles`."*

#### 5d — NFR coverage matrix (~30 sec, closing recap)
> *"Same order as the case spec — every NFR wired and shown today; here's the audit trail."*

| # | NFR | Where shown | Mechanism |
|---|---|---|---|
| 1 | **Security** | Seg 3 §1, Demo C | HMAC-SHA256 + `SHA-256(secret)` + ±5min skew + constant-time + rotation |
| 2 | **Tenant Isolation** | Seg 3 §2, Demo D1 | `partner_id` from auth context; unique includes `partner_id`; explicit binding (ADR-007) |
| 3 | **Reliability** | Seg 3 §4–§5, Demo A, Demo E | Outbox tx + pgmq VT redelivery + DLQ + Resilience4j + graceful shutdown + logged pgmq |
| 4 | **Idempotency** | Seg 3 §3, Demo B | Unique constraint + `ON CONFLICT DO NOTHING` + worker `tryMarkProcessing` skip |
| 5 | **Concurrency** | Seg 3 §3–§4 | `SKIP LOCKED` + atomic `UPDATE … WHERE status IN (...)` + Semaphore-bounded VTs |
| 6 | **Availability & Performance** | Seg 5a, Demo E metrics | Stateless pods + KEDA + Stage 2 sizing + partition pruning + read replica + L5 shard |
| 7 | **Maintainability** | Seg 5b, Demo D2 registry flip | Feature modules + 5-line filter add + 1-env-var stage flip + ADR-013 broker seam |
| — | **Auditability** (FR-6) | Seg 2 ERD, Demo B SQL | Append-only `event_audit_log`, atomic with op, 24-mo retention > events 12-mo |

> *"That's the full coverage. Happy to drill into any row."*

### Seg 6 (2m) — Assumptions, limits, what's next
**Assumptions**:
1. Internal user auth out of scope per case spec → mTLS / IdP in prod.
2. Downstream is mocked — Resilience4j scaffolding real, call is stub.
3. Partner secrets via Flyway seed for demo → secret manager in prod.

**Deliberately NOT in repo (called out in README)**:
1. Stage 2 k8s manifests (described in `07-stage2-topology.md`).
2. Cold-tier S3 archival job (partitions detach, ship script absent).
3. Per-partner rate limit (would slot into `PartnerAuthFilter` as Caffeine token bucket = lever #8).

## Q&A buffer (3.5m) — pre-rehearsed (NFR-tagged)
| Q | One-liner |
|---|---|
| Why pgmq not Kafka? `[A&P]` | "Same DB tx for events+queue insert; Kafka becomes lever #19 when one queue saturates per-heap contention." |
| Why outbox if pgmq is same DB? `[Rel][Maint]` | (ADR-002 talking points.) |
| Why `create_partitioned` not `create_unlogged`? `[Rel]` | "Unlogged TRUNCATEd on crash — combined with delete-on-send breaks at-least-once + DLQ. WAL fsync hidden behind 250ms poll cadence anyway." |
| What if Postgres goes down? `[A&P][Rel]` | "Single failure domain by design. HA = managed PG + read replica (opt-in). Cross-region out of scope." |
| Poison messages? `[Rel]` | "`read_ct >= 5` → `pgmq.archive` + FAILED row. Replay = re-INSERT into outbox from archive." |
| Ordering? `[Conc][A&P]` | "No in-queue ordering. Per-partner ordering: `hash(partner_id) % N` shard." |
| Why VTs? `[Conc][A&P]` | "Handler is I/O-bound. VT parks without burning carrier. Semaphore caps logical concurrency at DB connection budget — VTs cheap, connections not. pgjdbc 42.7.2+ + Hikari 5.1.0+ moved off `synchronized` so no pinning." |
| How is tenant isolation enforced? `[Iso]` | "Partner from auth context, never request params. Unique includes `partner_id`. Explicit binding per repo method — no AOP/`@TenantId`; pgmq forces JDBC and JPA *just* for tenant filtering = hybrid-stack waste (ADR-007). Demo D1." |
| Concurrency — race conditions? `[Conc]` | "`SKIP LOCKED` inter-process; atomic `UPDATE … WHERE status IN (...)` for transitions, no read-then-write. Ingest race: `ON CONFLICT DO NOTHING` + re-SELECT." |
| HA if consumer pod dies mid-message? `[Rel]` | "pgmq VT (30s) → message reappears → next worker reclaims via `PROCESSING → PROCESSING` rule on `tryMarkProcessing`. Two-tx claim/finalize makes that safe." |
| New filter? `[Maint]` | "Field on EventQuery + entry in `SPECS` registry (Demo D2)." |
| Why per-row `pgmq.send` not `send_batch`? `[A&P][Maint]` | "Outbox spans multiple queue destinations → `send_batch` needs per-queue grouping + flush buffer in poller (Kafka accumulator pattern). Per-row keeps loop flat (ADR-011). **Coupled to ADR-012**: split hot type into `event_outbox_<hot>` → every row same destination → `send_batch` unlocks free. One step flips both. Full ladder: `01-architecture.md § 6 Outbox structure scaling path`." |
| How does the outbox scale? `[A&P]` | "4-step ladder: (1) more API pollers [SKIP LOCKED, wired] → (2) per-type table for hot → (3) `send_batch` unlocks free at step 2 → (4) shard hot per-type by `hash(partner_id) % N`. Future: Kafka producer accumulator handles per-topic batching natively (ADR-013)." |
| Test coverage? | "JaCoCo ≥80% line. Testcontainers full submit→outbox→pgmq→consume, HMAC reject, tenant isolation, DLQ, audit atomicity, FAILED lifecycle." |

## Tactical tips
1. Show **diagrams not code** for architecture questions. Drop into code only when asked (and the `EventSpecifications.SPECS` registry).
2. Pre-signed Postman, pre-warmed app — cold startup kills momentum.
3. Pre-seed data — Demo D2 needs filterable rows.
4. If something fails live, **say so**, don't hide.
5. Use the phrase "trade-off" 3+ times — signals you understand engineering ≠ "best".
6. **Don't read your own ADRs.** Reference and move on.
7. **The matrix card (5d) is the highest-leverage 30 sec.** Single-page proof of full NFR coverage. Don't skip it even if Seg 5a/b run long — trim 5a's Stage 1 sizing detail before sacrificing the matrix.

If cut short, priority: **Seg 1 (framing) > Seg 4 Demo A+B+D2 > Seg 5d (NFR matrix) + Seg 5c (Open decisions matrix) [paired closer, <2 min, dual coverage] > Seg 3b consume walk (4 NFRs in 90s) > Seg 5a/b > everything else.** **Don't skip 5c or 5d** even if 5a/5b have to be trimmed — the two cards are the highest-leverage minute of the talk.
