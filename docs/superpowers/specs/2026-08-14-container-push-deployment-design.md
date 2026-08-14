# Container-push production deployment

Date: 2026-08-14
Status: approved, not yet implemented

## Goal

Make the published container images the complete deployment artifact, and make
the image you test locally byte-identical to the one you publish, with `.env`
as the only thing that differs between the two environments.

Today neither is true. `docker-compose.prod.yml` bind-mounts three things from
a git checkout on the deploy host (`deploy/caddy/Caddyfile`,
`deploy/config/roller-production.properties`, `deploy/backup/*.sh`), and
`deploy/deploy.sh` copies `bin/db/migrate.sh` and `bin/db/migrations` into the
postgres container at deploy time. The image is therefore only part of what
ships; the rest comes from whatever the host's checkout happens to be at. A
`git pull` and a `docker pull` can disagree, and nothing detects it.

## Non-goals

- Changing registries. Images stay on GHCR (`ghcr.io/jakefearsd/roller`);
  release.yml already authenticates with the built-in `GITHUB_TOKEN` and needs
  no new secrets.
- Automatic deployment (Watchtower and similar). Production changes when an
  operator runs a command.
- Digest pinning. `.env` pins a version tag; digest pinning stays available to
  an operator who wants it but is not the documented path.
- Multi-arch images.
- Changing `installation.type`. It stays `auto`; migrations continue to be
  applied by `bin/db/migrate.sh`, just from inside a container instead of from
  the host.

## Decisions

| Decision | Choice | Why |
|---|---|---|
| Host state | Nothing but `docker-compose.prod.yml` + `.env` | The only version where the pushed images fully define the deployment |
| Registry | GHCR, unchanged | Works today, no new secrets, public pulls are unauthenticated and unmetered |
| Local scope | Full stack from the same compose file | The newsletter and analytics paths are exactly the ones with same-origin CSP constraints; a subset would leave them untested until production |
| Publish trigger | `v*.*.*` tag, gated on the unit suite | Deliberate releases, and what you test locally is a real published tag |
| Deploy trigger | Manual, operator-run | Production changes when someone is present |
| App config | `ROLLER_*` environment overlay in `WebloggerConfig` | Any property becomes settable from `.env` with no image rebuild |
| Provisioner | Inside the app image | One image is the whole deliverable; the migrations the provisioner runs are the same bytes as the ones on the WAR's classpath, so they cannot drift |

## Architecture

### Images

Two images, both built from this tree, both tagged with the release version.

**`ghcr.io/jakefearsd/roller:<version>`** — the existing runtime stage plus:

- `postgresql-client` version 16 or newer (`pg_dump -Fc` refuses a server newer
  than the client, and the stack runs PostgreSQL 16)
- `/app/migrations` (already present)
- `/app/provision.sh` (new)
- `/app/umami-views.sql` (from `deploy/analytics/`)
- `/app/bin/migrate.sh` (from `bin/db/`)
- `/app/backup/backup.sh`, `/app/backup/loop.sh` (from `deploy/backup/`)

One image serves three roles — the app, the one-shot provisioner, and the
backup loop — because those are exactly the three roles that need either the
WAR or a PostgreSQL client.

**`ghcr.io/jakefearsd/roller-caddy:<version>`** — `caddy:2-alpine` with
`deploy/caddy/Caddyfile` baked in at `/etc/caddy/Caddyfile`. Content unchanged.

### Compose topology

A new one-shot `provision` service runs the app image with
`entrypoint: /app/provision.sh` and `restart: "no"`. It performs exactly what
`deploy.sh` does today, in the same order:

1. Create the umami and listmonk databases if they do not exist
2. Run `migrate.sh` against `rollerdb`
3. `GRANT CONNECT` on both databases to `grafana_ro`
4. Apply `umami-views.sql` to the umami database

Ordering moves out of bash and into the compose file:

```
postgres ──service_healthy──▶ provision ──service_completed_successfully──▶ app
                                                                         ├──▶ umami
                                                                         ├──▶ listmonk
                                                                         └──▶ caddy (via app)
backup ──service_healthy(postgres)──▶
```

This is the central payoff. `docker compose up -d` becomes correct on its own,
identically in both environments, and the migrate-then-start guarantee that
`deploy.sh`'s header spends a paragraph explaining becomes declarative. It
requires Compose v2.17 or newer for `service_completed_successfully`, which
becomes a documented prerequisite.

`backup` switches from `postgres:16` with bind-mounted scripts to the app image
with `entrypoint: /app/backup/loop.sh`. It needs `pg_dump`, `psql`, `tar`,
`find` and `date`; the first two come from `postgresql-client`, the rest from
the Debian-based JRE base image.

`provision.sh` must remain idempotent, which it is by construction: `createdb`
is guarded by an existence check, `migrate.sh` tracks applied versions in
`schema_migrations`, and the grants and views are `CREATE OR REPLACE` / `GRANT`.

Its environment contract, supplied by compose from `.env`: `POSTGRES_DB`,
`POSTGRES_USER`, `POSTGRES_PASSWORD`, `UMAMI_DB`, `LISTMONK_DB`. It sets
`PGHOST=postgres`, `PGPORT=5432`, `PGUSER=$POSTGRES_USER`,
`PGPASSWORD=$POSTGRES_PASSWORD`, and invokes `migrate.sh` with
`DB_NAME=$POSTGRES_DB` and `DB_APP_USER=$POSTGRES_USER`, matching the contract
in `bin/db/migrate.sh`'s header. It connects over the compose network rather
than over a socket, which is the one behavioural difference from today's
version — that ran inside the postgres container itself.

The `build:` stanza is removed from `docker-compose.prod.yml`. That file now
lives on a host with no build context, and images are named purely from
`IMAGE_VERSION`. Building locally is a separate command (see below), not a
compose concern. `deploy.sh` loses its `--build` flag accordingly.

## Configuration

### The environment overlay

`WebloggerConfig`'s static initializer gains a final step, after the
`-Droller.custom.config` file load and before property expansion:

- Consider only environment variables prefixed `ROLLER_`.
- Strip the prefix, lowercase the remainder, replace `_` with `.` to get a
  candidate key.
- If a key already present in the config matches that candidate
  case-insensitively, write to the existing key's exact spelling. This is what
  makes `ROLLER_DATABASE_JDBC_DRIVERCLASS` reach `database.jdbc.driverClass`.
- Otherwise write the candidate key as derived. This case is required, not
  incidental: `mail.port` does not appear in `roller.properties` at all, and
  `uploads.dir` is present only as a comment (line 118), so an
  allowlist restricted to keys with active defaults would silently fail to set
  either one.
- If two keys already in the config collide case-insensitively, throw at
  startup rather than silently picking one.

Resulting precedence, lowest to highest:

1. `/org/apache/roller/weblogger/config/roller.properties` (classpath defaults)
2. `/roller-custom.properties` or `/roller-junit.properties` (classpath)
3. the file named by `-Droller.custom.config`
4. `ROLLER_*` environment variables

The `-Droller.custom.config` mechanism stays supported — `./roller dev` depends
on it — but the production image's ENTRYPOINT stops passing it, since the file
it pointed at no longer exists.

### Config-load failure semantics

`WebloggerConfig`'s static initializer wraps its whole config load — file
reads, property expansion, and now the environment overlay — in a
long-standing `catch (Exception e) { e.printStackTrace(); }`. That swallowed
the new case-collision guard: two `ROLLER_*` variables colliding
case-insensitively threw inside the overlay step, the catch printed a stack
trace and moved on, and the app booted with the entire overlay silently
skipped — the opposite of the fail-loud behaviour the guard exists to
provide. The catch now rethrows when the caught exception is a
`RuntimeException`, so the collision guard (and any other overlay bug) stops
the boot as intended; checked exceptions — a missing optional properties
file, an `IOException` — keep the original lenient handling.

### Secret masking

The existing debug dump (`WebloggerConfig.java:144-153`) prints every key and
value at debug level. Values for keys matching `password`, `token` or `secret`
must be masked. This is pre-existing behaviour being corrected in passing
because the overlay makes it easier to turn debug logging on while diagnosing a
configuration problem.

### Mapping

`deploy/config/roller-production.properties` is deleted. Its keys become:

| Property | Environment variable |
|---|---|
| `database.jdbc.connectionURL` | `ROLLER_DATABASE_JDBC_CONNECTIONURL` |
| `database.jdbc.username` | `ROLLER_DATABASE_JDBC_USERNAME` |
| `database.jdbc.password` | `ROLLER_DATABASE_JDBC_PASSWORD` |
| `database.jdbc.driverClass` | `ROLLER_DATABASE_JDBC_DRIVERCLASS` |
| `database.configurationType` | `ROLLER_DATABASE_CONFIGURATIONTYPE` |
| `installation.type` | `ROLLER_INSTALLATION_TYPE` |
| `themes.dir` | `ROLLER_THEMES_DIR` |
| `mediafiles.storage.dir` | `ROLLER_MEDIAFILES_STORAGE_DIR` |
| `search.index.dir` | `ROLLER_SEARCH_INDEX_DIR` |
| `uploads.dir` | `ROLLER_UPLOADS_DIR` |
| `mail.configurationType` | `ROLLER_MAIL_CONFIGURATIONTYPE` |
| `mail.hostname` | `ROLLER_MAIL_HOSTNAME` |
| `mail.port` | `ROLLER_MAIL_PORT` |
| `mail.username` | `ROLLER_MAIL_USERNAME` |
| `mail.password` | `ROLLER_MAIL_PASSWORD` |
| `newsletter.listmonk.baseurl` | `ROLLER_NEWSLETTER_LISTMONK_BASEURL` |

The values that are fixed by the image's own layout — `themes.dir=/app/themes`,
the three `/data/*` directories, `installation.type=auto`,
`database.configurationType=jdbc`, `database.jdbc.driverClass` — are given as
defaults in `.env.example` rather than hardcoded, so an operator can override
them without a rebuild but never has to think about them.

### How the app receives them

The `app` service uses `env_file: .env`, so adding a key to `.env` is the only
step required to set a property.

The accepted tradeoff: this hands the app container every secret in the file,
including listmonk's admin password and umami's app secret. The alternative — an
explicit `environment:` list — is more hygienic but means a new key requires
editing compose as well, which weakens the property this design exists to
establish. On a single-tenant host the exposure is marginal, and `docker
inspect` already exposes the postgres credentials today.

Consequence: infrastructure variables are renamed off the `ROLLER_` prefix so
they do not land in the property namespace as junk keys.

| Old | New |
|---|---|
| `ROLLER_DOMAIN` | `SITE_DOMAIN` |
| `ROLLER_IMAGE` | `IMAGE_VERSION` (a tag, not a full image reference) |

`UMAMI_*` and `LISTMONK_*` variables are unaffected.

## Local parity

The same compose file and the same two images. `.env` differs in six places:

| Key | Production | Local |
|---|---|---|
| `SITE_DOMAIN` | `blog.example.com` | `:80` |
| `UMAMI_DOMAIN` | `analytics.example.com` | `:8081` |
| `LISTMONK_DOMAIN` | `newsletter.example.com` | `:8082` |
| `LISTMONK_ROOT_URL` | `https://newsletter.example.com` | `http://localhost:8082` |
| secrets | real | throwaway |
| `IMAGE_VERSION` | the deployed version | the tag being tested |

Caddy serves the umami and listmonk hostnames on 443 in production, so locally
those two site blocks need ports instead. Compose publishes `127.0.0.1:8081` and
`127.0.0.1:8082` in **both** environments: functional locally, inert in
production because nothing inside the container listens on those ports when the
blocks carry real hostnames. Loopback binding keeps this consistent with the
rule that only 80 and 443 are reachable from outside the host.

`SITE_DOMAIN=:80` already works — the Caddyfile documents it (lines 9-11): a
bare port is not a certificate-able name, so automatic HTTPS never activates and
Caddy serves plain HTTP.

### The two local test procedures

They are not equivalent, and the difference matters enough to document both.

**Before cutting a tag** — smoke-testing what you are about to publish:

```bash
docker build -t ghcr.io/jakefearsd/roller:test .
docker build -t ghcr.io/jakefearsd/roller-caddy:test -f deploy/caddy/Dockerfile .
IMAGE_VERSION=test docker compose -f docker-compose.prod.yml up -d
```

Same Dockerfiles CI uses, same compose file, local `.env`. This catches
essentially everything, but the bytes are not identical to what CI will
publish — CI runs its own build.

**After publishing, before deploying** — verifying the actual artifact:

```bash
IMAGE_VERSION=6.2.1 docker compose -f docker-compose.prod.yml pull
IMAGE_VERSION=6.2.1 docker compose -f docker-compose.prod.yml up -d
```

This pulls the exact images the deploy host will pull. It is the only form that
is byte-identical to production, and it is the one to run before touching the
server. Both use the same compose file and differ only in `.env`.

## Release and deploy

### release.yml

- A `test` job runs `mvn -V -ntp install`, which covers the unit suite and the
  JaCoCo floors (`check` binds to `verify`, and `install` runs `verify`). The
  publish job declares `needs: test`.

  This closes a real gap: a tag push does not match `main.yml`'s
  `on: push: branches: [master]` filter, so in the documented release flow
  (`git push origin master v6.2.0`) the unit suite and the publish job race,
  with no dependency between them. A tag on a commit that never passed
  `build-test` publishes today.

- The Docker build becomes the only build of the WAR. The Release's WAR asset is
  extracted from the built image (`docker create` + `docker cp`) rather than
  produced by a separate `mvn package`, so the attached WAR and the shipped WAR
  are the same bytes.

- Both images are built and pushed, tagged `:<version>`, `:latest`, and
  `:sha-<short>`.

- `docker-compose.prod.yml`, `deploy/.env.example`, and `deploy/deploy.sh` are
  attached to the Release, so a host with no checkout can obtain everything it
  needs from the release page.

### deploy.sh

Shrinks to: verify `.env` exists, `docker compose pull`, `docker compose up -d`,
poll the app's health endpoint, report. No `.env` sourcing, no copying files
into containers, no ordering logic — compose owns all of it now.

## Bug fixes folded in

**The `.env` variables `deploy.sh` never loads.** Lines 97 and 167 expand
`${UMAMI_DB:-umami}` and `${LISTMONK_DB:-listmonk}` in the host shell, which
has not sourced `.env` — only `docker compose` reads it. Renaming either
database in `.env` today makes the script provision `umami`/`listmonk` while
the containers point elsewhere. This is fixed by deletion: the logic moves into
`provision.sh`, which runs inside a container whose environment compose
populates from `.env` correctly.

**The stale routing comment.** `docker-compose.prod.yml:165` states that the
public subscribe API is "routed by Caddy" to listmonk. The Caddyfile says the
opposite and carries no such route; `/newsletter/*` is served by the app
(`ServletRegistrationConfig.NEWSLETTER_URL_PATTERNS`, permitted in
`SecurityConfig:295`), which is where the throttle and the `roller_event` write
live. The comment is corrected. `roller-production.properties.example` lines
58-61 already state it correctly, so this is the one stale copy — and it is
precisely the comment that would invite someone to re-add the rewrite the
Caddyfile warns against.

## Files touched

**New**: `deploy/provision.sh`, `deploy/caddy/Dockerfile`,
`app/src/test/.../WebloggerConfigEnvOverrideTest.java`,
`app/src/test/.../ProductionComposeTest.java`.

**Changed**: `Dockerfile` (postgresql-client, the new COPYs, ENTRYPOINT drops
`-Droller.custom.config`), `docker-compose.prod.yml` (provision service,
`depends_on` conditions, `env_file`, no bind mounts, no `build:`, loopback
ports, the corrected routing comment), `deploy/deploy.sh` (reduced to
pull/up/wait), `deploy/.env.example` (the `ROLLER_*` property block, renamed
infrastructure variables), `.github/workflows/release.yml` (test gate, both
images, WAR extracted from the image, compose and env files as release
assets), `.dockerignore` (its comment describes
`roller-production.properties` as bind-mounted at runtime, which stops being
true), `WebloggerConfig.java` (the overlay and the masking), `CLAUDE.md`
(Configuration scope gains the environment layer; the deployment description
changes), `docker_deployment.md` (substantial rewrite, see Risks).

**Deleted**: `deploy/config/roller-production.properties.example` and the
`deploy/config/` directory.

**Deliberately untouched**: `docker-compose.yml` (the dev postgres stack), the
`./roller` script, and `bin/db/migrate.sh` itself — `provision.sh` calls it
unmodified, preserving the property that a deploy's migration step and a manual
run cannot disagree about what "applied" means.

## Testing

**`WebloggerConfigEnvOverrideTest`** — the mapping rule end to end: prefix
filtering, case restoration against a known key, unknown-key passthrough
(`mail.port`), env-beats-file precedence, secret masking in the debug dump, and
the case-collision failure.

**`ProductionComposeTest`** — reads `docker-compose.prod.yml` and asserts that
no service bind-mounts a repository path. Reintroducing a bind mount is the
single change that would silently undo this work, and this repo already pins
configuration files with unit tests (`DesignTokenTest`, `EditorJspEscapingTest`,
`Routes`/`RouteSweepIT`), so the shape is established.

**Manual gate** — bring the stack up locally from clean volumes, then run
`docker compose up -d` a second time to confirm `provision` is idempotent and
exits successfully rather than blocking the dependent services.

## Risks

- **`pg_dump` client version is load-bearing.** The client must be 16 or newer
  or nightly backups fail. Install `postgresql-client-16` (or later)
  explicitly rather than the distribution's unversioned `postgresql-client`
  metapackage.
- **Image size** grows roughly 25MB.
- **Compose version floor.** `service_completed_successfully` needs Compose
  v2.17+; older hosts fail with a schema error rather than a subtle
  misbehaviour, which is the acceptable failure mode.
- **Secrets in `docker inspect`** for the app container, per the `env_file`
  tradeoff above.
- **Inconsistent third-party image pinning — found and closed.** The
  `listmonk` service was floating on `listmonk/listmonk:v3`, which does not
  exist on Docker Hub at all: `docker compose pull` failed on it, so a fresh
  production deploy was already broken before this wave, independent of
  anything else it changed. It is now pinned by digest to v6.2.0 (the oldest
  tag still published is v6.0.0), matching how `postgres` and `caddy` were
  already pinned; `umami` is now digest-pinned the same way. listmonk runs
  its own `--upgrade --yes` at container startup (see the `command:` block in
  `docker-compose.prod.yml`), so an existing deployment migrates its own
  schema automatically on first boot after the jump to v6.2.0 — no manual
  migration step for listmonk itself.
- **`docker_deployment.md` needs a substantial rewrite**, not a patch — the
  "Get the code onto the host", "Configure `roller-production.properties`",
  "First run" and "Upgrades" sections all describe a workflow that no longer
  exists.
