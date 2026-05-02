# Partner Event Gateway

Spring Boot 3.3 / Java 21 backend implementing the case study's Partner Event Gateway.
A modular-monolith design with seven feature modules, monthly-partitioned event +
audit tables, per-event-type partitioned `pgmq` queues, HMAC-authenticated partner
ingest, transactional outbox with delete-on-send semantics, immutable audit log of
every state transition, virtual-thread consumer fan-out, and a runtime mode switch
that supports either a single-process Stage 1 deployment or a per-queue Stage 2
deployment from the same image.

For the design rationale see [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md);
for diagrams see [`docs/diagrams/`](docs/diagrams/).

## Requirements

- Java 21 (Maven toolchain handles this)
- Docker + Docker Compose v2

## Quick start (local dev)

```bash
# 1. Start Postgres with pgmq + pg_partman bundled
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
| `http://localhost:8080/swagger-ui.html` | API documentation |
| `http://localhost:8080/actuator/health` | Liveness/readiness |
| `http://localhost:8080/actuator/prometheus` | Metrics |

## Test partners (local only)

Two partners are seeded by Flyway `V3__seed_test_partners.sql`:

| `X-Partner-Id` | Secret |
|----------------|--------|
| `partner-acme` | `acme-shared-secret-2024` |
| `partner-globex` | `globex-shared-secret-2024` |

### Computing the HMAC

Canonical message is:
```
{partnerId}\n{timestamp}\n{HTTP_METHOD}\n{path}\n{request_body}
```

The HMAC key is `SHA256(secret)` (raw bytes, not hex). Signature is Base64 of
`HMAC-SHA256(key, canonical)`.

Quick example using `openssl`:

```bash
PARTNER=partner-acme
SECRET="acme-shared-secret-2024"
TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
BODY='{"eventType":"OrderCreated","businessRef":"ORD-1","payload":{"total":99}}'
URL_PATH=/api/v1/events
METHOD=POST

# Derive key bytes once
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

A working Postman collection comes with the docs bundle.

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

Unit tests cover HMAC verification (incl. timestamp skew, tampered body/path, secret
rotation), runtime mode resolution, event-type wire mapping, and pagination.

Integration tests use Testcontainers to spin up a real Postgres with pgmq + pg_partman
and exercise:

- The full submit → outbox → pgmq → consume → PROCESSED flow
- Idempotent re-submission (same key, no double-processing)
- HMAC rejection on missing/bad signatures
- Tenant isolation in the repository (different partners can reuse event IDs)
- Atomic state transitions (claim semantics for redelivery)

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
| `HIKARI_MAX` | `30` | Hikari pool size per pod |

## What's where

Modular monolith — packages organized by feature, not by technical layer.
Each module has a `package-info.java` documenting its public API and
dependency direction.

```
src/main/java/com/example/peg/
├── PartnerEventGatewayApplication.java
├── shared/      cross-cutting types (EventType, EventStatus, EventRecord,
│                PartnerEventMessage, Errors, GlobalExceptionHandler)
├── partner/     identity + HMAC verification (PartnerAuthFilter,
│                HmacVerifier, PartnerRepository, CachingRequestWrapper)
├── ingest/      partner-facing write path (PartnerEventsController,
│                EventIngestService, SubmitEventRequest/Response)
├── delivery/    outbox poller + pgmq workers + processor (OutboxPoller,
│                OutboxRepository, PgmqWorker base + 5 subclasses,
│                EventProcessor)
├── query/       query API + Specifications-based filter framework
│                (InternalEventsController, EventRepository, EventQuery,
│                EventSpecifications, PageResponse, EventResponse)
├── audit/       immutable state-transition log (AuditLogger, AuditRecord)
└── platform/    runtime modes, scheduling, configuration, observability
                 (RuntimeProperties, WorkerRegistrationConfig, WorkerScheduler,
                 SchedulingConfig, ConsumerProperties, SecurityProperties,
                 JacksonConfig, OpenApiConfig, QueueDepthExporter)

src/main/resources/db/migration/
├── V1__init_schema.sql           partners, events (partitioned monthly),
│                                  event_outbox tables
├── V2__init_queues.sql           5 partitioned pgmq queues
│                                  (daily partitions, 4-day retention)
├── V3__seed_test_partners.sql    test partners with real SHA-256 hashes
└── V4__init_audit_log.sql        event_audit_log (partitioned monthly,
                                   24-month retention)

docker/init/00-configure-partman.sh    Enables pg_partman_bgw on first postgres start
docker-compose.yml                     Postgres + (optional) app
Dockerfile                             Multi-stage: build → layer-extract → JRE runtime
```

## Documentation

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — module structure, NFR analysis, 8 ADRs
- [`docs/diagrams/`](docs/diagrams/) — system overview, ERD, sequence flows, state machine, Stage 2 topology, plus 4 interactive HTML rationale documents
- [`docs/postman/`](docs/postman/) — Postman collection with HMAC pre-request scripts + environments

Tests live under `src/test/java/`:

- Unit tests (no DB needed): `HmacVerifierTest`, `RuntimePropertiesTest`,
  `EventTypeTest`, `PartnerTest`, `PageResponseTest`, `EventSpecificationsTest`
- Integration tests (Testcontainers Postgres with pgmq):
  `IngestToConsumeIT`, `EventRepositoryIT`, `AuditLoggerIT`
