# 7. Deployment view

| Component | Increment 1 | Target |
|---|---|---|
| Application | One Spring Boot jar (`apps/api`) that also serves the web UI from `static/` (ADR 0025), container image from `ops/Dockerfile` | Kubernetes with the Helm chart `ops/helm/etrm` ([operations/kubernetes.md](../operations/kubernetes.md)), 2–6 replicas, ShedLock serialises scheduled jobs |
| Database | PostgreSQL 16/17 with `ltree` and `btree_gist`; one schema per module | CloudNativePG cluster `ops/k8s/postgres-cluster.yaml` (three instances, synchronous replica, WAL archive for point-in-time recovery) or managed PostgreSQL; RPO 15 min, RTO 4 h |
| Identity | Keycloak (dev) via `ops/docker-compose.yml` | Corporate OIDC provider (e.g. Entra ID) |
| Observability | Prometheus metrics (separate management port in Kubernetes), OpenTelemetry traces over OTLP when configured, `X-Trace-Id` on every response, ECS JSON logs in the `json` profile ([operations/observability.md](../operations/observability.md)) | OpenTelemetry collector, Grafana |
| Events | Selected domain events to Kafka when `ETRM_KAFKA_ENABLED` (ADR 0029) | Managed Kafka, three replicas |
| Build | Gradle, jOOQ codegen against a PostgreSQL service in CI | Same |

Environment variables: `ETRM_DB_URL`, `ETRM_DB_USER`, `ETRM_DB_PASSWORD`, `ETRM_OIDC_ISSUER`, `ETRM_OIDC_AUDIENCE`; for the web UI
`ETRM_OIDC_BROWSER_ISSUER` (when the browser reaches the identity provider under another URL), `ETRM_OIDC_WEB_CLIENT`
(default `etrm-web`) and `ETRM_ENVIRONMENT` (label in the header); build-time `ETRM_CODEGEN_DB_URL`, `ETRM_TEST_DB_URL`.

The web bundle is built by `:apps:web` (Vite) and copied into the jar under `static/`. Hashed assets are cached as
immutable; `index.html` is not cached and serves every client route. Responses are compressed. A bundle budget
(`apps/web/scripts/check-bundle.mjs`, part of `check`) keeps the first page under 330 kB gzip.

Requests, scheduled jobs and async work run on virtual threads (Java 25). On Java 21 they were off: a virtual thread
blocking in `synchronized` pinned its carrier, and concurrent first requests hung the server. JEP 491 (Java 24) removed
that pinning; `ConcurrencyIT` replays the burst with two carrier threads.
