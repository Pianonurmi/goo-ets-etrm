#!/usr/bin/env bash
# Prepares an Ubuntu 24.04 machine with Docker Engine for this repository's self-hosted GitHub Actions runner
# (docs/operations/ci-runner.md). Run once as root; safe to run again, e.g. after a Playwright upgrade:
#   sudo ./provision.sh
# Installs what the workflows cannot install without sudo, creates the runner's user and a weekly Docker cleanup.
set -euo pipefail

# The version in apps/web/package-lock.json; its Chromium needs these system libraries.
PLAYWRIGHT_VERSION="${PLAYWRIGHT_VERSION:-1.64.0}"
NODE_MAJOR=22
RUNNER_USER="${RUNNER_USER:-runner}"
here="$(cd "$(dirname "$0")" && pwd)"

if [[ $EUID -ne 0 ]]; then
  echo "Run as root: sudo $0" >&2
  exit 1
fi
if ! command -v docker >/dev/null || ! docker info >/dev/null 2>&1; then
  echo "Docker Engine is not installed or not running: https://docs.docker.com/engine/install/ubuntu/" >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q ca-certificates curl git jq unzip xz-utils lsb-release

# PostgreSQL 17 client from the PGDG repository: psql in the workflows, pg_dump and pg_restore in the DR drill.
install -d /usr/share/postgresql-common/pgdg
curl -fsSL -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc https://www.postgresql.org/media/keys/ACCC4CF8.asc
echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" \
  > /etc/apt/sources.list.d/pgdg.list
apt-get update -q
apt-get install -y -q postgresql-client-17

# Node for one thing only: installing Chromium's system libraries. The jobs bring their own Node (setup-node).
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
base="https://nodejs.org/dist/latest-v${NODE_MAJOR}.x"
curl -fsSL -o "$tmp/SHASUMS256.txt" "$base/SHASUMS256.txt"
node_tar="$(grep -o "node-v[0-9.]*-linux-x64\.tar\.xz" "$tmp/SHASUMS256.txt" | head -1)"
curl -fsSL -o "$tmp/$node_tar" "$base/$node_tar"
(cd "$tmp" && grep " $node_tar\$" SHASUMS256.txt | sha256sum -c -)
tar xJf "$tmp/$node_tar" -C "$tmp"
PATH="$tmp/${node_tar%.tar.xz}/bin:$PATH" npx -y "playwright@$PLAYWRIGHT_VERSION" install-deps chromium

# The runner's own user: no sudo, but in the docker group (service containers, Testcontainers, image builds).
if ! id "$RUNNER_USER" >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash "$RUNNER_USER"
fi
usermod -aG docker "$RUNNER_USER"

# Images, containers and build cache of past jobs, removed weekly so the disk does not fill up.
install -m 0644 "$here/docker-prune.service" "$here/docker-prune.timer" /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now docker-prune.timer

echo "Provisioned. Next: sudo $here/install-runner.sh <repository URL> <registration token>"
