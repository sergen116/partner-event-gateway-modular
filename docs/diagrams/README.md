# Diagrams

Mermaid markdown files render natively on GitHub. The interactive HTML files
(`07-`, `08-`) open in any browser and ship dark mode out of the box.

## Index

### Reference diagrams (Mermaid)

- [`01-system-overview.md`](01-system-overview.md) — high-level component diagram
- [`02-erd.md`](02-erd.md) — entity-relationship diagram
- [`03-ingest-sequence.md`](03-ingest-sequence.md) — event submission flow
- [`04-consume-sequence.md`](04-consume-sequence.md) — event processing flow
- [`05-state-machine.md`](05-state-machine.md) — event lifecycle states
- [`06-stage2-topology.md`](06-stage2-topology.md) — Stage 2 production deployment

### Design rationale (interactive HTML)

- [`07-scaling-bottlenecks.html`](07-scaling-bottlenecks.html) — partition vs shard write distribution, with diagnostic rule
- [`08-outbox-vs-direct-pgmq.html`](08-outbox-vs-direct-pgmq.html) — six operational distinctions between the outbox pattern and direct `pgmq.send`, plus an incident simulator
- [`09-actual-scaling-bottlenecks.html`](09-actual-scaling-bottlenecks.html) — the five real bottlenecks, the cost-ordered escalation ladder, and the partitioning-myth correction
- [`10-outbox-scorecard.html`](10-outbox-scorecard.html) — the outbox-vs-direct trade-off as a single at-a-glance scorecard table
