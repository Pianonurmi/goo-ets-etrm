#!/usr/bin/env bash
# Point-in-time recovery with CloudNativePG, end to end (review finding O4): continuous WAL archiving to an object
# store, a base backup, writes, a recovery target, the damage, and a new cluster recovered to the target. Checks that
# exactly the rows written before the target are back, and reports the time of each step and the archiving lag.
#
# Runs against the current kubectl context (a kind cluster in CI, .github/workflows/cnpg-drill.yml) with the
# CloudNativePG operator installed. MinIO stands in for the object store.
#
# Usage: ops/dr/cnpg-drill.sh [report file]
set -euo pipefail

ns=etrm-drill
report=${1:-build/dr/cnpg-report.md}
minio_image=${MINIO_IMAGE:-minio/minio:RELEASE.2025-04-22T22-12-26Z}
pg_image=${PG_IMAGE:-ghcr.io/cloudnative-pg/postgresql:17.6}
key=drill-access-key
secret=drill-secret-key-0123456789
mkdir -p "$(dirname "$report")"
declare -a steps=()
started=$(date +%s)
mark=$started
step() { local now; now=$(date +%s); steps+=("| $1 | $((now - mark)) |"); mark=$now; echo "== $1 ($((now - started)) s)"; }
sql() { kubectl -n "$ns" exec "$1-1" -c postgres -- psql -U postgres -d app -v ON_ERROR_STOP=1 -Atc "$2"; }

kubectl create namespace "$ns"
kubectl -n "$ns" apply -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata: {name: minio}
spec:
  selector: {matchLabels: {app: minio}}
  template:
    metadata: {labels: {app: minio}}
    spec:
      containers:
        - name: minio
          image: $minio_image
          args: [server, /data]
          env:
            - {name: MINIO_ROOT_USER, value: $key}
            - {name: MINIO_ROOT_PASSWORD, value: $secret}
          ports: [{containerPort: 9000}]
          readinessProbe: {httpGet: {path: /minio/health/ready, port: 9000}}
---
apiVersion: v1
kind: Service
metadata: {name: minio}
spec:
  selector: {app: minio}
  ports: [{port: 9000}]
---
apiVersion: v1
kind: Secret
metadata: {name: drill-s3}
stringData: {ACCESS_KEY_ID: $key, ACCESS_SECRET_KEY: $secret}
EOF
kubectl -n "$ns" rollout status deploy/minio --timeout=300s
kubectl -n "$ns" port-forward svc/minio 19000:9000 >/dev/null 2>&1 &
forward=$!
trap 'kill $forward 2>/dev/null || true' EXIT
for _ in $(seq 1 30); do curl -fsS -o /dev/null http://127.0.0.1:19000/minio/health/ready && break; sleep 1; done
curl -fsS -X PUT --aws-sigv4 "aws:amz:us-east-1:s3" --user "$key:$secret" http://127.0.0.1:19000/drill
step "Object store ready"

store() {
  cat <<EOF
      destinationPath: s3://drill/
      endpointURL: http://minio.$ns.svc:9000
      s3Credentials:
        accessKeyId: {name: drill-s3, key: ACCESS_KEY_ID}
        secretAccessKey: {name: drill-s3, key: ACCESS_SECRET_KEY}
      wal: {compression: gzip}
EOF
}

kubectl -n "$ns" apply -f - <<EOF
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata: {name: drill-db}
spec:
  instances: 1
  imageName: $pg_image
  storage: {size: 1Gi}
  postgresql:
    parameters:
      # A quiet database still archives a segment this often (production: 5min, ops/k8s/postgres-cluster.yaml).
      archive_timeout: 60s
  backup:
    barmanObjectStore:
$(store)
EOF
kubectl -n "$ns" wait cluster/drill-db --for=condition=Ready --timeout=600s
step "Source cluster ready"

sql drill-db "CREATE TABLE drill (id int PRIMARY KEY, phase text NOT NULL); INSERT INTO drill SELECT g, 'before backup' FROM generate_series(1, 1000) g;"
kubectl -n "$ns" apply -f - <<EOF
apiVersion: postgresql.cnpg.io/v1
kind: Backup
metadata: {name: drill-base}
spec:
  cluster: {name: drill-db}
EOF
kubectl -n "$ns" wait backup/drill-base --for=jsonpath='{.status.phase}'=completed --timeout=600s
step "Base backup"

sql drill-db "INSERT INTO drill SELECT g, 'after backup' FROM generate_series(1001, 2000) g;"
sleep 2
target=$(sql drill-db "SELECT clock_timestamp()")
sleep 2
sql drill-db "INSERT INTO drill SELECT g, 'after target' FROM generate_series(2001, 2500) g; DELETE FROM drill WHERE id % 2 = 0;"
damaged_at=$(date +%s)
wal=$(sql drill-db "SELECT pg_walfile_name(pg_current_wal_lsn())")
sql drill-db "SELECT pg_switch_wal()" >/dev/null
for _ in $(seq 1 300); do
  archived=$(sql drill-db "SELECT coalesce(last_archived_wal, '') FROM pg_stat_archiver")
  [[ -n "$archived" && ! "$archived" < "$wal" ]] && break
  sleep 1
done
[[ -n "$archived" && ! "$archived" < "$wal" ]] || { echo "WAL $wal was not archived"; exit 1; }
lag=$(( $(date +%s) - damaged_at ))
step "Writes, recovery target $target, damage, WAL archived"

kubectl -n "$ns" apply -f - <<EOF
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata: {name: drill-restore}
spec:
  instances: 1
  imageName: $pg_image
  storage: {size: 1Gi}
  bootstrap:
    recovery:
      source: drill-db
      recoveryTarget:
        targetTime: "$target"
  externalClusters:
    - name: drill-db
      barmanObjectStore:
$(store | sed 's/^/  /')
EOF
kubectl -n "$ns" wait cluster/drill-restore --for=condition=Ready --timeout=900s
step "Recovered cluster ready"

rows=$(sql drill-restore "SELECT count(*) FILTER (WHERE phase <> 'after target') || '/' || count(*) FROM drill")
echo "Rows before the target / all rows on the recovered cluster: $rows"
[[ "$rows" == "2000/2000" ]] || { echo "Expected the 2000 rows written before the target and nothing after it"; exit 1; }
step "Recovered rows verified"

{
  echo "# CloudNativePG point-in-time recovery drill"
  echo
  echo "$(date -u +%Y-%m-%d), CloudNativePG $(kubectl -n cnpg-system get deploy cnpg-controller-manager -o jsonpath='{.spec.template.spec.containers[0].image}'), $pg_image."
  echo "Recovery target $target; the damaging writes were archived $lag s after they were made (archive_timeout 60 s)."
  echo
  echo "| Step | Seconds |"
  echo "|---|---|"
  printf '%s\n' "${steps[@]}"
  echo "| **Total** | **$(( $(date +%s) - started ))** |"
} > "$report"
cat "$report"
