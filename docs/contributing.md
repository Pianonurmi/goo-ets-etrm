# Contributing

How changes reach `main`, and the conventions every change follows. `CLAUDE.md` holds the same conventions in more
detail for coding assistants; where the two differ, this page wins and `CLAUDE.md` is corrected.

## How a change reaches main

1. Work on a branch named after what it does (`inc14/m1-authorisation`, `fix/expiry-guard`, `patch/0.13.2`).
2. Open a pull request against `main` (or `release/X.Y` for a patch to an older line). Fill in the template: what,
   why, how it was checked, and the risk checklist.
3. CI (`chart` and `build`) must be green on the pull request's last commit. A red build is fixed, never skipped or
   re-run until it passes; a flaky test is made robust in the same pull request or its own.
4. A reviewer other than the author approves. `CODEOWNERS` names who must look at migrations, the OpenAPI contract,
   security configuration, the ledger, `ops/` and the workflows.
5. Merge with a merge commit (the history keeps each pull request's commits). Delete the branch.

Patch releases for an older line follow [releases](operations/releases.md#cutting-a-release): a pull request into
`release/X.Y`, the Release workflow on that branch, then the same fix carried forward to `main` in its own pull
request.

## Before you push

```bash
./gradlew ktlintFormat detekt
./gradlew build                     # PostgreSQL from ops/docker-compose.yml, see README
./gradlew :apps:web:check           # when apps/web changed
cd apps/web && npm run e2e          # when a screen or a REST guard changed
```

## Conventions that matter most

- **Money and quantities**: `BigDecimal` only, rounding stated where it happens, every posting balanced. A change
  to postings, valuation or settlement comes with a test that shows the figures.
- **Access**: every endpoint checks the role and the legal entity (`Actor.requireRole`, `requireEntity`,
  `entityFilter`). Requests without a signed-in user act as nobody.
- **Migrations**: forward-only, additive, and safe while the previous release still runs (add, backfill, then
  remove in a later release). Never edit a migration that has been released.
- **Append-only records** (lot and allowance journals, the ledger, audit, documents): correct by reversal, never
  by update.
- **Contracts**: the OpenAPI snapshot and the Kafka event JSON are public; change them deliberately and note it in
  the changelog.
- **Documentation**: an ADR for a decision someone will later question; the user guide page when a screen changes;
  `CHANGELOG.md` under `## [Unreleased]`.

## Repository settings (owner)

Branch protection is a repository setting, so it is not in the code. Apply
[the ruleset](operations/branch-protection.md) to `main` and `release/*`.
