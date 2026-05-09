# DEFENCE_PLAN.md vs Backend_Case.pdf — Coverage Audit

Cross-checking `DEFENCE_PLAN.md` against the case PDF — **mostly yes, but with a few notable gaps**. Here's the audit:

## ✅ Solidly covered

| Case requirement | Where in plan |
|---|---|
| Multi-tenant + partner identification | Seg 1, Seg 4 Demo D |
| Tenant isolation (no cross-partner submit/read) | Seg 4 Demo D |
| HTTP event submission | Seg 4 Demo A |
| Event lifecycle (RECEIVED→PENDING→PROCESSING→PROCESSED) | Seg 4 Demo A |
| Duplicate / idempotency | Seg 3 §2, Seg 4 Demo B |
| Auditability (who/when/state) | Seg 3 §3, Seg 4 Demo B SQL |
| Security (HMAC, replay, secret storage) | Seg 3 §1, Seg 4 Demo C, Seg 5 trade-off |
| Reliability (outbox, atomic txn) | Seg 3 §3-4 |
| Concurrency (race-loss branch, virtual threads) | Seg 3 §2, Q&A |
| Availability & Performance (2K TPS, KEDA) | Seg 5 |
| Maintainability (modular monolith) | Seg 2 |
| Internal vs external access scope | Seg 4 Demo D |
| Postman collection, OpenAPI, metrics | Pre-talk checklist + Demo E |
| Tests | Q&A row |

## ⚠️ Gaps worth fixing before the call

1. **Querying — Functional Req 5 is under-demoed.** The case explicitly requires pagination, configurable page size, and dynamic filtering by *partner / event type / status / date interval / business reference / processing outcome*, plus extensibility. Demo D only shows "own vs all" — no filter combinations, no `?page=`, `?eventType=`, `?status=`, `?from=…&to=…`, or business-reference filter. Add a **Demo D2** that runs ~3 filtered queries and mentions filter extensibility.

2. **The five event types are barely named.** OrderCreated / ShipmentStatusUpdated / ReturnRequested / DeliveryAddressUpdated / OrderCancelled appear once ("5 pgmq queues"). Drop them by name in Seg 1 or Seg 4 Demo A.

3. **No FAILED-state demo.** Lifecycle requirement explicitly lists "failed during processing." Demo C is HMAC reject (no event row written, so it's not a lifecycle FAILED). Either submit a payload that triggers handler failure → maxAttempts → FAILED, or explicitly say "FAILED is exercised in tests, here's the path" instead of leaving it for Q&A.

4. **Payload/data model not walked.** Case asks to "define and document a reasonable model" + ERD. Plan never opens `02-data-model.md` or shows a request payload. Add 30 sec in Seg 2 to point at the ERD and the request DTO.

5. **Request/event-type validation.** Case lists "validate the request / validate the event type" as ingest steps — not called out. One-liner in Seg 3 fixes it.

6. **README deliverable** (assumptions, limitations, time spent, trade-offs) — Seg 6 partially covers limitations, but assumptions and time spent aren't addressed. Worth a sentence.

## Verdict

The plan is strong on architecture, NFRs, and trade-offs (the parts most candidates fumble), but it under-weights **Functional Req 5 (querying)** and **the five event types** — both are explicit, named requirements in the spec. A 2–3 minute Demo D2 covering filtered/paged queries would close the biggest gap.
