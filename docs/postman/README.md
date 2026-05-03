# Postman collection

## Files

- `partner-event-gateway.postman_collection.json` — request collection with HMAC pre-request scripts
- `local-acme.postman_environment.json` — `partner-acme` credentials for local dev
- `local-globex.postman_environment.json` — `partner-globex` credentials for local dev

## Usage

1. **Import** both the collection and one of the environments into Postman.
2. **Select** the environment (top-right dropdown).
3. **Run** any request — the pre-request script signs the request automatically.

The pre-request script:

- Computes `SHA-256(secret)` to derive the HMAC key
- Builds the canonical message: `partnerId\ntimestamp\nMETHOD\npath\nbody`
- Signs with HMAC-SHA256 and base64-encodes the result
- Sets `X-Partner-Id`, `X-Timestamp`, `X-Signature` headers

It runs only on partner endpoints (`/api/v1/events*`) and skips internal/swagger/actuator
paths so those work without modification.

## Tenant isolation demo

Run the same `Query own events` request twice — once with the Acme environment, once
with Globex. Each partner sees only their own events. Switching partners is a one-click
environment swap.

The submit requests' test scripts capture the returned `eventId` into a collection
variable, so `Get specific event` works straight away without any manual setup.

## Idempotency demo

Use the pre-built `Submit event — replay (fixed Idempotency-Key)` request. Its
`Idempotency-Key` is hardcoded to `00000000-0000-0000-0000-000000000001`.

1. Send it once. Response: `"duplicate": false, "status": "RECEIVED"`.
2. Send it again. Response: `"duplicate": true, "status": "PROCESSED"` (or whatever
   status the original event reached by then).

The events table will have exactly one row. To repeat the demo from a clean slate,
either bump the key to a new constant UUID or wait for the next calendar month
(uniqueness is scoped to `(partner_id, event_id, created_at month)`).
