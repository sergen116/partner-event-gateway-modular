# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build, run, test

```bash
# Local dev: start Postgres (Tembo image bundles pgmq + pg_partman), then run app
docker compose up -d postgres
./mvnw spring-boot:run

# Or run everything containerised
docker compose --profile app up --build

# Unit tests only (no Docker required)
./mvnw -B test

# Unit + integration tests (pulls Tembo Postgres via Testcontainers; needs Docker)
./mvnw -B verify

# Run a single test class / method
./mvnw -B test -Dtest=HmacVerifierTest
./mvnw -B test -Dtest=HmacVerifierTest#rejectsTamperedBody
./mvnw -B verify -Dit.test=IngestToConsumeIT      # IT-only

# Package + Docker image
./mvnw -B package
docker build -t peg:latest .
```

JaCoCo enforces ≥80% line coverage on merged unit + IT runs (`./mvnw verify`). Coverage check excludes the `@SpringBootApplication` main class.

Integration tests are anything matching `**/*IT.java` (failsafe). Unit tests are everything else (surefire).

## Architecture

Spring Boot 3.3 / Java 21 modular monolith. Same image runs in seven roles via `APP_RUNTIME_MODE` (`API`, `CONSUMER_ALL`, or one of five `CONSUMER_<EVENT_TYPE>`). `RuntimeProperties.activeEventTypes()` is the single switch that decides which worker beans get instantiated; the rest of the code is mode-agnostic.

**Packages are organized by feature module, not by layer.** Each module has a `package-info.java` declaring allowed deps. The graph is acyclic:

```
ingest    → shared, query, partner, platform
delivery  → shared, query, platform
query     → shared, audit
partner   → shared, platform
audit     → shared
platform  → shared, query, delivery   (wiring seam: registers worker beans)
```

`query.EventRepository` and `query.OutboxRepository` are the cross-module write seams: `ingest` writes events + outbox rows through `query`; `delivery` writes state transitions through `query`. State transitions are atomic with their audit row (the audit module is called by `query`, so other modules pick up audit writes transitively).

**End-to-end flow (single submit):**

1. `partner.PartnerAuthFilter` (registered on `/api/v1/events*`) loads the partner from DB (Caffeine cache, 60s TTL), verifies HMAC via `HmacVerifier`, sets `partner_id` as a request attribute.
2. `ingest.PartnerEventsController` → `EventIngestService` writes one `events` row + one `event_outbox` row in a single transaction.
3. `delivery.OutboxPoller` (scheduled, `SKIP LOCKED` claim) drains the outbox → `pgmq.send` to the per-event-type queue → DELETE outbox row.
4. `delivery.PgmqWorker` (one subclass per event type) reads in batches, hands each message to `EventProcessor`, which calls `DownstreamCallService.notify()` (stubbed) wrapped in Resilience4j `@Retry` + `@CircuitBreaker`. On success → events row reaches `PROCESSED`; on retry exhaustion → archive + `FAILED`.

**Persistence is JdbcTemplate + raw SQL — no JPA.** pgmq is JDBC-native (`pgmq.send`, `pgmq.read`, `FOR UPDATE SKIP LOCKED`); adding JPA on top would mean a hybrid stack for no gain. The "extensible filter" requirement is met by `query.EventSpecifications`, a hand-rolled registry of filter clauses that compose into prepared statements. Consequence: every repository method takes `partner_id` as an explicit parameter — there is no `TenantContext` thread-local. Don't introduce one without revisiting [ADR-007](docs/ARCHITECTURE.md#adr-007-specifications-without-jpa).

**Data model:**
- `events` — monthly partitioned, 12mo retention. Append-only state transitions live in `event_audit_log` (also monthly partitioned, 24mo retention).
- `event_outbox` — heap table. Rows are `DELETE`d after successful pgmq forward (delete-on-send, see [ADR-006](docs/ARCHITECTURE.md#adr-006-outbox-delete-on-send)).
- Five `pgmq` queues, one per `EventType`, daily-partitioned with 4-day retention.
- `partners` — `secret_hash = SHA-256(secret)`; raw secret never persisted. Rotation supported via `previous_secret_hash` + expiry.

**Concurrency model:** `spring.threads.virtual.enabled=true` — workers run on virtual threads with a `Semaphore` capping logical concurrency at the DB-connection budget (`app.consumer.concurrency.<queue>`). VTs are cheap; connections aren't. The Hikari pool size (`HIKARI_MAX`, default 40) is sized for `CONSUMER_ALL`'s worst-case: ~24 worker permits + ~10 API + 1 outbox + 1 metrics ≈ 36, with 4 headroom.

**Work-conserving worker loop:** after a full batch, re-poll on `busy-poll-interval-ms` (20 ms); after a partial/empty batch, back off to `poll-interval-ms` (500 ms). Retry budget (`max-attempts × wait × multiplier` in Resilience4j config) must stay well under pgmq `visibility-timeout-seconds` (30s) minus the 5s safety buffer enforced in `PgmqWorker.processBatch` — otherwise a message can be redelivered while the previous attempt is still retrying.

**Read replica is opt-in.** `DataSourceConfig` wires a second Hikari pool when `REPLICA_DB_URL` is set; only `EventRepository.query/count` use it (cross-partner / internal queries). Writes always hit the primary. When unset, the read template aliases to the primary, so docker-compose works unchanged.

**Observability:** Micrometer + Prometheus on `/actuator/prometheus`. OTel tracing flows `trace_id`/`span_id` into MDC always; OTLP export only activates when `MANAGEMENT_OTLP_TRACING_ENDPOINT` is set (don't put a value in YAML — any non-null value, including empty string, triggers URL validation against the literal). `TraceContextCarrier` (in `platform`) propagates the span context across the async outbox → pgmq → worker boundary.

## Conventions worth knowing

- **Partner secrets in tests:** `partner-acme` / `acme-shared-secret-2024` and `partner-globex` / `globex-shared-secret-2024`. Seeded by `V3__seed_test_partners.sql`. HMAC key derivation = `SHA-256(secret)` raw bytes (NOT hex). See README "HMAC signing" for the canonical message format and an `openssl` example.
- **Integration tests** subclass nothing — they wire Postgres via `PgmqPostgresInitializer` (`@ContextConfiguration(initializers = …)`) and `@SpringBootTest(webEnvironment = RANDOM_PORT)`. Use `awaitility` for async assertions and `@MockBean DownstreamCallService` to avoid the stub's simulated latency.
- **Idempotency-Key** header must be a UUID; idempotency is scoped to `(partner_id, event_id, created_at month)`.
- **Default query window is 90 days** when no `from`/`to` is given — this is intentional to keep partition pruning effective. Don't widen the default.
- **Internal API (`/api/v1/internal/**`) has no auth by design** — per case spec. Protect it via network ACL / mTLS / operator OIDC in front of the pod.
- **`event_outbox` schema constants are hardcoded** (`BATCH_SIZE=50`, `POLL_INTERVAL=250ms` in `OutboxPoller.java`). Promoting them to `app.outbox.*` is a documented follow-up.

## Where to read more

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — module structure, NFR analysis, 10 ADRs. **Consult ADRs before changing HMAC key derivation, outbox semantics, JPA introduction, partitioning scheme, or queue topology.**
- [`docs/diagrams/`](docs/diagrams/) — system overview, ERD, sequence flows, state machine, Stage 2 topology, scaling rationale (lever list).
- [`README.md`](README.md) — runtime modes, env vars, full HMAC example, submission notes (assumptions + known limitations).
- [`docs/postman/`](docs/postman/) — Postman collection with HMAC pre-request scripts.
