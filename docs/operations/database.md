# Database operations

## Roles: schema owner and runtime role

The append-only tables are protected by triggers, and a trigger only protects against a role that cannot disable it.
Production therefore uses two database roles (review finding D1):

| Role | Used by | Rights |
|---|---|---|
| Schema owner (`ETRM_DB_MIGRATION_USER` / `ETRM_DB_MIGRATION_PASSWORD`) | Flyway migrations and `PartitionMaintenance` | Owns the `etrm_*` schemas and every object in them |
| Runtime role (`ETRM_DB_USER` / `ETRM_DB_PASSWORD`) | Requests and jobs | `USAGE` on the schemas, `SELECT`, `INSERT`, `UPDATE`, `DELETE` on tables, sequences; nothing else |

After each module's migrations, `ModularMigrations` grants the runtime role these rights on everything in the schema and
sets default privileges, so tables the owner creates later (monthly partitions) are covered without a migration. The
runtime role cannot `ALTER TABLE … DISABLE TRIGGER`, `TRUNCATE`, `DROP` or `CREATE`; `DatabaseRolesIT` proves it.

When the migration user is not set, or equals the runtime user, one role does both (development, the showcase, the
compose stack). The `kubernetes` profile logs a warning in that case.

In Kubernetes the CloudNativePG cluster (`ops/k8s/postgres-cluster.yaml`) declares the runtime role `etrm_app` with its
password in the secret `etrm-db-runtime`; the application's owner `etrm` keeps the secret `etrm-db-app`. The chart
uses the split when `database.runtimeSecret` is set (the default):

```yaml
database:
  existingSecret: etrm-db-app        # owner: migrations and partitions
  runtimeSecret: etrm-db-runtime     # runtime role: requests and jobs
  url: jdbc:postgresql://etrm-db-rw:5432/etrm
```

An existing installation moves to the split by creating the runtime role and secret (the cluster manifest does both)
and upgrading the chart; the first start grants the runtime role its rights.

## Tamper evidence

| Data | Protection | Verified by |
|---|---|---|
| Journal (`journal_entry`, `journal_line`) | Lines never change; an entry changes only from POSTED to REVERSED by the reversal that names it. One reversal per entry (`journal_entry_reversal_uq`). No entry into a hard-closed period. Lines balance at commit. Hash chain per legal entity; version 2 covers the header, rates, base amounts, dimensions and memo | `GET /api/v1/ledger/journal/verify`; the GL export refuses a broken chain |
| Lot journal (`lot_event`) | Append-only, hash chain per lot | `GET /api/v1/inventory/journal/verify` (`brokenLots`) |
| Allowance movements (`allowance_movement`) | Append-only, hash chain per holding computed in the database (`allowance_movement_digest`) | `GET /api/v1/inventory/journal/verify` (`brokenHoldings`) |
| Versions and history (`certificate_lot_version`, `trade_version`, `reference_history`) | Append-only; the only update closes the current version's period | — |
| Audit trail (`audit_event`) | Append-only; sealed into a hash chain (`audit_seal`) by a job every minute | `GET /api/v1/audit/verify` |
| Posting rule history, registry instruction events, task events, period-end runs, hedge measurements, memo movements | Append-only | — |

Journal entries written before 0.14.0 keep `chain_version = 1` and verify with the payload they were written with.
`registry_instruction_event` records each state of a registry instruction (creation, status, attempt, external
reference, error, response); instructions from earlier versions start with their state at the upgrade.

`AppendOnlyTablesIT` fails the build when one of these tables, or one of its partitions, loses its trigger.

A change made by the schema owner with the triggers disabled is found by the chains, not prevented; the owner's
credentials are for migrations only and are not given to the application at run time.

### The audit seal

Every business transaction writes audit rows, so chaining them at insert would serialise all writes on one lock.
Instead `AuditSealJob` seals committed rows every minute (`etrm.audit.seal-delay`) into `audit_seal`, in the order it
seals them: each seal holds the digest of one audit row and of the previous seal. A row whose transaction commits late
is sealed on a later run, because the job looks for rows without a seal rather than for numbers above a watermark.
`GET /api/v1/audit/verify` (auditors, all entities) reports rows changed or removed after sealing (`brokenSeqs`) and
rows older than ten minutes still without a seal (`unsealed`).

### Who acted, and in which request

Each read-write transaction tells the database the acting user (`etrm.actor`) and the request's trace id
(`etrm.correlation`), local to the transaction. History rows (`reference_history`, `registry_instruction_event`) store
the user, and audit records without a correlation id of their own take the trace id, so an audit record leads to the
request's trace and log lines (`X-Trace-Id`).

Reads that change nothing but matter are recorded too (`SensitiveRead`): a document's content, a party's bank
accounts, report exports, the invoice export and the audit trail itself. Sign-ins and failed sign-ins to the showcase
are recorded as `SignedIn` and `SignInFailed`.

### Nightly verification and the chain-head digest

`ChainVerificationJob` runs at 02:40 UTC (`etrm.integrity.verify-cron`) on one replica. It seals any audit rows left
behind, then verifies the audit seals, the lot journal, the allowance movements and every legal entity's journal.

- The gauge `etrm_integrity_chain_ok{chain="audit|lots|allowances|journal"}` is 1 when the chain verified and 0 when it
  did not; alert on 0. A broken chain is also logged at ERROR with the records named.
- The result is recorded in the audit trail as `ChainVerification`.
- A digest is stored as a document (kind `INTEGRITY_DIGEST`, `chain-heads-YYYY-MM-DD.json`): the audit head and the
  results, signed with HMAC-SHA256 when `ETRM_INTEGRITY_SIGNING_KEY` is set (Helm: `integrity.signingKeySecret`).
  Because the audit trail records every posting and lot event with its hash, the audit head anchors them too.

Keep each day's digest outside the database (an object store with retention lock, or the archive of the GL export):
someone who can rewrite the database and its chains cannot rewrite a digest held elsewhere. To check a past day,
compare its stored digest's `auditHead` with the seal at that time:

```sql
SELECT hash FROM etrm_audit.audit_seal WHERE sealed_at <= '2026-10-14 02:40:00+00' ORDER BY position DESC LIMIT 1;
```

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
