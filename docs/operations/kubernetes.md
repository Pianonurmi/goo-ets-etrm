# Kubernetes deployment

The application runs on Kubernetes from the Helm chart in `ops/helm/etrm`. PostgreSQL runs from the CloudNativePG
cluster in `ops/k8s/postgres-cluster.yaml`. CI lints the chart (`helm lint --strict`), renders it twice (defaults, and
with every optional part on) and validates the output against the Kubernetes 1.31 schemas with kubeconform. The
CloudNativePG and ServiceMonitor resources are checked against the CRD catalogue.

## Install

```bash
# Once per cluster: the operator (https://cloudnative-pg.io/documentation/current/installation_upgrade/), cert-manager,
# then the Barman Cloud plugin, which takes the backups (CloudNativePG 1.26 or later):
kubectl apply -f https://github.com/cloudnative-pg/plugin-barman-cloud/releases/download/v0.15.1/manifest.yaml
kubectl create namespace etrm
kubectl -n etrm create secret generic etrm-backup-credentials --from-literal=ACCESS_KEY_ID=... --from-literal=ACCESS_SECRET_KEY=...
kubectl -n etrm apply -f ops/k8s/postgres-cluster.yaml     # creates etrm-db-rw, etrm-db-ro and secret etrm-db-app

kubectl -n etrm create secret tls etrm-tls --cert=... --key=...
helm upgrade --install etrm ops/helm/etrm -n etrm \
  --set image.tag=0.9.0 \
  --set ingress.host=etrm.example.com \
  --set oidc.issuer=https://login.example.com/realms/etrm \
  --set oidc.audience=etrm-api \
  --set environment=PROD
```

Access tokens must carry `oidc.audience` in their `aud` claim; configure an audience mapper (Keycloak) or the
application ID URI (Entra ID) for the clients that call the API. The chart deploys its `appVersion` image unless `image.tag` says otherwise; pin a release, and see
[releases](releases.md) for the image tags and upgrades.

### Migrations and upgrades

Migrations run in a Job before the install or upgrade (`migrations.job`, a `pre-install,pre-upgrade` hook): the image
with `--migrate-only`, as the schema owner, then the Job exits. The pods start with `ETRM_MIGRATIONS_ENABLED=false`.
A failed migration fails `helm upgrade` before any new pod starts, and the running version keeps serving; the Job's
log says why (`kubectl logs job/<release>-etrm-migrate`, kept until the next attempt).

While the new pods roll out (`maxUnavailable: 0`), pods of the previous version serve on the migrated schema. That is
safe because migrations only expand the schema ([ADR 0048](../adr/0048-expand-contract-migrations.md)),
and CI starts the previous release on each build's schema to prove it (`ops/release/compat-check.sh`). With
`migrations.job: false` the pods migrate at start-up as before (Flyway's per-schema lock lets one replica do it).

## What the chart sets

| Concern | Setting |
|---|---|
| Profiles | `kubernetes,json`: actuator on the management port (9090), readiness without the database (an outage answers 503 problems instead of emptying the service), ECS JSON logs, forwarded headers from the ingress |
| Probes | startup and liveness on `/actuator/health/liveness`, readiness on `/actuator/health/readiness`, all on the management port with a 3 s timeout |
| Shutdown | `preStop` sleep of 10 s so the endpoints drop the pod first; requests in flight and a running scheduled job get 30 s to finish, then async runs 15 s more (`etrm.async-runs.shutdown-wait`; a run cut off is failed by another instance within three minutes and can be started again); 60 s grace period |
| Security | non-root user 10001 (numeric in the image), read-only root file system with `emptyDir` for `/tmp`, all capabilities dropped, `RuntimeDefault` seccomp, no service account token. `/tmp` must allow exec: the exact allocation solver unpacks its native library (about 70 MB) there at every container start. An `emptyDir` does, and otherwise exact allocation falls back to greedy and says so; `tmpSizeLimit` (512 MiB) bounds it. |
| Secrets | database URL and credentials from `database.existingSecret` (default `etrm-db-app` from CloudNativePG); nothing secret in values |
| Scaling | HPA on CPU (2–6 replicas), optionally on request rate too ([below](#scaling-on-request-rate)); scheduled jobs run once across replicas through ShedLock; the PDB lets node drains take one pod at a time; pods spread across zones and nodes |
| Timeouts | the ingress waits 45 s for an answer (`proxy-read-timeout`, 5 s to connect). Period end, valuation runs and report exports answer 202 and run outside the request (`GET /api/v1/runs/{id}` reports their status), so the slowest synchronous request is an allocation proposal at design size, about 25 s: 3 s preparing the portfolio, 6 s greedy, up to 10 s exact and 5 s pricing. The synchronous report (`GET /api/v1/reports/{code}`) is bounded by the same timeout; long reports go through `POST /api/v1/reports/{code}/exports`. |
| Memory | request 1.5 GiB, limit 2 GiB; see [memory](#memory) |
| Network | ingress routes only the HTTP port. The network policy admits the ingress controller's namespace to HTTP and the monitoring namespace to the management port. With `networkPolicy.egress` (a list of egress rules) outbound traffic is limited to DNS and those destinations: the database, the identity provider, the registries, Kafka, the collector, and the ECB when it is on. |
| Metrics | Prometheus annotations on the pod, or a `ServiceMonitor` when `serviceMonitor.enabled` |
| Registry files | the FILE channel writes to `/var/lib/etrm/registry-outbox`: an `emptyDir` by default, or a claim with `registryOutbox.persistence` (ReadWriteMany when there is more than one replica) |
| Optional | Kafka (`kafka.enabled`, `kafka.bootstrapServers`, `kafka.topicReplicas`; `kafka.createTopics` off by default, topics are provisioned by the platform; `kafka.securityProtocol`, `kafka.saslMechanism` and `kafka.jaasSecret` for SASL), ECB rates (`ecb.enabled`), OTLP traces (`tracing.otlpEndpoint`), further settings (`config`, see below), extra environment (`extraEnv`) |

### Memory

Measured in October 2026 (increment 16):

| What | Figure | How |
|---|---|---|
| The application at rest | about 160 MB live heap and 160 MB non-heap; resident set about 1.35 GB | the jar in the `dev` profile on an empty database, after a full GC (`jvm.memory.used`, `VmRSS`) |
| One entity's prepared portfolio flow at design size (100 000 lots, 5 000 obligations) | 58 MB kept while cached; about 470 MB of heap at the peak of preparing it | `PortfolioPrepareBenchmarkTest` at design size prints `benchmark prepare memory` |
| The lots and obligations it is built from | about 110 MB while being prepared | the same line, `inputs` |

The resident set at rest is high because ZGC keeps committed heap for some minutes after it is used; the request
follows it, so the scheduler does not overcommit the node. The image gives the heap 75 % of the limit
(`-XX:MaxRAMPercentage=75`): at 2 GiB that is 1.5 GiB, enough for the application at rest, two entities preparing at
design size at once and about six prepared entities in the cache. Size the limit as

    limit ≥ (330 MB + 470 MB × entities preparing at once + 58 MB × prepared entities) / 0.75

for a larger book, and repeat the measurement after a change to the allocation engine
(`./gradlew :modules:allocation:test --tests '*PortfolioPrepareBenchmarkTest*' -Detrm.benchmark.lots=100000
-Detrm.benchmark.obligations=5000 -i`).

### Registry adapters and other settings

`config` is written to a config map and read as `/etc/etrm/application.yml`; pods restart when it changes. Secrets
never go there: put them in a Secret, pass them with `extraEnv`, and name the variable in the setting.

```yaml
config:
  etrm:
    registry:
      adapters:
        FINGRID:
          enabled: true
          base-url: https://registry.example/api
          client-id: etrm
          token-url: https://registry.example/oauth/token
          credential-env: ETRM_REGISTRY_FINGRID_SECRET
extraEnv:
  - name: ETRM_REGISTRY_FINGRID_SECRET
    valueFrom:
      secretKeyRef: {name: etrm-registry-fingrid, key: client-secret}
```

### Scaling on request rate

CPU follows load late when requests wait on the database rather than compute. With
`autoscaling.requestsPerSecond.enabled` the HPA also scales on requests per second per pod, a custom pods metric the
[Prometheus adapter](https://github.com/kubernetes-sigs/prometheus-adapter) serves from the application's
`http_server_requests_seconds_count`. The adapter needs a rule like this one (the name must match
`autoscaling.requestsPerSecond.metricName`):

```yaml
rules:
  custom:
    - seriesQuery: 'http_server_requests_seconds_count{namespace!="",pod!=""}'
      resources:
        overrides:
          namespace: {resource: namespace}
          pod: {resource: pod}
      name:
        as: http_server_requests_per_second
      metricsQuery: 'sum(rate(<<.Series>>{<<.LabelMatchers>>,uri!~"/actuator.*"}[2m])) by (<<.GroupBy>>)'
```

The default target, 150 requests per second per pod, is where one instance still answered reads at a p95 of 25 ms
in the load test (`baseline`, 144 req/s); at 556 req/s (`stress`) the p95 was 600 ms
([load testing](load-testing.md)). The HPA takes the larger of the CPU and request-rate recommendations. Check
`kubectl get --raw "/apis/custom.metrics.k8s.io/v1beta1/namespaces/<ns>/pods/*/http_server_requests_per_second"`
before turning it on: without the adapter the HPA reports the metric as missing and scales on CPU alone.

## PostgreSQL

- **Topology:** three instances, one primary and two replicas, spread across zones.
- **Durability:** synchronous commit to at least one replica (`dataDurability: required`). A failover loses no acknowledged write.
- **Backups:**
  - WAL is archived continuously and a base backup runs every night to object storage, kept for 35 days, through the Barman Cloud plugin (`ObjectStore` `etrm-backups`).
  - Point-in-time recovery is a new `Cluster` bootstrapped with `recovery` from that store (see the DR runbook).
- **Connections:** the application connects through `etrm-db-rw`. Each replica's Hikari pool is 20 connections, so six replicas (120) stay under `max_connections` (200).
- **Schemas:** the owner `etrm` creates the `etrm_<module>` schemas through Flyway (in the migrations Job); requests run as `etrm_app` with table rights only ([database](database.md#roles-schema-owner-and-runtime-role)).

## Not covered by the chart

- **Identity provider:** the OIDC realm and clients (`ops/keycloak` has the development realm).
- **Supporting services:** the Kafka cluster and the OpenTelemetry collector.
- **Ingress controller and certificates:** cert-manager can issue `etrm-tls`.
