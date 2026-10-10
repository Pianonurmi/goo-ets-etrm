# 7. Deployment view

| Component | Increment 1 | Target |
|---|---|---|
| Application | One Spring Boot jar (`apps/api`), container image from `ops/Dockerfile` | Kubernetes (Helm), 2+ replicas, ShedLock serialises scheduled jobs |
| Database | PostgreSQL 16/17 with `ltree` and `btree_gist`; one schema per module | Managed PostgreSQL with point-in-time recovery; RPO 15 min, RTO 4 h |
| Identity | Keycloak (dev) via `ops/docker-compose.yml` | Corporate OIDC provider (e.g. Entra ID) |
| Observability | Actuator health/metrics/prometheus endpoints, structured logs | OpenTelemetry collector, Grafana |
| Build | Gradle, jOOQ codegen against a PostgreSQL service in CI | Same |

Environment variables: `ETRM_DB_URL`, `ETRM_DB_USER`, `ETRM_DB_PASSWORD`, `ETRM_OIDC_ISSUER`; build-time
`ETRM_CODEGEN_DB_URL`, `ETRM_TEST_DB_URL`.
