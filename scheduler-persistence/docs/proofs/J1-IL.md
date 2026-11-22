# J1-IL — PostgreSQL and initial schema

Updated 21 November 2026. Owner: Ilyass. Required reviewer: Amine.
**Status: implementation prepared; Docker and four-PC verification remain open.**
The current source is the uncommitted `feat/database` working tree based on
`6c630ac`; that SHA alone does not include the database changes.

## Deliverables and local evidence

| Day 1 requirement | Implementation and evidence | Status |
| --- | --- | --- |
| PostgreSQL, Docker configuration and connection examples | PostgreSQL 18.7 verified in an isolated native cluster; `compose.yaml` and `.env.example` supplied; Compose configuration validated. Docker socket access is blocked in this execution environment. | Native SQL passed; Docker runtime pending |
| Entity/relationship diagrams | [Schema and core mapping](../database-schema.md) covers departments, specialties, doctors, rooms, equipment, availability, patients and requests. | Written; Amine review pending |
| Numbered migrations, FK, duration checks and `btree_gist` | V001–V003 kept byte-identical. Empty-database migration/replay, exact FK errors and negative/zero/oversized durations passed native SQL assertions. Candidates, confirmations and experiments are represented. | Native verification passed |
| `MigrationIT` with Testcontainers | [Test class](../../src/test/java/com/hospitalscheduler/persistence/MigrationIT.java) compiles and is selected by the `database-it` profile. Container initialization failed before any of its four test methods ran. | Implemented; Docker execution pending |
| Connection documentation for four PCs | [Team setup](../team-setup.md) gives Windows/Linux commands, host JDBC checks, expected results and evidence collection for each member. | Documented; per-PC results pending |
| Credentials | `.env.example` has an empty password and the local `.env` path is ignored. The fixed test-container password is a disposable fixture credential. | No real credential added |

The integration build completed successfully with all 18 core unit tests and
compiled the persistence integration test. The native SQL run confirmed all
27 tables, 150 feasible confirmed appointments, 300 candidates, 80 synthetic
experiment runs and 1680 synthetic fitness points. Migration and seed replay
preserved data; replanning retained all five original locked reservation IDs
and rolled back its changes. These data are fixtures, not measured benchmarks.

Commands actually executed during integration and cleanup, from the repository
root:

| Command / check | Outcome |
| --- | --- |
| `bash mvnw -B -ntp -Dmaven.repo.local=/tmp/hospital-scheduler-m2 verify` | PASS: full reactor, 18 core tests, integration-test compilation. |
| `bash mvnw -B -ntp -Dmaven.repo.local=/tmp/hospital-scheduler-m2 clean verify` after documentation cleanup and removal of the unused persistence `Main` | PASS: all six modules rebuilt; 18 core tests passed; integration test compiled. |
| `bash mvnw -B -ntp -Dmaven.repo.local=/tmp/hospital-scheduler-m2 -pl scheduler-persistence -am -Pdatabase-it verify` | Failed at container initialization: `Could not find a valid Docker environment`; Docker socket permission denied. No test method ran. |
| `PG_BINDIR=/tmp/hospital-scheduler-postgresql/bin bash scheduler-persistence/scripts/verify-database.sh` with socket permission | PASS: actual PostgreSQL 18.7 server, psql migration runner/replay, constraints, seed replay, feasibility, queries and replanning rollback. Temporary cluster stopped and removed. |
| The same SQL command inside the restricted sandbox | PASS via the supplied single-user backend fallback; this fallback does not establish Docker/JDBC success. |
| Compose `config --quiet` with a temporary verification password | PASS: configuration only; no development container started. |
| Migration SHA-256 comparison and persistence JAR inspection | PASS: original V001–V003 checksums retained; migrations included in the JAR and development seed excluded. |

## Verification on the four PCs

Each member follows [team-setup.md](../team-setup.md) using the same committed
feature SHA. Local sandbox/native results above do not substitute for running
Compose, the host connection check and Testcontainers on each PC. No completed
four-PC connection record has been supplied.

| Member | OS / JDK / tool versions | Tested commit | Compose + host JDBC connection | Four `MigrationIT` methods | Evidence / date |
| --- | --- | --- | --- | --- | --- |
| Aymane | Not recorded | Not recorded | Pending | Pending | Pending |
| Amine | Not recorded | Not recorded | Pending | Pending | Pending |
| Ilyass | Record the host environment | Not recorded | Pending | Pending; Docker blocked in the local agent run | Local checks above; host run pending |
| Youness | Not recorded | Not recorded | Pending | Pending | Pending |

For each row, record:

- Date, OS, `java -version`, wrapper `-v`, Docker/Compose versions and full commit
  SHA; note any uncommitted changes.
- Healthy Compose service, migration versions 1–3, seed counts and successful
  host-side IntelliJ Test Connection plus `SELECT current_database(), current_user;`.
- Exact build/test commands and results. Attach the Failsafe text report showing
  four tests, no failures/errors/skips; a missing or failing report stays open.
- Any error and the corrective action, without credentials or a password-bearing
  connection URL.

## Remaining Day 1 delivery checks

- Docker runtime and all four `MigrationIT` methods verified successfully.
- All four PC records completed against the same committed source.
- Amine reviews the schema; record reviewer, date and outcome here.
- Team validates D02/D04: tool versions and explicit time-zone mapping. Current
  Java 17 and `Europe/Paris` fixtures are documented; the plan's Java 21 and
  `Africa/Casablanca` alternatives were proposals, not recorded approvals.
- Feature commit/delivery review and any applicable test-first evidence recorded.
  No red/green history or colleague approval is claimed here.

The concrete SQL/core gaps remain in [decisions.md](../decisions.md). Application
repositories and UI wiring belong to later work, including J2-IL; they are not
counted as missing Day 1 implementations.
