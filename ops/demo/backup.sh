#!/usr/bin/env bash
# Daily dump of the showcase database, keeping seven. Run from cron at a time clear of the nightly reset (04:15
# Helsinki), e.g. "30 2 * * * /opt/provena/ops/demo/backup.sh". The demo rebuilds itself every night, so a dump
# is for looking back at what visitors did, not for restoring service.
set -euo pipefail
cd "$(dirname "$0")"
target="${BACKUP_DIR:-/var/backups/provena}"
mkdir -p "$target"
file="$target/etrm_showcase-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
docker compose exec -T postgres pg_dump -U etrm -d etrm_showcase --no-owner | gzip > "$file"
ls -1t "$target"/etrm_showcase-*.sql.gz | tail -n +8 | xargs -r rm --
echo "Backup written: $file"
