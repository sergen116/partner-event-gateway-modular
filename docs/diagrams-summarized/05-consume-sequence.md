# 04-consume-sequence.md — Summary

Worker poll loop on a virtual thread; per-batch fan-out across more VTs bounded by Semaphore.

## Happy path
1. `pgmq.read(queue, vt=30s, qty=batch-size)` — read_ct increments per msg.
2. Fan out batch across VTs (e.g. concurrency=8). Each VT acquires Semaphore permit.
3. `EventProcessor.process(msg)`:
   - **Tx 1 (claim)**: `UPDATE events SET status='PROCESSING' WHERE status IN ('PENDING','PROCESSING')`. Commit.
   - **(no tx)**: dispatch to handler — handler + downstream call run **outside any DB transaction**.
   - **Tx 2 (finalize)**: `UPDATE events SET status='PROCESSED', processed_at=NOW()`. Commit.
4. `pgmq.delete(queue, msg_id)` → release Semaphore.
5. `CompletableFuture.allOf(...).orTimeout(VT-5s = 25s).join()`.
6. **Work-conserving loop**: full batch → sleep `busy-poll-interval-ms=20ms`. Partial/empty → sleep `poll-interval-ms=500ms`. Effective cycle under load = `batch_processing_time + 20ms`, not 500ms.

## On handler exception
- Tx 1 already committed (PROCESSING). Tx 2 never starts → row stays PROCESSING.
- VT (30s) expires → pgmq redelivers → read_ct increments.
- `read_ct < maxAttempts (5)`: do nothing, let pgmq redeliver.
- `read_ct >= maxAttempts`: `pgmq.archive(queue, msg_id)` + UPDATE events FAILED + audit row.

## Visibility timeout interaction
- VT=30s → invisible to other workers. If processing >30s or worker dies → reappears.
- Batch deadline = VT−5s = 25s via `orTimeout(25s)`. **On timeout don't cancel in-flight tasks** — letting them finish delete/archive is safer than risking double-delete.

## Why `tryMarkProcessing` accepts both PENDING and PROCESSING
If anything between Tx 1 and Tx 3 fails (handler exception, downstream timeout, SIGKILL, finalize fail), row is committed in PROCESSING. pgmq VT expires → redelivery → next worker's `tryMarkProcessing` finds PROCESSING. Allowing `PROCESSING → PROCESSING` lets that worker take over cleanly.

**Post-PROCESSED race**: SIGKILL after Tx 2 commits but before `pgmq.delete` → events row PROCESSED, pgmq doesn't know. On redelivery, `tryMarkProcessing` returns rows=0 (not in WHERE) → worker silently skips and deletes. One predicate handles both.

## DLQ inspection
```sql
SELECT * FROM pgmq.a_events_order_created ORDER BY archived_at DESC LIMIT 50;
SELECT * FROM events WHERE status='FAILED' ORDER BY processed_at DESC LIMIT 50;
```
Failed event has rows in BOTH places. Replay = re-INSERT into outbox from archive.
