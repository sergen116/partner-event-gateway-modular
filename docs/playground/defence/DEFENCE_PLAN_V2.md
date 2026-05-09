# 30-Minute Case Defence Plan v2 — Partner Event Gateway

Revised from `DEFENCE_PLAN.md` to close the coverage gaps identified in `DEFENCE_PLAN_AUDIT.md`. The structure is the same six segments; the changes are:

- Seg 1 names the **five event types** explicitly (Functional Req — Supported Event Types).
- Seg 2 adds a **30-second ERD walkthrough** (Deliverable 5 — data model).
- Seg 3 adds explicit **request / event-type validation** beat (Functional Req 2).
- Seg 4 demo budget is reshuffled: Demo D is split into **D1 (isolation)** + **D2 (querying with pagination + filters)** — this is the biggest gap (Functional Req 5).
- Seg 4 calls out the **FAILED lifecycle state** explicitly via state-machine diagram + integration test (Functional Req 3 — "failed during processing").
- Seg 6 covers **assumptions and time spent** as required by the README deliverable.

Total time still 30 min; demo budget grows from 8 → 9 min by trimming 1 min from Q&A buffer.

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
# 3. docs/diagrams/02-erd.md                       (NEW — for Seg 2)
# 4. docs/diagrams/03-ingest-sequence.md
# 5. docs/diagrams/05-state-machine.md             (NEW — for FAILED beat)
# 6. docs/diagrams/06-stage2-topology.md
# 7. http://localhost:8080/swagger-ui/index.html
# 8. http://localhost:8080/actuator/prometheus
# Postman: collection open, partner-acme env selected, one request pre-signed
```

Pre-seed at least **6 events across both partners and at least 3 event types** before the call so Demo D2's filters return non-empty pages without you manually loading data live.

---

## Segment 1 — Framing the problem (3 min, 0:00–0:03)

**Say (~45 sec):**
> "The case is a multi-tenant event gateway for five operational event types — **OrderCreated, ShipmentStatusUpdated, ReturnRequested, DeliveryAddressUpdated, OrderCancelled**. Five non-functional concerns shape every decision: per-tenant isolation, at-least-once with idempotency, independent scaling per event type, full auditability of state transitions, and a production-ready operational surface. Outbox, pgmq, partitioning, virtual threads — they all fall out of those five."

Then point to **`docs/diagrams/01-system-overview.md`** and walk left→right in 90 seconds:
- Partner → `PartnerAuthFilter` (HMAC) → `EventIngestService` (one txn: events + outbox + audit)
- `OutboxPoller` → **5 pgmq queues, one per event type** → 5 workers → `EventProcessor` → downstream
- Internal users → `InternalEventsController` (cross-partner query)

**Don't** dive into code yet. Just topology.

---

## Segment 2 — Module layout, ERD, the modular monolith choice (3 min, 0:03–0:06)

Switch to your IDE, show `src/main/java/com/example/peg/` tree.

**Module talking points (one sentence each):**
- "Packages are by **feature module** — `ingest`, `delivery`, `query`, `audit`, `partner`, `platform`, `shared`."
- "Dependency graph is acyclic and documented per-module in `package-info.java`."
- "Same code, two deployment shapes: Stage 1 = one JVM (`CONSUMER_ALL`), Stage 2 = per-role pods. Stage transition is one env var, **no code change**."

**Then 30 sec on the data model — open `docs/diagrams/02-erd.md`:**
> "Three tables: `events` (partitioned by `created_at`, unique on `(partner_id, event_id, created_at)` for idempotency), `event_audit_log` (one row per state transition, written in the same txn), `event_outbox` (pgmq dispatch staging). Partitioning is on `created_at` because that's the dimension that grows monotonically and is what cold-tier archival keys on."

Open `docs/ARCHITECTURE.md` § 2 only if interviewer asks for the dependency diagram.

---

## Segment 3 — Walk the ingest path with the sequence diagram (4 min, 0:06–0:10)

Open **`docs/diagrams/03-ingest-sequence.md`**. Walk it top to bottom.

**Land these specific points:**
1. **HMAC auth** — `SHA-256(secret)` derivation, ±5 min timestamp window, constant-time compare. Mention secret rotation field. (ADR-001)
2. **Validation** — *"Request schema is bean-validated (`@Valid` on the DTO), event type is validated against the `EventType` enum on deserialization, partner identity comes from the auth context not the body — so a partner cannot spoof another's `partnerId` even by lying in the payload."* (Covers Functional Req 1 + Req 2.)
3. **Idempotency** — `(partner_id, event_id, created_at)` unique. `SELECT` first, then `INSERT ON CONFLICT`, then re-`SELECT` on race loss. Show the diagram's "race lost" branch.
4. **One transaction** — events row + audit row + outbox row. Atomic commit; partner sees 200 OK before pgmq is touched.
5. **Outbox poller decoupling** — *"I deliberately picked outbox over direct `pgmq.send` even though pgmq is in the same DB. Atomicity wasn't the reason — three things were: future-proofing for Kafka, shorter ingest transactions, and keeping the ingest module queue-agnostic. Cost: ~250 ms median forwarding latency."* (ADR-002 — your strongest design call, lead with it if asked.)

---

## Segment 4 — Live demo (9 min, 0:10–0:19) ← the part most candidates botch

Five demos, in this order. Don't improvise.

### Demo A — Happy path (1.5 min)
1. In Postman, send `POST /api/v1/events` with `partner-acme`, event type `OrderCreated`. *(HMAC pre-request script signs it.)*
2. Show 200 OK with `status: RECEIVED, duplicate: false`.
3. Run `GET /api/v1/events/{eventId}` → status now `PROCESSED`.
4. Switch to terminal — show the trace: `RECEIVED → PENDING → PROCESSING → PROCESSED`. Point at the `trace_id` MDC field threading through.

### Demo B — Idempotency + auditability (2 min)
1. Re-send the **exact same** request (same `Idempotency-Key`).
2. Show 200 OK with `duplicate: true` and the original event's current status.
3. **Quick SQL** in psql terminal:
   ```sql
   SELECT event_id, status FROM events WHERE event_id = '<id>';
   SELECT * FROM event_audit_log WHERE event_id = '<id>' ORDER BY occurred_at;
   ```
4. *"One events row, full audit trail. This is the auditability requirement — every transition logged in the same transaction as the operational write."*

### Demo C — HMAC rejection (1 min)
1. Tamper one byte of the body in Postman, resend → 401, no DB rows written.
2. Skip back to original request, edit timestamp to be 10 min old → 401 (replay protection).

### Demo D1 — Tenant isolation (1 min)
1. Send another event as `partner-globex`, event type `ShipmentStatusUpdated`.
2. `GET /api/v1/events` as `partner-acme` → only ACME's events visible.
3. *"Tenant isolation is enforced server-side from auth context, not from query params — there's no way for ACME to read globex's data, even with a forged `partnerId` query param."*

### Demo D2 — Querying: pagination + dynamic filters (2.5 min) ← NEW
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

### Demo E — Lifecycle FAILED + metrics (1 min)
- *"Lifecycle has a fifth state — FAILED — for events whose handler exhausts retries. I'm not triggering it live because it takes ~30 sec to walk through `maxAttempts` retries, but you can see it in the state machine diagram (`05-state-machine.md`) and it's covered end-to-end in `ProcessingFailureIT` — message hits `read_ct >= 5`, gets archived to `pgmq.archive`, events row marked FAILED, audit row written."*
- Open `http://localhost:8080/actuator/prometheus`, grep for `peg_consumer_processed_total`, `peg_consumer_failed_total`, `peg_queue_length`. *"Queue length is what KEDA scales on in Stage 2."*

---

## Segment 5 — Scaling & key trade-offs (5 min, 0:19–0:24)

Open **`docs/diagrams/06-stage2-topology.md`**.

**Say (the 2K TPS story):**
> "Stage 1 single pod tops out around 400 msg/s. 2K TPS is a multi-pod target: split into per-event-type Deployments, KEDA scales each on `pgmq.metrics().queue_length`, PgBouncer fronts Postgres so 14 consumer pods don't exhaust connections. Sizing is in the doc — 5 pods on `OrderCreated`, smaller on the rest, total ~14 consumer pods + 3 API pods."

Then **trade-offs you're ready to defend** (pick 3, briefly):

| Decision | Trade-off accepted | Why |
|---|---|---|
| Outbox vs direct send | +250 ms latency | Future-proofing + ingest stays queue-agnostic |
| `SHA-256(secret)` storage, not KMS | DB leak still exposes HMAC key | Documented simplification; KMS = next step |
| JdbcTemplate, no JPA | Devs reaching for `JpaSpecificationExecutor` need to read the registry | pgmq is JDBC-native; entity graph is trivial; partition pruning needs raw SQL |
| Per-event-type queues | 5× operational surface | Independent scaling required by case spec |
| Postgres for everything | Vendor lock-in | pgmq + partial indexes + `SKIP LOCKED` already lock us in; embrace it |

Reference **`docs/diagrams/07-scaling-and-tradeoffs.md`** as the deeper rationale — don't open it unless asked.

---

## Segment 6 — Assumptions, limits, what's missing, what I'd do next (2 min, 0:24–0:26)

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

## Q&A buffer (4 min, 0:26–0:30)

Pre-rehearsed answers:

| Likely question | Your one-line answer |
|---|---|
| Why pgmq and not Kafka/RabbitMQ? | "At case scale, pgmq lets the events insert and queue insert share one transaction in one DB. Kafka becomes lever #19 when a single queue saturates per-heap contention." |
| Why outbox if pgmq is in the same DB? | (See ADR-002 talking points above.) |
| What if Postgres goes down? | "Single failure domain by design at case scale. HA = managed Postgres + read replica (already wired opt-in via `REPLICA_DB_URL`); cross-region is out of scope." |
| How do you handle poison messages? | "`read_ct >= maxAttempts` (default 5) → `pgmq.archive` + events row marked FAILED. DLQ is the pgmq archive table; replay = re-insert into outbox." |
| How does ordering work? | "Per-event-type queues, no in-queue ordering guarantee. Per-partner ordering: shard by `hash(partner_id) % N`, covered in `06-stage2-topology.md`." |
| Why virtual threads? | "Handler is I/O-bound. VT parks on I/O without burning a carrier. Semaphore caps logical concurrency at the DB connection budget — VTs are cheap, connections aren't." |
| How is a new filter added? | "Field on `EventQuery`, entry in the `EventSpecifications.SPECS` registry. Demoed in Demo D2." |
| Test coverage? | "JaCoCo-enforced ≥ 80% line coverage. Testcontainers for full submit→outbox→pgmq→consume flow, HMAC reject paths, tenant isolation, DLQ, audit atomicity, **and the FAILED lifecycle path**." |

---

## Tactical tips (unchanged)

1. **Show diagrams, not code, for architecture questions.** Drop into code only when asked something specific — except the `EventSpecifications` registry, which earns its 15 sec in Demo D2.
2. **Have the Postman demo pre-signed and pre-warmed.** Cold app startup in front of an interviewer kills momentum.
3. **Pre-seed data** before the call — Demo D2 needs filterable rows.
4. **If something fails live**, say so, don't hide it.
5. **Use the exact phrase "trade-off" 3+ times.** Signals you understand engineering ≠ "best".
6. **Don't read your own ADRs.** Reference them and move on.

If they cut you short, the ranked priorities are: **Seg 1 (framing) > Seg 4 Demo A+B+D2 (the three demos that hit the most case requirements) > Seg 5 (trade-offs) > everything else**.

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
