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
V001 database applies V002–V004 and preserves valid existing data. All four
versions should be recorded in the migration journal. V003 adds global doctor
and room GiST exclusions to confirmed reservations; candidate overlaps remain
allowed. Existing conflicting confirmations make V003 fail and roll back, so
resolve those bookings explicitly before retrying.

V004 adds the shared `FitnessBreakdown` components, optimizer status, ordered
fitness history and the saved time-zone context. Legacy scalar scores are retained
in `final_fitness`; their hard/soft components are left unknown. New writes use
`hard_violations` and `soft_cost`. There are 25 hospital-schema tables after V004.

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

Verification on 2026-11-22: 18 core tests, four temporary mapper unit tests and 28 native
PostgreSQL integration checks passed, including the shared-core round trip,
legacy-score upgrade, migration replay, rollback, overlap and concurrency checks.
Temporary check files, fixtures and generated outputs were removed after use.
The user then ran the full Docker/Testcontainers profile successfully on
2026-11-22. Its generated XML reports confirmed 18 core tests and 28 integration
tests, with zero failures, errors or skips and no native JDBC override. Reports
and generated build outputs were removed after recording this summary.

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

### Delete editable entities

`HospitalRepository` also supports explicit full-record deletion:

```java
boolean deleted = repository.deleteDoctor("hospital-code", "doctor-code");
```

`deleteDepartment`, `deleteSpecialty`, `deleteDoctor`, `deleteRoom` and
`deleteRequest` take hospital code followed by entity code. A code outside the
specified hospital returns false. `deleteHospital` takes the hospital code.
`deletePatient`, `deleteRoomType` and `deleteEquipment` take only their entity
code because those records are shared globally in the current schema.

Each method returns true on deletion and false if no matching record exists.
Blank codes are rejected. Deletion is explicit; omitting a record from `saveWeek`
still does not delete it.

Doctor deletion removes that doctor's specialty links, availability, absences
and breaks. Room deletion removes its equipment links. Request deletion removes
its availability/equipment links while retaining the patient's windows and shared
equipment. An unused patient's deletion removes their preferences and windows.
These owned details and the parent record are deleted in one transaction.

External references are protected by the existing foreign keys. Saved candidate
or confirmed assignments prevent deleting their doctor, room or request. Requests
from any hospital prevent deleting their shared patient. Departments, specialties,
hospitals and catalogs can only be deleted once their dependents are gone.
`HospitalEntityInUseException` supplies an explanatory message and retains the
SQL cause. A blocked deletion rolls back every owned-detail removal; it does not
erase appointments or other master records to force the delete through.

Scoped deletions lock the hospital before the target row, matching `saveWeek`'s
lock order. All targets are locked before owned details are removed, allowing
foreign keys to protect concurrent reference creation. User values are bound
parameters; table names and cleanup queries are private constants.

Temporary PostgreSQL checks verified all nine deletion operations, reference
protection, rollback, shared patients/catalogs, hospital scoping and concurrent
deletion. They passed alongside the 22 maintained native integration tests and
18 core tests. Temporary check sources, databases and generated outputs were
removed after validation.

The repository integration tests use Testcontainers by default. For an explicitly
disposable native PostgreSQL instance, the three repository/exclusion suites
can run without Docker; the supplied role needs permission to create databases:

```bash
bash mvnw -pl scheduler-persistence -am -Ppersistence-it \
  -Dit.test=HospitalRepositoryJdbcIT,ScheduleRepositoryJdbcIT,ConfirmationConstraintsIT \
  -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dscheduler.test.jdbc.url=jdbc:postgresql://127.0.0.1:5432/postgres \
  -Dscheduler.test.jdbc.user=postgres verify
```

Each test creates and drops its own uniquely named database. Do not point this
command at a production server. The native option does not run `MigrationIT`,
which exercises the actual Bash runner inside a Docker container.

## Confirmed overlap constraints

Run `bash scripts/docker-database.sh migrate` from this module to apply V003 to
the local Compose database. It does not run automatically at application startup.
V001 and V002 remain unchanged. See the [schema guide](database-schema.md#day-2-task-3-confirmed-resource-exclusions)
for the two constraint names and their behavior.

`ConfirmationConstraintsIT` covers doctor-only and room-only collisions across
versions, adjacent bookings, independent resources, updates, valid V002 upgrades,
invalid existing data with atomic rollback, and simultaneous confirmations after
both commit and rollback. It shares the disposable database lifecycle with the
repository tests and supports the same native JDBC properties.


## Shared-core integration

Application and optimizer code can use Aymane's existing classes directly:

```java
HospitalRepository hospitals = new HospitalRepositoryJdbc(dataSource);
ScheduleRepository schedules = new ScheduleRepositoryJdbc(dataSource);
LocalDate monday = LocalDate.of(2026, 11, 23);
OptimizationProblem problem = hospitals.loadProblem("hospital-code", monday).orElseThrow();
OptimizationParameters parameters = new OptimizationParameters(42L, 100);
OptimizationResult result = optimizer.optimize(
    new OptimizationRequest(problem, parameters), progress -> {});

ScheduleSnapshot.RunMetadata metadata = new ScheduleSnapshot.RunMetadata(
    "candidate-001", "hospital-code", monday,
    new ScheduleSnapshot.Algorithm("greedy.v1", optimizer.getName(), "1"),
    "{\"maxIterations\":100}", parameters.seed());
ScheduleSnapshot.StoredResult saved = schedules.saveResult(metadata, result, Set.of());
OptimizationResult restored = schedules.loadResult("candidate-001", problem).orElseThrow().result();
Schedule restoredSchedule = schedules.loadSchedule("candidate-001", problem).orElseThrow();
```

The example assumes a configured `DataSource` and an `Optimizer` supplied by the
application. No JDBC or JavaFX dependency enters the shared domain. Editing can
continue through `loadWeek`/`saveWeek`, preserving additional database fields.

`CoreModelMapper` maps specialty/equipment codes consistently to core strings,
combines doctor absences and breaks into unavailable periods, clips optimization
windows to the selected week, and includes only OPEN requests. Each request must
have usable availability. Patients are reused across requests by ID.

The default integration convention is ROUTINE=1, PRIORITY=2, URGENT=3. To use a
different agreed convention, inject a mapper with a complete distinct integer map.
The same constructor accepts doctor-code -> selected-specialty-code entries:

```java
CoreModelMapper mapper = new CoreModelMapper(
    Map.of(HospitalWeek.Urgency.ROUTINE, 1,
           HospitalWeek.Urgency.PRIORITY, 2,
           HospitalWeek.Urgency.URGENT, 3),
    Map.of("doctor-code", "cardiology"));
HospitalRepository hospitals = new HospitalRepositoryJdbc(dataSource, mapper);
```

A doctor with multiple specialties requires an explicit choice from that doctor's
stored qualifications. The current core cannot express patient preferences,
required equipment quantities above one, or per-request room types when other
room types are present. Such optimization input is rejected with an explanatory
`IllegalArgumentException`; its editing data remains stored. Available equipment
quantities map to presence in the room's set. These checks prevent silently
optimizing a problem with missing requirements.

SQL instants convert to `TimeSlot` local date-times using the hospital zone. Missing
or repeated local clock times are rejected because `TimeSlot` has no offset field.
Schedule versions capture that zone on save, so later hospital-zone edits do not
reinterpret saved appointments. Sub-microsecond values remain unsupported.

## Schedule repository

`saveResult` saves the shared `Schedule`, both `FitnessBreakdown` components,
every history entry in order and the exact `OptimizationResult.status` in one
transaction. Optimizer status is independent of lifecycle CANDIDATE/CONFIRMED.
Lock IDs accompany the result because the current core `Schedule` has no lock
field. A lock must reference an included request. `loadResult` reconstructs the
shared appointments using the actual objects from the supplied matching
`OptimizationProblem`; missing IDs cause an error. It does not recreate a
historical problem snapshot after hospital data has changed.

The lower-level `saveCandidate(ScheduleSnapshot)` remains available for storing
candidate data without an optimizer run. Its `finalFitness` now uses the shared
`FitnessBreakdown`, with optional history/status. Legacy scalar-only versions
remain readable through `load()` and their `legacyFitness()` value. `loadResult`
refuses to invent missing score components or optimizer status; `loadSchedule`
can still reconstruct their appointments given a matching problem.

The hospital and referenced requests/resources must already exist. Each saved
version needs a unique code; a duplicate fails without overwriting the existing
version. Hospital weeks are shared across versions. Algorithm codes retain their
name/version; use a new code for a new implementation version. Parameters must
be valid JSON objects; returned JSON uses PostgreSQL's canonical formatting.
Seeds retain all 64 bits. Hard violations use an integer and soft cost uses a
finite nonnegative double, matching `FitnessBreakdown` and its lexicographic
comparison without scalar encoding.

Assignments are batch-inserted, one per request per version, with valid IDs from
the same hospital. Resource conflicts and incomplete candidates remain
representable for fitness evaluation. The service still owns completeness,
feasibility and lock-preservation rules. A failed statement or invalid reference
rolls back metadata, assignments and history. SQL failures are wrapped in
`ScheduleRepositoryException`; validation failures use `IllegalArgumentException`.

Loads use three grouped queries for assignments, history and metadata, with
repeatable-read consistency. Versions are ordered by creation timestamp/ID;
assignments by request ID. Unknown codes return empty; unknown hospital/weeks
return empty lists. The candidate API does not publish operational reservations.

Temporary validation checked the shared optimizer/result round trip, legacy-score
upgrade, history, object identity and saved time zones. Those temporary checks and
their generated outputs were removed after use. The committed repository,
migration and exclusion tests remain the project's maintained test suite.
