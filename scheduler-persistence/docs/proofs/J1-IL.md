# J1-IL — PostgreSQL and initial schema

Day 1 owner: Ilyass. Reviewer: Amine. Team-PC results remain pending; use the
[four-PC setup guide](../team-setup.md) to record actual checks.

## Day 1 scope

- `compose.yaml` and `.env.example` provide a local PostgreSQL service without
  committing credentials.
- V001 creates the initial hospital, specialty, doctor, room, equipment, patient,
  request and availability schema; it includes foreign keys, duration checks and
  `btree_gist`.
- `MigrationIT` checks an empty database, migration replay, negative request
  duration and an invalid patient foreign key.
- The [schema guide](../database-schema.md) documents relationships and current
  Java mapping rules and remaining core-model limitations.

## Verification recorded

A previous full reactor build compiled all six modules. Native PostgreSQL checks
also passed; Docker was initially unavailable to the agent.

On 2026-11-22, Ilyass ran the full `persistence-it` profile from his terminal.
The generated XML reports were inspected: all 18 core tests and 28 integration
tests passed, with zero failures, errors or skips. No native JDBC override was
present, so all four integration suites used Docker/Testcontainers. Maven's Java
runtime was 17.0.19. Generated reports and build outputs were removed after this
summary was recorded, per the requested cleanup policy.

The current working tree includes the Day 2 extensions through V004; this run
covers those extensions as well as the V001 foundation. It does not establish
that the other three team PCs have passed or that the work has been reviewed.

| Team member | OS and tools | Commit SHA | Host DB connection | Testcontainers result | Date |
|---|---|---|---|---|---|
| Aymane | Pending | Pending | Pending | Pending | Pending |
| Amine | Pending | Pending | Pending | Pending | Pending |
| Ilyass | Ubuntu 25.10; Java 17.0.19; Docker Snap 29.8.0 | Working tree with uncommitted changes | Pending | 28 IT passed; no skips | 2026-11-22 |
| Youness | Pending | Pending | Pending | Pending | Pending |

Amine's schema review and the team's Java/time-zone decision remain pending.
