# Diagrams

Mermaid markdown files. Render natively on GitHub and in any IDE with Mermaid
support.

## Reference diagrams

The case spec asks for an ERD and at least one sequence/flow diagram. The first
six files cover that and a little more — together they let a reviewer
reconstruct the system without reading source.

- [`01-system-overview.md`](01-system-overview.md) — high-level component diagram + module dependency graph
- [`02-erd.md`](02-erd.md) — entity-relationship diagram with constraints, indexes, and partitioning
- [`03-ingest-sequence.md`](03-ingest-sequence.md) — event submission flow (HMAC → ingest → outbox → pgmq)
- [`04-consume-sequence.md`](04-consume-sequence.md) — event processing flow (worker poll → claim → dispatch → terminal)
- [`05-state-machine.md`](05-state-machine.md) — event lifecycle states and transition rules
- [`06-stage2-topology.md`](06-stage2-topology.md) — the deployed evolution from `CONSUMER_ALL` Stage 1 into per-role Deployments

## Design rationale

- [`07-scaling-and-tradeoffs.md`](07-scaling-and-tradeoffs.md) — outbox-vs-direct scorecard, scaling diagnostic (5 layers), lever inventory, and the partitioning-vs-sharding distinction. Companion to ADRs in [`../ARCHITECTURE.md`](../ARCHITECTURE.md).
