#!/usr/bin/env bash
# Sourced by the migration entry point; never prints credentials.
set -euo pipefail
PERSISTENCE_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if ! command -v psql >/dev/null 2>&1; then
    echo 'ERROR: psql is required. Install PostgreSQL client tools.' >&2
    exit 1
fi
PERSISTENCE_PSQL=(psql -X --set=ON_ERROR_STOP=1)
if [[ -n ${DATABASE_URL:-} ]]; then
    PERSISTENCE_PSQL+=(--dbname="$DATABASE_URL")
elif [[ -z ${PGDATABASE:-} ]]; then
    echo 'ERROR: set PGDATABASE (and PGHOST/PGUSER) or DATABASE_URL explicitly.' >&2
    exit 1
fi
