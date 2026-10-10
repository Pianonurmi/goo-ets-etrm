# ECB euro reference rates

The ECB publishes euro foreign exchange reference rates on TARGET working days, around 16:00 CET. The daily file
(`/stats/eurofxref/eurofxref-daily.xml` on `www.ecb.europa.eu`) lists, for one date, the units of each currency per
euro.

- `EcbPullJob` fetches the daily file at 16:30 and 17:30 Europe/Berlin on weekdays, when `etrm.marketdata.ecb.enabled`
  (environment `ETRM_ECB_ENABLED`) is true. It imports the file through the `ECB_DAILY_XML` format, so a pull and an
  upload of the same document are one import, and a second pull of an unchanged file does nothing.
- Only the GET is retried, on transport errors and 5xx responses, with backoff (`max-attempts`, `retry-backoff`).
  A 4xx is not retried. Timeouts are `connect-timeout` and `read-timeout`.
- XML is parsed with document type declarations disabled.
- The gauge `etrm.marketdata.fx.age.days` shows how old the newest rate is. Alert when it exceeds three days.
- History can be loaded by uploading `eurofxref-hist.csv` with the `ECB_EUROFXREF_HIST` format.
- Manual pull: `POST /api/v1/market-data/ecb/pull` (ADMIN).

Status: tested against a local stub serving the published file layout. The build environment's network policy
blocked `www.ecb.europa.eu` (and `data-api.ecb.europa.eu`). Confirm against the live service once the host is
allowed.
