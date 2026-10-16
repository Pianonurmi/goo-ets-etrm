#!/usr/bin/env bash
# The previous release on this build's schema (ADR 0048, review finding D10). During a rolling upgrade the new
# version's migrations run first (the Helm migrations Job) while pods of the previous version keep serving, so
# every migration must leave a schema the previous release can read and write.
#
# Migrates an empty database with this build's jar (--migrate-only), then starts the latest published release on it
# with migrations off and lets its demo seeder write a full data set through the REST API.
#
# The demo data follows the date it is seeded on, so a previous release's seeder can fail on a given day for reasons
# of its own (0.15.0 from 2026-10-15). When it fails on the migrated schema and a second empty database is given, the
# previous release seeds that one on its own migrations: failing at the same step there shows the schema is not the
# cause, and the check passes with a warning. Schema errors in the log fail the check in every case.
#
# Usage: ops/release/compat-check.sh <this build's showcase jar> <JDBC URL of an empty database> [<JDBC URL of a second
# empty database>]
# The showcase jar (api-showcase.jar): it carries the demo seeder's run-log migration, which the previous release's
# seeder writes to. The previous release's seeder comes from its showcase image; releases before 0.15.0 carried it in
# the release jar.
# Environment: GH_TOKEN (to find and download the release), JAVA (a Java 25 binary, default java), PREVIOUS_JAR (skip
# the download), SHOWCASE_IMAGE (default ghcr.io/pianonurmi/goo-ets-etrm-showcase), ETRM_DB_USER / ETRM_DB_PASSWORD
# (default etrm / etrm).
set -euo pipefail

jar=$(realpath "$1")
url=$2
baseline_url=${3:-}
java=${JAVA:-java}
port=${COMPAT_PORT:-18080}
work=$(mktemp -d)
export ETRM_DB_USER=${ETRM_DB_USER:-etrm} ETRM_DB_PASSWORD=${ETRM_DB_PASSWORD:-etrm}

previous=${PREVIOUS_JAR:-}
if [[ -z "$previous" ]]; then
  tag=$(gh release list --exclude-drafts --exclude-pre-releases --limit 1 --json tagName --jq '.[0].tagName')
  echo "Previous release: $tag"
  image="${SHOWCASE_IMAGE:-ghcr.io/pianonurmi/goo-ets-etrm-showcase}:${tag#v}"
  if docker pull -q "$image" > /dev/null 2>&1; then
    container=$(docker create "$image")
    docker cp "$container:/app/app.jar" "$work/previous.jar"
    docker rm "$container" > /dev/null
    previous="$work/previous.jar"
    echo "Seeder from $image"
  else
    gh release download "$tag" --pattern 'provena-*.jar' --dir "$work"
    previous=$(ls "$work"/provena-*.jar)
    echo "Seeder from the release jar (no showcase image $image)"
  fi
fi

pid=""
trap '[[ -n "$pid" ]] && { kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; }' EXIT

# Starts the previous release on a database and waits for its demo seed; prints COMPLETED, FAILED or the last state
# seen. The application's output goes to the log file given.
seed_previous() {
  local db=$1 log=$2 migrations=$3 state=""
  SPRING_PROFILES_ACTIVE=dev,demo ETRM_MIGRATIONS_ENABLED=$migrations \
    "$java" -jar "$previous" --server.port="$port" --spring.datasource.url="$db" > "$log" 2>&1 &
  pid=$!
  for _ in $(seq 1 180); do
    # A failed seed stops the application (the step is rethrown from the ready listener).
    if ! kill -0 "$pid" 2>/dev/null; then state=FAILED; break; fi
    state=$(curl -fsS "http://127.0.0.1:$port/api/v1/ui-config" 2>/dev/null | jq -r '.demoSeed.state // empty' || true)
    [[ "$state" == COMPLETED || "$state" == FAILED ]] && break
    sleep 5
  done
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  pid=""
  echo "${state:-none}"
}

failed_step() { grep -oE "step [0-9]+/[0-9]+ '[^']+' failed" "$1" | tail -1 || true; }

echo "Migrating with this build"
SPRING_PROFILES_ACTIVE=dev,demo "$java" -jar "$jar" --migrate-only --spring.datasource.url="$url"

echo "Starting the previous release with migrations off"
log="$work/previous.log"
state=$(seed_previous "$url" "$log" false)

# Errors that only a schema the previous release does not know produces (listeners run after the seeder's calls).
incompatible="BadSqlGrammar|column .* does not exist|relation .* does not exist|violates not-null constraint"
if grep -qE "$incompatible" "$log"; then
  echo "The previous release hit schema errors on the migrated schema"
  grep -E -B2 -A8 "$incompatible" "$log" | head -60
  exit 1
fi

if [[ "$state" == COMPLETED ]]; then
  echo "The previous release reads and writes the migrated schema"
  exit 0
fi

step=$(failed_step "$log")
echo "The previous release could not seed its demo data on the migrated schema (state: $state; ${step:-no failed step logged})"
if [[ -n "$baseline_url" && -n "$step" ]]; then
  echo "Seeding the previous release on its own migrations for comparison"
  baseline_log="$work/baseline.log"
  baseline_state=$(seed_previous "$baseline_url" "$baseline_log" true)
  baseline_step=$(failed_step "$baseline_log")
  if [[ "$baseline_state" != COMPLETED && "$baseline_step" == "$step" ]]; then
    echo "::warning::The previous release fails at the same step on its own schema ($step): its demo data, not this build's schema, is the cause"
    grep -E "ERROR" "$log" | head -5 || true
    echo "The previous release reads and writes the migrated schema up to that step"
    exit 0
  fi
  echo "On its own schema the previous release ended with state $baseline_state (${baseline_step:-no failed step logged})"
fi
grep -E "ERROR|Exception|SQLState" "$log" | head -40 || tail -100 "$log"
exit 1
