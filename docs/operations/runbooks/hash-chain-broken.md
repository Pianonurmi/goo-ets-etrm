# Hash chain broken

**Signal.** `EtrmHashChainBroken{chain}`: the nightly verification found a record that does not match its hash.
Treat it as a security incident until shown otherwise: a record was changed outside the application.

**Confirm.** The `ChainVerification` record in the audit trail (Administration, Audit trail) lists what broke:
`auditBroken` (audit rows), `lotsBroken`, `holdingsBroken`, `journalBroken` per legal entity. Recheck on demand:
`GET /api/v1/ledger/journal/verify?legalEntityId=…`, `GET /api/v1/inventory/journal/verify`, `GET /api/v1/audit/verify`.

**Do.**
1. Do not repair anything yet; restrict database access to the incident team and keep the evidence (a dump of the
   affected rows, `pg_stat_statements`, the database logs, who held the schema owner's credentials).
2. Compare with the last good state: the daily chain-head digest stored outside the database
   ([tamper evidence](../database.md#tamper-evidence)) and the backups ([disaster recovery](../disaster-recovery.md)).
3. Decide with the controller and the auditor: restore the affected rows from a backup, or, for the journal, post
   correcting entries and document the incident. A broken journal chain blocks the GL export until resolved.

**Verify.** The verification endpoints report nothing broken and the next nightly run sets the gauge to 1.
