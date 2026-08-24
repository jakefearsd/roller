# URL Redirects Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** A URI that would otherwise 404 can be answered with a 301 to live
content — automatically when a page slug is renamed, and by operator-created
rules for migrated sites — with a hit count and a diagnosable log line per
redirect served.

**Architecture:** One table (`roller_weblog_redirect`, V028), one small
manager on the facade (`WeblogRedirectManager`), consulted only at the four
code points where a weblog-scoped 404 is already decided (two in
`PageServlet`, two in `WeblogRequestMapper`). `Location` is built through
`URLStrategy.getWeblogURL` so vhost/context-path handling is inherited. A
JPA post-load slug snapshot on `WeblogPage` powers the automatic
slug-history hook in `savePage`. Admin surface is API-only (`RedirectsApi`).

**Tech Stack:** PostgreSQL 16 + JPA/EclipseLink, Spring MVC, JUnit 5,
Selenide/Selenium.

**Spec:** `docs/superpowers/specs/2026-08-24-url-redirects-design.md`. Read
"The narrow definition" section before Task 2 — the fail-closed property is
*where the lookup is called from*, and a consultation added anywhere else is
the failure mode.

## Global Constraints

- **Never push.** The top-level session commits only when Jake asks.
- Work directly on `master`. No feature branch.
- **This wave owns migration `V028` and no other number.** Idempotent DDL;
  never edit an applied migration.
- **TDD per house rules**: failing test first, watched failing, for each
  behavior. Manager tests are DB-backed through `TestUtils.weblogger()`.
- Check `it-selenium/src/test/resources/seed-it-data.sql` (new table, so the
  usual failure mode does not apply, but check and report).
- No new JSP route → no `Routes.java` entry, no new message keys.
- **Controllers must name `@RequestParam`/`@PathVariable` explicitly**
  (`ControllerMetadataTest`).
- The new manager bean in `WebloggerBeanConfig` takes `@Lazy Weblogger` like
  its siblings; `ContextRefreshDoesNotBootstrapTest` is the enforcement.
- `StaticServiceLocatorTest` scans sources — no new static business-tier
  fields.
- Run the full unit suite per task in the foreground: `mvn -q -pl app test`.
  Serialise on the build lock:
  `pgrep -f "[s]urefirebooter.*source/roller"` must be CLEAR first.
- Quality gates run at `verify` — PMD/SpotBugs/CPD zero tolerance. New code
  must arrive clean, not suppressed.

## File Structure

| File | Responsibility |
|---|---|
| `bin/db/migrations/V028__weblog_redirects.sql` | **new** — the table |
| `.../pojos/WeblogRedirect.java` | **new** — entity + normalization/validation helpers |
| `.../resources/.../pojos/WeblogRedirect.orm.xml` | **new** — mapping + named queries |
| `.../resources/META-INF/persistence.xml` | the mapping-file row |
| `.../business/WeblogRedirectManager.java` | **new** — interface |
| `.../business/jpa/JPAWeblogRedirectManagerImpl.java` | **new** — impl, hit count, mutation logging |
| `.../business/Weblogger.java` + `WebloggerImpl.java` + `jpa/WebloggerBeanConfig.java` | facade wiring |
| `.../business/jpa/JPAWeblogManagerImpl.java` | `removeWeblog` cascade |
| `app/src/test/java/.../business/MockWeblogger.java` | the mock's new manager |
| `.../ui/rendering/RedirectResponder.java` | **new** — resolve → 301 → count → log, the one consultation implementation |
| `.../ui/rendering/WeblogRequestMapper.java` | consult at 2 `sendError` sites + the decline |
| `.../ui/rendering/servlets/PageServlet.java` | consult at the 2 `sendNotFound` sites |
| `.../pojos/WeblogPage.java` + `WeblogPage.orm.xml` | `loadedSlug` post-load snapshot |
| `.../business/jpa/JPAWeblogPageManagerImpl.java` | the slug-history hook |
| `.../ui/restapi/v1/RedirectsApi.java` | **new** — list/create/delete |
| `docs/api/README.md` | the API section |
| `it-selenium/.../RedirectIT.java` | **new** |
| `CLAUDE.md` | the seams, the construction argument, the anchor finding |

---

### Task 1: Table, entity, manager, facade

**Interfaces produced:**
- `WeblogRedirect` (id, weblog, sourcePath, targetPath, origin
  `MANUAL|SLUG_HISTORY`, createdAt, hitCount, lastHitAt)
- `WeblogRedirect.normalizePath(String)` — leading `/` guaranteed, single
  trailing slash stripped (root stays `/`), used identically at save and match
- `WeblogRedirectManager`: `getRedirects(Weblog)` (newest first),
  `saveRedirect(WeblogRedirect)` (normalizes + validates),
  `removeRedirect(WeblogRedirect)`, `getRedirect(String id)`,
  `resolve(Weblog, String path)` → `WeblogRedirect` or null,
  `recordHit(WeblogRedirect)` (best-effort, own failure never propagates),
  `removeRedirects(Weblog)` (cascade)

- [x] Failing tests first: `WeblogRedirectManagerTest` (DB-backed, modeled on
  `WeblogPageManagerTest`): save+resolve round-trip; normalization (trailing
  slash both directions, missing leading slash refused or normalized — pick
  refuse for source without `/`, since API input should be explicit);
  per-weblog isolation; uniqueness per (weblog, source); validation refusals
  (target==source, `//`-prefix, scheme, `?`, backslash, control chars,
  chain-from-either-end); `recordHit` advances count and `lastHitAt`;
  `removeRedirects` empties; newest-first ordering.
- [x] `V028__weblog_redirects.sql`: `roller_weblog_redirect` — `id varchar(48) primary key`,
  `weblogid` FK → weblog, `source_path varchar(255) not null`,
  `target_path varchar(255) not null`, `origin varchar(16) not null`,
  `created_at timestamp not null`, `hit_count bigint not null default 0`,
  `last_hit_at timestamp`. Unique index on `(weblogid, source_path)`.
  Match column types to what `V014__weblog_pages.sql` actually used — read it.
- [x] Entity + orm.xml (`roller_weblog_redirect`, `<enumerated>STRING</enumerated>`
  for origin, named queries `getByWeblog`, `getByWeblogAndSource`,
  `removeByWeblog`) + persistence.xml row (no trailing spaces — the file's
  own comment warns).
- [x] Manager interface + JPA impl (constructor `(Weblogger, JPAPersistenceStrategy)`
  like `JPAWeblogPageManagerImpl`); wire `Weblogger`/`WebloggerImpl`/
  `WebloggerBeanConfig` (`@Lazy`)/`MockWeblogger`. `saveRedirect` logs the
  mutation on the `roller.redirects` logger.
- [x] `removeWeblog` cascade in `JPAWeblogManagerImpl` beside
  `WeblogPage.removeByWeblog`.
- [x] `mvn -q -pl app test -Dtest=SchemaMigrationTest,WeblogRedirectManagerTest`,
  then the full suite.

### Task 2: The four seams, the 301, the log line

**Interfaces produced:** `RedirectResponder` (ui.rendering): given
`(Weblogger, Weblog, weblogRelativePath, request, response)` → resolves via
the manager; on a match sets status 301 and `Location` =
`urlStrategy.getWeblogURL(weblog, null, true)` root + target + original query
string, records the hit, and emits the INFO line on logger
`roller.redirects` (weblog handle, rule id, origin, full requested URI with
query string, target, referer, user-agent — all `{}`-parameterized); returns
whether it answered. Resolver/store failure → log, return false, the 404
proceeds.

- [x] Failing tests first (`RedirectResponderTest`, MockWeblogger-based, mock
  request/response like the existing servlet/mapper tests): match → 301 with
  Location **derived** at both root and `/roller` context paths (never
  hardcoded — the `SeoController.robots()` lesson); vhost weblog → Location on
  the custom domain; query string preserved; no match → not answered; manager
  throw → not answered, no exception; hit recorded on match; log line carries
  every named field (list-appender assertion).
- [x] Failing seam tests: extend `WeblogRequestMapperTest` (the two
  `sendError(SC_NOT_FOUND)` sites and the `calculateForwardUrl == null`
  decline — the decline consults only when the weblog is resolved) and the
  PageServlet path (`selectTemplate == null`, `rejectionReason != null`).
  Establish the weblog-relative path each seam actually has in hand (mapper:
  pathInfo; PageServlet: forwarded pathInfo minus handle — verify, don't
  assume) and pin it.
- [x] Implement; run the full suite.

### Task 3: Automatic slug history

- [x] Failing tests first (extend `WeblogPageManagerTest`): renaming a
  published page's slug mints a `SLUG_HISTORY` rule old→new; an existing rule
  targeting the old slug is re-pointed to the new one (collapse); a rule whose
  source equals the new slug is deleted; A→B→A round-trip converges to a
  clean table; a save that does not change the slug mints nothing; a brand-new
  page mints nothing.
- [x] `WeblogPage` gains `loadedSlug` via a JPA `post-load` callback in
  `WeblogPage.orm.xml` — same mechanism as `WeblogEntry.snapshotLoadedContent`
  (read that entity's orm entry for the exact syntax; remember the
  `.orm.xml`/getter pairing rule). Transient — EclipseLink validates
  `<transient>` rows against getters.
- [x] Hook in `JPAWeblogPageManagerImpl.savePage`, same transaction as the
  save, after slug validation. Run the full suite.

### Task 4: `RedirectsApi` + docs

- [x] Failing tests first, modeled on the existing `*Api` unit tests:
  `GET /v1/weblogs/{handle}/redirects` returns rules with hitCount/lastHitAt;
  `POST` creates (validation errors surface as the house error contract);
  `DELETE /v1/weblogs/{handle}/redirects/{id}` removes; a foreign weblog's
  redirect id → **404 never 403**; write endpoints need POST scope.
  Mappings are written relative to `/v1/...` (the `/api` prefix is the
  servlet mapping — the house 404-at-runtime trap).
- [x] Implement `RedirectsApi` (`UISecurityEnforced`, ownership through the
  shared path — follow `PagesApi`'s shape); update `docs/api/README.md`.
- [x] Full suite + `mvn -q -pl app verify` for the quality gates and
  `OpenApiDocumentTest`.

### Task 5: Browser IT + CLAUDE.md

- [x] `RedirectIT` (owns its weblog, no resource locks): create + publish a
  page; rename its slug; GET the old URL → lands on the new URL with the
  page's content (Selenide follows the 301); create a manual rule over the
  API for a multi-segment would-404 path and follow it; read hit counts back
  over the API and assert they advanced.
- [x] `mvn verify -Pit -Dit.test=RedirectIT`, then at the prefixed context:
  `mvn verify -Pit -Dit.context.path=roller -Dit.test=RedirectIT`. (The full
  both-context sweep is the pre-release step, per CLAUDE.md.)
- [x] CLAUDE.md gains a "Redirects" section: the four seams, the
  fail-closed-by-construction argument and the decline-seam caveat, the
  immutable-anchor finding, the no-chaining + collapse rules, the
  `roller.redirects` logger, API-only surface.
