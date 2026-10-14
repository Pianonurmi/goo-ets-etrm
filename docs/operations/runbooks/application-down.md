# Application down

**Signal.** `EtrmDown`: Prometheus cannot scrape a pod for five minutes, or users see 502/503 from the ingress.

**Confirm.**
```bash
kubectl -n etrm get pods -l app.kubernetes.io/name=etrm
kubectl -n etrm describe pod <pod>          # last state, OOMKilled, probe failures
kubectl -n etrm logs <pod> --previous | tail -100
```

**Do.**
- `OOMKilled`: raise `resources.limits.memory` and redeploy; note the RSS in [load testing](../load-testing.md).
- Fails at start with a Flyway error: a migration failed. Do not edit the migration; read the error, fix the data or
  roll back to the previous image (expand/contract migrations keep the previous release working).
- Fails at start with `ProfileGuard`: a `dev` or `test` profile reached a cluster. Remove it from `SPRING_PROFILES_ACTIVE`.
- Readiness fails while liveness passes: see [latency and the pool](latency-and-pool.md).
- Crash loop with database errors: see [database backup and failover](database-backup.md).

**Verify.** All pods `Ready`, `up == 1`, `/actuator/health/readiness` UP on the management port.
