# Scheduled job failures

**Signal.** `EtrmJobFailed`: `etrm_jobs_failures_total{job_name}` grew (a job failed as a whole or for one subject, an
entity, a curve, an account). `EtrmJobNotRun`: a job has not completed for twice its interval.

**Confirm.**
```promql
sum by (job_name) (increase(etrm_jobs_failures_total[6h]))
time() - etrm_jobs_last_success_seconds
```
The log line `Job <name> failed for <subject>` carries the stack trace. Jobs run on one replica at a time
(ShedLock, table `etrm_platform.shedlock`); a job that never completes may hold its lock until `lockAtMostFor`:
```sql
SELECT name, lock_until, locked_at, locked_by FROM etrm_platform.shedlock ORDER BY locked_at DESC;
```

**Do.** By job:
- `valuation-eod`, `risk-measure`: usually a missing curve or FX rate; fix the market data, then run the valuation or
  the risk measurement again from the screens.
- `marketdata-ecb-pull`: see [FX rates stale](fx-rates-stale.md).
- `inventory-lot-expiry`: each failed lot has a `LOT_EXPIRY_FAILED` task in the inbox; fix the cause (often the
  ledger), then the next night expires it.
- `clearing-margin`: a missing settlement price; import it, and later days are recomputed. A day marked **Stale** on
  the margin tab could not be recomputed after an earlier day changed; compute it again once its price is there.
- `settlement-reprice`: deliveries still waiting for a fixing after the morning retry; the item's problem names the
  missing index or month. Import the quotes, or fix the price by hand on the settlement item.
- `partition-maintenance`: see [database operations](../database.md#partitions-adr-0030).
- `integrity-chain-verification`: see [hash chain broken](hash-chain-broken.md).
- A job holding its lock after a crash: wait for `lock_until`, or delete its row from `shedlock` once no replica runs it.

**Verify.** The counter stops growing and `etrm_jobs_last_success_seconds` moves on the next run.
