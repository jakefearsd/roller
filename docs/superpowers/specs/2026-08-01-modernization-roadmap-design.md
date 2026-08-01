# Roller Modernization Roadmap — Design

**Date:** 2026-08-01
**Status:** Approved
**Supersedes context in:** 2026-03-29 simplification specs (those phases are complete)

## Context

This fork of Apache Roller 6.2.0 is a private multi-blog server for a small
business: 10–50 blogs across two use cases — (1) travel and rental-property
city guides, (2) Maiia's photography portfolio and events. Not a public
service; all accounts are admin-created.

Work completed before this spec: Struts2 → Spring MVC migration, PostgreSQL-only
with versioned migrations, removal of XML-RPC/AtomPub/OAuth1/pings/trackbacks/
planet/bookmarks/LDAP/OpenID/public-registration/mobile-detection, unit coverage
27% → 69% (2,039 tests), PIT mutation testing, a Testcontainers harness, and a
Selenide browser IT suite with sub-resource health checking.

This spec defines the next arc: **modern infrastructure, top-tier coverage,
easy administration, and a feature set benchmarked against Ghost 6, WordPress
6.8, Publii, Pixieset, and Astro** — sized for this fork's actual use cases.

## Decisions (settled during design; do not re-litigate)

| Decision                  | Choice                                                                                                                                                                                           |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Platform direction        | Evolve this Java/Spring/PostgreSQL codebase — no rewrite, no replatform                                                                                                                          |
| Production infrastructure | Single VPS, Docker Compose: app + PostgreSQL + Caddy (auto-TLS) + backup container                                                                                                               |
| Runtime                   | Convert to Spring Boot **4.x** (Spring Framework 7, Spring Security 7, Jakarta EE 11), embedded **Tomcat 11**, **Java 25** compile target; packaged as **executable WAR** (JSP constraint, see Risks) |
| Admin UI                  | Modernize the JSP admin **in place**, screen by screen; no SPA                                                                                                                                   |
| Public rendering          | Velocity pipeline stays                                                                                                                                                                          |
| Database                  | PostgreSQL only, migration-chain discipline unchanged                                                                                                                                            |
| Quality bar               | Ratchet + gates: ~90% diff coverage on new/changed code, per-package floors that only rise (target 85% overall), PIT thresholds on core packages, browser IT per new admin screen/public feature |
| Roadmap shape             | Foundation first: Stage 0 safety net → Stage 1 platform → Stage 2 features                                                                                                                       |
| Big-ticket features       | All in scope: modern editor, newsletter (Listmonk), analytics (Umami), cross-posting (Bluesky/Mastodon)                                                                                          |

## Non-goals

Public multi-tenancy, ActivityPub federation, memberships/payments, SPA admin,
replacing Velocity, supporting any database besides PostgreSQL, spam-scoring
pipeline (deliberately removed; remaining dead machinery gets deleted).

## Current state (survey, 2026-08-01)

Facts the implementation plans will rely on:

- **Web layer**: 34 Spring MVC controllers (`ui/controllers/{admin,core,editor}`)
  mapped to ~95 `.rol` paths (Struts-era `!action` names preserved for
  compatibility); 10 hand-written rendering servlets under
  `/roller-ui/rendering/*` for public pages; 3 AJAX servlets.
  `BaseController` + `RollerHandlerInterceptor` provide auth/weblog resolution.
- **Rendering**: 76 JSPs (admin, layouts via the hand-rolled `RollerViewResolver`,
  469 LOC of tile definitions in Java); 64 Velocity templates (4 shared themes:
  basic, gaurav, fauxcoly, frontpage; 11 feed templates; system templates).
  Four chained Velocity resource loaders (webapp → theme dir → DB → classpath).
  Domain objects reach templates only through 8 read-only wrapper classes.
- **Persistence**: JPA/EclipseLink 5.0, XML `.orm.xml` mappings (19 files),
  6 JPA managers (largest: `JPAWeblogEntryManagerImpl`, 1,378 LOC).
  3 migrations in `bin/db/migrations/`, consumed identically by
  `migrate.sh`, the web install wizard, and the test harness.
- **DI**: Guice (1 module, 13 bindings) builds the business tier; Spring owns
  only controllers. Bridge: `WebloggerFactory` static singleton — **360 call
  sites across 111 files**. A package-private `installProvider` test seam exists.
- **Search**: Lucene 9.12.3, async `IndexOperation`s behind a RW lock.
- **Caching**: in-JVM LRU caches for pages/feeds/site/CSRF-salts, config-driven,
  object-graph invalidation. No clustering (fine — single node).
- **Background tasks**: DB-lease-coordinated `RollerTask` scheduler
  (`ScheduledEntriesTask`, `ResetHitCountsTask`).
- **Coverage**: 69.2% line / 59.8% branch overall, but **the entire public
  request path is at 0%** — `ui.rendering.servlets` (1,043 lines),
  `ui.rendering.velocity` (249), `ui.rendering` (204), `ui.tags` (133),
  `ui.controllers.ajax` (141). Admin-side packages sit at 93–99%.
- **Tests**: 2,039 passing. Guard tests: `RouteCoverageTest`, `MessageKeyTest`,
  `WebjarReferenceTest`, `SchemaMigrationTest`, JSP precompilation
  (`jetty-jspc-maven-plugin`). Browser ITs (`RouteSweepIT`, `AuthoringJourneyIT`)
  run Tomcat 10.1 via cargo + real Chrome with DevTools sub-resource checking —
  **but CI never runs them** and JaCoCo `check` is wired but skipped
  (`jacoco.check.skip=true`, minimums 0.00).
- **Deployment**: the Dockerfile is broken beyond repair (builds upstream 6.1.0
  from the internet on Java 17 into Tomcat 9; the app needs this fork, Java 21,
  Tomcat 10.1+). `docker_deployment.md` documents a stack that does not exist.
  Real path today: WAR + external Tomcat + JNDI, as encoded in `it-selenium/pom.xml`.
- **Key versions**: Java 21, Spring Framework 6.2 (XML config, no Boot), Spring
  Security 6.5 (legacy voter mode), EclipseLink 5.0, Velocity 2.4.1,
  Lucene 9.12.3, Bootstrap **3.4.1 (EOL)** + jQuery 3.7.1 + Summernote 0.8.20,
  jakarta.servlet 6.0, PostgreSQL driver 42.7.9.
- **Notable dead weight** (verified unused): ROME (only `LinkbackExtractor`,
  itself uncalled), Bouncy Castle, JBoss/GlassFish descriptors, `radiomap.ftl`
  (Struts2 fossil), a second checked-in jQuery 2.1.1, `jquery.mobile-theme/`,
  `roller-ui/theme/{tan,blue}/`, `RSDServlet` (advertises deleted APIs),
  `TestTask`, `CmaRollerContext`, `StandaloneWebappClassLoader`, ~214 orphaned
  i18n keys per bundle, a spam pipeline with no validators wired (its admin
  panel does nothing), dead `roller.properties` keys (hibernate.*, salt.*,
  securelogin.*, cache.futureInvalidations.peerTime, uploads.migrate.auto).
- **Stale docs**: `README.md` advertises eight removed features; `CLAUDE.md`
  claims LDAP support and disabled ITs; CI workflow carries a stale comment
  about the IT module.

---

## Stage 0 — Safety net

Purpose: make the public request path tested and make CI enforce everything,
*before* platform surgery begins.

1. **Public-path rendering tests (in-JVM).** Drive `PageServlet`, `FeedServlet`,
   `SearchServlet`, and `CommentServlet` with mock servlet requests against the
   real Testcontainers database and real Velocity templates. Assert on rendered
   output: entry permalink page, weblog front page, RSS 2.0 + Atom feeds,
   search results, comment submission including the moderation
   (`commentModerationRequired`) flow. Cover `WeblogRequestMapper` URL parsing
   and the wrapper-enforced template model. Target: `ui.rendering.*` moves from
   0% to a meaningful floor (≥60%).
2. **Anonymous-visitor browser ITs.** Extend the IT suite with logged-out
   routes: blog page, permalink, feed, search — each with a content-specific
   CSS marker per the existing `Routes` discipline.
3. **ITs in CI.** `mvn verify -Pit` as a CI job (Chrome is present on
   `ubuntu-latest`); remove the stale workflow comment.
4. **Engage the ratchet.** Set JaCoCo per-package minimums at current actual
   levels (never lower); add a diff-coverage check (~90%) for changed code.
   Ratchet values are raised at each stage boundary.

**Exit criteria:** every anonymous route exercised in CI; JaCoCo `check` active
and failing on regression; browser ITs green in CI.

## Stage 1 — Platform modernization

Ordered steps; each is its own commit series.

1. **Collapse Guice into Spring.** Register the 13 business bindings as Spring
   beans (constructor injection). `WebloggerFactory` becomes a thin shim over
   the Spring context so existing call sites keep working; migrate call sites
   to injection incrementally — controllers and rendering servlets first (they
   are what the new tests exercise) — then delete the shim and the Guice
   dependency. The `installProvider` test seam is replaced by ordinary Spring
   test configuration.
2. **Spring Boot conversion.** Target **Spring Boot 4.x** (Spring Framework 7,
   Spring Security 7, Jakarta EE 11). Executable **WAR** (`java -jar`, embedded
   **Tomcat 11** / Servlet 6.1; still deployable to an external Tomcat 11
   during transition). Tomcat 10.1 is not a valid target — it reaches end of
   life on 2026-12-31. Boot 4 is also the *aligned* pairing for this codebase:
   EclipseLink 5.0 implements Jakarta Persistence 3.2, the EE 11 level, so
   Boot 3.x (EE 10) would have been the version mismatch.
   - Compiler `<release>` moves 21 → **25** (current LTS; the CI matrix
     already exercises JDK 25, and the ASM 9.9 weaving stack supports it).
     The JDK 21 CI leg is dropped once the Boot conversion lands.
   - `web.xml` (404 lines) → `ServletRegistrationBean`/`FilterRegistrationBean`
     Java config, preserving the documented filter order (CharEncoding →
     IPBan → firewall-exception → Spring Security → Bootstrap → Persistence →
     Init → RequestMapping).
   - `security.xml` → `SecurityFilterChain` Java config on the modern
     authorization-manager model. This is mandatory, not optional: Spring
     Security 7 removes the legacy voter mode
     (`use-authorization-manager="false"`) entirely. Fine-grained checks stay
     in `RollerHandlerInterceptor`.
   - JNDI datasource + mail session → Boot-managed, configured by environment
     variables. EclipseLink retained via explicit `JpaBaseConfiguration`
     (Boot defaults to Hibernate; this is the one deliberate off-default).
   - `roller.properties` remains for application semantics; infrastructure
     keys (DB, mail, ports, storage dirs) move to Boot configuration with the
     custom-override chain preserved (`roller-custom.properties` behavior).
   - Actuator: `/actuator/health` (+ readiness/liveness) for container checks.
   - Dev loop: `./roller dev` switches from jetty:run to `spring-boot:run`
     (or `java -jar`) with devtools; the IT harness drops cargo and launches
     the executable WAR directly, deleting the port-juggling/WAR-unpacking
     machinery documented in `it-selenium/pom.xml`.
3. **Real deployment stack.**
   - Multi-stage `Dockerfile` building **this fork**: Temurin 25 build stage →
     JRE 25 runtime stage running the executable WAR as a non-root user, with
     volumes for media files and the search index.
   - Production `docker-compose.yml`: `app`, `postgres:16` (named volume),
     `caddy` (automatic TLS; per-blog hostnames supported), `backup` (nightly
     `pg_dump` with rotation + media/search-index volume snapshots).
     Healthchecks wired to Actuator.
   - `deploy.sh`: one command — pull image, run migrations
     (`bin/db/migrate.sh`), restart app, verify health.
   - CI: build and publish the image (GHCR) on push to master.
4. **Fossil sweep + docs.** Delete everything in the "notable dead weight" list
   above, including the unreachable spam machinery and its admin panel, the
   RSD servlet, and dead config keys. Rewrite `README.md` and `CLAUDE.md` to
   match reality; replace `docker_deployment.md` with documentation of the
   real stack; prune stale `testing/` files and the orphaned
   `assembly-release/` module decision (delete — releases are container images
   now).
5. **Admin UI base refresh.** Bootstrap 3.4 → 5 across all 76 JSPs (layouts
   first, then screens), jQuery retained, Summernote replaced later in Wave 4.
   Done last in Stage 1 so every new Stage 2 screen is built once on the new
   design system. Browser IT suite must be green before and after; webjar
   guard tests updated.

**Exit criteria:** `docker compose up` on a fresh VPS yields a working,
TLS-terminated, backed-up installation; `java -jar roller.war` runs locally;
Guice gone; all tests green; ratchet floors raised.

## Stage 2 — Features

Wave 1 is prerequisite to Waves 2–3; Waves 2–5 are otherwise independent and
can proceed in any order (or in parallel worktrees).

### Wave 1 — Media & SEO foundation

- **Responsive image pipeline.** On upload, generate a width ladder
  (480/960/1600/2400, capped at original) + WebP via Thumbnailator, stored as
  renditions beside originals under `mediafiles.storage.dir`; extend
  `MediaFileManager`/`FileContentManager`. Theme macros emit
  `srcset`/`sizes`, explicit `width`/`height` (no layout shift), and
  `loading="lazy"`. Backfill task for existing media.
- **Blurhash/LQIP.** Tiny placeholder computed at upload, stored on
  `MediaFile`, rendered while full images lazy-load.
- **EXIF extraction** (metadata-extractor): camera, lens, exposure, ISO, date,
  GPS → columns on `MediaFile`. Per-blog option to strip GPS from published
  renditions (privacy default: strip).
- **Featured image per entry.** Column on `weblogentry` + editor picker; used
  by OG tags, entry cards, list layouts, and themes.
- **Automatic SEO baseline.** Every themed page emits canonical URL, OG/Twitter
  card tags, and JSON-LD `BlogPosting` with zero per-post work (Ghost/Publii
  model), via shared Velocity macros + a model helper.
- **Sitemaps + robots.** Per-blog XML sitemap and image sitemap plus aggregate,
  as Spring controllers with lastmod; robots.txt.
- **Per-entry SEO panel.** Optional meta title/description, OG image override,
  canonical override, noindex flag; Google/social snippet preview in the
  editor. Columns via migration.
- **Shortcode expander mechanism.** A generic `[shortcode]` processor in the
  rendering pipeline (applied to entry content before Velocity output) that
  later waves register blocks with (gallery, map, CTA button,
  image-with-caption). Defining it here keeps Waves 2–5 order-independent.

### Wave 2 — Photography

- **Gallery shortcode** rendering a justified grid; self-hosted vanilla-JS
  lightbox (captions, keyboard nav, EXIF overlay, blurhash placeholders,
  IntersectionObserver lazy load). No CDN assets.
- **Password-protected share links.** Per-entry/per-gallery share tokens with
  optional password (Pixieset client-gallery model) — no user accounts needed.
- **Crop + focal point.** Cropper.js in admin, server-side re-encode; focal
  point drives hero/card/OG crops.
- **Portfolio theme.** Purpose-built full-bleed grid theme using the shared
  theme system.

### Wave 3 — Travel

- **Map shortcode.** Leaflet + OpenStreetMap (self-hosted assets, no API key):
  manual pins/routes per post; optional auto-map from photo GPS.
- **Travel structured data.** Per-entry JSON-LD type selector:
  `TouristAttraction`, `TouristTrip`, `Event`, `FAQPage`.
- **CTA/booking button shortcode.** Styled button card for rental/booking/print
  links with UTM tagging.
- **Travel-guide theme.** Itinerary/FAQ/map layout blocks.

### Wave 4 — Editor & authoring QoL

- **Markdown editor with live preview.** A maintained component (Toast UI or
  Milkdown), vendored as a webjar; server-side rendering via commonmark-java.
  **Per-entry format flag** — existing HTML entries render exactly as before;
  markdown is opt-in per entry. Replaces Summernote for markdown entries;
  the vestigial dual-editor plugin system is deleted.
- **Shortcode cards.** Toolbar/slash insertion for whichever shortcodes have
  shipped (gallery, map, CTA button, image-with-caption), using the Wave 1
  expander; the editor preview renders them via the same expander.
- **Authoring QoL.** Post duplication (city-guide skeletons), bulk
  publish/tag/delete on the entries list, entry revisions
  (`weblogentry_revision` written on save, diff + restore view).

### Wave 5 — Services (compose containers, minimal in-app code)

- **Newsletter (Listmonk).** Listmonk container; per-blog list mapping;
  public subscribe endpoint proxying to Listmonk's API (double opt-in handled
  by Listmonk); "send this post as email" on publish.
- **Analytics (Umami).** Umami container; per-blog website IDs injected via the
  existing per-weblog analytics-code mechanism (near-zero app changes);
  cookie-free, no consent banner.
- **Cross-posting.** On publish, optionally push title/link/image to Bluesky
  and/or Mastodon (plain JSON APIs), per-blog credentials, executed by the
  existing background-task framework with retry. No ActivityPub.

## Quality gates (every stage and wave)

- TDD; ~90% diff coverage on new/changed code, enforced in CI.
- JaCoCo per-package floors raised at each stage boundary, never lowered;
  overall target 85%.
- PIT mutation thresholds on `util` and core `business` packages.
- A browser IT with content-specific markers for every new admin screen and
  every new public-facing feature; `RouteCoverageTest` classification kept
  current.
- Every schema change is a numbered migration passing `SchemaMigrationTest`
  (idempotent, discoverable).
- Existing guards stay: JSP precompilation, `MessageKeyTest`,
  `WebjarReferenceTest` (or its generated replacement).

## Execution model

This spec is the durable context document for implementation sessions run by
Opus/Sonnet. Each stage (and each Stage 2 wave) gets its own implementation
plan written via the writing-plans skill: bite-sized, independently-committable
tasks with explicit verification commands and exit criteria. Stages are
strictly ordered (0 → 1 → 2); within Stage 2, Wave 1 precedes Waves 2–3, and
Waves 2–5 may run in any order or in parallel worktrees. Commits go directly
to master (solo-dev convention), one logical change per commit, only when the
user asks.

## Risks and mitigations

1. **Boot + JSP.** Executable *jar* packaging silently breaks JSP rendering —
   executable **WAR** is a hard constraint of this design.
2. **Boot + EclipseLink.** Boot auto-configures Hibernate; EclipseLink needs an
   explicit `JpaBaseConfiguration` + build-time weaving check. Verified by the
   full suite plus a dedicated context-load test before call-site migration.
   (Version alignment is favorable: EclipseLink 5.0 = Jakarta Persistence 3.2
   = EE 11 = Boot 4.)
3. **EE 11 JSP/JSTL stack.** Tomcat 11 means Jakarta Pages 4.0 / EL 6.0. The
   76 JSPs and the JSTL 3.0 taglibs must be verified under Pages 4.0, and the
   JSP-precompilation guard must move to an EE 11-capable JSPC (Jetty 12.1
   ee11 or Tomcat 11 jasper) — the current `jetty-jspc-maven-plugin` setup
   targets EE 10. The precompile guard is re-proven with a deliberately broken
   JSP before it is trusted.
4. **Bootstrap 3 → 5** touches all 76 JSPs. One dedicated phase; browser IT
   suite green before/after is the gate; no functional changes mixed in.
5. **Security config conversion** (voter mode → authorization manager) could
   silently change access rules, and Security 7 removes the old model
   entirely. The 8 `intercept-url` patterns get explicit authorization tests
   (authenticated/anonymous × allowed/denied) written *before* conversion.
6. **Editor migration.** Dual-format flag means zero risk to existing content.
7. **IT harness rewrite** (cargo → executable WAR) temporarily reduces
   deployment realism; mitigated because the executable WAR *is* the new
   production artifact — the harness becomes more realistic, not less.

## Feature research appendix (2026-08 survey)

Benchmarks: Ghost 6.0 (card editor, zero-config SEO, native analytics,
newsletters, ActivityPub), WordPress 6.8 (Gutenberg, srcset/WebP media library,
Yoast/RankMath norms), Publii (full built-in SEO suite, purpose-built theme
categories), Micro.blog (cross-posting), Astro/Hugo (build-time image
guardrails), Buttondown/Substack (post-to-email loop), Pixieset/Format/SmugMug
(client galleries, lightbox with EXIF, password-protected sharing).

Explicitly rejected for this fork: ActivityPub federation, memberships/paid
subscriptions, audience-discovery features, bespoke block editor. Rationale:
private 10–50-blog server; cost/benefit strongly negative.
