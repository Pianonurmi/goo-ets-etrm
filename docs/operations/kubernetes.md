# Kubernetes deployment

The application runs on Kubernetes from the Helm chart in `ops/helm/etrm`. PostgreSQL runs from the CloudNativePG
cluster in `ops/k8s/postgres-cluster.yaml`. CI lints the chart (`helm lint --strict`), renders it twice (defaults, and
with every optional part on) and validates the output against the Kubernetes 1.31 schemas with kubeconform. The
CloudNativePG and ServiceMonitor resources are checked against the CRD catalogue.

## Install

```bash
# Operator, once per cluster: https://cloudnative-pg.io/documentation/current/installation_upgrade/
kubectl create namespace etrm
kubectl -n etrm create secret generic etrm-backup-credentials --from-literal=ACCESS_KEY_ID=... --from-literal=ACCESS_SECRET_KEY=...
kubectl -n etrm apply -f ops/k8s/postgres-cluster.yaml     # creates etrm-db-rw, etrm-db-ro and secret etrm-db-app

kubectl -n etrm create secret tls etrm-tls --cert=... --key=...
helm upgrade --install etrm ops/helm/etrm -n etrm \
  --set image.tag=<git sha> \
  --set ingress.host=etrm.example.com \
  --set oidc.issuer=https://login.example.com/realms/etrm \
  --set environment=PROD
```

Flyway migrates at start-up. Each module's migrations run under PostgreSQL's advisory lock, so when two replicas start
together the second one waits and then finds nothing to do. A rolling update keeps every old pod serving
(`maxUnavailable: 0`) until a new one is ready.

## What the chart sets

| Concern | Setting |
|---|---|
| Profiles | `kubernetes,json`: actuator on the management port (9090), readiness includes the database, ECS JSON logs, forwarded headers from the ingress |
| Probes | startup and liveness on `/actuator/health/liveness`, readiness on `/actuator/health/readiness`, all on the management port |
| Shutdown | `preStop` sleep of 10 s so the endpoints drop the pod first; 30 s per shutdown phase in the application; 60 s grace period |
| Security | non-root user 10001 (numeric in the image), read-only root file system with `emptyDir` for `/tmp`, all capabilities dropped, `RuntimeDefault` seccomp, no service account token. `/tmp` must allow exec: the exact allocation solver unpacks its native library there. An `emptyDir` does, and otherwise exact allocation falls back to greedy and says so. |
| Secrets | database URL and credentials from `database.existingSecret` (default `etrm-db-app` from CloudNativePG); nothing secret in values |
| Scaling | HPA on CPU (2–6 replicas); scheduled jobs run once across replicas through ShedLock; PDB keeps one pod during node drains; pods spread across zones and nodes |
| Network | ingress routes only the HTTP port. The network policy admits the ingress controller's namespace to HTTP and the monitoring namespace to the management port. |
| Metrics | Prometheus annotations on the pod, or a `ServiceMonitor` when `serviceMonitor.enabled` |
| Registry files | the FILE channel writes to `/var/lib/etrm/registry-outbox`: an `emptyDir` by default, or a claim with `registryOutbox.persistence` (ReadWriteMany when there is more than one replica) |
| Optional | Kafka (`kafka.enabled`, `kafka.bootstrapServers`, `kafka.topicReplicas`), OTLP traces (`tracing.otlpEndpoint`), extra environment (`extraEnv`) |

## PostgreSQL

- **Topology:** three instances, one primary and two replicas, spread across zones.
- **Durability:** synchronous commit to at least one replica (`dataDurability: required`). A failover loses no acknowledged write.
- **Backups:**
  - WAL is archived continuously and a base backup runs every night to object storage, kept for 35 days.
  - Point-in-time recovery is a new `Cluster` bootstrapped with `recovery` from that store (see the DR runbook).
- **Connections:** the application connects through `etrm-db-rw`. Each replica's Hikari pool is 20 connections, so six replicas (120) stay under `max_connections` (200).
- **Schemas:** the application user owns the database and creates the `etrm_<module>` schemas through Flyway.

## Not covered by the chart

- **Identity provider:** the OIDC realm and clients (`ops/keycloak` has the development realm).
- **Supporting services:** the Kafka cluster and the OpenTelemetry collector.
- **Ingress controller and certificates:** cert-manager can issue `etrm-tls`.
