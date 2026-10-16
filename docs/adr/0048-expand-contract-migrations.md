# ADR 0048: Expand and contract database migrations

- Status: accepted
- Date: 2026-10-14

## Context
- A rolling upgrade runs the previous release beside the new one: old pods serve requests against a schema the new
  release has already migrated. Increment 14 also moves migrations into a Helm hook that runs before the new pods
  start, as the schema owner (review finding O1).
- Nothing so far kept a migration compatible with the release before it (review finding D10). A dropped or renamed
  column breaks the old pods in the middle of the upgrade, and a failed upgrade cannot roll back to them.

## Decision
- **Every migration is compatible with the previous release.** A change that would break it is split across
  releases:
  1. **Expand**: add the new column, table or constraint as optional, write both old and new shapes, backfill.
  2. **Migrate readers**: the next release reads the new shape only.
  3. **Contract**: a later release drops what is no longer read, once no supported release reads it.
- **Never in one release:** dropping or renaming a column or table that the previous release reads; adding a
  `NOT NULL` column without a default; narrowing a type or a check constraint; changing the meaning of a value.
- **Append-only tables** stay append-only through all three steps; a contract step never rewrites history.
- **Migrations are forward only.** A failed release rolls back the application, never the schema; that is safe
  because of the rule above.
- **CI checks it**: each build migrates an empty database with its own jar (`--migrate-only`), then starts the
  latest published release on it with migrations off and lets its demo seeder write a full data set through the
  REST API (`ops/release/compat-check.sh`, step "Previous release on the migrated schema"). The seeder comes from the
  previous release's showcase image. Its demo data follows the date it is seeded on, so when it fails on the migrated
  schema the check seeds the previous release on its own migrations too: the same failing step there shows that the
  release's demo data, not the schema, is at fault, and the check passes with a warning.

## Consequences
- Some changes take two or three releases. The pull request template asks whether a migration is additive.
- The previous release can run against the new schema, so a rollback of the image is always possible.
