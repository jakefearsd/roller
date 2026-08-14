#!/usr/bin/env bash
# Install the Grafana analytics contract's traffic view into Umami's database.
#
# This is a SEPARATE one-shot from provision.sh, and the split is the whole
# point. analytics_traffic is defined over `website_event` -- a table Umami
# creates on its own first boot -- but provision.sh runs BEFORE umami is
# allowed to start, because app/umami/listmonk all gate on provision exiting
# successfully. Applying the view from provision.sh therefore deadlocks a fresh
# install: provision waits for a table only umami can create, and umami waits
# for provision to exit. The plan's full-stack verification is what surfaced
# this; the pre-wave deploy.sh had the same ordering and would have failed the
# same way.
#
# So this runs AFTER umami has started, waits for the table to appear, and
# GATES NOTHING -- no service declares depends_on against it. If it fails or
# times out, the stack is still up and serving; only the Grafana traffic view
# is missing, which is an operator dashboard concern, not a blog outage.
set -euo pipefail

export PGHOST="${PGHOST:-postgres}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${POSTGRES_USER:?POSTGRES_USER must be set}"
export PGPASSWORD="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set}"

UMAMI_DB="${UMAMI_DB:-umami}"
WAIT_SECONDS="${ANALYTICS_VIEWS_WAIT_SECONDS:-180}"

echo "==> Waiting up to ${WAIT_SECONDS}s for Umami to create website_event in ${UMAMI_DB}..."
# to_regclass returns NULL rather than raising when the table is absent, so
# this probe never has to distinguish "missing" from "error"; an unreachable
# database simply produces no output and the loop retries.
deadline=$(( SECONDS + WAIT_SECONDS ))
until psql -d "${UMAMI_DB}" --no-psqlrc --quiet -tAc \
        "SELECT to_regclass('public.website_event') IS NOT NULL" 2>/dev/null | grep -qx t; do
    if (( SECONDS >= deadline )); then
        echo "website_event did not appear within ${WAIT_SECONDS}s; analytics views NOT installed." >&2
        echo "The rest of the stack is unaffected. Re-run: docker compose -f docker-compose.prod.yml up -d analytics-views" >&2
        exit 1
    fi
    sleep 3
done
echo "    website_event present."

echo "==> Applying analytics views to ${UMAMI_DB}..."
psql -d "${UMAMI_DB}" --single-transaction -v ON_ERROR_STOP=1 -f /app/umami-views.sql
echo "==> Analytics views installed."
