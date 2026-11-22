#!/usr/bin/env bash
# Docker commands for the local development database and an isolated SQL verifier.
set -euo pipefail
persistence_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
usage() {
    echo 'Usage: scripts/docker-database.sh {up|status|logs|migrate|seed|verify|down}'
}
if [[ $# -ne 1 ]]; then usage >&2; exit 2; fi
case "$1" in
    up|status|logs|migrate|seed|verify|down) ;;
    *) usage >&2; exit 2 ;;
esac
command -v docker >/dev/null || { echo 'ERROR: install Docker with the Compose plugin first.' >&2; exit 1; }
docker compose version >/dev/null || { echo 'ERROR: Docker Compose v2 is required.' >&2; exit 1; }
compose=(docker compose --project-directory "$persistence_root" --file "$persistence_root/compose.yaml")
case "$1" in
    up) "${compose[@]}" up --detach --wait postgres ;;
    status) "${compose[@]}" ps ;;
    logs) "${compose[@]}" logs --tail=100 postgres ;;
    migrate|seed)
        "${compose[@]}" exec -T postgres bash "/workspace/scripts/$1-database.sh"
        ;;
    verify)
        # A separate container, no host port, and a new cluster in /tmp. The
        # verifier never uses the development cluster or PGDATA volume.
        POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-verification_only}" "${compose[@]}" run --rm --no-deps -T --user postgres \
            --env PG_BINDIR=/usr/lib/postgresql/18/bin --entrypoint bash \
            postgres /workspace/scripts/verify-database.sh
        ;;
    down) "${compose[@]}" down ;;
esac
