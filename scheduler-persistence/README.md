# Scheduler persistence — hospital and schedule database

This module contains the PostgreSQL hospital and schedule schemas, JDBC
repositories, local connection setup and integration tests. The shared core
has no database dependency.

## Start a local database

From this directory (use Git Bash on Windows):

```bash
cp .env.example .env
# Set a local POSTGRES_PASSWORD in .env.
bash scripts/docker-database.sh up
bash scripts/docker-database.sh migrate
bash scripts/docker-database.sh status
```

The database listens on `127.0.0.1:55432`. Stop it with
`bash scripts/docker-database.sh down`; its volume is retained.

## Verify

From the repository root:

```bash
bash mvnw verify
bash mvnw -pl scheduler-persistence -am -Ppersistence-it verify
```

The database profile checks fresh migrations, V001 upgrades, replay, candidate
assignments, confirmation references and hospital/schedule repository round trips
and rollback.
Its default configuration requires Docker.
See [four-PC setup](docs/team-setup.md), [schema](docs/database-schema.md),
[database setup](docs/database-setup.md) and [Day 1 evidence](docs/proofs/J1-IL.md).

V002 adds saved schedules, candidate assignments and confirmed reservations.
`HospitalRepositoryJdbc` now loads hospital weeks and saves edits transactionally;
see [repository usage](docs/database-setup.md#hospital-repository).
V003 adds global doctor/room overlap exclusions for confirmed appointments.
`ScheduleRepositoryJdbc` saves candidate versions and their assignment batches
atomically, then loads them by code or hospital/week. See
[schedule repository usage](docs/database-setup.md#schedule-repository).
The application confirmation transaction remains subsequent work.
Experiment tables and demo seeds remain later work.
