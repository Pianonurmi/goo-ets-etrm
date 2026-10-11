# Database operations

## Partitions (ADR 0030)

| Table | Partition key | Partitions |
|---|---|---|
| `etrm_audit.audit_event` | `occurred_at` | `audit_event_pYYYYMM`, `audit_event_pdefault` |
| `etrm_inventory.lot_event` | `recorded_at` | `lot_event_pYYYYMM`, `lot_event_pdefault` |

`PartitionMaintenance` keeps the current month and the next three (`etrm.partitions.months-ahead`). It runs nightly
at 02:07 UTC (`etrm.partitions.maintenance-cron`) on one replica.

Check the partitions of a table:

```sql
SELECT c.relname, pg_get_expr(c.relpartbound, c.oid) AS bounds, pg_size_pretty(pg_total_relation_size(c.oid))
  FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
 WHERE i.inhparent = 'etrm_audit.audit_event'::regclass ORDER BY 1;
```

### Rows in a default partition

The alert is `etrm_db_partitions_default_rows > 0`. It means rows were written for a month that had no partition,
because the job did not run for months or because a row carried a far-future time. To resolve it, run the following
as the schema owner in a maintenance window, in one transaction. The steps use the audit table; the same applies to
`lot_event`.

```sql
BEGIN;
SET LOCAL lock_timeout = '10s';
-- 1. Move the default partition aside and give the table a new, empty default.
ALTER TABLE etrm_audit.audit_event DETACH PARTITION etrm_audit.audit_event_pdefault;
ALTER TABLE etrm_audit.audit_event_pdefault RENAME TO audit_event_pdefault_old;
CREATE TABLE etrm_audit.audit_event_pdefault PARTITION OF etrm_audit.audit_event DEFAULT;
-- 2. Create the missing month(s), then copy the rows back through the parent so they land in the right partition.
CREATE TABLE etrm_audit.audit_event_p202701 PARTITION OF etrm_audit.audit_event
  FOR VALUES FROM ('2027-01-01 00:00:00+00') TO ('2027-02-01 00:00:00+00');
INSERT INTO etrm_audit.audit_event OVERRIDING SYSTEM VALUE SELECT * FROM etrm_audit.audit_event_pdefault_old;
-- 3. Drop the old default only after the counts match.
DROP TABLE etrm_audit.audit_event_pdefault_old;
COMMIT;
```

The copy keeps `seq` and the hash columns, so the lot journal's chains still verify:
`GET /api/v1/inventory/journal/verify`.

### Archiving a year

Nothing deletes audit or journal rows automatically. When the retention period of a year has passed (ten years for
accounting records), archiving needs a change approved by a second person. Then, per month:

1. Detach the partition: `ALTER TABLE ... DETACH PARTITION ... CONCURRENTLY`. Detaching removes the cloned
   append-only trigger, so do steps 2 and 3 in the same window, and let no one but the schema owner reach the table.
2. Export it with `pg_dump -Fc -t etrm_audit.audit_event_p201601`, record the file's SHA-256 in the change record,
   and store the file in the archive bucket with object lock.
3. Drop the detached table.

## Event publications

`etrm_platform.event_publication` is the Spring Modulith registry of events sent to listeners and to Kafka (ADR 0029).
Completed rows older than seven days are deleted. The gauge `etrm_events_publications_incomplete` counts the pending
ones.
