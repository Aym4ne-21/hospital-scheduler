#!/usr/bin/env bash
# Always creates its own cluster. No existing cluster or database is modified.
set -euo pipefail
persistence_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ $# -gt 0 ]]; then
    echo 'Usage: scripts/verify-database.sh (optional PG_BINDIR=/path/to/postgresql/bin)' >&2
    exit 2
fi
if [[ -z ${PG_BINDIR:-} ]]; then
    command -v pg_config >/dev/null || { echo 'ERROR: install PostgreSQL server tools or set PG_BINDIR.' >&2; exit 1; }
    PG_BINDIR=$(pg_config --bindir)
fi
for tool in initdb pg_ctl createdb psql; do
    [[ -x "$PG_BINDIR/$tool" ]] || { echo "ERROR: missing $PG_BINDIR/$tool; install PostgreSQL server and contrib." >&2; exit 1; }
done
if [[ $(id -u) -eq 0 ]]; then
    echo 'ERROR: initdb cannot run as root. Run this script as an unprivileged user.' >&2
    exit 1
fi
export PATH="$PG_BINDIR:$PATH"
scratch=$(mktemp -d /tmp/project15-verify.XXXXXX)
running=0
cleanup() {
    result=$?
    trap - EXIT
    if (( running )); then
        "$PG_BINDIR/pg_ctl" -D "$scratch/data" -m immediate -w stop >> "$scratch/server.log" 2>&1 || true
    fi
    if (( result != 0 )); then
        echo 'ERROR: database verification failed; recent server output follows.' >&2
        tail -n 25 "$scratch/server.log" >&2 2>/dev/null || true
    fi
    if [[ ${KEEP_VERIFY_ARTIFACTS:-0} == 1 ]]; then
        echo "Stopped verification cluster and logs retained at: $scratch"
    else
        rm -rf -- "$scratch"
    fi
    exit "$result"
}
trap cleanup EXIT
# A private Unix socket, no TCP listener, and a new database isolate this run.
unset DATABASE_URL PGSERVICE PGSERVICEFILE PGHOSTADDR PGOPTIONS PGPASSWORD PGSSLMODE PGTARGETSESSIONATTRS
export PGHOST="$scratch/socket" PGPORT=55432 PGUSER=project15_verify PGDATABASE=project15_verify
mkdir -m 700 "$PGHOST"
"$PG_BINDIR/initdb" -D "$scratch/data" --username="$PGUSER" --encoding=UTF8 --no-locale \
    --auth-local=trust --auth-host=reject > "$scratch/initdb.log" 2>&1
if ! "$PG_BINDIR/pg_ctl" -D "$scratch/data" -l "$scratch/server.log" \
    -o "-F -c listen_addresses='' -k $PGHOST -p $PGPORT" -w start; then
    if grep -Eq 'could not bind Unix address.*(Operation not permitted|Permission denied)' "$scratch/server.log"; then
        echo 'Socket creation prohibited; using the real PostgreSQL single-user backend.'
        command -v python3 >/dev/null || { echo 'ERROR: Python 3 is required for socket-free verification.' >&2; exit 1; }
        python3 "$persistence_root/scripts/verify-single-user.py" --bindir "$PG_BINDIR" --data "$scratch/data"
        exit 0
    fi
    exit 1
fi
running=1
"$PG_BINDIR/createdb" "$PGDATABASE"
echo '1/6 Apply migrations on an empty database.'
"$persistence_root/scripts/migrate-database.sh"
echo '2/6 Replay migrations; verify extension, exclusions and transactional constraints.'
"$persistence_root/scripts/migrate-database.sh"
psql -X --set=ON_ERROR_STOP=1 --file="$persistence_root/db/verify/constraints.sql"
echo '3/6 Load the synthetic dataset.'
"$persistence_root/scripts/seed-database.sh"
echo '4/6 Replay the seed; compare every application table and preserve a local edit.'
psql -X --set=ON_ERROR_STOP=1 --file="$persistence_root/db/verify/replay.sql"
echo '5/6 Verify counts, feasibility and experiment convergence.'
psql -X --set=ON_ERROR_STOP=1 --file="$persistence_root/db/verify/demo.sql"
psql -X --set=ON_ERROR_STOP=1 --file="$persistence_root/db/queries/experiments.sql"
echo '6/6 Exercise the documented absence/replanning transaction and roll it back.'
psql -X --set=ON_ERROR_STOP=1 --file="$persistence_root/db/verify/replanning.sql"
echo 'PASS: migrations, both global exclusions, seed replay, 150 feasible confirmed requests, candidates, 80 synthetic runs and 1680 fitness points.'
