# 05-state-machine.md — Summary

5 states. Transitions are atomic UPDATE-with-WHERE-status. No app locks, no read-then-write.

## States
| State | Meaning | Set by |
|---|---|---|
| RECEIVED | events row + outbox row committed; durable but not in pgmq yet | API ingest |
| PENDING | OutboxPoller called `pgmq.send`, in queue, awaiting consumer | OutboxPoller |
| PROCESSING | Worker claimed row, actively processing | Worker `tryMarkProcessing` |
| PROCESSED | Handler succeeded, `pgmq.delete` called. **Terminal** | EventProcessor |
| FAILED | `read_ct >= maxAttempts (5)`, archived to DLQ. **Terminal** | Worker |

## Transitions
- RECEIVED → PENDING (poller forward)
- PENDING → PROCESSING (worker claim)
- PROCESSING → PROCESSED (handler ok)
- **PROCESSING → PROCESSING** (handler/worker died → pgmq VT redelivers, next worker reclaims via PENDING-or-PROCESSING filter)
- PROCESSING → FAILED (max attempts hit → DLQ)

## Concurrency safety — single mechanism
Every transition is `UPDATE ... WHERE status IN (...)`. PostgreSQL `READ COMMITTED` + atomic UPDATE = only one of N concurrent updaters wins. Losers see `rows=0` and silently skip. No locks needed beyond pgmq's own SKIP LOCKED for read claim.

## NOT a state transition
`Idempotency-Key` retries don't transition state. `INSERT ... ON CONFLICT DO NOTHING` returns 0 rows; API reads existing row's current state and returns it.

## Audit invariant
Every transition writes an audit row in the **same transaction** as the UPDATE. Examples:
- Successful PROCESSED event has 4 audit rows: `null→RECEIVED`, `RECEIVED→PENDING`, `PENDING→PROCESSING`, `PROCESSING→PROCESSED`.
- FAILED has 4 ending in `PROCESSING→FAILED` with reason in `error` col.
- Redelivered+recovered event has 5+ rows (one or more `PROCESSING→PROCESSING`).

24-mo audit retention vs 12-mo events → late-arriving compliance lookups still work.
