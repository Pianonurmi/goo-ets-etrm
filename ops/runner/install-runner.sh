#!/usr/bin/env bash
# Registers this machine as the repository's self-hosted runner (labels self-hosted, linux, x64, etrm) and installs
# it as a systemd service (docs/operations/ci-runner.md). Run as root after provision.sh:
#   sudo ./install-runner.sh https://github.com/Pianonurmi/goo-ets-etrm <registration token>
# The token comes from Settings -> Actions -> Runners -> New self-hosted runner and is valid for an hour.
# RUNNER_VERSION=2.x.y pins a release; by default the latest. The runner updates itself afterwards.
set -euo pipefail

repo_url="${1:?usage: $0 <repository URL> <registration token>}"
token="${2:?usage: $0 <repository URL> <registration token>}"
RUNNER_USER="${RUNNER_USER:-runner}"
RUNNER_NAME="${RUNNER_NAME:-$(hostname)}"
api="https://api.github.com/repos/actions/runner/releases"

if [[ $EUID -ne 0 ]]; then
  echo "Run as root: sudo $0 ..." >&2
  exit 1
fi
home="$(getent passwd "$RUNNER_USER" | cut -d: -f6)"
if [[ -z $home ]]; then
  echo "No user $RUNNER_USER: run provision.sh first." >&2
  exit 1
fi
dir="$home/actions-runner"

# The release's Linux x64 archive and the SHA-256 digest GitHub records for it (RUNNER_SHA256 overrides it, e.g.
# from the release notes, should the API not report one).
if [[ -n ${RUNNER_VERSION:-} ]]; then path="tags/v$RUNNER_VERSION"; else path="latest"; fi
release="$(curl -fsSL "$api/$path")"
read -r url digest < <(jq -r '.assets[] | select(.name | test("^actions-runner-linux-x64-[0-9.]+\\.tar\\.gz$"))
  | "\(.browser_download_url) \(.digest // "")"' <<<"$release")
sha="${RUNNER_SHA256:-${digest#sha256:}}"
if [[ -z ${url:-} || ! $sha =~ ^[0-9a-f]{64}$ ]]; then
  echo "Could not find the runner archive or its SHA-256 in the release; set RUNNER_SHA256." >&2
  exit 1
fi

runuser -u "$RUNNER_USER" -- mkdir -p "$dir"
runuser -u "$RUNNER_USER" -- curl -fsSL -o "$dir/runner.tar.gz" "$url"
echo "$sha  $dir/runner.tar.gz" | sha256sum -c -
runuser -u "$RUNNER_USER" -- tar xzf "$dir/runner.tar.gz" -C "$dir"
rm -f "$dir/runner.tar.gz"

# Unattended; --replace takes over a runner of the same name (a reinstalled machine).
(cd "$dir" && runuser -u "$RUNNER_USER" -- ./config.sh --unattended --url "$repo_url" --token "$token" \
  --name "$RUNNER_NAME" --labels etrm --work _work --replace)
(cd "$dir" && ./svc.sh install "$RUNNER_USER" && ./svc.sh start && ./svc.sh status)
