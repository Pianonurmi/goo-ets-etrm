# Disaster recovery

Targets (architecture plan): **RPO 15 minutes, RTO 4 hours**. The database is the only stateful component.
Generated documents, the audit trail and both hash-chained journals all live in PostgreSQL. The application pods
are stateless, apart from the registry FILE outbox, which is regenerated from the instructions.

## What protects what

| Failure | Protection | Data loss |
|---|---|---|
| Pod or node | Two or more replicas, PDB, readiness gating (Helm chart) | None |
| PostgreSQL primary | CloudNativePG fails over to the synchronous replica within about a minute | None (synchronous commit to one replica) |
| Zone | Instances and pods spread across zones | None |
| Logical damage (bad migration, operator error) | Point-in-time recovery from the WAL archive | Up to the chosen recovery time |
| Region or cluster | New cluster bootstrapped from the object-store backup (WAL archive and nightly base backup) | Up to the last archived WAL, within minutes, inside the 15-minute RPO |

## Restore (CloudNativePG)

1. **Stop writes:** scale the application to zero (`kubectl scale deploy/etrm --replicas=0`). Registry calls then
   stop too, because the outbox dispatcher runs in the application.
2. **Create a new cluster from the backup**, to the latest point or to a time before the damage:

   ```yaml
   apiVersion: postgresql.cnpg.io/v1
   kind: Cluster
   metadata:
     name: etrm-db-restore
   spec:
     instances: 3
     bootstrap:
       recovery:
         source: etrm-db
         recoveryTarget:
           targetTime: "2026-10-11 07:45:00+00"   # omit for the latest point
     externalClusters:
       - name: etrm-db
         barmanObjectStore: { ... same destinationPath and credentials as the original ... }
   ```

3. **Point the application at the new cluster:** set `database.existingSecret` to `etrm-db-restore-app`, then
   `helm upgrade`.
4. **Start one replica** and check:
   - It must start without migrating: the log shows no `Migrating schema`, which would mean the backup and the
     image do not match.
   - The hash chains verify:
     - `GET /api/v1/inventory/journal/verify`
     - `GET /api/v1/ledger/journal/verify?legalEntityId=` for each entity
     - `GET /api/v1/documents/{id}/verify` for a sample of documents
   - `etrm_events_publications_incomplete`: a restore to an earlier time can resend events that consumers already
     saw. They deduplicate on `etrm-event-id`.
5. **Reconcile with the registries:** run the holdings statements for every account. Instructions sent after the
   recovery point show as breaks, and the reconciliation workflow resolves them (break detail: confirm instruction,
   record receipt). Never resend an instruction whose outcome is unknown. The gateways treat it as `Unknown`
   (ADR 0012).
6. **Scale back up** and record the drill or incident: times per step, recovery point, breaks found.

## Drill

`ops/dr/drill.sh` does the same on any PostgreSQL. It backs up a source database with `pg_dump`, restores it into an
empty one, and compares the row counts of every table. Then it starts the jar on the copy (no migrations allowed),
verifies the lot and ledger chains and recent documents, and writes `build/dr/report.md` with the time of each step.

```bash
SOURCE_DB_URL=postgresql://etrm:etrm@localhost:5432/etrm \
TARGET_DB_URL=postgresql://etrm:etrm@localhost:5432/etrm_dr \
APP_JAR=apps/api/build/libs/api.jar ops/dr/drill.sh
```

The **DR drill** workflow (`.github/workflows/dr-drill.yml`) runs it every month, and on demand, against a database
seeded with the demo data. Run the drill against a production backup in the recovery environment every quarter and
file its report with the change record.

### Last local drill

2026-10-11: the source was the database after the end-to-end suite (demo data plus the Playwright scenarios),
PostgreSQL 16, on a 4-core machine.

| Step | Seconds |
|---|---|
| Backup (`pg_dump`, 576 kB) | 0 |
| Restore and row counts (110 tables, 1 674 rows, all equal) | 2 |
| Application ready on the copy (no migrations) | 12 |
| Lot and ledger chains (2 entities), 18 documents, smoke reads | 2 |
| **Restore to verified** | **16** |

Production volumes change the backup and restore times, not the steps. Measure them in the quarterly drill against
a production backup, and keep the total well inside the 4-hour RTO.
