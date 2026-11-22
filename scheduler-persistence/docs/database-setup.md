# PostgreSQL setup and verification

The database belongs to `scheduler-persistence` in the existing Java 17 Maven
reactor. All shell, SQL and Compose commands below run from that module unless
explicitly marked as repository-root commands. Use Git Bash for shell scripts on
Windows. Java builds use the repository's Maven wrapper.

For Aymane, Amine, Ilyass and Youness, follow the
[four-PC setup checklist](team-setup.md) and record the actual outcomes in
[J1-IL](proofs/J1-IL.md).

## Docker development database

Install Docker with Compose v2, then:

```bash
cd scheduler-persistence
cp .env.example .env
# Set POSTGRES_PASSWORD in .env to a local password before starting.
bash scripts/docker-database.sh up
bash scripts/docker-database.sh migrate
bash scripts/docker-database.sh seed
bash scripts/docker-database.sh status
```

The service uses `postgres:18.7-bookworm`, binds only `127.0.0.1:55432`, and
persists data in a named Docker volume at `/var/lib/postgresql`. PostgreSQL 18
keeps its version-specific data directory underneath that mount. The password
is required and has no development default. `.env` is ignored by the root
`.gitignore`. The seed and migrations are not startup hooks.

IntelliJ's database tool can connect with the values from `.env`:

```text
URL: jdbc:postgresql://localhost:55432/project15_dev?currentSchema=hospital,public
User: project15
Password: the local POSTGRES_PASSWORD value
```

The database/user defaults and `PROJECT15_DB_PORT` variable are retained from
the supplied assets for compatibility. There is no application connection
configuration yet; these values describe a JDBC connection, not environment
variables automatically consumed by the placeholder app. Pass credentials
separately from the JDBC URL when implementing the adapter. Schema-qualified
SQL is preferred.

Use `bash scripts/docker-database.sh logs` for recent server logs.
`bash scripts/docker-database.sh down` stops the service and preserves the
volume. Restart it with `up`; do not delete the volume to replay migrations.

## Native PostgreSQL

Install PostgreSQL server/client tools and contrib (`btree_gist`). The SQL
requires PostgreSQL 14 or newer. Create a dedicated role and database using an
administrator connection, adjusting names and the example password:

```sql
CREATE ROLE project15 LOGIN PASSWORD 'replace_this_local_password';
CREATE DATABASE project15_dev OWNER project15 ENCODING 'UTF8';
```

Set standard libpq connection variables explicitly, then run the same scripts:

```bash
export PGHOST=localhost PGPORT=5432 PGUSER=project15 PGDATABASE=project15_dev
# Supply credentials using a local .pgpass file or PGPASSWORD.
bash scripts/migrate-database.sh
bash scripts/seed-database.sh
psql -X -v ON_ERROR_STOP=1 -f db/queries/experiments.sql
```

`DATABASE_URL=postgresql://...` is also accepted by the shell runner; a JDBC
`jdbc:postgresql://...` URL is not a libpq URL. The native scripts do not source
`.env`. Database creation and migrations need an appropriate owner role.
`btree_gist` must be available on the server; if extension creation is restricted,
have the administrator install it in `public` before migrating. The application
role should not need database-creation or migration permissions.

## Migrations

`src/main/resources/db/migration` is packaged as a resource in the persistence
JAR. Operational scripts use the files from a source checkout; the JAR alone
does not execute migrations automatically.

The existing Bash/psql runner is the sole migration engine. It takes a transaction
advisory lock, applies migrations in numeric order in one transaction, and
records filenames, versions and SHA-256 checksums in
`public.database_schema_migration`. It rejects edits to an applied migration
and missing earlier versions. Replays preserve the journal. The application
schema is `hospital`; migrations work independently of the seed.

The imported V001–V003 files were preserved byte for byte. Add subsequent
numbered migrations for schema changes. Do not introduce a second migration
journal alongside this runner. The current project has no Flyway, Liquibase,
Spring or ORM configuration to reuse.

## Maven integration tests

From the **repository root**:

```bash
bash mvnw verify
bash mvnw -pl scheduler-persistence -am -Pdatabase-it verify
```

Windows equivalents:

```powershell
.\mvnw.cmd verify
.\mvnw.cmd -pl scheduler-persistence -am -Pdatabase-it verify
```

The first command builds all six modules and runs unit tests without Docker.
The second activates Failsafe only in the persistence module. JUnit 5.11.4 and
Failsafe 3.5.2 follow the parent build; PostgreSQL and Testcontainers versions are
managed there too. The `MigrationIT` class uses package
`com.hospitalscheduler.persistence`.

The four integration tests cover an empty database, journal-preserving replay,
an incremental V001 → V003 upgrade, rejection of an edited applied migration,
both global resource exclusions and named SQLSTATE errors, seed replay,
feasibility, replanning rollback, invalid requests, and JDBC timestamp instants.
The suite starts one dedicated Testcontainers container; each method gets a
fresh database in that container.
They do not use `.env`, the Compose volume, or a running development database.
A missing Docker daemon is an error when this profile is requested, not a
successful skipped test. Reports: `scheduler-persistence/target/failsafe-reports/`.
Override the test image only when needed with `-Dpostgres.image=postgres:...`;
Compose remains explicitly pinned in `compose.yaml`.

## SQL-only verification

```bash
# Independent container, no development password or database needed.
bash scripts/docker-database.sh verify
# Independent native cluster; install server tools and contrib first.
bash scripts/verify-database.sh
# Optional overrides for native verification:
PG_BINDIR=/usr/lib/postgresql/18/bin KEEP_VERIFY_ARTIFACTS=1 bash scripts/verify-database.sh
```

The verifier creates a temporary cluster with a private Unix socket and no TCP
listener. It ignores existing connection settings, stops the cluster on exit,
and deletes its own files unless `KEEP_VERIFY_ARTIFACTS=1`. Constraint and
replanning checks roll back their changes. It verifies migration replay,
exact error constraints, both global exclusions, adjacent intervals, conflicting
candidates, seed replay without overwriting a local edit, all table counts,
150 feasible confirmed requests, and synthetic experiment convergence.

If native socket creation is prohibited, the supplied Python adapter uses the
real PostgreSQL single-user backend in the temporary cluster. Its output states
the limitation: that mode does not validate JDBC, libpq connections, the live
psql runner's replay/checksum checks, or concurrent sessions. It is only a SQL
verification fallback. A SQL PASS does not establish a Testcontainers PASS.

## Demonstration data

All people and emails (`example.test`) are fictional. The week is
16–20 November 2026 in the hospital's explicit `Europe/Paris` timezone. It
contains 5 departments, 10 specialties, 20 doctors, 12 rooms, 10 equipment types,
130 patients and 150 requests (10 urgent, 20 priority, 120 routine). Re-running
the transactionally guarded seed preserves every existing demo row, including
user edits; it does not repair or reset an edited dataset.

| Schedule | Appointments | Purpose |
| --- | ---: | --- |
| `demo15.schedule.published` | 150 confirmed | Feasible witness; requests 001–005 locked |
| `demo15.schedule.candidate-a` | 150 candidates | Feasible copy, including references to the locked reservations |
| `demo15.schedule.candidate-b` | 150 candidates | Request 011 moved to Monday 09:15–09:30, causing a doctor overlap, room overlap and patient-window violation |

Requests 001 (09:00–10:00) and 011 (10:00–10:15) demonstrate exact adjacency.
`db/verify/replanning.sql` simulates a doctor absence and atomically replaces
the published schedule while retaining all five locked reservation identities;
it finishes with `ROLLBACK`. The archived old schedule is not an immutable
snapshot because those original rows move to the replacement.

Two versioned benchmark instances, two algorithms and seeds 1–20 yield
80 **synthetic** runs with 21 points each (1680 total), using a 2000 ms budget.
No optimizer was executed to produce these fixtures. Their illustrative scalar
scores must not be treated as the core's lexicographic `FitnessBreakdown`.
The tiny instance has a witness for its illustrative zero optimum; the weekly
instance has no claimed optimum.

```bash
psql -X -v ON_ERROR_STOP=1 -f db/queries/experiments.sql
psql -X -v ON_ERROR_STOP=1 \
  -c "SET project15.run_code='demo15.run.tiny.annealing.03';" \
  -f db/queries/experiments.sql
```

These queries show best score, mean, sample standard deviation, violations,
best runs and an ordered convergence curve. They are queries over synthetic
scalar fixtures. See the [core mapping and gaps](database-schema.md) and
[integration decisions](decisions.md) before writing application repositories.
