# Diagrams

All diagrams are Mermaid markdown files that render natively on GitHub and in
any IDE with Mermaid support. Some files (07–10) were originally interactive
HTML; they're now Markdown with the same content (tables + Mermaid where it
helps) so they render alongside the rest of the docs.

## Index

### Reference diagrams

- [`01-system-overview.md`](01-system-overview.md) — high-level component diagram
- [`02-erd.md`](02-erd.md) — entity-relationship diagram
- [`03-ingest-sequence.md`](03-ingest-sequence.md) — event submission flow
- [`04-consume-sequence.md`](04-consume-sequence.md) — event processing flow
- [`05-state-machine.md`](05-state-machine.md) — event lifecycle states
- [`06-stage2-topology.md`](06-stage2-topology.md) — Stage 2 production deployment

### Design rationale

- [`07-scaling-bottlenecks.md`](07-scaling-bottlenecks.md) — partition vs shard write distribution, with diagnostic rule
- [`08-outbox-vs-direct-pgmq.md`](08-outbox-vs-direct-pgmq.md) — six operational distinctions between the outbox pattern and direct `pgmq.send`, plus an incident simulator
- [`09-actual-scaling-bottlenecks.md`](09-actual-scaling-bottlenecks.md) — the five real bottlenecks, the cost-ordered escalation ladder, and the partitioning-myth correction
- [`10-outbox-scorecard.md`](10-outbox-scorecard.md) — the outbox-vs-direct trade-off as a single at-a-glance scorecard table
- [`11-scaling-levers.md`](11-scaling-levers.md) — every documented scaling lever in one ladder-ordered inventory, with what each fixes, what it doesn't, and current implementation status
