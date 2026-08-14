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
2. [Get the deploy files onto the host](#get-the-deploy-files-onto-the-host)
3. [Configure `.env`](#configure-env)
4. [DNS](#dns)
5. [First run](#first-run)
6. [TLS](#tls)
7. [Health monitoring](#health-monitoring)
8. [Analytics](#analytics)
9. [Newsletter](#newsletter)
10. [Backup and restore](#backup-and-restore)
11. [Test a release locally before deploying it](#test-a-release-locally-before-deploying-it)
12. [Upgrades](#upgrades)
13. [Firewall](#firewall)
14. [Troubleshooting](#troubleshooting)

## Prerequisites

- A host with a public IP and a Docker Engine with the Compose plugin,
  **v2.17 or newer** (`docker compose version`) — the compose file uses
  `depends_on: condition: service_completed_successfully`, which older
  Compose rejects with a schema error rather than misbehaving quietly. Any
  VPS-sized box works otherwise; Roller plus Postgres is comfortable on
  1 vCPU / 2GB RAM for a handful of low-traffic blogs.
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

## Get the deploy files onto the host

There is no git checkout on the deploy host at all. Everything the stack
needs lives in two published images (`ghcr.io/jakefearsd/roller` and
`ghcr.io/jakefearsd/roller-caddy`) — nothing is bind-mounted from a working
tree. The host needs exactly three files, all attached to the GitHub Release
for the version you're deploying: `docker-compose.prod.yml`, `.env.example`,
and `deploy.sh`. Download them into one directory, e.g. `/opt/roller`:

```bash
mkdir -p /opt/roller && cd /opt/roller
curl -LO https://github.com/jakefearsd/roller/releases/download/v6.2.0/docker-compose.prod.yml
curl -LO https://github.com/jakefearsd/roller/releases/download/v6.2.0/.env.example
curl -LO https://github.com/jakefearsd/roller/releases/download/v6.2.0/deploy.sh
chmod +x deploy.sh
mv .env.example .env
```

(If you maintain a different fork, the `IMAGE_VERSION` tag in your `.env`
still resolves against `ghcr.io/jakefearsd/roller` unless you also edit the
image names in `docker-compose.prod.yml` to point at your fork's GHCR path.)

## Configure `.env`

`docker-compose.prod.yml` reads all of its configuration — infrastructure
settings **and** the app's own runtime properties — from a `.env` file next
to it (gitignored — it holds secrets). You already renamed the downloaded
`.env.example` to `.env` above; edit it in place.

`.env` has four parts:

- **Images.** `IMAGE_VERSION` is the tag shared by both
  `ghcr.io/jakefearsd/roller` and `ghcr.io/jakefearsd/roller-caddy` — they are
  published together from one release job and must move together. Published
  only when a `v*.*.*` tag is pushed (`.github/workflows/release.yml`), so
  this is always a specific release, never "whatever was on master." Pin it
  to the version you intend to run rather than floating; see
  [Upgrades](#upgrades) for how to move it forward deliberately.

  **After the first tagged release:** GHCR packages are private by default
  the first time they're published, so an anonymous `docker compose pull`
  will fail with "denied"/"unauthorized" until you do one of the following:
  on GitHub, go to the package's own page (under your account/org's Packages
  tab) → Package settings → Change visibility → Public; or keep it private
  and instead `docker login ghcr.io` on the VPS with a PAT that has
  `read:packages`.
- **`SITE_DOMAIN`** — your real domain (e.g. `blog.example.com`) for
  auto-TLS, or `:80` for a domain-less LAN/testing deployment with plain
  HTTP only (see [TLS](#tls)). Deliberately not named `ROLLER_DOMAIN`: that
  prefix is reserved for app configuration (below), and a stray
  `ROLLER_DOMAIN` would be overlaid onto the app's properties as a junk key
  named `domain`.
- **The `ROLLER_*` block — the app's entire runtime configuration.** There is
  no properties file in the image; every property the app reads comes from
  `ROLLER_*` variables in this file, overlaid onto the image's built-in
  defaults by `WebloggerConfig.applyEnvironmentOverrides` at startup: strip
  the `ROLLER_` prefix, lowercase the remainder, turn `_` into `.` to get the
  property name — so `ROLLER_DATABASE_JDBC_USERNAME` sets
  `database.jdbc.username`, `ROLLER_MAIL_HOSTNAME` sets `mail.hostname`, and
  so on for any key documented in
  `app/src/main/resources/org/apache/roller/weblogger/config/roller.properties`.
  The variables `.env.example` ships (and their meaning) are:
  - `ROLLER_INSTALLATION_TYPE=auto` — checks the schema at startup; by then
    the `provision` service has already applied every pending migration (see
    [First run](#first-run)).
  - `ROLLER_DATABASE_CONFIGURATIONTYPE`, `ROLLER_DATABASE_JDBC_DRIVERCLASS`,
    `ROLLER_DATABASE_JDBC_CONNECTIONURL` — fixed by the image's layout and the
    compose network (the connection URL's host is the `postgres` service
    name); normally leave these as shipped.
  - `ROLLER_DATABASE_JDBC_USERNAME` / `ROLLER_DATABASE_JDBC_PASSWORD` — **must
    match** `POSTGRES_USER` / `POSTGRES_PASSWORD` below. The two are not
    templated against each other; keep them in sync by hand.
  - `ROLLER_THEMES_DIR`, `ROLLER_MEDIAFILES_STORAGE_DIR`,
    `ROLLER_SEARCH_INDEX_DIR`, `ROLLER_UPLOADS_DIR` — match the image's own
    layout and the volumes `docker-compose.prod.yml` mounts; no reason to
    change them.
  - `ROLLER_MAIL_CONFIGURATIONTYPE`, `ROLLER_MAIL_HOSTNAME`,
    `ROLLER_MAIL_PORT`, and optionally `ROLLER_MAIL_USERNAME` /
    `ROLLER_MAIL_PASSWORD` — your SMTP relay, for password-reset and
    notification mail. Roller runs fine without these set; those two
    features just won't send anything.
  - `ROLLER_NEWSLETTER_LISTMONK_BASEURL` — where `/newsletter/subscribe`
    forwards to. Blank makes that endpoint return 503; see
    [Newsletter](#newsletter).
- **Postgres credentials, backup schedule, Analytics and Newsletter
  settings** — `POSTGRES_DB`/`POSTGRES_USER`/`POSTGRES_PASSWORD` (shared by
  `postgres`, `provision`, and `backup` — generate a real password, e.g.
  `openssl rand -base64 24`), `BACKUP_HOUR`/`BACKUP_RETENTION_DAYS`
  (defaults `3`, `14` are reasonable starting points), `JAVA_OPTS` (optional
  extra JVM flags, e.g. `-Xmx1g`), and the `UMAMI_*`/`LISTMONK_*` variables
  covered in their own sections below ([Analytics](#analytics),
  [Newsletter](#newsletter)).

## DNS

Point your domain's A (and AAAA, if you have an IPv6 address) record at the
host's public IP **before** starting the stack. Caddy requests a Let's
Encrypt certificate on first request to the domain and needs port 80
reachable from the public internet to complete the ACME HTTP-01 challenge;
if DNS isn't live yet, certificate issuance fails and Caddy retries with
backoff until it is.

If you want the analytics dashboard (see [Analytics](#analytics)) or the
newsletter (see [Newsletter](#newsletter)), point a second and third name —
`analytics.example.com` and `newsletter.example.com`, say — at the same IP and
set `UMAMI_DOMAIN` / `LISTMONK_DOMAIN` to them. Caddy obtains a certificate for it the same way. Leave
it unset and the dashboard is simply unreachable; the blog still works and
still collects nothing.

If you don't have a domain (LAN deployment, quick local test of the
production stack), set `SITE_DOMAIN=:80` in `.env` instead — Caddy then
serves plain HTTP on port 80 only and never attempts ACME/TLS (a bare port
with no hostname isn't a certificate-able name, so automatic HTTPS simply
doesn't activate for it). See `deploy/caddy/Caddyfile` for the exact logic.

## First run

With `.env` filled in:

```bash
docker compose -f docker-compose.prod.yml up -d
```

There is no separate migration step and no ordering to get right: compose
runs the one-shot `provision` service first (creates the `umami`/`listmonk`
databases, applies the migration chain via `migrate.sh`, grants
`grafana_ro`, installs the analytics views — see `deploy/provision.sh`), and
`app`, `umami` and `listmonk` all declare
`depends_on: { provision: { condition: service_completed_successfully } }`,
so none of them starts until provisioning has exited successfully. `deploy.sh`
(see [Upgrades](#upgrades)) wraps this same `up -d` with a health-check wait
and is safe to use for first run too, but the plain compose command above is
equally correct.

When it finishes, browse to `https://<your-domain>/roller` (or
`http://<host>/roller` in `:80` mode) and complete Roller's normal
first-run flow: register the first user account, which becomes the site
administrator, then create a weblog.

## TLS

Handled entirely by Caddy (`deploy/caddy/Caddyfile`, baked into the
`ghcr.io/jakefearsd/roller-caddy` image) — there is nothing to configure
beyond setting `SITE_DOMAIN` to a real domain in `.env`. Caddy
obtains a Let's Encrypt certificate for that domain on first use, renews it
automatically well before expiry, and redirects HTTP to HTTPS. Certificate
state lives in the `caddy-data` named volume, so it survives
`docker compose down` (but not `down -v`).

The app honors `X-Forwarded-Proto`/`X-Forwarded-For` from the reverse proxy
(`server.forward-headers-strategy=framework` in `application.properties`), so
absolute URLs the app generates — redirects, sitemap entries — use `https://`
even though Caddy talks plain HTTP to the container. Caddy
sets these headers automatically for proxied requests; there is nothing to
configure, and existing deployments pick this up on their next image update
with no action needed.

That header-forwarding filter only affects `getScheme()`/`isSecure()`/
`getRequestURL()` — it does **not** change what `HttpServletRequest
.getRemoteAddr()` reports. Without a second piece of configuration, every
request the app tier sees, from any reader anywhere on the internet, would
report Caddy's own container IP as its remote address, because Caddy — not
the reader — is the TCP peer. That collapses every per-client throttle
(contact form, newsletter subscribe, password-reset) onto one shared key,
so `server.tomcat.remoteip.remote-ip-header=x-forwarded-for` and
`server.tomcat.remoteip.protocol-header=x-forwarded-proto` (also in
`application.properties`) install Tomcat's `RemoteIpValve`, which rewrites
`getRemoteAddr()`/`getScheme()` from those headers before the request ever
reaches the app. Caddy is this stack's only ingress, and the valve only
trusts `X-Forwarded-For` from its default internal-proxies address ranges
(private/loopback), so an external client cannot spoof its way past a
throttle by forging the header itself — Caddy's own hop is the only one the
valve believes. The security-relevant case is the password-reset throttle,
which suppresses a flood of reset requests per source address; without the
valve it would suppress (or fail to suppress) based on Caddy's address
instead of the reader's.

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

## Analytics

The stack ships [Umami](https://umami.is): self-hosted, cookie-free page
analytics. It stores nothing on the reader's device, which is why no consent
banner is needed and why it is worth having at all.

### Why the tracker is served from your blog's own domain

Every bundled theme sends a Content-Security-Policy of `default-src 'none'`
with `script-src 'self'` and `connect-src 'self'`. A tracker loaded from
another hostname is therefore blocked by the reader's browser, and its beacon
never sends — you would see an empty dashboard and no error anywhere.

So Caddy serves the two pieces the policy governs from the blog's own origin:

| URL | What it is |
| --- | --- |
| `https://<your blog>/analytics/script.js` | the tracker |
| `https://<your blog>/analytics/api/send` | where it reports |

The dashboard is a separate hostname (`UMAMI_DOMAIN`), because Umami's UI
assumes it lives at a domain root — its `BASE_PATH` is set when the image is
built, and the prebuilt image does not carry one. No theme CSP applies to the
dashboard, so this costs nothing.

### Turning it on

1. Set `UMAMI_APP_SECRET` (`openssl rand -hex 32`) and, if you want the
   dashboard, `UMAMI_DOMAIN` in `.env`.
2. Run `./deploy.sh` (or `docker compose -f docker-compose.prod.yml up -d`).
   The `provision` service creates the `umami` database inside the existing
   Postgres instance if it is missing, and the nightly backup starts dumping
   it alongside the blog's own.
3. Open the dashboard and sign in with `admin` / `umami`. **Change that
   password immediately** — it is the documented default and the dashboard is
   on the public internet.
4. Add a website in Umami for each weblog and copy its website ID.

### Pointing a weblog at it

Analytics are per-weblog and opt-in, through a field dedicated to it —
**not** the free-text "Analytics tracking code" textarea that has always
lived on the same Settings page. That textarea only renders when *Server
Administration → Configuration → Allow analytics code override* is on **and**
`weblogAdminsUntrusted` is off — and this fork keeps `weblogAdminsUntrusted`
on everywhere (see `CLAUDE.md`), so the textarea has never actually been
reachable. If an earlier revision of this document told you to paste a
`<script>` snippet into it, that procedure never worked; disregard it.

The real steps, once [Turning it on](#turning-it-on) above has a website
registered in Umami:

1. In Umami, open the website and copy its **Website ID** — the bare UUID
   Umami shows under the website's settings, not the `<script>` snippet
   Umami also offers on the same screen (that snippet is the impossible
   textarea path above; ignore it).
2. In Roller, as the weblog's owner or a site admin: *Settings → Weblog
   Settings → Analytics website ID*, paste the UUID, save. Roller validates
   it as a UUID and rejects anything else.
3. Optionally also set *Analytics share URL* to Umami's public share link
   for the site. This is display-only — a convenience link shown back to the
   editor on the Settings page — and plays no part in tracking.

`#showAnalyticsTrackingCode` (called from every bundled theme's `<head>`)
builds the `<script defer src="…" data-website-id="…" data-host-url="…">`
tag itself from the validated UUID plus two startup properties
(`analytics.umami.basePath`, default `/analytics`; `analytics.umami.scriptName`,
default `script.js`) — nothing an operator or weblog admin types is ever
emitted as raw HTML into the page head. That is precisely what lets this
coexist with `weblogAdminsUntrusted` staying on: there is no admin-authored
markup anywhere in the path. If you
set `UMAMI_SCRIPT_NAME` to something other than `script.js` when deploying
(the cheapest defence against content blockers that match the default
path), also set `ROLLER_ANALYTICS_UMAMI_SCRIPTNAME` to the same value in
`.env` or the tag will
point at a script Caddy never serves.

Nothing is emitted for a weblog whose Analytics website ID is blank, so this
stays opt-in per blog and off until an admin sets it.

### The Grafana contract

Roller and Umami both expose their data to Grafana as small, versioned SQL
**views** — never raw tables, and Grafana is never granted anything beyond
`SELECT` on those views.

**Two databases, two view halves, and why.** `rollerdb` and Umami's own
database (`${UMAMI_DB:-umami}`) both live inside the one shared Postgres
instance, but PostgreSQL has no cross-database queries, so the contract is
split down that seam — each half lives with the data it reads:

| View | Lives in | Ships via | What it holds |
| --- | --- | --- | --- |
| `analytics_events` | `rollerdb` | `bin/db/migrations/V017__analytics_contract.sql` (the migration chain) | First-party outcomes from `roller_event` — form submissions, newsletter subscriptions, entry publishes — grouped by weblog handle, event type, day |
| `analytics_weblog_sites` | `rollerdb` | same migration | The join key: which Umami website UUID belongs to which weblog handle |
| `analytics_traffic` | `${UMAMI_DB:-umami}` | `deploy/analytics/umami-views.sql` (baked into the app image as `/app/umami-views.sql`), applied by the `provision` service's `provision.sh` (not the migration chain — it can only reach `rollerdb`) | Umami's raw `website_event` rolled up to sessions/views by website, path and day |

Grafana joins the two halves itself: two PostgreSQL datasources (one per
database, both authenticating as `grafana_ro`), with `analytics_traffic.website_id`
joined to `analytics_weblog_sites.website_id` using a panel-level join
transformation (Grafana's "Outer join" / "Join by field" transform) —
Postgres cannot do this join server-side because it spans two databases.

**`grafana_ro`: created `NOLOGIN`, enabled out of band.** `V017` creates the
role with `CREATE ROLE grafana_ro NOLOGIN` (guarded by a `DO $$ … EXCEPTION
WHEN duplicate_object …` block, since roles are cluster-global and the
migration chain re-applies on every deploy) and grants it `SELECT` on
exactly the contract views — never the underlying tables, never
`roller_user`'s privileges. A migration cannot carry a secret, so the role
ships with no password and cannot log in until you set one:

```bash
docker compose -f docker-compose.prod.yml exec postgres \
    psql -U "${POSTGRES_USER:-roller}" -d "${POSTGRES_DB:-rollerdb}" \
    -c "ALTER ROLE grafana_ro LOGIN PASSWORD 'choose-a-strong-password-here';"
```

The `provision` service already grants `grafana_ro` `CONNECT` on both
databases (see `deploy/provision.sh`), so the one role/password pair you set
above works for both Grafana datasources — just point one at `rollerdb` and
the other at `${UMAMI_DB:-umami}`, same credentials.

**Access is tunnel-only.** Postgres never gets a published host port in this
stack (see [Firewall](#firewall) — port 5432 is reachable only on the
internal Docker network). Point Grafana's datasources at an SSH tunnel or a
bastion into the host, never at a directly exposed port; do not add a host
port mapping for `postgres` to make this easier.

**Two labels on `analytics_events` are untrusted display text, not
metadata.** `page_slug` and `entry_anchor` on `FORM_SUBMITTED` rows are
copied from the contact form's reader-controlled `source` field — a visitor
chooses that text, not Roller. Treat them as display strings in any
dashboard, never as something safe to interpolate elsewhere. Separately,
`ENTRY_PUBLISHED` counts publish **events**, not distinct published entries:
unpublishing and republishing an entry records a second event, so a
"posts published" panel built naively from this view will over-count
republished entries.

## Newsletter

The stack ships [listmonk](https://listmonk.app): a self-hosted mailing list
manager. It owns the subscriber list, the double opt-in flow, the sending and
the unsubscribe links. **Roller stores no subscriber data at all**, which is
what keeps consent and retention obligations out of the blog software.

### Turning it on

1. Set `LISTMONK_ADMIN_PASSWORD`, and — if you want subscribers to be able to
   confirm — `LISTMONK_DOMAIN` and `LISTMONK_ROOT_URL` in `.env`. The two must
   agree: listmonk builds its confirmation and unsubscribe links from
   `LISTMONK_ROOT_URL`, and those links are the only way a subscriber can
   confirm or leave.
2. Run `./deploy.sh` (or `docker compose -f docker-compose.prod.yml up -d`).
   The `provision` service creates the `listmonk` database, and listmonk
   creates its own schema on first start.
3. Sign in at `https://<LISTMONK_DOMAIN>/admin` with `admin` and the password
   you set.
4. **Set up SMTP** under *Settings → SMTP*. Nothing sends until you do; those
   credentials live in listmonk's own database, not in `.env`.
5. Create a list, set it to **double opt-in**, and copy its UUID.

`.env.example` already sets `ROLLER_NEWSLETTER_LISTMONK_BASEURL=http://listmonk:9000`
— that's the compose **service name**, reached over the internal Docker
network, not `LISTMONK_DOMAIN` (which is for a browser reaching the
opt-in/unsubscribe pages, not for Roller reaching the API). You normally
don't need to touch it; blank (the `roller.properties` built-in default,
what you'd have if the variable were unset entirely) is what makes
`/newsletter/subscribe` return 503 in local dev, where no listmonk service
exists — that's the intended fallback, not a bug.

### Putting a subscribe form on a blog

Two ways, both feeding the same weblog field (Weblog Settings → Newsletter
list UUID) and the same client-side injection
(`#showAudienceAssets`/`data-list-uuid`):

- The `[subscribe]` shortcode, dropped into any entry or page body.
- The shared template library's macro, for a fixed spot in a theme (a
  sidebar, a footer):

  ```velocity
  #showSubscribeForm($model.weblog "Get new guides by email")
  ```

  It takes the weblog, not a raw UUID — the macro reads
  `$weblog.newsletterListUuid` itself and renders nothing at all when that
  field is blank, so a theme can call it unconditionally on every page.

The form posts to `/newsletter/subscribe` **on the blog's own domain**, which
is required, not decorative: every bundled theme sends `connect-src 'self'`,
so a form posting to `newsletter.example.com` would be blocked by the
reader's browser and nothing would happen. **Roller itself serves that
endpoint** — `NewsletterController` throttles it, checks the honeypot,
refuses to forward a list uuid this install never configured, records a
first-party `NEWSLETTER_SUBSCRIBED` event on success, and only then forwards
to listmonk over the Docker network using `newsletter.listmonk.baseurl` from
above. This used to be a Caddy `rewrite` straight to listmonk's public API;
it is not anymore — `deploy/caddy/Caddyfile` now carries only a comment where
that rewrite used to be (`/newsletter/subscribe is served by the app itself
(throttle + events); do not re-add a rewrite here.`), because a path-specific
proxy rule in front of the app would silently skip both the throttle and the
event write with no error anywhere. The opt-in and unsubscribe pages still
stay on `LISTMONK_DOMAIN` — those are followed from an email client, where no
theme CSP applies, so routing them through the app buys nothing.

> **Deployments running an older Caddyfile:** the Caddyfile is baked into the
> `ghcr.io/jakefearsd/roller-caddy` image now, not read from a checkout, so
> picking up the current one means pulling a newer `IMAGE_VERSION` (see
> [Upgrades](#upgrades)) — bump it in `.env` and run `./deploy.sh`. Until you
> do, an old rewrite from before this design keeps forwarding subscribe
> requests straight to listmonk — Roller's own `/newsletter/subscribe` route
> is registered and correct underneath, but Caddy never lets a request reach
> it, so the throttle and the `roller_event` write are silently bypassed the
> whole time the stale image is running.

A subscriber who is already on the list gets the same "check your email"
message as a new one. That is deliberate: a different message would let anyone
use the form to test whether a given address is subscribed.

### Sending

Compose and send one-off campaigns from listmonk's own console, or use the
entry editor's **"Send as newsletter"** button — a synchronous, one-shot send
to the weblog's configured list, stamping `newsletterSentAt` on the entry so
it cannot be sent twice. The button needs a *second*, higher-privilege
credential than the public subscribe endpoint above: a listmonk **API user**.

1. In the listmonk console: **Admin → Users → + New**.
2. Give it the **Super Admin** role (or a custom role with campaign
   create/manage permissions) and set **Type** to *API*.
3. Generate a token and copy both the username and the token immediately —
   listmonk shows the token exactly once.
4. Put both in `.env`:

   ```bash
   ROLLER_NEWSLETTER_LISTMONK_APIUSER=your-api-username
   ROLLER_NEWSLETTER_LISTMONK_APITOKEN=the-generated-token
   ```

5. Redeploy (`./deploy.sh`). Leaving either blank disables only the "Send as newsletter"
   button (`newsletter.notConfigured`) — the public subscribe form keeps
   working regardless, since `ListmonkClient` checks the two credential tiers
   (`isUnconfigured()` for subscribe, `isCampaignConfigured()` for sending)
   independently.

Sending is deliberately manual and synchronous, not queued: an editor clicks
the button, the campaign sends in that same request, and
`weblogentry.newsletter_sent_at` is stamped only once listmonk confirms — the
editor who clicked IS the retry mechanism, since there is no background queue
to retry on their behalf.

> **Not built:** triggering a campaign automatically when a post is
> published. The per-blog list mapping and the API client both exist now
> (above); what's still missing is a retry queue and a hook on *both* publish
> paths (the editor's and the scheduler's — a hook on only the first silently
> skips every scheduled post). Until that exists, publishing a post and
> sending it as a newsletter stay two deliberate, separate acts.

## Backup and restore

The `backup` service (the app image, which bakes in `postgresql-client` for
`pg_dump`/`psql` alongside `tar` from the base image) runs
`/app/backup/loop.sh` (baked in from `deploy/backup/loop.sh`), a cron-less
scheduler that wakes hourly and, once a day at `BACKUP_HOUR` (UTC), runs
`/app/backup/backup.sh` (`deploy/backup/backup.sh`). It runs as root (`user:
"0:0"` in `docker-compose.prod.yml`), unlike every other use of the image —
the `roller-backups` volume is root-owned, and the image's unprivileged
`roller` user cannot write into it. Each cycle:

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
docker compose -f docker-compose.prod.yml exec backup /app/backup/backup.sh
docker compose -f docker-compose.prod.yml exec backup ls -la /backups
```

### Restore

Full restore commands (with exact flags) live in the header comment of
`deploy/backup/backup.sh` (baked into the image at `/app/backup/backup.sh`)
— read that before restoring for real. Summary:

**Database** (app must be stopped first):

The restore command must be run as a single-quoted `bash -c '...'` INSIDE
the postgres container, exactly like below — `$POSTGRES_USER` /
`$POSTGRES_PASSWORD` / `$POSTGRES_DB` only exist in the postgres container's
own environment (set by `docker-compose.prod.yml`). A double-quoted or
unquoted command would instead expand them in your host shell, where
they're unset, and `pg_restore` would silently get empty values (this is
the same in-container pattern `provision.sh` uses for migrations):

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
derived from the directory the compose file lives in (`roller` if you put
the deploy files in `/opt/roller` per [Get the deploy files onto the
host](#get-the-deploy-files-onto-the-host)), giving e.g.
`roller_roller-mediafiles`. Run `docker volume ls` FIRST to confirm the
exact names on your host and substitute them below if they differ:

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

## Test a release locally before deploying it

The deploy files and images are the same in both places; only `.env` differs.
On your workstation, in a directory holding the same two files:

```bash
IMAGE_VERSION=6.2.1 docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
```

with a local `.env` carrying `SITE_DOMAIN=:80`, `UMAMI_DOMAIN=:8081`,
`LISTMONK_DOMAIN=:8082`, `LISTMONK_ROOT_URL=http://localhost:8082` and
throwaway secrets. The blog is at `http://localhost/roller`, the analytics
console at `http://localhost:8081`, the newsletter admin at
`http://localhost:8082`.

This pulls the exact images the server will pull, so it is the only form of
local testing that is byte-identical to production. Building locally
(`docker build -t ghcr.io/jakefearsd/roller:test .`) is faster and catches
nearly everything, but CI runs its own build, so those bytes are merely
equivalent rather than identical.

## Upgrades

Edit `IMAGE_VERSION` in `.env` to the new release's version, then:

```bash
./deploy.sh           # pull the images and deploy, or:
./deploy.sh --prune   # the above, plus `docker image prune -f` after
```

(equivalently, `docker compose -f docker-compose.prod.yml pull && docker
compose -f docker-compose.prod.yml up -d`.) There is no `git pull` — the host
holds no checkout to update, only the two files from [Get the deploy files
onto the host](#get-the-deploy-files-onto-the-host), and neither one changes
between releases.

This is the same script used for [first run](#first-run) — running it again
is safe and idempotent. Each run: pulls the images, brings the stack up (which
re-runs the now-current `provision` service — a no-op if every migration is
already applied, since `migrate.sh` tracks what's applied in
`schema_migrations`), and waits for `app` to report healthy (up to 120s,
exits non-zero if it doesn't). Nothing is silently swallowed: a failed pull
or an app that never goes healthy both cause `deploy.sh` to exit non-zero
rather than leaving the stack in a half-upgraded state without telling you.

Always have a recent, verified backup before upgrading across a schema
change (see [Backup and restore](#backup-and-restore)).

## Firewall

Only 80 and 443 need to be open to the internet. Caddy also publishes
`127.0.0.1:8081` and `127.0.0.1:8082` (the local-testing path to the Umami
and listmonk consoles — see [Test a release locally before deploying
it](#test-a-release-locally-before-deploying-it)), but those are
loopback-bound, unreachable from outside the host by construction, and need
no firewall rule either way. Nothing else in `docker-compose.prod.yml`
publishes a host port by default (`postgres` and the app's `8080`/`8090` are
reachable only on the internal Docker network), so there's nothing else to
explicitly block on a default-deny host firewall, but locking it down
explicitly is still good practice:

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
docker compose -f docker-compose.prod.yml logs provision
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
(`WebloggerFactory.bootstrap()`), and it won't even start until the
`provision` service has exited successfully — check
`docker compose -f docker-compose.prod.yml logs provision` first. If
provisioning succeeded, confirm `.env`'s
`ROLLER_DATABASE_JDBC_USERNAME`/`ROLLER_DATABASE_JDBC_PASSWORD` match
`POSTGRES_USER`/`POSTGRES_PASSWORD` exactly — the two are not templated
against each other. Re-running `./deploy.sh` (or `docker compose up -d`) is
safe and idempotent if you want to retry provisioning from scratch.

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
