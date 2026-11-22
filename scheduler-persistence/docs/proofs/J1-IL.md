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
  Java mapping gaps.

## Verification recorded

A clean Maven build was run successfully: all six modules compiled and all 18
core unit tests passed. The Day 1 Testcontainers suite compiles. A previous
Testcontainers attempt could not initialize Docker in the local agent environment,
so no Docker test pass is claimed here.

The initial schema's SQL constraints were checked on an isolated PostgreSQL
server. Historical work on schedules, experiment fixtures and replanning was
removed from this Day 1 branch; those checks do not describe the current files.

| Team member | OS and tools | Commit SHA | Host DB connection | Testcontainers result | Date |
|---|---|---|---|---|---|
| Aymane | Pending | Pending | Pending | Pending | Pending |
| Amine | Pending | Pending | Pending | Pending | Pending |
| Ilyass | Pending | Pending | Pending | Docker access pending | Pending |
| Youness | Pending | Pending | Pending | Pending | Pending |

Amine's schema review and the team's Java/time-zone decision remain pending.
