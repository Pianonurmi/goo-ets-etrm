#!/usr/bin/env bash
# Prints the CHANGELOG.md section of a version, without its heading: the body of the GitHub release.
# Usage: ops/release/release-notes.sh 0.9.0
set -euo pipefail
cd "$(dirname "$0")/../.."
want="${1:?version}"
awk -v v="$want" '
  /^## / { if (on) exit; on = index($0, "## [" v "]") == 1; next }
  on && /^\[[^]]+\]: / { exit }
  on { print }
' CHANGELOG.md | sed -e '/./,$!d'
