# Latency and the connection pool

**Signal.** `EtrmSlowRequests` (p95 above one second) or `EtrmDatabasePoolSaturated` (requests waiting for a
connection), lock timeouts in the logs.

**Confirm.**
```promql
max(hikaricp_connections_active) ; max(hikaricp_connections_pending) ; max(hikaricp_connections_max)
```
```sql
-- What the database is waiting on
SELECT pid, state, wait_event_type, wait_event, now() - query_start AS running, left(query, 120)
  FROM pg_stat_activity WHERE datname = current_database() AND state <> 'idle' ORDER BY running DESC;
-- Who blocks whom
SELECT blocked.pid, blocking.pid AS blocking_pid, left(blocking.query, 120)
  FROM pg_stat_activity blocked JOIN pg_stat_activity blocking ON blocking.pid = ANY(pg_blocking_pids(blocked.pid));
```

**Do.**
- A long period end, valuation or export holds the pool: let it finish; move the job if it collides with the day.
- A blocking transaction idle in transaction: note its query and `pid`; terminate it with `pg_terminate_backend(pid)`
  only when it belongs to a stuck request (its transaction rolls back).
- Sustained load: scale out (`autoscaling`), and check that `ETRM_DB_POOL_MAX` times replicas stays below the
  database's `max_connections`.

**Verify.** Pending connections at 0 and p95 under the target in [load testing](../load-testing.md).
