# 01-system-overview.md — Summary

High-level component diagram of all roles in one JVM (Stage 1) or split per role (Stage 2).

## Components (Stage 1 dashed boundary = single JVM)

**API role**: `PartnerAuthFilter` (HMAC) → `PartnerCache` (Caffeine, 60s TTL, 10k max) → `PartnerEventsController` → `EventIngestService` writes events+outbox+audit atomically. Plus `InternalEventsController` (cross-partner), `OutboxPoller` (drains every 250ms), `QueueDepthExporter`.

**Consumer roles**: 5 workers (one per event type) → `EventProcessor`. Each worker bound to one pgmq queue.

**Cross-module data writers**: `EventRepository`, `AuditLogger`.

**Postgres**: `partners`, `events` (monthly), `event_audit_log` (monthly), `event_outbox` (transient), 5 pgmq queues (daily).

## Module dependency graph (compile-time)
```
ingest → shared, audit, query, delivery, partner, platform
delivery → shared, audit, query, platform
query → shared, audit, platform
partner → shared, platform
audit → shared, platform
platform → shared
shared → ∅
```
Acyclic. Audit consumed by ingest/delivery/query (each writes), consumes none.

## Critical notes
- **`PartnerAuthFilter`** only on partner endpoints; internal endpoints bypass it (per case spec).
- **Caffeine cache rotation handling**: on HMAC verify failure, filter invalidates entry, reloads from `partners`, retries once. Absorbs rotation windows without operator intervention.
- **`OutboxPoller`** runs only on API pods (`runsOutboxPoller()`); multiple API pods coordinate via SKIP LOCKED.
- **Delete-on-send**: events table is audit source of truth, not outbox.
- **`AuditLogger`** invoked by `EventRepository` inside every transition method → audit atomic with op write.
- **`QueueDepthExporter`** runs only on API pods → single source of truth for queue-depth metrics.
