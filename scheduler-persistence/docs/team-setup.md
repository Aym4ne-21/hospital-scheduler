# Database setup on the four team PCs

Aymane, Amine, Ilyass and Youness should use the same committed feature revision
and run a separate local PostgreSQL container on their own PC. Record the actual
results in [J1-IL](proofs/J1-IL.md); a written checklist is not evidence that a
PC passed.

## Requirements

Each PC needs Git, JDK 17 and Docker with Compose v2 and Linux containers. The
Maven wrapper supplies Maven 3.9.9. The first build needs Maven Central and the
container registry. Compare the full commit SHA on all four PCs.

From the repository root, check:

```bash
git rev-parse HEAD
java -version
docker info
docker compose version
```

PowerShell can use `.\mvnw.cmd -v`; Linux and Git Bash can use `bash mvnw -v`.
On Linux, Docker access must work for the same user that runs Maven. On Windows,
start Docker Desktop first.

## Start the local database

From the repository root, copy the example configuration only if you do not
already have a local `.env`.

Linux / Git Bash:

```bash
if [ ! -f scheduler-persistence/.env ]; then
    cp scheduler-persistence/.env.example scheduler-persistence/.env
fi
```

PowerShell:

```powershell
if (-not (Test-Path scheduler-persistence/.env)) {
    Copy-Item scheduler-persistence/.env.example scheduler-persistence/.env
}
```

Set a local `POSTGRES_PASSWORD` in `.env`. Keep the default database, user and
port unless the PC needs a different port. Never put the password in shared
evidence or a JDBC URL.

Then, from `scheduler-persistence`:

```bash
docker compose up --detach --wait postgres
docker compose exec -T postgres bash /workspace/scripts/migrate-database.sh
docker compose ps
docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -c "SELECT version, filename FROM public.database_schema_migration ORDER BY version;"
```

Expected: the service is healthy and migration versions 1, 2, 3 and 4 are recorded.
Repeating the migration command should report all four as already applied.
See [migration compatibility](database-setup.md#migrations) if this PC applied
the earlier development V002.

## Test the host connection

Use IntelliJ's Database tool to create a PostgreSQL data source and select **Test
Connection**:

| Setting | Default |
| --- | --- |
| Host / port | `127.0.0.1` / `55432` |
| Database / user | `project15_dev` / `project15` |
| Password | Local `POSTGRES_PASSWORD` from `.env` |
| JDBC URL | `jdbc:postgresql://127.0.0.1:55432/project15_dev?currentSchema=hospital,public` |

Then run `SELECT current_database(), current_user;` and record the result. If a
host `psql` client is installed, this also checks the host connection and prompts
for the password:

```bash
psql -h 127.0.0.1 -p 55432 -U project15 -d project15_dev -W -c "SELECT current_database(), current_user;"
```

## Run the migration tests

From the repository root, on Linux / Git Bash:

```bash
bash mvnw clean verify
bash mvnw -pl scheduler-persistence -am -Ppersistence-it verify
```

On Windows PowerShell:

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd -pl scheduler-persistence -am -Ppersistence-it verify
```

The integration test uses its own disposable Testcontainers PostgreSQL instance,
not the local Compose volume or `.env`. It should run 28 tests (six migration, seven hospital, eight schedule and seven exclusion tests) with no failures,
errors or skips. Attach its Failsafe text report and record each PC's OS, Java,
Maven, Docker/Compose versions, commit SHA, host connection result and command
outcomes in [J1-IL](proofs/J1-IL.md).

If Docker reports permission denied or Testcontainers cannot find Docker, fix
Docker access before recording a pass. The native SQL fallback is not a Docker or
Testcontainers pass. Use `docker compose logs --tail=100 postgres` for local logs;
`docker compose down` stops the service and retains its volume.
