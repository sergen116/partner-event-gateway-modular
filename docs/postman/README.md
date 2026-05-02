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

## Idempotency demo

The submission requests use `{{$guid}}` for the `Idempotency-Key` header, which generates
a fresh UUID each run. To test idempotency, pin the key to a constant:

1. Edit any submit request, change `{{$guid}}` to e.g. `00000000-0000-0000-0000-000000000001`.
2. Send the request twice.
3. First response: `"duplicate": false, "status": "RECEIVED"`.
4. Second response: `"duplicate": true, "status": "PROCESSED"` (or whatever it reached).

The events table will have exactly one row.
