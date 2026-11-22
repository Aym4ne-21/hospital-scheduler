#!/usr/bin/env python3
"""Socket-free verification using PostgreSQL itself, for restricted sandboxes.

This is a verification adapter, not a replacement for the development migration
runner. Only the two psql commands used by these verification inputs are accepted.
An unsupported command or any unhandled backend SQL error makes the run fail.
"""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parent.parent


def sql_file(path: Path) -> str:
    result = []
    for line in path.read_text().splitlines():
        stripped = line.strip()
        if stripped.startswith("--"):
            continue
        if stripped.startswith(r"\set ON_ERROR_STOP "):
            continue
        if stripped.startswith(r"\ir "):
            result.append(sql_file((path.parent / stripped[4:]).resolve()))
        elif stripped.startswith("\\"):
            raise ValueError(f"Unsupported psql command in {path}: {stripped}")
        else:
            result.append(line)
    # -j reads a SQL batch up to an empty line. These trusted inputs use only
    # whole-line SQL comments, already removed above. Keep one batch per file.
    return " ".join(result) + "\n\n"


def migration_batches() -> list[str]:
    files = sorted((ROOT / "src/main/resources/db/migration").glob("V*.sql"),
                   key=lambda p: int(re.fullmatch(r"V(\d+)__[A-Za-z0-9_]+\.sql", p.name)[1]))
    batches = ["""BEGIN;
        SELECT pg_advisory_xact_lock(hashtext('project15:migrations'));
        CREATE TABLE IF NOT EXISTS public.database_schema_migration (
            version bigint PRIMARY KEY, filename text NOT NULL UNIQUE,
            sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
            applied_at timestamptz NOT NULL DEFAULT current_timestamp);
        """.replace("\n", " ") + "\n\n"]
    for file in files:
        version = int(re.fullmatch(r"V(\d+)__[A-Za-z0-9_]+\.sql", file.name)[1])
        digest = hashlib.sha256(file.read_bytes()).hexdigest()
        batches.extend([sql_file(file),
                        f"INSERT INTO public.database_schema_migration(version,filename,sha256) "
                        f"VALUES ({version},'{file.name}','{digest}');\n\n"])
    batches.append("COMMIT;\n\n")
    return batches


def verify(bindir: Path, data: Path) -> None:
    sql = migration_batches()
    sql.append(sql_file(ROOT / "db/verify/constraints.sql"))
    sql.append(sql_file(ROOT / "db/seed/demo.sql"))
    sql.append(sql_file(ROOT / "db/verify/replay.sql"))
    sql.append(sql_file(ROOT / "db/verify/demo.sql"))
    sql.append(sql_file(ROOT / "db/queries/experiments.sql"))
    sql.append(sql_file(ROOT / "db/verify/replanning.sql"))
    sql.append("SELECT 'PROJECT15_VERIFICATION_COMPLETE' AS verification_result;\n\n")
    process = subprocess.run(
        [str(bindir / "postgres"), "--single", "-j", "-D", str(data),
         "-c", "exit_on_error=off", "-c", "log_min_messages=notice",
         "-c", "search_path=hospital,public", "postgres"],
        input="".join(sql), text=True, capture_output=True, check=False)
    log = data.parent / "single-user.log"
    log.write_text(process.stdout + "\n" + process.stderr)
    if (process.returncode != 0 or 'verification_result = "PROJECT15_VERIFICATION_COMPLETE"' not in process.stdout
            or re.search(r"\b(?:ERROR|FATAL|PANIC):", process.stderr)):
        # Show the first cause before later transaction-aborted errors.
        error = re.search(r"^.*\b(?:ERROR|FATAL|PANIC):.*$", process.stderr, re.MULTILINE)
        if error:
            print(process.stderr[error.start():error.start() + 5000], file=sys.stderr)
        else:
            print(process.stderr[-5000:], file=sys.stderr)
        raise RuntimeError(f"PostgreSQL single-user verification failed; log: {log}")
    # COPY-like backend output is verbose, so print just assertions and counts.
    for line in process.stderr.splitlines():
        if "NOTICE:" in line:
            print(line.split("NOTICE:", 1)[1].strip())
    for match in re.finditer(r'table_name = "([^"\n]+)"[^\n]*\n[^\n]*actual = "(\d+)"', process.stdout):
        print(f"  {match[1]}: {match[2]}")
    print("PASS: PostgreSQL single-user SQL checks; 27 populated tables, 150 feasible confirmed requests, "
          "two candidates, 80 synthetic runs, 1680 fitness points, seed replay and rolled-back replanning.")
    print("LIMITATION: socket restrictions prevent exercising the psql migration entry point live here; "
          "SQL migrations and assertions ran in the actual PostgreSQL backend.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bindir", required=True, type=Path)
    parser.add_argument("--data", required=True, type=Path)
    args = parser.parse_args()
    try:
        verify(args.bindir, args.data)
    except (ValueError, RuntimeError, OSError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)
