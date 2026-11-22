# Database decisions

- The schema is integrated into the existing Java 17 `scheduler-persistence`
  Maven module; no second Maven project or database dependency was added to core.
- JUnit follows the parent project's 5.11.4 version. PostgreSQL JDBC and
  Testcontainers versions are managed by the parent POM.
- The initial schema, Compose setup, migration runner and basic migration test
  are Day 1 scope. Day 2 task 1 adds V002 with schedule metadata, candidate
  assignments and confirmed reservations in separate tables as proposed in the plan.
- Confirmations reference exact saved assignments through a composite foreign key;
  this prevents a reservation silently diverging from its candidate version.
- V003 adds global GiST doctor/room exclusions on confirmed reservations, without
  schedule IDs. This protects bookings across versions and concurrent transactions;
  half-open ranges permit adjacency and candidates still allow conflicts.
- Confirmation transactions remain subsequent Day 2 work. No operational
  confirmation flow is enabled yet.
  Sample data and experiments remain subsequent work.
- V001 remains the initial migration. Once applied, future changes must use a
  new numbered migration instead of editing it.
- The hospital repository uses `Instant` for stored timestamps and the hospital's
  explicit `ZoneId` for local week boundaries. Sub-microsecond input is rejected
  rather than rounded by PostgreSQL.

The optimizer domain's single doctor specialty, integer urgency and local date-time
slots do not fully match the SQL model. The repository's immutable `HospitalWeek`
DTO preserves those details without changing existing optimizer contracts.
Conversion to optimizer objects still requires team agreement; see the
[core contract](../../docs/contracts.md).

- `ScheduleRepositoryJdbc` inserts immutable candidate versions with unique caller
  codes. Duplicate codes fail instead of overwriting historical or confirmed data.
  Assignments are inserted as one JDBC batch in the same transaction as metadata.
- Algorithm codes identify fixed names/versions; a new implementation version must
  use a new code. This prevents later saves changing historical algorithm metadata.
- Repository snapshots preserve JSON parameters, exact numeric fitness, signed
  64-bit seeds, lock flags and absolute instants. PostgreSQL validates and
  canonicalizes parameter JSON without an additional JSON-library dependency.
