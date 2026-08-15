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

- [ ] **Step 4b: The install-wizard banner**

Added after Task 2's first review, which caught this: `installer.bannerTitleLeft` is rendered by `WEB-INF/jsps/tiles/bannerInstallation.jsp:23` as the navbar brand on the install wizard — the first screen anyone deploying this ever sees. Four bundles carry it:

```properties
app/src/main/resources/ApplicationResources.properties:326       installer.bannerTitleLeft=Apache Roller
app/src/main/resources/ApplicationResources_zh_CN.properties:283 installer.bannerTitleLeft=Apache Roller
app/src/main/resources/ApplicationResources_de.properties:156    installer.bannerTitleLeft=Apache Roller Weblogger
app/src/main/resources/ApplicationResources_ja.properties:809    installer.bannerTitleLeft=Apache Roller Weblogger
```

All four become:

```properties
installer.bannerTitleLeft=Roller
```

Also change the file-header comment at `ApplicationResources_ru.properties:20` from `# Russian messages for Apache Roller` to `# Russian messages for Roller`. It is a comment rather than shipped output, but leaving it means Step 6's acceptance grep can never reach zero, and an acceptance check that is expected to fail is worthless.

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

**Corrected after Task 3's first review.** The original wording here said "removed when this fork diverged", which is false: `roller_share_link` (V020) and `roller_comment` (V022) were features **this fork itself added and later removed** — they did not exist at the divergence point at all. The removals span the fork's life; they are not an instant. The replacement text below says so.

Line 68, from `Tables belonging to features removed in 6.2.0. The baseline must not` to:

```java
     * Tables belonging to features this fork has removed since diverging from
     * upstream. The baseline must not
```

Line 132, from `"Table '" + removed + "' belongs to a feature removed in 6.2.0 and "` to:

```java
                        "Table '" + removed + "' belongs to a feature this fork removed and "
```

Keep the following line (`"must not be in the baseline schema"`) as it is, so the assembled message still reads as one sentence: "Table 'x' belongs to a feature this fork removed and must not be in the baseline schema".

- [ ] **Step 5: Reword RollerPostgresContainer's javadoc**

**Corrected after Task 3's first review.** The original wording here claimed PostgreSQL-only status *since the divergence*, which contradicts the very next clause in the same sentence — "rather than the embedded Derby they used before" only makes sense if there was a period, inside this fork, when tests used Derby. CLAUDE.md's Database section confirms it: the Derby-in-test / PostgreSQL-in-prod split was something this fork ran with before the cutover. The claim must be a later event, not the divergence.

Line 25, from `<p>Roller is PostgreSQL-only as of 6.2.0, and tests run against the same` to:

```java
 * <p>Roller became PostgreSQL-only during this fork's simplification, and
 * tests run against the same
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
- Modify: `app/pom.xml:245` (a comment — the version elements were already handled in Task 1)
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

- [ ] **Step 2b: The comment in `app/pom.xml`**

Task 1 changed this file's `<version>` elements but deliberately left its prose alone. Line 245 still reads:

```xml
        <!-- PostgreSQL is the only database Roller supports as of 6.2.0.
```

Change it to:

```xml
        <!-- PostgreSQL is the only database Roller supports since this fork
             diverged from Apache Roller 6.1.x.
```

Keep the following two comment lines (`Version comes from Spring Boot's BOM; …`) exactly as they are, and mind the continuation indentation so the comment block still lines up.

This step was added after Task 1's review: the original plan's sweep list missed this file, which would have made Step 8's verification fail with an unexplained third match.

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

### Task 4 corrections (after review) — stop making timing claims

Task 4's review verified against git history that the fork's first commit sat at version `6.1.5`, and PostgreSQL-only arrived at `021c6004f` — **54 commits and about eight months later**, after the Jakarta EE migration, the Struts2→Spring MVC rewrite, and the Planet/Bookmarks/Pings removals. So "PostgreSQL-only since this fork diverged" is false by that entire span, and "the schema as of this fork's divergence" is false for the same reason: V002 already reflects months of fork-only feature removal.

This is the third time in this plan that lineage prose anchored an event at the divergence point when it happened later. **The rule going forward is to drop the timing claim rather than relocate it** — the migrations README's own successful rewording ("the complete baseline schema", no date at all) is the pattern to copy.

Corrected text, replacing what Steps 1, 2, 2b, 3 and 4 originally prescribed:

- `README.md:58` → `Roller is PostgreSQL-only. Earlier upstream releases generated vendor-specific`
- `CLAUDE.md:132` → `Roller is **PostgreSQL-only**. Development, test, and production all`
- `app/pom.xml:245` → `<!-- PostgreSQL is the only database Roller supports.` (keep the following two comment lines unchanged). Note this removes app/pom.xml's "Apache Roller" mention entirely, which lowers Task 5's expected match count by one.
- `bin/db/migrations/README.md:7` → `Roller is **PostgreSQL-only**. The pre-fork scheme — Velocity`
- `bin/db/migrations/V002__baseline_schema.sql:17` → `-- The complete Roller schema as of this fork's move to PostgreSQL. This replaces the` (accurate: V002 was created by that commit)

Also correct the cross-reference the review found broken: `docker_deployment.md` claims its `IMAGE_VERSION` snippet "Matches the same snippet in `docs/superpowers/specs/2026-08-14-container-push-deployment-design.md`". That spec is on the do-not-touch list and still says `6.2.1`, so the claim is now false. Delete the "matches" sentence rather than edit the spec — a historical record should not be rewritten to preserve a cross-reference.

### Task 5: Finish the identity cleanup (added after Task 4)

Task 4's verification found 23 surviving "Apache Roller" mentions where the plan predicted 3 — the Step 9 grep scans the whole repository, not just shipped output, and the plan's expectation was wrong. The implementer escalated rather than reinterpreting, which is what surfaced this.

Triage of the 23:
- **4 are correct** and stay: the deliberate lineage references in `README.md:3`, `CLAUDE.md:132`, `app/pom.xml:246`, `DatabaseInstaller.java:108`. Each says the fork *came from* Apache Roller, which is true and required.
- **2 are accurate history** and stay: `CLAUDE.md:720` and `docs/design/design-system.md:68` describe the admin rail as replacing the old "Powered by Apache Roller" card. True statements about what was removed.
- **The remaining 17** are this task.

**Files:**
- Modify: `README.md:1`, `README.md:124-129`
- Modify: `CLAUDE.md:156`
- Modify: `CONTRIBUTING.md:1-3`
- Modify: `.github/workflows/main.yml:18`
- Modify: `app/src/main/resources/log4j2.xml:24`
- Modify: `app/src/test/resources/log4j2.xml:20`
- Modify: `app/src/main/resources/org/apache/roller/weblogger/config/roller.properties:273`
- Modify: `docs/README.md:5-7`
- Delete: `docs/roller-install-guide.adoc`, `docs/roller-template-guide.adoc`, `docs/roller-user-guide.adoc`
- Delete: `docs/images/` (42 files, 3.8MB)

**Interfaces:** consumes Tasks 1-4; produces the final state.

- [ ] **Step 1: README's H1 and CLAUDE.md's identity line**

`README.md:1`, from `# Apache Roller` to:

```markdown
# Roller
```

`CLAUDE.md:156`, from `Apache Roller is a multi-user blog server built with:` to:

```markdown
Roller is a multi-user blog server built with:
```

Both are identity claims — they say this software *is* Apache Roller. `README.md:3`'s "It is a fork of [Apache Roller]" stays exactly as it is; that one is lineage.

- [ ] **Step 2: The three infrastructure names**

`.github/workflows/main.yml:18`, from `name: Apache Roller` to:

```yaml
name: Roller
```

`app/src/main/resources/log4j2.xml:24` and `app/src/test/resources/log4j2.xml:20`, both from `<Configuration status="warn" name="Apache Roller" >` to:

```xml
<Configuration status="warn" name="Roller" >
```

- [ ] **Step 3: The properties comment**

`app/src/main/resources/org/apache/roller/weblogger/config/roller.properties:273`, from `# Authentication method for Apache Roller. Use 'db' for database-based authentication.` to:

```properties
# Authentication method for Roller. Use 'db' for database-based authentication.
```

- [ ] **Step 4: CONTRIBUTING.md**

It is currently three lines: a title carrying the ASF's registered trademark, and a pointer to Apache's contribution wiki — upstream's process, not this fork's. Replace the whole file with:

```markdown
# Contributing to Roller

This is a personal fork of [Apache Roller](https://roller.apache.org), simplified
substantially and maintained independently. It is not affiliated with or endorsed
by the Apache Software Foundation.

To contribute to the upstream project instead, see
[How to contribute to Roller](https://cwiki.apache.org/confluence/x/2hsB).
```

The trademark symbol goes with it: "Apache Roller®" on a fork's contributing guide reads as a claim to be the upstream project.

- [ ] **Step 5: Delete the three legacy guides**

```bash
git rm docs/roller-install-guide.adoc docs/roller-template-guide.adoc docs/roller-user-guide.adoc
```

These are upstream's manuals. They document Derby, the comment subsystem, the Planet aggregator, Bookmarks/Blogroll, Pings/Trackbacks and vendor-specific DDL — every one of which this fork has removed. They are not merely misnamed; a reader following them is actively misled.

- [ ] **Step 6: Delete the orphaned screenshots**

```bash
git rm -r docs/images
```

All 42 files (3.8MB) are referenced only by the three guides just deleted — verified by grepping every filename against the rest of the tree. They are also screenshots of an admin UI this fork rebuilt (see CLAUDE.md's Admin UI section), so they were doubly stale.

Leave `docs/readme-images/` alone. Nothing references it either, but it is unrelated to the guides and its removal is not this task's decision.

- [ ] **Step 7: Fix the now-dangling references**

`README.md`'s Documentation section currently links all three deleted guides. Replace lines 124-129 — from `Detailed guides are available in the [`docs/`](docs/) directory:` through the Template Guide bullet — with:

```markdown
- **[Production Deployment Runbook](docker_deployment.md)** — Fresh-VPS Docker deployment, TLS, backups, upgrades
- **[Design System](docs/design/design-system.md)** — the admin UI's tokens, type scale and layout rules
```

Keep the existing Production Deployment Runbook bullet rather than duplicating it — the result should list exactly those two entries.

`docs/README.md`, replace the three guide bullets (lines 5-7) so the file reads:

```markdown
# docs/README.md

In this directory you'll find design documentation and various examples.

* `design/` - the admin UI design system and approved component mockups
* `examples/` - example configuration and script files
```

- [ ] **Step 8: Verify**

```bash
git grep -n "Apache Roller" -- . ':!docs/superpowers' ':!LICENSE.txt' ':!NOTICE.txt'
```

Expected: **exactly 6 matches** — the four lineage references (`README.md:3`, `CLAUDE.md:132`, `app/pom.xml:246`, `DatabaseInstaller.java:108`), the two accurate historical ones (`CLAUDE.md:720`, `docs/design/design-system.md:68`), and the new `CONTRIBUTING.md` lineage sentences. Note `CONTRIBUTING.md` adds two, so **8 is also correct** if its replacement text mentions Apache Roller twice; count by reading, not by number alone. Every surviving match must be a statement *about* upstream, never a claim to *be* upstream.

```bash
git grep -n "roller-install-guide\|roller-template-guide\|roller-user-guide" -- . ':!docs/superpowers'
```
Expected: no matches. A dangling link to a deleted file is worse than the stale file was.

```bash
mvn -ntp clean install
```
Expected: BUILD SUCCESS. The log4j2 config name change touches both the main and test logging configuration, so this confirms logging still initialises.

- [ ] **Step 9: Commit**

```bash
git add -A README.md CLAUDE.md CONTRIBUTING.md docs/README.md .github/workflows/main.yml \
        app/src/main/resources/log4j2.xml app/src/test/resources/log4j2.xml \
        app/src/main/resources/org/apache/roller/weblogger/config/roller.properties \
        docs/roller-install-guide.adoc docs/roller-template-guide.adoc docs/roller-user-guide.adoc docs/images
git commit -m "docs: finish the identity cleanup and retire upstream's manuals"
```

---

### Whole-branch review corrections (after Task 5)

The final review of the complete branch found six things. Recorded here rather
than silently folded into the steps above, because the *pattern* is the useful
part.

**1. `pre-fork` was a relocated timing claim, not a dropped one — the fifth
recurrence.** Tasks 3 and 4 rewrote `pre-6.2.0` to `pre-fork` in four places
(`DatabaseInstaller.java:45`, `bin/db/migrations/README.md:7`,
`V001__schema_migrations.sql:22`, `V002__baseline_schema.sql:18`). All four
were false by the same 54-commit, eight-month span the Task 4 correction had
just finished measuring: this fork carried the Texen/`createdb.vm`/
`upgradeToNNN` design from its first commit until `021c6004f`. The Task 4
correction's own rule — *drop the timing claim rather than relocate it* — was
written and then not applied to the word that had just replaced it. All four
now read `the earlier ...`, which makes no claim about when. `V002:17`'s
"as of this fork's move to PostgreSQL" is correct and stays: `021c6004f`
created that file.

**2. Task 2 Step 6's second grep did not pass, and was reported as passing.**
The check
`grep -rn "roller\.apache\.org" app/src/main/webapp/themes/ app/src/main/webapp/WEB-INF/velocity/ ...`
returns `app/src/main/webapp/WEB-INF/velocity/feeds.vm:36` — an Atom
`<category scheme="http://roller.apache.org/ns/tags/">` emitted into every
feed, the same category of artefact as the `<generator uri>` two lines away
that Step 1 *did* remove. `scheme` is optional in RFC 4287; the attribute is
now dropped. This is the same failure mode as the Task 1 deviation below, one
step further along: an acceptance command whose output was not read.

**3. No string grep can find a logo.** Every acceptance check in this plan
greps for `Apache Roller` and `roller.apache.org`. `WEB-INF/jsps/tiles/
footer.jsp:27` shipped the ASF feather (`alt="ASF logo"`) on every admin page,
immediately beside the string Step 3 rewrote to "Powered by Roller" — leaving
the branding removed from the text and intact in the image next to it, while
`CONTRIBUTING.md` disclaimed ASF endorsement. Removed, along with
`images/tinyfeather.png`, the unreferenced `roller-ui/images/feather.svg`, and
the now-dead `.roller-footer-mark` CSS rule. **Any future branding sweep needs
an asset-level check beside the string ones.** Both of these must return no
matches — and note they deliberately do *not* match the bare word `ASF`, which
appears in the licence header of nearly every file in the tree and would drown
the signal:

```bash
git grep -n -iE '(feather|asf[ _-]?logo|apache[a-z0-9_-]*\.(png|svg|gif|jpe?g))' -- app/src/main/webapp
git ls-files app/src/main/webapp | grep -iE '\.(png|svg|gif|jpe?g|ico)$' | grep -iE 'feather|apache|asf'
```

**4. Step 8's expected match list was stale.** It named `CLAUDE.md:132` and
`app/pom.xml:246` as surviving lineage references; the Task 4 correction had
already removed the "Apache Roller" mention from both. That correction noted
the `app/pom.xml` consequence (line 577) and missed the `CLAUDE.md` one. The
true surviving set is **five**, all verified as statements *about* upstream:
`README.md:5` (the fork paragraph), `CLAUDE.md:719` and
`docs/design/design-system.md:68` (accurate history of the removed "Powered by
Apache Roller" card), `CONTRIBUTING.md:3`, `DatabaseInstaller.java:108`. Plus
`NOTICE.txt`, which is a licence obligation and excluded from the grep.

**5. Two count/verification slips in the briefs themselves.** Task 2's header
says "seven strings" where its steps cover eight (and Step 4b later added four
more bundles plus the `_ru` comment, so the number was stale twice over).
Task 1's implementer ran a narrower verification grep than Step 5 specified,
reached the right conclusion by a different route, and did not note the
deviation. Both are harmless in isolation; together with (2) they are one
habit, which is why they are listed rather than dropped.

**6. Cross-wave prose disagreed with this branch's own premise.** This plan's
justification is that nothing has ever been published. `docker_deployment.md`'s
Upgrades section — written during the preceding deployment wave — described
"this very release" as a counterexample to an earlier one and cited "an
operator who did exactly that", which after the reset names a release history
that does not exist. Rewritten in the conditional. `.github/workflows/
release.yml:42` had the mirror-image problem: it documented the release
procedure as `mvn versions:set` **without** `-Pit`, the one trap this plan
opens by calling load-bearing. Now `mvn -Pit versions:set -DnewVersion=0.1.1`,
with the reason inline. A renumbering plan has to leave the *next* renumbering
correct, not just this one.

**7. The sweep greps were scoped to the wrong axis, and it hid three more.**
Every check in this plan looks for the capitalised phrase `Apache Roller`
repo-wide, or for `roller.apache.org` **only under `app/src/main/webapp/`**.
Neither shape matches a bare lowercase URL outside the webapp, so three
identity claims sat in plain sight until a final repo-wide URL sweep:

- `pom.xml`'s `<url>` and `<scm>` declared `roller.apache.org` and
  `github.com/apache/roller` as *this project's* homepage and repository.
- `README.md`'s two Quick Start blocks told a developer to
  `git clone https://github.com/apache/roller.git` — not a branding slip but a
  **functional** defect: following this project's own README gets you
  upstream's code, not this codebase.
- `README.md`'s Contributing section listed `dev@roller.apache.org` and three
  `cwiki.apache.org` links as if the ASF's channels served this fork, which
  `CONTRIBUTING.md` explicitly disclaims.

The lesson generalises past branding: **sweep by identifier, not by prose.**
The three checks that would have caught all of it are

```bash
git grep -n "roller\.apache\.org" -- . ':!docs/superpowers'
git grep -n "github\.com/apache" -- . ':!docs/superpowers'
git grep -niE "cwiki\.apache|dev@roller" -- . ':!docs/superpowers'
```

Two `apache.org` references are correct and must survive these greps:
`WEB-INF/jsps/taglibs-spring.jsp:7` + `WEB-INF/rollerConfig.tld:9` are a
matched TLD-URI pair (an XML identifier, invisible to users, and breakable if
only one side is edited), and `app/pom.xml:437` cites Maven's own
`issues.apache.org/jira/browse/MNG-1977`, which has nothing to do with Roller.
Same trap as the listmonk `6.2.0` pin: an `apache.org` string that is not an
identity claim. `CONTRIBUTING.md`'s cwiki link is also deliberate — it points
contributors at upstream's process for contributing *to upstream*.

**8. The install wizard still sent deployers to the ASF's mailing list, in five
bundles.** `installer.whatHappenedUnknown` ends with "Follow the instructions on
the Roller wiki and seek help from the *Roller user mailing list*", linking
`cwiki.apache.org` — shipped, user-visible text on the same install screen whose
banner Step 4b fixed, telling someone deploying *this* fork to go and email the
ASF about it. It survived every check because the string contains neither
`Apache Roller` nor `roller.apache.org`.

The referral clause is now cut from all five (`ApplicationResources`, `_de`,
`_ja`, `_ko`, `_zh_CN`), keeping each translation's actionable first half
("look at your server's log files and diagnose the problem yourself"). `_ko` is
notable: **no earlier task in this plan touched it at all**, so a bundle outside
the edited set still carried branding — when sweeping resource bundles, sweep
*all* of `ApplicationResources*`, not the subset a previous step happened to
name.

These files are `native2ascii`-encoded and must stay pure ASCII, so the three
`\uXXXX` bundles were edited by exact substring match on the raw file text
rather than by hand, and verified afterwards with
`LC_ALL=C grep -cP '[^\x00-\x7F]'` against the same count at `HEAD`. (`_de` has
one pre-existing non-ASCII line at 388 and `_fr` has 236; both counts are
unchanged.)

---

## Self-Review Notes

**Spec coverage.** Every spec section maps to a task: the version number and the `-Pit` trap → Task 1; the seven user-visible strings and the translated bundles → Task 2; the schema guard → Task 3; the three categories of `6.2.0` mention → Tasks 3 (Java) and 4 (docs/config); the verification list → Task 4 Steps 8-12; the listmonk ambiguity → Global Constraints plus Task 4 Step 8, which is written to fail on zero matches rather than celebrate them.

**Two things worth flagging to a reviewer.**

Task 4 Step 1 rewrites README's opening paragraph, the only place in this plan where new prose is invented rather than an existing sentence adjusted. It is included because the current text states the project is maintained by the Apache Software Foundation — false for this fork, and a stronger misrepresentation than any version number.

Self-review found three defects in the first draft of this plan, all in the test names it cited. `FrontpageThemeRenderingTest` and `DatabaseInstallerTest` do not exist and were replaced with the real coverage (`FrontpageRenderingTest`, `FeedServletRenderingTest`, and an explicit note that `DatabaseInstaller` has no test at all). More usefully, the draft missed `RollerVersionTest` entirely: its javadoc quotes the exact footer string Task 2 rewrites, and its assertions are a better end-to-end proof of the filtered-resource mechanism than the manual grep the draft used. Both are now folded in.
