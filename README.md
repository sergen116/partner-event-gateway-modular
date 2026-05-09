# Partner Event Gateway

Spring Boot 3.3 / Java 21 backend implementing the **Partner Event Gateway** case
study ([`docs/case/Backend_Case.pdf`](docs/case/Backend_Case.pdf)).

A modular monolith with seven feature modules, monthly-partitioned event + audit
tables, per-event-type partitioned `pgmq` queues, HMAC-authenticated partner
ingest, transactional outbox with delete-on-send semantics, an immutable audit log
of every state transition, virtual-thread consumer fan-out, and a runtime mode
switch that supports either a single-process Stage 1 deployment or a per-queue
Stage 2 deployment from the same image.

For the design rationale see [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md);
for diagrams see [`docs/diagrams/`](docs/diagrams/).

## Requirements

- Java 21 (Maven toolchain handles this)
- Docker + Docker Compose v2

## Quick start (local dev)

```bash
# 1. Start Postgres (Tembo image with pgmq + pg_partman bundled)
docker compose up -d postgres

# 2. Wait until healthy, then run the app locally
./mvnw spring-boot:run

# Or: build the image and run everything in compose
docker compose --profile app up --build
```

The app exposes:

| URL | Purpose |
|-----|---------|
| `http://localhost:8080/api/v1/events` | Partner ingest + query (HMAC required) |
| `http://localhost:8080/api/v1/internal/events` | Cross-partner query (no auth, per case spec) |
| `http://localhost:8080/swagger-ui/index.html` | API documentation |
| `http://localhost:8080/actuator/health` | Liveness / readiness |
| `http://localhost:8080/actuator/prometheus` | Metrics |

## Test partners (local only)

Two partners are seeded by Flyway `V3__seed_test_partners.sql`:

| `X-Partner-Id` | Secret |
|----------------|--------|
| `partner-acme` | `acme-shared-secret-2024` |
| `partner-globex` | `globex-shared-secret-2024` |

### HMAC signing

Canonical message:

```
{partnerId}\n{timestamp}\n{HTTP_METHOD}\n{path}\n{request_body}
```

The HMAC key is `SHA-256(secret)` (raw bytes, not hex). Signature is Base64 of
`HMAC-SHA256(key, canonical)`. The raw secret never leaves the partner side and
never appears in DB rows or logs.

Quick example using `openssl`:

```bash
PARTNER=partner-acme
SECRET="acme-shared-secret-2024"
TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
BODY='{"eventType":"OrderCreated","businessRef":"ORD-1","payload":{"total":99}}'
URL_PATH=/api/v1/events
METHOD=POST

KEY_HEX=$(printf %s "$SECRET" | openssl dgst -sha256 -binary | xxd -p -c 256)
CANON=$(printf "%s\n%s\n%s\n%s\n%s" "$PARTNER" "$TS" "$METHOD" "$URL_PATH" "$BODY")
SIG=$(printf "%s" "$CANON" | openssl dgst -sha256 -mac HMAC -macopt hexkey:"$KEY_HEX" -binary | base64)

curl -X POST "http://localhost:8080$URL_PATH" \
    -H "Content-Type: application/json" \
    -H "X-Partner-Id: $PARTNER" \
    -H "X-Timestamp: $TS" \
    -H "X-Signature: $SIG" \
    -H "Idempotency-Key: $(uuidgen)" \
    -d "$BODY"
```

A working Postman collection (with HMAC pre-request scripts) lives in
[`docs/postman/`](docs/postman/).

## Runtime modes

`APP_RUNTIME_MODE` (default `CONSUMER_ALL`):

| Mode | API | Workers | Use |
|------|-----|---------|-----|
| `CONSUMER_ALL` | yes | all 5 | Local dev, Stage 1 fallback |
| `API` | yes | none | Stage 2 production API pod |
| `CONSUMER_ORDER_CREATED` | no | order-created only | Stage 2 production worker |
| `CONSUMER_SHIPMENT_UPDATED` | no | shipment-updated only | Stage 2 production worker |
| `CONSUMER_RETURN_REQUESTED` | no | return-requested only | Stage 2 production worker |
| `CONSUMER_ADDRESS_UPDATED` | no | address-updated only | Stage 2 production worker |
| `CONSUMER_ORDER_CANCELLED` | no | order-cancelled only | Stage 2 production worker |

The same Docker image runs all 7 modes.

## Building

```bash
./mvnw -B package              # produces target/partner-event-gateway-0.1.0.jar
./mvnw -B verify               # also runs integration tests (require Docker)
docker build -t peg:latest .   # multi-stage build → minimal JRE image
```

## Tests

```bash
./mvnw -B test         # unit tests only (no Docker needed)
./mvnw -B verify       # unit + integration tests; pulls Tembo pgmq Postgres image via Testcontainers
```

JaCoCo enforces ≥ 80% line coverage on the merged unit + IT runs (`./mvnw verify`).

**Unit tests** cover HMAC verification (timestamp skew, tampered body / path,
secret rotation), runtime mode resolution, event-type wire mapping, pagination,
filter spec compilation, error envelopes, and every platform configuration class.

**Integration tests** use Testcontainers (Tembo Postgres with pgmq + pg_partman):

- `IngestToConsumeIT` — full submit → outbox → pgmq → consume → PROCESSED flow
- `PartnerEventsControllerIT` / `PartnerAuthFilterIT` — HMAC accept / reject paths and replay protection
- `EventRepositoryIT` — tenant isolation in the repository (different partners can reuse event IDs)
- `OutboxPollerIT` — `SKIP LOCKED` claim + delete-on-send
- `DeadLetterQueueIT` — retry exhaustion → archive + FAILED transition
- `AuditLoggerIT` — atomic audit row write inside operational transactions
- `InternalEventsControllerIT` — cross-partner internal querying

## Configuration

Standard Spring Boot — properties or environment variables override
`src/main/resources/application.yml`. Most-relevant env vars:

| Variable | Default | Notes |
|----------|---------|-------|
| `APP_RUNTIME_MODE` | `CONSUMER_ALL` | See runtime modes above |
| `DB_HOST` | `localhost` | Postgres host |
| `DB_PORT` | `5432` | |
| `DB_NAME` | `events` | |
| `DB_USER` | `postgres` | |
| `DB_PASS` | `postgres` | |
| `HIKARI_MAX` | `40` | Hikari pool size per pod (writer) |
| `REPLICA_DB_URL` | _unset_ | Optional read-replica JDBC URL. Unset → cross-partner / internal event queries fall back to the primary pool. Writes always use the primary. |
| `REPLICA_DB_USER` | inherits `DB_USER` | Replica username when `REPLICA_DB_URL` is set |
| `REPLICA_DB_PASS` | inherits `DB_PASS` | Replica password when `REPLICA_DB_URL` is set |
| `REPLICA_HIKARI_MAX` | `10` | Replica pool size — sized for query API concurrency only |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | _unset_ | Set in deployed envs to enable OTLP span export |
| `TRACING_SAMPLING` | `0.1` | Span sampling probability |
| `DOWNSTREAM_BASE_URL` | `http://localhost:9999` | Stub downstream system invoked by handlers |

Per-queue worker concurrency and batch-size live under
`app.consumer.concurrency.<queue>` and `app.consumer.batch-size.<queue>` in
`application.yml`. Workers run a work-conserving loop: after a full batch they
re-poll on `app.consumer.busy-poll-interval-ms` (20 ms default); after a
partial or empty batch they back off to `app.consumer.poll-interval-ms`
(500 ms default). Visibility timeout and max attempts live alongside. Capacity
sizing for 2K TPS in
[`docs/ARCHITECTURE.md` → Capacity and scaling](docs/ARCHITECTURE.md#capacity-and-scaling-to-2k-tps-at-peak).

## What's where

```
src/main/java/com/example/peg/
├── PartnerEventGatewayApplication.java
├── shared/      cross-cutting types (EventType, EventStatus, EventRecord,
│                PartnerEventMessage, Errors, GlobalExceptionHandler)
├── partner/     identity + HMAC verification (PartnerAuthFilter,
│                HmacVerifier, PartnerRepository, CachingRequestWrapper,
│                PartnerCacheConfig)
├── ingest/      partner-facing write path (PartnerEventsController,
│                EventIngestService, SubmitEventRequest/Response)
├── delivery/    outbox poller + pgmq workers + processor (OutboxPoller,
│                OutboxRepository, PgmqWorker base + 5 subclasses,
│                EventProcessor, DownstreamCallService)
├── query/       query API + Specifications-based filter framework
│                (InternalEventsController, EventRepository, EventQuery,
│                EventSpecifications, PageResponse, EventResponse)
├── audit/       immutable state-transition log (AuditLogger, AuditRecord)
└── platform/    runtime modes, scheduling, configuration, observability
                 (RuntimeProperties, WorkerRegistrationConfig, WorkerScheduler,
                 SchedulingConfig, ConsumerProperties, SecurityProperties,
                 DataSourceConfig, JacksonConfig, OpenApiConfig,
                 QueueDepthExporter, HikariPoolHealthIndicator,
                 TraceContextCarrier)

src/main/resources/db/migration/
├── V1__init_schema.sql           partners, events (monthly partitions, 12mo
│                                  retention), event_outbox
├── V2__init_queues.sql           5 partitioned pgmq queues (daily, 4-day
│                                  retention)
├── V3__seed_test_partners.sql    test partners with real SHA-256 hashes
└── V4__init_audit_log.sql        event_audit_log (monthly partitions,
                                   24mo retention)

docker/init/00-configure-partman.sh    Enables pg_partman_bgw on first start
docker-compose.yml                     Postgres + (optional) app via --profile
Dockerfile                             Multi-stage: build → layer-extract → JRE
```

## Documentation

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — module structure, NFR analysis, 10 ADRs
- [`docs/diagrams/`](docs/diagrams/) — system overview, ERD, sequence flows, state machine, Stage 2 topology, scaling-and-tradeoffs rationale
- [`docs/postman/`](docs/postman/) — Postman collection with HMAC pre-request scripts + environments
- [`docs/case/Backend_Case.pdf`](docs/case/Backend_Case.pdf) — original case study

---

# Submission notes

## Assumptions

1. **Internal user authentication is not implemented.** The case spec explicitly
   excludes it. `InternalEventsController` runs without auth on `/api/v1/internal/**`;
   in production, route protection (operator OIDC, mTLS, network ACL) lives in
   front of the pod.
2. **The downstream system is mocked.** Per the case spec, "the platform is not
   expected to implement full downstream business processes." `DownstreamCallService`
   logs and sleeps briefly to simulate latency; the real integration plugs in by
   replacing this single service. The Resilience4j scaffolding (retry + circuit
   breaker) around it is real and fully wired.
3. **Partner secrets are stored as `SHA-256(secret)`.** Real production would put
   secrets in a KMS or sealed secret store. The current scheme is a documented
   simplification — the raw secret never appears in DB rows, but a DB-level leak
   would still expose the HMAC key. See [ADR-001](docs/ARCHITECTURE.md#adr-001-hmac-key-derivation).
4. **Single-region, single-Postgres deployment.** No multi-region replication, no
   external broker. Read-replica wiring is opt-in via `REPLICA_DB_URL`.
5. **`Idempotency-Key` is a UUID.** The header is parsed as `UUID.fromString`; any
   non-UUID value returns 400. Idempotency is scoped to `(partner_id, event_id,
   created_at month)` — within-month duplicates are inert; cross-month retries of
   the same UUID would re-insert. In practice idempotency keys are short-lived per
   event, so this is not a real concern.
6. **Internal users see all partners.** No per-partner authorization on the
   `/internal` path beyond the optional `partnerId` query parameter.
7. **Default query window of 90 days** is applied to both partner and internal
   queries when no `from` / `to` is given, so partition pruning bounds the scan.
   Callers can override either bound.

## Limitations / missing parts

- **Stage 2 manifests not in repo.** k8s Deployments, KEDA `ScaledObject`, and
  PgBouncer config are described in
  [`docs/diagrams/06-stage2-topology.md`](docs/diagrams/06-stage2-topology.md)
  but not committed. The application code, image, and migrations are deploy-ready;
  manifests are the missing operations layer.
- **Long-polling not wired.** `PgmqWorker` calls `pgmq.read`, not
  `pgmq.read_with_poll`. Under load the work-conserving loop's busy interval
  (20 ms) already keeps pickup latency low; long-polling would only help on
  near-idle queues, dropping the worst-case empty-queue pickup from 500 ms to
  tens of ms. Documented as lever #4 in
  [`docs/diagrams/07-scaling-and-tradeoffs.md`](docs/diagrams/07-scaling-and-tradeoffs.md).
- **Per-partner rate limiting not wired.** Would slot into `PartnerAuthFilter` as
  a Caffeine-backed token bucket. Lever #8.
- **Cold-tier S3 archival job not implemented.** `retention_keep_table=true` is
  set on `events`, `event_audit_log`, and pgmq queues, so partitions detach
  instead of dropping — but the actual `pg_dump` / S3 ship script is not in the
  repo. Lever #17.
- **Outbox poll cadence and batch are constants.** `BATCH_SIZE=50` and
  `POLL_INTERVAL=250 ms` are hardcoded in `OutboxPoller.java`. Promoting them to
  `app.outbox.*` properties is a small follow-up.
- **No production-grade secret management.** See assumption #3.
- **No partner self-service onboarding.** Partners are seeded via Flyway migration
  for the case study. A real platform would have an admin API to create / rotate /
  deactivate partners.
- **Tenant scoping is explicit, not implicit.** There is no `TenantContext`
  thread-local + Hibernate filter that would auto-inject `partner_id` into
  every query. The persistence layer is JdbcTemplate + raw SQL because pgmq is
  JDBC-native (`pgmq.send`, `pgmq.read`, `FOR UPDATE SKIP LOCKED`) and the
  five-table schema has no entity-graph traversal — adding JPA on top *just*
  for tenant filtering would mean a JDBC + JPA hybrid stack with no upside
  (see [ADR-007](docs/ARCHITECTURE.md#adr-007-specifications-without-jpa)).
  Consequence: every repository method takes `partner_id` as a parameter and
  binds it manually; JPA would have made this seamless via `@FilterDef` /
  `@TenantId`. Known trade-off — accepted as a direct consequence of the
  no-JPA decision pgmq already forces on us.

## Time spent

Approximately **3 working days** end-to-end:

- ~0.5 day — case study analysis, API + data-model design, build scaffolding
  (Maven, Docker, Postgres + pgmq + pg_partman wiring)
- ~1.5 days — ingest path, outbox pattern, pgmq workers (per-event-type queues,
  virtual-thread fan-out, partition lifecycle, idempotency), plus tests (unit
  suites + Testcontainers integration tests for the full submit-to-process flow,
  idempotency, tenant isolation, DLQ, audit atomicity)
- ~1 day — observability (Micrometer / Prometheus, OTel tracing across the async
  boundary, JSON logging, health indicators), runtime mode topology, ADR + diagram
  documentation

## Main design decisions and trade-offs

The key choices and their rationale (full-form ADRs in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#7-architecture-decision-records-short-form)):

1. **Modular monolith over microservices.** Deployable as one process for Stage 1
   and as per-role pods in Stage 2 — same image, no code change. Cheaper to
   operate at the case's scale; the modular boundaries make a future split
   straightforward if needed. *Trade-off:* a single-DB blast radius until that
   future split.

2. **Transactional outbox over direct `pgmq.send`.** Atomicity is equivalent
   in a same-database setup, but the outbox decouples partner-facing reliability
   from queue health, isolates pgmq churn from the API hot path, and keeps the
   ingest module queue-agnostic. *Trade-off:* +250 ms median accept-to-queue
   latency. See [ADR-002](docs/ARCHITECTURE.md#adr-002-transactional-outbox-vs-direct-pgmqsend).

3. **PostgreSQL for everything (events, outbox, queue, audit, partner).** pgmq
   gives us a queue with the same ACID properties as the events table, so the
   outbox pattern works without a second system. Postgres-specific features
   (`SKIP LOCKED`, partial indexes, declarative partitioning, JSONB) are used
   freely. *Trade-off:* Postgres lock-in. See
   [ADR-010](docs/ARCHITECTURE.md#adr-010-postgresql-as-the-storage-choice).

4. **JdbcTemplate, no JPA.** pgmq is JDBC-native (`pgmq.send`, `pgmq.read`,
   `FOR UPDATE SKIP LOCKED`), the schema has five tables and no entity-graph
   traversal, and SQL stays one read away — keeping queries partition-prunable
   on the monthly-partitioned `events` table. The case spec's "extensible filter"
   requirement is met by a hand-rolled `EventSpecifications` registry. See
   [ADR-007](docs/ARCHITECTURE.md#adr-007-specifications-without-jpa).

5. **Per-event-type pgmq queues.** Different event types have different processing
   profiles; per-type queues let us scale them independently in Stage 2 (KEDA on
   `pgmq.metrics()`). *Trade-off:* 5× the operational surface, negligible at this
   scale. See [ADR-003](docs/ARCHITECTURE.md#adr-003-per-event-type-queues).

6. **Virtual threads + Semaphore in workers.** Message processing is I/O-bound;
   virtual threads park instead of blocking carriers. The Semaphore caps logical
   concurrency at the DB-connection budget — VTs are cheap, connections aren't.

7. **HMAC-SHA256 with `SHA-256(secret)` storage.** Raw secrets never appear in
   DB rows or logs. *Trade-off:* a DB leak would still expose the HMAC key — a
   KMS-backed signing path would be the next step. See
   [ADR-001](docs/ARCHITECTURE.md#adr-001-hmac-key-derivation).

8. **Monthly partitioning for `events` and `event_audit_log`; daily for pgmq.**
   Records vs queues. Catalog count stays manageable, retention is `DROP PARTITION`,
   queries with time bounds prune. See
   [ADR-008](docs/ARCHITECTURE.md#adr-008-monthly-partitioning-for-events-and-audit).

9. **Outbox row delete-on-send.** The events table is the audit source of truth;
   keeping outbox rows after forwarding adds nothing and grows unboundedly.
   *Trade-off:* the pgmq `msg_id` is not retained on the application side — rare
   forensic case. See [ADR-006](docs/ARCHITECTURE.md#adr-006-outbox-delete-on-send).

10. **Resilience4j retry + circuit breaker on the downstream call.** Fast in-process
    absorption of transient blips; a breaker caps long outages. The fallback
    rethrows so pgmq's outer redelivery / DLQ budget still applies — two layers
    composed, not stacked. See
    [ADR-009](docs/ARCHITECTURE.md#adr-009-circuit-breaker--retry-on-downstream-calls).
