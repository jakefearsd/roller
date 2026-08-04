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
- Browser ITs run in CI (`mvn verify -Pit`) — see `it-selenium/`.

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
  featured images + focal points, Stage 2 Wave 2; and `travel` — light
  guide-card theme that frames the travel shortcodes, Stage 2 Wave 3)
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

### Share Links (Stage 2 Wave 2)
- `ShareController` serves `/share/<token>` — a tokened public view of one
  entry (drafts included) or one media directory, optional password with
  session unlock, never cached. Directory media is served share-scoped at
  `/share/<token>/media/<id>`. Editor UI: cards on MediaFileView (per
  directory, plus the `is_private` toggle) and EntryEdit.
- Private directories (`MediaFileDirectory.isPrivate()`): 404 on the base
  media path (except logged-in editors of the owning weblog), excluded from
  sitemaps, refused by the `[gallery]` shortcode — reachable only via the
  share link.

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
- `WeblogEntryComment` - Comment system
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
  `app` (image built from `Dockerfile`, published to GHCR by CI on every
  push to master), `postgres:16`, `caddy` (auto-TLS reverse proxy, the only
  published ports), and `backup` (nightly `pg_dump` + volume snapshots,
  atomic writes, rotation). One-command deploy/upgrade via
  `deploy/deploy.sh` (pulls or builds the image, applies pending migrations
  against `postgres` before starting `app`, waits for health, reconciles
  the rest). Full fresh-VPS runbook: `docker_deployment.md`. As in dev, the
  actuator health endpoint lives on management port 8090 and is never
  published to the host — reachable only via `docker compose exec app curl
  http://localhost:8090/actuator/health` or the container healthcheck.

## Entry editing
- **Editor**: EasyMDE (Markdown + server-rendered preview). The page exposes
  three functions that are the ONLY seam into the editor —
  `insertMediaFile`, `rollerSetEntryText`, `rollerGetEntryText` — so replacing
  the editor (e.g. with a WYSIWYG surface that edits Markdown) is one file.
  Browser ITs drive `.CodeMirror` and go through those functions, never the
  editor's own API.
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
  transaction.

**Controllers: always name `@RequestParam`/`@PathVariable` explicitly.** The
build does not pass `-parameters`, so a bare `@RequestParam String id` throws at
runtime while unit tests (which call the method directly) keep passing.
`ControllerMetadataTest` fails on any unnamed one.

## Plugin System
Roller supports plugins for:
- **Entry Plugins**: Content processing and formatting
- **Comment Plugins**: Comment text formatting (`WeblogEntryCommentPlugin`) —
  no spam filtering exists; moderators mark spam manually (`ApprovalStatus.SPAM`)
- **UI Plugins**: Editor components and custom functionality

Plugins implement specific interfaces and are configured through the plugin manager system.

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
directories.
`[[name ...]]` / `[[/name]]` escape a registered shortcode to literal text;
unknown names and malformed input pass through byte-for-byte. New handlers
implement `ShortcodeHandler` and register in `defaultExpander()`; the interface
also requires a `ShortcodeCard` (label + snippet), which is what the editor's
Insert menu is generated from, so a new shortcode cannot ship undiscoverable.