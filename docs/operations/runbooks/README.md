# Runbooks

One page per situation an operator meets, each with the same four parts: the **signal** (the alert or symptom), how
to **confirm** it, what to **do**, and how to **verify** that it is over. Alerts in the Helm chart's `PrometheusRule`
(`prometheusRule.enabled`) link to their page through `runbook_url`.

| Page | Alerts or symptom |
|---|---|
| [Application down](application-down.md) | `EtrmDown` |
| [Error rate](error-rate.md) | `EtrmHighErrorRate` |
| [Latency and the connection pool](latency-and-pool.md) | `EtrmSlowRequests`, `EtrmDatabasePoolSaturated`, lock timeouts |
| [Scheduled job failures](job-failures.md) | `EtrmJobFailed`, `EtrmJobNotRun` |
| [Hash chain broken](hash-chain-broken.md) | `EtrmHashChainBroken` |
| [Events not posted](ledger-unposted.md) | `EtrmLedgerUnposted` |
| [Event publications](event-publications.md) | `EtrmEventPublicationsStuck`, Kafka outage, a poison publication |
| [Registry instructions](registry-instructions.md) | `EtrmRegistryDispatchLag`, `EtrmRegistryInstructionsUnknown` |
| [FX rates stale](fx-rates-stale.md) | `EtrmFxRatesStale` |
| [Database backup and failover](database-backup.md) | `EtrmDatabaseBackupOld`, `EtrmDatabaseWalArchiveFailing`, a CloudNativePG failover |
| [Identity provider outage](identity-provider.md) | Sign-in fails, every API call answers 401 |
| [Period end failed](period-end.md) | A period end run or hard close refused |
| [Secret and certificate rotation](secret-rotation.md) | Planned rotation of database, OIDC, registry and signing credentials |

Default-partition rows and archiving are in [database operations](../database.md#rows-in-a-default-partition);
restores in [disaster recovery](../disaster-recovery.md).
