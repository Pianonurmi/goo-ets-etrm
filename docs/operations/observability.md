# Observability

## Metrics

Micrometer with the Prometheus registry. `GET /actuator/prometheus` serves them.

- **Kubernetes** (profile `kubernetes`): the actuator listens on its own port (`ETRM_MANAGEMENT_PORT`, default 9090).
  Probes and Prometheus reach it without a token; the ingress does not route it. On the public port the actuator
  needs a token except for health and info.
- Every meter has the tag `application=goo-ets-etrm`.
- HTTP server timings carry histogram buckets and SLO buckets at 100 ms, 250 ms, 500 ms, 1 s and 2 s, so latency
  percentiles and SLO burn can be computed in Prometheus.
- Application meters include `etrm.reconciliation.breaks.open` (registry dispatcher) and the outbox and dispatcher
  timings of the registry module, and `etrm.inventory.expiry.failures`, the lots the nightly expiry sweep could not
  expire (each also opens a `LOT_EXPIRY_FAILED` task for back office and registry operators).
- `etrm.integrity.chain.ok{chain}` is 1 when the nightly verification found the chain intact and 0 when it did not,
  for the audit seals, the lot journal, the allowance movements and the journal
  ([tamper evidence](database.md#tamper-evidence)).

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
