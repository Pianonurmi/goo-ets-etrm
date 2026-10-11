# 7. Deployment view

| Component | Increment 1 | Target |
|---|---|---|
| Application | One Spring Boot jar (`apps/api`) that also serves the web UI from `static/` (ADR 0025), container image from `ops/Dockerfile` | Kubernetes (Helm), 2+ replicas, ShedLock serialises scheduled jobs |
| Database | PostgreSQL 16/17 with `ltree` and `btree_gist`; one schema per module | Managed PostgreSQL with point-in-time recovery; RPO 15 min, RTO 4 h |
| Identity | Keycloak (dev) via `ops/docker-compose.yml` | Corporate OIDC provider (e.g. Entra ID) |
| Observability | Prometheus metrics (separate management port in Kubernetes), OpenTelemetry traces over OTLP when configured, `X-Trace-Id` on every response, ECS JSON logs in the `json` profile ([operations/observability.md](../operations/observability.md)) | OpenTelemetry collector, Grafana |
| Build | Gradle, jOOQ codegen against a PostgreSQL service in CI | Same |

Environment variables: `ETRM_DB_URL`, `ETRM_DB_USER`, `ETRM_DB_PASSWORD`, `ETRM_OIDC_ISSUER`; for the web UI
`ETRM_OIDC_BROWSER_ISSUER` (when the browser reaches the identity provider under another URL), `ETRM_OIDC_WEB_CLIENT`
(default `etrm-web`) and `ETRM_ENVIRONMENT` (label in the header); build-time `ETRM_CODEGEN_DB_URL`, `ETRM_TEST_DB_URL`.

The web bundle is built by `:apps:web` (Vite) and copied into the jar under `static/`. Hashed assets are cached as
immutable; `index.html` is not cached and serves every client route. Responses are compressed. A bundle budget
(`apps/web/scripts/check-bundle.mjs`, part of `check`) keeps the first page under 330 kB gzip.

Requests run on platform threads: virtual threads are off while the runtime is Java 21, because pinning in
`synchronized` blocks deadlocked the server under concurrent first requests (see `application.yml`).
