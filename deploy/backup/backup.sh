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
# with `docker compose -f docker-compose.prod.yml stop app`).
#
# IMPORTANT: run this via `exec ... bash -c '...'` with the command
# SINGLE-QUOTED, exactly like below -- $POSTGRES_USER/$POSTGRES_PASSWORD/
# $POSTGRES_DB only exist inside the postgres container's environment (set
# by docker-compose.prod.yml). A double-quoted or unquoted command expands
# them in your HOST shell instead, where they're unset, and pg_restore/psql
# silently gets empty values (see deploy.sh's own migration step for the
# same pattern):
#
#   docker compose -f docker-compose.prod.yml exec -T postgres bash -c '
#     set -euo pipefail
#     export PGHOST=localhost
#     export PGUSER="${POSTGRES_USER}"
#     export PGPASSWORD="${POSTGRES_PASSWORD}"
#     pg_restore -d "${POSTGRES_DB}" --clean --if-exists
#   ' < /path/on/host/to/rollerdb-<timestamp>.dump
#
#   docker compose -f docker-compose.prod.yml start app
#
#   (The dump lives in the roller-backups volume, at
#   /backups/rollerdb-<timestamp>.dump inside the backup container --
#   `docker compose cp backup:/backups/rollerdb-<timestamp>.dump .` to get it
#   onto the host first if restoring from a different machine.)
#
# Media/search-index/uploads volumes (stack must be down -- the archive
# extracts to /data/... paths, matching the volume mount points).
#
# The volume names below are Compose's default PROJECT-PREFIXED names, not
# the bare names declared in docker-compose.prod.yml -- the project name is
# derived from the directory the compose file lives in (`roller` if you
# cloned to /opt/roller per docker_deployment.md), giving e.g.
# `roller_roller-mediafiles`. Run `docker volume ls` FIRST to confirm the
# exact names on your host and substitute them below if they differ:
#
#   docker compose -f docker-compose.prod.yml down
#   docker run --rm \
#     -v roller_roller-mediafiles:/data/mediafiles \
#     -v roller_roller-search-index:/data/search-index \
#     -v roller_roller-uploads:/data/uploads \
#     -v roller_roller-backups:/backups \
#     postgres:16@sha256:33f923b05f64ca54ac4401c01126a6b92afe839a0aa0a52bc5aeb5cc958e5f20 \
#     tar xzf /backups/volumes-<timestamp>.tar.gz -C /
#   docker compose -f docker-compose.prod.yml up -d
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

# The analytics database, if this stack runs one. Dumped separately rather
# than with pg_dumpall so each restores on its own: losing analytics history
# should never be a reason to hesitate over restoring the blog. Missing is not
# an error -- a stack deployed before analytics existed simply has no such
# database, and a backup run must not start failing because of it.
UMAMI_DB_NAME="${UMAMI_DB:-umami}"
if psql -d "${DB_NAME}" -tAc \
        "SELECT 1 FROM pg_database WHERE datname = ${UMAMI_DB_NAME@Q}" | grep -q 1; then
    UMAMI_DUMP_FILE="${BACKUP_DIR}/${UMAMI_DB_NAME}-${TIMESTAMP}.dump"
    pg_dump -Fc -d "${UMAMI_DB_NAME}" -f "${UMAMI_DUMP_FILE}.tmp"
    mv "${UMAMI_DUMP_FILE}.tmp" "${UMAMI_DUMP_FILE}"
    echo "  analytics -> ${UMAMI_DUMP_FILE}"
else
    echo "  analytics -> none (no ${UMAMI_DB_NAME} database on this stack)"
fi

VOLUMES_FILE="${BACKUP_DIR}/volumes-${TIMESTAMP}.tar.gz"
tar czf "${VOLUMES_FILE}.tmp" -C / data/mediafiles data/search-index data/uploads
mv "${VOLUMES_FILE}.tmp" "${VOLUMES_FILE}"
echo "  volumes  -> ${VOLUMES_FILE}"

echo "Rotating backups older than ${RETENTION_DAYS} day(s)..."
find "${BACKUP_DIR}" -maxdepth 1 -type f \
    \( -name 'rollerdb-*.dump' -o -name "${UMAMI_DB_NAME}-*.dump" \
       -o -name 'volumes-*.tar.gz' -o -name '*.tmp' \) \
    -mtime "+${RETENTION_DAYS}" -print -delete

echo "[$(date -u +%FT%TZ)] Backup complete."
