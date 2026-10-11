#!/usr/bin/env bash
# Disaster recovery drill (docs/operations/disaster-recovery.md): back up the source database, restore it into an
# empty one, start the application on the restored copy and prove it is whole.
#
#   ops/dr/drill.sh
#
# Needs pg_dump, pg_restore and psql (the server's major version or newer), curl and python3.
#
# Environment:
#   SOURCE_DB_URL   postgresql://user:password@host:5432/etrm    database to back up (libpq URL)
#   TARGET_DB_URL   postgresql://user:password@host:5432/etrm_dr  empty database to restore into (created if missing)
#   APP_JAR         apps/api/build/libs/api.jar
#   JAVA            java (Java 25)
#   APP_PORT        18080
#   WORK_DIR        build/dr
#
# The application runs on the restored copy with the dev profile, so the checks can act through the persona headers.
# It must never point at production systems: the drill starts it with registry gateways in their default (simulated)
# mode and Kafka off.
set -euo pipefail

: "${SOURCE_DB_URL:?SOURCE_DB_URL is required}"
: "${TARGET_DB_URL:?TARGET_DB_URL is required}"
APP_JAR=${APP_JAR:-apps/api/build/libs/api.jar}
JAVA=${JAVA:-java}
APP_PORT=${APP_PORT:-18080}
WORK_DIR=${WORK_DIR:-build/dr}
mkdir -p "$WORK_DIR"
REPORT="$WORK_DIR/report.md"
BASE="http://localhost:$APP_PORT"
HEADERS=(-H "X-Actor: dr-drill" -H "X-Roles: AUDITOR,ACCOUNTANT,BACK_OFFICE,MIDDLE_OFFICE")
start=$(date +%s)
log() { echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$WORK_DIR/drill.log"; }
fail() { log "FAILED: $*"; echo "- **Result: FAILED** ($*)" >> "$REPORT"; exit 1; }
trap 'fail "command failed at line $LINENO"' ERR

# Row counts of the tables that must survive the restore unchanged.
COUNT_SQL="SELECT format('%s.%s', schemaname, relname), n FROM (
  SELECT table_schema AS schemaname, table_name AS relname,
         (xpath('/row/n/text()', query_to_xml(format('SELECT count(*) AS n FROM %I.%I', table_schema, table_name), false, true, '')))[1]::text::bigint AS n
    FROM information_schema.tables
   WHERE table_schema LIKE 'etrm\_%' AND table_type = 'BASE TABLE'
     AND table_name NOT LIKE '%\_p______' AND table_name NOT LIKE '%\_pdefault'
     AND table_name NOT IN ('flyway_history', 'shedlock', 'event_publication')) t ORDER BY 1"

{
  echo "# Disaster recovery drill $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
} > "$REPORT"

log "1/5 backup of the source"
pg_dump --format=custom --no-owner --no-privileges --file="$WORK_DIR/etrm.dump" "$SOURCE_DB_URL"
sha256sum "$WORK_DIR/etrm.dump" | tee "$WORK_DIR/etrm.dump.sha256"
psql -X -At -F' ' "$SOURCE_DB_URL" -c "$COUNT_SQL" > "$WORK_DIR/source-counts.txt"
backup_done=$(date +%s)

log "2/5 restore into an empty database"
target_db=${TARGET_DB_URL##*/}
target_db=${target_db%%\?*}
admin_url=${TARGET_DB_URL%/*}/postgres
psql -X -q "$admin_url" -c "DROP DATABASE IF EXISTS \"$target_db\" WITH (FORCE)" -c "CREATE DATABASE \"$target_db\""
pg_restore --no-owner --no-privileges --exit-on-error --dbname="$TARGET_DB_URL" "$WORK_DIR/etrm.dump"
psql -X -At -F' ' "$TARGET_DB_URL" -c "$COUNT_SQL" > "$WORK_DIR/target-counts.txt"
diff -u "$WORK_DIR/source-counts.txt" "$WORK_DIR/target-counts.txt" > "$WORK_DIR/counts.diff" || fail "row counts differ (counts.diff)"
restore_done=$(date +%s)

log "3/5 start the application on the restored database"
jdbc_url="jdbc:postgresql://${TARGET_DB_URL#*@}"
db_user=${TARGET_DB_URL#*://}; db_user=${db_user%%:*}
db_password=${TARGET_DB_URL#*://*:}; db_password=${db_password%%@*}
ETRM_DB_URL="$jdbc_url" ETRM_DB_USER="$db_user" ETRM_DB_PASSWORD="$db_password" ETRM_KAFKA_ENABLED=false \
  "$JAVA" -jar "$APP_JAR" --spring.profiles.active=dev --server.port="$APP_PORT" \
  --logging.level.com.pianonurmi.etrm=INFO --logging.level.org.jooq=INFO > "$WORK_DIR/app.log" 2>&1 &
app_pid=$!
trap 'kill $app_pid 2>/dev/null || true' EXIT
for _ in $(seq 1 120); do
  curl -fs "$BASE/actuator/health/readiness" > /dev/null && break
  kill -0 $app_pid 2>/dev/null || fail "application exited (app.log)"
  sleep 2
done
curl -fs "$BASE/actuator/health/readiness" > /dev/null || fail "application not ready (app.log)"
# Flyway validated every module's migration history against the jar at start-up; nothing may have been pending.
grep -q "Migrating schema" "$WORK_DIR/app.log" && fail "the restored copy needed migrations: backup and jar do not match"
started=$(date +%s)

log "4/5 verify the hash chains and the documents"
lots=$(curl -fs "${HEADERS[@]}" "$BASE/api/v1/inventory/journal/verify")
echo "$lots" | grep -Eq '"ok" *: *true' || fail "lot journal chain broken: $lots"
entities=$(curl -fs "${HEADERS[@]}" "$BASE/api/v1/reference/legal-entities" | python3 -c 'import json, sys; print("\n".join(e["id"] for e in json.load(sys.stdin)))')
for entity in $entities; do
  ledger=$(curl -fs "${HEADERS[@]}" "$BASE/api/v1/ledger/journal/verify?legalEntityId=$entity")
  echo "$ledger" | grep -Eq '"ok" *: *true' || fail "ledger journal chain broken for $entity: $ledger"
done
documents=$(psql -X -At "$TARGET_DB_URL" -c "SELECT id FROM etrm_document.document ORDER BY created_at DESC LIMIT 20" 2>/dev/null || true)
for document in $documents; do
  curl -fs "${HEADERS[@]}" "$BASE/api/v1/documents/$document/verify" | grep -Eq '"intact" *: *true' || fail "document $document does not match its hash"
done

log "5/5 smoke reads"
for path in "/api/v1/trades/search?limit=5" "/api/v1/workflow/tasks?limit=5" "/api/v1/audit/events?limit=5"; do
  curl -fs "${HEADERS[@]}" "$BASE$path" > /dev/null || fail "GET $path"
done
done_at=$(date +%s)

{
  echo "| Step | Seconds |"
  echo "|---|---|"
  echo "| Backup | $((backup_done - start)) |"
  echo "| Restore and row counts | $((restore_done - backup_done)) |"
  echo "| Application ready | $((started - restore_done)) |"
  echo "| Verification | $((done_at - started)) |"
  echo "| **Total (restore to verified)** | **$((done_at - backup_done))** |"
  echo
  echo "- Backup: $(cut -d' ' -f1 "$WORK_DIR/etrm.dump.sha256") ($(du -h "$WORK_DIR/etrm.dump" | cut -f1))"
  echo "- Tables compared: $(wc -l < "$WORK_DIR/source-counts.txt"), rows: $(awk '{s+=$2} END {print s}' "$WORK_DIR/source-counts.txt")"
  echo "- Lot journal and ledger chains (entities: $(echo "$entities" | wc -w)) verified; documents checked: $(echo "$documents" | wc -w)"
  echo "- **Result: PASSED**"
} >> "$REPORT"
log "drill passed in $((done_at - start)) s; report in $REPORT"
