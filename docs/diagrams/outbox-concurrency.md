# Outbox concurrency: are concurrent reads and writes a problem?

**Short answer: no, the current design handles it cleanly.** Concrete reasons, grounded in the code:

## 1. No row-level lock conflict
- Writer (`OutboxRepository.insert`, line 36): plain `INSERT`. Takes no locks on existing rows; only writes a new tuple.
- Reader (`OutboxRepository.claimBatch`, lines 48–55): `SELECT … FOR UPDATE SKIP LOCKED` locks only the rows it claims.

Different rows → no contention.

## 2. MVCC handles visibility correctly
A poller running mid-INSERT just doesn't see the not-yet-committed row. The next 250ms poll picks it up. Worst case: ~250ms latency, never lost.

## 3. No deadlock possible between ingest and poller
- Writer order (`EventIngestService.ingest`, line 38, `@Transactional`): lock events row → insert outbox row → COMMIT.
- Reader order (`OutboxPoller.drain`, lines 87–102): claim outbox row → `pgmq.send` → delete outbox row → update events row.

The reader can't even *see* the outbox row until the writer has committed and released its locks. They never hold conflicting locks at the same time.

## 4. `SKIP LOCKED` lets multiple pollers coexist
If/when API runs multi-pod, two pollers grab disjoint batches — no coordination needed. (Already noted at `OutboxPoller.java:24`.)

## 5. `BIGSERIAL` doesn't bottleneck
Sequence allocation is non-transactional and uses local caching. Concurrent inserters never wait on each other for ids.

## 6. BTREE hotspot is negligible at this scale
All inserts land at the right edge of `ix_outbox_id`; all polls read from the left edge. They're operating on different pages most of the time. At the documented 1k events/sec target, no measurable contention.

## Real things to keep in mind (none are correctness bugs)

- **No strict FIFO across partners.** `ORDER BY id LIMIT 50` plus multiple concurrent writers means insertion order is the commit order of inserts, not request order. Within a single client connection, sequential. (pgmq itself doesn't guarantee cross-producer order anyway.)
- **Long writer transactions delay delivery.** If `ingest()` runs a slow downstream call inside its `@Transactional`, the outbox row is invisible until commit. Right now `ingest()` is short — events insert + outbox insert + JSON serialize — so this is fine. Just don't let the transaction grow.
- **Dead-tuple churn.** Every successful row is `INSERT`ed then `DELETE`d, so the table is high-churn but tiny (~250 rows steady state). Autovacuum keeps up trivially. If write rate ever sustains well above 1k/sec, watch `pg_stat_user_tables.n_dead_tup` for `event_outbox` to confirm autovacuum is winning.
- **At-most-batch atomicity, not per-row.** `drain()` runs the entire batch in one transaction (line 87). If `pgmq.send` for row N throws, the JDBC tx is in aborted state, so the catch block's `recordFailure` will also fail and the whole batch rolls back. Net effect: nothing duplicated, nothing lost — but `recordFailure` / `last_error_at` won't actually persist after a `pgmq.send` failure. That's a design quirk separate from concurrency, worth a follow-up if accurate failure metrics matter.

## Bottom line

Ingest writes and poller reads do not step on each other. The design is the textbook transactional outbox pattern, correctly applied.
