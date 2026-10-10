#!/usr/bin/env bash
#
# Roller admin accounts, for whoever controls the host:
#
#   C="docker compose -f docker-compose.prod.yml"
#   $C run --rm admin create --username NAME --email ADDRESS
#   $C run --rm admin reset-password --username NAME
#   $C run --rm admin status
#
# create and reset-password prompt for the password twice. With --password-stdin it reads
# exactly one line from stdin instead (use `run --rm -T`).
#
# Trust model: running this at all means controlling the host and its
# .env, which already holds every database credential. Nothing reachable
# over HTTP can do what this does. See
# docs/superpowers/specs/2026-10-10-admin-bootstrap-and-recovery-design.md.
#
# The password is never an argument and is never printed. It reaches psql
# only through psql's environment (\getenv), and psql quotes it itself.
# Every psql call reads its SQL from a heredoc: -c would not substitute -v
# variables, and the heredoc stops psql eating this script's stdin, where
# the password is.
set -euo pipefail

export PGHOST="${PGHOST:-postgres}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${POSTGRES_USER:?POSTGRES_USER must be set}"
export PGPASSWORD="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set}"
export PGDATABASE="${POSTGRES_DB:-rollerdb}"

MIN_PASSWORD_LENGTH=8
USERNAME_RE='^[A-Za-z0-9]+$'
EMAIL_RE='^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'

usage() {
    cat >&2 <<'EOF'
usage: admin-account.sh create --username NAME --email ADDRESS [--password-stdin]
       admin-account.sh reset-password --username NAME [--password-stdin]
       admin-account.sh status
EOF
    exit 2
}

die() {
    echo "admin-account: $*" >&2
    exit 1
}

# sql [psql -v args...] <<'SQL' ... -- unaligned, tuples only, stops on error
sql() {
    psql -X -q -v ON_ERROR_STOP=1 -tA "$@"
}

require_schema() {
    local present
    present=$(sql <<'SQL'
SELECT to_regclass('public.roller_user') IS NOT NULL;
SQL
) || die "cannot reach database ${PGDATABASE} on ${PGHOST}:${PGPORT}"
    [[ "${present}" == t ]] \
        || die "the Roller schema is not installed in ${PGDATABASE}: run deploy.sh first"
}

# Sets PASSWORD. IFS= keeps leading/trailing spaces; -r keeps backslashes.
read_password() {
    local confirm=""
    PASSWORD=""
    if [[ "${FROM_STDIN}" -eq 1 ]]; then
        IFS= read -r PASSWORD || true
        [[ -n "${PASSWORD}" ]] || die "no password on stdin"
    else
        IFS= read -r -s -p "Password: " PASSWORD || true
        if [[ -t 0 ]]; then echo >&2; fi
        IFS= read -r -s -p "Repeat the password: " confirm || true
        if [[ -t 0 ]]; then echo >&2; fi
        [[ "${PASSWORD}" == "${confirm}" ]] || die "the two passwords do not match"
    fi
    (( ${#PASSWORD} >= MIN_PASSWORD_LENGTH )) \
        || die "the password must be at least ${MIN_PASSWORD_LENGTH} characters"
}

cmd_create() {
    [[ "${USERNAME}" =~ ${USERNAME_RE} && ${#USERNAME} -le 255 ]] \
        || die "username must be 1-255 letters and digits"
    [[ "${EMAIL}" =~ ${EMAIL_RE} ]] || die "not an email address: ${EMAIL}"
    require_schema
    local taken
    taken=$(sql -v username="${USERNAME}" <<'SQL'
SELECT count(*) FROM roller_user WHERE lower(username) = lower(:'username');
SQL
)
    [[ "${taken}" == 0 ]] || die "an account named ${USERNAME} already exists (perhaps disabled)"
    read_password
    ROLLER_ADMIN_PASSWORD="${PASSWORD}" sql -v username="${USERNAME}" -v email="${EMAIL}" >/dev/null <<'SQL'
\getenv pw ROLLER_ADMIN_PASSWORD
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
INSERT INTO roller_user (id, username, passphrase, screenname, fullname,
                         emailaddress, datecreated, locale, timezone, isenabled)
VALUES (gen_random_uuid()::text, :'username',
        '{bcrypt}' || crypt(:'pw', gen_salt('bf', 10)),
        :'username', :'username', :'email', now(), 'en_US', 'UTC', true);
INSERT INTO userrole (id, rolename, username)
VALUES (gen_random_uuid()::text, 'admin', :'username'),
       (gen_random_uuid()::text, 'editor', :'username');
COMMIT;
SQL
    echo "created admin account ${USERNAME} (roles: admin, editor)"
}

cmd_reset() {
    require_schema
    local enabled
    enabled=$(sql -v username="${USERNAME}" <<'SQL'
SELECT isenabled FROM roller_user WHERE username = :'username';
SQL
)
    [[ -n "${enabled}" ]] || die "no account named ${USERNAME}"
    read_password
    ROLLER_ADMIN_PASSWORD="${PASSWORD}" sql -v username="${USERNAME}" >/dev/null <<'SQL'
\getenv pw ROLLER_ADMIN_PASSWORD
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
UPDATE roller_user SET passphrase = '{bcrypt}' || crypt(:'pw', gen_salt('bf', 10))
 WHERE username = :'username';
COMMIT;
SQL
    if [[ "${enabled}" != t ]]; then
        echo "admin-account: warning: ${USERNAME} is disabled and still cannot log in;" \
             "enable it from another admin account, or create a new admin" >&2
    fi
    echo "password reset for ${USERNAME}."
    echo "The running app caches accounts it has loaded; restart it so the new password takes effect:"
    echo "  docker compose -f docker-compose.prod.yml restart app"
}

cmd_status() {
    require_schema
    local admins
    admins=$(sql <<'SQL'
SELECT u.username FROM roller_user u
 WHERE u.isenabled
   AND EXISTS (SELECT 1 FROM userrole r
                WHERE r.username = u.username AND r.rolename = 'admin')
 ORDER BY u.username;
SQL
)
    if [[ -z "${admins}" ]]; then
        echo "no enabled admin account. Create one with:"
        echo "  docker compose -f docker-compose.prod.yml run --rm admin create --username NAME --email ADDRESS"
        exit 3
    fi
    echo "enabled admin accounts:"
    sed 's/^/  /' <<<"${admins}"
}

COMMAND="${1:-}"
[[ -n "${COMMAND}" ]] || usage
shift
USERNAME="" EMAIL="" FROM_STDIN=0
while (( $# > 0 )); do
    case "$1" in
        --username) (( $# >= 2 )) || usage; USERNAME="$2"; shift 2 ;;
        --email)    (( $# >= 2 )) || usage; EMAIL="$2"; shift 2 ;;
        --password-stdin) FROM_STDIN=1; shift ;;
        *) usage ;;
    esac
done

case "${COMMAND}" in
    create)
        [[ -n "${USERNAME}" && -n "${EMAIL}" ]] || usage
        cmd_create ;;
    reset-password)
        [[ -n "${USERNAME}" && -z "${EMAIL}" ]] || usage
        cmd_reset ;;
    status)
        [[ -z "${USERNAME}${EMAIL}" && "${FROM_STDIN}" -eq 0 ]] || usage
        cmd_status ;;
    *) usage ;;
esac
