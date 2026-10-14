# ADR 0049: Test strategy

- Status: accepted
- Date: 2026-10-14

## Context
The review of October 2026 (findings C8 and W4) found good tests in the wrong proportions: integration tests drive
most modules against PostgreSQL, but hand-wired tests cannot show transaction behaviour (savepoints, rollback-only
marks), property tests were seeded, and web coverage measured a small part of the tree.

## Decision
| Layer | What it proves | Where |
|---|---|---|
| Domain unit tests | Pure rules: costing, rounding, eligibility, Black-76, four-eyes predicates | `modules/*/src/test`, `apps/web` Vitest |
| Module integration tests | A module's services against a real PostgreSQL, wired by hand (`TestDatabase`) | `*IT` in each module |
| Spring-context tests | Transactions, savepoints, security chains, listeners across modules | `apps/api` `*IT` |
| Database protection tests | Triggers and grants: what the runtime role and the application cannot change | `AppendOnlyTablesIT`, `DatabaseRolesIT`, `JournalIntegrityIT` |
| Contract tests | The OpenAPI snapshot, every operation's guard (`OperationGuardsIT`), token validation | `apps/api` |
| End-to-end journeys | A person's work through the UI, both personas of every approval | Playwright, `apps/web/e2e` |
| Load and drills | Throughput, recovery time | k6 (`ops/load`), the DR drill workflow |

- **A path that catches a domain error from another `@Transactional` service gets a Spring-context test**, because
  hand-wired tests pass `Savepoints.NONE` and cannot show a rollback-only transaction.
- **Property tests** run with a fixed seed in the build and unseeded in the nightly job, which reports the seed of a
  failure.
- **Time** comes from an injected `Clock` (ADR 0050); tests use a fixed clock.
- **Coverage is a ratchet**: Kover's line and branch bounds and the Vitest thresholds are raised as coverage grows,
  never lowered.
- **Retries hide defects.** Playwright retries stay at one in CI only while a flake is being fixed, with an issue.

## Consequences
- A defect found in production gets a test at the lowest layer that can show it.
- The database protection tests make a trigger or grant that disappears fail the build.
