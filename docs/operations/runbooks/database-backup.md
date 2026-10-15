# Database backup and failover

**Signal.** `EtrmDatabaseBackupOld` (no base backup for 26 hours), `EtrmDatabaseWalArchiveFailing` (WAL cannot reach
the object store), or a CloudNativePG failover (the primary changed).

**Confirm.**
```bash
kubectl -n etrm get cluster etrm-db            # phase, current primary
kubectl -n etrm get backups                     # last backups and their phase
kubectl -n etrm get objectstore etrm-backups -o yaml   # status: first recoverability point, last backups per server
kubectl cnpg status etrm-db -n etrm             # replication, archiving, last archived WAL
```

**Do.**
- Archiving fails: check the object store credentials and bucket policy; point-in-time recovery is at risk until it
  works again. The plugin runs as a sidecar of each instance: read its log
  (`kubectl -n etrm logs etrm-db-1 -c plugin-barman-cloud`). Take a manual backup once it works
  (`kubectl cnpg backup etrm-db -n etrm --method plugin --plugin-name barman-cloud.cloudnative-pg.io`).
- Backup old: the scheduled backup did not run or failed; read its status, the operator's log and the plugin's
  (`kubectl -n cnpg-system logs deploy/barman-cloud`).
- Failover: CloudNativePG promotes a replica and moves the `-rw` service. The application reconnects through Hikari;
  requests in flight fail once. Check that jobs resumed (ShedLock rows are in the database and survive).

**Verify.** A completed backup younger than a day; `cnpg status` shows archiving working; the restore drill in
[disaster recovery](../disaster-recovery.md) is the proof that backups restore.
