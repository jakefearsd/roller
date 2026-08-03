#!/usr/bin/env bash
# One-command deploy/upgrade for the production stack (docker-compose.prod.yml).
#
# Steps: pull (or build) the app image, bring postgres up and wait for it to
# be healthy, apply any pending schema migrations directly against postgres,
# THEN start the app -- in that order, deliberately. installation.type=auto
# (deploy/config/roller-production.properties) makes the app check
# WebloggerFactory.isBootstrapped() once at startup, not on every request, so
# an app container already running when migrations land would need a manual
# restart to notice the fresh schema. Migrating first and then `up -d app`
# (never `restart`) means a freshly-started app always sees a current schema
# and this is a non-issue.
#
# Migrations: this deploy host already has a full checkout (docker-compose.
# prod.yml itself bind-mounts deploy/config and deploy/caddy from here, so
# that's an existing assumption, not a new one), and the app image is always
# built or pulled from the same git ref as that checkout. So this script
# copies bin/db/migrate.sh and bin/db/migrations into the postgres container
# -- unmodified, at the same relative layout migrate.sh expects -- and runs
# it there via `docker compose exec`, exactly as if you'd run
# `./bin/db/migrate.sh` against this database yourself. No tracking logic is
# reimplemented here: schema_migrations bookkeeping, the V001 bootstrap
# special-case, and the single-transaction-per-file behavior all come from
# migrate.sh itself, so this script and a manual migrate.sh run can never
# disagree about what "applied" means. Re-running this script (or migrate.sh)
# against an up-to-date database is a no-op by design (see migrate.sh).
#
# Usage:
#   deploy/deploy.sh [--build] [--prune]
#
#   --build   build the app image from this tree instead of pulling
#             ROLLER_IMAGE (same as `docker compose ... build app`)
#   --prune   run `docker image prune -f` after a successful deploy
#
# Run from anywhere; always operates on the repo root's docker-compose.prod.yml
# and .env. Requires .env and deploy/config/roller-production.properties to
# already be populated -- see docker-compose.prod.yml's header / docker_deployment.md.
set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
REPO_ROOT="$( cd "${SCRIPT_DIR}/.." && pwd )"
COMPOSE=(docker compose -f "${REPO_ROOT}/docker-compose.prod.yml")

BUILD=0
PRUNE=0
for arg in "$@"; do
    case "$arg" in
        --build) BUILD=1 ;;
        --prune) PRUNE=1 ;;
        -h|--help)
            awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
            exit 0 ;;
        *)
            echo "Unknown argument: ${arg}" >&2
            exit 2 ;;
    esac
done

cd "${REPO_ROOT}"

if [[ "${BUILD}" -eq 1 ]]; then
    echo "==> Building app image from this tree..."
    "${COMPOSE[@]}" build app
else
    echo "==> Pulling latest images..."
    "${COMPOSE[@]}" pull app postgres caddy umami listmonk
fi

echo "==> Starting postgres..."
"${COMPOSE[@]}" up -d postgres

echo "==> Waiting for postgres to be healthy..."
postgres_cid="$("${COMPOSE[@]}" ps -q postgres)"
healthy=0
for _ in $(seq 1 60); do
    status="$(docker inspect --format '{{.State.Health.Status}}' "${postgres_cid}" 2>/dev/null || true)"
    if [[ "${status}" == "healthy" ]]; then
        healthy=1
        break
    fi
    sleep 2
done
if [[ "${healthy}" -ne 1 ]]; then
    echo "postgres did not become healthy in time" >&2
    exit 1
fi
echo "    postgres healthy."

# The analytics and newsletter services keep their own databases inside the
# same postgres instance. Each creates its own tables, but not the database
# itself, and postgres only runs its initdb scripts on a first-ever start -- so
# on an already-deployed stack there is nobody to create them. Do it here, and
# keep it idempotent: `createdb` on an existing database is an error, so ask
# first.
echo "==> Ensuring the service databases exist..."
"${COMPOSE[@]}" exec -T \
    -e SERVICE_DBS="${UMAMI_DB:-umami} ${LISTMONK_DB:-listmonk}" \
    postgres bash -c '
    set -euo pipefail
    export PGHOST=localhost
    export PGUSER="${POSTGRES_USER}"
    export PGPASSWORD="${POSTGRES_PASSWORD}"
    for db in ${SERVICE_DBS}; do
        exists="$(psql -d "${POSTGRES_DB}" -tAc \
            "SELECT 1 FROM pg_database WHERE datname = ${db@Q}")"
        if [[ -z "${exists}" ]]; then
            createdb "${db}"
            echo "    created ${db}."
        else
            echo "    ${db} already exists."
        fi
    done
'

echo "==> Applying database migrations..."

# `docker compose cp` (like `docker cp`) nests the source INSIDE an existing
# destination directory rather than replacing it. If a previous run failed
# mid-migration (or was interrupted) and left /tmp/migrate.sh + /tmp/migrations
# staged in the postgres container, copying fresh files on top would nest the
# new migrations/ under the stale one, and migrate.sh would keep reading the
# OLD (possibly broken) SQL forever -- the operator's fix would never take
# effect and a retry would be unrecoverable without manual cleanup. Guard
# against that two ways: (1) clear any staging idempotently before copying
# fresh files in, and (2) register the same cleanup as an EXIT trap so it
# also runs after this run, on failure, or on interruption (Ctrl-C).
cleanup_migration_staging() {
    "${COMPOSE[@]}" exec -T postgres rm -rf /tmp/migrate.sh /tmp/migrations >/dev/null 2>&1 || true
}
trap cleanup_migration_staging EXIT
cleanup_migration_staging

"${COMPOSE[@]}" cp bin/db/migrate.sh postgres:/tmp/migrate.sh
"${COMPOSE[@]}" cp bin/db/migrations postgres:/tmp/migrations
"${COMPOSE[@]}" exec -T postgres bash -c '
    set -euo pipefail
    export PGHOST=localhost
    export PGUSER="${POSTGRES_USER}"
    export PGPASSWORD="${POSTGRES_PASSWORD}"
    export DB_NAME="${POSTGRES_DB}"
    bash /tmp/migrate.sh
'
cleanup_migration_staging

echo "==> Starting app..."
"${COMPOSE[@]}" up -d app

echo "==> Waiting for app to report healthy (up to 120s)..."
healthy=0
for _ in $(seq 1 60); do
    if "${COMPOSE[@]}" exec -T app curl -sf http://localhost:8090/actuator/health >/dev/null 2>&1; then
        healthy=1
        break
    fi
    sleep 2
done
if [[ "${healthy}" -ne 1 ]]; then
    echo "app did not become healthy within 120s" >&2
    exit 1
fi
echo "    app healthy."

echo "==> Reconciling remaining services (caddy, umami, listmonk, backup)..."
"${COMPOSE[@]}" up -d

if [[ "${PRUNE}" -eq 1 ]]; then
    echo "==> Pruning dangling images..."
    docker image prune -f
fi

echo "==> Deploy complete."
