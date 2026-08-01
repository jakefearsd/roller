# Stage 1C — Production Docker Stack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A real production deployment: `docker compose up` on a fresh VPS yields a TLS-terminated, backed-up Roller; CI builds and publishes the image to GHCR on push to master.

**Architecture:** Multi-stage Dockerfile building THIS tree (Temurin 25 build → JRE 25 runtime, non-root, executable WAR); production `docker-compose.prod.yml` with services `app`, `postgres:16`, `caddy` (auto-TLS), `backup` (nightly pg_dump + volume snapshots, rotation); named volumes for pgdata/media/search-index/uploads; healthchecks via the management port; `deploy/deploy.sh` one-command update (pull, migrate, restart, verify health); GHCR publish job in CI. The existing DEV `docker-compose.yml` (postgres-only, port 5433) stays as-is minus its dead `full` profile. `docker_deployment.md` is replaced with docs matching reality.

**Runtime contract (established in 1B — normative for this plan):**
- App: `java -jar roller.war`; app config via `-Droller.custom.config=/config/roller-production.properties`; server port 8080, context `/roller`; health at `http://app:8090/actuator/health` (management port — never published on the host); dirs `themes.dir` (bake themes INTO the image at `/app/themes` by copying `app/src/main/webapp/themes`), `mediafiles.storage.dir=/data/mediafiles`, `search.index.dir=/data/search-index`, `uploads.dir=/data/uploads`; DB via `database.configurationType=jdbc` + `database.jdbc.*`; mail via `mail.configurationType=properties` + SMTP keys (operator-provided).
- Migrations: `bin/db/migrate.sh` (psql-based) — the deploy script runs it against the postgres container before app restart; ship psql in the backup/deploy tooling container or run via `docker exec` into postgres with the SQL mounted.

## Global Constraints

- Do not touch app code; this plan is packaging/infra + docs + CI only (exception: none anticipated — if one appears, STOP and report).
- Secrets NEVER in the repo: compose reads `.env` (gitignored; ship `.env.example` with every key documented). The Caddy domain, DB password, SMTP creds are operator-supplied.
- Images pinned by digest or full version tag (postgres:16 already digest-pinned in dev compose — same discipline).
- CI: publish `ghcr.io/<owner>/roller:{sha,latest}` only on push to master after tests pass (needs `packages: write` permission; derive owner from `github.repository`).
- FOREGROUND builds in subagents; docker builds are allowed (they're not Maven). Local verification uses throwaway ports/volumes and cleans up completely.
- Commits per task with the repo's trailers.

## Tasks

### Task 1: Production Dockerfile
**Files:** replace `Dockerfile`; delete `docker/` dir if its entrypoint scripts are obsolete (read them first — port anything still relevant into the new entrypoint); add `.dockerignore`.
- Multi-stage: `maven:3.9-eclipse-temurin-25` (verify exact current tag on Docker Hub; fall back to eclipse-temurin-25 + install maven) building with `-DskipTests` from the LOCAL context (never git clone); stage 2 `eclipse-temurin:25-jre`, non-root user `roller`, `COPY` the repackaged WAR + `app/src/main/webapp/themes` → `/app/themes` + `bin/db/migrations` → `/app/migrations` (for reference/exec-based migration), volumes `/data`, ENTRYPOINT `java -jar /app/roller.war` with `JAVA_OPTS` passthrough and `-Droller.custom.config=/config/roller-production.properties`.
- `.dockerignore`: target dirs, .git, .claude, .superpowers, docs, it-selenium/target, logs.
- Verify: `docker build -t roller-test .` succeeds; `docker run` with a minimal mounted config against a throwaway postgres container → health UP via a `docker exec curl` on 8090; login.rol 200 from the host on a mapped port. Clean up containers/volumes/image.
- Commit: "Build a production image from this tree".

### Task 2: Production compose stack
**Files:** create `docker-compose.prod.yml`, `deploy/.env.example`, `deploy/config/roller-production.properties.example`, `deploy/caddy/Caddyfile`; trim the dev `docker-compose.yml` `full` profile (dead — references the old broken Dockerfile).
- Services: `app` (image from GHCR or local build, depends_on postgres healthy, healthcheck curl 8090 internally, restart unless-stopped, volumes: config ro + data), `postgres:16` (named volume, healthcheck pg_isready, no host port by default), `caddy` (80/443 published, Caddyfile `{$ROLLER_DOMAIN} { reverse_proxy app:8080 }`, volumes for caddy data/config), `backup` (postgres:16 image reusing pg_dump, cron-loop shell: nightly `pg_dump -Fc` to `/backups` + tar of media/search/uploads volumes, 14-day rotation; document restore commands in the file header).
- Properties example: every key the runtime contract needs, commented.
- Verify locally: `docker compose -f docker-compose.prod.yml --env-file deploy/.env.test up -d` with a test env (domain-less caddy config variant using `:80`), wait health, curl login.rol through caddy :80, run one backup cycle manually (`docker compose exec backup /backup.sh`), assert dump file exists, tear down with volumes. Cleanup verified.
- Commit: "Add the production compose stack".

### Task 3: Deploy script + migration path
**Files:** create `deploy/deploy.sh`; extend `bin/db/migrate.sh` docs header if needed.
- `deploy.sh`: `set -euo pipefail`; steps: pull new image (or build), `docker compose ... up -d postgres` + wait healthy, run migrations (`docker compose exec -T postgres psql ...` loop over `/app/migrations` mounted from the image via a one-off `docker compose run --rm app` with a small migrate entry mode? — simplest correct: run a one-off container from the app image executing a bundled `migrate.sh` adapted to in-container psql absence → DECISION: bundle migrations SQL in the image (done in Task 1) and execute them via `docker compose exec -T postgres psql` with the SQL piped from `docker compose run --rm app cat ...`; implement whichever is simplest and PROVE it applies a pending migration in the local stack), then `up -d app`, poll app health via `docker compose exec app curl 8090` until UP (120s), `docker image prune -f` optional flag.
- Verify: full cycle against the Task 2 local stack, including a no-op migration run (idempotency) and a real one (create a scratch V999 migration in the test env only — do NOT commit it — prove it applies, then reset the test volume).
- Commit: "Add one-command deploy".

### Task 4: CI image publish
**Files:** `.github/workflows/main.yml` (new job `publish-image`, needs build-test + integration-test, push-to-master only, `permissions: packages: write`, docker/login-action + build-push-action or plain docker CLI, tags `ghcr.io/${{ github.repository }}:latest` + `:${{ github.sha }}`).
- YAML validate; note in the job comments that the first run proves it (cannot test locally beyond `docker build`).
- Commit: "Publish the production image from CI".

### Task 5: Docs + final verification
**Files:** replace `docker_deployment.md` (fresh-VPS runbook: prerequisites, .env setup, first-run `docker compose up`, DNS/TLS, backup/restore, upgrade via deploy.sh, health monitoring incl. management-port note); update `CLAUDE.md` deployment section + README deployment paragraph (ONLY the deployment paragraph — full README rewrite is Stage 1D); ledger any leftovers.
- Final battery: fresh `docker build`, full local prod-stack cycle (up → health → login page through caddy → backup → deploy.sh rerun → down -v), plus `mvn -ntp -pl app test` to prove no accidental app changes.
- Commit: "Document the production deployment".

## Self-Review
- Spec Stage 1 step 3 coverage: Dockerfile(T1), compose app+postgres+caddy+backup(T2), deploy.sh(T3), CI publish(T4), docs replacing the fictional stack(T5). Healthchecks via management port per 1B's final state.
- Risks: base-image tag names verified at implementation; migration-execution mechanics get an explicit prove-it step (T3); GHCR first-publish only provable in CI — flagged.
- No placeholders: the one open implementation choice (migration piping mechanics) is bounded with a decision rule and a proof requirement.
