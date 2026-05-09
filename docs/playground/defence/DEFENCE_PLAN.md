# 30-Minute Case Defence Plan — Partner Event Gateway

The plan is split into **6 segments** that move from "what" → "how" → "why" → "live demo" → "trade-offs" → "Q&A buffer". Stick to the timing — the live demo is where most candidates run over.

## Pre-talk checklist (do 5 min before the call)

```bash
# Terminal 1 — DB up, app up
docker compose up -d postgres
./mvnw spring-boot:run

# Terminal 2 — tail logs in JSON-pretty form
# (already streaming from spring-boot:run window)

# Open in browser tabs (left → right, in this order):
# 1. docs/case/Backend_Case.pdf
# 2. docs/diagrams/01-system-overview.md (rendered)
# 3. docs/diagrams/03-ingest-sequence.md
# 4. docs/diagrams/06-stage2-topology.md
# 5. http://localhost:8080/swagger-ui/index.html
# 6. http://localhost:8080/actuator/prometheus
# Postman: open the partner-event-gateway collection, partner-acme env selected
```

Have one Postman request **already signed and ready** so you don't fumble HMAC live.

---

## Segment 1 — Framing the problem (3 min, 0:00–0:03)

Open with the case spec, not your code. Interviewers want to hear that you read it.

**Say (script, ~45 sec):**
> "The case is a multi-tenant event gateway. Five things shape every decision I made: per-tenant isolation, at-least-once with idempotency, independent scaling per event type, full auditability of state transitions, and a production-ready operational surface. Everything else — outbox, pgmq, partitioning, virtual threads — falls out of those five."

Then point to **`docs/diagrams/01-system-overview.md`** and walk the boxes left→right in 90 seconds:
- Partner → `PartnerAuthFilter` (HMAC) → `EventIngestService` (one txn: events + outbox + audit)
- `OutboxPoller` → 5 pgmq queues → 5 workers → `EventProcessor` → downstream
- Internal users → `InternalEventsController` (cross-partner query)

**Don't** explain code yet. Just the topology.

---

## Segment 2 — Module layout & the "modular monolith" choice (3 min, 0:03–0:06)

Switch to your IDE, show `src/main/java/com/example/peg/` tree.

**Key talking points (one sentence each):**
- "Packages are by **feature module**, not technical layer — `ingest`, `delivery`, `query`, `audit`, `partner`, `platform`, `shared`."
- "Dependency graph is acyclic and documented in each module's `package-info.java`."
- "Same code, two deployment shapes: Stage 1 = one JVM (`CONSUMER_ALL`), Stage 2 = per-role pods. Stage transition is one env var, **no code change**."

Open `docs/ARCHITECTURE.md` § 2 if they want the dependency diagram.

---

## Segment 3 — Walk the ingest path with the sequence diagram (4 min, 0:06–0:10)

Open **`docs/diagrams/03-ingest-sequence.md`**. Walk it top to bottom.

**Land these specific points:**
1. **HMAC auth** — `SHA-256(secret)` derivation, ±5 min timestamp window, constant-time compare. Mention secret rotation field. (ADR-001)
2. **Idempotency** — `(partner_id, event_id, created_at)` unique. `SELECT` first, then `INSERT ON CONFLICT`, then re-`SELECT` on race loss. Show the diagram's "race lost" branch.
3. **One transaction** — events row + audit row + outbox row. Atomic commit; partner sees 200 OK before pgmq is touched.
4. **Outbox poller decoupling** — "I deliberately picked outbox over direct `pgmq.send` even though pgmq is in the same DB. Atomicity wasn't the reason — three things were: future-proofing for Kafka, shorter ingest transactions, and keeping the ingest module queue-agnostic. Cost: ~250 ms median forwarding latency." (ADR-002 — this is your strongest design call, lead with it.)

---

## Segment 4 — Live demo (8 min, 0:10–0:18) ← the part most candidates botch

Three demos, in this order. Don't improvise — run the same flow you've rehearsed.

### Demo A — Happy path (2 min)
1. In Postman, send `POST /api/v1/events` with `partner-acme` (HMAC pre-request script signs it).
2. Show 200 OK with `status: RECEIVED, duplicate: false`.
3. Run `GET /api/v1/events/{eventId}` → status now `PROCESSED`.
4. Switch to terminal — show the trace in logs: `RECEIVED → PENDING → PROCESSING → PROCESSED`. Point at the `trace_id` MDC field threading through.

### Demo B — Idempotency (2 min)
1. Re-send the **exact same** request (same `Idempotency-Key`).
2. Show 200 OK with `duplicate: true` and the original event's current status.
3. **Quick SQL** in a third terminal:
   ```sql
   SELECT event_id, status FROM events WHERE event_id = '<id>';
   SELECT * FROM event_audit_log WHERE event_id = '<id>' ORDER BY occurred_at;
   ```
   Show one events row, full audit trail. *"This is the auditability requirement — every transition logged in the same transaction as the operational write."*

### Demo C — HMAC rejection (1.5 min)
1. Tamper one byte of the body in Postman, resend.
2. Show 401, no DB rows written.
3. Skip-back to original request, edit timestamp to be 10 min old → 401 (replay protection).

### Demo D — Internal cross-partner query (1.5 min)
1. Send another event as `partner-globex`.
2. `GET /api/v1/internal/events` (no auth) → both partners' events visible.
3. `GET /api/v1/events` as `partner-acme` → only ACME's events. *"Tenant isolation is enforced server-side from auth context, not from query params."*

### Demo E — Metrics (1 min, only if time permits)
- Open `http://localhost:8080/actuator/prometheus`, grep for `peg_consumer_processed_total`, `peg_queue_length`. *"This is what KEDA scales on in Stage 2."*

---

## Segment 5 — Scaling & key trade-offs (5 min, 0:18–0:23)

Open **`docs/diagrams/06-stage2-topology.md`**.

**Say (the 2K TPS story):**
> "Stage 1 single pod tops out around 400 msg/s. 2K TPS is a multi-pod target: split into per-event-type Deployments, KEDA scales each on `pgmq.metrics().queue_length`, PgBouncer fronts Postgres so 14 consumer pods don't exhaust connections. Sizing is in the doc — 5 pods on `OrderCreated`, smaller on the rest, total ~14 consumer pods + 3 API pods."

Then pivot to **trade-offs you're ready to defend** (pick 3, briefly):

| Decision | Trade-off you accepted | Why |
|---|---|---|
| Outbox vs direct send | +250 ms latency | Future-proofing + ingest stays queue-agnostic |
| `SHA-256(secret)` storage, not KMS | DB leak still exposes HMAC key | Documented simplification; KMS = next step |
| JdbcTemplate, no JPA | Devs reaching for `JpaSpecificationExecutor` need to read the registry | pgmq is JDBC-native; entity graph is trivial; partition pruning needs raw SQL |
| Per-event-type queues | 5× operational surface | Independent scaling required by case spec |
| Postgres for everything | Vendor lock-in | pgmq + partial indexes + `SKIP LOCKED` already lock us in; embrace it |

Mention **`docs/diagrams/07-scaling-and-tradeoffs.md`** as the deeper rationale — don't open it unless asked.

---

## Segment 6 — Limits, what's missing, what I'd do next (2 min, 0:23–0:25)

Be upfront. Interviewers respect honesty here far more than coverage.

**Say:**
> "Three things are deliberately not in the repo and called out in the README:
> 1. Stage 2 k8s manifests — described in `06-stage2-topology.md`, not committed.
> 2. Cold-tier S3 archival job — partitions detach, but the `pg_dump`/ship script isn't there.
> 3. Per-partner rate limiting — would slot into `PartnerAuthFilter` as a Caffeine token bucket; called out as lever #8.
>
> Two assumptions worth flagging: internal user auth is out of scope per the case spec, and the downstream system is mocked — Resilience4j scaffolding is real, the call itself is a stub."

---

## Q&A buffer (5 min, 0:25–0:30)

**Pre-rehearse answers to the likely questions:**

| Likely question | Your one-line answer |
|---|---|
| Why pgmq and not Kafka/RabbitMQ? | "At case scale, pgmq lets the events insert and queue insert share one transaction in one DB. Kafka becomes lever #19 when a single queue saturates per-heap contention." |
| Why outbox if pgmq is in the same DB? | (See ADR-002 talking points above.) |
| What if Postgres goes down? | "Single failure domain by design at case scale. HA = managed Postgres + read replica (already wired opt-in via `REPLICA_DB_URL`); cross-region is out of scope." |
| How do you handle poison messages? | "`read_ct >= maxAttempts` (default 5) → `pgmq.archive` + events row marked FAILED. DLQ is the pgmq archive table; replay = re-insert into outbox." |
| How does ordering work? | "Per-event-type queues, no in-queue ordering guarantee. If per-partner ordering is needed, shard by `hash(partner_id) % N` — covered in `06-stage2-topology.md`." |
| Why virtual threads? | "Handler is I/O-bound. VT parks on I/O without burning a carrier. Semaphore caps logical concurrency at the DB connection budget — VTs are cheap, connections aren't." |
| Test coverage? | "JaCoCo-enforced ≥ 80% line coverage. Testcontainers for full submit→outbox→pgmq→consume flow, HMAC reject paths, tenant isolation, DLQ, audit atomicity." |

---

## Tactical tips

1. **Show diagrams, not code, for architecture questions.** Drop into code only when asked something specific.
2. **Have the Postman demo pre-signed and pre-warmed.** Cold app startup in front of an interviewer kills momentum.
3. **If something fails live**, say so, don't hide it: *"Let me check the log — looks like X. The recovery path is Y because of pgmq visibility timeout."* That earns more points than a clean demo.
4. **Use the exact phrase "trade-off" 3+ times.** It signals you understand engineering ≠ "best".
5. **Don't read your own ADRs.** Reference them — *"That's ADR-002 in the doc"* — and move on.

If they cut you short, the ranked priorities are: Segment 1 (framing) > Segment 4 Demo A+B (live works) > Segment 5 (trade-offs) > everything else.
