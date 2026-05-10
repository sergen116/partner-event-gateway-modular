# Defence Plan — Summary

30-min interview script. From `DEFENCE_PLAN_V2.md` (revised after audit closed gaps in v1).

## Pre-talk checklist (5 min before)
- DB up, app up (`docker compose up -d postgres && ./mvnw spring-boot:run`).
- psql session ready.
- Browser tabs in order: case PDF, `01-system-overview`, `02-erd`, `03-ingest-sequence`, `05-state-machine`, `06-stage2-topology`, swagger-ui, `/actuator/prometheus`.
- Postman: collection open, partner-acme env, one request **pre-signed**.
- **Pre-seed ≥6 events across both partners and ≥3 event types** so Demo D2 filters return non-empty.

## 6 segments — total 30 min

### Seg 1 (3m) — Frame the problem
Five NFRs shape every decision: per-tenant isolation, at-least-once + idempotency, independent scaling per event type, full auditability, production-ready ops. Five event types: **OrderCreated, ShipmentStatusUpdated, ReturnRequested, DeliveryAddressUpdated, OrderCancelled**. Walk system overview left→right (90 sec) — topology only, not code.

### Seg 2 (3m) — Module layout, ERD, monolith choice
- Show feature modules (`ingest, delivery, query, audit, partner, platform, shared`).
- Acyclic, documented in `package-info.java`.
- Same image, two shapes via `APP_RUNTIME_MODE`. **No code change between Stage 1 & 2.**
- 30 sec on ERD: `events` partitioned monthly, unique on `(partner_id, event_id, created_at)`, audit one-row-per-transition same tx, outbox staging.

### Seg 3 (4m) — Walk ingest sequence diagram
Land 5 points:
1. **HMAC**: `SHA-256(secret)` derivation, ±5 min window, constant-time compare, rotation field. (ADR-001)
2. **Validation**: `@Valid` on DTO, EventType enum, `partner_id` from auth context not body — partner can't spoof another's id.
3. **Idempotency**: SELECT first, INSERT ON CONFLICT, re-SELECT on race loss.
4. **One transaction**: events + audit + outbox atomic. 200 OK before pgmq touched.
5. **Outbox decoupling**: NOT for atomicity (same DB). Three reasons: future-proofing for Kafka, shorter ingest tx, ingest stays queue-agnostic. Cost: +250ms median. **(ADR-002 — strongest design call.)**

### Seg 4 (9m) — Live demo (most candidates botch)
- **Demo A (1.5m) Happy path**: POST as ACME OrderCreated → 200 RECEIVED → GET → PROCESSED → show `trace_id` MDC.
- **Demo B (2m) Idempotency + auditability**: re-send same `Idempotency-Key` → 200 `duplicate=true`. SQL: one events row, full audit trail.
- **Demo C (1m) HMAC reject**: tamper body → 401, no DB writes. Old timestamp → 401 (replay).
- **Demo D1 (1m) Tenant isolation**: send as Globex; GET as ACME → only ACME's events. *"Server-side from auth context, not query params."*
- **Demo D2 (2.5m) NEW — Querying** (covers Functional Req 5): pagination (`page=0&size=2`), filter combos (`eventType=OrderCreated&status=PROCESSED`), date interval + partner, `businessRef`. Then 15-sec IDE flip to `EventSpecifications.SPECS` registry: *"Adding a filter is two lines — field on EventQuery + entry in registry. No SQL concatenation, no injection risk."*
- **Demo E (1m) FAILED + metrics**: don't trigger live (~30s for retries); reference `05-state-machine.md` and `ProcessingFailureIT`. Show `peg_consumer_processed/failed_total`, `peg_queue_length` (KEDA input).

### Seg 5 (5m) — Scaling & trade-offs
2K TPS story: Stage 1 single pod ~400 msg/s, 2K = multi-pod. ~14 consumer + 3 API pods, KEDA on `pgmq.queue_length`, PgBouncer for connection multiplexing. Pick 3 trade-offs (don't read ADRs):

| Decision | Trade-off | Why |
|---|---|---|
| Outbox vs direct | +250ms latency | Future-proofing + queue-agnostic ingest |
| `SHA-256(secret)` not KMS | DB leak still exposes HMAC key | Documented simplification; KMS = next |
| JdbcTemplate, no JPA | Devs reaching for `JpaSpecificationExecutor` need to read | pgmq is JDBC-native; pruning needs raw SQL |
| Per-event-type queues | 5× ops surface | Independent scaling required |
| Postgres for everything | Vendor lock-in | pgmq + indexes + SKIP LOCKED already lock us in |

### Seg 6 (2m) — Assumptions, limits, what's next
**Assumptions**:
1. Internal user auth out of scope per case spec → mTLS / IdP in prod.
2. Downstream is mocked — Resilience4j scaffolding real, call is stub.
3. Partner secrets via Flyway seed for demo → secret manager in prod.

**Deliberately NOT in repo (called out in README)**:
1. Stage 2 k8s manifests (described in `06-stage2-topology.md`).
2. Cold-tier S3 archival job (partitions detach, ship script absent).
3. Per-partner rate limit (would slot into `PartnerAuthFilter` as Caffeine token bucket = lever #8).

## Q&A buffer (4m) — pre-rehearsed
| Q | One-liner |
|---|---|
| Why pgmq not Kafka? | "Same DB tx for events+queue insert; Kafka becomes lever #19 when one queue saturates per-heap contention." |
| Why outbox if pgmq is same DB? | (ADR-002 talking points.) |
| Why `create_partitioned` not `create_unlogged`? | "Unlogged TRUNCATEd on crash — combined with delete-on-send breaks at-least-once + DLQ. WAL fsync hidden behind 250ms poll cadence anyway." |
| What if Postgres goes down? | "Single failure domain by design. HA = managed PG + read replica (opt-in). Cross-region out of scope." |
| Poison messages? | "`read_ct >= 5` → `pgmq.archive` + FAILED row. Replay = re-INSERT into outbox from archive." |
| Ordering? | "No in-queue ordering. Per-partner ordering: `hash(partner_id) % N` shard." |
| Why VTs? | "Handler is I/O-bound. VT parks without burning carrier. Semaphore caps logical concurrency at DB connection budget — VTs cheap, connections not." |
| New filter? | "Field on EventQuery + entry in `SPECS` registry (Demo D2)." |
| Test coverage? | "JaCoCo ≥80% line. Testcontainers full submit→outbox→pgmq→consume, HMAC reject, tenant isolation, DLQ, audit atomicity, FAILED lifecycle." |

## Tactical tips
1. Show **diagrams not code** for architecture questions. Drop into code only when asked (and the `EventSpecifications.SPECS` registry).
2. Pre-signed Postman, pre-warmed app — cold startup kills momentum.
3. Pre-seed data — Demo D2 needs filterable rows.
4. If something fails live, **say so**, don't hide.
5. Use the phrase "trade-off" 3+ times — signals you understand engineering ≠ "best".
6. **Don't read your own ADRs.** Reference and move on.

If cut short, priority: **Seg 1 (framing) > Seg 4 Demo A+B+D2 (most case requirements) > Seg 5 (trade-offs) > everything else.**
