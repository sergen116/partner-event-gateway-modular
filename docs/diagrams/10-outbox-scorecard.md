# Outbox vs direct `pgmq.send` — summary scorecard

> Atomicity is equivalent in a same-database setup; ten properties of the API contract differ.

## Scorecard

| Concern | Direct `pgmq.send` | Outbox |
|---|---|---|
| Atomicity with `events` row | = same DB, same tx | = same DB, same tx |
| Durability | = equivalent | = equivalent |
| API p99 latency | − correlated with queue load | + predictable |
| Lock contention with consumers | − on hot pgmq pages | + separate table |
| Behaviour during pgmq incident | − cascading 500s | + partners unaffected |
| Coupling to queue technology | − in the ingest path | + isolated to poller |
| Backpressure / rate-limit point | − none | + poller is the seam |
| Observability of delivery | − via pgmq only | + application-managed |
| Code complexity | + simpler (~5 lines) | − more (~100 lines) |
| Latency from accept to queue | + immediate | − +1 poll interval |

**Legend:** `+` better &nbsp;·&nbsp; `−` worse &nbsp;·&nbsp; `=` equivalent

## The framing that captures the difference

> Atomicity is the same in both options. What differs is *what API success means*. Direct send promises "everything downstream is healthy right now." Outbox promises "we have it, you can stop worrying."

For the long-form per-concern breakdown and the incident simulator, see `08-outbox-vs-direct-pgmq.md`.
