#!/usr/bin/env bash
# Checks that a release version is the same everywhere it is written down (docs/operations/releases.md).
# Usage: ops/release/check-version.sh 0.9.0     (CI passes the tag without its leading "v")
set -euo pipefail
cd "$(dirname "$0")/../.."
want="${1:?usage: check-version.sh X.Y.Z}"
fail=0
check() { # name, value
  if [[ "$2" != "$want" ]]; then echo "::error::$1 is '$2', the tag says '$want'"; fail=1; else echo "$1: $2"; fi
}
[[ "$want" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || { echo "::error::'$want' is not a semantic version"; exit 1; }
check "gradle.properties version" "$(sed -n 's/^version=//p' gradle.properties)"
check "apps/web/package.json version" "$(sed -n 's/^  "version": "\(.*\)",$/\1/p' apps/web/package.json | head -1)"
check "Helm chart version" "$(sed -n 's/^version: //p' ops/helm/etrm/Chart.yaml)"
check "Helm chart appVersion" "$(sed -n 's/^appVersion: "\(.*\)"$/\1/p' ops/helm/etrm/Chart.yaml)"
if grep -q "^## \[$want\] - [0-9]\{4\}-[0-9]\{2\}-[0-9]\{2\}$" CHANGELOG.md; then echo "CHANGELOG.md: [$want] dated"; else
  echo "::error::CHANGELOG.md has no dated section '## [$want] - YYYY-MM-DD'"; fail=1; fi
exit "$fail"
