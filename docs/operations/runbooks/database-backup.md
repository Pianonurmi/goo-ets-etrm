# Database backup and failover

**Signal.** `EtrmDatabaseBackupOld` (no base backup for 26 hours), `EtrmDatabaseWalArchiveFailing` (WAL cannot reach
the object store), or a CloudNativePG failover (the primary changed).

**Confirm.**
```bash
kubectl -n etrm get cluster etrm-db            # phase, current primary
kubectl -n etrm get backups                     # last backups and their phase
kubectl cnpg status etrm-db -n etrm             # replication, archiving, last archived WAL
```

**Do.**
- Archiving fails: check the object store credentials and bucket policy; point-in-time recovery is at risk until it
  works again. Take a manual backup once it does (`kubectl cnpg backup etrm-db -n etrm`).
- Backup old: the scheduled backup did not run or failed; read its status and the operator's log.
- Failover: CloudNativePG promotes a replica and moves the `-rw` service. The application reconnects through Hikari;
  requests in flight fail once. Check that jobs resumed (ShedLock rows are in the database and survive).

**Verify.** A completed backup younger than a day; `cnpg status` shows archiving working; the restore drill in
[disaster recovery](../disaster-recovery.md) is the proof that backups restore.
