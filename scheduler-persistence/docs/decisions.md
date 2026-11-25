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

- `ScheduleRepositoryJdbc` inserts immutable candidate versions with unique caller
  codes. Duplicate codes fail instead of overwriting historical or confirmed data.
  Assignments are inserted as one JDBC batch in the same transaction as metadata.
- Algorithm codes identify fixed names/versions; a new implementation version must
  use a new code. This prevents later saves changing historical algorithm metadata.
- Repository snapshots preserve JSON parameters, shared fitness components, signed
  64-bit seeds, lock flags and absolute instants. PostgreSQL validates and
  canonicalizes parameter JSON without an additional JSON-library dependency.

- The application-facing repository path uses `OptimizationProblem`, `Schedule`
  and `OptimizationResult`. Editing DTOs retain the richer database metadata.
- `CoreModelMapper` defines a configurable default urgency mapping (1/2/3 for
  routine/priority/urgent), explicit specialty selection for multi-qualified
  doctors, and strict representability checks. Core domain classes are not extended.
- V004 adds two fitness components, ordered history, optimizer status and saved
  time zone. Legacy scalar values are retained without guessing their components.
- Repeated/missing local clock times are rejected because the shared TimeSlot
  cannot retain an offset. Stored schedules capture the conversion zone at save.
- Full-record deletion is explicit and transactional. Owned child rows are
  removed with their parent; external references retain foreign-key protection.
  Saved appointments and requests in any hospital are never removed to enable
  deletion. A blocked operation raises `HospitalEntityInUseException` after rollback.
- Scoped deletion uses hospital code plus entity code and the same hospital-first
  lock order as `saveWeek`. Shared patients, equipment and room types have explicit
  global deletion methods. Missing or out-of-scope records return false.
