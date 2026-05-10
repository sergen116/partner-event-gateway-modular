# 03-ingest-sequence.md — Summary

End-to-end POST flow. **Partner gets 200 OK as soon as Postgres commits — before pgmq sees the message.**

## Flow
1. POST `/api/v1/events` with `X-Partner-Id`, `X-Timestamp`, `X-Signature`, `Idempotency-Key`.
2. `PartnerAuthFilter` → cache lookup → on miss, load from `partners` (60s TTL).
3. `HmacVerifier`: timestamp ±5 min → HMAC-SHA256 over canonical msg → constant-time compare.
   - **On verify fail**: invalidate cache entry, reload from DB, retry once. Handles rotation transparently.
4. `EventIngestService.ingest` (one transaction):
   - SELECT events WHERE `(partner_id, event_id)` — done first because unique index includes `created_at` (partition key) so `ON CONFLICT` alone can't dedupe by `(partner_id, event_id)` cleanly.
   - **If exists** → duplicate path, skip insert+outbox, return existing row.
   - **Else** → INSERT events `ON CONFLICT DO NOTHING RETURNING *`:
     - 1 row → INSERT audit (null→RECEIVED) + INSERT outbox.
     - 0 rows (race lost) → re-SELECT winner, skip outbox.
5. Commit atomically. Return 200 `{eventId, status=RECEIVED, duplicate=false}`.
6. **Decoupled, ~250ms later**: `OutboxPoller` claims via SKIP LOCKED batch=50, per row: `pgmq.send` → DELETE outbox → UPDATE events PENDING → INSERT audit (RECEIVED→PENDING).

## Idempotency duplicate path
Same `Idempotency-Key` retry: SELECT finds row → returns 200 + `duplicate=true` + current status (could be any of 5 states). `ON CONFLICT DO NOTHING` is the backstop for two concurrent first-time submissions racing past the SELECT.

## Crash points
| When | Recovery |
|---|---|
| HMAC fails | 401, no DB writes |
| HMAC fails on cached partner (rotation) | Cache invalidated, retry once |
| API crashes after events insert before outbox | Both rolled back (same tx) |
| Race past SELECT | Loser sees rows=0 from INSERT, re-SELECTs |
| API crashes after commit before response | Partner retries → idempotency catches it |
| Outbox poller crashes mid-batch | FOR UPDATE locks released, next poller picks up |
| `pgmq.send` fails | Outbox row stays, `attempts` increments, retried next tick |
| Postgres crashes | Both writes atomic — both committed or both lost |
