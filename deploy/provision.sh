#!/usr/bin/env bash
# One-shot database provisioning for the production stack.
#
# Runs as the `provision` service in docker-compose.prod.yml, from the app
# image, after postgres reports healthy and before app/umami/listmonk start
# (compose enforces both with depends_on conditions). Every step is
# idempotent, so it runs on every `docker compose up -d` and is a no-op once
# the stack is current.
#
# This replaces the equivalent steps that used to live in deploy/deploy.sh and
# needed a git checkout on the host to copy migrate.sh and the migrations into
# the postgres container. It also fixes a real bug in that version: it expanded
# ${UMAMI_DB:-umami} in the HOST shell, which never sourced .env -- so renaming
# either service database in .env made the script provision the default names
# while the containers pointed at the renamed ones. Here the names arrive as
# container environment, which compose populates from .env correctly.
#
# The one behavioural difference from the old version: this connects to
# postgres over the compose network rather than running inside the postgres
# container itself.
set -euo pipefail

export PGHOST="${PGHOST:-postgres}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${POSTGRES_USER:?POSTGRES_USER must be set}"
export PGPASSWORD="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set}"

ROLLER_DB="${POSTGRES_DB:-rollerdb}"
UMAMI_DB="${UMAMI_DB:-umami}"
LISTMONK_DB="${LISTMONK_DB:-listmonk}"

echo "==> Ensuring the service databases exist..."
# The analytics and newsletter services create their own tables but not their
# own databases, and postgres only runs initdb scripts on a first-ever start --
# so on an already-deployed stack there is nobody to create them. `createdb` on
# an existing database is an error, so ask first.
for db in "${UMAMI_DB}" "${LISTMONK_DB}"; do
    exists="$(psql -d "${ROLLER_DB}" -tAc "SELECT 1 FROM pg_database WHERE datname = ${db@Q}")"
    if [[ -z "${exists}" ]]; then
        createdb "${db}"
        echo "    created ${db}."
    else
        echo "    ${db} already exists."
    fi
done

echo "==> Applying ${ROLLER_DB} migrations..."
# migrate.sh unmodified, so a deploy's migration step and a manual run can
# never disagree about what "applied" means. It resolves its migrations
# directory relative to its own location, which is why the Dockerfile places it
# at /app/migrate.sh beside /app/migrations.
DB_NAME="${ROLLER_DB}" DB_APP_USER="${PGUSER}" bash /app/migrate.sh

echo "==> Granting grafana_ro CONNECT on both databases..."
# Issued here rather than inside a migration or umami-views.sql because neither
# can portably learn its own database's name (current_database() needs dynamic
# SQL to use inside a GRANT), but the real names are known here. Double quotes,
# not ${db@Q}: a database name after GRANT ... ON DATABASE is an SQL
# IDENTIFIER, which takes double quotes, and @Q emits a shell-style literal.
psql -d "${ROLLER_DB}" -v ON_ERROR_STOP=1 -c \
    "GRANT CONNECT ON DATABASE \"${ROLLER_DB}\" TO grafana_ro;"
psql -d "${ROLLER_DB}" -v ON_ERROR_STOP=1 -c \
    "GRANT CONNECT ON DATABASE \"${UMAMI_DB}\" TO grafana_ro;"

echo "==> Applying analytics views to ${UMAMI_DB}..."
# analytics_traffic cannot live in the rollerdb migration chain: PostgreSQL has
# no cross-database queries. CREATE OR REPLACE + GRANT are idempotent.
psql -d "${UMAMI_DB}" --single-transaction -v ON_ERROR_STOP=1 -f /app/umami-views.sql

echo "==> Provisioning complete."
