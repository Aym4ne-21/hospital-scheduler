# Persistence integration decisions

Integrated on 21 November 2026 after reading the actual parent/module POMs,
shared domain classes, optimization contracts and existing tests. The source
folder was a separate SQL deliverable. Current setup instructions and evidence
are maintained in this module; the superseded standalone brief and reports
have been removed.

| Concern | Integration decision |
| --- | --- |
| Module | Use the existing `scheduler-persistence` child of `com.hospitalscheduler:hospital-scheduler:1.0-SNAPSHOT`; no separate database Maven project. |
| Java and tests | Inherit Java 17 and JUnit 5.11.4. Manage JDBC/Testcontainers in the parent. Align Failsafe with the parent's Surefire 3.5.2. |
| Packages | Database integration tests use `com.hospitalscheduler.persistence`, matching the project's package prefix. |
| Boundaries | PostgreSQL driver is runtime-only in persistence. Tests use Testcontainers. `scheduler-core` has no database/UI dependencies. |
| Test lifecycle | `database-it` explicitly runs Failsafe integration tests. Ordinary reactor builds run unit tests without Docker; the requested integration profile fails if Docker is unavailable. |
| Migrations | Keep V001–V003 bytes and their SHA-256 history, the existing transaction/advisory-lock runner, and one journal. Migration resources ship in the persistence JAR. |
| Operations | Keep Compose, scripts, queries, fixtures and docs under the persistence module; paths resolve from the scripts' locations. |
| Data compatibility | Preserve legacy `project15` lock keys, connection defaults, fixture codes and SQL custom settings. Existing migration journals and seed markers remain valid. |
| Time | Keep explicit hospital timezone and fixture instants. The demo remains `Europe/Paris`; no unused timezone environment variable is advertised. |
| Secrets | Keep `.env.example` with an empty password and use root ignore rules for local `.env`. The standalone `.gitignore` is unnecessary. |
| Scope | Deliver runnable SQL infrastructure and tests. Repositories and application startup wiring require the core/SQL gaps below to be resolved. |

## Gaps established against the actual core

- `Doctor.specialty` is one `String`; `doctor_specialty` is many-to-many and the
  seed has two doctors with two specialties. Do not select an arbitrary specialty
  or duplicate a doctor ID in an optimization problem. Decide whether to extend
  the core or explicitly constrain a repository's input.
- Core specialty/equipment fields are strings; SQL has stable codes and display
  names. Use a documented consistent identity (codes are the natural adapter
  choice), not locale-dependent name matching.
- Core urgency is an `int` with no defined range/order. SQL uses
  `URGENT/PRIORITY/ROUTINE`. No numeric ordering has been invented.
- Core time slots use `LocalDateTime`; SQL stores instants. Read `OffsetDateTime`
  and convert through the owning hospital's `ZoneId`. Resolve ambiguous/missing
  local times explicitly when saving. Never rely on the machine's default zone.
- Core appointments require a request, doctor, room and slot; SQL permits
  unassigned candidates. A repository cannot silently construct or drop an
  incomplete SQL row as a core `Appointment`.
- Core `Schedule` is only an appointment list. It has no database ID, week,
  publication state, lock or reservation provenance. Keep that metadata in the
  persistence/application boundary when that API is designed.
- `FitnessBreakdown` minimizes `(hardViolations, softCost)` lexicographically.
  SQL's existing `final_fitness` and `fitness_point.fitness` are illustrative
  scalar objectives. Runs have final violation counts, but schedules and history
  do not retain complete core breakdowns. Add a follow-up migration before
  storing real `OptimizationResult` values; do not encode them into the fixture
  formula `1000 * violations + delay` or infer missing values.
- SQL room types, equipment quantities and patient preferences have no core
  representation. The core also has no standardized optimization status
  vocabulary, experiment model, elapsed-time budget or complete lock protocol.
- Replanning transfers the original locked reservation rows. An archived
  schedule therefore is not a full immutable snapshot. Separate operational
  reservations from versioned assignments if immutable history is required.

The shared API is documented in [core contracts](../../docs/contracts.md).
These are concrete API design gaps, not runtime configuration errors; adding
an ORM or a second copy of the domain would not resolve them.

## References

- [Testcontainers PostgreSQL module](https://java.testcontainers.org/modules/databases/postgres/)
- [pgJDBC timestamp mapping](https://jdbc.postgresql.org/documentation/query/)
- [PostgreSQL official image](https://hub.docker.com/_/postgres)
- [Maven Failsafe lifecycle](https://maven.apache.org/surefire/maven-failsafe-plugin/usage.html)
