# Scheduler persistence

PostgreSQL assets for the Java 17 Hospital Scheduler. This module inherits the
`com.hospitalscheduler:hospital-scheduler` parent and depends on `scheduler-core`.
The PostgreSQL JDBC driver is available at runtime; JUnit and Testcontainers are
test dependencies managed by the parent. The core remains independent of SQL.

## Development database

Run these commands from this directory (Windows: use Git Bash):

```bash
cp .env.example .env
# Set POSTGRES_PASSWORD in .env.
bash scripts/docker-database.sh up
bash scripts/docker-database.sh migrate
bash scripts/docker-database.sh seed
bash scripts/docker-database.sh status
```

The database listens on `localhost:55432`. Migrations and demonstration data are
explicit operations. `bash scripts/docker-database.sh down` preserves the volume.

## Verification

From the **repository root**, using the existing Maven wrapper:

```bash
# Build all modules and run unit tests; Docker is not needed.
bash mvnw verify

# Run the persistence integration tests and their upstream core module.
bash mvnw -pl scheduler-persistence -am -Pdatabase-it verify
```

On Windows, replace `bash mvnw` with `.\mvnw.cmd`. The `database-it` profile
requires Docker and fails if it is unavailable. It creates a separate container
and a new database for each test; it does not use the development database or
`.env`. Reports are in `target/failsafe-reports/` inside this module. `mvn test`
compiles integration tests but only runs unit tests; a normal `verify` does not
activate the database profile.

SQL-only alternatives, from this directory:

```bash
# Separate container and temporary cluster; no development .env required.
bash scripts/docker-database.sh verify
# Or use installed PostgreSQL server/client tools and contrib.
bash scripts/verify-database.sh
```

## Layout

```text
pom.xml                             Existing persistence module, parent-managed dependencies
compose.yaml / .env.example          Local PostgreSQL service and credential template
src/main/resources/db/migration/     Versioned SQL, included in the module JAR
src/test/java/com/hospitalscheduler/persistence/
                                    MigrationIT, executed by the database-it profile
db/seed/                            Explicit synthetic demonstration dataset
db/verify/                          Constraint, replay, feasibility and replanning checks
db/queries/                         Example experiment queries
scripts/                            Migration, seed and verification entry points
docs/                               Setup, schema, integration decisions and Day 1 evidence
```

The seed contains 20 doctors, 12 rooms, 130 patients, 150 requests, one published
schedule, two candidates, 80 synthetic runs and 1680 synthetic fitness points.
These are demonstration fixtures, not measured optimizer results.

The current delivery supplies schema, migration tooling and database tests.
Application repositories and UI database wiring are not implemented: the shared
core does not yet express several SQL concepts. See the concrete mapping and
gaps before implementing an adapter; do not silently truncate database data.

- [Setup and verification](docs/database-setup.md)
- [Setup and connection checklist for the four PCs](docs/team-setup.md)
- [Schema and mapping to the existing core](docs/database-schema.md)
- [Integration decisions and remaining contract gaps](docs/decisions.md)
- [Day 1 status and team verification record](docs/proofs/J1-IL.md)
- [Project architecture](../docs/contracts.md)
