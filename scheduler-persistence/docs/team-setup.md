# Database setup on the four team PCs

Aymane, Amine, Ilyass and Youness each run a local PostgreSQL container with the
same schema and synthetic seed. Each PC has its own database, volume and local
password. Record each member's results in the
[Day 1 verification record](proofs/J1-IL.md); instructions alone do not establish
that a PC has passed.

## 1. Use the same source and tools

Use the committed database feature branch once it is published, or `develop`
after the feature is merged. Compare the full commit SHA across all four PCs.
Use a clean checkout for the shared verification; uncommitted changes are not
identified by the commit SHA. From the repository root:

```bash
git rev-parse HEAD
git status --short
java -version
docker info
docker compose version
```

All four PCs need JDK 17, Git and a running Docker installation with Compose v2
and Linux containers. The Maven wrapper downloads Maven 3.9.9; a separate Maven
installation is not required. Initial builds need access to Maven Central and
the container registry. The test image is `postgres:18.7-bookworm`.

Verify Maven's actual Java runtime, since it can differ from the IDE:

| Terminal | Command from repository root |
| --- | --- |
| Linux / Git Bash | `bash mvnw -v` |
| Windows PowerShell | `.\mvnw.cmd -v` |

In IntelliJ, also set the project SDK and Maven runner JRE to JDK 17. On Windows,
start Docker Desktop before testing. On Linux, `docker info` must succeed for
the same user running Maven. Docker errors at this stage must be resolved before
claiming a successful Testcontainers run.

## 2. Create the local configuration

Create the file only if it does not already exist, preserving an existing local
password. Run from the repository root.

Linux / Git Bash:

```bash
if [ ! -f scheduler-persistence/.env ]; then
    cp scheduler-persistence/.env.example scheduler-persistence/.env
fi
```

Windows PowerShell:

```powershell
if (-not (Test-Path scheduler-persistence/.env)) {
    Copy-Item scheduler-persistence/.env.example scheduler-persistence/.env
}
```

Edit `scheduler-persistence/.env` locally. Set `POSTGRES_PASSWORD` to a password
chosen on that PC. Keep `POSTGRES_DB=project15_dev`, `POSTGRES_USER=project15` and
`PROJECT15_DB_PORT=55432` unless that PC needs another port. The file is ignored
by Git. Record only variable names and non-secret connection settings in reports.

## 3. Start, migrate and seed

These commands work in both PowerShell and Bash. From the repository root:

```bash
cd scheduler-persistence
docker compose up --detach --wait postgres
docker compose exec -T postgres bash /workspace/scripts/migrate-database.sh
docker compose exec -T postgres bash /workspace/scripts/seed-database.sh
docker compose ps
```

The service should be healthy and publish `127.0.0.1:55432`. Repeat the migration
command: it should report V001–V003 as already applied. Repeating the seed should
report that the dataset already exists and preserve current rows.

Check the connection and migration journal from the container:

```bash
docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -c "SELECT current_database(), current_user;"
docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -c "SELECT version, filename FROM public.database_schema_migration ORDER BY version;"
docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -c "SELECT status, count(*) FROM hospital.appointment GROUP BY status ORDER BY status;"
```

Expected with the default configuration and a freshly seeded database:
`project15_dev` / `project15`, migration versions 1–3, 150 `CONFIRMED` appointments
and 300 `CANDIDATE` appointments. Existing edited datasets may have other counts;
the seed does not reset them.

## 4. Check the connection from each host PC

In IntelliJ's Database tool, create a PostgreSQL data source and use **Test
Connection** with the settings below. This checks the host-side connection;
the preceding container commands only check the connection inside the container.

| Setting | Default value on each PC |
| --- | --- |
| Host / port | `127.0.0.1` / `55432` |
| Database / user | `project15_dev` / `project15` |
| Password | That PC's local `POSTGRES_PASSWORD` |
| JDBC URL | `jdbc:postgresql://127.0.0.1:55432/project15_dev?currentSchema=hospital,public` |

Adjust these settings to match that PC's `.env` if its defaults changed. Execute
`SELECT current_database(), current_user;` in the IDE SQL console and record the
result. Keep the password outside the URL and any shared evidence. The current
application has no database startup wiring; launching its placeholder `Main`
does not test a connection.

If a host `psql` client is available, this is an additional connection check that
prompts for the password instead of putting it in shell history:

```bash
psql -h 127.0.0.1 -p 55432 -U project15 -d project15_dev -W -v ON_ERROR_STOP=1 -c "SELECT current_database(), current_user;"
```

## 5. Run the build and integration suite

Return to the repository root with `cd ..`, then use the commands for that PC.

Linux / Git Bash:

```bash
bash mvnw clean verify
bash mvnw -pl scheduler-persistence -am -Pdatabase-it verify
```

Windows PowerShell:

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd -pl scheduler-persistence -am -Pdatabase-it verify
```

Both commands must finish with `BUILD SUCCESS`. The database profile must run
all four `MigrationIT` methods without failures, errors or skipped tests. Its
container and fresh databases are separate from the development Compose volume;
it does not read `.env`. A normal build without the profile does not run those
integration tests.

Attach the text report from
`scheduler-persistence/target/failsafe-reports/com.hospitalscheduler.persistence.MigrationIT.txt`
to the delivery evidence and fill the member's row in [J1-IL](proofs/J1-IL.md),
including the date, OS, Java/Maven/Docker versions, full commit SHA, connection
result and command outcomes. A missing/failed report leaves that check pending.

## Troubleshooting and stopping

| Symptom | Check |
| --- | --- |
| Docker permission denied / no valid Docker environment | Run `docker info` as the same user as Maven; check daemon access or start Docker Desktop. Do not treat the native SQL fallback as a Docker PASS. |
| Port 55432 already in use | Choose another `PROJECT15_DB_PORT` in the local `.env`, rerun `docker compose up --detach --wait postgres`, and use that port in the IDE/client. |
| Password fails after editing `.env` | An existing volume retains its initialized credentials. Use those credentials or update the role through an administrator connection; editing `.env` does not reset a stored password. |
| Windows shell cannot run `.sh` | Use the direct Compose commands above, which execute Bash inside the Linux container, or use Git Bash for the helper scripts. |

From `scheduler-persistence`, `docker compose logs --tail=100 postgres` shows
recent logs and `docker compose down` stops the service while retaining its
volume. Keep the volume when replaying migrations.
