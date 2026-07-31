# Browser Integration Testing for Roller

**Date:** 2026-07-31
**Status:** Approved, pending implementation

## Problem

Roller has no working integration tests. The `it-selenium` module contains one
test, `InitialLoginTestIT`, which drives public user registration — a feature
that was removed — using JUnit 4 and Selenium 3 APIs against a hardcoded
`localhost:8080`. The module is commented out of the parent POM. Nothing in the
codebase references MockMvc, `MockHttpServletRequest`, or any of the rendering
servlets.

Two production defects reached the running application this year without any
test failing:

1. **Broken webjar URLs.** `head.jsp` referenced jquery-ui 1.14.1,
   jquery-validation 1.20.0 and summernote 0.8.12 while the POM shipped 1.14.2,
   1.21.0 and 0.8.20. All three 404'd on every admin page, disabling the
   rich-text editor, date pickers and client-side validation. The pages still
   rendered, so any conventional assertion still passed.

2. **A JSP that compiled and then 500'd.** Removing OpenID markup left an
   orphaned `</c:if>` and a `c:choose` with no `c:when` in `Login.jsp`. The Java
   build passed and all 124 unit tests passed. It was found only because someone
   happened to `curl` the page.

The second defect is the more alarming one: `Login.jsp` is public. `Profile.jsp`
and `UserEdit.jsp` sit behind authentication and could have stayed broken
indefinitely.

A JSP precompilation step (`jetty-jspc-maven-plugin`) now catches JSP *syntax*
errors at build time. Nothing catches a page that compiles and then fails at
render time on a null model attribute, a bad EL expression, or a missing asset.

## Goals

Catch runtime-only breakage in a real browser:

- Pages that return 500 or render empty
- Assets that 404 (the webjar class of bug)
- JavaScript errors and uncaught exceptions
- Broken authoring workflows

Non-goals for this iteration: performance testing, cross-browser testing,
accessibility auditing, visual regression.

## Design

### Module and tooling

Reuse the `it-selenium` directory, replacing its contents entirely. The JUnit 4
page objects and `InitialLoginTestIT` are deleted. The name is retained because
Selenide wraps Selenium and existing docs reference the path.

Stack follows the sibling jspwiki/wikantik project: **Selenide + JUnit 5 +
maven-failsafe-plugin**. The module rejoins the reactor behind a `-Pit` profile
so that a plain `mvn install` remains fast; `mvn verify -Pit` runs the suite.

### Harness lifecycle

```
pre-integration-test
  docker-maven-plugin   postgres:16 on a build-scoped port (it.db.port)
  bin/db/migrate.sh     applies V001..V003 — the same chain as production
  seed-it-data.sql      admin user, one weblog, one category
  cargo-maven3-plugin   downloads Tomcat 10.1, deploys roller.war
                        plus a context.xml providing the jdbc/rollerdb
                        JNDI DataSource the WAR requires
integration-test
  maven-failsafe-plugin runs *IT
post-integration-test
  cargo stop, docker stop
```

Tomcat **10.1**, not 9: Roller uses `jakarta.servlet 6.0`, which Tomcat 9 (Java
EE 8, `javax.*`) cannot run.

PostgreSQL runs in its own container on its own port, so integration tests never
touch the development database from `docker-compose.yml`.

The schema comes from the real migration chain rather than a test-specific
script, for the same reason the unit tests do it that way: a second schema
definition is a second thing to drift.

### BrowserHealthExtension

A JUnit 5 extension applied to every integration test. After each test it fails
the test if the browser reported:

- a console error or uncaught JavaScript exception
- a non-2xx response for any script, stylesheet or image

Failures name the asset URL and the page that requested it.

This is what makes the suite catch defect (1) above. A conventional Selenide
assertion passes while Summernote 404s in the background; this does not. Every
page the suite visits is checked, so coverage does not depend on anyone
remembering to assert it.

A short allowlist accommodates genuinely noisy third-party requests. It is kept
in one file and reviewed when it changes.

**This is the main technical risk in the design.** Console logs are
straightforward via Chrome's `goog:loggingPrefs`. Response status codes are not:
Selenium 4's W3C mode does not expose them. Two candidate routes exist — the
Chrome DevTools Protocol (`Network.enable` / `Network.responseReceived`) and
Selenide's `SelenideProxyServer` with a response filter. This must be spiked and
proven before the rest of the suite is built on it.

### Phase 1 — reachability sweep

A single `@ParameterizedTest` over every admin route. For each: log in as the
seeded admin, visit, and assert the page returns non-500 and has content. The
`BrowserHealthExtension` supplies the console and asset checks.

This is a small amount of code covering roughly sixty routes, and it directly
addresses the "pages behind authentication can sit broken indefinitely" problem.

Accompanied by a **guard test** that reflects over the `@GetMapping` and
`@PostMapping` annotations in `ui.controllers` and fails if a GET route is
missing from the sweep list. A new page cannot be added without being visited.
This follows the pattern established by `WebjarReferenceTest` and
`SchemaMigrationTest`.

### Phase 2 — journeys

- `InstallWizardIT` — against a separate empty database, since it is the one
  flow requiring a virgin schema
- `AuthoringJourneyIT` — login, create entry, publish, view on the public blog,
  edit, verify
- `MediaUploadIT`
- `CommentModerationIT`

### Test state

The application under test is a live Tomcat in a separate JVM, so Roller's
in-process caches (`CacheManager`, the weblog page cache) cannot be cleared from
the test side. Truncating the database mid-run would leave stale cached pages.
The per-test `TRUNCATE` used by the unit tests is therefore unavailable here.

Instead: seed once before Tomcat starts; each test creates uniquely-named
`it_<test>_<n>` entities and never deletes. Tests remain independent and
order-independent. The database is disposable.

### CI

A separate job from the unit build: `mvn verify -Pit` on JDK 21 with headless
Chrome. Screenshots and the browser-health report are published as artifacts on
failure.

## Risks

**Browser health capture may not work as designed.** If neither CDP nor the
Selenide proxy yields response status codes cleanly, the premise of catching
asset 404s weakens and the design needs revisiting. Spiked first, before
anything is built on it.

**Phase 1 requires a working login**, so the seed SQL must produce a password
hash matching Roller's configured encoder (see `RollerContext` for the
bcrypt/argon2 selection). If that proves fiddly, the fallback is to have the
sweep bootstrap its admin through the install wizard once.

## Out of scope

The root `Dockerfile` is broken: it clones upstream Roller from GitHub and
builds tag `roller-6.1.0` rather than local source, on JDK 17, into Tomcat 9.
It cannot produce a working image of this codebase. This design deliberately
does not depend on it, but `docker compose --profile full up` does not currently
work and should be fixed separately.
