# Deploying Roller with Docker

A from-scratch runbook for taking Roller from a fresh VPS to a TLS-terminated,
backed-up, one-command-upgradeable deployment using `docker-compose.prod.yml`.
This describes the actual production stack shipped in this tree, not a
hypothetical one — every file it references exists in the repo.

If you're looking for the **local development** stack instead (Postgres
only, run the app via `./roller dev`), see the root `docker-compose.yml` and
`CLAUDE.md`. This document is for a real deployment on a server with a
public IP.

## Contents

1. [Prerequisites](#prerequisites)
2. [Get the code onto the host](#get-the-code-onto-the-host)
3. [Configure `.env`](#configure-env)
4. [Configure `roller-production.properties`](#configure-roller-production-properties)
5. [DNS](#dns)
6. [First run](#first-run)
7. [TLS](#tls)
8. [Health monitoring](#health-monitoring)
9. [Backup and restore](#backup-and-restore)
10. [Upgrades](#upgrades)
11. [Firewall](#firewall)
12. [Troubleshooting](#troubleshooting)

## Prerequisites

- A host with a public IP and a Docker Engine with the Compose plugin
  (`docker compose version` should print v2.x). Any VPS-sized box works;
  Roller plus Postgres is comfortable on 1 vCPU / 2GB RAM for a handful of
  low-traffic blogs.
- A domain name (e.g. `blog.example.com`) with an A/AAAA record you control,
  **or** none at all if you only want to run over plain HTTP on a LAN (see
  [DNS](#dns) — Caddy supports both modes).
- Ports 80 and 443 reachable from the internet (Caddy needs 80 for the ACME
  HTTP-01 challenge even if you only ever browse over HTTPS).
- Outbound SMTP relay credentials if you want comment-notification and
  password-reset mail to work (`mail.hostname`/`mail.port`/optional
  `mail.username`/`mail.password`). Roller runs fine without mail configured;
  those two features just won't send anything.
- Nothing needs to be pre-installed on the host besides Docker: the app image
  bakes in its own JRE, themes, and migration SQL (see `Dockerfile`); Caddy
  and Postgres run from their own images. The app image also bakes in
  `cwebp` (the `webp` package, Debian's name for libwebp-tools), so WebP
  renditions of uploaded images are generated in production automatically.
  If you ever run the WAR outside this image, install libwebp-tools/webp
  yourself — Roller feature-detects the binary at startup and quietly falls
  back to a JPEG/PNG-only rendition ladder when it is missing.

## Get the code onto the host

`deploy/deploy.sh` and `docker-compose.prod.yml` both assume a full git
checkout on the host — the compose file bind-mounts `deploy/config` and
`deploy/caddy`, and `deploy.sh` reads `bin/db/migrate.sh` and
`bin/db/migrations` out of the working tree to run migrations. Clone the
repo directly on the deploy host:

```bash
git clone https://github.com/jakefearsd/roller.git /opt/roller
cd /opt/roller
```

(If you maintain a different fork, clone that instead — the `ROLLER_IMAGE`
value in your `.env` should then point at your fork's GHCR path; see below.)

## Configure `.env`

`docker-compose.prod.yml` reads its variables from a `.env` file in the repo
root (gitignored — it holds secrets). Copy the example and fill it in:

```bash
cp deploy/.env.example .env
```

Edit `.env`:

- `ROLLER_DOMAIN` — your real domain (e.g. `blog.example.com`) for
  auto-TLS, or `:80` for a domain-less LAN/testing deployment with plain
  HTTP only (see [TLS](#tls)).
- `ROLLER_IMAGE` — defaults to `ghcr.io/jakefearsd/roller:latest`, published
  by CI on every push to master (`.github/workflows/main.yml`, job
  `publish-image`). Pin to a `:<git-sha>` tag for a reproducible deploy
  instead of floating on `:latest`, or point it at your own fork's GHCR
  path if you cloned a fork. This value is ignored if you deploy with
  `deploy/deploy.sh --build` (build locally instead of pulling).

  **After the first CI publish:** GHCR packages are private by default the
  first time they're published, so an anonymous `docker pull` of
  `ROLLER_IMAGE` will fail with "denied"/"unauthorized" until you do one of
  the following: on GitHub, go to the package's own page (under your
  account/org's Packages tab) → Package settings → Change visibility →
  Public; or, keep it private and instead `docker login ghcr.io` on the VPS
  with a PAT that has `read:packages`. Until either is done, deploy with
  `deploy/deploy.sh --build` (builds from the local checkout, no pull
  needed).
- `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` — generate a real
  password for `POSTGRES_PASSWORD` (e.g. `openssl rand -base64 24`). These
  three are shared by the `app`, `postgres`, and `backup` services.
- `BACKUP_HOUR` / `BACKUP_RETENTION_DAYS` — when (UTC hour, 0-23) the
  nightly backup runs and how many days of backups to retain. Defaults
  (`3`, `14`) are reasonable starting points.
- `JAVA_OPTS` — optional extra JVM flags (e.g. `-Xmx1g`).

## Configure `roller-production.properties`

This is the app's runtime config, mounted read-only into the `app`
container. Copy the example and fill it in:

```bash
cp deploy/config/roller-production.properties.example \
   deploy/config/roller-production.properties
```

Edit `deploy/config/roller-production.properties`:

- `database.jdbc.username` / `database.jdbc.password` — **must match**
  `POSTGRES_USER` / `POSTGRES_PASSWORD` in `.env`. The two files are not
  templated against each other; keep them in sync by hand.
- `mail.hostname` / `mail.port` / (optional) `mail.username` /
  `mail.password` — your SMTP relay.
- Everything else in the example (`installation.type=auto`,
  `database.configurationType=jdbc`, the `/data/*` directories,
  `themes.dir=/app/themes`) matches the image's runtime contract and
  normally doesn't need to change — see the comments in the example file
  and `Dockerfile` if you do need to.

This file is gitignored (`deploy/config/roller-production.properties`) —
only the `.example` template is committed.

## DNS

Point your domain's A (and AAAA, if you have an IPv6 address) record at the
host's public IP **before** starting the stack. Caddy requests a Let's
Encrypt certificate on first request to the domain and needs port 80
reachable from the public internet to complete the ACME HTTP-01 challenge;
if DNS isn't live yet, certificate issuance fails and Caddy retries with
backoff until it is.

If you don't have a domain (LAN deployment, quick local test of the
production stack), set `ROLLER_DOMAIN=:80` in `.env` instead — Caddy then
serves plain HTTP on port 80 only and never attempts ACME/TLS (a bare port
with no hostname isn't a certificate-able name, so automatic HTTPS simply
doesn't activate for it). See `deploy/caddy/Caddyfile` for the exact logic.

## First run

With `.env` and `deploy/config/roller-production.properties` filled in:

```bash
deploy/deploy.sh --build   # build the image from this checkout, or:
deploy/deploy.sh           # pull ROLLER_IMAGE from GHCR instead
```

`deploy.sh` brings up `postgres`, waits for it to report healthy, applies
any pending schema migrations directly against it (via
`bin/db/migrate.sh`, copied into the postgres container and run there — no
separate migration-tracking scheme; see the script's own header), *then*
starts `app` and polls its health endpoint for up to 120s before starting
`caddy` and `backup`. Migrations are deliberately applied before the app's
first start: `installation.type=auto` makes the app check
`WebloggerFactory.isBootstrapped()` once at startup, not on every request,
so migrating-then-starting (rather than starting-then-migrating) means a
fresh app container always sees a current schema.

When it finishes, browse to `https://<your-domain>/roller` (or
`http://<host>/roller` in `:80` mode) and complete Roller's normal
first-run flow: register the first user account, which becomes the site
administrator, then create a weblog.

You can also start the stack directly with `docker compose` instead of
`deploy.sh` for the very first run — `deploy.sh` is really the *upgrade*
tool (it's also safe and idempotent for first-run, since a fresh database
has no migrations to apply):

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d
```

If you do this, remember migrations won't run until you invoke
`bin/db/migrate.sh` yourself or run `deploy/deploy.sh` at least once.

## TLS

Handled entirely by Caddy (`deploy/caddy/Caddyfile`) — there is nothing to
configure beyond setting `ROLLER_DOMAIN` to a real domain in `.env`. Caddy
obtains a Let's Encrypt certificate for that domain on first use, renews it
automatically well before expiry, and redirects HTTP to HTTPS. Certificate
state lives in the `caddy-data` named volume, so it survives
`docker compose down` (but not `down -v`).

## Health monitoring

The app's health endpoint (`/actuator/health`, Spring Boot Actuator) lives
on a separate **management port, 8090**, which — per the runtime contract —
is **never published to the host**. `docker-compose.prod.yml` doesn't map
it, and it shouldn't be added to an override file for a production
deployment either: it's meant to be reached only from inside the Docker
network (the `app` service's own healthcheck) or via `docker exec`, never
from the internet.

To check health from the host:

```bash
# Compose's own view (uses the same internal healthcheck):
docker compose -f docker-compose.prod.yml ps

# Or query the endpoint directly via exec:
docker compose -f docker-compose.prod.yml exec app \
    curl -sf http://localhost:8090/actuator/health

# Or inspect Docker's healthcheck state/history directly:
docker inspect --format '{{json .State.Health}}' \
    $(docker compose -f docker-compose.prod.yml ps -q app) | python3 -m json.tool
```

`docker compose ps` reporting `app` as `healthy` is normally enough for
day-to-day monitoring; wire up an external uptime check against the public
`https://<domain>/roller/roller-ui/login.rol` URL (200 OK) for outside-in
monitoring, since that's the only thing actually exposed to the internet.

Logs:

```bash
docker compose -f docker-compose.prod.yml logs -f app
docker compose -f docker-compose.prod.yml logs -f caddy
docker compose -f docker-compose.prod.yml logs -f postgres
docker compose -f docker-compose.prod.yml logs -f backup
```

## Backup and restore

The `backup` service (reuses the `postgres:16` image for `pg_dump`/`psql`/
`tar`) runs `deploy/backup/loop.sh`, a cron-less scheduler that wakes hourly
and, once a day at `BACKUP_HOUR` (UTC), runs `deploy/backup/backup.sh`. Each
cycle:

1. `pg_dump -Fc` the database to `/backups/rollerdb-<timestamp>.dump`.
2. `tar czf` the `mediafiles`/`search-index`/`uploads` volumes to
   `/backups/volumes-<timestamp>.tar.gz`.
3. Deletes anything in `/backups` older than `BACKUP_RETENTION_DAYS`.

Both artifacts are written atomically: each is built under a `.tmp` suffix
and only `mv`'d to its final name once the write completes, so a backup
killed mid-write (OOM, container restart, disk full) never leaves a
truncated file sitting under a name a restore would trust — it leaves an
orphaned `.tmp` file instead, which rotation also cleans up once it ages
past the retention window. Backups land in the `roller-backups` named
volume.

These dumps contain the full database, including user password hashes —
treat them as sensitive. Copy nightly dumps off-host regularly (e.g. an
`rclone`/`rsync` cron job to remote storage) and restrict filesystem access
to wherever they land, both on this host and off it.

Run a backup by hand at any time:

```bash
docker compose -f docker-compose.prod.yml exec backup /backup.sh
docker compose -f docker-compose.prod.yml exec backup ls -la /backups
```

### Restore

Full restore commands (with exact flags) live in the header comment of
`deploy/backup/backup.sh` — read that before restoring for real. Summary:

**Database** (app must be stopped first):

The restore command must be run as a single-quoted `bash -c '...'` INSIDE
the postgres container, exactly like below — `$POSTGRES_USER` /
`$POSTGRES_PASSWORD` / `$POSTGRES_DB` only exist in the postgres container's
own environment (set by `docker-compose.prod.yml`). A double-quoted or
unquoted command would instead expand them in your host shell, where
they're unset, and `pg_restore` would silently get empty values (this is
the same in-container pattern `deploy.sh` uses for migrations):

```bash
docker compose -f docker-compose.prod.yml stop app

docker compose -f docker-compose.prod.yml exec -T postgres bash -c '
    set -euo pipefail
    export PGHOST=localhost
    export PGUSER="${POSTGRES_USER}"
    export PGPASSWORD="${POSTGRES_PASSWORD}"
    pg_restore -d "${POSTGRES_DB}" --clean --if-exists
' < /path/on/host/to/rollerdb-<timestamp>.dump

docker compose -f docker-compose.prod.yml start app
```

(Get the dump onto the host first with
`docker compose -f docker-compose.prod.yml cp backup:/backups/rollerdb-<timestamp>.dump .`
if restoring from a different machine than the one that made it.)

**Media/search-index/uploads volumes** (stack must be fully down — the
archive extracts to `/data/...` paths matching the volume mount points):

The volume names below are Compose's default PROJECT-PREFIXED names, not
the bare names declared in `docker-compose.prod.yml` — the project name is
derived from the directory the compose file lives in (`roller` if you
cloned to `/opt/roller` per [Get the code onto the
host](#get-the-code-onto-the-host)), giving e.g. `roller_roller-mediafiles`.
Run `docker volume ls` FIRST to confirm the exact names on your host and
substitute them below if they differ:

```bash
docker compose -f docker-compose.prod.yml down
docker run --rm \
    -v roller_roller-mediafiles:/data/mediafiles \
    -v roller_roller-search-index:/data/search-index \
    -v roller_roller-uploads:/data/uploads \
    -v roller_roller-backups:/backups \
    postgres:16@sha256:33f923b05f64ca54ac4401c01126a6b92afe839a0aa0a52bc5aeb5cc958e5f20 \
    tar xzf /backups/volumes-<timestamp>.tar.gz -C /
docker compose -f docker-compose.prod.yml up -d
```

Test your restore procedure at least once before you need it for real —
against a scratch copy of the stack, not production.

## Upgrades

```bash
cd /opt/roller
git pull
deploy/deploy.sh           # pull ROLLER_IMAGE and deploy it, or:
deploy/deploy.sh --build   # build the new image from the checkout instead
deploy/deploy.sh --prune   # either of the above, plus `docker image prune -f` after
```

This is the same script used for [first run](#first-run) — running it
again is safe and idempotent. Each run: pulls/builds the image, brings
`postgres` up and waits healthy, applies any pending migrations (a no-op if
there are none — `bin/db/migrate.sh` tracks what's already applied), starts
`app` and waits for it to report healthy (up to 120s, exits non-zero if it
doesn't), then reconciles `caddy` and `backup`. Nothing is silently
swallowed: a failed pull, a failed migration, or an app that never goes
healthy all cause `deploy.sh` to exit non-zero rather than leaving the
stack in a half-upgraded state without telling you.

Always have a recent, verified backup before upgrading across a schema
change (see [Backup and restore](#backup-and-restore)).

## Firewall

Only 80 and 443 need to be open to the internet — Caddy is the only service
with a published port. Nothing else in `docker-compose.prod.yml` publishes
a host port by default (`postgres` and the app's `8080`/`8090` are reachable
only on the internal Docker network), so there's nothing else to explicitly
block on a default-deny host firewall, but locking it down explicitly is
still good practice:

```bash
ufw default deny incoming
ufw allow 22/tcp    # SSH — or your actual admin access port
ufw allow 80/tcp
ufw allow 443/tcp
ufw enable
```

Do **not** add host port mappings for `postgres` (5432) or the app's
`8080`/`8090` in a compose override file for a real deployment; if you need
one-off DB access for debugging, use `docker compose exec postgres psql
...` instead of exposing the port.

## Troubleshooting

**A container won't start / keeps restarting**

```bash
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs app
docker compose -f docker-compose.prod.yml logs postgres
```

**App container is up but `/roller` isn't reachable through Caddy**

Check Caddy's own logs for ACME/certificate errors (common cause: DNS isn't
pointed at the host yet, or port 80 isn't reachable from the internet):

```bash
docker compose -f docker-compose.prod.yml logs caddy
```

**App health check never turns green**

The app needs a reachable, migrated Postgres to bootstrap
(`WebloggerFactory.bootstrap()`). Confirm migrations have actually been
applied (`deploy/deploy.sh` again, or `bin/db/migrate.sh` directly against
the `postgres` container) and that
`deploy/config/roller-production.properties`'s `database.jdbc.*` values
match `.env`'s `POSTGRES_*` values exactly.

**Disk filling up**

Backups accumulate in the `roller-backups` volume until
`BACKUP_RETENTION_DAYS` rotates them out; lower the retention window in
`.env` (and restart the `backup` service) if backups are consuming more
disk than expected, or check for orphaned `*.tmp` files from a backup that
was killed mid-write (see [Backup and restore](#backup-and-restore) —
rotation cleans these up too, just on the same delay).

**Starting over on this host**

`docker compose -f docker-compose.prod.yml down -v` removes every named
volume (`roller-pgdata`, `roller-mediafiles`, `roller-search-index`,
`roller-uploads`, `roller-backups`, `caddy-data`, `caddy-config`) —
irreversible without a backup. Only use it when you actually intend to
destroy all data on this host.
