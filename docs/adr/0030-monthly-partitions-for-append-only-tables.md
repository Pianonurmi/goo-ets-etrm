# ADR 0030: The large append-only tables are partitioned by month

- Status: accepted
- Date: 2026-10-11

## Context
The audit trail (`etrm_audit.audit_event`) gets a row for every domain event. The lot journal
(`etrm_inventory.lot_event`) gets a row for every lot operation. Both are append-only (trigger-protected), and
they are the tables that grow without bound. Lots and the ledger journal are kept for ten years or more. A single
heap that size makes these slow: vacuum, index rebuilds, backups by period, and moving old years to cheaper
storage.

PostgreSQL's declarative partitioning solves this, but a partitioned table's primary and unique keys must
contain the partition key. The audit trail's idempotency key `event_id` was globally unique on its own.

## Decision
- **Partitioning:** `audit_event` is range-partitioned by `occurred_at`, `lot_event` by `recorded_at`. Each has
  one partition per calendar month (UTC), named `<table>_pYYYYMM`, plus a default partition `<table>_pdefault`.
- **Keys:**
  - The primary keys become `(seq, <partition key>)`. `seq` still comes from one identity sequence, so it
    stays unique and ordered.
  - The audit idempotency key becomes `(event_id, occurred_at)`. A redelivered event carries the same
    `occurred_at`, so a duplicate still conflicts and is ignored.
  - The lot journal's per-lot hash chain is unchanged.
- **Append-only:** the triggers are defined on the partitioned table, so PostgreSQL clones them to every
  partition, including future ones. Updates and deletes stay refused, also when aimed at a partition directly.
- **Migrations:** they copy any existing rows into the partitioned table, keeping `seq`. They create the
  partitions from the oldest row's month to three months ahead.
- **Maintenance:** `PartitionMaintenance` (in `apps/api`) runs nightly under ShedLock. It finds every
  range-partitioned table with a default partition in the `etrm_*` schemas from the catalogue, so a module that
  partitions another table needs no code. It creates the current month and the next three.
- **Default partitions:** a row lands in the default partition only when its month had no partition yet. Such
  rows block creating that month's partition. The job reports the failure, and the gauge
  `etrm.db.partitions.default.rows` (by table) alerts on it.
- **jOOQ:** code generation excludes the partitions (`.*_p[0-9]{6}`, `.*_pdefault`). Code reads and writes the
  parent table only.
- **No automatic deletion:** archiving a year is a manual, four-eyes operation: detach the partitions, export
  them, keep the export with its hash, then drop them. See `docs/operations/database.md`.

## Not partitioned (yet)
- **Ledger journal:** its lines reference entries by foreign key, and the per-entity hash chain runs across
  years. Its volume (one entry per posting) is a fraction of the lot journal.
- **Version tables** (`certificate_lot_version` and others): they hold one row per change of a projection.
  Readers query them by aggregate across all time, so partition pruning would not help them.
- **`event_publication`:** it is purged after seven days instead (ADR 0029).

## Consequences
- An audit lookup by `event_id` alone scans every partition's unique index. That is acceptable for an
  occasional lookup, and the trail is normally read by aggregate, actor or time.
- A query that orders by `seq` across partitions uses a merge of per-partition index scans.
- Before a table can be partitioned, its unique constraints must be redesigned to include the partition key.
