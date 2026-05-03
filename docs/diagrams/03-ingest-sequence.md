# Sequence: Event submission

End-to-end flow from partner POST through queue delivery. The dotted return arrow at the
top happens before the queue write — the partner gets their 200 OK as soon as the event
is durably committed to Postgres, not after pgmq has accepted the message.

```mermaid
sequenceDiagram
    autonumber
    actor Partner
    participant Filter as PartnerAuthFilter
    participant Verifier as HmacVerifier
    participant Ctrl as PartnerEventsController
    participant Ingest as EventIngestService
    participant Tx as DB transaction
    participant DB as PostgreSQL
    participant Poller as OutboxPoller
    participant PGMQ as pgmq queue

    Partner->>Filter: POST /api/v1/events<br/>Headers: X-Partner-Id, X-Timestamp,<br/>X-Signature, Idempotency-Key

    Filter->>DB: SELECT FROM partners WHERE partner_id = ?
    DB-->>Filter: partner row (secret_hash, active)

    Filter->>Verifier: verify(partner, ts, method, path, body, sig)
    Note over Verifier: 1. timestamp within ±5min<br/>2. HMAC-SHA256 over canonical msg<br/>3. constant-time compare
    Verifier-->>Filter: ok = true

    Filter->>Ctrl: forward request (partner_id attribute set)

    Ctrl->>Ingest: ingest(partner_id, eventId, body)

    activate Tx
    Ingest->>DB: SELECT * FROM events WHERE partner_id = ? AND event_id = ?
    DB-->>Ingest: existing row OR empty
    Note over Ingest,DB: SELECT first because the unique index<br/>includes created_at (partition key), so a<br/>single ON CONFLICT can't dedupe by<br/>(partner_id, event_id) alone

    alt existing row (duplicate)
        Note over Ingest: skip insert and outbox<br/>(already enqueued previously)
    else not found
        Ingest->>DB: INSERT INTO events ... ON CONFLICT DO NOTHING RETURNING *
        alt inserted (1 row returned)
            DB-->>Ingest: new row (status=RECEIVED)
            Ingest->>DB: INSERT INTO event_audit_log (null → RECEIVED)
            Ingest->>DB: INSERT INTO event_outbox (queue_name, payload, ...)
        else race lost (0 rows — concurrent insert won)
            Ingest->>DB: SELECT * FROM events WHERE partner_id = ? AND event_id = ?
            DB-->>Ingest: winner row
            Note over Ingest: skip outbox insert
        end
    end
    Ingest-->>Ctrl: IngestResult(row, newlyAccepted)
    deactivate Tx
    Note over Tx,DB: commit — events + audit + outbox written atomically

    Ctrl-->>Partner: 200 OK<br/>{eventId, status=RECEIVED, duplicate=false}

    Note over Poller,PGMQ: --- decoupled, ~250ms later ---

    Poller->>DB: SELECT FROM event_outbox<br/>ORDER BY id LIMIT 50 FOR UPDATE SKIP LOCKED
    DB-->>Poller: claimed rows
    loop per outbox row
        Poller->>PGMQ: SELECT pgmq.send(queue_name, payload)
        PGMQ-->>Poller: msg_id (discarded — outbox row is deleted)
        Poller->>DB: DELETE FROM event_outbox WHERE id
        Poller->>DB: UPDATE events SET status='PENDING' WHERE ...
        Poller->>DB: INSERT INTO event_audit_log (RECEIVED → PENDING)
    end
```

## Idempotency — duplicate path

When a partner retries the same `Idempotency-Key`, the initial `SELECT` by
`(partner_id, event_id)` finds the existing row and returns it without attempting an
`INSERT`. The outbox insert is skipped — there's already a row from the first
submission, sent or not. The response is 200 with `duplicate=true` and the original
event's current status (could be RECEIVED, PENDING, PROCESSING, PROCESSED, or FAILED).

The `ON CONFLICT DO NOTHING` clause on the `INSERT` is the backstop for the rare race
where two concurrent first-time submissions both pass the `SELECT`: the loser gets
zero rows back and re-`SELECT`s to read the winner.

The partner sees their retry succeed without ever knowing whether the original made it.
That's the whole point.

## Crash points and recovery

| When | What happens |
|---|---|
| HMAC fails | Filter throws 401, no DB writes |
| API crashes after `events` insert, before `outbox` insert | All of `events` + audit + `outbox` rolled back (same transaction) |
| Two concurrent first-time submissions race past the initial `SELECT` | Loser gets `rows = 0` from `INSERT`, re-`SELECT`s the winner, skips outbox |
| API crashes after commit, before response | Partner retries → idempotency catches it |
| Outbox poller crashes mid-batch | `FOR UPDATE` releases locks; another poller picks up next tick |
| `pgmq.send` fails | Outbox row retains, `attempts` increments, retried next tick |
| Postgres crashes | Both writes either both committed or both lost (atomic) |
