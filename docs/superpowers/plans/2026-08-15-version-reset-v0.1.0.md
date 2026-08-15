# Version Reset to 0.1.0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Renumber the project from `6.2.0` to `0.1.0` and stop its user-visible output claiming to be Apache Roller.

**Architecture:** The version is a single Maven property that reaches every display surface through a filtered resource, so the number itself is one command plus one manual edit. The identity change is a fixed list of seven user-visible strings. Everything else is prose whose reference point disappears with the renumbering.

**Tech Stack:** Maven (versions-maven-plugin), Java 25, Velocity templates, Java resource bundles.

**Spec:** `docs/superpowers/specs/2026-08-15-version-reset-v0.1.0-design.md`

## Global Constraints

- The new version is exactly `0.1.0`.
- The fork is called **Roller**. User-visible output must never say "Apache Roller" or "Apache Roller Weblogger", and must not link to `roller.apache.org`.
- `LICENSE.txt` and `NOTICE.txt` are **untouched**. Removing branding is correct for a fork; removing attribution would breach Apache-2.0.
- **`6.2.0` is ambiguous in this repository.** It is this project's old version *and* listmonk's currently pinned version. `docker-compose.prod.yml:181,185` refer to **listmonk** and must NOT be changed. A verification grep that returns zero `6.2.0` matches means that comment was corrupted, not that the job is done.
- **No mechanical find-and-replace anywhere in this change.** Every edit is made by reading the surrounding line.
- `docs/superpowers/**` is excluded from all sweeps. Those are historical records of decisions made when the project was numbered 6.2.0; rewriting them would falsify the record.
- Java packages (`org.apache.roller.weblogger.*`), Maven coordinates (`org.apache.roller:roller-project`, `roller-webapp`), container image names, and the `V001`–`V025` migration chain are all unchanged.
- The 70 inherited upstream git tags (`roller-5.1.2` …) stay.
- `app/src/main/resources/ApplicationResources_ja.properties` escapes `=` and `:` as `\=` and `\:`. Preserve that file's escaping style exactly.
- Commit convention: lowercase `area: summary` subject, body ending with exactly `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`. **This repo's CLAUDE.md forbids committing unless the user explicitly asks** — treat each commit step as "ask, then commit".

---

## File Structure

**Modified — build:** `pom.xml`, `app/pom.xml`, `it-selenium/pom.xml`

**Modified — shipped output:** `app/src/main/webapp/WEB-INF/velocity/templates/feeds/site-entries-atom.vm`, `.../weblog-entries-atom.vm`, `app/src/main/webapp/themes/frontpage/_footer.vm`, `app/src/main/resources/ApplicationResources.properties`, `ApplicationResources_zh_CN.properties`, `ApplicationResources_de.properties`, `ApplicationResources_ja.properties`

**Modified — Java:** `app/src/main/java/.../business/startup/DatabaseInstaller.java`, `app/src/test/java/.../ui/rendering/model/ConfigModelTest.java`, `app/src/test/java/.../business/startup/SchemaMigrationTest.java`, `app/src/test/java/org/apache/roller/testing/RollerPostgresContainer.java`

**Modified — docs and config:** `README.md`, `CLAUDE.md`, `bin/db/migrations/README.md`, `bin/db/migrations/V001__schema_migrations.sql`, `bin/db/migrations/V002__baseline_schema.sql`, `deploy/.env.example`, `.github/workflows/release.yml`, `docker_deployment.md`

**Deliberately untouched:** `docker-compose.prod.yml`, `LICENSE.txt`, `NOTICE.txt`, `version-rules.xml`, everything under `docs/superpowers/`.

---

### Task 1: The version number

**Files:**
- Modify: `pom.xml` (via plugin, plus one manual edit at line 46)
- Modify: `app/pom.xml` (via plugin)
- Modify: `it-selenium/pom.xml` (via plugin — **only if `-Pit` is active**)

**Interfaces:**
- Produces: `${project.version}` = `0.1.0`, which flows to `app/src/main/resources-filtered/roller-version.properties` (`ro.version=${project.version}`) and from there to `WebloggerImpl.getVersion()`, the startup banner, and `$config.rollerVersion` in every theme and feed. Later tasks assume the number is already `0.1.0`.

- [ ] **Step 1: Confirm the starting state**

```bash
grep -n "<version>6.2.0</version>\|<roller.version>" pom.xml app/pom.xml it-selenium/pom.xml
```
Expected: four lines — `pom.xml` `<version>`, `pom.xml` `<roller.version>`, `app/pom.xml` parent `<version>`, `it-selenium/pom.xml` parent `<version>`.

- [ ] **Step 2: Set the version across all three poms**

```bash
mvn -q -Pit versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
```

**The `-Pit` is load-bearing.** `it-selenium` is a module only inside the `it` profile (`pom.xml:87-92`). Without the profile active the plugin never sees it, its `<parent>` stays pinned at `6.2.0`, and the browser ITs silently stop resolving their parent — a breakage nobody discovers until the next `mvn verify -Pit`.

- [ ] **Step 3: Fix the property the plugin does not touch**

`versions:set` rewrites `<version>` and `<parent><version>` elements. It does **not** touch the root pom's separate property. In `pom.xml`, change:

```xml
        <roller.version>6.2.0</roller.version>
```

to:

```xml
        <roller.version>0.1.0</roller.version>
```

- [ ] **Step 4: Verify all three poms resolve**

```bash
mvn -q -Pit validate && echo "all three poms resolve"
```
Expected: `all three poms resolve`. This is the check that catches a missed `it-selenium` — Maven cannot load the reactor at all if a module's parent version is wrong, so it fails before any goal runs. It takes seconds; do **not** substitute `mvn -Pit install`, which would additionally run the ~16-minute browser IT suite and needs Chrome.

- [ ] **Step 5: Confirm no `6.2.0` survives in any pom**

```bash
grep -rn "6\.2\.0" pom.xml app/pom.xml it-selenium/pom.xml; echo "exit=$?"
```
Expected: no matches, `exit=1`.

- [ ] **Step 6: Run the unit suite**

```bash
mvn -ntp clean install
```
Expected: BUILD SUCCESS. Two tests assert on version strings (`ConfigModelTest`, `SchemaMigrationTest`) but both use their own literals rather than the project version, so they should still pass here; Task 3 updates them.

Note: every `mvn test` in this repo starts a Testcontainers PostgreSQL container before any test class loads, so expect a container-startup pause and log noise. Docker must be running.

- [ ] **Step 7: Commit**

```bash
git add pom.xml app/pom.xml it-selenium/pom.xml
git commit -m "build: reset version to 0.1.0"
```

---

### Task 2: User-visible branding

Seven strings in shipped output name Apache Roller. All lose "Apache" and the `roller.apache.org` link.

**Files:**
- Modify: `app/src/main/webapp/WEB-INF/velocity/templates/feeds/site-entries-atom.vm:38`
- Modify: `app/src/main/webapp/WEB-INF/velocity/templates/feeds/weblog-entries-atom.vm:38`
- Modify: `app/src/main/webapp/themes/frontpage/_footer.vm:4`
- Modify: `app/src/main/resources/ApplicationResources.properties:273-274`
- Modify: `app/src/main/resources/ApplicationResources_zh_CN.properties:236`
- Modify: `app/src/main/resources/ApplicationResources_de.properties:137`
- Modify: `app/src/main/resources/ApplicationResources_ja.properties:647`
- Modify: `app/src/test/java/org/apache/roller/weblogger/business/RollerVersionTest.java:46` (javadoc only)

**Interfaces:**
- Consumes: `$config.rollerVersion` now renders `0.1.0` (Task 1). No signature changes.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: The two Atom feed generators**

`site-entries-atom.vm:38`, from:

```
    <generator uri="http://roller.apache.org" version="$config.rollerVersion ($config.rollerBuildTimestamp:$config.rollerBuildUser)">Apache Roller Weblogger</generator>
```

to:

```
    <generator version="$config.rollerVersion ($config.rollerBuildTimestamp:$config.rollerBuildUser)">Roller</generator>
```

`weblog-entries-atom.vm:38`, from:

```
    <generator uri="http://roller.apache.org" version="$config.rollerVersion">Apache Roller</generator>
```

to:

```
    <generator version="$config.rollerVersion">Roller</generator>
```

The `uri` attribute is dropped rather than repointed: it is optional in Atom, and there is no public URL for this fork to name.

- [ ] **Step 2: The frontpage footer**

`themes/frontpage/_footer.vm:4`, from:

```html
    <p class="fd-credit">Powered by <a href="https://roller.apache.org">Apache Roller</a> $config.rollerVersion</p>
```

to:

```html
    <p class="fd-credit">Powered by Roller $config.rollerVersion</p>
```

- [ ] **Step 3: The base resource bundle**

`ApplicationResources.properties:273-274`, from:

```properties
footer.productName=Powered by <a href="http://roller.apache.org">Apache Roller Weblogger</a> Version {0} ({1})
footer.productNameNoVersion=Powered by <a href="http://roller.apache.org">Apache Roller Weblogger</a>
```

to:

```properties
footer.productName=Powered by Roller Version {0} ({1})
footer.productNameNoVersion=Powered by Roller
```

- [ ] **Step 4: The three translated bundles**

These are easy to miss and would leave the admin footer saying "Apache Roller Weblogger" in Chinese, German and Japanese.

`ApplicationResources_zh_CN.properties:236` →

```properties
footer.productName=Powered by Roller Version {0} ({1})
```

`ApplicationResources_de.properties:137` →

```properties
footer.productName=Powered by Roller Version {0}
```

`ApplicationResources_ja.properties:647` →

```properties
footer.productName=Powered by Roller Version {0} ({1})
```

Two things not to "fix" while here:
- The German copy has a **one**-argument pattern (`{0}`) where the others have two. `footer.jsp` supplies both and `MessageFormat` ignores the surplus, so this is pre-existing and harmless.
- The Japanese file escapes `=` and `:` as `\=` and `\:` throughout. Its replacement line above has neither character in the value, so no escaping is needed — but do not reformat anything else in that file.

- [ ] **Step 5: Fix the one test javadoc that quotes the old string**

`RollerVersionTest.java:46` describes the failure it guards against as shipping "a footer that reads `Powered by Apache Roller Weblogger Version  ()`". That quotes the message Step 3 just rewrote. Change it to:

```java
 * that reads "Powered by Roller Version  ()".
```

The test's assertions do not reference the message at all — they check only that `ro.version` and `ro.revision` are non-blank and free of unresolved `${` placeholders — so this is a comment fix, not a behaviour change. It matters because that javadoc is the clearest existing explanation of *why* the filtered resource exists, and a stale quotation makes it read as describing a different system.

- [ ] **Step 6: Verify no branding survives in shipped output**

```bash
grep -rn "Apache Roller" app/src/main/webapp/ app/src/main/resources/ApplicationResources*.properties; echo "exit=$?"
```
Expected: no matches, `exit=1`.

```bash
grep -rn "roller\.apache\.org" app/src/main/webapp/themes/ app/src/main/webapp/WEB-INF/velocity/ app/src/main/resources/ApplicationResources*.properties; echo "exit=$?"
```
Expected: no matches, `exit=1`.

- [ ] **Step 8: Run the tests that cover these files**

```bash
mvn -q -pl app test -Dtest='ConfigModelTest,MessageKeyTest,FrontpageRenderingTest,FeedServletRenderingTest,RollerVersionTest'
```
Expected: PASS. All five exist. `MessageKeyTest` scans webapp sources for message keys, so it is the one that would catch a bundle edit that broke key resolution; `FrontpageRenderingTest` and `FeedServletRenderingTest` render the two surfaces whose templates changed. No test asserts on the branding text itself — verified by grepping the test sources — so none of these should need updating.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/webapp/WEB-INF/velocity/templates/feeds/site-entries-atom.vm \
        app/src/main/webapp/WEB-INF/velocity/templates/feeds/weblog-entries-atom.vm \
        app/src/main/webapp/themes/frontpage/_footer.vm \
        app/src/main/resources/ApplicationResources.properties \
        app/src/main/resources/ApplicationResources_zh_CN.properties \
        app/src/main/resources/ApplicationResources_de.properties \
        app/src/main/resources/ApplicationResources_ja.properties \
        app/src/test/java/org/apache/roller/weblogger/business/RollerVersionTest.java
git commit -m "branding: call the fork Roller, not Apache Roller"
```

---

### Task 3: The schema guard and version strings in Java

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/startup/DatabaseInstaller.java:100-108` (and the class javadoc at line 45)
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/model/ConfigModelTest.java:257,261`
- Modify: `app/src/test/java/org/apache/roller/weblogger/business/startup/SchemaMigrationTest.java:68,132`
- Modify: `app/src/test/java/org/apache/roller/testing/RollerPostgresContainer.java:25`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Reword the schema guard**

This is a user-facing error message that names `6.2.0` three times. After the renumbering it would cite a version this project no longer has. In `DatabaseInstaller.java`, replace the comment and throw at lines 100-108:

```java
                // A database predating this fork has Roller tables but no
                // tracking table. There is no upgrade path from upstream, so
                // say so plainly rather than silently re-running the baseline
                // over a populated schema.
                if (tableExists(con, "userrole") || tableExists(con, "roller_user")) {
                    throw new IllegalStateException(
                            "This database has Roller tables but no " + TRACKING_TABLE + " table, "
                            + "so it predates this fork's migration chain. There is no in-place "
                            + "upgrade path from Apache Roller 6.1.x or earlier, nor from any "
                            + "database other than PostgreSQL. Export your content and load it "
                            + "into a fresh database.");
                }
```

The guard is kept rather than deleted. This fork has never been deployed, so nothing can trigger it today — but it is the only thing standing between someone pointing this at a real upstream Roller database and silent schema corruption, and it costs one paragraph.

- [ ] **Step 2: Fix the class javadoc in the same file**

`DatabaseInstaller.java:45` reads "This replaces the pre-6.2.0 design, which rendered vendor-specific DDL for". Change `pre-6.2.0` to `pre-fork`, so the sentence becomes "This replaces the pre-fork design, which rendered vendor-specific DDL for".

- [ ] **Step 3: Update the mocked version in ConfigModelTest**

Lines 257 and 261 use `"6.2.0"` as arbitrary mock data. It is not a real assertion about the project version, but leaving it invites a reader to take it for one. Change both occurrences of `"6.2.0"` to `"0.1.0"`:

```java
        when(weblogger.getVersion()).thenReturn("0.1.0");
```

```java
        assertEquals("0.1.0", model.getRollerVersion(),
```

- [ ] **Step 4: Reword the two lineage references in SchemaMigrationTest**

Line 68, from `Tables belonging to features removed in 6.2.0. The baseline must not` to:

```java
     * Tables belonging to features removed when this fork diverged. The
     * baseline must not
```

Line 132, from `"Table '" + removed + "' belongs to a feature removed in 6.2.0 and "` to:

```java
                        "Table '" + removed + "' belongs to a feature removed when this fork "
                                + "diverged from upstream and "
```

Keep the following line (`"must not be in the baseline schema"`) as it is, so the assembled message still reads as one sentence.

- [ ] **Step 5: Reword RollerPostgresContainer's javadoc**

Line 25, from `<p>Roller is PostgreSQL-only as of 6.2.0, and tests run against the same` to:

```java
 * <p>Roller has been PostgreSQL-only since this fork diverged from Apache
 * Roller 6.1.x, and tests run against the same
```

- [ ] **Step 6: Run the affected tests**

```bash
mvn -q -pl app test -Dtest='ConfigModelTest,SchemaMigrationTest'
```
Expected: PASS. There is deliberately no third test here: **no test class covers `DatabaseInstaller` directly**, and nothing asserts on the guard's message text — which is why rewording it is safe, and also why the reworded message must be read carefully by a human, since no test will catch a mistake in it.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/business/startup/DatabaseInstaller.java \
        app/src/test/java/org/apache/roller/weblogger/ui/rendering/model/ConfigModelTest.java \
        app/src/test/java/org/apache/roller/weblogger/business/startup/SchemaMigrationTest.java \
        app/src/test/java/org/apache/roller/testing/RollerPostgresContainer.java
git commit -m "startup: name upstream rather than a version this fork no longer has"
```

---

### Task 4: Documentation and config, then final verification

**Files:**
- Modify: `README.md:3,5,58`
- Modify: `CLAUDE.md:132`
- Modify: `bin/db/migrations/README.md:7,15`
- Modify: `bin/db/migrations/V001__schema_migrations.sql:22`
- Modify: `bin/db/migrations/V002__baseline_schema.sql:15,17,19-20`
- Modify: `deploy/.env.example:23`
- Modify: `.github/workflows/release.yml:42,48,49,86,110`
- Modify: `docker_deployment.md:67,68,69,669,670,674,764`

**Interfaces:**
- Consumes: everything from Tasks 1-3.
- Produces: the final verified state.

- [ ] **Step 1: README — the opening paragraph, the version line, and the lineage line**

Line 3 is upstream marketing prose that now misdescribes this repository — it claims the project is "maintained by the Apache Software Foundation", which this fork is not. Replace it with:

```markdown
Roller is a Java-based, multi-user blog server. It is a fork of [Apache Roller](http://roller.apache.org), created in 2002 and maintained by the Apache Software Foundation, simplified substantially: PostgreSQL only, Markdown only, no comment subsystem, no Planet aggregator, and a Spring Boot executable WAR in place of the old servlet-container deployment.
```

Line 5, from `**Current Version:** 6.2.0 | **License:** Apache 2.0 | **Java:** 25` to:

```markdown
**Current Version:** 0.1.0 | **License:** Apache 2.0 | **Java:** 25
```

Line 58, from `Roller is PostgreSQL-only as of 6.2.0. Earlier releases generated vendor-specific` to:

```markdown
Roller has been PostgreSQL-only since this fork diverged. Earlier upstream releases generated vendor-specific
```

- [ ] **Step 2: CLAUDE.md**

Line 132, from `Roller is **PostgreSQL-only** as of 6.2.0. Development, test, and production all` to:

```markdown
Roller is **PostgreSQL-only** since this fork diverged from Apache Roller 6.1.x.
Development, test, and production all
```

- [ ] **Step 3: The migrations README**

Line 7, from `Roller is **PostgreSQL-only** as of 6.2.0. The pre-6.2.0 scheme — Velocity` to:

```markdown
Roller is **PostgreSQL-only** since this fork diverged. The pre-fork scheme — Velocity
```

Line 15, from `- `V002__baseline_schema.sql` — the complete 6.2.0 schema (20 tables)` to:

```markdown
- `V002__baseline_schema.sql` — the complete baseline schema (20 tables)
```

- [ ] **Step 4: The two migration file comments**

These are comments only. **Do not touch a single line of SQL** — these files have been applied to real databases and `SchemaMigrationTest` enforces their content.

`V001__schema_migrations.sql:22`, from `-- This replaces the pre-6.2.0 scheme, which stored a single` to:

```sql
-- This replaces the pre-fork scheme, which stored a single
```

`V002__baseline_schema.sql`, lines 15-20, from:

```sql
-- Migration: Roller 6.2.0 baseline schema
--
-- The complete Roller schema as of 6.2.0. This replaces the pre-6.2.0
-- createdb.vm Velocity template and the 310-to-400 ... 610-to-620 upgrade
-- chain, which generated per-vendor DDL for seven databases. Roller is now
-- PostgreSQL-only and 6.2.0 is the baseline; there is no upgrade path from
```

to:

```sql
-- Migration: Roller baseline schema
--
-- The complete Roller schema as of this fork's divergence. This replaces the
-- pre-fork createdb.vm Velocity template and the 310-to-400 ... 610-to-620
-- upgrade chain, which generated per-vendor DDL for seven databases. Roller is
-- now PostgreSQL-only and this is the baseline; there is no upgrade path from
```

The following line (`-- 6.1.x, so the tables belonging to the removed Planet Aggregator,`) stays as it is: `6.1.x` correctly names the upstream release there is no path from.

- [ ] **Step 5: `deploy/.env.example`**

Line 23, from `IMAGE_VERSION=6.2.0` to:

```bash
IMAGE_VERSION=0.1.0
```

- [ ] **Step 6: `.github/workflows/release.yml`**

Four comment lines carry example versions. Line 42 → `#   1. mvn versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false`. Line 48 → `#   3. git commit -am "release: 0.1.0"`. Line 49 → `#   4. git tag v0.1.0 && git push origin master v0.1.0`. Line 86 → the parenthetical becomes `` (`git push origin master v0.1.0`) ``. Line 110 → `          # v0.1.0 -> 0.1.0`.

- [ ] **Step 7: `docker_deployment.md`**

Lines 67-69: the three release-download URLs change `v6.2.0` to `v0.1.0`.

Lines 669-670: the local-verification snippet uses `6.2.1` as an upgrade example — both lines become `IMAGE_VERSION=0.1.1`. Line 674's prose reference to `` `IMAGE_VERSION=6.2.1` `` becomes `` `IMAGE_VERSION=0.1.1` ``.

Line 764: the parenthetical `(e.g. `6.2.0`)` becomes `(e.g. `0.1.0`)`.

- [ ] **Step 8: Verify the sweep left exactly what it should**

```bash
git grep -n "6\.2\.0" -- . ':!docs/superpowers'
```

Expected: **exactly two matches, both in `docker-compose.prod.yml` (lines 181 and 185), both referring to listmonk's pinned version.** Any other match is a miss. **Zero matches means the listmonk comment was corrupted** — check it immediately:

```bash
sed -n '178,188p' docker-compose.prod.yml
```
Expected: the comment still explains that `listmonk/listmonk:v3` did not exist and v6.2.0 was pinned by digest instead.

- [ ] **Step 9: Verify no Apache Roller branding survives outside licence files**

```bash
git grep -n "Apache Roller" -- . ':!docs/superpowers' ':!LICENSE.txt' ':!NOTICE.txt'
```
Expected: only deliberate lineage references — README's fork sentence, `CLAUDE.md`'s "diverged from Apache Roller 6.1.x", `DatabaseInstaller`'s error message, `RollerPostgresContainer`'s javadoc. Every match must be a sentence *about* upstream, never a claim to *be* upstream.

- [ ] **Step 10: Confirm the built artifact name is unchanged**

```bash
grep -n "finalName" app/pom.xml
grep -n "roller.war" Dockerfile
```
Expected: `<finalName>roller</finalName>` and the Dockerfile's `COPY --from=builder /build/app/target/roller.war /app/roller.war`. The WAR name comes from `finalName`, not the version, so the image build is unaffected — confirm rather than assume, because a change here would break the image build silently.

- [ ] **Step 11: Full build**

```bash
mvn -q -Pit validate && echo "poms resolve"
mvn -ntp clean install
```
Expected: `poms resolve`, then BUILD SUCCESS with the full unit suite and the JaCoCo floors met.

- [ ] **Step 12: Confirm the version actually reaches the runtime**

`RollerVersionTest` already exists for exactly this and ran as part of Step 11 — it loads `/roller-version.properties` from the classpath the same way `WebloggerImpl` does and fails if the value is blank or still contains an unresolved `${`. Confirm the concrete value too:

```bash
grep -n "ro.version" app/target/classes/roller-version.properties
```
Expected: `ro.version=0.1.0`. Together these are the end-to-end proof that the Maven property reached the filtered resource `WebloggerImpl` reads — the single mechanism every displayed version string depends on.

- [ ] **Step 13: Commit**

```bash
git add README.md CLAUDE.md bin/db/migrations/README.md \
        bin/db/migrations/V001__schema_migrations.sql \
        bin/db/migrations/V002__baseline_schema.sql \
        deploy/.env.example .github/workflows/release.yml docker_deployment.md
git commit -m "docs: renumber to 0.1.0 and name upstream as lineage, not identity"
```

---

## Self-Review Notes

**Spec coverage.** Every spec section maps to a task: the version number and the `-Pit` trap → Task 1; the seven user-visible strings and the translated bundles → Task 2; the schema guard → Task 3; the three categories of `6.2.0` mention → Tasks 3 (Java) and 4 (docs/config); the verification list → Task 4 Steps 8-12; the listmonk ambiguity → Global Constraints plus Task 4 Step 8, which is written to fail on zero matches rather than celebrate them.

**Two things worth flagging to a reviewer.**

Task 4 Step 1 rewrites README's opening paragraph, the only place in this plan where new prose is invented rather than an existing sentence adjusted. It is included because the current text states the project is maintained by the Apache Software Foundation — false for this fork, and a stronger misrepresentation than any version number.

Self-review found three defects in the first draft of this plan, all in the test names it cited. `FrontpageThemeRenderingTest` and `DatabaseInstallerTest` do not exist and were replaced with the real coverage (`FrontpageRenderingTest`, `FeedServletRenderingTest`, and an explicit note that `DatabaseInstaller` has no test at all). More usefully, the draft missed `RollerVersionTest` entirely: its javadoc quotes the exact footer string Task 2 rewrites, and its assertions are a better end-to-end proof of the filtered-resource mechanism than the manual grep the draft used. Both are now folded in.
