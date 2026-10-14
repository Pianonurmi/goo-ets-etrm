# Observability

## Metrics

Micrometer with the Prometheus registry. `GET /actuator/prometheus` serves them.

- **Kubernetes** (profile `kubernetes`): the actuator listens on its own port (`ETRM_MANAGEMENT_PORT`, default 9090).
  Probes and Prometheus reach it without a token; the ingress does not route it. On the public port the actuator
  needs a token except for health and info.
- Every meter has the tag `application=goo-ets-etrm`.
- HTTP server timings carry histogram buckets and SLO buckets at 100 ms, 250 ms, 500 ms, 1 s and 2 s, so latency
  percentiles and SLO burn can be computed in Prometheus.
- Application meters are listed in the catalogue below.

### Metric catalogue

Names as Prometheus shows them (Micrometer turns dots into underscores and adds `_total` to counters).

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `etrm_jobs_failures_total` | counter | `job_name` | A scheduled job failed, as a whole or for one subject (entity, curve, account, lot) |
| `etrm_jobs_last_success_seconds` | gauge | `job_name` | When the job last completed (epoch seconds) |
| `etrm_jobs_expected_interval_seconds` | gauge | `job_name` | How often the job is expected to complete |
| `etrm_integrity_chain_ok` | gauge | `chain` | 1 when the nightly verification found the chain intact, 0 when not ([tamper evidence](database.md#tamper-evidence)) |
| `etrm_ledger_unposted_open` | gauge | | Events the ledger could not post, waiting in the inbox |
| `etrm_events_publications_incomplete` | gauge | | Domain events not yet delivered to their listeners or Kafka |
| `etrm_events_publications_parked` | gauge | | Publications that failed `etrm.events.max-attempts` times and wait for an administrator |
| `etrm_http_bulkhead_rejected_total` | counter | | API requests refused with 503 at the concurrent request limit (`etrm.http.max-concurrent-requests`) |
| `etrm_http_bulkhead_in_use` | gauge | | API requests in progress, counted against that limit |
| `etrm_db_partitions_default_rows` | gauge | `table` | Rows in a default partition (partition maintenance fell behind) |
| `etrm_registry_dispatch_lag_seconds` | gauge | | Age of the oldest instruction waiting to be sent |
| `etrm_registry_instructions_open` | gauge | `status` | Open registry instructions by status (`UNKNOWN` needs a person) |
| `etrm_reconciliation_breaks_open` | gauge | | Open reconciliation breaks |
| `etrm_marketdata_fx_age_days` | gauge | | Days since the newest FX rate |
| `etrm_inventory_expiry_failures_total` | counter | | Lots the nightly expiry could not expire |
| `http_server_requests_seconds` | histogram | `uri`, `status`, `exception` | API latency and outcomes, with SLO buckets |
| `hikaricp_connections_*` | gauges | `pool` | Database pool: active, idle, pending, max |

### Alerts

The chart's `PrometheusRule` (`prometheusRule.enabled=true`, with the labels your Prometheus selects rules by in
`prometheusRule.labels`) carries the alerts below. Each links its page in the [runbooks](runbooks/README.md).
`prometheusRule.cnpg.enabled` adds the database backup and WAL archive alerts for the CloudNativePG cluster.

| Alert | Severity | Fires when |
|---|---|---|
| `EtrmDown` | critical | A pod is not scraped for 5 minutes |
| `EtrmHighErrorRate` | critical | More than 2 % of requests answer 5xx for 10 minutes |
| `EtrmSlowRequests` | warning | p95 latency above 1 s for 15 minutes |
| `EtrmDatabasePoolSaturated` | warning | More than 5 requests wait for a connection for 5 minutes |
| `EtrmDatabaseUnreachable` | critical | More than 10 requests timed out waiting for a connection in 5 minutes, for 5 minutes |
| `EtrmRequestsShed` | warning | Requests refused at the concurrent request limit for 10 minutes |
| `EtrmJobFailed` | warning | A job failure in the last hour |
| `EtrmJobNotRun` | warning | A job has not completed for twice its interval |
| `EtrmHashChainBroken` | critical | The nightly verification found a broken chain |
| `EtrmLedgerUnposted` | warning | Events have waited for the ledger for an hour |
| `EtrmEventPublicationsStuck` | warning | Events have not been delivered for 30 minutes |
| `EtrmEventPublicationsParked` | critical | A publication failed too often and waits for an administrator |
| `EtrmDefaultPartitionRows` | warning | Rows in a default partition |
| `EtrmRegistryDispatchLag` | warning | Instructions wait more than 10 minutes to be sent |
| `EtrmRegistryInstructionsUnknown` | critical | An instruction's outcome has been unknown for 30 minutes |
| `EtrmFxRatesStale` | warning | The newest FX rate is more than 3 days old |
| `EtrmDatabaseBackupOld` | critical | No base backup for 26 hours (CloudNativePG) |
| `EtrmDatabaseWalArchiveFailing` | critical | WAL archiving failed in the last 15 minutes (CloudNativePG) |

## Traces

Micrometer Tracing with the OpenTelemetry bridge. Spans are created for HTTP requests, JDBC calls via observations,
and scheduled jobs.

- **Export:** traces go to an OTLP collector only when one is configured:
  `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://collector:4318/v1/traces`.
- **Sampling:** `ETRM_TRACE_SAMPLING` (default 0.1). W3C `traceparent` headers are honoured, so an upstream gateway
  decides for the whole trace.
- **Resource attributes:** `service.name=goo-ets-etrm` and `deployment.environment=$ETRM_ENVIRONMENT`.
- **`X-Trace-Id`:** every response carries this header, problems included. A user who reports an error quotes it, and
  support finds the trace and the log lines of that request.

## Logs

- **Default:** plain text with the trace and span ids of the current observation.
- **Profile `json`:** Elastic Common Schema, one JSON object per line, with `service.name`, `service.environment` and the
  trace ids, for Fluent Bit, Vector or the OpenTelemetry collector's file log receiver.

## Health and shutdown

- `/actuator/health/liveness` reports the process.
- `/actuator/health/readiness` reports readiness and, in the `kubernetes` profile, the database.
- Shutdown is graceful: new requests are refused and requests in flight get up to 30 s
  (`spring.lifecycle.timeout-per-shutdown-phase`).
- Scheduled jobs hold their ShedLock until they finish, so another replica takes over the next run.
