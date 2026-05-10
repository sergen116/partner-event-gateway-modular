#!/usr/bin/env bash
# Fires COUNT HMAC-signed OrderCreated events at the local gateway in parallel.
# Defaults: 50 events, 50 concurrent submissions, partner-acme.
#
#   scripts/load-test-order-created.sh
#   COUNT=200 PARALLEL=20 scripts/load-test-order-created.sh
#
# Each subshell prints the HTTP status it got back (expect 202 for all).

set -euo pipefail

HOST="${HOST:-http://localhost:8080}"
PARTNER="${PARTNER:-partner-acme}"
SECRET="${SECRET:-acme-shared-secret-2024}"
COUNT="${COUNT:-50}"
PARALLEL="${PARALLEL:-50}"
URL_PATH="/api/v1/events"

KEY_HEX=$(printf %s "$SECRET" | openssl dgst -sha256 -binary | xxd -p -c 256)
RUN_TS=$(date -u +%Y%m%dT%H%M%SZ)

export HOST PARTNER KEY_HEX URL_PATH RUN_TS

submit_one() {
    local i="$1"
    local ts body canon sig idem
    ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    body="{\"eventType\":\"OrderCreated\",\"businessRef\":\"LOAD-${RUN_TS}-${i}\",\"payload\":{\"total\":${i}}}"
    canon=$(printf "%s\n%s\n%s\n%s\n%s" "$PARTNER" "$ts" "POST" "$URL_PATH" "$body")
    sig=$(printf "%s" "$canon" | openssl dgst -sha256 -mac HMAC -macopt "hexkey:$KEY_HEX" -binary | base64)
    idem=$(uuidgen)

    curl -s -o /dev/null -w "%{http_code}\n" \
        -X POST "${HOST}${URL_PATH}" \
        -H "Content-Type: application/json" \
        -H "X-Partner-Id: $PARTNER" \
        -H "X-Timestamp: $ts" \
        -H "X-Signature: $sig" \
        -H "Idempotency-Key: $idem" \
        -d "$body"
}
export -f submit_one

echo "submitting $COUNT OrderCreated events to $HOST (parallelism=$PARALLEL, partner=$PARTNER)" >&2

seq 1 "$COUNT" | xargs -n1 -P "$PARALLEL" -I{} bash -c 'submit_one "$@"' _ {}
