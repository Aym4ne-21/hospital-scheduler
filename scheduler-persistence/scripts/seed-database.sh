#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/database-common.sh"
"${PERSISTENCE_PSQL[@]}" --file="$PERSISTENCE_ROOT/db/seed/demo.sql"
