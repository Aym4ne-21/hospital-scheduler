#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/database-common.sh"
command -v sha256sum >/dev/null || { echo 'ERROR: sha256sum is required.' >&2; exit 1; }
driver=$(mktemp /tmp/project15-migrate.XXXXXX.sql)
trap 'rm -f -- "$driver"' EXIT
cat > "$driver" <<'SQL'
\set ON_ERROR_STOP on
SELECT pg_advisory_xact_lock(hashtext('project15:migrations'));
CREATE TABLE IF NOT EXISTS public.database_schema_migration (
    version bigint PRIMARY KEY,
    filename text NOT NULL UNIQUE,
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    applied_at timestamptz NOT NULL DEFAULT current_timestamp
);
SQL
shopt -s nullglob
migrations=("$PERSISTENCE_ROOT"/src/main/resources/db/migration/V*.sql)
(( ${#migrations[@]} > 0 )) || { echo 'ERROR: no numbered migrations found.' >&2; exit 1; }
# Sort by numeric version, not lexically (V2 precedes V10).
while IFS= read -r filename; do
    [[ $filename =~ ^V([0-9]+)__[A-Za-z0-9_]+\.sql$ ]] || {
        echo "ERROR: unsupported migration filename: $filename" >&2; exit 1;
    }
    version=$((10#${BASH_REMATCH[1]}))
    file="$PERSISTENCE_ROOT/src/main/resources/db/migration/$filename"
    checksum=$(sha256sum "$file")
    checksum=${checksum%% *}
    # Filenames are validated above. SQL/psql-escape the absolute include path.
    include_path=${file//\\/\\\\}
    include_path=${include_path//\'/\'\'}
    cat >> "$driver" <<SQL
DO \$migration\$
BEGIN
    IF EXISTS (SELECT 1 FROM public.database_schema_migration
               WHERE version=$version AND (sha256<>'$checksum' OR filename<>'$filename')) THEN
        RAISE EXCEPTION 'Migration $filename changed after application; add a new migration.';
    END IF;
END;
\$migration\$;
SELECT NOT EXISTS (SELECT 1 FROM public.database_schema_migration WHERE version=$version) AS apply_migration \gset
\if :apply_migration
\echo Applying $filename
\i '$include_path'
INSERT INTO public.database_schema_migration(version,filename,sha256)
    VALUES ($version,'$filename','$checksum');
\else
\echo Already applied: $filename
\endif
SQL
done < <(printf '%s\n' "${migrations[@]##*/}" | sort -t_ -k1.2,1n)
"${PERSISTENCE_PSQL[@]}" --single-transaction --file="$driver"
