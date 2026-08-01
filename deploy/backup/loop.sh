#!/usr/bin/env bash
# Nightly backup loop for the `backup` service in docker-compose.prod.yml.
# No cron daemon in the postgres:16 base image, so this is the whole
# scheduler: wake up once an hour, and if it's BACKUP_HOUR (UTC) and backup.sh
# hasn't already run today, run it. Equivalent to a `0 <BACKUP_HOUR> * * *`
# crontab entry, without needing cron installed.
set -euo pipefail

BACKUP_HOUR="${BACKUP_HOUR:-3}"
last_run_day=""

echo "[$(date -u +%FT%TZ)] Backup loop started; running daily around ${BACKUP_HOUR}:00 UTC."

while true; do
    # Force base-10 on BOTH operands: date can print hours with a leading
    # zero ("08", "09"), which bash's arithmetic/[[ -eq ]] context would
    # otherwise misread as octal -- and an operator is just as likely to
    # write a zero-padded BACKUP_HOUR (e.g. "08") in .env, which would
    # crash this same comparison ("value too great for base") if left
    # unconverted.
    current_hour=$((10#$(date -u +%H)))
    backup_hour=$((10#${BACKUP_HOUR}))
    current_day="$(date -u +%Y-%m-%d)"

    if [[ "${current_hour}" -eq "${backup_hour}" && "${current_day}" != "${last_run_day}" ]]; then
        if /backup.sh; then
            last_run_day="${current_day}"
        else
            echo "[$(date -u +%FT%TZ)] Backup failed; will retry at ${BACKUP_HOUR}:00 UTC tomorrow." >&2
        fi
    fi

    sleep 3600
done
