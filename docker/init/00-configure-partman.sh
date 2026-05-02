#!/bin/bash
# Configures pg_partman_bgw to manage partition lifecycle automatically.
# Runs once when the postgres container is initialized (empty data dir).
set -euo pipefail

echo "[init] enabling pg_partman_bgw shared_preload_libraries..."

# Append to postgresql.conf if not already present.
PGDATA="${PGDATA:-/var/lib/postgresql/data}"
CONF="$PGDATA/postgresql.conf"

if ! grep -q "pg_partman_bgw" "$CONF"; then
    cat <<EOF >> "$CONF"

# --- pg_partman background worker (added by peg init) ---
shared_preload_libraries = 'pg_partman_bgw'
pg_partman_bgw.interval = 60
pg_partman_bgw.role = 'postgres'
pg_partman_bgw.dbname = 'events'
EOF
    echo "[init] postgresql.conf updated; pg_partman_bgw will start on next container restart"
else
    echo "[init] pg_partman_bgw already configured, skipping"
fi

echo "[init] done"
