#!/usr/bin/env bash
# The previous release on this build's schema (ADR 0048, review finding D10). During a rolling upgrade the new
# version's migrations run first (the Helm migrations Job) while pods of the previous version keep serving, so
# every migration must leave a schema the previous release can read and write.
#
# Migrates an empty database with this build's jar (--migrate-only), then starts the latest published release on it
# with migrations off and lets its demo seeder write a full data set through the REST API.
#
# Usage: ops/release/compat-check.sh <this build's jar> <JDBC URL of an empty database>
# Environment: GH_TOKEN (to download the release), JAVA (a Java 25 binary, default java), PREVIOUS_JAR (skip the
# download), ETRM_DB_USER / ETRM_DB_PASSWORD (default etrm / etrm).
set -euo pipefail

jar=$(realpath "$1")
url=$2
java=${JAVA:-java}
port=${COMPAT_PORT:-18080}
work=$(mktemp -d)
log="$work/previous.log"
export ETRM_DB_USER=${ETRM_DB_USER:-etrm} ETRM_DB_PASSWORD=${ETRM_DB_PASSWORD:-etrm}

previous=${PREVIOUS_JAR:-}
if [[ -z "$previous" ]]; then
  tag=$(gh release list --exclude-drafts --exclude-pre-releases --limit 1 --json tagName --jq '.[0].tagName')
  echo "Previous release: $tag"
  gh release download "$tag" --pattern 'provena-*.jar' --dir "$work"
  previous=$(ls "$work"/provena-*.jar)
fi

echo "Migrating with this build"
SPRING_PROFILES_ACTIVE=dev,demo "$java" -jar "$jar" --migrate-only --spring.datasource.url="$url"

echo "Starting the previous release with migrations off"
SPRING_PROFILES_ACTIVE=dev,demo ETRM_MIGRATIONS_ENABLED=false \
  "$java" -jar "$previous" --server.port="$port" --spring.datasource.url="$url" > "$log" 2>&1 &
pid=$!
trap 'kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true' EXIT

state=""
for _ in $(seq 1 180); do
  if ! kill -0 "$pid" 2>/dev/null; then echo "The previous release stopped"; tail -100 "$log"; exit 1; fi
  state=$(curl -fsS "http://127.0.0.1:$port/api/v1/ui-config" 2>/dev/null | jq -r '.demoSeed.state // empty' || true)
  [[ "$state" == COMPLETED || "$state" == FAILED ]] && break
  sleep 5
done
if [[ "$state" != COMPLETED ]]; then
  echo "The previous release could not seed its demo data on the migrated schema (state: ${state:-none})"
  grep -E "ERROR|Exception|SQLState" "$log" | head -40 || tail -100 "$log"
  exit 1
fi
# Errors that only a schema the previous release does not know produces (listeners run after the seeder's calls).
incompatible="BadSqlGrammar|column .* does not exist|relation .* does not exist|violates not-null constraint"
if grep -qE "$incompatible" "$log"; then
  echo "The previous release hit schema errors on the migrated schema"
  grep -E -B2 -A8 "$incompatible" "$log" | head -60
  exit 1
fi
echo "The previous release reads and writes the migrated schema"
