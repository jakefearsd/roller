# Hardening Wave Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the test-debt and small-defect list accepted by the Waves A–C final reviews, plus the XSS sink-class sweep those reviews' follow-up implied.

**Architecture:** No new features. Each task is a self-contained fix-plus-test or test-only addition against existing code. TDD throughout: red test first where a defect is being fixed.

**Tech Stack:** JUnit 5 + Testcontainers PG (schema tests), MockMvc-style direct controller tests, Selenide ITs, jQuery/JSP for the sink sweep.

## Global Constraints

- Never rename/remove ids, names, or classes the browser ITs or page JS rely on (`#entries-list-marker`, `#entriesBulkForm`, `#postTitleLabel`, `#page-delete-title`, `#category-name`, modal ids, form actions).
- roller.css stays hex-free (DesignTokenTest); no theme CSP metas move; weblog.vm/permalink.vm pinned substrings unchanged except where a task names them.
- All builds foreground (timeout 600000); never commit red; single commit per task with the message the task names.
- Subscribe-context-path IT coverage is ALREADY DONE (`SubscribeFormIT`) — no task recreates it.

---

### Task 1: Analytics contract tests (grafana_ro privileges + analytics_events data)

**Files:**
- Test: `app/src/test/java/org/apache/roller/weblogger/business/startup/AnalyticsContractTest.java` (new)

**Interfaces:**
- Consumes: `RollerPostgresContainer.getJdbcUrl/getUsername/getPassword()`; the `freshDatabase(suffix)` helper shape from `SchemaMigrationTest`; migration chain application as `SqlScriptRunnerMigrationTest` does it.

- [ ] Step 1: Write failing tests in `AnalyticsContractTest` (new scratch DB, full migration chain applied):
  - `grafanaRoCanReadTheContractViewsAndNothingElse`: as the admin connection, assert `SELECT has_table_privilege('grafana_ro','public.analytics_events','SELECT')` and `...analytics_weblog_sites...` are true; assert the same call for `public.roller_event`, `public.weblog`, `public.roller_form_submission`, `public.roller_user_token` is false.
  - `analyticsEventsRollsUpByHandleTypeAndDay`: INSERT a `weblog` row (satisfying `rev_weblog_fk`), three `roller_event` rows (two same type/day, one different day), then assert `SELECT events FROM analytics_events WHERE weblog_handle=? AND event_type=? AND day=?` returns 2, and the other day returns 1. Column list per V017: `weblog_handle, event_type, entry_anchor, page_slug, day, events`.
- [ ] Step 2: Run: `mvn -pl app test -Dtest=AnalyticsContractTest` — must fail only if the contract is broken; on current HEAD both should PASS (these are pin tests, not defect tests). If either fails, STOP and report — that is a real contract break.
- [ ] Step 3: Commit `"Hardening: pin the Grafana contract"`.

### Task 2: URLModel.page() encodes the page link

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/MultiWeblogURLStrategy.java:240`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/model/URLModelTest.java`

- [ ] Step 1: Failing test alongside `customPageUrlsCarryTheirFiltersAsQueryParameters`: a page link containing a space and an ampersand (e.g. `notes & maps`) must come back percent-encoded (`notes+%26+maps` — match whatever `URLUtilities.encode` produces; `staticPage()`'s existing behavior at `URLModel.java:269` is the reference).
- [ ] Step 2: Run scoped, watch it fail (raw append at line 240).
- [ ] Step 3: Fix: `pathinfo.append("page/").append(URLUtilities.encode(pageLink));` — mirroring `getWeblogEntryURL`'s anchor treatment at line 95.
- [ ] Step 4: Scoped test green, then `mvn -pl app test` green.
- [ ] Step 5: Commit `"Hardening: encode the page link in page URLs"`.

### Task 3: canonicalUrl http(s) allowlist (entries + pages)

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/rendering/model/PageModel.java` (getCanonicalUrl, lines ~173-207)
- Modify: `EntryEditController` / `PageEditController` validation (add to existing myValidate or create the check where other bean validation lives)
- Test: `SeoHeadRenderingTest`, the two controllers' `*ControllerTest`s

**Interfaces:**
- Consumes: `org.apache.commons.validator.routines.UrlValidator` exactly as `CtaShortcode.java:67-68` uses it (`new UrlValidator(new String[]{"http","https"})`).

- [ ] Step 1: Failing tests: (a) `SeoHeadRenderingTest`: an entry whose canonicalUrl is `javascript:alert(1)` emits NO canonical link, NO og:url, NO mainEntityOfPage (extend the existing quote test at line ~412's fixture pattern); (b) controller tests: saving an entry/page with canonicalUrl `data:text/html,x` is rejected with a validation error, plain `https://example.com/x` accepted, blank accepted.
- [ ] Step 2: Watch them fail.
- [ ] Step 3: Implement both layers: save-time rejection in the two controllers (bundle key `entryEdit.canonicalUrlInvalid` — add to base bundle + 7 locales per each file's escaping convention); emission-time filter in `PageModel.getCanonicalUrl()` returning null for non-http(s) values (protects rows stored before this wave).
- [ ] Step 4: Scoped green, `mvn -pl app test` green.
- [ ] Step 5: Commit `"Hardening: canonical URLs are http(s) or nothing"`.

### Task 4: The .html() sink sweep

**Files (from the research — verify each before editing):**
- `Entries.jsp:358` `$('#postTitleLabel').html(postTitle)` → `.text(...)`; also add `fn:escapeXml` at the `onclick` attribute site line 186 (`${post.title}` currently raw).
- `EntryEdit.jsp:838` same sink → `.text(...)`; `fn:escapeXml` at line 516.
- `PageEdit.jsp:378` `.html(pageTitle)` → `.text(...)` (attribute site already escaped).
- `Categories.jsp:331` `.html(name)` → `.text(...)`; `fn:escapeXml` at the unescaped `${category.name}` attribute site (~line 302).
- `MediaFileView.jsp:435` `$('#edit-subtitle').html(mediaFileName)` → `.text(...)`.
- `Comments.jsp:410,417,435,448,459` — the inline comment editor pipes reader-submitted comment content through `.html(...)`. Convert every site where the value is comment CONTENT or reader-supplied text to `.text(...)`; where the intent is genuinely to preview rendered comment HTML, keep `.html()` ONLY if the value is server-sanitized before the AJAX response (verify against the controller; if not verifiable, use `.text()` — a comment preview that shows markup source is acceptable, a stored-XSS vector is not).

- [ ] Step 1: For each JSP, apply the sink + attribute fixes. No id/name changes.
- [ ] Step 2: jspc-compile all touched JSPs; `mvn -pl app test` green.
- [ ] Step 3: Manual-verification note in the report: which Comments.jsp sites kept `.html()` and the server-side sanitization evidence for each.
- [ ] Step 4: Commit `"Hardening: delete modals and comment editor stop executing names"`.

### Task 5: ShareLink 404 browser scenarios

**Files:**
- Test: `it-selenium/src/test/java/org/apache/roller/it/ShareLinkIT.java`

- [ ] Step 1: Two scenarios using the existing `anonymousStatusOf` helper (lines ~198-213): (a) well-formed unknown token (`[A-Za-z0-9_-]+` shape) → 404; (b) malformed token (contains `.`) → 404. For any browser-driven navigation add `BrowserHealth.current().expectRefusal(...)` first, per `SubscribeFormIT:125`.
- [ ] Step 2: `mvn verify -Pit -Dit.test=ShareLinkIT` green (foreground; docker cleanup `docker rm -f roller-it-postgres` if a prior container lingers).
- [ ] Step 3: Commit `"Hardening: dead share links 404 in a real browser"`.

### Task 6: Ratchet + full verification

**Files:**
- Modify: `pom.xml` (`jacoco.branch.minimum` 0.7800 → 0.7900; leave `jacoco.line.minimum` at 0.8700 — actual is 0.8751, margin too thin to raise; leave the PACKAGE rule at 0.55, velocity actual 0.5992)

- [ ] Step 1: Bump the branch floor, run `mvn -pl app test` then `mvn -pl app jacoco:report` and confirm the check passes with post-wave numbers (they may have moved — set 0.79 only if actual ≥ 0.79 + 0.003 margin; otherwise report actuals and leave the floor).
- [ ] Step 2: FULL `mvn verify -Pit` green (foreground discipline; a GalleryIT font-abort failure is a known flake — rerun that class once before treating as real).
- [ ] Step 3: Commit `"Hardening: ratchet the branch floor"`.
