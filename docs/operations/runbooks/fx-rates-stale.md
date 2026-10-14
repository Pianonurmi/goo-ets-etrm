# FX rates stale

**Signal.** `EtrmFxRatesStale`: the newest ECB reference rate is more than three days old. Postings in another
currency start failing once the rate is older than the staleness limit, and wait in [events not posted](ledger-unposted.md).

**Confirm.** Market data, FX rates; the `marketdata-ecb-pull` job's log lines and `etrm_jobs_failures_total`.

**Do.** Check egress to the ECB (`ECB_ENABLED`, the network policy, the proxy). If the ECB is unreachable for a while,
enter the day's rates by hand in market data (`PUT /api/v1/market-data/fx-rates`, with the source named), and retry the waiting postings.

**Verify.** `etrm_marketdata_fx_age_days` is back to 0 or 1 (weekends).
