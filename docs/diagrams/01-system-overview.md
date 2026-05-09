# System overview

High-level component diagram of the Partner Event Gateway, organized by
modular-monolith feature module. The dashed boundary is the deployment unit
in `CONSUMER_ALL` (Stage 1) mode — everything inside runs in one JVM. In
Stage 2 mode, the API role and each consumer role run in separate
Deployments using the same image.

```mermaid
flowchart LR
    P1[Partner ACME]
    P2[Partner Globex]
    OPS[Internal users]

    subgraph PEG[Partner Event Gateway]
        direction TB

        subgraph API_ROLE["API role"]
            HMAC[partner: PartnerAuthFilter]
            PCACHE[/"partner: Caffeine cache<br/>TTL 60s · max 10k<br/>hit_ratio + size gauges"/]
            CTRL_INGEST[ingest: PartnerEventsController]
            INGEST_SVC[ingest: EventIngestService]
            CTRL_INTERNAL[query: InternalEventsController]
            OUTBOX[delivery: OutboxPoller]
            QDX[platform: QueueDepthExporter]
        end

        subgraph WORKER_ROLE["Consumer roles"]
            W1[OrderCreated worker]
            W2[ShipmentUpdated worker]
            W3[ReturnRequested worker]
            W4[AddressUpdated worker]
            W5[OrderCancelled worker]
            EP[delivery: EventProcessor]
        end

        subgraph SHARED_DATA["Cross-module data writers"]
            EVT_REPO[query: EventRepository]
            AUDIT[audit: AuditLogger]
        end
    end

    subgraph PG["PostgreSQL"]
        EVENTS[(events table<br/>monthly partitions, 12mo retention)]
        AUDIT_T[(event_audit_log<br/>monthly partitions, 24mo retention)]
        OUTBOX_T[(event_outbox<br/>delete-on-send)]
        PARTNERS[(partners table)]
        Q1[(pgmq events_order_created)]
        Q2[(pgmq events_shipment_updated)]
        Q3[(pgmq events_return_requested)]
        Q4[(pgmq events_address_updated)]
        Q5[(pgmq events_order_cancelled)]
    end

    PROM[Prometheus / KEDA]

    P1 -- HMAC POST/GET --> HMAC
    P2 -- HMAC POST/GET --> HMAC
    OPS -- "GET /internal" --> CTRL_INTERNAL

    HMAC --> CTRL_INGEST
    HMAC -- "lookup(partnerId)" --> PCACHE
    PCACHE -. "miss: load" .-> PARTNERS
    HMAC -. "invalidate on<br/>verify-fail (rotation)" .-> PCACHE
    PCACHE -- "hit_ratio, size" --> PROM

    CTRL_INGEST --> INGEST_SVC
    INGEST_SVC --> EVT_REPO
    INGEST_SVC -- "atomic insert" --> OUTBOX_T
    EVT_REPO -- "atomic insert" --> EVENTS
    EVT_REPO --> AUDIT
    AUDIT --> AUDIT_T

    OUTBOX -- "FOR UPDATE SKIP LOCKED" --> OUTBOX_T
    OUTBOX -- "pgmq.send" --> Q1
    OUTBOX -- "pgmq.send" --> Q2
    OUTBOX -- "pgmq.send" --> Q3
    OUTBOX -- "pgmq.send" --> Q4
    OUTBOX -- "pgmq.send" --> Q5
    OUTBOX -- "DELETE on success" --> OUTBOX_T
    OUTBOX -- "RECEIVED → PENDING" --> EVT_REPO

    Q1 -- "pgmq.read" --> W1
    Q2 -- "pgmq.read" --> W2
    Q3 -- "pgmq.read" --> W3
    Q4 -- "pgmq.read" --> W4
    Q5 -- "pgmq.read" --> W5

    W1 --> EP
    W2 --> EP
    W3 --> EP
    W4 --> EP
    W5 --> EP

    EP -- "tryMarkProcessing<br/>markProcessed" --> EVT_REPO

    CTRL_INTERNAL -- "Specifications query" --> EVT_REPO
    QDX -- "pgmq.metrics()" --> Q1
    QDX --> PROM

    classDef controller fill:#1f77b4,stroke:#0b3d66,color:#ffffff;
    classDef worker fill:#2ca02c,stroke:#145214,color:#ffffff;
    classDef outbox fill:#ff7f0e,stroke:#9c4a00,color:#ffffff;
    classDef partner fill:#9467bd,stroke:#4b276b,color:#ffffff;

    class CTRL_INGEST,CTRL_INTERNAL controller;
    class W1,W2,W3,W4,W5 worker;
    class OUTBOX outbox;
    class P1,P2,OPS partner;
```

## Module-level dependency direction

The arrows above are runtime data flow. The compile-time dependency direction
between modules is much simpler:

```mermaid
flowchart LR
    ingest --> shared
    ingest --> audit
    ingest --> query
    ingest --> delivery
    ingest --> partner
    ingest --> platform

    delivery --> shared
    delivery --> audit
    delivery --> query
    delivery --> platform

    query --> shared
    query --> audit
    query --> platform

    partner --> shared
    partner --> platform

    audit --> shared
    audit --> platform

    platform --> shared
```

`shared` has no dependencies; `audit` depends only on `shared` + `platform`.
Every feature module is consumable independently — Stage 2 deploys API pods
with `ingest` + `query` + `partner` and consumer pods with `delivery`. Both
roles include `audit` and `platform`.

## Notes

- **`PartnerAuthFilter`** sits in front of partner endpoints only. Internal
  endpoints bypass it (per case spec — internal user auth is out of scope).
- **In-memory partner cache** (`PartnerCacheConfig`): per-pod Caffeine cache
  fronting `PartnerRepository`. `expireAfterWrite=60s`, `maximumSize=10_000`,
  populated on miss via `partners::findById`. On HMAC verify failure the
  filter invalidates the entry, reloads from `partners`, and retries once —
  this absorbs secret-rotation windows without operator intervention. Stats
  are exposed as `peg.partner_cache.size` and `peg.partner_cache.hit_ratio`
  gauges to Prometheus.
- **`OutboxPoller`** runs only on pods with the API runtime role (see
  `RuntimeProperties.runsOutboxPoller()`). Multiple API pods coordinate via
  `FOR UPDATE SKIP LOCKED`.
- **`OutboxPoller` deletes outbox rows on successful send.** The events table
  is the audit source of truth, not the outbox.
- **`AuditLogger`** is invoked by `EventRepository` inside every state-transition
  method, so audit writes are atomic with the operational write.
- **`QueueDepthExporter`** runs only on API pods so there's one source of
  truth for queue-depth metrics, not one per consumer pod.
- The 5 consumer workers run in the same JVM in `CONSUMER_ALL` mode. In
  Stage 2 each one runs in its own Deployment, scaled independently by KEDA
  against its queue's depth.
