#!/usr/bin/env bash
# Roller production backup -- single shot: pg_dump the database, tar the
# mediafiles/search-index/uploads volumes, then rotate anything older than
# BACKUP_RETENTION_DAYS. Both artifacts are written atomically: pg_dump/tar
# target "<final-name>.tmp" and a same-filesystem `mv` publishes it under
# its final name only once the write is complete, so a backup killed
# mid-write (OOM, container restart, disk full) leaves an orphaned .tmp
# file rather than a truncated file sitting under the name a restore would
# trust. Runs inside the `backup` service of
# docker-compose.prod.yml (the postgres:16 image, so pg_dump/psql/tar are
# already present); deploy/backup/loop.sh calls this once a day, or an
# operator can run one cycle by hand:
#
#   docker compose -f docker-compose.prod.yml exec backup /backup.sh
#
# RESTORE
# =======
#
# Database (drops and recreates objects in place -- take the app down first
# with `docker compose -f docker-compose.prod.yml stop app`):
#
#   docker compose -f docker-compose.prod.yml exec -T \
#     -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
#     pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists \
#     < /path/on/host/to/rollerdb-<timestamp>.dump
#
#   (The dump lives in the roller-backups volume, at
#   /backups/rollerdb-<timestamp>.dump inside the backup container --
#   `docker compose cp backup:/backups/rollerdb-<timestamp>.dump .` to get it
#   onto the host first if restoring from a different machine.)
#
# Media/search-index/uploads volumes (stack must be down -- the archive
# extracts to /data/... paths, matching the volume mount points):
#
#   docker compose -f docker-compose.prod.yml down
#   docker run --rm \
#     -v roller-mediafiles:/data/mediafiles \
#     -v roller-search-index:/data/search-index \
#     -v roller-uploads:/data/uploads \
#     -v roller-backups:/backups \
#     postgres:16@sha256:33f923b05f64ca54ac4401c01126a6b92afe839a0aa0a52bc5aeb5cc958e5f20 \
#     tar xzf /backups/volumes-<timestamp>.tar.gz -C /
#   docker compose -f docker-compose.prod.yml up -d
#
#   (Volume names above are Compose's default project-prefixed names, e.g.
#   roller_roller-mediafiles -- check `docker volume ls` for the exact names
#   on your host.)
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/backups}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

export PGHOST="${PGHOST:-postgres}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${POSTGRES_USER:?POSTGRES_USER must be set}"
export PGPASSWORD="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set}"
DB_NAME="${POSTGRES_DB:?POSTGRES_DB must be set}"

mkdir -p "${BACKUP_DIR}"

echo "[$(date -u +%FT%TZ)] Starting backup..."

DUMP_FILE="${BACKUP_DIR}/rollerdb-${TIMESTAMP}.dump"
pg_dump -Fc -d "${DB_NAME}" -f "${DUMP_FILE}.tmp"
mv "${DUMP_FILE}.tmp" "${DUMP_FILE}"
echo "  database -> ${DUMP_FILE}"

VOLUMES_FILE="${BACKUP_DIR}/volumes-${TIMESTAMP}.tar.gz"
tar czf "${VOLUMES_FILE}.tmp" -C / data/mediafiles data/search-index data/uploads
mv "${VOLUMES_FILE}.tmp" "${VOLUMES_FILE}"
echo "  volumes  -> ${VOLUMES_FILE}"

echo "Rotating backups older than ${RETENTION_DAYS} day(s)..."
find "${BACKUP_DIR}" -maxdepth 1 -type f \
    \( -name 'rollerdb-*.dump' -o -name 'volumes-*.tar.gz' -o -name '*.tmp' \) \
    -mtime "+${RETENTION_DAYS}" -print -delete

echo "[$(date -u +%FT%TZ)] Backup complete."
