# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Important Rules

- **Never commit or push unless explicitly asked.** Wait for the user to request a commit or push. Do not proactively create commits or push to remote.
- **Work directly on `master`.** This is a solo-developer repo; do not create a feature branch before committing unless explicitly asked.

## Build and Development Commands

### Basic Build Commands
```bash
# Full build with tests (tests need Docker: they run against a PostgreSQL container)
mvn clean install

# Build without tests (faster for development)
mvn -DskipTests=true install

# Run the dev server: starts PostgreSQL, applies migrations, runs the app
# via `spring-boot:run` (embedded Tomcat) with roller-boot-dev.properties
./roller dev
# Access at http://localhost:8083/roller

# Or run the packaged executable WAR directly, no Maven/IDE involved
# (default port 8080; point -Droller.custom.config at a real database
# config, e.g. app/src/test/resources/roller-boot-dev.properties, or the
# app fails to bootstrap):
java -jar app/target/roller.war --server.port=8083 \
    -Droller.custom.config=app/target/test-classes/roller-boot-dev.properties
# Health check (works even before the business tier bootstraps). Actuator
# lives on its own management port (management.server.port=8090), not under
# the main app port -- DispatcherServlet is mapped to *.rol only, so there is
# no "/" catch-all for /actuator/** to attach to on 8083. Management-only:
# do not expose 8090 outside localhost/the deploy host in production.
# curl http://localhost:8090/actuator/health

# Database-only helpers
./roller db          # start PostgreSQL and migrate, without running the app
./roller migrate     # apply pending migrations
./roller status      # show applied migrations
./roller stop        # stop the dev database (data preserved)
./roller reset       # DESTROY the dev database volume and rebuild it
```

### Testing Commands
```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=TestClassName

# Coverage report (JaCoCo)
mvn clean test && mvn jacoco:report -pl app
# HTML: app/target/site/jacoco/index.html
```

Tests require Docker. A single PostgreSQL container is started once per JVM by
`RollerTestBootstrap` (a JUnit `LauncherSessionListener`) and its schema is built
by applying the real `bin/db/migrations` chain — there is no separate test
schema. Tests create fixtures through `TestUtils.setupX(...)` and must remove them in
`@AfterEach` (`teardownWeblog`/`teardownUser` + `endSession(true)`) — nothing
truncates tables between tests. Render caches are per-JVM singletons; tests
touching the rendering path call `CacheManager.clear()` in `@BeforeEach`
(see `RenderingTestSupport`).

### Coverage gates

- JaCoCo `check` runs at `verify` with floors in the parent `pom.xml`
  (`jacoco.line.minimum` / `jacoco.branch.minimum`, plus a PACKAGE rule for
  `ui.rendering.*`). Floors only ever move up. Raise them after each stage.
- Changed lines need ~90% coverage: `bin/check-diff-coverage.sh [base-ref]`
  (default `HEAD~1`; needs `pip install diff_cover` and a fresh
  `mvn -pl app jacoco:report`). CI enforces this on every push/PR.
- Browser ITs run in CI (`mvn verify -Pit`) — see `it-selenium/`, and CI
  below for *when*.

### CI: three tiers, and nothing publishes on a push

`.github/workflows/main.yml` is split by cost, not by topic.

- **Every push and PR** runs `build-test` only: the unit suite plus the
  diff-coverage gate. ~3 minutes.
- **Nightly (04:00 UTC), on a PR, or on demand** runs `integration-test`:
  the browser ITs, ~16 minutes. They are deliberately *not* on the push
  path — paying 16 minutes per commit turned a re-runnable flake (the
  GalleryIT upload race) into a red mail on work that was fine. Run them
  yourself before cutting a release: `mvn verify -Pit`.
- **CodeQL** (`codeql-analysis.yml`) is weekly + `workflow_dispatch`, not
  per push.

**Publishing happens only on a `v*.*.*` tag** (`release.yml`): it builds the
WAR, pushes `ghcr.io/jakefearsd/roller:<version>` **and** `:latest`, and
cuts a GitHub Release with the WAR attached. Pushing to master publishes
nothing. That matters because `docker-compose.prod.yml` defaults the app to
`:latest` and `deploy/deploy.sh` pulls it — under the old publish-on-push
setup, every commit silently became the image the next deploy would take.
`deploy.sh --build` is how you deploy an untagged tree.

**Known flake: `ReferenceError: EasyMDE is not defined`** on
`entryEdit!firstSave.rol`, surfacing through `BrowserHealth`'s
uncaught-exception check (seen in `MediaCropIT`). `head.jsp` loads
`easymde.min.js` synchronously and unconditionally — line 45, *outside* the
`<c:if>` that gates Font Awesome — so this is the page's inline initialiser
racing a script that had not finished executing, not a missing include. Green
on rerun. Before assuming flake, confirm the `<script>` is still unconditional:
if someone widens that `<c:if>` to cover EasyMDE, the identical error becomes a
real breakage on every screen the condition excludes.

A stale `roller-it-postgres` container from a killed IT run makes
`mvn verify -Pit` fail at `docker-maven-plugin:start` with a 409 name
conflict, not a test failure. `docker rm -f roller-it-postgres` and re-run.

### Database

Roller is **PostgreSQL-only** as of 6.2.0. Development, test, and production all
run the same engine; the previous Derby-in-test / PostgreSQL-in-prod split and
the Velocity/Texen layer that generated DDL for seven vendors are gone.

- **Development**: PostgreSQL 16 via `docker-compose.yml` (named volume, data persists)
- **Testing**: PostgreSQL 16 via Testcontainers, schema from the migration chain
- **JNDI Name**: `jdbc/rollerdb`

#### Schema changes

**Every commit that changes the schema MUST add a numbered migration** under
`bin/db/migrations/`. Take the next `V<NNN>__description.sql`, write idempotent
DDL, and never edit a migration that has already been applied anywhere but local
dev — fix mistakes with a follow-up migration. See
`bin/db/migrations/README.md` for the full convention; `SchemaMigrationTest`
enforces discoverability, schema shape, and idempotency.

Migrations reach a database three ways, all reading the same files:
`bin/db/migrate.sh` (deploy), `DatabaseInstaller` (web install wizard), and the
test harness.

## Architecture Overview

Apache Roller is a multi-user blog server built with:
- **Runtime**: Spring Boot 4.1 executable WAR (`java -jar app/target/roller.war`,
  or deployable to an external servlet container) on embedded Tomcat 11,
  targeting Java 25. Servlets/filters are registered in Java config
  (`ServletRegistrationConfig`, transcribed from the retired `web.xml`), not
  a deployment descriptor; there is no `web.xml` in the built artifact.
- **Web Framework**: Spring MVC with `@Controller` classes and `*.rol` URL mappings
- **Security**: Spring Security with role-based access control and built-in CSRF
- **Persistence**: JPA with EclipseLink on PostgreSQL
- **Templating**: Dual system - Velocity for blog rendering, JSP/JSTL for admin UI
- **Entry content**: Markdown, always. There is no per-entry format flag and no
  column that could hold one (V009 dropped `content_type`/`content_src`).
  commonmark-java converts it in `WeblogEntry.render()` **after** shortcode
  expansion and before sanitization — that order is load-bearing (markdown-first
  escapes the quotes in `[gallery dir="x"]`). Raw HTML passes through commonmark
  by design, so `HTMLSanitizer` (OWASP policy) is the only boundary.
- **Search**: Apache Lucene 10 for full-text search (embedded, index on local disk)
- **DI Container**: Single Spring container. Business beans are defined in
  `WebloggerBeanConfig` (`@Configuration @Lazy`, in
  `org.apache.roller.weblogger.business.jpa`) and are constructed lazily at
  `WebloggerFactory.bootstrap()`, after `WebloggerStartup.prepare()`.
  Controllers get the `Weblogger` facade via the `@Autowired @Lazy` field on
  `BaseController`. Rendering servlets/models/pagers, background tasks/beans,
  and `RollerHandlerInterceptor` intentionally still go through the
  `WebloggerFactory` static shim -- out of scope for the Spring Boot
  conversion (Stage 1B), which targeted the deployment/servlet layer, not
  this DI seam; a candidate for a later stage.

### Core Package Structure
```
org.apache.roller.weblogger.
├── boot/               # Spring Boot entrypoint, Java-config (servlets, security, MVC)
├── business/           # Service layer and business logic
│   ├── jpa/           # JPA persistence implementations
│   ├── plugins/       # Plugin system for content processing
│   ├── themes/        # Theme and template management
│   └── search/        # Lucene search implementation
├── pojos/             # Domain model entities
├── ui/controllers/    # Spring MVC controllers
│   ├── admin/         # Administrative functions
│   ├── core/          # Core app functions (login, profile)
│   └── editor/        # Content editing interface
└── util/              # Common utilities
```

### Key Architecture Patterns

**Service Layer Pattern**: The `Weblogger` interface serves as the main facade providing access to all manager components:
```java
UserManager getUserManager()
WeblogManager getWeblogManager()
WeblogEntryManager getWeblogEntryManager()
ThemeManager getThemeManager()
IndexManager getIndexManager()
// ... other managers
```

**Manager Pattern**: Business logic is organized into specialized managers:
- `UserManager` - User accounts and authentication
- `WeblogManager` - Blog CRUD operations  
- `WeblogEntryManager` - Blog entry management
- `ThemeManager` - Theme and template handling
- `IndexManager` - Search indexing
- `MediaFileManager` - File uploads and media

### Security Architecture
- **Authentication**: Database only (`AuthMethod` — LDAP/OpenID/container-managed
  were removed; an unsupported `authentication.method` value now fails loudly
  at startup instead of silently behaving like `db`)
- **Authorization**: Role-based with `GlobalPermission`, `WeblogPermission`, and `ObjectPermission`
- **Custom Interceptor**: `RollerHandlerInterceptor` enforces access controls
- **CSRF Protection**: Spring Security built-in CSRF (automatic on all POST forms)

### Theme System
- **Shared Themes**: System-provided themes in `/themes/` directory
  (incl. `portfolio` — dark justified-grid photography theme driven by
  featured images + focal points, Stage 2 Wave 2; `travel` — light
  guide-card theme that frames the travel shortcodes, Stage 2 Wave 3; and
  `journal` — the current default, "Quiet Journal": reading-first entry
  list with date marginalia, `qj-*` vocabulary, light+dark, self-hosted IBM
  Plex Serif/Sans/Mono (`ibm__plex-serif` 0.0.3-alpha.0 joins the existing
  Sans/Mono webjars in `app/pom.xml`, Theme Wave). A theme that ships its
  own webfont needs `font-src 'self'` added to its CSP on top of
  `CSP_STANDARD` — omitting it means the browser refuses every `@font-face`
  the theme just shipped — and `JournalThemeRenderingTest` pins the
  resulting string byte-for-byte the same way the other themes' CSPs are
  pinned. `ThemeCspCoverageTest` enforces the `font-src` addition generically
  (any theme whose CSS references `@font-face` must carry it) and, because
  Plex is served from the classpath via Spring Boot's static-resource merge
  rather than from a file under `webapp/`, checks `/webjars/`-referencing
  themes against `getResource("META-INF/resources/webjars/...")` instead of
  the filesystem — a plain file-existence check would false-negative on
  every webjar-hosted font. `journal` ships a `_page` template from day one
  (unlike `travel`/`portfolio`, which grew theirs later), covered for both
  the `[contact]` slot and the draft-404 case.
  **Retired**: `basic`, `fauxcoly` and `gaurav` are gone — directories
  deleted, `V018__retire_legacy_themes.sql` moves any weblog still on one of
  them to `journal` (idempotent; a custom-theme weblog stores `'custom'` in
  `editortheme` and is untouched by definition). The test fixture theme
  (`TestUtils`, the seeded IT weblog) is `journal` now, and `ThemeMatrixIT`'s
  theme list is `journal`/`portfolio`/`travel`. These three ids never come
  back — do not reintroduce a theme directory or a migration branch that
  writes `editortheme` back to one of them.
- `frontpage` (the multi-weblog aggregator theme, distinct from a per-weblog
  shared theme) was restyled to the `fd-*` front-door design: hero, latest-
  across-the-site post rows, and a teal-wash weblog directory with a live
  dot — same `font-src` CSP addition as `journal`, self-hosted Plex. The
  portal-era `_blogprofile.vm` (a per-weblog profile view) and the
  already-dead `_blogs.vm` are deleted along with their `theme.xml`
  registrations; the weblog directory now links each card straight to the
  weblog itself instead of an intermediate profile page.
- **Entry titles are stored HTML-escaped; page titles are stored raw — the
  two are opposite conventions, and a template (or a future doc revision)
  that assumes they match ships either double-escaped garbage or stored
  XSS.** `EntryBean.copyTo` runs `StringEscapeUtils.escapeHtml4` on the title
  once, at save time — the only place raw author input becomes escaped
  markup for an entry — which is why `WeblogEntry.getTitle()` already
  returns entity-escaped text and every theme must emit `$entry.title` bare;
  `journal`, `travel` and `portfolio` all do this now (the latter two were
  found still calling `$utils.escapeHTML($entry.title)` — and so
  double-encoding to `&amp;amp;` — during the Theme Wave sweep and fixed in
  the same pass, cosmetic breakage rather than a security bug, but still a
  defect wherever it happens). `PageBean.copyTo` does the opposite: it
  copies `title` straight through with **no** escaping at all, so
  `WeblogPage.getTitle()` returns raw author input and every page-rendering
  template **must** call `$utils.escapeHTML($model.page.title)` (or
  `#showPageTitle`, which does the same) itself — `journal`/`travel`/
  `portfolio`'s `page.vm` all do. Skipping that escape on a future theme is
  stored XSS the moment a page title carries `<script>`, not a cosmetic bug
  like the entry-side mistake. The inverse mistake on the entry side — a
  *never*-escaped author field rendered raw — is the same class of stored
  XSS; see `EditorJspEscapingTest` for the admin-JSP side of the same
  invariant (it pins that every author-controlled EL expression,
  `entry.title` included, goes through `fn:escapeXml` in the editor JSPs,
  since JSP-side fields get no save-time escaping the way the entry title
  does).
- **Custom Themes**: User-customized themes per blog
- **Template Types**: Main templates (`.vm`), stylesheets, and resources
- **Hot Reload**: Theme changes reload automatically in development mode

### Media Pipeline (Stage 2 Wave 1)
- Uploads get a rendition ladder (480/960/1600/2400px, JPEG/PNG only, never
  upscaled) via `RenditionSupport`, plus WebP siblings when `cwebp` is present
  (feature-detected `CwebpEncoder`; the prod Docker image installs it, dev
  works without). Served by `MediaResourceServlet` via `?w=<width>` +
  `Accept: image/webp` negotiation; renditions are excluded from upload quotas.
- Upload also extracts EXIF (`ExifSupport`) and a BlurHash placeholder onto
  `MediaFile`; `uploads.exif.stripGps` (default on) nulls GPS coordinates
  before persist — the original file on disk is never modified.
- Backfill for pre-pipeline uploads: Maintenance page →
  `MediaFileManager.regenerateRenditions(weblog)`.
- Crop (Stage 2 Wave 2): `MediaFileManager.cropMediaFile` destructively
  re-encodes the original (orientation composed first, atomic temp+move
  write), deletes and regenerates the whole rendition ladder + thumbnail +
  blurhash; stored EXIF fields are kept. Focal point (`MediaFile.focalX/Y`,
  set on MediaFileEdit) emits `object-position` via `#showResponsiveImage`
  only — never into entry content.
- Private directories (`MediaFileDirectory.isPrivate()`, toggled on
  MediaFileView): 404 on the base media path (except logged-in editors of the
  owning weblog), excluded from sitemaps, refused by the `[gallery]`
  shortcode. This is a pure visibility flag with no bypass of any kind — a
  private directory is simply not served publicly, by anyone, ever. (The
  share-link feature that once punched a tokened public hole through it —
  `ShareController`, `roller_share_link` — was removed entirely and is not
  coming back.)

### SEO (Stage 2 Wave 1)
- Per-entry SEO fields on `WeblogEntry` (metaTitle, searchDescription,
  canonicalUrl, noindex, featuredImageId, ogImageId), edited in the entry
  editor's "SEO & Social Sharing" card with featured/social image pickers.
- `#showSeoHead` (in `WEB-INF/velocity/weblog.vm`, called from every bundled
  theme head) emits meta description, canonical, robots noindex, Open Graph /
  Twitter card, and JSON-LD; `#showResponsiveImage` is the theme-side
  `<picture>`/srcset emitter.
- `SeoController` serves `/robots.txt`, `/sitemap.xml` (index), and
  `/sitemap-<handle>.xml` (mapped via `*.xml`; a middle-wildcard servlet
  pattern is illegal).

### Travel (Stage 2 Wave 3)
- Three shortcodes in `business/shortcodes`, registered in
  `ShortcodeExpander.DEFAULT` like `[image]`/`[gallery]`: `[map]` with
  `[pin lat lng label]` children (or `auto="<dir>"` mapping a directory's
  GPS-bearing photos — same private-directory refusal as `[gallery]`),
  `[faq]` with `[q]`/`[a]` pairs, and `[cta href label note]` (absolute
  http(s) only, UTM-tagged).
- `MapPins.parse` / `FaqBlocks.parse` are the single source of truth: the
  shortcode renderers AND the JSON-LD head emission call them, so the map a
  reader sees and the itinerary a crawler reads cannot drift.
- Leaflet 1.9.4 (webjar, self-hosted) ships via `#showMapAssets`, the map
  twin of `#showGalleryAssets`; OSM tiles, no API key. Leaflet paints
  aborted tiles with a `data:` GIF, so every theme head's CSP carries
  `img-src * data:` — the string is pinned byte-for-byte by
  `MapAssetsRenderingTest`/`PortfolioThemeRenderingTest`/`TravelThemeRenderingTest`.
- Per-entry structured-data type (`WeblogEntry.jsonLdType` + geo/event
  columns, V008): `EntryJsonLd` emits TouristAttraction/TouristTrip/Event/
  FAQPage as a SECOND `ld+json` block; the BlogPosting block is always
  emitted unchanged, so entries keep author/date/headline.

### Database Schema
Key domain entities:
- `Weblog` - Blog instances with settings and metadata
- `WeblogEntry` - Individual blog posts with content and publishing status
- `User` - User accounts with roles and permissions
- `WeblogCategory` - Blog categorization
- `MediaFile` - File attachments and media
- `WeblogTemplate` - Custom template definitions

### Search Implementation
- **Engine**: Apache Lucene with background indexing
- **Operations**: Asynchronous add/remove/rebuild operations
- **Scope**: Full-text search across entries with category and locale filtering
- **Index Location**: Configurable work directory for search indices

## Module Organization

- **`app/`** - Main web application (executable WAR artifact)
- **`bin/db/`** - Schema migrations and the migrate/install scripts
- **`deploy/`** - Production deploy script and Caddy/backup config for
  `docker-compose.prod.yml` (see `docker_deployment.md`)
- **`it-selenium/`** - Browser integration tests (Selenium, run via `mvn verify -Pit`
  against the packaged executable WAR; see Coverage gates above)

## Configuration Files

### Key Configuration Locations
- **Boot Config**: `app/src/main/resources/application.properties` (server
  port/context-path, filter ordering, actuator exposure)
- **Dev Properties**: `app/src/test/resources/roller-boot-dev.properties`
  (loaded via `-Droller.custom.config` by `./roller dev` and NetBeans run/debug
  actions)
- **Servlets/Filters**: `app/src/main/java/.../boot/ServletRegistrationConfig.java`
  (Java-config transcription of the retired `web.xml`)
- **Security Config**: `app/src/main/java/.../boot/SecurityConfig.java`
  (Java-config transcription of the retired `WEB-INF/security.xml`)
- **JPA Mappings**: `app/src/main/resources/org/apache/roller/weblogger/pojos/*.orm.xml`
- **Velocity Templates**: `app/src/main/webapp/WEB-INF/velocity/templates/`

### Development vs Production
- **Development**: PostgreSQL via `docker-compose.yml` (postgres only; the
  app runs via `./roller dev` / `spring-boot:run`, not in a container),
  theme reload enabled, caching disabled.
- **Production**: containerized end-to-end via `docker-compose.prod.yml` —
  `app` (image built from `Dockerfile`, published to GHCR by CI only when a
  `v*.*.*` tag is pushed — see CI below), `postgres:16`, `caddy` (auto-TLS
  reverse proxy, the only
  published ports), and `backup` (nightly `pg_dump` + volume snapshots,
  atomic writes, rotation). One-command deploy/upgrade via
  `deploy/deploy.sh` (pulls or builds the image, applies pending migrations
  against `postgres` before starting `app`, waits for health, reconciles
  the rest). Full fresh-VPS runbook: `docker_deployment.md`. As in dev, the
  actuator health endpoint lives on management port 8090 and is never
  published to the host — reachable only via `docker compose exec app curl
  http://localhost:8090/actuator/health` or the container healthcheck.

## Themes
- A weblog runs either a **shared** theme (id from `themes/<id>/theme.xml`) or
  `WeblogTheme.CUSTOM`. Switching to custom **imports** the shared theme's
  templates as the weblog's own rows and is one-way — the weblog stops tracking
  the shared theme from then on. `ThemeIT` therefore works on weblogs it creates
  itself; never switch the seeded IT weblog.
- `ThemeEdit.jsp` keeps its Save buttons inside blocks hidden until its JS
  decides something changed, so anything driving that page must pick the theme
  (or the radio) first to reveal the right one.
- A theme switch reaches readers via `saveWeblog` bumping `lastModified`, not
  via `CacheManager.invalidate` — see Templates on `WeblogPageCache`.
- **`themes.customtheme.allowed` is enforced in `ThemeEditController`, not just
  in the menu.** `editor-menu.xml` gates the Design tab on it and that used to
  be the *only* check, so a POST to `themeEdit!save.rol` converted the weblog
  whatever the setting said — a one-way conversion behind a hidden menu entry.
  A weblog already on a custom theme is grandfathered (turning the option off
  stops new customisations; it must not strand a weblog that has no way back).
- `travel` and `portfolio` each ship a `_page` template
  (`themes/<id>/page.vm`) — a `WeblogPage` falls back to it through the same
  `StaticThemeTemplate` path as any other unthemed content, so a static page
  renders in the theme's own identity (travel's `tg-header` chrome, portfolio's
  dark frame) instead of
  the naked fallback template's bare `<h1>`. `TravelThemeRenderingTest`/
  `PortfolioThemeRenderingTest` pin this end to end: a page carrying
  `[contact]` must render through the theme's header/prose classes *and*
  ship the audience assets (contact form script/style) in the head, with an
  explicit `assertFalse` that the fallback template's unstyled `<h1>` is not
  what rendered.

## Configuration scope
Three scopes, and which one a property lives in decides whether it can be
changed without a restart — and therefore whether the test suites can cover
both of its branches in a single run.

- **Runtime (`runtimeConfigDefs.xml` → `roller_properties` → Admin Settings).**
  Read through `WebloggerRuntimeConfig`, which checks the DB row first and
  falls back to `WebloggerConfig` when there is none. Hot.
- **Startup (`roller.properties` / `roller-custom.properties`).** Read once via
  `WebloggerConfig`. Changing one needs a restart.
- **Per-weblog** (`Weblog` columns, edited on Weblog Settings) and **per-entry**.

**Promoting a startup property to runtime** means adding a `<property-def>` and
switching the call site to `WebloggerRuntimeConfig`. Three traps, all pinned by
`PromotedRuntimePropertyTest`:
1. The name now lives in two files; the defaults must match, or a fresh install
   and an upgraded one behave differently from identical configuration.
2. The DB row wins once it exists, so seeding must take the *startup* value
   (`JPAPropertiesManagerImpl.initialValueFor`) or the first boot after an
   upgrade silently discards what the deployer set.
3. The call site must genuinely re-read it. A `static final` (as
   `WeblogEntry`'s anchor separator was) or a value latched in `init()` keeps
   the old value until a restart, so promoting it buys nothing.

Promoted so far: `groupblogging.enabled`, `user.hideUserNames`,
`weblogentry.title.useUnderscoreSeparator`. (The comment subsystem's
`comment.throttle.enabled` was a fourth, promoted the same way — it was
removed along with the rest of comments in W1, not demoted; see Comments
below.) Other throttles' *sizing* (threshold/interval/maxentries) stays
startup-scoped — it dimensions a fixed cache that cannot be resized under
live callers; only the on/off switch is hot.

**Deliberately NOT promoted**, and not to be promoted without a decision:
- `weblogAdminsUntrusted` and `passwds.encryption.enabled` — promoting these
  would put "disable HTML sanitization" and "stop hashing passwords" on a web
  form. Security invariants stay at boot scope.
- `rememberme.enabled`, `themes.reload.mode`, `users.firstUserAdmin` —
  structurally boot-scoped (filter chain, Velocity engine config, first-user
  bootstrap).
- `search.enabled` — gates whether a Lucene index is built at all, so making it
  hot would mean either always paying for the index or serving search over one
  that does not exist.

Browser tests permute global runtime properties via `RollerIT.setGlobalFlag`
(or `setGlobalFlags` for several in one save), which drives the real Admin
Settings page and returns the previous value. **These are global and the suite
shares one instance**, so every caller must restore in a `finally`.

### Permutation coverage in the browser suite
Four classes carry the configuration matrix. The split is deliberate:

- `ThemeMatrixIT` — every bundled theme rendering one entry that carries
  `[image]`/`[gallery]`/`[map]`/`[faq]`, on both the home page and the
  permalink (different templates). One test looping the themes, not one per
  theme: the fixture costs ~9s and five methods would pay it five times.
  `frontpage` is excluded — it renders through `$site`, which exists only for
  the weblog named by `site.frontpage.weblog.handle`.
- `WeblogConfigMatrixIT` — per-weblog settings: locale, `entryDisplayCount`,
  and `active` (which also withdraws the weblog from the sitemap index). Each
  test owns its weblog and touches no global state. It used to also cover the
  per-weblog comment switches (moderation, allow/disallow); those tests and
  their `postCommentDirectly`/`approveComment` helpers were deleted with the
  rest of the comment subsystem in W1, not replaced — there is nothing left
  in this class to cover them with.
- `GlobalConfigMatrixIT` — **the only class that mutates site-wide state**:
  the feature-refusal switch (uploads/weblog-creation/group-blogging off) and
  the entry-URL word-separator setting. Kept to one on purpose: it is what
  would have to be serialised if the suite ever runs classes in parallel,
  since everything else is per-weblog. It used to carry three more tests
  covering the site-wide comment switches (off-at-the-servlet, moderation,
  HTML-escaping); those and the `postCommentDirectly` helper were deleted in
  W1 along with the comment subsystem they exercised, leaving the two tests
  above — the class still mutates global state, so the serialisation
  rationale for keeping it to one class is unchanged.
- `ScheduledEntryIT` — a future-dated entry is withheld from pages, feeds and
  the sitemap.

Two settings have **no reachable browser coverage**, both documented in place
rather than silently skipped:
- per-weblog `analyticsCode` — its textarea renders only when
  `weblogAdminsUntrusted` is off, and this fork keeps it on.
- `user.hideUserNames` — every bundled theme and feed uses
  `$entry.creator.screenName`, never `.userName`, so the flag changes nothing
  in shipped output.

`ScheduledEntriesTask` promoting a scheduled entry is also uncovered: an entry
is only `SCHEDULED` when its pubtime is >1 minute out, and the task cadence is
configured in whole minutes, so observing it costs 1-3 minutes with real
variance.

### BrowserHealth: two checks, not one
`assertNoBrokenResources` catches any sub-resource that came back 4xx/5xx.
`assertNoFailedRequests` catches requests that produced **no response at all**,
and exists because the first has a blind spot: a stylesheet whose URL 404s is
served an HTML error page, and Chrome — refusing a stylesheet with the wrong
content type — *aborts* the load rather than completing it. No
`Network.responseReceived` is ever emitted, so a theme whose CSS had gone
missing rendered unstyled and passed. Webfonts refused by a page's own CSP
arrive the same way.

The discriminator is what may legitimately be cancelled. Page script starts
`Image`/`XHR`/`Fetch` and may abandon them (Leaflet cancels ~48 tiles per map
render; jQuery UI's autocomplete cancels one XHR per keystroke), and a
`Document` navigation is cancelled by navigating again. A `Stylesheet`,
`Script` or `Font` is declared by the document and nothing cancels those, so an
abort there means the browser refused it. A **blocked** request is never
excused whatever its type.

## Templates
- Add/edit/remove live in `TemplatesController` and `TemplateEditController`;
  both resolve client ids through `BaseController.lookupTemplate`, and the
  isolation is pinned by unit tests in their `*ControllerTest`s.
- A CUSTOM template gets `link = name` and is then served publicly at
  `/<handle>/page/<link>` — including on a weblog running a *shared* theme,
  via `WeblogSharedTheme.getTemplateByLink`'s fallback to the weblog's own
  templates. `TemplateIT` asserts that end to end.
- `saveTemplate`/`removeTemplate` bump `weblog.lastModified`, which is what
  expires the rendered page in `WeblogPageCache` — that cache has no
  CacheHandler, so `CacheManager.invalidate(...)` never reaches it, and a page
  is only ever evicted lazily against `weblog.lastModified`.
- **Velocity in this codebase is lenient, and that is a live hazard whenever
  you delete a Java member.** `velocity.properties` sets no
  `runtime.references.strict` and turns off `runtime.log.invalid.reference`.
  A template reference to a deleted field or getter therefore does not throw
  and does not log — it prints as literal text (e.g. `$entry.commentCount`)
  into the rendered page, silently, with no failing test to catch it. This W1
  comment-removal wave shipped two such bugs before they were caught by hand
  (`journal/_day.vm`'s `$entry.commentCount`, and `feeds.vm`'s
  `<comments>$url.comments(...)</comments>` in every RSS feed). The mitigation
  is manual and has to stay manual: any change that deletes a Java member
  reachable from a template must `grep` `app/src/main/webapp/themes` and
  `app/src/main/webapp/WEB-INF/velocity` for it before calling the change
  done. This is not comment-specific — it applies to every future deletion
  that touches a pojo, wrapper, or model class a `.vm` file can reach.

## Admin UI
- **Design system**: `docs/design/design-system.md` is the committed spec
  ("Quiet Instrument" — tokens, type, spacing, and the three "signature
  moves": the rail spine, empty-states-as-invitations, the button
  hierarchy). Tokens live in `roller-ui/styles/roller-tokens.css` (light
  palette under `:root`, dark under `@media (prefers-color-scheme: dark)`,
  self-hosted IBM Plex `@font-face`), linked in `head.jsp` *after*
  `bootstrap.min.css` (so its custom properties are there to override) and
  *before* `roller.css` (so `roller.css` can override the tokens) — that
  ordering, every hex literal tracing to the spec's 21 values, and light/dark
  defining the same token names are all enforced by `DesignTokenTest`, not
  just convention.
- **Never restyle by renaming a selector.** Every admin route's content tile
  must keep emitting the CSS marker `Routes` pins for it in
  `it-selenium/.../support/Routes.java` — `RouteSweepIT` visits every route
  and asserts that marker, because Roller's layout renders full site chrome
  (banner, nav, footer, `<h2 class="roller-page-title">`) regardless of
  whether the content tile is wired up at all (the `categoryEdit.rol`
  failure mode: a healthy 200 with no actual form on it). A class rename
  that isn't also updated in `Routes` fails the sweep on purpose — the CSS
  change and the marker update belong in the same commit, not a silent
  drift.
- **The tiles system** is homegrown, not Apache Tiles: `ViewDefinition`
  (layout JSP + named attribute JSPs, e.g. `content`, `menu`) is resolved by
  `RollerViewResolver`, which registers seven base layouts
  (`.tiles-mainmenupage`, `.tiles-tabbedpage`, `.tiles-simplepage`,
  `.tiles-loginpage`, `.tiles-installpage`, `.tiles-errorpage`,
  `.tiles-popuppage`) in `init()`. `tiles-tabbedpage.jsp`/
  `tiles-mainmenupage.jsp` are the two that render `#adminRail` (weblog context block, then tool
  groups from the `navMenu` model under caps-labels, with `.rail-active` —
  a 2px inset accent rule — on the current tab) in place of the old
  "Powered by Apache Roller" card and the header dropdown menus.
  `RouteSweepIT.adminRailIsPresentWithAnActiveSpineOnATabbedPage` is a smoke
  test riding an already-covered route (Entries), not a new fixture.
- **Buttons theme through Bootstrap's `--bs-btn-*` custom properties**
  (`--bs-btn-hover-bg`, `--bs-btn-active-bg`, `--bs-btn-disabled-bg`, …),
  never literal `:hover`/`:active` rules of our own — Bootstrap's own
  `:active`/`.active`/`.show` chain reaches `(0,3,0)` specificity and beats a
  plain classed color override, so stock Bootstrap blue/green would flash on
  click otherwise. Redefining the variables per bucket (primary/secondary/
  destructive) makes every one of Bootstrap's *own* selectors resolve to a
  token color across the whole hover→active→disabled chain, in one place.
- **`.form-stacked`** on a `<form>` converts Bootstrap's
  `row.mb-3 > label.col-sm-3 + div.col-sm-9` grid to labels-above block flow
  without touching individual fields — used on `WeblogConfig.jsp`/
  `GlobalConfig.jsp` plus ten more forms in the Task 7 sweep.
- **`.empty-state`/`.empty-state-title`/`.empty-state-body`** are the
  "invitations, not shrugs" signature (one 600/16px title, one `--ink-soft`
  sentence, at most one primary-button action, icon-free) on Entries/Pages/
  Submissions/MediaFileView. `Pages.jsp`/`Submissions.jsp` render it
  as the lone `<tr>` in an otherwise-empty table body, which makes its `<td>`
  the tbody's first-child — the same structural hook the table header's
  caps-label rule keys off — so `.empty-state` resets those inherited
  properties rather than trusting every future caller to remember.
- `roller-ui/scripts/ajax-user.js` is pulled in with `<%@ include %>` (a
  translation-time include), so JSP scriptlets inside it **are** interpolated —
  it is not a static resource despite the `.js` extension. Shared by
  `UserAdmin.jsp` and `MembersInvite.jsp`, which do not have the same element
  ids, so anything touching one page's controls needs a null guard.
- Enabling/disabling an account is the Weblog-Settings-shaped hazard again: the
  checkbox persists whatever happens, so only an end-to-end check (disable, then
  try to sign in) proves it works. `UserAdminIT` does that.

## Categories
- **Ownership-check every id.** `BaseController.lookupCategory` is the third of
  the family alongside `lookupEntry`/`lookupTemplate`: the permission
  interceptor only vouches for the *action* weblog, so a global by-id lookup
  lets any editor rewrite any weblog's data. Both `removeId` and
  `targetCategoryId` need it — a foreign move target silently re-files this
  weblog's entries into someone else's blog. All three helpers treat a blank id
  as absent, not as something to look up.
- **Modal JS binds by control NAME, not id.** The page's JS was written against
  Struts-generated ids (`#categoryEditForm_bean_name`) that the JSP migration
  never reproduced, so add/edit/delete all silently did nothing. Names are what
  the server binds and cannot drift unnoticed.
- **Add and edit are different endpoints** (`categoryAdd!save.rol` /
  `categoryEdit!save.rol`); the shared modal picks by whether `bean.id` is set.
- A weblog's blogger category **can be null** — `removeWeblogCategory` nulls it
  when that category is deleted. Anything reading it must cope.

## Comments
The comment subsystem — servlet, manager, moderation screens, pojos, schema,
runtime properties, theme rendering — was removed outright in W1. It is not
coming back.

It was removed because it was unreachable **by design**, not because it was
unused by accident. `requireAuthenticatedComments` defaulted true (V013 set
`weblog.comment_auth_required` `DEFAULT true NOT NULL`), and this fork had
already removed public self-registration, so the only account that could ever
be signed in to post a comment was one an administrator had provisioned by
hand. A feature no stranger can ever reach is not a moderation queue, it is
dead weight with a bigger attack surface than the code that replaced it. The
contact form and the newsletter (see Audience below) are the reader-facing
channels now; neither needs an account.

Two things a future sweep for "comment" will trip over and must leave alone:
- **`roller_audit_log.comment_text`** is not a comment column. It is the audit
  log's own change note (e.g. "changed password"), unrelated to the removed
  feature by name collision only.
- **`util/GenericThrottle`** is not comment infrastructure, even though
  `CommentServlet` used to be its most visible caller. It also throttles
  `ContactController` (`contact.throttle.*`), `NewsletterController`
  (`newsletter.subscribe.throttle.*`) and `PasswordResetController`
  (`passwordreset.throttle.*`), and stays untouched.

One operational note for whoever runs Maintenance next: `IndexOperation` used
to index comment content/name/email into the Lucene index, and
`SearchOperation.SEARCH_FIELDS` included the comment content field, so site
search could match text a reader had posted in a comment. Both are gone from
the code, but an index built before this wave still has the old fields on
disk — stale comment text stays searchable until someone rebuilds the index
from the Maintenance page. Not a bug, just something that will look like one
until it's rebuilt.

## Entry editing
- **Layout**: `EntryEdit.jsp` (the approved card is committed at
  `docs/design/editor/editor-writing-surface.html`) is a writing surface plus
  a 252px publish rail, not a top-to-bottom form. The main column carries
  only title (large serif, borderless — **emphasis elsewhere is weight, never
  size**, the rule the whole card follows), the permalink as a mono line with
  a copy control, and the untouched `EntryEditor.jsp` include. Everything
  about *managing* the entry — not writing it — lives in the rail: a Publish
  box (status pill, the one visible time field, the submit buttons), an
  Organize box (category/tags/locale), an SEO drawer (the SEO & Social
  Sharing card, unchanged, just collapsed by default), and the
  newsletter/revisions cards as quiet boxes below. Delete is a quiet text
  link, not a red button. The `#entry` form is `display:contents` specifically
  so the newsletter/revisions cards — which carry their own `<form>`s (own
  CSRF token, own POST target) — can sit in the rail's grid column without
  nesting a `<form>` inside a `<form>`.
- **`bean.pubTimeLocal` is the entry's only pubtime field, and it means the
  WEBLOG's clock, not the browser's or the server's.** One `<input
  type="datetime-local">` replaced the old three-`<select>` hour/minute/second
  row plus a separate readonly date field. `EntryBean.getPubTime(TimeZone)`
  parses the submitted wall-clock string against whatever `TimeZone` the
  caller passes, and `EntryEditController` always passes
  `getActionWeblog(request).getTimeZoneInstance()` — pubtime has always meant
  the weblog's timezone, never the request locale's. A non-blank value that
  fails to parse now **throws** and the save is blocked with
  `entryEdit.pubTimeInvalid` via the normal `hasErrors` gate; the old
  dateString parser used to swallow a bad value and silently publish "now",
  which is exactly the failure mode a mistyped pubtime must not have. A blank
  field still means "no time chosen" → publish now, unchanged.
  **The SEO card's event-schedule fields (`bean.eventStartLocal`/
  `eventEndLocal`) are a different, older, and pre-existing quirk — do not
  assume they share pubTimeLocal's weblog-zone semantics.** They round-trip
  through `Timestamp.valueOf(LocalDateTime)` with no `TimeZone` parameter at
  all, so the stored instant is whatever the **server's default zone**
  happens to be at read/write time, not the weblog's. This predates the
  editor rebuild (SEO Wave 1) and was out of scope for it; a multi-timezone
  deployment where the weblog's zone differs from the server's will see
  `eventStart`/`eventEnd` drift from what an author typed. Fixing it means
  giving `EntryBean` the same `TimeZone`-parameterized accessor pubTimeLocal
  has — not yet done.
- **The sidebar is retired.** `EntrySidebar.jsp` is deleted;
  `RollerViewResolver`'s `.EntryEdit` layout definition now maps its
  `"sidebar"` attribute to `tiles/empty.jsp` instead. The four recent-entries
  lists (published/scheduled/draft/pending, 20 each) it used to render are
  gone from `EntryEditController` too — nothing else consumed them; Entries.jsp
  is where recent-entries lists live now.
- **Entry plugins are gone; the shortcode render seam stays.** The "Plugins to
  apply" checkbox card, `weblogentry.plugins`/`weblog.defaultplugins`
  (dropped V021, idempotent), and the sole registered plugin
  (`ConvertLineBreaksPlugin`) are all deleted — see Plugin System below.
  `PluginManagerImpl`/`WeblogEntryPlugin` and `WeblogEntry.render()`'s call
  into `applyWeblogEntryPlugins` are **not** deleted, because that call is
  also where `ShortcodeExpander` runs (see Shortcodes below); ripping out the
  seam would have ripped out shortcode expansion with it. With the per-entry
  opt-in column gone, both call sites now apply every site-registered plugin
  unconditionally rather than filtering by a name list that no longer exists
  anywhere — the same policy shortcodes already followed. In production the
  registry is presently empty, so this is a no-op today; it is the seam a
  future plugin would register into.
- **Editor**: EasyMDE (Markdown + server-rendered preview). The page exposes
  three functions that are the ONLY seam into the editor —
  `insertMediaFile`, `rollerSetEntryText`, `rollerGetEntryText` — so replacing
  the editor (e.g. with a WYSIWYG surface that edits Markdown) is one file.
  Browser ITs drive `.CodeMirror` and go through those functions, never the
  editor's own API. Byte-untouched by the rail rebuild.
- **Preview** is rendered server-side (`entryEdit!preview.rol`) because only the
  server can expand shortcodes; a browser-side Markdown library would disagree
  with the published page.
- **List actions**: `Entries.jsp` is ONE form around the table — bulk
  checkboxes, per-row duplicate, and the action bar all post through it, so the
  duplicate control is a submit button carrying `name="duplicateId"` rather than
  a nested form. Every bulk action loops per id through `BaseController`'s
  `lookupEntry`, and delete goes through `removeEntryWithIndex` so the Lucene
  index cannot be orphaned.
- **Revisions**: `weblogentry_revision` (V010) keeps the pre-save title/text/
  summary of every content-changing save. The snapshot is taken by a JPA
  `post-load` callback (`WeblogEntry.snapshotLoadedContent`) because
  `saveWeblogEntry` only ever sees the caller's NEW values. Retention is the
  runtime property `entry.revisions.retention`: **-1 (default) keeps
  everything**, 0 records none, n>0 prunes to the n newest in the save's own
  transaction. Rendered as a rail box below the publish rail, own form/CSRF
  per restore button.

**Controllers: always name `@RequestParam`/`@PathVariable` explicitly.** The
build does not pass `-parameters`, so a bare `@RequestParam String id` throws at
runtime while unit tests (which call the method directly) keep passing.
`ControllerMetadataTest` fails on any unnamed one.

## Pages
Static pages (`WeblogPage`, V014) are a separate entity from `WeblogEntry` **on
purpose** — folding them into entries would have meant threading a page/entry
distinction through all 25 of `WeblogEntryManager`'s query paths (date
archives, tags, feeds, pagers …), every one of which a page has no business
answering to.
- **Routing**: a published page is served at `/<handle>/<slug>` — a bare,
  single path segment. `ReservedSlugs` is the single source of truth for what
  a slug may **not** be, shared by the page-save validator
  (`WeblogPageManager`) and the request parser (`WeblogPageRequest`), so a
  slug that would collide with `entry`/`category`/`tags`/`feed`/… can never be
  saved in the first place. `WeblogRequestMapper` forwards any unknown
  single-segment path to the page servlet — the context whitelist gap the
  browser ITs caught; before this the mapper declined the request outright and
  a published page was never reachable at its own URL.
- **Lazy resolution**: `WeblogPageRequest` parsing sets only `pageSlug` (no
  database access — this is the field cache-key generation reads).
  `getWeblogPageContent()` resolves it lazily, memoized, on first call; a
  cache hit therefore never resolves the page it names, and a resolved slug
  that does not name a published page (unknown, or a draft) is a 404, never a
  fall-through to the permalink/default-page branches. `WeblogPageCache` and
  `SiteWideCache` keys carry a `/pageslug/<slug>` segment so a page and a same-
  named context never share a cache entry.
- **Rendering**: a theme may override the shipped page template with one
  named `_page` — same `StaticThemeTemplate` fallback path through
  `VelocityRendererFactory` that any other unthemed content uses, so a theme
  that has never heard of pages still renders them. `savePage`/`removePage`
  bump `weblog.lastModified`, the same lazy-expiry contract `WeblogPageCache`
  already relies on for templates (see Templates above) — there is no
  explicit cache eviction for a page edit.
- **Editor**: `PageEditController`/`PagesController` reuse the entry editor's
  shape (Markdown + shortcodes + SEO card) via `PageBean`. `lookupPage` joins
  `lookupEntry`/`lookupTemplate`/`lookupCategory` as the fourth
  ownership-checked-by-id family member on `BaseController` — a page id is
  client input and `getPage` is a global by-id lookup. The `showInNav`
  checkbox uses Spring's field-marker convention with the marker named
  `_showInNav`, **not** `_bean.showInNav` — the `bean.` prefix breaks marker
  resolution silently (the box stays checked forever), which is why a unit
  test reads `PageEdit.jsp` directly to pin the marker's actual name rather
  than hardcoding it.

## Audience
Contact forms, newsletter subscribe, and account tokens (Stage 2 Wave B). No
CAPTCHA anywhere; no CSP change anywhere — every endpoint is same-origin.

- **Placeholder-div + `#showAudienceAssets` injection, and WHY.** `[contact]`/
  `[subscribe]` (below) emit an inert `<div class="...-slot" data-*="...">`,
  never a `<form>`, because `HTMLSanitizer` strips `<form>` from authored
  content on purpose — an authored form is a phishing kit waiting to happen.
  `#showAudienceAssets` (`weblog.vm`, the audience twin of `#showEmbedAssets`)
  finds those slots client-side and builds the real forms. No theme CSP
  changes for this: both endpoints are same-origin, and `connect-src 'self'`
  already allows the fetch. The contact endpoint is built **server-side** —
  `ContactShortcode.render()` emits `WebloggerRuntimeConfig
  .getRelativeContextURL() + "/roller-ui/rendering/contact.rol"` into
  `data-endpoint` — because a client-side heuristic (scanning the page for a
  stylesheet `<link>` containing `/roller-ui/`) silently posted to the site
  root under a context path: a browser IT caught it when the only stylesheet
  on the page was the weblog's own theme CSS. The subscribe form's fetch is
  fixed the same way: both `SubscribeShortcode.render()` and the
  `#showSubscribeForm` macro emit a server-built `data-endpoint`
  (`WebloggerRuntimeConfig.getRelativeContextURL()` / `$url.site` — the same
  value, two call sites) instead of a client-guessed absolute-root
  `/newsletter/subscribe`, and `#showAudienceAssets`' injector reads
  `data-endpoint` off the slot for both forms rather than hardcoding either
  path.
- **Persist-first, then notify.** `ContactController` writes the
  `roller_form_submission` row before attempting any notification email —
  if SMTP is down the lead survives, which for a business running on leads is
  the failure that matters. Layered defences run in order: a per-IP throttle
  refuses abusive clients (429); an unknown weblog handle 404s; **a filled
  honeypot field or a too-fast submit answers 204, identically to a genuine
  success, and stores nothing** — the silent drop is deliberate, so
  automation learns nothing from being detected. The newsletter subscribe
  endpoint mirrors the same ordering and the same honeypot-answers-like-
  success contract.
- **`/newsletter/subscribe` is served by the app, not Caddy.** Throttle and
  `roller_event` recording both live in `NewsletterController`; the old Caddy
  `handle /newsletter/subscribe { rewrite ... reverse_proxy listmonk }` block
  is gone and **must never come back** — a path-specific rewrite in front of
  the app would silently bypass both the throttle and the event write. See
  `docker_deployment.md`. **Roller stores no subscriber data at all** —
  Listmonk owns the list, double opt-in, sending and unsubscribe; the only
  newsletter state Roller itself holds is `weblog.newsletter_list_uuid`
  (configuration, not a subscriber) and `weblogentry.newsletter_sent_at`.
  Newsletter list uuids are **not** required to be unique across weblogs —
  `getWeblogByNewsletterListUuid`'s named query orders by handle, so a shared
  uuid always credits the same (first-by-handle) weblog rather than throwing
  `NonUniqueResultException`.
- **`roller_event`** (V015) is written across Wave B — `FORM_SUBMITTED`
  (`ContactController`), `NEWSLETTER_SUBSCRIBED` (`NewsletterController`,
  only on a genuinely new subscription, not an already-subscribed 409), and
  `ENTRY_PUBLISHED` (`JPAWeblogEntryManagerImpl.saveWeblogEntry`, gated on
  `entry.getLoadedStatus() != PubStatus.PUBLISHED` — the same post-load
  snapshot mechanism entry revisions use, see Entry editing). One consequence
  worth knowing: unpublishing an entry and republishing it records a
  **second** `ENTRY_PUBLISHED` event, because the reload between the two
  saves resets `loadedStatus` away from `PUBLISHED`. Every write is
  best-effort (caught, logged, never fails the request that produced it).
  Wave C's SQL views read this table; the `metadata` jsonb column exists but
  is deliberately unmapped in JPA until something writes it.
- **`roller_user_token`** (V015) stores a SHA-256 digest only, never the raw
  token — a database read must not yield working reset links. Single-use
  (`consume` is an atomic rows-affected `UPDATE ... WHERE used_at IS NULL AND
  ...`, not validate-then-mark, closing the double-redemption race a
  read-then-write would leave open) and expires after
  `UserTokenManager.TOKEN_TTL_MS` (1 hour). Serves both the forgot-password
  flow and the admin "send set-password link" action
  (`PasswordLinkMailer.sendLink`, shared by both so the emailed URL shape
  cannot drift between them).
- **Forgot-password is enumeration-proof by construction.**
  `PasswordLinkMailer.isReady()` requires BOTH a configured mail transport
  (`MailUtil.isMailConfigured()`) AND a non-blank `site.adminemail` — checking
  only the transport half would leave a server that has SMTP but no site
  email looking ready while every send silently went nowhere. The flow's
  actual work (token issuance + email) runs off-thread via
  `ThreadManager.executeInBackground`, with `weblogger.release()` in a
  `finally` on that worker thread — the same convention `AddEntryOperation`
  established: background JPA work that never releases its `EntityManager`
  leaks a connection. Running the found-user and not-found paths through the
  same background/timing shape is what keeps the response identical either
  way; the form answers with the same confirmation message regardless of
  whether the submitted address matches an account, on purpose.
- **"Send as newsletter" is manual, synchronous, and stamped-on-success** —
  a deliberate deviation from a queued/retried send. `EntryEditController`
  calls `ListmonkClient.sendCampaign` in-request and stamps
  `weblogentry.newsletter_sent_at` only after it returns without throwing, so
  a failed send never marks the entry sent; the human who clicked the button
  IS the retry mechanism (no queue exists). If the campaign send succeeds but
  the stamp-save itself fails, the entry shows a distinct
  `newsletter.sentButNotRecorded` message rather than the generic error, so
  an editor isn't invited to click Send again and double-mail the list.

## Analytics
Per-weblog Umami tracking plus a read-only Grafana contract over two
databases (Stage 2 Wave C). Umami owns traffic; Roller owns first-party
outcomes; nothing is emitted that an admin typed.

- **Structured injection vs `weblogAdminsUntrusted`, and why.** `Weblog
  .analyticsSiteId` is a validated UUID (`WeblogConfigController.myValidate`
  rejects anything else), not markup. `#showAnalyticsTrackingCode`
  (`weblog.vm`) checks it first and, when present, **builds** the
  `<script defer src="…" data-website-id="…" data-host-url="…">` tag itself
  from that UUID plus two startup properties
  (`ConfigModel.getAnalyticsBasePath()`/`getAnalyticsScriptName()`, backed by
  `analytics.umami.basePath`/`analytics.umami.scriptName` in
  `roller.properties`) — no admin-typed text ever reaches the page head.
  That is what lets per-weblog analytics exist at all in this fork: the
  legacy free-text `analyticsCode` textarea it sits beside only renders when
  *Allow analytics code override* is on **and** `weblogAdminsUntrusted` is
  off, and this fork keeps `weblogAdminsUntrusted` on everywhere (see
  Permutation coverage above) — that textarea has never actually been
  reachable, and the structured field is what a weblog owner uses instead.
  The legacy branches remain in the macro (config-default fallback included)
  but are dead weight for any weblog on this fork's default settings.
- **Same-origin, so the pinned CSPs never moved.** The tracker is served
  from the blog's own origin through Caddy's `/analytics/*` handle
  (`docker_deployment.md`), which is why it runs under every bundled theme's
  `script-src 'self'` / `connect-src 'self'` without a single CSP edit this
  wave — `ThemeCspCoverageTest.everyPolicyStillAllowsSameOriginScriptsAndBeacons`
  is what would fail if that stopped being true.
- **The Grafana contract splits across two databases, because Postgres
  cannot query across them.** `rollerdb` and Umami's database share one
  Postgres instance but not a connection. `analytics_events` (first-party
  outcomes from `roller_event` — form submissions, subscriptions,
  publishes) and `analytics_weblog_sites` (the weblog-handle ↔ Umami-website-
  id join key) live in `rollerdb`, shipped by
  `bin/db/migrations/V017__analytics_contract.sql`. `analytics_traffic`
  (Umami's `website_event` rolled up to sessions/views by path and day)
  lives in Umami's own database, shipped by `deploy/analytics/umami-views.sql`
  and applied by `deploy/deploy.sh` — it cannot live in the migration chain,
  which only ever touches `rollerdb`. Grafana is the thing that joins the
  two halves (two datasources, a panel-level join on `website_id`); no
  server-side query ever spans both. `page_slug`/`entry_anchor` on
  `analytics_events`' `FORM_SUBMITTED` rows are copied from the contact
  form's reader-controlled `source` field — untrusted display text, not
  metadata — and `ENTRY_PUBLISHED` counts publish *events*, so an
  unpublish/republish cycle double-counts (same mechanism as the Audience
  section's `roller_event` note).
- **`SQLScriptRunner` is now dollar-quote-aware.** `V017`'s cluster-global
  `CREATE ROLE grafana_ro` needs a `DO $$ … EXCEPTION WHEN duplicate_object
  … END $$;` guard to survive re-application, but the install wizard's
  `SQLScriptRunner` — the third of the three migration appliers, alongside
  `migrate.sh` and the test harness — used to split SQL on bare semicolons
  with no awareness that one could be inside a dollar-quoted block, which
  would have silently corrupted that guard into broken fragments. The
  splitter now tracks dollar-quote state (`\$[A-Za-z0-9_]*\$` delimiters,
  any tag including the empty `$$`) and suspends both semicolon-splitting
  and `--`-comment-stripping while inside one.
  `SqlScriptRunnerMigrationTest` is what makes this real rather than
  theoretical: it runs the *actual* migration chain through
  `SQLScriptRunner`, the same applier `DatabaseInstaller` uses, not a
  synthetic fixture. One edge case is deliberately still a hazard, not a
  bug: a closing delimiter and a trailing `--` comment on the **same physical
  line** (e.g. `END $$; -- done`) isn't stripped, because the stripper only
  ever looks at the dollar-quote state *incoming* to that line — the comment
  becomes part of the accumulated (single-line-joined) command text and
  silently swallows whatever statement follows. Keep dollar-quote delimiters
  and any trailing comment off the same line as a terminating `;`.
- **The hitcount subsystem is gone; Umami replaced it.** Deleted whole:
  `HitCountQueue`, `HitCountProcessingJob`, `ResetHitCountsTask`,
  `ContinuousWorkerThread`/`WorkerThread` (orphaned once the queue went),
  `WeblogHitCount` (pojo + `.orm.xml`), the `roller_hitcounts` table (`V017`),
  `WeblogEntryManager`'s eight hitcount methods (`getHitCount`,
  `getHitCountByWeblog`, `getHotWeblogs`, `saveHitCount`, `removeHitCount`,
  `incrementHitCount`, `resetAllHitCounts`, `resetHitCount`),
  `Weblog.getTodaysHits()`/`WeblogWrapper`'s delegate, the Maintenance
  page's reset button, and the frontpage theme's "Hot blogs" sidebar.
  `WeblogPageRequest.isWebsitePageHit()`/`isOtherPageHit()` **survive** —
  they classify a request URL (website-root hit vs. some other page), which
  `PageServlet` still uses; only the *counting* that used to gate on them is
  gone, marked with a one-line comment at the old call sites.
- **`grafana_ro` ships `NOLOGIN`.** `V017` creates it with no password (a
  migration cannot carry a secret) and grants `SELECT` on exactly the
  contract views, never the underlying tables. An operator enables it with
  `ALTER ROLE grafana_ro LOGIN PASSWORD '...'` over `docker compose exec
  postgres psql` (`docker_deployment.md`); `deploy.sh` grants it `CONNECT`
  on both databases so one password works for both Grafana datasources.
  Postgres keeps no published host port in any compose file — access is
  tunnel-only, same as every other direct-DB debugging path in this repo.

## Plugin System
- **Entry plugins are a seam with nothing registered in it, on purpose.**
  `ConvertLineBreaksPlugin` — the only `WeblogEntryPlugin` ever shipped
  (`roller.properties` `plugins.page`) — is deleted, along with the per-entry
  "Plugins to apply" checkbox card and the per-weblog default in
  `WeblogConfig.jsp`'s Formatting section (permanently empty once the plugin
  was gone). `weblogentry.plugins`/`weblog.defaultplugins` drop via V021
  (idempotent) — any live `"ConvertLineBreaks"` data on an existing database
  is discarded deliberately. `PluginManagerImpl`, the `WeblogEntryPlugin`
  interface, and `Weblog.getInitializedPlugins()` all **stay**:
  `applyWeblogEntryPlugins` is also the render seam `ShortcodeExpander` runs
  through (see Shortcodes below and Entry editing above), so deleting the
  seam would have deleted shortcode expansion with it. With the opt-in column
  gone, both `applyWeblogEntryPlugins` and `WeblogEntry.render()` now apply
  every site-registered plugin unconditionally instead of filtering by a
  per-entry name list that no longer exists — a future
  `WeblogEntryPlugin` registers into this seam and runs for every entry, no
  UI required.

## Shortcodes
`org.apache.roller.weblogger.business.shortcodes` — `ShortcodeExpander`
expands `[name attr="v"]body[/name]` syntax **unconditionally** (independent of
entry plugins) at both render seams (`WeblogEntry.render()` and
`PluginManagerImpl.applyWeblogEntryPlugins`), immediately before
sanitization. Built-in: `[image id=".." caption=".." alt=".."]` emits a
responsive `<figure><picture>` (the Summernote media insert pastes it);
`[gallery dir=".." row=".." max=".."]` renders a media directory as a
justified grid (`GalleryMarkup`, flex-grow `--ar` CSS from the
`#showGalleryGridStyles` macro) with a PhotoSwipe lightbox
(`#showGalleryAssets`; EXIF overlay, captions), refusing private
directories; `[video url=".." caption=".."]` (YouTube/Vimeo) matches the url
against an allowlist of known provider shapes but never fetches anything — it
emits an inert placeholder `<div>` because
`HTMLSanitizer` strips iframes outright, and `#showEmbedAssets`
click-injects the real `<iframe>` client-side only once a reader opts in
(consent-gated embeds: no frame, no cookies, and no script from the provider
before that click — though the placeholder's thumbnail `<img>`, e.g.
YouTube's `i.ytimg.com`, does load from the provider's CDN at render time).
The theme
CSPs each carry a `frame-src` naming the provider's embed origin, pinned
byte-for-byte by three rendering tests the same way the Leaflet `img-src *
data:` addition is;
`[contact]` and `[subscribe]` (Stage 2 Wave B) are the third and fourth uses
of the same placeholder-div pattern: each emits an inert `<div class="...-
slot" data-*="...">` (never a `<form>` — the sanitizer strips those), and
`#showAudienceAssets` injects the real form client-side. `[contact]` carries
a server-built `data-endpoint` (see Audience above for why); `[subscribe]`
carries `data-list-uuid` and renders nothing at all when the weblog has no
newsletter list configured or the stored uuid doesn't have a uuid's shape.
`[[name ...]]` / `[[/name]]` escape a registered shortcode to literal text;
unknown names and malformed input pass through byte-for-byte. New handlers
implement `ShortcodeHandler` and register in `defaultExpander()`; the interface
also requires a `ShortcodeCard` (label + snippet), which is what the editor's
Insert menu is generated from, so a new shortcode cannot ship undiscoverable.