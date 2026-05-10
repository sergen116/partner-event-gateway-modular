# Interview Cheat-Sheet — Index

Short summaries of every doc, for quick pre-interview recall. Pair with the full docs in `docs/` when you need the long form.

| File | Source | What to recall |
|---|---|---|
| [01-architecture.md](01-architecture.md) | `ARCHITECTURE.md` | Modules, storage, NFRs, all 13 ADRs |
| [02-system-overview.md](02-system-overview.md) | `diagrams/01-system-overview.md` | Component map, module dep graph, runtime roles |
| [03-erd.md](03-erd.md) | `diagrams/02-erd.md` | 4 app tables, partitioning, why no FKs |
| [04-ingest-sequence.md](04-ingest-sequence.md) | `diagrams/03-ingest-sequence.md` | Submit flow, idempotency race, crash matrix |
| [05-consume-sequence.md](05-consume-sequence.md) | `diagrams/04-consume-sequence.md` | Worker loop, two-tx claim/finalize, DLQ |
| [06-state-machine.md](06-state-machine.md) | `diagrams/05-state-machine.md` | 5 states, atomic transitions, audit trail |
| [07-stage2-topology.md](07-stage2-topology.md) | `diagrams/06-stage2-topology.md` | K8s topology, KEDA, sizing for 2K TPS |
| [08-scaling-and-tradeoffs.md](08-scaling-and-tradeoffs.md) | `diagrams/07-scaling-and-tradeoffs.md` | 5-layer diagnostic, 20-lever ladder, partition vs shard |
| [09-cryptography.md](09-cryptography.md) | `playground/cryptography/*` | HMAC, SHA-256, hex vs base64 |
| [10-virtual-threads.md](10-virtual-threads.md) | `playground/virtual-threads/*` | Pinning, pgjdbc safety, why Semaphore |
| [11-defence-plan.md](11-defence-plan.md) | `playground/defence/*` | 30-min interview script, 6 segments |

## The 5 NFRs every decision serves

1. **Per-tenant isolation** — partners can't see/affect each other.
2. **At-least-once + idempotency** — accepted ≠ lost; duplicates ≠ double-processed.
3. **Independent scaling per event type** — different profiles, separate queues/pods.
4. **Auditability** — every transition immutably logged, atomic with the op write.
5. **Production-ready ops** — observability, graceful shutdown, retries, DLQ, partition lifecycle.

## The 3 strongest design calls (lead with these)

1. **Outbox over direct `pgmq.send`** even though same DB → not for atomicity, but for broker-future-proofing + queue-agnostic ingest module + shorter API tx.
2. **Per-event-type queues** → independent scaling profiles; cost = 5× ops surface.
3. **JdbcTemplate, no JPA** → pgmq is JDBC-native; 5-table schema with no entity graph; partition pruning needs raw SQL.
