#!/usr/bin/env bash
# Prints the image tags to publish for a git ref, one per line (docs/operations/releases.md).
#   refs/heads/main      -> main, <sha>
#   refs/tags/vX.Y.Z     -> X.Y.Z, X.Y, latest, <sha>   (and X from 1.0.0 on)
#   refs/tags/vX.Y.Z-rc1 -> X.Y.Z-rc1, <sha>            (a pre-release moves no floating tag)
# Usage: ops/release/image-tags.sh <ref> <sha>
set -euo pipefail
ref="${1:?ref}" sha="${2:?sha}"
case "$ref" in
  refs/heads/main) echo main ;;
  refs/tags/v*)
    version="${ref#refs/tags/v}"
    [[ "$version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)(-.+)?$ ]] || { echo "not a semantic version: $version" >&2; exit 1; }
    echo "$version"
    if [[ -z "${BASH_REMATCH[4]}" ]]; then
      echo "${BASH_REMATCH[1]}.${BASH_REMATCH[2]}"
      [[ "${BASH_REMATCH[1]}" != 0 ]] && echo "${BASH_REMATCH[1]}"
      echo latest
    fi ;;
  *) echo "no image for $ref" >&2; exit 1 ;;
esac
echo "$sha"
