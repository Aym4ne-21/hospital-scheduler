# PostgreSQL setup

The database lives in `scheduler-persistence` in the Java 17 Maven reactor. For
setup and connection verification on Aymane, Amine, Ilyass and Youness's PCs, use
the [four-PC checklist](team-setup.md) and record actual outcomes in
[J1-IL](proofs/J1-IL.md).

## Docker database

From this module, copy `.env.example` to `.env`, set a local password, and run:

```bash
bash scripts/docker-database.sh up
bash scripts/docker-database.sh migrate
bash scripts/docker-database.sh status
```

The PostgreSQL 18.7 service binds to `127.0.0.1:55432`. Its named volume
persists between stops. `docker compose down` preserves it. The service does not
automatically apply migrations.

Connect from IntelliJ with:

```text
URL: jdbc:postgresql://127.0.0.1:55432/project15_dev?currentSchema=hospital,public
User: project15
Password: the local POSTGRES_PASSWORD in .env
```

The current Java application has no database startup wiring. This is a manual
JDBC connection example.

## Migrations

`src/main/resources/db/migration/V001__hospital_and_availability.sql` creates
the hospital, department, specialty, doctor, room, equipment, patient, request
and availability schema. It installs `btree_gist`, adds foreign keys and duration
checks, and uses finite half-open time ranges.

`V002__schedules.sql` adds algorithms, hospital weeks, saved schedules, candidate
assignments and confirmed reservations. Running the same migration command on a
V001 database applies V002 and V003 and preserves valid existing data. All three
versions should be recorded in the migration journal. V003 adds global doctor
and room GiST exclusions to confirmed reservations; candidate overlaps remain
allowed. Existing conflicting confirmations make V003 fail and roll back, so
resolve those bookings explicitly before retrying.

If an earlier development database already records the removed
`V002__schedules_and_reservations.sql`, this V002 will be rejected because its
filename/checksum differs. Use a fresh development database for this revised
sequence; preserve any needed data first. Do not edit the migration journal to
bypass that protection.

The supplied Bash/psql runner applies numbered migrations in one transaction,
uses an advisory lock and records SHA-256 checksums in
`public.database_schema_migration`. It rejects changes to an applied migration.
Add later migrations with new version numbers rather than editing V001. Migration
SQL is packaged in the persistence JAR; the operational script runs it from a
source checkout.

## Build and integration test

From the repository root:

```bash
bash mvnw verify
bash mvnw -pl scheduler-persistence -am -Ppersistence-it verify
```

The first command runs the reactor and unit tests without Docker. The
`persistence-it` profile runs `MigrationIT` with a fresh Testcontainers database.
Its migration tests check a fresh schema, V001 upgrade without data loss, replay,
foreign keys, negative request duration, overlapping candidates, confirmation
references and invalid metadata/bounds. Docker access is required for the second
command.

Verification on 2026-11-22: the core/persistence Maven build passed with all 18
core tests, and the six Testcontainers tests compiled. Direct checks on an
isolated PostgreSQL 18.7 server passed for fresh installation, V001 upgrade with
data/journal preservation, replay, overlapping candidates, exact confirmation
references, unique reservations and invalid metadata/bounds. Docker could not
start in this environment, so this is not a Testcontainers execution result.

## Optional native PostgreSQL

The SQL requires PostgreSQL 14+ with `btree_gist` available. For a native server,
create a local role and database, then set libpq variables and apply the migrations:

```sql
CREATE ROLE project15 LOGIN PASSWORD 'replace_this_local_password';
CREATE DATABASE project15_dev OWNER project15 ENCODING 'UTF8';
```

```bash
export PGHOST=localhost PGPORT=5432 PGUSER=project15 PGDATABASE=project15_dev
bash scripts/migrate-database.sh
```

`DATABASE_URL=postgresql://...` is also accepted by the migration runner. It is
a libpq URL, not a JDBC URL. See [J1-IL](proofs/J1-IL.md) for tested outcomes and
[database-schema.md](database-schema.md) for the diagram and core mapping.


## Hospital repository

`HospitalRepositoryJdbc` implements the core `HospitalRepository` contract.
Inject a configured `javax.sql.DataSource`; credentials belong in the application's
composition layer, outside the repository. For example, with the existing runtime
driver:

```java
PGSimpleDataSource dataSource = new PGSimpleDataSource();
dataSource.setURL(System.getenv("JDBC_DATABASE_URL")); // jdbc:postgresql://host:port/database
dataSource.setUser(System.getenv("PGUSER"));
dataSource.setPassword(System.getenv("PGPASSWORD"));
HospitalRepository repository = new HospitalRepositoryJdbc(dataSource);
Optional<HospitalWeek> week = repository.loadWeek("hospital-code", LocalDate.of(2026, 11, 23));
week.ifPresent(repository::saveWeek);
```

`PGSimpleDataSource` is `org.postgresql.ds.PGSimpleDataSource`. The repository
itself depends only on standard JDBC, so a pooled DataSource can be injected too.
If the composition module imports this PostgreSQL-specific class, it must declare
the driver as a compile dependency; persistence currently uses runtime scope.
This is a composition example; automatic application startup wiring is separate.

`loadWeek` returns empty for an unknown hospital and rejects a non-Monday start.
It loads hospital departments/specialties, doctors and rooms; room-type/equipment
catalogs are global in V001. Patients are included through the selected requests.
Requests are selected by their specialty's hospital and an availability window
intersecting the week; windowless requests are also included to expose missing
availability. All request statuses are retained for editing. The request creation
date is not used as the scheduling date. Recurring preferences are preserved.

For new entities, use ID `0` and a stable unique code. Reference other entities by
code, so a single save can create departments, doctors, patients and their requests.
Reload after saving to obtain generated database IDs. A nonzero ID must match its
code. Existing hospital-scoped codes cannot be reassigned from another hospital.

`saveWeek` upserts only listed top-level entities; omission never deletes a doctor,
room, patient or request. For each listed doctor/patient, dated child collections
replace windows intersecting the selected week. Windows in other weeks remain
unchanged. Full original endpoints are retained for crossing windows. Listed
request availability is reconciled within the week; specialty links, equipment
quantities and recurring preferences replace their full corresponding lists.
Empty child lists remove those links/windows within this documented scope.
The caller should load the week, edit its immutable data and save the revised
snapshot. A separate week must be loaded to edit its windows.

Patient identities, availability, catalogs and preferences are shared across
hospitals in V001. Removing a patient window still referenced by any omitted
request fails with a foreign-key error and rolls the entire save back; edit the
affected request links in the same operation. Reassigning a patient on a request
with existing availability links is likewise rejected by the composite FK.
The hospital row serializes saves through this adapter; this does not provide
optimistic detection of stale editor snapshots. Services still own business
validation and conflict handling.

Times use `Instant` and PostgreSQL microsecond precision; finer input is rejected.
The week ends at the following local Monday, so daylight-saving weeks can differ
from 168 hours. `HospitalRepositoryException` wraps SQL failures with their cause;
invalid references/IDs or out-of-week input produce `IllegalArgumentException`.
Every failed save rolls back all its edits, and JDBC resources are closed.

The seven `HospitalRepositoryJdbcIT` tests run with Testcontainers by default.
For an explicitly disposable native PostgreSQL instance, the same tests can run
without Docker (the supplied role needs permission to create test databases):

```bash
bash mvnw -pl scheduler-persistence -am -Ppersistence-it \
  -Dit.test=HospitalRepositoryJdbcIT -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dscheduler.test.jdbc.url=jdbc:postgresql://127.0.0.1:5432/postgres \
  -Dscheduler.test.jdbc.user=postgres verify
```

The tests create and drop uniquely named databases. Verification on 2026-11-22
used an isolated PostgreSQL 18.7 instance: all seven repository integration tests
and 18 core tests passed. Docker execution remains pending in this environment.


## Confirmed overlap constraints

Run `bash scripts/docker-database.sh migrate` from this module to apply V003 to
the local Compose database. It does not run automatically at application startup.
V001 and V002 remain unchanged. See the [schema guide](database-schema.md#day-2-task-3-confirmed-resource-exclusions)
for the two constraint names and their behavior.

`ConfirmationConstraintsIT` covers doctor-only and room-only collisions across
versions, adjacent bookings, independent resources, updates, valid V002 upgrades,
invalid existing data with atomic rollback, and simultaneous confirmations after
both commit and rollback. It shares the disposable database lifecycle with the
repository tests and supports the same native JDBC properties. To include both
suites in the native command above, use
`-Dit.test=HospitalRepositoryJdbcIT,ConfirmationConstraintsIT`.

Verification on 2026-11-22: all 14 repository/exclusion integration tests and 18
core tests passed on isolated PostgreSQL 18.7. Fresh V001–V003 installation and
migration replay also passed, preserving the journal. These checks used native
PostgreSQL; the Docker-backed migration test execution remains pending.


## Schedule repository

Inject the same configured `DataSource` into `ScheduleRepositoryJdbc`. Its core
contract is `ScheduleRepository`; its immutable data is `ScheduleSnapshot`.

```java
ScheduleRepository schedules = new ScheduleRepositoryJdbc(dataSource);
ScheduleSnapshot candidate = new ScheduleSnapshot(
    "candidate-001", "hospital-code", LocalDate.of(2026, 11, 23),
    new ScheduleSnapshot.Algorithm("greedy.v1", "Greedy", "1"),
    "{\"iterations\":100}", 42L, new BigDecimal("12.5"),
    List.of(new ScheduleSnapshot.Assignment(
        requestId, doctorId, roomId,
        Instant.parse("2026-11-23T08:00:00Z"),
        Instant.parse("2026-11-23T08:30:00Z"), false)));
ScheduleSnapshot.Stored saved = schedules.saveCandidate(candidate);
Optional<ScheduleSnapshot.Stored> reloaded = schedules.load("candidate-001");
List<ScheduleSnapshot.Stored> versions = schedules.loadWeek(
    "hospital-code", LocalDate.of(2026, 11, 23));
```

The hospital and referenced requests/resources must already exist. Use a new
unique schedule code for each version; a duplicate code fails without changing
the existing schedule. The week row is shared across versions of the same
hospital/Monday. Algorithm codes must retain the same name/version; use a new
code when the algorithm changes. Parameters must be valid JSON objects.
The returned snapshot contains PostgreSQL's canonical JSON formatting.

Candidate fitness is a nonnegative `BigDecimal`; seeds use Java `long` and SQL
`bigint`. Assignments require positive known IDs, distinct request IDs, positive
time intervals and microsecond precision. Their order on reload is request-ID
order. Empty or incomplete candidates and conflicting assignments can be stored;
completeness and business feasibility remain service/optimizer responsibilities.
`locked` is preserved as data; enforcing locks during optimization belongs to core.

The save owns one connection and transaction for its week, algorithm, schedule
and assignment batch. Unknown or foreign references produce
`IllegalArgumentException`; SQL failures produce `ScheduleRepositoryException`
with the underlying cause. Both roll the operation back. Existing versions and
confirmed reservations are never replaced by this operation. Listing an unknown
hospital/week returns an empty list; loading an unknown code returns empty.
Read operations also support existing draft/running rows with no fitness value,
although `saveCandidate` requires a score and always creates CANDIDATE status.

`ScheduleRepositoryJdbcIT` adds eight integration tests for a 150-assignment
batch, metadata/time/lock round trips, conflicting candidates, separate versions,
week/hospital filters, invalid JSON, late batch failure, immutable codes,
algorithm metadata protection and cross-hospital rejection. It uses the shared
Testcontainers/native lifecycle. To run all three native repository/exclusion
suites with the command above, set
`-Dit.test=ScheduleRepositoryJdbcIT,HospitalRepositoryJdbcIT,ConfirmationConstraintsIT`.

Verification on 2026-11-22: all 22 repository/exclusion integration tests and 18
core tests passed on isolated PostgreSQL 18.7, including the 150-assignment save
and full rollback of a partially successful assignment batch. Migration replay
also passed. Docker-backed migration test execution remains pending.
