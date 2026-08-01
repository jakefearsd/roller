#!/bin/bash
# Roller database migration runner.
#
# Applies any pending V*.sql files from the migrations/ directory to a Roller
# PostgreSQL database. Tracks applied versions in the schema_migrations table
# so re-runs only execute new migrations.
#
# Usage:
#   ./migrate.sh [--status] [--help]
#
# Environment variables (with defaults):
#   DB_NAME      rollerdb    target database
#   DB_APP_USER  roller      role granted DML access in each migration
#   PGHOST       localhost
#   PGPORT       5432
#   PGUSER       roller      role that runs migrations; needs CREATE on
#                            schema public. Override with PGUSER=postgres
#                            when running against a fresh server.
#   PGPASSWORD               optional
#
# Each migration is executed inside a single transaction via psql's
# --single-transaction flag, so a failure rolls back any changes made by that
# migration. If every statement succeeds, migrate.sh records the version in
# schema_migrations.
#
# Roller also applies these same migrations at startup through the web install
# wizard (see DatabaseInstaller). Both paths read the same V*.sql files and the
# same schema_migrations table, so it does not matter which one runs first.
#
# deploy/deploy.sh (production deploys) runs this exact script too: it copies
# this file and migrations/ into the postgres container unmodified and execs
# it there, so a production deploy's migration step and a manual
# ./migrate.sh run can never disagree about what "applied" means.
set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
MIGRATIONS_DIR="${SCRIPT_DIR}/migrations"

DB_NAME="${DB_NAME:-rollerdb}"
DB_APP_USER="${DB_APP_USER:-roller}"
export PGHOST="${PGHOST:-localhost}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${PGUSER:-roller}"

RED="\033[0;31m"
GREEN="\033[0;32m"
YELLOW="\033[1;33m"
NC="\033[0m"

print_ok()   { echo -e "${GREEN}✓${NC} $*"; }
print_warn() { echo -e "${YELLOW}!${NC} $*"; }
print_err()  { echo -e "${RED}✗${NC} $*" >&2; }

psql_args=(
    --no-psqlrc
    --quiet
    --tuples-only
    --no-align
    -v ON_ERROR_STOP=1
    -v "app_user=${DB_APP_USER}"
    -d "${DB_NAME}"
)

usage() {
    awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
}

for arg in "$@"; do
    case "$arg" in
        -h|--help)
            usage; exit 0 ;;
        --status)
            echo "Applied migrations in ${DB_NAME}:"
            psql "${psql_args[@]}" -c \
                "SELECT version || '  ' || applied_at FROM schema_migrations ORDER BY version;" \
                || { print_err "Could not read schema_migrations (not initialised?)"; exit 1; }
            exit 0 ;;
        *)
            print_err "Unknown argument: ${arg}"; usage; exit 2 ;;
    esac
done

if [[ ! -d "${MIGRATIONS_DIR}" ]]; then
    print_err "Migrations directory not found: ${MIGRATIONS_DIR}"
    exit 1
fi

# Check we can reach the database before doing anything else.
if ! psql "${psql_args[@]}" -c 'SELECT 1;' >/dev/null 2>&1; then
    print_err "Could not connect to database ${DB_NAME} as ${PGUSER}@${PGHOST}:${PGPORT}"
    print_err "Set PGHOST/PGPORT/PGUSER/PGPASSWORD/DB_NAME env vars if defaults are wrong."
    exit 1
fi

# Bootstrap the tracker. V001 creates schema_migrations itself, but we cannot
# query the table before it exists, so we apply V001 unconditionally the first
# time (its CREATE TABLE IF NOT EXISTS makes this safe).
tracker_exists=$(psql "${psql_args[@]}" -c \
    "SELECT to_regclass('public.schema_migrations') IS NOT NULL;")
tracker_exists="${tracker_exists// /}"

if [[ "${tracker_exists}" != "t" ]]; then
    print_warn "schema_migrations table not found — bootstrapping from V001"
    psql "${psql_args[@]}" --single-transaction -f "${MIGRATIONS_DIR}/V001__schema_migrations.sql"
    psql "${psql_args[@]}" -c \
        "INSERT INTO schema_migrations (version) VALUES ('V001__schema_migrations') ON CONFLICT DO NOTHING;"
    print_ok "V001__schema_migrations applied"
fi

# Load applied versions into a bash set.
mapfile -t applied < <(psql "${psql_args[@]}" -c "SELECT version FROM schema_migrations ORDER BY version;")
declare -A applied_set=()
for v in "${applied[@]}"; do
    [[ -n "${v}" ]] && applied_set["${v}"]=1
done

pending=()
for path in "${MIGRATIONS_DIR}"/V*.sql; do
    [[ -e "${path}" ]] || continue
    version="$(basename "${path}" .sql)"
    if [[ -z "${applied_set[${version}]:-}" ]]; then
        pending+=("${path}")
    fi
done

if [[ ${#pending[@]} -eq 0 ]]; then
    print_ok "Database ${DB_NAME} is up to date (${#applied_set[@]} migrations applied)"
    exit 0
fi

echo "Applying ${#pending[@]} pending migration(s) to ${DB_NAME}:"
for path in "${pending[@]}"; do
    version="$(basename "${path}" .sql)"
    echo "  → ${version}"
    if psql "${psql_args[@]}" --single-transaction -f "${path}"; then
        psql "${psql_args[@]}" -c \
            "INSERT INTO schema_migrations (version) VALUES ('${version}') ON CONFLICT DO NOTHING;"
        print_ok "${version} applied"
    else
        print_err "${version} failed — aborting"
        exit 1
    fi
done

print_ok "All migrations applied"
