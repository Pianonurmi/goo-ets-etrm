#!/usr/bin/env bash
# Refuses a change that adds detekt baseline entries (config/detekt/type-resolution.yml): a baseline holds findings
# that predate a rule, and only shrinks. Compares every module's detekt-baseline*.xml with the given base revision.
#
#   ops/ci/detekt-baseline-check.sh origin/main
set -euo pipefail

base="${1:?usage: $0 <base revision>}"
cd "$(git rev-parse --show-toplevel)"

ids() { grep -ho '<ID>[^<]*</ID>' "$@" 2>/dev/null | sort -u || true; }

head_ids=$(git ls-files '*detekt-baseline*.xml' | { mapfile -t files; ((${#files[@]})) && ids "${files[@]}"; } || true)
base_files=$(git ls-tree -r --name-only "$base" | grep 'detekt-baseline[^/]*\.xml$' || true)
if [[ -z "$base_files" ]]; then
  echo "detekt baselines: none on $base yet, so this change introduces them"
  exit 0
fi
base_ids=$(while read -r f; do git show "$base:$f"; done <<<"$base_files" | grep -o '<ID>[^<]*</ID>' | sort -u || true)

added=$(comm -13 <(printf '%s\n' "$base_ids") <(printf '%s\n' "$head_ids") | sed '/^$/d')
if [[ -n "$added" ]]; then
  echo "::error::detekt baseline entries added since $base; fix the findings instead of baselining them:"
  printf '%s\n' "$added" | sed -e 's/<\/\?ID>//g' -e 's/^/  /'
  exit 1
fi
echo "detekt baselines: $(printf '%s\n' "$head_ids" | sed '/^$/d' | wc -l) entries, none added since $base"
