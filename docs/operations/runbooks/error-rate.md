# Error rate

**Signal.** `EtrmHighErrorRate`: more than 2 % of requests answer 5xx for ten minutes.

**Confirm.** In Prometheus, which endpoints fail:
```promql
topk(10, sum by (uri, status, exception) (rate(http_server_requests_seconds_count{status=~"5.."}[5m])))
```
Each failed response carries `X-Trace-Id`; search the logs (`json` profile: `trace.id`) or the trace backend for it.

**Do.**
- One endpoint, `exception` set: a defect. Collect a trace id and the log line (`Unhandled exception`), roll back if a
  release caused it, otherwise raise an issue.
- Every endpoint, `CannotGetJdbcConnectionException` or timeouts: see [latency and the pool](latency-and-pool.md).
- `UnexpectedRollbackException`: a caller caught a domain error without `Savepoints.attempt` (CLAUDE.md); a defect.

**Verify.** The ratio is back under 1 % for 15 minutes.
