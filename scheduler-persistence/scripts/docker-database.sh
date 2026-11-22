#!/usr/bin/env bash
# Local PostgreSQL commands for the Day 1 schema.
set -euo pipefail
persistence_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
usage() {
    echo 'Usage: scripts/docker-database.sh {up|status|logs|migrate|down}'
}
if [[ $# -ne 1 ]]; then usage >&2; exit 2; fi
case "$1" in
    up|status|logs|migrate|down) ;;
    *) usage >&2; exit 2 ;;
esac
command -v docker >/dev/null || { echo 'ERROR: install Docker with the Compose plugin first.' >&2; exit 1; }
docker compose version >/dev/null || { echo 'ERROR: Docker Compose v2 is required.' >&2; exit 1; }
compose=(docker compose --project-directory "$persistence_root" --file "$persistence_root/compose.yaml")
case "$1" in
    up) "${compose[@]}" up --detach --wait postgres ;;
    status) "${compose[@]}" ps ;;
    logs) "${compose[@]}" logs --tail=100 postgres ;;
    migrate) "${compose[@]}" exec -T postgres bash /workspace/scripts/migrate-database.sh ;;
    down) "${compose[@]}" down ;;
esac
