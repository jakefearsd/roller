# Container-Push Production Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the two published container images the complete deployment artifact, so a production host needs only `docker-compose.prod.yml` and `.env`, and the image tested locally is the one deployed.

**Architecture:** App configuration moves from a bind-mounted properties file to a `ROLLER_*` environment overlay inside `WebloggerConfig`. The three host-side deploy steps (create service databases, migrate, apply analytics views) move into a `provision.sh` baked into the app image and run as a one-shot compose service, with `depends_on: service_completed_successfully` replacing `deploy.sh`'s bash ordering. The Caddyfile and backup scripts stop being bind mounts and become image content.

**Tech Stack:** Java 25 / Maven / JUnit 6 (Jupiter), Docker + Compose v2.17+, PostgreSQL 16, Caddy 2, GitHub Actions, GHCR.

**Spec:** `docs/superpowers/specs/2026-08-14-container-push-deployment-design.md`

## Global Constraints

- Registry stays GHCR. Images: `ghcr.io/jakefearsd/roller` and `ghcr.io/jakefearsd/roller-caddy`.
- `.env` is the *only* file that differs between local and production. No new host-side files.
- Compose v2.17+ required (`service_completed_successfully`).
- `postgresql-client` must be **16 or newer** — `pg_dump -Fc` refuses a server newer than the client, and the stack runs PostgreSQL 16.
- `installation.type` stays `auto`. Migrations continue to be applied by `bin/db/migrate.sh`, unmodified.
- Environment variable prefix for app configuration is exactly `ROLLER_`. Infrastructure variables must **not** use that prefix.
- `bin/db/migrate.sh` is copied into the image unmodified. It resolves its migrations directory as `$(dirname $0)/migrations`, so it must land at `/app/migrate.sh` with migrations at `/app/migrations`.
- Every task ends with a commit step. **This repo's CLAUDE.md forbids committing unless the user explicitly asks.** Treat each commit step as "ask, then commit" during execution.
- Tests run from the `app/` module directory; sibling-repo files are reached as `Paths.get("../deploy/...")` (established idiom — see `UmamiViewScriptTest`, `MigrationFiles`).
- `snakeyaml` 2.6 is already on the app's compile classpath. Do not add a YAML dependency.

---

## File Structure

**New files**
- `deploy/provision.sh` — the one-shot database provisioner. Sole responsibility: bring both service databases and the rollerdb schema to a current state, idempotently.
- `deploy/caddy/Dockerfile` — bakes the Caddyfile into a `caddy:2-alpine` derivative.
- `app/src/test/java/org/apache/roller/weblogger/config/WebloggerConfigEnvOverrideTest.java`
- `app/src/test/java/org/apache/roller/weblogger/business/startup/ProductionComposeTest.java` — sits beside `UmamiViewScriptTest`, the existing home for tests that police `deploy/` artifacts.

**Modified**
- `app/src/main/java/org/apache/roller/weblogger/config/WebloggerConfig.java` — the env overlay and secret masking.
- `Dockerfile`, `docker-compose.prod.yml`, `deploy/deploy.sh`, `deploy/.env.example`, `deploy/caddy/Caddyfile`, `.dockerignore`, `.github/workflows/release.yml`, `docker_deployment.md`, `CLAUDE.md`.

**Deleted**
- `deploy/config/roller-production.properties.example` and the `deploy/config/` directory.

**Untouched on purpose**
- `docker-compose.yml` (dev postgres), `./roller`, `bin/db/migrate.sh`, `deploy/backup/*.sh`, `deploy/analytics/umami-views.sql`.

---

### Task 1: Environment overlay in WebloggerConfig

`WebloggerConfig` loads properties files only; it has no environment support. The overlay logic must be a package-private static method taking an explicit `Map`, because `System.getenv()` cannot be modified from a test and the class's work happens in a static initializer that runs once per JVM.

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/config/WebloggerConfig.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/config/WebloggerConfigEnvOverrideTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `static void applyEnvironmentOverrides(Properties config, Map<String, String> env)` — package-private, mutates `config` in place.
  - `static String maskSecret(String key, String value)` — package-private, returns `"********"` when `key` contains `password`, `token`, or `secret` (case-insensitive) and `value` is non-empty; otherwise returns `value` unchanged.
  - Environment naming rule relied on by Tasks 5 and 6: `ROLLER_` + property key uppercased with `.` replaced by `_`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/apache/roller/weblogger/config/WebloggerConfigEnvOverrideTest.java`:

```java
/*
* Licensed to the Apache Software Foundation (ASF) under one or more
*  contributor license agreements.  The ASF licenses this file to You
* under the Apache License, Version 2.0 (the "License"); you may not
* use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.  For additional information regarding
* copyright in this work, please see the NOTICE file in the top level
* directory of this distribution.
*/
package org.apache.roller.weblogger.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the ROLLER_* environment overlay -- the mechanism that lets the
 * production image take its whole configuration from .env, with no
 * bind-mounted properties file (see
 * docs/superpowers/specs/2026-08-14-container-push-deployment-design.md).
 *
 * <p>The overlay is tested through an explicit Map rather than the real
 * environment because System.getenv() cannot be modified from a test and
 * WebloggerConfig does its work in a static initializer that runs once per
 * JVM.
 */
class WebloggerConfigEnvOverrideTest {

    private static Properties props(String... keysAndValues) {
        Properties p = new Properties();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            p.setProperty(keysAndValues[i], keysAndValues[i + 1]);
        }
        return p;
    }

    private static Map<String, String> env(String... keysAndValues) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return m;
    }

    @Test
    void environmentBeatsAValueAlreadyLoadedFromAFile() {
        Properties config = props("database.jdbc.username", "from-file");

        WebloggerConfig.applyEnvironmentOverrides(
                config, env("ROLLER_DATABASE_JDBC_USERNAME", "from-env"));

        assertEquals("from-env", config.getProperty("database.jdbc.username"));
    }

    @Test
    void aKnownKeyKeepsItsOriginalSpelling() {
        // database.jdbc.driverClass is camelCase in roller.properties. A naive
        // lowercase mapping would create a SECOND key that nothing ever reads,
        // and the override would appear to do nothing.
        Properties config = props("database.jdbc.driverClass", "org.h2.Driver");

        WebloggerConfig.applyEnvironmentOverrides(
                config, env("ROLLER_DATABASE_JDBC_DRIVERCLASS", "org.postgresql.Driver"));

        assertEquals("org.postgresql.Driver", config.getProperty("database.jdbc.driverClass"));
        assertNull(config.getProperty("database.jdbc.driverclass"),
                "must not create a lowercase twin of an existing key");
    }

    @Test
    void anUnknownKeyIsCreatedFromTheDerivedName() {
        // mail.port does not appear in roller.properties at all, and
        // uploads.dir is present only as a comment. An allowlist restricted to
        // keys with active defaults could not set either one.
        Properties config = new Properties();

        WebloggerConfig.applyEnvironmentOverrides(
                config, env("ROLLER_MAIL_PORT", "587",
                            "ROLLER_UPLOADS_DIR", "/data/uploads"));

        assertEquals("587", config.getProperty("mail.port"));
        assertEquals("/data/uploads", config.getProperty("uploads.dir"));
    }

    @Test
    void variablesWithoutThePrefixAreIgnored() {
        Properties config = new Properties();

        WebloggerConfig.applyEnvironmentOverrides(
                config, env("POSTGRES_PASSWORD", "hunter2",
                            "PATH", "/usr/bin",
                            "ROLLER_", "empty-name"));

        assertTrue(config.isEmpty(),
                "only ROLLER_<something> variables may reach the configuration");
    }

    @Test
    void anEmptyValueIsAppliedRatherThanSkipped() {
        // Clearing mail.username is a legitimate thing to express in .env.
        Properties config = props("mail.username", "someone");

        WebloggerConfig.applyEnvironmentOverrides(config, env("ROLLER_MAIL_USERNAME", ""));

        assertEquals("", config.getProperty("mail.username"));
    }

    @Test
    void keysDifferingOnlyInCaseAreRejected() {
        Properties config = props("some.key", "a", "some.KEY", "b");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> WebloggerConfig.applyEnvironmentOverrides(config, env()));

        assertTrue(thrown.getMessage().contains("differ only in case"), thrown.getMessage());
    }

    @Test
    void secretValuesAreMaskedForLogging() {
        assertEquals("********", WebloggerConfig.maskSecret("database.jdbc.password", "hunter2"));
        assertEquals("********", WebloggerConfig.maskSecret("newsletter.listmonk.apitoken", "abc"));
        assertEquals("********", WebloggerConfig.maskSecret("some.SECRET.thing", "abc"));
        assertEquals("smtp.example.com", WebloggerConfig.maskSecret("mail.hostname", "smtp.example.com"));
        assertEquals("", WebloggerConfig.maskSecret("database.jdbc.password", ""));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl app test -Dtest=WebloggerConfigEnvOverrideTest`
Expected: compilation failure — `applyEnvironmentOverrides` and `maskSecret` do not exist.

- [ ] **Step 3: Write minimal implementation**

In `WebloggerConfig.java`, add to the imports:

```java
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
```

Add the constant beside the existing `custom_jvm_param` field (around line 39):

```java
    private final static String env_prefix = "ROLLER_";

    private final static Pattern SECRET_KEY =
            Pattern.compile("password|token|secret", Pattern.CASE_INSENSITIVE);
```

Add these two methods after the private constructor (around line 159):

```java
    /**
     * Overlay {@code ROLLER_*} environment variables onto the loaded
     * configuration. This is the highest-precedence layer, which is what lets
     * the production image take its whole configuration from the deploy host's
     * .env with no properties file mounted (see
     * docs/superpowers/specs/2026-08-14-container-push-deployment-design.md).
     *
     * <p>The name mapping strips the prefix, lowercases, and turns {@code _}
     * into {@code .}. A derived name that matches an existing key
     * case-insensitively is written to that key's ORIGINAL spelling, so
     * {@code ROLLER_DATABASE_JDBC_DRIVERCLASS} reaches
     * {@code database.jdbc.driverClass} instead of quietly creating a
     * lowercase twin nothing reads. A derived name matching nothing is used
     * as-is, which is required rather than incidental: {@code mail.port} has no
     * entry in roller.properties and {@code uploads.dir} is commented out, so
     * an allowlist of keys with active defaults could set neither.
     *
     * <p>Package-private and taking an explicit map so it can be tested;
     * {@code System.getenv()} is unmodifiable and the caller below is a static
     * initializer that runs once per JVM.
     */
    static void applyEnvironmentOverrides(Properties config, Map<String, String> env) {

        Map<String, String> knownByLowerCase = new HashMap<>();
        for (String key : config.stringPropertyNames()) {
            String previous = knownByLowerCase.put(key.toLowerCase(Locale.ROOT), key);
            if (previous != null && !previous.equals(key)) {
                throw new IllegalStateException("Configuration keys '" + previous + "' and '"
                        + key + "' differ only in case, so an environment override cannot "
                        + "address them unambiguously.");
            }
        }

        // Sorted for deterministic behaviour when two variables derive the same key.
        for (Map.Entry<String, String> entry : new TreeMap<>(env).entrySet()) {
            String name = entry.getKey();
            if (!name.startsWith(env_prefix) || name.length() == env_prefix.length()) {
                continue;
            }
            String derived = name.substring(env_prefix.length())
                                 .toLowerCase(Locale.ROOT)
                                 .replace('_', '.');
            config.setProperty(knownByLowerCase.getOrDefault(derived, derived), entry.getValue());
        }
    }

    /**
     * Replace a secret's value with a fixed mask for logging. The debug dump
     * below prints every key and value, and the environment overlay makes
     * turning that on while diagnosing a configuration problem much more
     * likely.
     */
    static String maskSecret(String key, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return SECRET_KEY.matcher(key).find() ? "********" : value;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl app test -Dtest=WebloggerConfigEnvOverrideTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Wire the overlay into the static initializer**

In `WebloggerConfig.java`, immediately after the `-Droller.custom.config` block closes (the `}` ending the `if(env_file != null ...)` at line 107) and **before** the `config.expandedProperties` block at line 109, insert:

```java
            // Highest-precedence layer: ROLLER_* environment variables. Placed
            // before property expansion below so an env-supplied value can
            // still carry ${...} references.
            applyEnvironmentOverrides(config, System.getenv());
```

Then change the debug dump at line 151 from:

```java
                log.debug(key+"="+config.getProperty(key));
```

to:

```java
                log.debug(key+"="+maskSecret(key, config.getProperty(key)));
```

- [ ] **Step 6: Run the full config test package to check nothing regressed**

Run: `mvn -q -pl app test -Dtest='WebloggerConfig*Test,PromotedRuntimePropertyTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/config/WebloggerConfig.java \
        app/src/test/java/org/apache/roller/weblogger/config/WebloggerConfigEnvOverrideTest.java
git commit -m "config: let ROLLER_* environment variables override properties"
```

---

### Task 2: The provision script

Replaces the three host-side steps in `deploy/deploy.sh` (lines 89-190). Same commands, same order, same idempotency; it just runs inside a container that gets its database names from compose instead of from a shell that never sourced `.env`.

**Files:**
- Create: `deploy/provision.sh` (mode 755)

**Interfaces:**
- Consumes: `bin/db/migrate.sh`'s documented contract — `DB_NAME`, `DB_APP_USER`, `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD`.
- Produces: `/app/provision.sh` in the image (Task 3), run by the `provision` compose service (Task 5). Reads `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `UMAMI_DB`, `LISTMONK_DB`. Exits non-zero on any failure.

- [ ] **Step 1: Write the script**

Create `deploy/provision.sh`:

```bash
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
```

- [ ] **Step 2: Make it executable and syntax-check it**

```bash
chmod 755 deploy/provision.sh
bash -n deploy/provision.sh && echo "syntax OK"
```
Expected: `syntax OK`.

- [ ] **Step 3: Verify it fails loudly with no environment**

Run: `env -u POSTGRES_USER bash deploy/provision.sh; echo "exit=$?"`
Expected: an error naming `POSTGRES_USER` and a non-zero exit. This confirms the `:?` guards fire before any database work.

- [ ] **Step 4: Commit**

```bash
git add deploy/provision.sh
git commit -m "deploy: add provision.sh, the containerised database provisioner"
```

---

### Task 3: Bake everything into the app image

**Files:**
- Modify: `Dockerfile`
- Modify: `.dockerignore:32-38` (its comment claims `roller-production.properties` is bind-mounted at runtime, which stops being true)

**Interfaces:**
- Consumes: `deploy/provision.sh` (Task 2).
- Produces: image paths relied on by Task 5 — `/app/provision.sh`, `/app/backup/loop.sh`, `/app/migrate.sh`, `/app/migrations/`, `/app/umami-views.sql`. ENTRYPOINT no longer passes `-Droller.custom.config`.

- [ ] **Step 1: Update the runtime stage's package install**

In `Dockerfile`, replace the `RUN apt-get ...` block at lines 68-69 with:

```dockerfile
RUN apt-get update && apt-get install -y --no-install-recommends curl webp postgresql-client \
    && rm -rf /var/lib/apt/lists/*

# Separate RUN on purpose. Chaining this onto the install with `&& ... || exit`
# makes a failed apt-get report the pg_dump version message instead of its own,
# which sends you looking in the wrong place.
RUN pg_dump --version | grep -Eq 'PostgreSQL\) (1[6-9]|[2-9][0-9])' \
    || { echo "pg_dump must be 16 or newer: the stack runs PostgreSQL 16 and pg_dump refuses a server newer than itself" >&2; exit 1; }
```

Update the comment above it (lines 59-67) to add a third sentence:

```dockerfile
# postgresql-client provides psql/createdb for /app/provision.sh and pg_dump
# for the nightly backup loop -- this one image is the app, the provisioner and
# the backup runner, because those are exactly the roles needing the WAR or a
# PostgreSQL client. The version assertion is load-bearing: pg_dump refuses a
# server newer than itself, so a base-image change that dropped the client
# below 16 would break backups silently at 03:00 rather than at build time.
```

- [ ] **Step 2: Add the new COPYs and drop the /config directory**

Replace lines 75-84 of `Dockerfile` with:

```dockerfile
COPY --from=builder /build/app/target/roller.war /app/roller.war
COPY --from=builder /build/app/src/main/webapp/themes /app/themes
COPY --from=builder /build/bin/db/migrations /app/migrations

# migrate.sh resolves its migrations directory as $(dirname $0)/migrations, so
# it must sit beside /app/migrations. Copied unmodified from the build context:
# the deploy path and a manual ./bin/db/migrate.sh run must never disagree
# about what "applied" means.
COPY bin/db/migrate.sh /app/migrate.sh
COPY deploy/provision.sh /app/provision.sh
COPY deploy/analytics/umami-views.sql /app/umami-views.sql
COPY deploy/backup/backup.sh /app/backup/backup.sh
COPY deploy/backup/loop.sh /app/backup/loop.sh

# Runtime data: mediafiles, search index, uploads, all under /data per the
# runtime contract. There is no /config any more -- configuration arrives as
# ROLLER_* environment variables (see WebloggerConfig.applyEnvironmentOverrides).
RUN chmod 755 /app/migrate.sh /app/provision.sh /app/backup/backup.sh /app/backup/loop.sh \
    && mkdir -p /data/mediafiles /data/search-index /data/uploads \
    && chown -R roller:roller /app /data
```

- [ ] **Step 3: Drop `-Droller.custom.config` from the ENTRYPOINT**

Replace lines 92-95 with:

```dockerfile
# Shell form so JAVA_OPTS expands. No -Droller.custom.config: configuration
# comes from ROLLER_* environment variables, which compose supplies from .env.
# WebloggerConfig still honours the flag if an operator sets it in JAVA_OPTS.
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar /app/roller.war"]
```

- [ ] **Step 4: Update the Dockerfile header comment**

Replace lines 30-39 (the "Run:" paragraph) with:

```dockerfile
# Run: java -jar roller.war (Boot's embedded Tomcat serves the app at /roller
# on 8080, and a second embedded connector serves /actuator/health on the
# management port 8090 -- see app/src/main/resources/application.properties).
# Runtime config is supplied entirely by ROLLER_* environment variables, which
# WebloggerConfig overlays on top of its built-in defaults; at minimum set
# ROLLER_DATABASE_CONFIGURATIONTYPE, ROLLER_DATABASE_JDBC_*, and the
# ROLLER_MAIL_* keys. See deploy/.env.example for the full set.
```

- [ ] **Step 5: Fix the `.dockerignore` comment**

Replace `.dockerignore` lines 32-38 with:

```
# Secrets -- .env is read only by docker compose on the host and is never
# COPYd by the Dockerfile. Kept out of the build context as defense-in-depth
# against a future Dockerfile change accidentally COPYing it in.
/.env
```

- [ ] **Step 6: Build the image and verify its contents**

```bash
docker build -t roller:plan-check .
docker run --rm --entrypoint sh roller:plan-check -c '
  set -e
  pg_dump --version
  for f in /app/roller.war /app/provision.sh /app/migrate.sh \
           /app/umami-views.sql /app/backup/loop.sh /app/backup/backup.sh; do
    test -x "$f" -o -f "$f" || { echo "MISSING $f"; exit 1; }
  done
  test -d /app/migrations && ls /app/migrations | tail -1
  test ! -d /config && echo "no /config, good"
  echo OK'
```
Expected: a `pg_dump (PostgreSQL) 16.x` line or newer, `V025__entry_trash.sql`, `no /config, good`, `OK`.

- [ ] **Step 7: Commit**

```bash
git add Dockerfile .dockerignore
git commit -m "docker: bake provisioner, migrator and backup scripts into the app image"
```

---

### Task 4: The Caddy image

**Files:**
- Create: `deploy/caddy/Dockerfile`
- Modify: `deploy/caddy/Caddyfile:12` (`{$ROLLER_DOMAIN}` becomes `{$SITE_DOMAIN}`)

**Interfaces:**
- Produces: `ghcr.io/jakefearsd/roller-caddy`, built with **context `.` (repo root)** and `-f deploy/caddy/Dockerfile`. Reads `SITE_DOMAIN`, `UMAMI_DOMAIN`, `LISTMONK_DOMAIN` at runtime.

- [ ] **Step 1: Create the Dockerfile**

Create `deploy/caddy/Dockerfile`:

```dockerfile
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  The ASF licenses this file to You
# under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# The reverse proxy with its config baked in, so a production host needs no
# checkout to bind-mount a Caddyfile from. Build from the REPO ROOT:
#   docker build -t ghcr.io/jakefearsd/roller-caddy:<tag> -f deploy/caddy/Dockerfile .
FROM caddy:2-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648

COPY deploy/caddy/Caddyfile /etc/caddy/Caddyfile
```

- [ ] **Step 2: Rename the domain placeholder in the Caddyfile**

In `deploy/caddy/Caddyfile`, change line 12 from `{$ROLLER_DOMAIN} {` to `{$SITE_DOMAIN} {`, and update the header comment at lines 3-4 from `{$ROLLER_DOMAIN} is substituted from the ROLLER_DOMAIN environment variable` to:

```
# {$SITE_DOMAIN} is substituted from the SITE_DOMAIN environment variable (see
# deploy/.env.example). It is deliberately NOT named ROLLER_DOMAIN any more:
# that prefix is reserved for app configuration, which the app container takes
# wholesale from .env via WebloggerConfig's environment overlay, and a stray
# ROLLER_DOMAIN would land there as a junk property named "domain".
```

- [ ] **Step 3: Build and verify the config parses**

```bash
docker build -t roller-caddy:plan-check -f deploy/caddy/Dockerfile .
docker run --rm -e SITE_DOMAIN=":80" -e UMAMI_DOMAIN=":8081" -e LISTMONK_DOMAIN=":8082" \
    roller-caddy:plan-check caddy validate --config /etc/caddy/Caddyfile
```
Expected: `Valid configuration`.

- [ ] **Step 4: Commit**

```bash
git add deploy/caddy/Dockerfile deploy/caddy/Caddyfile
git commit -m "docker: bake the Caddyfile into a roller-caddy image"
```

---

### Task 5: Rewrite docker-compose.prod.yml

**Files:**
- Modify: `docker-compose.prod.yml` (whole file)
- Test: `app/src/test/java/org/apache/roller/weblogger/business/startup/ProductionComposeTest.java`

**Interfaces:**
- Consumes: image paths from Task 3, the caddy image from Task 4, `SITE_DOMAIN` from Task 4.
- Produces: the `IMAGE_VERSION`, `SITE_DOMAIN` variable names Task 6 writes into `.env.example`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/apache/roller/weblogger/business/startup/ProductionComposeTest.java`:

```java
/*
* Licensed to the Apache Software Foundation (ASF) under one or more
*  contributor license agreements.  The ASF licenses this file to You
* under the Apache License, Version 2.0 (the "License"); you may not
* use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.  For additional information regarding
* copyright in this work, please see the NOTICE file in the top level
* directory of this distribution.
*/
package org.apache.roller.weblogger.business.startup;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the production stack's shape: the deploy host holds
 * docker-compose.prod.yml and .env and nothing else (see
 * docs/superpowers/specs/2026-08-14-container-push-deployment-design.md).
 *
 * <p>Reintroducing a bind mount is the single change that would silently undo
 * that -- the stack would keep working on a developer's machine, which has the
 * checkout, and fail only on a host that does not. Same reasoning as
 * {@link UmamiViewScriptTest} policing deploy/analytics from a unit test.
 */
class ProductionComposeTest {

    private static final Path COMPOSE = Paths.get("../docker-compose.prod.yml");

    private static Map<String, Object> compose;

    @SuppressWarnings("unchecked")
    @BeforeAll
    static void load() throws IOException {
        assertTrue(Files.exists(COMPOSE), "missing " + COMPOSE.toAbsolutePath());
        try (InputStream in = Files.newInputStream(COMPOSE)) {
            compose = (Map<String, Object>) new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> services() {
        return (Map<String, Object>) compose.get("services");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) {
        Map<String, Object> svc = (Map<String, Object>) services().get(name);
        assertNotNull(svc, "no '" + name + "' service in " + COMPOSE);
        return svc;
    }

    @Test
    @SuppressWarnings("unchecked")
    void noServiceBindMountsARepositoryPath() {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, Object> entry : services().entrySet()) {
            Object volumes = ((Map<String, Object>) entry.getValue()).get("volumes");
            if (!(volumes instanceof List<?> list)) {
                continue;
            }
            for (Object volume : list) {
                String spec = String.valueOf(volume);
                String source = spec.split(":", 2)[0];
                // A named volume's source is a bare name; a bind mount's is a path.
                if (source.startsWith(".") || source.startsWith("/")) {
                    offenders.add(entry.getKey() + ": " + spec);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "the production host has no checkout, so nothing may be bind-mounted from one: "
                        + offenders);
    }

    @Test
    void noServiceBuildsFromSource() {
        for (Map.Entry<String, Object> entry : services().entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> svc = (Map<String, Object>) entry.getValue();
            assertTrue(svc.get("build") == null,
                    entry.getKey() + " declares build:, but the production host has no build "
                            + "context -- images come from the registry");
        }
    }

    @Test
    void provisionRunsOnceAndGatesTheServicesThatNeedASchema() {
        assertEquals("no", String.valueOf(service("provision").get("restart")),
                "provision is a one-shot job, not a restarting service");

        for (String dependent : List.of("app", "umami", "listmonk")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> dependsOn =
                    (Map<String, Object>) service(dependent).get("depends_on");
            assertNotNull(dependsOn, dependent + " must declare depends_on");
            @SuppressWarnings("unchecked")
            Map<String, Object> condition = (Map<String, Object>) dependsOn.get("provision");
            assertNotNull(condition, dependent + " must wait for provision");
            assertEquals("service_completed_successfully", condition.get("condition"),
                    dependent + " must start only after provisioning succeeds -- this is what "
                            + "replaced deploy.sh's migrate-then-start ordering");
        }
    }

    @Test
    void onlyCaddyPublishesNonLoopbackPorts() {
        for (Map.Entry<String, Object> entry : services().entrySet()) {
            @SuppressWarnings("unchecked")
            Object ports = ((Map<String, Object>) entry.getValue()).get("ports");
            if (ports == null) {
                continue;
            }
            assertEquals("caddy", entry.getKey(), "only caddy may publish ports");
            for (Object port : (List<?>) ports) {
                String spec = String.valueOf(port);
                boolean publicPort = spec.startsWith("80:") || spec.startsWith("443:");
                assertTrue(publicPort || spec.startsWith("127.0.0.1:"),
                        "only 80 and 443 may be reachable off-host; " + spec + " is neither "
                                + "those nor loopback-bound");
            }
        }
    }

    @Test
    void theAppTakesItsConfigurationFromEnvFile() {
        assertEquals(".env", String.valueOf(service("app").get("env_file")),
                "the app's whole configuration arrives as ROLLER_* variables from .env");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl app test -Dtest=ProductionComposeTest`
Expected: FAIL — the current file bind-mounts `./deploy/config/...`, declares `build:`, and has no `provision` service.

- [ ] **Step 3: Rewrite the compose file**

Replace the `services:` block of `docker-compose.prod.yml`. Header comment first (replacing lines 17-34):

```yaml
# Production stack for Roller: app + postgres + caddy (TLS-terminating reverse
# proxy) + umami (analytics) + listmonk (newsletter) + backup.
#
# This file and .env are the ONLY things a production host needs. Nothing is
# bind-mounted from a checkout: the Caddyfile lives in the roller-caddy image,
# and provision.sh, migrate.sh, the migrations, umami-views.sql and the backup
# scripts all live in the roller image. ProductionComposeTest fails the build
# if a bind mount comes back.
#
# Requires Compose v2.17+ for depends_on: service_completed_successfully.
#
# First-time setup (see docker_deployment.md for the full runbook):
#   curl -LO <release-url>/docker-compose.prod.yml
#   curl -L  <release-url>/.env.example -o .env
#   # edit .env: real domain, real secrets, real SMTP
#   docker compose -f docker-compose.prod.yml up -d
#
# Only caddy publishes off-host ports (80/443). 8081/8082 are loopback-bound
# and inert in production -- they exist so the same file serves a local run
# where UMAMI_DOMAIN/LISTMONK_DOMAIN are bare ports instead of hostnames.
```

Then the services, in this order:

```yaml
services:

  postgres:
    image: postgres:16@sha256:33f923b05f64ca54ac4401c01126a6b92afe839a0aa0a52bc5aeb5cc958e5f20
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-rollerdb}
      POSTGRES_USER: ${POSTGRES_USER:-roller}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set in .env}
    volumes:
      - roller-pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:-roller} -d ${POSTGRES_DB:-rollerdb}"]
      interval: 5s
      timeout: 5s
      retries: 20
    restart: unless-stopped
    networks:
      - internal

  # One-shot: creates the umami/listmonk databases, applies the rollerdb
  # migration chain, grants grafana_ro, and installs the analytics views. Every
  # step is idempotent, so this runs on every `up -d` and is a no-op once
  # current. app/umami/listmonk wait for it to EXIT SUCCESSFULLY, which is what
  # replaced deploy.sh's hand-rolled migrate-then-start ordering.
  provision:
    image: ghcr.io/jakefearsd/roller:${IMAGE_VERSION:?IMAGE_VERSION must be set in .env}
    entrypoint: ["/app/provision.sh"]
    restart: "no"
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-rollerdb}
      POSTGRES_USER: ${POSTGRES_USER:-roller}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set in .env}
      UMAMI_DB: ${UMAMI_DB:-umami}
      LISTMONK_DB: ${LISTMONK_DB:-listmonk}
    networks:
      - internal

  app:
    image: ghcr.io/jakefearsd/roller:${IMAGE_VERSION:?IMAGE_VERSION must be set in .env}
    depends_on:
      provision:
        condition: service_completed_successfully
    # The app's entire configuration is the ROLLER_* block in .env, overlaid on
    # the image's built-in defaults by WebloggerConfig. Everything else in .env
    # simply has no ROLLER_ prefix and is ignored by the app.
    env_file: .env
    environment:
      JAVA_OPTS: ${JAVA_OPTS:-}
    volumes:
      - roller-mediafiles:/data/mediafiles
      - roller-search-index:/data/search-index
      - roller-uploads:/data/uploads
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8090/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 60s
    restart: unless-stopped
    networks:
      - internal

  caddy:
    image: ghcr.io/jakefearsd/roller-caddy:${IMAGE_VERSION:?IMAGE_VERSION must be set in .env}
    depends_on:
      - app
      - umami
      - listmonk
    ports:
      - "80:80"
      - "443:443"
      # Inert in production: with real hostnames in UMAMI_DOMAIN/LISTMONK_DOMAIN
      # Caddy serves those sites on 443 and nothing listens here. Locally those
      # variables are ":8081"/":8082" and these are how you reach the consoles.
      # Loopback-bound either way, so the 80/443-only rule still holds.
      - "127.0.0.1:8081:8081"
      - "127.0.0.1:8082:8082"
    environment:
      SITE_DOMAIN: ${SITE_DOMAIN:?SITE_DOMAIN must be set in .env (a real domain, or :80 for LAN/testing)}
      UMAMI_DOMAIN: ${UMAMI_DOMAIN:-analytics.invalid}
      LISTMONK_DOMAIN: ${LISTMONK_DOMAIN:-newsletter.invalid}
    volumes:
      - caddy-data:/data
      - caddy-config:/config
    restart: unless-stopped
    networks:
      - internal

  umami:
    # Digest-pinned like postgres and caddy. deploy.sh pulls on every run, so a
    # moved tag could otherwise swap umami out mid-deploy; postgresql-latest is
    # a floating tag and this stack's other third-party images are all pinned.
    image: docker.umami.is/umami-software/umami@sha256:87312d334d009ee67ee0d2fba8fed01435547cc468e452243aef5133a9984d48
    depends_on:
      provision:
        condition: service_completed_successfully
    environment:
      DATABASE_URL: postgresql://${POSTGRES_USER:-roller}:${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set in .env}@postgres:5432/${UMAMI_DB:-umami}
      DATABASE_TYPE: postgresql
      APP_SECRET: ${UMAMI_APP_SECRET:?UMAMI_APP_SECRET must be set in .env (openssl rand -hex 32)}
      CLIENT_IP_HEADER: X-Forwarded-For
      DISABLE_TELEMETRY: "1"
      TRACKER_SCRIPT_NAME: ${UMAMI_SCRIPT_NAME:-script.js}
    restart: unless-stopped
    networks:
      - internal

  listmonk:
    # v6.2.0, digest-pinned. The previous value was `listmonk/listmonk:v3`,
    # which DOES NOT EXIST on Docker Hub -- `docker compose pull` failed on it,
    # so a fresh production deploy was already broken before this wave. The
    # oldest tag still published is v6.0.0. The --install/--idempotent/
    # --upgrade/--yes flags below are unchanged in v6.2.0 (verified against the
    # image's own --help), so the command block needs no edit; listmonk runs
    # its own schema upgrade on first boot.
    image: listmonk/listmonk@sha256:f535d59e14991337a9f2d570273685378ae86b0d7698c3e00da444e3bc205286
    depends_on:
      provision:
        condition: service_completed_successfully
    command: >
      sh -c "./listmonk --install --idempotent --yes --config ''
             && ./listmonk --upgrade --yes --config ''
             && ./listmonk --config ''"
    environment:
      LISTMONK_app__address: "0.0.0.0:9000"
      LISTMONK_app__root_url: ${LISTMONK_ROOT_URL:-https://newsletter.invalid}
      LISTMONK_db__host: postgres
      LISTMONK_db__port: "5432"
      LISTMONK_db__user: ${POSTGRES_USER:-roller}
      LISTMONK_db__password: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set in .env}
      LISTMONK_db__database: ${LISTMONK_DB:-listmonk}
      LISTMONK_db__ssl_mode: disable
      LISTMONK_ADMIN_USER: ${LISTMONK_ADMIN_USER:-admin}
      LISTMONK_ADMIN_PASSWORD: ${LISTMONK_ADMIN_PASSWORD:?LISTMONK_ADMIN_PASSWORD must be set in .env}
    restart: unless-stopped
    networks:
      - internal

  backup:
    # The app image, for pg_dump/psql plus the baked-in backup scripts. See
    # /app/backup/backup.sh for the RESTORE procedure.
    image: ghcr.io/jakefearsd/roller:${IMAGE_VERSION:?IMAGE_VERSION must be set in .env}
    entrypoint: ["/app/backup/loop.sh"]
    # Runs as root, unlike every other use of this image: the roller-backups
    # volume is created root-owned, and the unprivileged `roller` user the image
    # otherwise runs as cannot write into it. A backup that cannot write fails
    # at 03:00, not at deploy time, so this is not a detail to leave implicit.
    user: "0:0"
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-rollerdb}
      POSTGRES_USER: ${POSTGRES_USER:-roller}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set in .env}
      PGHOST: postgres
      UMAMI_DB: ${UMAMI_DB:-umami}
      LISTMONK_DB: ${LISTMONK_DB:-listmonk}
      BACKUP_HOUR: ${BACKUP_HOUR:-3}
      BACKUP_RETENTION_DAYS: ${BACKUP_RETENTION_DAYS:-14}
    volumes:
      - roller-backups:/backups
      - roller-mediafiles:/data/mediafiles:ro
      - roller-search-index:/data/search-index:ro
      - roller-uploads:/data/uploads:ro
    restart: unless-stopped
    networks:
      - internal

networks:
  internal:

volumes:
  roller-pgdata:
  roller-mediafiles:
  roller-search-index:
  roller-uploads:
  roller-backups:
  caddy-data:
  caddy-config:
```

- [ ] **Step 4: Fix the stale routing comment**

The old file's listmonk block (line 165) claimed the public subscribe API is "routed by Caddy". The rewrite above already drops that sentence. Confirm it is gone and that nothing reintroduces it:

Run: `grep -n "routed by Caddy" docker-compose.prod.yml; echo "exit=$?"`
Expected: no matches, `exit=1`.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q -pl app test -Dtest=ProductionComposeTest`
Expected: PASS, 5 tests.

- [ ] **Step 6: Verify compose can interpolate the file**

```bash
SITE_DOMAIN=:80 IMAGE_VERSION=plan-check POSTGRES_PASSWORD=x UMAMI_APP_SECRET=y \
LISTMONK_ADMIN_PASSWORD=z docker compose -f docker-compose.prod.yml config -q && echo "compose OK"
```
Expected: `compose OK`. A Compose older than v2.17 fails here on the `service_completed_successfully` condition, which is the documented prerequisite.

- [ ] **Step 7: Commit**

```bash
git add docker-compose.prod.yml \
        app/src/test/java/org/apache/roller/weblogger/business/startup/ProductionComposeTest.java
git commit -m "deploy: images-only production stack, with provisioning ordered by compose"
```

---

### Task 6: Rewrite .env.example and shrink deploy.sh

**Files:**
- Modify: `deploy/.env.example` (whole file)
- Modify: `deploy/deploy.sh` (whole file)
- Delete: `deploy/config/roller-production.properties.example`

**Interfaces:**
- Consumes: `IMAGE_VERSION`, `SITE_DOMAIN` (Task 5); the `ROLLER_*` naming rule (Task 1).
- Produces: the operator-facing contract Task 8 documents.

- [ ] **Step 1: Rewrite `deploy/.env.example`**

```bash
# Environment for docker-compose.prod.yml. Copy to .env NEXT TO that file and
# fill in real values. Never commit the result -- it holds every secret in the
# stack.
#
# This file plus docker-compose.prod.yml is everything a production host needs.
# The same pair runs the stack locally; only the values below change, which is
# what makes "test exactly what you are about to deploy" true rather than
# aspirational. Local values are given beside each key that differs.

#-----------------------------------------------------------------------------
# Images
#-----------------------------------------------------------------------------
# Version tag for BOTH ghcr.io/jakefearsd/roller and
# ghcr.io/jakefearsd/roller-caddy. They are published together from one release
# job and must move together. Published only when a v*.*.* tag is pushed
# (.github/workflows/release.yml), so this is a release, never "whatever was on
# master".
#
# NOTE: GHCR packages are private on first publish. Until the package is made
# public (GitHub -> Packages -> Settings -> Change visibility) or you
# `docker login ghcr.io` here with a PAT carrying read:packages, an anonymous
# pull fails with "denied".
IMAGE_VERSION=6.2.0

# Extra JVM flags for the app container, e.g. -Xmx1g. Optional.
JAVA_OPTS=

#-----------------------------------------------------------------------------
# Public domain / TLS
#-----------------------------------------------------------------------------
# Your real domain. Caddy requests and auto-renews a Let's Encrypt certificate
# for it; you need only DNS pointed here and 80/443 reachable (80 carries the
# ACME challenge).
#
# LOCAL: use ":80". A bare port is not a certificate-able name, so automatic
# HTTPS never activates and Caddy serves plain HTTP.
#
# Deliberately NOT named ROLLER_DOMAIN: that prefix is reserved for app
# configuration (see below), and a stray ROLLER_DOMAIN would be overlaid onto
# the app's properties as a junk key named "domain".
SITE_DOMAIN=blog.example.com

#-----------------------------------------------------------------------------
# Roller application configuration
#-----------------------------------------------------------------------------
# Every ROLLER_* variable here is overlaid onto the app's built-in defaults at
# startup: strip the prefix, lowercase, turn _ into . -- so
# ROLLER_MAIL_HOSTNAME sets the property mail.hostname. This is the
# highest-precedence configuration layer, and it is the ONLY one the production
# image uses; there is no properties file to mount. Any key documented in
# app/src/main/resources/org/apache/roller/weblogger/config/roller.properties
# can be set this way.

# Schema handling. 'auto' checks the schema at startup; the provision service
# has already applied every pending migration by then.
ROLLER_INSTALLATION_TYPE=auto

# Database. The URL's host is the compose service name; username and password
# must match POSTGRES_USER/POSTGRES_PASSWORD below.
ROLLER_DATABASE_CONFIGURATIONTYPE=jdbc
ROLLER_DATABASE_JDBC_DRIVERCLASS=org.postgresql.Driver
ROLLER_DATABASE_JDBC_CONNECTIONURL=jdbc:postgresql://postgres:5432/rollerdb
ROLLER_DATABASE_JDBC_USERNAME=roller
ROLLER_DATABASE_JDBC_PASSWORD=changeme-generate-a-real-secret

# Storage paths. These match the image's own layout and the volumes mounted by
# docker-compose.prod.yml; there is no reason to change them.
ROLLER_THEMES_DIR=/app/themes
ROLLER_MEDIAFILES_STORAGE_DIR=/data/mediafiles
ROLLER_SEARCH_INDEX_DIR=/data/search-index
ROLLER_UPLOADS_DIR=/data/uploads

# Mail: your SMTP relay. Used for password-reset and notification mail.
# Username and password are optional -- set them only if your relay needs auth.
ROLLER_MAIL_CONFIGURATIONTYPE=properties
ROLLER_MAIL_HOSTNAME=smtp.example.com
ROLLER_MAIL_PORT=587
#ROLLER_MAIL_USERNAME=
#ROLLER_MAIL_PASSWORD=

# Newsletter: where /newsletter/subscribe forwards to. The APP serves that
# endpoint (throttle + roller_event recording) and calls listmonk over the
# compose network -- Caddy has no route for it and must never be given one.
# Blank leaves the endpoint returning 503.
ROLLER_NEWSLETTER_LISTMONK_BASEURL=http://listmonk:9000

#-----------------------------------------------------------------------------
# Postgres credentials
#-----------------------------------------------------------------------------
# Shared by postgres, provision, and backup. Keep POSTGRES_USER/PASSWORD in
# sync with ROLLER_DATABASE_JDBC_USERNAME/PASSWORD above.
POSTGRES_DB=rollerdb
POSTGRES_USER=roller
POSTGRES_PASSWORD=changeme-generate-a-real-secret

#-----------------------------------------------------------------------------
# Nightly backup service
#-----------------------------------------------------------------------------
# Hour (UTC, 0-23) for pg_dump + volume snapshots. A bare number is clearest,
# though zero-padded is parsed correctly too.
BACKUP_HOUR=3
BACKUP_RETENTION_DAYS=14

#-----------------------------------------------------------------------------
# Analytics (Umami)
#-----------------------------------------------------------------------------
# Self-hosted and cookie-free, so no consent banner is required. The tracker
# and its collect endpoint are served from the BLOG's own origin at
# /analytics/* -- every bundled theme sends script-src 'self' and connect-src
# 'self', so a tracker on any other hostname is blocked and records nothing.
#
# Signing key for Umami's session tokens. Changing it logs everyone out.
#     openssl rand -hex 32
UMAMI_APP_SECRET=changeme-openssl-rand-hex-32

# Hostname for the Umami DASHBOARD (its UI assumes a domain root, so it cannot
# live under the blog's /analytics path). Unset, it defaults to
# analytics.invalid -- a name that cannot resolve, so the console is
# unreachable rather than accidentally published on your blog's domain.
#
# LOCAL: ":8081", reachable at http://localhost:8081
#UMAMI_DOMAIN=analytics.example.com

UMAMI_DB=umami

# Filename Umami serves its tracker under. Renaming it from script.js is the
# cheapest way to stop content blockers matching on the path.
UMAMI_SCRIPT_NAME=script.js

#-----------------------------------------------------------------------------
# Newsletter (Listmonk)
#-----------------------------------------------------------------------------
# Listmonk owns the subscribers, the double opt-in flow and the unsubscribe
# links; Roller stores no subscriber data at all.
LISTMONK_ADMIN_USER=admin
LISTMONK_ADMIN_PASSWORD=changeme-generate-a-real-secret

# Hostname for the newsletter admin AND the opt-in/unsubscribe pages
# subscribers follow from email. Needs its own name: running listmonk under a
# sub-path is an open upstream feature request, not a supported configuration.
#
# LOCAL: ":8082", reachable at http://localhost:8082
#LISTMONK_DOMAIN=newsletter.example.com

# Must match LISTMONK_DOMAIN, with scheme and no trailing slash. Listmonk
# builds confirmation and unsubscribe links from this, and those links are the
# only way a subscriber can confirm or leave.
#
# LOCAL: http://localhost:8082
#LISTMONK_ROOT_URL=https://newsletter.example.com

LISTMONK_DB=listmonk

# NOTE: listmonk needs its own SMTP credentials to send anything. Those are set
# in its admin console (Settings -> SMTP), not here.
```

- [ ] **Step 2: Rewrite `deploy/deploy.sh`**

```bash
#!/usr/bin/env bash
# Deploy or upgrade the production stack.
#
# There is almost nothing left here, and that is the point. Ordering used to
# live in this script -- bring postgres up, wait, copy migrate.sh into its
# container, run it, then start the app -- and now lives in
# docker-compose.prod.yml as depends_on conditions, where `docker compose up
# -d` enforces it whether or not anyone runs this file. What remains is a
# convenience wrapper: pull, up, and wait for health with a real timeout.
#
# The old version also had a genuine bug this shape cannot have: it expanded
# ${UMAMI_DB:-umami} in the host shell, which never sourced .env, so renaming a
# service database in .env made it provision the default names while the
# containers used the renamed ones. Those names are now read inside the
# provision container, which compose populates from .env correctly.
#
# Usage:
#   ./deploy.sh [--prune]
#
#   --prune   run `docker image prune -f` after a successful deploy
#
# Run from the directory holding docker-compose.prod.yml and .env.
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
COMPOSE=(docker compose -f "${COMPOSE_FILE}")

PRUNE=0
for arg in "$@"; do
    case "$arg" in
        --prune) PRUNE=1 ;;
        -h|--help)
            awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
            exit 0 ;;
        *)
            echo "Unknown argument: ${arg}" >&2
            exit 2 ;;
    esac
done

for required in "${COMPOSE_FILE}" .env; do
    if [[ ! -f "${required}" ]]; then
        echo "Missing ${required} in $(pwd). Both are attached to the GitHub Release." >&2
        exit 1
    fi
done

echo "==> Pulling images..."
"${COMPOSE[@]}" pull

echo "==> Starting the stack (compose orders provisioning before the app)..."
"${COMPOSE[@]}" up -d

echo "==> Waiting for the app to report healthy (up to 120s)..."
healthy=0
for _ in $(seq 1 60); do
    if "${COMPOSE[@]}" exec -T app curl -sf http://localhost:8090/actuator/health >/dev/null 2>&1; then
        healthy=1
        break
    fi
    sleep 2
done
if [[ "${healthy}" -ne 1 ]]; then
    echo "app did not become healthy within 120s. Check: ${COMPOSE[*]} logs provision app" >&2
    exit 1
fi
echo "    app healthy."

if [[ "${PRUNE}" -eq 1 ]]; then
    echo "==> Pruning dangling images..."
    docker image prune -f
fi

echo "==> Deploy complete."
```

- [ ] **Step 3: Delete the obsolete properties example**

```bash
git rm deploy/config/roller-production.properties.example
```

- [ ] **Step 4: Syntax-check the script and confirm no stale references remain**

```bash
bash -n deploy/deploy.sh && echo "syntax OK"
grep -rn "ROLLER_DOMAIN\|ROLLER_IMAGE\|roller-production.properties" \
    deploy/ docker-compose.prod.yml Dockerfile || echo "no stale references"
```
Expected: `syntax OK`, then `no stale references`.

- [ ] **Step 5: Commit**

```bash
git add deploy/.env.example deploy/deploy.sh
git commit -m "deploy: env-driven .env.example, and reduce deploy.sh to pull/up/wait"
```

---

### Task 7: Release workflow

**Files:**
- Modify: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: `deploy/caddy/Dockerfile` (Task 4).
- Produces: `ghcr.io/jakefearsd/roller{,-caddy}:<version>|:latest|:sha-<short>`, and a Release carrying `roller.war`, `docker-compose.prod.yml`, `.env.example`, `deploy.sh`.

- [ ] **Step 1: Add a test job the publish job depends on**

Insert this job above the existing `release:` job in `.github/workflows/release.yml`, and update the file's header comment to note the gate:

```yaml
jobs:
  test:
    name: Unit suite and coverage gates
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - name: Checkout Project
        uses: actions/checkout@v6
        with:
          persist-credentials: false
          submodules: false
          show-progress: false

      - name: Set up JDK 25
        uses: actions/setup-java@v5
        with:
          java-version: '25'
          distribution: 'zulu'
          cache: maven

      # main.yml triggers on `push: branches: [master]`, which a TAG push does
      # not match -- so in the documented release flow
      # (`git push origin master v6.2.0`) the unit suite and this workflow race,
      # with no dependency between them. Without this job a tag on a commit that
      # never passed build-test publishes anyway. `install` runs `verify`, so
      # this covers the JaCoCo floors too.
      - name: Build and run the unit suite
        run: mvn -V -ntp install
```

- [ ] **Step 2: Gate the release job and build both images**

Replace the `release:` job's `steps:` up to and including the image push with:

```yaml
  release:
    name: Build, publish images, create release
    needs: test
    runs-on: ubuntu-latest
    timeout-minutes: 45

    steps:
      - name: Checkout Project
        uses: actions/checkout@v6
        with:
          persist-credentials: false
          submodules: false
          show-progress: false

      - name: Extract version from tag
        id: version
        run: |
          # v6.2.0 -> 6.2.0
          echo "version=${GITHUB_REF_NAME#v}" >> "$GITHUB_OUTPUT"
          echo "sha_short=$(git rev-parse --short HEAD)" >> "$GITHUB_OUTPUT"

      # GHCR requires an all-lowercase repository path; github.repository keeps
      # the casing configured on GitHub.
      - name: Compute lowercase image repository
        run: echo "IMAGE_REPO=${GITHUB_REPOSITORY,,}" >> "$GITHUB_ENV"

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v4

      - name: Log in to GHCR
        uses: docker/login-action@v4
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Build and push the app image
        uses: docker/build-push-action@v7
        with:
          context: .
          push: true
          tags: |
            ghcr.io/${{ env.IMAGE_REPO }}:${{ steps.version.outputs.version }}
            ghcr.io/${{ env.IMAGE_REPO }}:latest
            ghcr.io/${{ env.IMAGE_REPO }}:sha-${{ steps.version.outputs.sha_short }}
          labels: |
            org.opencontainers.image.source=${{ github.server_url }}/${{ github.repository }}
            org.opencontainers.image.version=${{ steps.version.outputs.version }}
            org.opencontainers.image.revision=${{ github.sha }}
            org.opencontainers.image.licenses=Apache-2.0
          cache-from: type=gha
          cache-to: type=gha,mode=max

      - name: Build and push the caddy image
        uses: docker/build-push-action@v7
        with:
          context: .
          file: deploy/caddy/Dockerfile
          push: true
          tags: |
            ghcr.io/${{ env.IMAGE_REPO }}-caddy:${{ steps.version.outputs.version }}
            ghcr.io/${{ env.IMAGE_REPO }}-caddy:latest
            ghcr.io/${{ env.IMAGE_REPO }}-caddy:sha-${{ steps.version.outputs.sha_short }}
          labels: |
            org.opencontainers.image.source=${{ github.server_url }}/${{ github.repository }}
            org.opencontainers.image.version=${{ steps.version.outputs.version }}
            org.opencontainers.image.revision=${{ github.sha }}
            org.opencontainers.image.licenses=Apache-2.0
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

- [ ] **Step 3: Extract the WAR from the built image instead of rebuilding it**

Replace the old `Build the executable WAR` step (which ran `mvn clean package -DskipTests`) — it is deleted — and add this after the app image push:

```yaml
      # The image build is the ONLY build of the WAR. Extracting the artifact
      # from the image it shipped in means the WAR attached to this Release and
      # the WAR running in production are the same bytes; running `mvn package`
      # separately produced a second, merely-equivalent binary.
      - name: Extract the WAR from the published image
        run: |
          image="ghcr.io/${IMAGE_REPO}:${{ steps.version.outputs.version }}"
          docker pull "$image"
          cid="$(docker create "$image")"
          mkdir -p release-assets
          docker cp "$cid:/app/roller.war" release-assets/roller.war
          docker rm "$cid"
          cp docker-compose.prod.yml release-assets/
          cp deploy/.env.example release-assets/.env.example
          cp deploy/deploy.sh release-assets/deploy.sh
          ls -l release-assets
```

- [ ] **Step 4: Attach the deploy bundle to the Release**

Replace the `Create GitHub Release` step's `files:` block:

```yaml
      - name: Create GitHub Release
        uses: softprops/action-gh-release@v3
        with:
          name: Roller ${{ steps.version.outputs.version }}
          generate_release_notes: true
          # A production host has no checkout, so everything it needs is here.
          files: |
            release-assets/roller.war
            release-assets/docker-compose.prod.yml
            release-assets/.env.example
            release-assets/deploy.sh
          fail_on_unmatched_files: true
```

- [ ] **Step 5: Validate the workflow parses**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/release.yml')); print('workflow YAML OK')"`
Expected: `workflow YAML OK`.

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci: gate releases on tests, publish both images, ship the deploy bundle"
```

---

### Task 8: Documentation

**Files:**
- Modify: `docker_deployment.md` (sections listed below)
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: everything from Tasks 1-7. Produces no code.

- [ ] **Step 1: Rewrite the affected `docker_deployment.md` sections**

Four sections describe a workflow that no longer exists and must be rewritten, not patched:

- **"Get the code onto the host"** → becomes **"Get the deploy files onto the host"**. There is no `git clone`. Download `docker-compose.prod.yml`, `.env.example` and `deploy.sh` from the release page into one directory, e.g. `/opt/roller`, and `mv .env.example .env`.
- **"Configure `roller-production.properties`"** → deleted entirely. Fold its content into **"Configure `.env`"**, describing the `ROLLER_*` block and the mapping rule (strip prefix, lowercase, `_` → `.`).
- **"First run"** → `docker compose -f docker-compose.prod.yml up -d`. State that compose runs `provision` first and that `app`, `umami` and `listmonk` do not start until it exits successfully, so there is no separate migration step and no ordering to get right. Keep the "browse to `https://<domain>/roller` and register the first user" paragraph.
- **"Upgrades"** → edit `IMAGE_VERSION` in `.env`, then `./deploy.sh` (or `docker compose pull && docker compose up -d`). No `git pull`.

Add a new section, **"Test a release locally before deploying it"**, immediately before "Upgrades":

````markdown
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
````

Also update **"Prerequisites"** to require Docker Compose v2.17 or newer, and **"Firewall"** to mention that 8081/8082 are loopback-bound and therefore need no firewall rule.

- [ ] **Step 2: Update `CLAUDE.md`**

In the **Configuration scope** section, add a fourth scope after the "Per-weblog" bullet:

```markdown
- **Environment (`ROLLER_*` variables).** The highest-precedence layer, applied
  by `WebloggerConfig.applyEnvironmentOverrides` over everything the properties
  files set: strip the `ROLLER_` prefix, lowercase, turn `_` into `.`. A derived
  name matching an existing key case-insensitively writes to that key's original
  spelling, so `ROLLER_DATABASE_JDBC_DRIVERCLASS` reaches
  `database.jdbc.driverClass` rather than creating a lowercase twin nothing
  reads; a name matching nothing is used as derived, which is required rather
  than incidental (`mail.port` has no entry in `roller.properties` and
  `uploads.dir` is commented out). This is how the production image is
  configured — there is no properties file in it.
```

In **Development vs Production**, replace the Production paragraph with:

```markdown
- **Production**: containerized end-to-end and **image-only** — the deploy host
  holds `docker-compose.prod.yml` and `.env` and nothing else. Two images ship
  per release tag: `ghcr.io/jakefearsd/roller` (WAR, themes, migrations,
  `provision.sh`, `migrate.sh`, `umami-views.sql`, the backup scripts, and a
  PostgreSQL client) and `ghcr.io/jakefearsd/roller-caddy` (Caddy with the
  Caddyfile baked in). A one-shot `provision` service creates the umami and
  listmonk databases, applies the migration chain, and installs the analytics
  views; `app`, `umami` and `listmonk` declare
  `depends_on: { provision: { condition: service_completed_successfully } }`,
  so the migrate-then-start ordering is compose's job, not a bash script's.
  `deploy/deploy.sh` is now just pull/up/wait. **Nothing may be bind-mounted
  from a checkout** — `ProductionComposeTest` fails the build if a bind mount,
  a `build:` stanza, or a non-loopback published port other than 80/443
  reappears. Full runbook: `docker_deployment.md`.
```

- [ ] **Step 3: Check for surviving references to the old model**

```bash
grep -rn "roller-production.properties\|deploy/config\|ROLLER_DOMAIN" \
    docker_deployment.md CLAUDE.md README.md || echo "docs are consistent"
```
Expected: `docs are consistent`.

- [ ] **Step 4: Commit**

```bash
git add docker_deployment.md CLAUDE.md
git commit -m "docs: describe the image-only deployment model"
```

---

### Task 9: Full-stack verification

The manual gate. Nothing here is automated, and it is the only step that proves the pieces fit.

**Files:** none modified.

**Interfaces:** consumes everything.

- [ ] **Step 1: Build both images locally**

```bash
docker build -t ghcr.io/jakefearsd/roller:local .
docker build -t ghcr.io/jakefearsd/roller-caddy:local -f deploy/caddy/Dockerfile .
```
Expected: both succeed.

- [ ] **Step 2: Create a local .env**

```bash
mkdir -p /tmp/roller-local && cp docker-compose.prod.yml /tmp/roller-local/
sed -e 's/^IMAGE_VERSION=.*/IMAGE_VERSION=local/' \
    -e 's/^SITE_DOMAIN=.*/SITE_DOMAIN=:80/' \
    -e 's/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=localdev/' \
    -e 's/^ROLLER_DATABASE_JDBC_PASSWORD=.*/ROLLER_DATABASE_JDBC_PASSWORD=localdev/' \
    -e 's/^UMAMI_APP_SECRET=.*/UMAMI_APP_SECRET=0123456789abcdef0123456789abcdef/' \
    -e 's/^LISTMONK_ADMIN_PASSWORD=.*/LISTMONK_ADMIN_PASSWORD=localdev/' \
    -e 's/^#UMAMI_DOMAIN=.*/UMAMI_DOMAIN=:8081/' \
    -e 's/^#LISTMONK_DOMAIN=.*/LISTMONK_DOMAIN=:8082/' \
    -e 's|^#LISTMONK_ROOT_URL=.*|LISTMONK_ROOT_URL=http://localhost:8082|' \
    deploy/.env.example > /tmp/roller-local/.env
grep -E '^(IMAGE_VERSION|SITE_DOMAIN|UMAMI_DOMAIN|LISTMONK_DOMAIN)=' /tmp/roller-local/.env
```
Expected: the four keys show the local values.

- [ ] **Step 3: Bring the stack up from clean volumes**

```bash
cd /tmp/roller-local
docker compose -f docker-compose.prod.yml down -v 2>/dev/null || true
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml logs provision
```
Expected: provision's log shows `created umami.`, `created listmonk.`, migrations applied through `V025`, and `Provisioning complete.` It must have exited 0 — `docker compose ps -a provision` shows `Exited (0)`.

- [ ] **Step 4: Confirm the app came up and took its config from the environment**

```bash
docker compose -f docker-compose.prod.yml exec -T app curl -sf http://localhost:8090/actuator/health
curl -sS -o /dev/null -w '%{http_code}\n' http://localhost/roller/
```
Expected: a healthy JSON body, then `200`. A `200` here proves the env overlay worked: with no properties file and no `-Droller.custom.config`, the only way the app reached PostgreSQL is `ROLLER_DATABASE_JDBC_*`.

- [ ] **Step 5: Confirm provisioning is idempotent**

```bash
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml logs --tail=20 provision
```
Expected: `umami already exists.`, `listmonk already exists.`, migrate.sh reporting nothing pending, exit 0. The dependent services must not be recreated or blocked.

- [ ] **Step 6: Confirm the backup service can write**

```bash
docker compose -f docker-compose.prod.yml exec -T -u 0 backup /app/backup/backup.sh
docker compose -f docker-compose.prod.yml exec -T backup ls -l /backups
```
Expected: a `roller-*.dump`, the two service dumps, and a `volumes-*.tar.gz`. This is the step that catches the volume-ownership trap the `user: "0:0"` setting exists for.

- [ ] **Step 7: Tear down**

```bash
docker compose -f docker-compose.prod.yml down -v
cd - && rm -rf /tmp/roller-local
```

- [ ] **Step 8: Run the whole unit suite one last time**

Run: `mvn -ntp install`
Expected: PASS, including `WebloggerConfigEnvOverrideTest`, `ProductionComposeTest`, and the JaCoCo floors.

- [ ] **Step 9: Commit any fixes found during verification**

```bash
git add -A
git commit -m "deploy: fixes from full-stack verification"
```

---

### Task 10: Break the fresh-install deadlock (added after Task 9)

Task 9's full-stack verification found that a fresh `docker compose up -d` cannot
complete. `umami-views.sql` defines `analytics_traffic` over `website_event` — a
table Umami creates on its **own first boot** — but `provision.sh` applies it,
and `umami` cannot start until `provision` exits successfully. Neither can go
first. The pre-wave `deploy.sh` had the same ordering and would have failed the
same way; nobody had run the stack end to end before.

The fix splits the views out of provisioning into a one-shot that runs *after*
Umami starts and **gates nothing**, so it can never block the stack.

**Files:**
- Create: `deploy/analytics-views.sh` (mode 755)
- Modify: `deploy/provision.sh` (drop the views step, it becomes three steps)
- Modify: `Dockerfile` (COPY + chmod the new script)
- Modify: `docker-compose.prod.yml` (new `analytics-views` service)
- Modify: `app/src/test/java/org/apache/roller/weblogger/business/startup/ProductionComposeTest.java`
- Modify: `docker_deployment.md`, `docs/superpowers/specs/2026-08-14-container-push-deployment-design.md`

**Interfaces:**
- Consumes: `/app/umami-views.sql` (Task 3), the `provision` service (Task 5).
- Produces: `/app/analytics-views.sh` in the image; a compose service named
  `analytics-views` that **nothing** depends on.

- [ ] **Step 1: Write the new script**

Create `deploy/analytics-views.sh`:

```bash
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
```

- [ ] **Step 2: Make it executable and syntax-check it**

```bash
chmod 755 deploy/analytics-views.sh
bash -n deploy/analytics-views.sh && echo "syntax OK"
```
Expected: `syntax OK`.

- [ ] **Step 3: Remove the views step from provision.sh**

Delete the final `psql ... -f /app/umami-views.sql` step and its `echo`/comment
block from `deploy/provision.sh`. Update its header comment, which currently
describes four steps, to describe three: create the service databases, migrate
`rollerdb`, grant `grafana_ro`. Add one sentence saying the analytics views moved
to `analytics-views.sh` and why.

**Keep the `GRANT CONNECT` step in provision.sh.** It only needs the umami
*database* to exist (provision creates it) and the `grafana_ro` role to exist
(V017 creates it during the migration step) — neither requires Umami to have
booted.

- [ ] **Step 4: Bake the script into the image**

In `Dockerfile`, beside the existing `COPY deploy/provision.sh /app/provision.sh`:

```dockerfile
COPY deploy/analytics-views.sh /app/analytics-views.sh
```

and add `/app/analytics-views.sh` to the existing `chmod 755` list.

- [ ] **Step 5: Add the compose service**

In `docker-compose.prod.yml`, after the `umami` service:

```yaml
  # Installs the Grafana analytics contract's traffic view. Separate from
  # `provision` because analytics_traffic is defined over `website_event`, a
  # table umami creates on its OWN first boot -- and provision runs before
  # umami is allowed to start. Applying it there deadlocks a fresh install.
  #
  # Nothing declares depends_on against this service, deliberately: if it fails
  # or times out, the blog is still up and only the Grafana traffic view is
  # missing. ProductionComposeTest pins that nothing gates on it.
  analytics-views:
    image: ghcr.io/jakefearsd/roller:${IMAGE_VERSION:?IMAGE_VERSION must be set in .env}
    entrypoint: ["/app/analytics-views.sh"]
    restart: "no"
    depends_on:
      provision:
        condition: service_completed_successfully
      umami:
        condition: service_started
    environment:
      POSTGRES_USER: ${POSTGRES_USER:-roller}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set in .env}
      UMAMI_DB: ${UMAMI_DB:-umami}
    networks:
      - internal
```

- [ ] **Step 6: Pin the non-blocking property with a test**

Add to `ProductionComposeTest`:

```java
    @Test
    void analyticsViewsGateNothing() {
        assertNotNull(services().get("analytics-views"),
                "the analytics views one-shot must exist");

        for (Map.Entry<String, Object> entry : services().entrySet()) {
            @SuppressWarnings("unchecked")
            Object dependsOn = ((Map<String, Object>) entry.getValue()).get("depends_on");
            if (dependsOn instanceof Map<?, ?> conditions) {
                assertFalse(conditions.containsKey("analytics-views"),
                        entry.getKey() + " must not wait on analytics-views: it installs a Grafana "
                                + "dashboard view, and a failure there must never keep the blog down");
            } else if (dependsOn instanceof List<?> list) {
                assertFalse(list.contains("analytics-views"),
                        entry.getKey() + " must not wait on analytics-views");
            }
        }
    }
```

Add `import static org.junit.jupiter.api.Assertions.assertFalse;` if absent.

- [ ] **Step 7: Run the test**

Run: `mvn -q -pl app test -Dtest=ProductionComposeTest`
Expected: PASS, 6 tests.

- [ ] **Step 8: Verify the fresh-install path end to end**

This is the point of the task — the previous arrangement failed exactly here.

```bash
docker build -t ghcr.io/jakefearsd/roller:local .
mkdir -p /tmp/roller-t10 && cp docker-compose.prod.yml /tmp/roller-t10/
# reuse the local .env recipe from Task 9 Step 2
cd /tmp/roller-t10
docker compose -f docker-compose.prod.yml down -v 2>/dev/null || true
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml ps -a
docker compose -f docker-compose.prod.yml logs analytics-views
```
Expected: `up -d` completes **unassisted** from clean volumes. `provision` exits 0,
`analytics-views` exits 0 having waited for and found `website_event`, and the
app answers on `http://localhost/roller/`. Then confirm idempotency with a second
`up -d`, and tear down with `down -v`.

- [ ] **Step 9: Update the docs**

In `docker_deployment.md`, wherever provisioning is described as four steps,
correct it to three plus the separate `analytics-views` one-shot, and explain
that a failure there leaves the blog serving. In the spec, add a paragraph to
the compose-topology section recording the deadlock and this resolution.

- [ ] **Step 10: Commit**

```bash
git add deploy/analytics-views.sh deploy/provision.sh Dockerfile docker-compose.prod.yml \
        app/src/test/java/org/apache/roller/weblogger/business/startup/ProductionComposeTest.java \
        docker_deployment.md docs/superpowers/specs/2026-08-14-container-push-deployment-design.md
git commit -m "deploy: install analytics views after umami boots, not before"
```

## Self-Review Notes

**Spec coverage.** Every spec section maps to a task: images → 3, 4; compose topology → 5; env overlay and masking → 1; the mapping table → 6; local parity and both test procedures → 8 (documented), 9 (executed); release and deploy → 7, 6; both bug fixes → 2 (the `.env` expansion bug, fixed by deletion) and 5 Step 4 (the stale routing comment); testing → 1, 5, 9; risks → 3 (pg_dump assertion), 5 (`user: "0:0"`), 8 (Compose version floor).

**One thing the spec did not anticipate**, found while writing Task 5: the backup service inherits the app image's unprivileged `USER roller`, but the `roller-backups` volume is created root-owned, so backups would fail at 03:00 rather than at deploy time. `user: "0:0"` and Task 9 Step 6 exist for that. Today's stack does not have the problem because it runs the postgres image as root.
