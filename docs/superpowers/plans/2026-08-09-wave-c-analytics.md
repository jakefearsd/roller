# Wave C — Analytics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Structured per-weblog Umami analytics injection (no raw HTML crossing the trust boundary), the versioned SQL contract Grafana reads (views + a read-only role), and deletion of the legacy hitcount subsystem Umami replaces.

**Architecture:** `Weblog.analyticsSiteId` (a UUID) replaces the raw-`<script>` textarea procedure that `weblogAdminsUntrusted` correctly made impossible: `#showAnalyticsTrackingCode` now *builds* the script tag itself from the site id plus startup-scoped config, so nothing an admin types is ever emitted verbatim into `<head>`. The tracker stays same-origin (`/analytics/script.js` via the existing Caddy handle) so the pinned theme CSPs never change. The Grafana contract is split across the two databases that actually exist: `rollerdb`'s migration chain ships views over `roller_event` plus a guarded read-only role, while the Umami-side traffic view ships as a separate idempotent script applied to the `umami` database by `deploy.sh`. The hitcount subsystem (queue, task, pojo, table, hot-blogs UI) is deleted whole.

**Tech Stack:** Java 25, Spring Boot 4.1, EclipseLink JPA, PostgreSQL 16, Velocity, JSP/JSTL, JUnit 5 + Mockito + Testcontainers, Selenide, Caddy, Umami v2 (postgresql image).

**Spec:** `docs/superpowers/specs/2026-08-08-pages-audience-analytics-design.md` (Wave C section + Cross-cutting)

**Wave base commit:** `d4b5c6dce` (Wave B's final commit). Diff coverage in the last task runs against this ref.

## Deviations from the spec, called out for review

1. **The Umami-side view cannot live "in the migration chain."** Umami stores its data in a **separate PostgreSQL database** (`umami`) inside the shared instance (`docker-compose.prod.yml:120-149`), and PostgreSQL has no cross-database queries. The migration chain (`bin/db/migrations/`, three appliers) only ever connects to `rollerdb`. So: the `roller_event` views and the read-only role land in `V017` in the chain as the spec asks; the Umami-traffic view ships as `deploy/analytics/umami-views.sql` — versioned in this repo, idempotent (`CREATE OR REPLACE VIEW` + `GRANT`), applied by `deploy.sh` against the `umami` database in the same step that already creates the service databases. Grafana joins across the two databases with two datasources, keyed on the Umami website UUID, which `rollerdb`'s `analytics_weblog_sites` view maps to weblog handles. The anti-corruption property survives: every shape Grafana touches is a view this repo owns.
2. **`SQLScriptRunner` must be fixed before V017 can carry a guarded `CREATE ROLE`.** Roles are cluster-global, so `CREATE ROLE` must be wrapped in a `DO $$ ... EXCEPTION WHEN duplicate_object ... $$;` guard to survive `SchemaMigrationTest`'s re-apply check and the shared test cluster — but the web install wizard's `SQLScriptRunner` splits SQL on semicolons with no dollar-quote awareness, so a `DO $$` block would silently break that third applier, and **no test currently exercises it against the real chain**. Task 1 fixes the splitter and adds that missing test; V017 depends on it.
3. **The role ships `NOLOGIN` with no password.** A migration cannot carry a secret. `V017` creates `grafana_ro` as NOLOGIN with SELECT on exactly the contract views; the operator enables it with `ALTER ROLE grafana_ro LOGIN PASSWORD '...'` over `docker compose exec` (documented). 5432 stays unpublished; access is tunnel-only, per the spec.
4. **Legacy `analyticsCode` behavior is left intact** (the macro's old branches and the gated textarea remain). The structured path is checked first; removing the legacy field is a separate decision this wave does not take.

## Global Constraints

Every task inherits these. From the spec.

- **`weblogAdminsUntrusted` stays `true`.** The entire point of `analyticsSiteId`: the macro builds the `<script>` tag from a validated UUID and startup config; no admin-typed markup reaches `<head>`. No sanitizer changes at all this wave.
- **ZERO theme-CSP changes.** The tracker is same-origin via Caddy's existing `/analytics/*` handle — that is *why* it works under `script-src 'self'`. `ThemeCspCoverageTest.everyPolicyStillAllowsSameOriginScriptsAndBeacons` already pins the invariant.
- **Every schema change adds a numbered idempotent migration**; never edit an applied one. This wave adds `V017` (and the out-of-chain `deploy/analytics/umami-views.sql`, versioned in-repo).
- **Migrations must pass ALL THREE appliers**: `migrate.sh` (psql), the test harness (single-string JDBC), and `DatabaseInstaller`/`SQLScriptRunner` — Task 1's new test makes the third one real.
- **Grafana's access is the view layer**: `grafana_ro` gets SELECT on the contract views and nothing else — not `roller_user`'s privileges, not raw tables.
- **Untrusted label columns:** `roller_event.page_slug`/`entry_anchor` on `FORM_SUBMITTED` rows are client-seeded text (Wave B's contact endpoint copies them from the reader-controlled `source` field). The view carries a SQL comment saying so, and the docs tell dashboard authors to treat them as untrusted display text. Republished entries record a second `ENTRY_PUBLISHED` (Wave B's documented mechanism) — dashboards count "publish events," not "distinct published entries."
- **Controllers name every `@RequestParam`/`@PathVariable`**; ownership-check every id; every new message key referenced (`MessageKeyTest` ratchet); new GETs into `Routes.java`.
- **Coverage gates:** ~90% diff coverage on changed lines (`bin/check-diff-coverage.sh d4b5c6dce`); floors only rise; a browser IT for the new public surface behavior.
- **Tests clean up after themselves**; rendering tests clear caches in `@BeforeEach`.
- **Commit on `master`.** Solo-developer repo; no feature branch. Never commit while any test fails.

## File Structure

| File | Responsibility |
| --- | --- |
| `business/startup/SQLScriptRunner.java` | Dollar-quote-aware statement splitting (Task 1) |
| `app/src/test/java/.../startup/SqlScriptRunnerMigrationTest.java` *(new)* | Runs the REAL chain through SQLScriptRunner |
| `bin/db/migrations/V017__analytics_contract.sql` *(new)* | Drop `roller_hitcounts`; `analytics_site_id`/`analytics_share_url` columns; `analytics_events` + `analytics_weblog_sites` views; guarded `grafana_ro` role + grants |
| `deploy/analytics/umami-views.sql` *(new)* | `analytics_traffic` view in the `umami` database + grants |
| `deploy/deploy.sh` | Applies the umami views after the DB-ensure step |
| ~25 files (Task 3) | Hitcount subsystem deletion (inventory in the task) |
| `pojos/Weblog.java` / `Weblog.orm.xml` / `wrapper/WeblogWrapper.java` | `analyticsSiteId`, `analyticsShareUrl` |
| `ui/controllers/editor/WeblogConfigBean.java` / `WeblogConfigController.java` / `WeblogConfig.jsp` | Settings fields + validation |
| `WEB-INF/velocity/weblog.vm` | `#showAnalyticsTrackingCode` structured branch |
| `ui/rendering/model/ConfigModel.java` | `analyticsScriptPath()` etc. from startup props |
| `config/roller.properties` | `analytics.umami.*` startup block |
| `docker_deployment.md`, `CLAUDE.md` | Rewritten analytics docs |
| `it-selenium/.../AnalyticsInjectionIT.java` *(new)* | Tag present/absent end-to-end |

---

# Task 1: Make `SQLScriptRunner` dollar-quote-aware, and test it against the real chain

The web install wizard is the third migration applier, and today it splits SQL on semicolons line-by-line with no `$$` awareness — a `DO $$` block (which V017 needs for its cluster-global `CREATE ROLE` guard) would be corrupted into broken fragments, and **no test runs `SQLScriptRunner` over the real migrations**, so nothing would catch it.

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/startup/SQLScriptRunner.java:44-77`
- Test: `app/src/test/java/org/apache/roller/weblogger/business/startup/SQLScriptRunnerTest.java` (extend if it exists, else create), `app/src/test/java/org/apache/roller/weblogger/business/startup/SqlScriptRunnerMigrationTest.java` *(new)*

**Interfaces:**
- Produces: `SQLScriptRunner` whose statement splitting treats everything between `$tag$ ... $tag$` (any tag, including the empty tag `$$`) as one statement body — semicolons inside a dollar-quoted block never split.

- [ ] **Step 1: Write the failing unit test**

In `SQLScriptRunnerTest` (create with the ASF header if absent, in the startup test package), add:

```java
    @Test
    void aDollarQuotedDoBlockStaysOneStatement() throws Exception {
        String sql = """
                CREATE TABLE IF NOT EXISTS t1 (id int);
                DO $$ BEGIN
                    CREATE ROLE somerole;
                EXCEPTION WHEN duplicate_object THEN
                    NULL;
                END $$;
                CREATE TABLE IF NOT EXISTS t2 (id int);
                """;
        SQLScriptRunner runner = new SQLScriptRunner(
                new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));

        List<String> commands = runner.getCommandList();

        assertEquals(3, commands.size(), "got: " + commands);
        assertTrue(commands.get(1).startsWith("DO $$"), commands.get(1));
        assertTrue(commands.get(1).contains("EXCEPTION WHEN duplicate_object"),
                "the block must survive intact: " + commands.get(1));
    }

    @Test
    void aTaggedDollarQuoteAlsoStaysOneStatement() throws Exception {
        String sql = "DO $guard$ BEGIN PERFORM 1; END $guard$;\nSELECT 1;";
        SQLScriptRunner runner = new SQLScriptRunner(
                new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, runner.getCommandList().size(),
                "got: " + runner.getCommandList());
    }
```

(Check `SQLScriptRunner`'s actual accessor for the parsed list — the constructor builds `commands`; if the accessor is named differently, use the real name. If none exists, add a package-private `List<String> getCommandList()`.)

- [ ] **Step 2: Run and watch it fail**

Run: `mvn -pl app test -Dtest=SQLScriptRunnerTest`
Expected: FAIL — the DO block is split at its internal semicolons.

- [ ] **Step 3: Fix the splitter**

Rewrite the constructor's accumulation loop to track dollar-quote state: scan each appended line for `$tag$` delimiters (regex `\$[A-Za-z0-9_]*\$`); while inside an open dollar quote, semicolons do NOT terminate the command and `--` comment-stripping is suspended (a `--` inside a function body is content, not a comment). Keep the existing behavior for everything outside dollar quotes byte-for-byte (existing installer scripts must parse identically — the existing tests, if any, plus the migration test in Step 4 are the proof). Preserve line-joining with single spaces as today.

- [ ] **Step 4: Write the real-chain test**

Create `SqlScriptRunnerMigrationTest.java`:

```java
package org.apache.roller.weblogger.business.startup;

// ASF header as in SchemaMigrationTest

/**
 * SchemaMigrationTest proves the chain through psql-style whole-string
 * execution. The install wizard parses the SAME files through
 * SQLScriptRunner's own splitter -- a third applier with its own grammar,
 * previously untested against the real chain. V017's DO-block role guard is
 * exactly the construct the old splitter corrupted.
 */
class SqlScriptRunnerMigrationTest {

    @Test
    void everyMigrationParsesAndAppliesThroughTheInstallWizardsSplitter() throws Exception {
        // Build a fresh database exactly as SchemaMigrationTest.freshDatabase does
        // (reuse its helper if accessible; otherwise copy its container/DDL setup),
        // then for each migration file in order:
        //   SQLScriptRunner runner = new SQLScriptRunner(Files.newInputStream(migration));
        //   runner.runScript(connection, false);
        //   assertTrue(runner.getErrors().isEmpty(), migration + " errors: " + runner.getErrors());
        // Then re-apply the whole chain the same way and assert no errors
        // (idempotency through THIS applier too).
    }
}
```

Follow `SchemaMigrationTest`'s container conventions exactly (`RollerPostgresContainer`, fresh database per test, `MigrationFiles.all()`). The `runScript(con, false)` failonerror-false variant is what `DatabaseInstaller` actually calls with `true` — use `true` so errors throw, and drop the manual error assertion if redundant; match `DatabaseInstaller.java:189-199`'s usage precisely.

- [ ] **Step 5: Run the tests**

Run: `mvn -pl app test -Dtest='SQLScriptRunnerTest,SqlScriptRunnerMigrationTest,SchemaMigrationTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/business/startup/SQLScriptRunner.java \
        app/src/test/java/org/apache/roller/weblogger/business/startup/
git commit -m "Teach the install wizard's SQL splitter dollar quoting, and test it on the real chain"
```

---

# Task 2: `V017__analytics_contract.sql`

**Files:**
- Create: `bin/db/migrations/V017__analytics_contract.sql`
- Modify: `app/src/test/java/org/apache/roller/weblogger/business/startup/SchemaMigrationTest.java` (`EXPECTED_TABLES`: remove `roller_hitcounts`; follow the file's `REMOVED_FEATURE_TABLES` documentation convention)
- Test: `SchemaMigrationTest`, `SqlScriptRunnerMigrationTest` (Task 1)

**Interfaces:**
- Consumes: the fixed `SQLScriptRunner` (Task 1).
- Produces: columns `weblog.analytics_site_id varchar(64)`, `weblog.analytics_share_url varchar(255)`; views `analytics_events`, `analytics_weblog_sites`; role `grafana_ro` (NOLOGIN, guarded); table `roller_hitcounts` dropped.

- [ ] **Step 1: Write the migration**

ASF header (V013 style), then:

```sql
-- Migration: the analytics contract
--
-- 1. Per-weblog Umami wiring: analytics_site_id is the Umami website UUID
--    the theme macro builds the tracker tag from -- structured data, not
--    raw HTML, which is what lets per-weblog analytics coexist with
--    weblogAdminsUntrusted. analytics_share_url is the operator's saved
--    link to the Umami share dashboard; display-only.
--
-- 2. The Grafana contract, rollerdb half: versioned views over first-party
--    events, plus the site-id-to-handle mapping Grafana joins Umami traffic
--    against. The Umami half (analytics_traffic) lives in the umami
--    DATABASE -- PostgreSQL has no cross-database queries, so it ships as
--    deploy/analytics/umami-views.sql, applied by deploy.sh. Both halves
--    are views this repo owns: replacing Umami later rewrites views, not
--    dashboards.
--
-- 3. grafana_ro: cluster-global role, SELECT on the contract views and
--    nothing else. Created NOLOGIN with no password (a migration cannot
--    carry a secret); the operator enables login out of band. The DO block
--    guard is what makes a cluster-global CREATE ROLE idempotent, and the
--    install wizard's SQL splitter learned dollar quoting in the same wave
--    precisely so this block survives all three appliers.
--
-- 4. roller_hitcounts drops: Umami owns traffic counting now. The table
--    held one zeroed-daily number per weblog, fed a sidebar no bundled
--    theme except frontpage rendered, and reset itself nightly -- there is
--    no history to migrate.
--
-- Prerequisites: V015__form_submissions_and_tokens (roller_event),
-- V016__newsletter_wiring.

ALTER TABLE weblog
    ADD COLUMN IF NOT EXISTS analytics_site_id varchar(64);

ALTER TABLE weblog
    ADD COLUMN IF NOT EXISTS analytics_share_url varchar(255);

DROP TABLE IF EXISTS roller_hitcounts;

-- First-party outcomes by weblog and day. page_slug and entry_anchor on
-- FORM_SUBMITTED rows originate from a reader-controlled field: treat them
-- as untrusted display text in any dashboard. ENTRY_PUBLISHED counts
-- publish EVENTS; a republished entry records again.
CREATE OR REPLACE VIEW analytics_events AS
SELECT w.handle                              AS weblog_handle,
       e.event_type                          AS event_type,
       e.entry_anchor                        AS entry_anchor,
       e.page_slug                           AS page_slug,
       CAST(date_trunc('day', e.occurred_at) AS date) AS day,
       count(*)                              AS events
FROM roller_event e
JOIN weblog w ON w.id = e.weblogid
GROUP BY w.handle, e.event_type, e.entry_anchor, e.page_slug,
         CAST(date_trunc('day', e.occurred_at) AS date);

-- The join key between the two databases: which Umami website id is which
-- weblog. Grafana joins analytics_traffic (umami database) to this.
CREATE OR REPLACE VIEW analytics_weblog_sites AS
SELECT handle            AS weblog_handle,
       analytics_site_id AS website_id
FROM weblog
WHERE analytics_site_id IS NOT NULL;

-- Cluster-global; guarded so re-applying the chain (and applying it to the
-- test cluster's many databases) is a no-op.
DO $$ BEGIN
    CREATE ROLE grafana_ro NOLOGIN;
EXCEPTION WHEN duplicate_object THEN
    NULL;
END $$;

GRANT SELECT ON analytics_events TO grafana_ro;
GRANT SELECT ON analytics_weblog_sites TO grafana_ro;
```

- [ ] **Step 2: Update `SchemaMigrationTest`'s table expectations**

Remove `"roller_hitcounts"` from `EXPECTED_TABLES` and record the removal per the file's own `REMOVED_FEATURE_TABLES` convention (a V017 note beside the 6.2.0 removals).

- [ ] **Step 3: Run the migration tests**

Run: `mvn -pl app test -Dtest='SchemaMigrationTest,SqlScriptRunnerMigrationTest'`
Expected: PASS — including idempotent re-apply through BOTH appliers (the DO-block guard and `CREATE OR REPLACE`/`GRANT` idempotency are what make it so).

Note: the app's JPA tests will FAIL after this migration until Task 3 deletes the `WeblogHitCount` mapping (the table its orm.xml maps no longer exists). That is expected mid-wave sequencing; Tasks 2 and 3 land as ONE commit if the suite cannot be made green between them — **check**: `WeblogHitCount.orm.xml` maps a now-dropped table, but EclipseLink with `metadata-complete` mappings does not validate tables at bootstrap unless queried; `HitCountTest` queries them. Decide by evidence: run `mvn -pl app test -Dtest='WeblogEntryManagerQueryTest,HitCountTest'` — if `HitCountTest` alone fails, fold Tasks 2+3 into one commit (the commit message below covers both); if the persistence unit itself fails, same answer. Never leave master red between tasks.

- [ ] **Step 4: Commit** (alone if green, else together with Task 3)

```bash
git add bin/db/migrations/V017__analytics_contract.sql \
        app/src/test/java/org/apache/roller/weblogger/business/startup/SchemaMigrationTest.java
git commit -m "V017: analytics columns, contract views, grafana_ro, drop hitcounts"
```

---

# Task 3: Delete the hitcount subsystem

Pure deletion, driven by a complete reference inventory (researched; every line pre-verified). No behavior replaces it — Umami owns traffic counting.

**Files — delete outright:**
- `app/src/main/java/org/apache/roller/weblogger/business/HitCountQueue.java`
- `app/src/main/java/org/apache/roller/weblogger/business/runnable/HitCountProcessingJob.java`
- `app/src/main/java/org/apache/roller/weblogger/business/runnable/ResetHitCountsTask.java`
- `app/src/main/java/org/apache/roller/weblogger/business/runnable/ContinuousWorkerThread.java` and `WorkerThread.java` (orphaned — their ONLY consumer is HitCountQueue; verify with a grep before deleting; `Job.java` STAYS, `WeblogCacheWarmupJob` implements it)
- `app/src/main/java/org/apache/roller/weblogger/pojos/WeblogHitCount.java`
- `app/src/main/resources/org/apache/roller/weblogger/pojos/WeblogHitCount.orm.xml`
- `app/src/test/java/org/apache/roller/weblogger/business/HitCountTest.java`

**Files — edit (the inventory):**
- `persistence.xml:16` — remove the WeblogHitCount mapping-file line.
- `WebloggerImpl.java:344` — remove the queue shutdown call.
- `WeblogEntryManager.java` — remove the import and all eight hitcount methods (`getHitCount`, `getHitCountByWeblog`, `getHotWeblogs`, `saveHitCount`, `removeHitCount`, `incrementHitCount`, `resetAllHitCounts`, `resetHitCount`).
- `JPAWeblogEntryManagerImpl.java:1283-1431` — remove the impl block. KEEP `setFirstMax`, `bindParams`, `getStartDateNow` (used elsewhere: SiteModel comment/tag stats and multiple query methods).
- `Weblog.java:723-736` — remove `getTodaysHits()`; `WeblogWrapper.java:322-324` — remove its delegate.
- `SiteModel.java` — remove `getHotWeblogs` (lines ~434-457) + the WeblogHitCount import.
- `PageServlet.java` — remove both hit-counting call sites (~204-209, ~244-249), the private `processHit` (~556-563), and the HitCountQueue import. KEEP `WeblogPageRequest.isWebsitePageHit`/`isOtherPageHit` and their tests — they are URL-classification accessors whose removal is riskier surgery than this wave needs; leave a one-line comment where the gate was, noting the classification accessors survived the counter.
- `MaintenanceController.java:121-140` — remove the whole `reset` action; `Maintenance.jsp:33-34` — remove the prompt + button. KEEP flush-cache and regenerate-renditions untouched.
- `themes/frontpage/weblog.vm` — remove the "Hot blogs" sidebar block (~lines 138-158; keep the `$since`/`$maxResults` vars, they feed the entries pager); `themes/frontpage/_css.vm:171-175` — remove the `.hotBlogs` rules.
- `roller.properties` — `tasks.enabled=ScheduledEntriesTask` (line 241); delete the ResetHitCountsTask block (252-256) and the `hitcount.queue.sleepTime` property if present.
- `app/src/test/resources/roller-custom.properties:26` — `tasks.enabled=ScheduledEntriesTask,TestTask`.
- `ApplicationResources.properties:599-601,950` — remove `maintenance.prompt.reset`, `maintenance.button.reset`, `maintenance.message.reset`, `statCount.weblogDayHits`; same keys in `_ja`, `_fr`, `_zh_CN`, `_ko`, `_de` bundles where present.
- Tests: `TestUtils.java` (import + `setupHitCount` + `teardownHitCount`); `SiteModelTest` (two hot-weblogs tests + import); `MaintenanceControllerTest` (the two reset tests); `EqualsContractTest` (WeblogHitCount specimen + `hitCount()` helper; rename the `"ResetHitCountsTask"` TaskLock string to a neutral name); `WeblogLogicTest` (strip only the hits assertions from the two named tests, keep comment/entry-count coverage); `WeblogWrapperDelegationTest` (drop the hits portion of `countsAndHitsComeFromTheEntryManager`); `TaskLockLeaseTest` (rename the three `"ResetHitCountsTask"` string fixtures).
- `MessageKeyTest` ratchet: deleting keys can strand nothing (the JSP references go too), but verify the unused-key count did not go UP.

- [ ] **Step 1: Delete and edit per the inventory above** (grep after each family: `grep -rn "HitCount\|hitcount\|hitCount\|getTodaysHits\|getHotWeblogs" app/src bin deploy --include=*.java --include=*.xml --include=*.vm --include=*.jsp --include=*.properties` must end up matching only `GenericThrottle`'s unrelated rate-limit wording, cache-"hit" terminology, and `WeblogPageRequest`'s kept classification accessors).

- [ ] **Step 2: Full unit suite**

Run: `mvn -pl app test`
Expected: PASS, zero failures. This is a deletion — the whole suite is the safety net, not a subset.

- [ ] **Step 3: Commit** (with Task 2 if they had to land together)

```bash
git add -A
git commit -m "Delete the hitcount subsystem; Umami owns traffic counting

Queue, processing job, nightly reset task, pojo+table, manager methods,
maintenance button, frontpage hot-blogs sidebar, and every test fixture.
The only survivors are WeblogPageRequest's page-type classification
accessors, which describe URLs, not counters."
```

---

# Task 4: `analyticsSiteId` + `analyticsShareUrl` through the seven layers

**Files:**
- Modify: `pojos/Weblog.java` (fields beside `analyticsCode`), `Weblog.orm.xml` (after `analyticscode` mapping), `wrapper/WeblogWrapper.java` (plain sanitized-irrelevant getters — these are validated scalars, no bypass comment needed)
- Modify: `ui/controllers/editor/WeblogConfigBean.java` (fields + copyFrom/copyTo with `trimToNull`), `WeblogConfigController.java` (`myValidate` additions), `WeblogConfig.jsp`, `ApplicationResources.properties`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/editor/WeblogConfigAnalyticsTest.java` *(new)*

**Interfaces:**
- Consumes: V017's columns (Task 2).
- Produces: `Weblog.getAnalyticsSiteId()/setAnalyticsSiteId`, `getAnalyticsShareUrl()/setAnalyticsShareUrl`, mirrored on `WeblogWrapper`; Settings fields validated: site id blank or UUID (`websiteSettings.analyticsSiteId.invalid`), share url blank or absolute http(s) (`websiteSettings.analyticsShareUrl.invalid`).

- [ ] **Step 1: Failing test** — `WeblogConfigAnalyticsTest`, modelled exactly on `WeblogConfigNewsletterTest` (same fixture): valid UUID persists; blank clears to null; `not-a-uuid` → field error, no persist; share url `https://...` persists; `javascript:alert(1)` → field error, no persist; round-trip via copyFrom.

- [ ] **Step 2: Wire the layers** — the `newsletterListUuid` template layer-for-layer (the V016 precedent; reuse the existing `UUID_PATTERN` in `WeblogConfigController`). Share-url validation: `^https?://` prefix check after `trimToNull`. JSP: two rows in the existing Settings form — NOT inside the `analytics.code.override.allowed` gate; a new plain "Analytics" subsection with label/tip/invalid keys:

```properties
websiteSettings.analyticsSiteId=Analytics website ID
websiteSettings.analyticsSiteId.tip=From Umami: Settings → Websites → your site → Website ID. Blank disables analytics for this weblog.
websiteSettings.analyticsSiteId.invalid=That is not a website ID. Copy the UUID exactly from Umami.
websiteSettings.analyticsShareUrl=Analytics share URL
websiteSettings.analyticsShareUrl.tip=Optional: Umami's public share link for this site, shown here as a convenience.
websiteSettings.analyticsShareUrl.invalid=The share URL must start with http:// or https://.
```

When `analyticsShareUrl` is set, render it beside the field as an escaped link (`<a href="${fn:escapeXml(...)}">`).

- [ ] **Step 3: Run** `mvn -pl app test -Dtest='WeblogConfigAnalyticsTest,WeblogConfigNewsletterTest,SmallWrapperDelegationTest,MessageKeyTest'` → PASS.

- [ ] **Step 4: Commit** — `git commit -m "Per-weblog analyticsSiteId and share URL, validated as structured data"` (add the touched files explicitly as in prior waves).

---

# Task 5: `#showAnalyticsTrackingCode` builds the tag

**Files:**
- Modify: `WEB-INF/velocity/weblog.vm:382-393` (the macro), `ui/rendering/model/ConfigModel.java`, `config/roller.properties`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/AnalyticsInjectionRenderingTest.java` *(new)*, extend `ConfigModelTest`

**Interfaces:**
- Consumes: `WeblogWrapper.getAnalyticsSiteId()` (Task 4).
- Produces: `ConfigModel.getAnalyticsBasePath()` (startup prop `analytics.umami.basePath`, default `/analytics`), `ConfigModel.getAnalyticsScriptName()` (`analytics.umami.scriptName`, default `script.js`); the macro's structured branch.

- [ ] **Step 1: Failing rendering test** — `AnalyticsInjectionRenderingTest` (five-theme loop plus `page.vm`, the `PageNavRenderingTest` pattern): with `analyticsSiteId` set on the fixture weblog, every theme's home page AND a page view contain `<script defer src="/analytics/script.js"` with `data-website-id="<the uuid>"` and `data-host-url="/analytics"`; with it null, no `/analytics/` script anywhere; the legacy `analyticsCode` fallback branch still renders the config default when the structured id is absent (pin one case). Assert the CSP meta is byte-unchanged (reuse the pinned constants).

- [ ] **Step 2: Startup properties** in `roller.properties`, beside the newsletter block:

```properties
# Umami analytics, served same-origin through Caddy's /analytics/* handle --
# that is what lets the tracker run under script-src 'self' without touching
# the theme CSPs. basePath is where Caddy mounts it on the blog's own origin;
# scriptName should match UMAMI_SCRIPT_NAME if you renamed the tracker to
# dodge content blockers. Startup-scoped: these describe the reverse proxy,
# which does not change while the JVM runs.
analytics.umami.basePath=/analytics
analytics.umami.scriptName=script.js
```

`ConfigModel` getters follow the `getMapTileUrl` precedent (static `WebloggerConfig.getProperty`, javadoc explaining startup scope). Extend `ConfigModelTest` per its existing analytics-property tests.

- [ ] **Step 3: The macro** — replace with (structured branch first; legacy branches byte-identical below it):

```velocity
#**
Analytics for a weblog. The structured path: a validated Umami website id
plus startup config builds the script tag HERE, so no admin-typed markup
ever reaches the head -- which is what lets per-weblog analytics coexist
with weblogAdminsUntrusted. Same-origin via Caddy's /analytics handle, so
the pinned theme CSPs are untouched. The legacy raw-snippet branches remain
for installations that used them.
*#
#macro(showAnalyticsTrackingCode $weblog)
    #if ($utils.isNotEmpty($weblog.analyticsSiteId))
<script defer src="$config.analyticsBasePath/$config.analyticsScriptName" data-website-id="$utils.escapeHTML($weblog.analyticsSiteId)" data-host-url="$config.analyticsBasePath"></script>
    #elseif ($config.analyticsOverrideAllowed && $utils.isNotEmpty($weblog.analyticsCode))
        $weblog.analyticsCode
    #elseif ($utils.isNotEmpty($config.defaultAnalyticsTrackingCode))
        $config.defaultAnalyticsTrackingCode
    #end
#end
```

- [ ] **Step 4: Run** `mvn -pl app test -Dtest='AnalyticsInjectionRenderingTest,ConfigModelTest,*Rendering*Test,ThemeCspCoverageTest'` → PASS (CSP pins untouched).

- [ ] **Step 5: Commit** — `"#showAnalyticsTrackingCode builds the tag from structured config"`.

---

# Task 6: The Umami-side view and the deploy wiring

**Files:**
- Create: `deploy/analytics/umami-views.sql`
- Modify: `deploy/deploy.sh` (apply step after the ensure-databases step)

**Interfaces:**
- Consumes: `grafana_ro` (V017, cluster-global), Umami v2's postgresql schema (`website_event`, `session` tables — external, managed by the Umami image).
- Produces: view `analytics_traffic` in the `umami` database; grants for `grafana_ro`.

- [ ] **Step 1: Write the view script**

`deploy/analytics/umami-views.sql` (ASF header + prose comment explaining the two-database split and that this file is versioned here but applied to the umami DB):

```sql
-- The Umami half of the analytics contract. Lives in the umami DATABASE
-- (PostgreSQL has no cross-database queries), applied by deploy.sh on every
-- deploy -- CREATE OR REPLACE + GRANT are idempotent, so re-running is a
-- no-op. Versioned here so replacing Umami rewrites this file, not any
-- dashboard. Grafana joins website_id against rollerdb's
-- analytics_weblog_sites view.
--
-- Column shapes follow Umami v2's postgresql schema (website_event with
-- event_type 1 = pageview; session for visitor identity). If an Umami
-- upgrade changes them, this view is the only thing to fix.

CREATE OR REPLACE VIEW analytics_traffic AS
SELECT we.website_id                                   AS website_id,
       we.url_path                                     AS path,
       CASE WHEN we.url_path LIKE '%/entry/%'
            THEN split_part(we.url_path, '/entry/', 2)
            ELSE NULL END                              AS entry_anchor,
       CAST(date_trunc('day', we.created_at) AS date)  AS day,
       count(DISTINCT we.session_id)                   AS sessions,
       count(*) FILTER (WHERE we.event_type = 1)       AS views
FROM website_event we
GROUP BY we.website_id, we.url_path,
         CASE WHEN we.url_path LIKE '%/entry/%'
              THEN split_part(we.url_path, '/entry/', 2)
              ELSE NULL END,
         CAST(date_trunc('day', we.created_at) AS date);

GRANT CONNECT ON DATABASE umami TO grafana_ro;
GRANT USAGE ON SCHEMA public TO grafana_ro;
GRANT SELECT ON analytics_traffic TO grafana_ro;
```

(One caveat to encode as a comment: `GRANT CONNECT ON DATABASE umami` hardcodes the default database name; deploy.sh substitutes the real name via a psql variable if `UMAMI_DB` is overridden — implement with `\connect` handled by deploy.sh choosing the target DB and the CONNECT grant using `:umami_db`, mirroring migrate.sh's `:app_user` pattern, OR simply have deploy.sh run `psql -d "${UMAMI_DB}" -v ...`; pick the mechanism matching deploy.sh's existing style and document it.)

- [ ] **Step 2: Wire into deploy.sh**

After the ensure-service-databases block (deploy.sh:90-113) and after the migrate step (so `grafana_ro` exists), add a step copying `deploy/analytics/umami-views.sql` into the postgres container and applying it with `psql -d "${UMAMI_DB:-umami}" --single-transaction -f ...`, with a comment noting it is idempotent and why it is not part of the rollerdb chain. Also GRANT CONNECT on the rollerdb database to grafana_ro here OR in V017 — decide: V017 cannot know the database name portably (`current_database()` interpolation needs dynamic SQL), so put `GRANT CONNECT ON DATABASE ... TO grafana_ro` for BOTH databases in this deploy step where the names are known, and keep V017's grants table-level only. Note that choice in both files' comments.

- [ ] **Step 3: Shell-check and dry-run**

Run: `bash -n deploy/deploy.sh` (syntax) and, since no production stack exists in dev, validate the SQL against the shared test container manually: create a scratch database, apply Umami's minimal shape (`CREATE TABLE website_event (website_id uuid, session_id uuid, created_at timestamptz, url_path varchar, event_type int);`), apply the view script (minus the DB-name grant), assert it creates and re-applies cleanly. Script this as a small JUnit test `UmamiViewScriptTest` in the startup test package using `RollerPostgresContainer` — the view file is read from `deploy/analytics/`, the fake schema from the test, so the repo's only Umami-schema knowledge stays in the one SQL file plus this test's minimal mirror of it.

- [ ] **Step 4: Commit** — `"The Umami half of the analytics contract: versioned view, deploy-applied"`.

---

# Task 7: Browser IT

**Files:**
- Create: `it-selenium/src/test/java/org/apache/roller/it/AnalyticsInjectionIT.java`

Scenarios (IT conventions: own weblog, BrowserHealth on browser-visited pages, raw HttpClient for content checks that would trip BrowserHealth):

1. With no site id set: browser-visit the weblog home; assert NO `/analytics/` script element; `assertNoBrokenResources` + `assertNoFailedRequests` (the clean baseline).
2. Set a valid UUID through the real Settings form (the `WeblogConfigMatrixIT` driving pattern); fetch the home page via raw HttpClient (the tracker file does not exist in the IT stack — a browser visit would 404 the script and trip BrowserHealth; the raw fetch is the PageIT precedent) and assert the tag: `src="/analytics/script.js"`, the `data-website-id`, `data-host-url="/analytics"`.
3. Enter an invalid site id in Settings; assert the field error renders and the stored value is unchanged.
4. Maintenance page still renders (post-hitcount-deletion) with flush-cache present and NO reset button; `BrowserHealth`.

- [ ] **Step 1: Write the IT; iterate with `mvn -pl it-selenium test-compile -Pit`.**
- [ ] **Step 2: Run the full suite ONCE: `mvn verify -Pit`** — FOREGROUND, timeout 600000; if harness-backgrounded, wait for that one notification; never shell-timeout/detach/self-monitor.
- [ ] **Step 3: Commit** — `"Browser IT: analytics injection is structured, opt-in, CSP-clean"`.

---

# Task 8: Ratchet the gates and rewrite the docs

**Files:**
- Modify: `pom.xml` (floors), `CLAUDE.md`, `docker_deployment.md`

- [ ] **Step 1: Measure** — `mvn clean test && mvn jacoco:report -pl app`; `bin/check-diff-coverage.sh d4b5c6dce` (~90%+; add tests if under). Floors rounded down, only rise.
- [ ] **Step 2: `docker_deployment.md`** — rewrite the "Pointing a weblog at it" procedure: no more raw-snippet paste (that textarea never rendered in this fork — the old docs described an impossible procedure); the new steps are Umami → copy Website ID → Settings → Analytics website ID. Add the Grafana contract section: the two views, the two-database topology, `grafana_ro` (NOLOGIN by default; `ALTER ROLE grafana_ro LOGIN PASSWORD '...'` via `docker compose exec postgres psql`), tunnel-only access, two Grafana datasources joined on `website_id`, and the untrusted-label warning for FORM_SUBMITTED slugs.
- [ ] **Step 3: `CLAUDE.md`** — extend with an `## Analytics` section (dense, hazard-first): structured injection vs `weblogAdminsUntrusted` (why the macro builds the tag); same-origin tracker under the pinned CSPs; the two-database view split and WHERE each view lives; `SQLScriptRunner` is now dollar-quote-aware and `SqlScriptRunnerMigrationTest` keeps the third applier honest; hitcounts are GONE (what was deleted and that `isWebsitePageHit`/`isOtherPageHit` survive as URL classification); `grafana_ro` NOLOGIN convention.
- [ ] **Step 4: Full verification** — `mvn clean verify -Pit` (foreground discipline as Task 7).
- [ ] **Step 5: Commit** — `"Ratchet coverage floors and document the analytics wave"`.

---

# Self-review

**Spec coverage.** Per-weblog injection (`analyticsSiteId` validated as UUID + `analytics_share_url`) → 4; macro builds the script from trusted config → 5; `docker_deployment.md` impossible-procedure rewrite → 8; versioned views (`weblog_handle/path/entry_anchor/day/sessions/views` shape split across the two halves) → 2 + 6; `roller_event` views → 2; dedicated read-only role, SELECT-on-views-only, 5432 unpublished, tunnel-only → 2 + 6 + 8; deletion list (`HitCountQueue`, `WeblogHitCount`, `ResetHitCountsTask`, `getHotWeblogs`, `resetAllHitCounts`, `roller_hitcounts`, `.hotBlogs` CSS) → 3; migration V017 idempotent through all three appliers → 1 + 2; browser IT per changed public surface → 7; floors → 8.

**Deviations** (header): the two-database view split; the SQLScriptRunner prerequisite; NOLOGIN role; legacy analyticsCode retained.

**Type consistency.** `analyticsSiteId`/`analyticsShareUrl` names consistent across 4, 5, 7; `analytics.umami.basePath`/`scriptName` in 5 and 7's assertions; `grafana_ro` in 2, 6, 8; `analytics_events`/`analytics_weblog_sites`/`analytics_traffic` in 2, 6, 8.

**Known risks.** (1) Task 2/3 sequencing: dropping `roller_hitcounts` before deleting its mapping may break the suite between commits — the plan's own Step 3 note resolves it by evidence (fold into one commit if needed). (2) `UmamiViewScriptTest` mirrors Umami's schema minimally; an Umami upgrade changing `website_event` breaks the real view silently until the deploy applies it — the view file's comment names this as the single repair point, and the deploy applies with `--single-transaction` so a broken view fails loudly at deploy time. (3) The IT stack has no Umami, so the injected script 404s if browser-loaded — the IT deliberately asserts the tag via raw HTTP and asserts the no-id case in the browser; production correctness of the script path is Caddy's already-shipped handle.
