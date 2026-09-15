# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Important Rules

- **Never commit or push unless explicitly asked.**
- **A dispatched agent may commit its own work. No agent may ever push.**
  A local commit is how a background agent *finishes* (undone by one
  `git reset`). A push is outward-facing (CI, other people; on a `v*.*.*`
  tag it publishes container images), so only the top-level session pushes,
  only when asked in so many words — never a subagent, reviewer, or agent
  that believes it is finishing up. Written down because a read-only agent
  once pushed to `master`, noticed only when `HEAD` had moved. An agent that
  believes the work needs pushing says so in its report and stops.
- **Work directly on `master`.** Solo-developer repo; no feature branch unless explicitly asked.
- **Ship work; don't let agents sit idle.** Dispatch the next piece rather
  than waiting for the current one to wrap up: reviews are read-only, so
  the next implementer runs alongside the previous reviewer. Prepare briefs
  ahead so a dispatch is instant. Nothing in flight is a planning failure, not a pause.
  **The one hard serialisation is the build.** Implementers share
  `app/target/`, so two concurrent `mvn -pl app test` runs clobber each
  other's output — never run two builds at once in one working tree;
  everything else (reviews, briefs, greps) overlaps freely.
  The busy check must be **bracketed and scoped to this repo**:

  ```bash
  pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || echo CLEAR
  ```

  An unbracketed `pgrep -f surefirebooter` matches the checking command
  itself, so a `while pgrep …; do sleep; done` loop waits on itself forever
  (the same self-match makes `pkill -f "spring-boot:run"` kill its own
  shell; use `[s]pring-boot` there too). An unscoped pattern matches other
  checkouts' builds. Never poll for a lock as a separate background step —
  inline the wait in the build command.
- **Parallelising implementers means git worktrees — and a worktree's base
  MUST be pinned and verified before you dispatch into it.** Each worktree
  has its own `app/target/` and git index, so builds need no serialising.
  But a worktree branched from a stale commit silently *reverts* everything
  else that changed in those files since the branch point — and the result
  compiles, passes tests and gates. This has happened here (two agents ~22
  commits behind master, files carrying a prior wave's charset,
  resource-handling, `volatile` and suppression fixes).

  Before dispatching into a worktree:

  ```bash
  git -C <worktree> rev-parse --short HEAD        # is this the base you meant?

  # Does the agent's scope overlap anything changed on master since that
  # base? LC_ALL=C on BOTH sides: a locale-sensitive sort makes comm print
  # "input is not in sorted order" and a still-empty (reassuring) result.
  git diff --name-only <base>..<branch> | LC_ALL=C sort > /tmp/a
  git diff --name-only <base>..master   | LC_ALL=C sort > /tmp/b
  LC_ALL=C comm -12 /tmp/a /tmp/b

  # Cross-check with git itself (no sorting needed, authoritative):
  git merge-tree --write-tree <branch> master >/dev/null && echo clean
  ```

  A non-empty overlap means a merge can revert real work. **Do not resolve
  a large overlap by cherry-picking or rebasing** — a bad resolution reverts
  a fix silently. Discard and redo on the correct base; the discarded
  agent's report (written to the main checkout, never inside the worktree)
  survives as a checklist. Corollary: **a worktree on a stale base may not
  even contain the quality gates**, so its `mvn verify` proves less than it
  looks (missing plugins in its `pom.xml` caught the case above).
- **All development is test-driven.** Write the failing test first, run it
  and watch it fail for the reason you expect, then write the minimum code
  that makes it pass. A test never seen to fail has not been shown to test
  anything — "wrote the code, then added a passing test" is not TDD; the
  order is what makes the test a specification.
- **Acceptance criteria live in the spec, not in the code review.** Work
  large enough for a spec under `docs/superpowers/specs/` states there what
  "done" means, concretely enough to write a test against; a plan's tasks
  derive their tests from it. A requirement no test could check is not yet
  a criterion — sharpen it or drop it.
- **Characterisation tests are the exception that proves the rule.** When
  extracting or refactoring existing behaviour, the test is written first
  and expected to pass immediately against the old code, proving the
  refactor changed nothing. Say so in the test's javadoc, or a later reader
  takes it for one written backwards.

- **A `catch` that logs and falls through is fine in a display path and a
  bug in a decision path; the difference is what the caller does with the
  answer.** ~170 in this tree, mostly correct (a sidebar that cannot load
  renders empty). The wrong ones share a shape:

  ```java
  private void myValidate(...) {
      try {
          if (weblogManager.getTemplateByName(weblog, bean.getName()) != null) {
              addError(model, "pagesForm.error.alreadyExists", ...);
          }
      } catch (WebloggerException ex) {
          log.error("Error checking page name uniqueness", ex);   // <-- no error added
      }
  }
  // caller:
  myValidate(bean, template, request, model);
  if (!hasErrors(model)) { ...save... }
  ```

  The caller reads an empty error list as "passed", so an unreachable store
  let the save through unchecked. **A check that could not run is not a
  check that passed.** `TemplateEditController` and `TemplatesController`
  both did this and now add an error; their tests pin it.

  When adding a `catch` that only logs, ask what the caller does next; if it
  proceeds as though nothing was wrong, fail closed. Two sweeps to repeat:
  methods named `*validate*`/`check*` with a log-only catch, and
  `boolean` methods that swallow — what matters is the value they fall
  through to (every one today falls to `false`, i.e. deny or degrade;
  `RollerHandlerInterceptor.preHandle` is fail-closed because a
  swallowed weblog lookup leaves `actionWeblog` null and `enforceSecurity`
  then refuses).

## Build and Development Commands

### Basic Build Commands
```bash
# Full build; tests need Docker (PostgreSQL)
mvn clean install

mvn -DskipTests=true install   # without tests

# Dev server (PostgreSQL + migrations + spring-boot:run with
# roller-boot-dev.properties) at http://localhost:8083, from the ROOT
# (server.servlet.context-path=/). No code may assume a context path;
# DEV_CONTEXT_PATH=/roller reproduces one (see Virtual hosts).
./roller dev

# Or run the packaged WAR (default port 8080; -Droller.custom.config must
# name a real DB config or bootstrap fails):
java -jar app/target/roller.war --server.port=8083 \
    -Droller.custom.config=app/target/test-classes/roller-boot-dev.properties
# Health check (works before the tier bootstraps). Actuator is on its own
# port, management.server.port=8090 (DispatcherServlet is *.rol only, so 8083
# has no "/" catch-all). Never expose 8090 beyond the deploy host.
# curl http://localhost:8090/actuator/health

# Database-only helpers
./roller db       # PostgreSQL + migrate, no app
./roller migrate  # apply pending migrations
./roller status   # show applied migrations
./roller stop     # stop dev DB (data kept)
./roller reset    # DESTROY dev DB volume and rebuild
```

### Frontend build

- Maven builds the editor's CodeMirror bundle: `frontend-maven-plugin`
  fetches a pinned Node `v24.21.0` into `app/frontend/node/`, then `npm ci`
  + esbuild at `generate-resources` (before every compile); never run `npm`
  by hand.
- Output `app/src/main/webapp/roller-ui/scripts/roller-editor.js`
  (git-ignored, regenerated) lives under `src/main/webapp`, not
  `target/classes/static`: with `DispatcherServlet` on `*.rol` only, neither
  classpath static resources nor `WEB-INF/classes` nesting can serve it; only
  that path both `maven-war-plugin` and `spring-boot:run` serve.
- `-DskipTests` skips the bundle's `npm test` (`test` phase, `skipTests`
  property), not the build.
- **After bumping `nodeVersion`, `rm -rf app/frontend/node` on any existing
  checkout.** The plugin unpacks the new tarball over the old one without
  clearing it, leaving npm's own `node_modules` mixed across versions;
  the symptom is `npm ci` dying with "Class extends value undefined is not
  a constructor". CI and the Docker build start empty and never see it.
- `mvn -pl app -DskipTests clean generate-resources`: cold ~8s, warm ~1.6s
  — negligible against `verify`.
- `docker build` starts from a fresh `maven` image (no `app/frontend/node/`
  or `node_modules/`) so always pays the cold cost and needs
  `nodejs.org`/`registry.npmjs.org` (docker_deployment.md).
- **Bundle size ruling**: `roller-editor.js` is ~575 KB minified / ~192 KB
  gzipped, served only to the two login-gated editors; accepted — dropping
  fenced-code highlighting (the bulk) costs a feature for bytes nobody else
  pays.

### Testing Commands
```bash
mvn test                        # all tests
mvn test -Dtest=TestClassName   # one class
mvn clean test && mvn jacoco:report -pl app  # coverage: app/target/site/jacoco/index.html
```

- Tests need Docker: `RollerTestBootstrap` (JUnit `LauncherSessionListener`)
  starts one PostgreSQL container per JVM, schema from the real
  `bin/db/migrations` chain.
- Fixtures: `TestUtils.setupX(...)`, removed in `@AfterEach`
  (`teardownWeblog`/`teardownUser` + `endSession(true)`) — nothing truncates
  tables.
- Render caches are per-JVM singletons: rendering tests call
  `CacheManager.clear()` in `@BeforeEach` (`RenderingTestSupport`).
- `TestUtils.weblogger()` is the real tier (one per JVM;
  `TestUtils.setupWeblogger()` via `SpringWebloggerProvider.standalone()`);
  hand it, or `MockWeblogger.create()`'s mock, to the class under test by
  constructor/`init`/field; no global, no `mockStatic`.
- `MockWeblogger.attached()` also attaches the mock's `PropertiesManager` to
  `WebloggerRuntimeConfig` for runtime-config reads (`RuntimeConfigAttachment`:
  try-with-resources form); both restore the previous attachment
  (DB-backed tests set the real tier).

### Coverage gates

- JaCoCo `check` runs at `verify` with floors in the parent `pom.xml`
  (`jacoco.line.minimum` / `jacoco.branch.minimum`, plus a PACKAGE rule for
  `ui.rendering.*`). Floors only ever move up; "raise" means raise where
  there is slack, not all three. Re-measured 2026-09-08: LINE 0.9142, BRANCH
  0.8458, PACKAGE velocity 0.8806 / servlets 0.8571 / rendering 0.9447;
  floors stand at **0.9100 / 0.8400 / 0.80**. Set a floor a few tenths
  under measured, never at it — the margin separates a ratchet from a
  tripwire an unrelated change sets off. The PACKAGE rule's binding package
  is whatever its `<includes>` names, not the lowest number in the report:
  `ui.rendering.filters` measures lower (0.8333) but is not included and at
  18 lines is too volatile (one line = 5.6 points); the real constraint is
  `servlets` at 0.8571 across 693 lines. New work's coverage is the diff
  gate's job.
  `cwebp` caveat: the same tree measures differently with and without
  `cwebp` on `PATH` (three WebP tests skip without it) — 2026-09-09,
  without: 0.9142 / 0.8458; with: 0.9158 / 0.8487. Floors are set against
  the **without** numbers on purpose — a floor raised from a CI-measured
  number can pass CI while failing a laptop that has no `cwebp`.
- Changed lines need ~90% coverage: `bin/check-diff-coverage.sh [base-ref]`
  (default `HEAD~1`; needs `pip install diff_cover` and a fresh
  `mvn -pl app jacoco:report`). CI enforces this on every push/PR. Measure
  it against the range you will actually push, not per commit — CI takes
  its base from `github.event.before`, so a batch is judged as one diff.
- **A large mechanical sweep will fail this gate for a reason that is not
  real, and that is expected.** Changing `log.debug("x " + y)` to
  `log.debug("x {}", y)` re-marks a long-uncovered error-path line as new;
  coverage has not dropped. The JCL→SLF4J migration (pushed at `586d0458b`,
  37 commits) measured 83% over 1,199 changed lines where the
  static-analysis wave scored 91–92%. Accept the one red run and say so; do
  not fix it — tests for error-path log statements are the assertion-free
  coverage theatre the static-analysis spec bans ("Policy: defensive
  branches and the diff-coverage gate"), and exempting a category of line
  buys a permanent hole for a one-off.
- **Three i18n ratchets guard the message bundle**, each answering a
  question the others cannot. `MessageKeyTest`: a JSP arm (every
  `<spring:message code>` resolves), a Java arm (every key in a
  `getText`/`addError`/`addMessage` literal exists), and an orphan arm
  matching on a word boundary, so `error` is not "used" because
  `error.upload` exists. `MessagePlaceholderContractTest` pins arity: a
  value's highest `{n}` must match what every call site passes, catching a
  message that renders a literal `{1}`. `MessageFormatRegressionTest` runs
  both apostrophe directions over every translation — a value with a
  placeholder may not carry a bare `'`, one without may not carry `''`. All
  three assert against `Set.of()`; a ratchet with a populated offender set
  is green and means nothing, so empty the set rather than adding to it.
- Browser ITs run in CI (`mvn verify -Pit`) — see `it-selenium/`, and CI
  below for *when*.

### Static-analysis gates

PMD, CPD and SpotBugs run at `verify` in the `app` module and fail the build
on **any** violation. Config: `config/pmd/ruleset.xml`,
`config/spotbugs/exclude.xml`; wiring in the parent `pluginManagement`,
executions in `app/pom.xml`. `bin/quality-report.sh` prints current counts
and sites; `bin/quality-report.sh <RuleName>` lists one rule's sites. It
derives the CPD token threshold from the pom, and `QualityGatePomTest` pins
that (it printed `CPD @200` against a gate of 110 for eighteen days).

The wave took the tree from 362 PMD / 134 SpotBugs / 4 CPD violations to
zero; the temporary `pmd.max.violations` / `spotbugs.max.violations`
ceilings and `maxAllowedViolations` wiring were deleted once it held there.
**The PMD count is measured against the pinned PMD version (7.27.0 since
2026-09-14; the wave was measured on 7.26.0), and the pin is load-bearing.** The maven-pmd-plugin's bundled 7.17 measured **307** on the
same source and ruleset against **362** under 7.26.0: `CloseResource` alone
goes 13 -> 42 as the detector improved, and five rules did not exist in 7.17
(`OverrideBothEqualsAndHashCodeOnComparable`, `LambdaCanBeMethodReference`,
`UseStandardCharsets`, `AvoidInstanceofChecksInCatchClause`,
`AvoidCatchingGenericException`). An unpinned plugin drifts the count
silently on a routine bump; the `pmd-core`/`pmd-java` `7.26.0` override in
the parent `pluginManagement` fixes it.

Zero tolerance is affordable only because the rule set is narrow: **a rule
is excluded only when violating it is systematically not a defect in this
architecture**, never because there are a lot of them.
`UnnecessaryConstructor` is the clearest case — JPA entities must declare a
no-arg constructor, so the rule is wrong here, not noisy. PMD's `codestyle`
category (7,997 violations of format opinion) is not used at all.

Six PMD rules and three SpotBugs families (469 of 603 raw SpotBugs findings)
are excluded, each with a reason comment in the config file.
`QualityGatePomTest` fails the build if an exclusion lacks a justification
comment or the excluded set differs from the list it names — a rule outside
`PERMITTED_PMD_EXCLUSIONS`, a `<Match>` block beyond the three families, or
a bug pattern outside `PERMITTED_SPOTBUGS_PATTERNS` — so widening either set
is a spec change, not an implementation decision.

The JCL→SLF4J migration is done (~797 call sites, all on `org.slf4j` with
parameterized `{}` logging; 6 `log.fatal` calls mapped to `log.error`).
SLF4J only formats when the level is enabled, so every
`if (log.isDebugEnabled()) { log.debug("x " + y); }` guard whose job was
gating concatenation was deleted as ceremony. `ProperLogger` came back
clean (167 → 0) and is active with no exclusion. `GuardLogStatement`
flagged **175** at activation: PMD treats any non-trivial argument (a
method call, a ternary) as unprovably cheap, so it fires on the idiomatic
`{}` fed by `entry.getId()` / `weblog.getHandle()`. Three sites were real
waste (an eagerly-built `StringBuilder`/`MessageFormat.format()` handed to
`log.info(String)`, two `stream().collect()` calls at startup) and were
fixed. The other 172 were first suppressed with
`@SuppressWarnings("PMD.GuardLogStatement")` on 74 class declarations;
overruled — a class-level suppression also silences future code, and 74
classes sharing one reason is a family, which belongs in the config file.
`GuardLogStatement` is excluded in `config/pmd/ruleset.xml` permanently:
with parameterized SLF4J, violating it is systematically not a defect. Full
accounting: the Follow-up section of
`docs/superpowers/specs/2026-08-18-static-analysis-quality-gates-design.md`.

SpotBugs 4.10.4 (2026-09-14) added two detectors that found 19 sites; both
were fixed in code rather than excluded: `IAOM_DO_NOT_INCREASE_METHOD_ACCESSIBILITY`
(sixteen `doGet`/`doPost`/`doRun` overrides declared `public` over a
`protected` parent, now `protected` — test callers sit in the same package)
and `USO_UNSAFE_*_METHOD_SYNCHRONIZATION` (three `synchronized` methods on
publicly reachable objects, now private lock objects). A tool bump that
adds detectors is expected to find things; fix them or justify a site-level
suppression, never widen `exclude.xml` for a version bump.

**On SLF4J's varargs form a `Throwable` must stay the LAST argument, and no
`{}` may consume it.** `log.error("x {}", a, e)` preserves the stack trace;
`log.error("x {} {}", a, e)` binds `e` to the second placeholder via
`String.valueOf(e)` and the trace is silently gone. JCL's `String`/`Object`
single-argument overloads (`log.error(ex)`, `log.warn("msg " + ex)`) were
the most common pre-existing bug the migration surfaced. There is no
compiler check; read the argument list.

CPD runs at **110 tokens**, lowered from 200 on 2026-08-22 after triage. The
old threshold missed real duplicates across a routing/parsing boundary at
roughly a hundred tokens each:

- `isLocale`, byte-for-byte identical in `WeblogRequest` (routing) and
  `WeblogRequestMapper` (parsing) — a divergence would have made the two
  halves read the same url as different requests. Now `LocaleSegment`.
- The `<rendition>` parser, copied inside `ThemeMetadataParser` at 193 tokens.

The triage at 110 found nine blocks; four were extracted, the rest carry
`CPD-OFF` with a stated reason — two (the day/month pagers) marked deferred
rather than excused. The threshold is part of the gate's spec:
`QualityGatePomTest` pins it, and changing it means updating the design doc
and that test, not just the pom. Below 110 the gate costs more than it
returns; that is not a claim that such duplication is fine.

**Editing `config/pmd/ruleset.xml` has two parse-time traps.** A literal
`{}` anywhere in the file — including inside a comment — breaks PMD's
ruleset merge, because the file is run through `MessageFormat`. A bare `--`
inside an XML comment is illegal XML, so the ASCII dash convention (fine in
`.java` and Markdown) cannot be used there; the same rule bites
`runtimeConfigDefs.xml`, where it fails silently.

One-off suppressions go at the call site (`@SuppressWarnings("PMD.Rule")`,
`@SuppressFBWarnings`, `// CPD-OFF`) **with a reason** that does more than
restate the rule name; the two config files are for whole families only. A
genuinely repeating pattern within one class may carry the suppression on
the class declaration — still "at the site". A pattern repeating across many
classes is a family and belongs in the config file (`GuardLogStatement`
above: 74 classes, one reason).

Proof the gate bites: seeding one violation per tool (an unused import for
PMD, an unchecked `String.getBytes()` for SpotBugs's `DM_DEFAULT_ENCODING`,
a 200+-token method copied into a second class for CPD) each turned
`mvn -pl app verify` into a `BUILD FAILURE` naming file, line and rule — see
`docs/superpowers/specs/2026-08-18-static-analysis-quality-gates-design.md`.
Cost: PMD+CPD+SpotBugs add ~10 seconds to a warm `verify` (inside the
30-second budget); a cold full `verify` runs in under 20 seconds.

### CI: three tiers, and nothing publishes on a push

`.github/workflows/main.yml`, split by cost:

- **Every push and PR**: `build-test` — unit suite + diff-coverage gate, ~3
  minutes. It **installs `cwebp`** (`webp` package) first, load-bearing
  because `CwebpEncoder` is feature-detected: without the binary
  `CwebpEncoderTest`, `MediaFileTest` and `MediaResourceServletRenderingTest`
  silently skip their WebP arms (until 2026-09-09 the shipped WebP path had
  no executing coverage); `ItCiWorkflowTest` pins the step. A new
  `assumeTrue` on a binary must make CI provide it in the same commit — an
  assumption that never holds is worse than a missing test.
- **Nightly (04:00 UTC), on a PR, or on demand**: `integration-test`, the
  browser ITs, ~16 minutes — off the push path so a re-runnable flake (the
  GalleryIT upload race) isn't per-commit red mail. Run `mvn verify -Pit`
  before a release.
- **CodeQL** (`codeql-analysis.yml`): weekly + `workflow_dispatch`.
- **Publishing happens only on a `v*.*.*` tag** (`release.yml`): pushes
  `ghcr.io/jakefearsd/roller:<version>` and
  `ghcr.io/jakefearsd/roller-caddy:<version>` (plus `:latest`,
  `:sha-<short>`), extracts the WAR from the `roller` image into a
  GitHub Release with the deploy bundle (`docker-compose.prod.yml`,
  `.env.example`, `deploy.sh`).
- `docker-compose.prod.yml` requires `IMAGE_VERSION` in `.env` (no floating
  default) so a deploy always names a tagged release.
- Local testing of an untagged tree is a `docker build` of
  `Dockerfile`/`deploy/caddy/Dockerfile` (docker_deployment.md);
  `deploy.sh` only pulls — `docker-compose.prod.yml` has no `build:` stanza.

**Known flake, mechanism NOT established: a 403 on `POST
/roller-ui/admin/createUser!save.rol`** in
`ErrorCasesIT.aDuplicateUserNameIsRefused` (via `BrowserHealth`), seen once
(parallel suite, 2026-08-19) on a commit that had just passed 125/125;
`ErrorCasesIT` passes 8/8 alone (`mvn verify -Pit -Dit.test=ErrorCasesIT`).
Two candidate stories, no evidence either way: Spring Security answers 403
for both a stale CSRF token and access-denied, and concurrent classes call
`loginAs`/`logout`, which invalidates the session. Deliberately unexplained
(the `ModDateHeaderUtil` precedent). **Recurred 2026-09-14** on the first
parallel run after the dependency wave (Boot 4.1.1 / Security 7.1.1): same
test, same POST, 136/137; `ErrorCasesIT` alone passed 8/8 and the next full
parallel run passed 137/137. The app log said nothing because every path
that answers 403 here (`CsrfFilter`, `ExceptionTranslationFilter`, the
interceptor's DENIED branches) logs only at DEBUG, so `start-app.sh` now
runs the IT app with those loggers at DEBUG and writes Roller's file log
per run to `it-selenium/target/it-work/roller-<run id>/roller.log` — read
that, not the stdout log, on the next occurrence. Then trace it, don't
rerun: it is the shape of a real regression. Capture what else was in
flight on the other three threads from the failsafe report timestamps
BEFORE running anything else — an isolated rerun overwrites
`it-selenium/target/failsafe-reports`, which is how the 2026-09-14 overlap
evidence was lost.

### The IT harness cleans up by identity, not by pidfile

Everything an IT run creates carries its id (`${it.run.id}` = build
timestamp + reserved HTTP port); the app JVM's command line adds
`-Droller.it.run=<id>` and `-Droller.it.owner=<pid>@<start time>` (owning
build). The pidfile scheme it replaced silently leaked an app JVM, a
chromedriver and a Docker volume per abnormal run
(`build-helper:reserve-network-port`'s free port meant leaks never collided).

Five mechanisms in `it-selenium/src/test/script/`:

- **`start-app.sh` kills what it started on every failure path** (`trap
  cleanup EXIT`; INT/TERM become exits). Its readiness timeout used to
  `exit 1` with the JVM running, skipping `post-integration-test` and so
  `app-stop`.
- **`sweep-stale.sh` runs first in `pre-integration-test`**, reaps what
  earlier runs left and **reports every pid and container it takes** —
  silence was the defect.
- **`supervise-run.sh` starts before `pg-start`, detaches, and outlives the
  build.** Failsafe protects cleanup from *test* failures only; an
  *infrastructure* failure in `pre-integration-test` (`pg-wait-ready`,
  `migrate.sh`, the seed, `app-start`) or a Ctrl-C skips
  `post-integration-test`. It kills the run's app, its chromedrivers
  (recorded while their JVM lived; unattributable after), and the
  container (`docker rm -f -v`, always `-v`).
- **Staleness is decided by the owning build, never by "an IT process
  exists"**: a concurrent `mvn verify -Pit` is legitimate and must survive
  another run's sweep.
- **The supervisor marks itself `-Droller.it.supervisor=`, not
  `-Droller.it.run=`.** A forked subshell (command substitution, pipeline
  halves) shows its *parent's* command line in `ps`; a supervisor in its
  own run would kill its own subshells (`ItHarnessLeakTest` pins it).

Per run: container `roller-it-postgres-<run id>` (no fixed-name 409 needing
`docker rm`); `pg-stop` passes `<removeVolumes>true</removeVolumes>` (the
default leaked an anonymous volume even on success: `postgres:16` declares
`VOLUME /var/lib/postgresql/data`); pidfiles, logs and work dirs, so a leaked
run's `app.log`/`app.pid` survive; `it-work/app-latest.log` symlinks the
newest.

`RollerPostgresContainer` (the unit suite's shared, never-stopped container)
adds a JVM shutdown hook behind Ryuk, one process that can be missing.

Tests (fast suite, not `-Pit`): `ItHarnessLeakTest` (real scripts, fake
processes), `ItHarnessPomTest` (`removeVolumes`, per-run names, plugin order
putting `app-stop` before `pg-stop`), `RollerPostgresContainerTest`.

### Database

Roller is **PostgreSQL-only** — one engine for dev, test and production
(Derby and the Velocity/Texen DDL generator are gone).

- **Development**: PostgreSQL 16 via `docker-compose.yml` (named volume; persists)
- **Testing**: PostgreSQL 16 via Testcontainers, schema from the migration chain
- **JNDI Name**: `jdbc/rollerdb`

#### Schema changes

**Every schema-changing commit MUST add a numbered migration** under
`bin/db/migrations/`: next `V<NNN>__description.sql`, idempotent DDL; never
edit a migration already applied anywhere but local dev — fix with a
follow-up. Convention: `bin/db/migrations/README.md`; `SchemaMigrationTest`
enforces discoverability, shape and idempotency. Three appliers read the same
files: `bin/db/migrate.sh` (deploy), `DatabaseInstaller` (install wizard),
and the test harness.

## Architecture Overview

Roller is a multi-user blog server built with:
- **Runtime**: Spring Boot 4.1 executable WAR (`java -jar app/target/roller.war`
  or an external servlet container), embedded Tomcat 11, Java 25.
  Servlets/filters are Java config (`ServletRegistrationConfig`, transcribed
  from the retired `web.xml`); no `web.xml` in the artifact.
- **Web Framework**: Spring MVC, `@Controller` classes, `*.rol` mappings
- **Security**: Spring Security, role-based access control, built-in CSRF
- **Persistence**: JPA/EclipseLink on PostgreSQL
- **Templating**: Velocity for blog rendering, JSP/JSTL for admin UI
- **Entry content**: Markdown, always; no per-entry format flag or column
  (V009 dropped `content_type`/`content_src`). commonmark-java runs in
  `WeblogEntry.render()` **after** shortcode expansion, before sanitization
  (load-bearing: markdown-first escapes the quotes in `[gallery dir="x"]`).
  Raw HTML passes through commonmark by design; `HTMLSanitizer` (OWASP
  policy) is the only boundary.
- **Search**: Apache Lucene 10, embedded, index on local disk
- **DI Container**: one Spring container, **no static service locator**
  (`WebloggerFactory` deleted 2026-08-22; spec
  `docs/superpowers/specs/2026-08-22-retire-static-service-locator-design.md`).
  Beans: `WebloggerBeanConfig` (`@Configuration @Lazy`,
  `org.apache.roller.weblogger.business.jpa`), built lazily by
  `SpringWebloggerProvider.bootstrap()` (`@Component` implementing
  `WebloggerProvider`: `isBootstrapped()`/`bootstrap()`/`getWeblogger()`)
  after `WebloggerStartup.prepare()`, from `RollerLifecycle` (phase 0) or
  the install wizard. **How a class reaches the tier depends only on how it
  is constructed:**
  - Container-managed (controllers, the nine rendering/ajax servlets, filters,
    interceptors, business beans): `@Lazy Weblogger` in the constructor. The
    `@Lazy` is load-bearing (beans are created at context refresh, before
    `prepare()`); `ContextRefreshDoesNotBootstrapTest` fails the build on a
    forgotten one.
  - `WebloggerProvider` only where "is the tier up?" is a runtime question:
    `BootstrapFilter`, `PersistenceSessionFilter`, `ControlPlaneHostFilter`,
    `InitFilter`, `RequestMappingFilter`/`WeblogRequestMapper`,
    `RollerHandlerInterceptor`, `RollerUserDetailsService`,
    `ApiTokenAuthFilter`, `RollerSession`.
  - Models: `weblogger` (and `urlStrategy`) from `initData` (`ModelLoader`
    requires it). Pagers, `*Request` objects, wrappers, shortcode
    handlers, theme subclasses, background tasks: constructor / `init`.
    Velocity resource loaders: the engine's application attribute, set by
    `RollerVelocity.initialize(servletContext, weblogger)`.
  - **Entities under `pojos/` are data plus invariants and import no
    business-tier type.** URLs: `URLStrategy` (JSPs via `BaseController`'s
    `urls` helper, `AdminUrls`; themes via the wrappers, which hold the
    facade). Rendering: `EntryRenderer` (`Weblogger.getEntryRenderer()`).
    Queries/identity/authorisation: managers and wrappers.
  - `StaticServiceLocatorTest` pins this as a source scan: nothing names
    `WebloggerFactory`; no main-source `static` field has a business-tier
    type except `WebloggerRuntimeConfig`'s attached `PropertiesManager`
    (until the config wave retires it) and `RollerVelocity`'s engine; no
    entity references one.
  - **JSP scriptlets are callers** (`footer.jsp`/`login-redirect.jsp` use
    `WebApplicationContextUtils`); only a clean build's `jspc-validate`
    catches a broken one.
  - **An `.orm.xml` `<transient>` row must be deleted with the getter it
    names**, or EclipseLink's validation fails bootstrap.

### Core Package Structure
```
org.apache.roller.weblogger.
├── boot/            # Boot entrypoint, Java config (servlets, security, MVC)
├── business/        # Service layer
│   ├── jpa/
│   ├── plugins/     # Content plugins
│   ├── themes/
│   └── search/      # Lucene
├── pojos/           # Domain entities
├── ui/controllers/  # Spring MVC controllers
│   ├── admin/
│   ├── core/        # Login, profile
│   └── editor/
└── util/
```

### Key Architecture Patterns

**Service Layer Pattern**: the `Weblogger` interface is the facade over every manager:
```java
getUserManager() / getWeblogManager() / getWeblogEntryManager() / getThemeManager() / getIndexManager() / ...
```

**Manager Pattern**: business logic lives in `UserManager`, `WeblogManager`,
`WeblogEntryManager`, `ThemeManager`, `IndexManager`, `MediaFileManager`.

### Security Architecture
- **Authentication**: database only (`AuthMethod`; LDAP/OpenID/container-managed
  removed); an unsupported `authentication.method` fails loudly at startup
  rather than silently behaving like `db`.
- **Authorization**: `GlobalPermission`, `WeblogPermission`, `ObjectPermission`
- **Custom Interceptor**: `RollerHandlerInterceptor` enforces access controls
- **CSRF Protection**: Spring Security built-in, automatic on all POST forms

### Theme System
- **Shared themes** live under `/themes/`: `portfolio` (dark photo grid),
  `travel` (light travel guide cards) and `journal` (the default: `qj-*`
  vocabulary, light+dark, self-hosted IBM Plex — `ibm__plex-serif` joins
  the Sans/Mono webjars in `app/pom.xml`; the CSS `url()`s carry the webjar
  version, so a bump edits three stylesheets and `WebjarReferenceTest`). A theme
  with its own webfont must add `font-src 'self'` to its CSP on top of
  `CSP_STANDARD`, or the browser refuses every `@font-face`;
  `JournalThemeRenderingTest` pins the string byte-for-byte.
  `ThemeCspCoverageTest` enforces it for any theme CSS referencing
  `@font-face`, checking `/webjars/` fonts via
  `getResource("META-INF/resources/webjars/...")`, not the filesystem (Plex
  is classpath-served, not under `webapp/`). All three ship a `_page`
  template (see Themes); `journal`'s is covered for the `[contact]` slot and
  the draft-404 case.
- **Retired**: `basic`, `fauxcoly`, `gaurav` — directories deleted;
  `V018__retire_legacy_themes.sql` moves any weblog still on one to `journal`
  (idempotent; a custom-theme weblog, `'custom'` in `editortheme`, is
  untouched). The fixture theme (`TestUtils`, seeded IT weblog) is
  `journal`; `ThemeMatrixIT` lists `journal`/`portfolio`/`travel`. Never
  reintroduce a theme directory or a migration writing `editortheme` back
  to one of these ids.
- `frontpage` (the multi-weblog aggregator theme) uses the `fd-*` front-door
  design, same `font-src` addition and self-hosted Plex as `journal`.
  `_blogprofile.vm` and `_blogs.vm` are deleted with their `theme.xml`
  registrations; directory cards link to the weblog.
- **Entry titles are stored HTML-escaped; page titles are stored raw.**
  `EntryBean.copyTo` runs `StringEscapeUtils.escapeHtml4` on the title once
  at save, so `WeblogEntry.getTitle()` is already escaped and every theme
  must emit `$entry.title` bare — `$utils.escapeHTML($entry.title)`
  double-encodes to `&amp;amp;`. `PageBean.copyTo` copies `title` through
  unescaped, so `WeblogPage.getTitle()` is raw and every page template must
  call `$utils.escapeHTML($model.page.title)` (or `#showPageTitle`) itself,
  as all three themes' `page.vm` do — skipping it is stored XSS once a title
  carries `<script>`. `EditorJspEscapingTest` pins the admin-JSP side:
  every author-controlled EL expression, `entry.title` included, goes through
  `fn:escapeXml` (JSP fields get no save-time escaping).
- **The `bean.*` form expressions are the dangerous twins of the display
  expressions**: `value="${bean.name}"`, `<textarea>${bean.text}</...>`,
  inline-JS `'${bean.link}'` are stored/reflected XSS. Two traps:
  `EntryBean.copyFrom` runs `StringEscapeUtils.unescapeHtml4` on the title
  (the author edits what they typed), so `bean.title` is raw though
  `entry.title` is stored escaped; and a wrapper that escapes does not make
  the pojo safe — `Weblog.getName()` is raw, `WeblogWrapper.getName()`
  escapes on read, `WeblogConfig.jsp` reads the bean. Guard a field by adding
  it to `EditorJspEscapingTest`'s `AUTHOR_CONTROLLED` list in
  **both** spellings. `<spring:message arguments="...">` does **not** escape
  (`defaultHtmlEscape` is unset), so an argument is an injection point too.
- Custom themes: per-weblog (see Themes); template types `.vm`, stylesheets,
  resources; hot reload in dev.

### Media Pipeline (Stage 2 Wave 1)
- Rendition ladder (480/960/1600/2400px, JPEG/PNG only, never upscaled) via
  `RenditionSupport`; WebP siblings when `cwebp` is present (feature-detected
  `CwebpEncoder`; the prod Docker image installs it, dev works without).
  `MediaResourceServlet` serves them via `?w=<width>` + `Accept: image/webp`
  negotiation. Renditions are excluded from upload quotas.
- Upload extracts EXIF (`ExifSupport`) and a BlurHash placeholder onto
  `MediaFile`; `uploads.exif.stripGps` (default on) nulls GPS coordinates
  before persist. The original file on disk is never modified.
- Backfill for pre-pipeline uploads: Maintenance page →
  `MediaFileManager.regenerateRenditions(weblog)`.
- Crop (Stage 2 Wave 2): `MediaFileManager.cropMediaFile` destructively
  re-encodes the original (orientation composed first, atomic temp+move
  write) and regenerates the whole ladder + thumbnail + blurhash; stored
  EXIF fields are kept. Focal point (`MediaFile.focalX/Y`,
  set on MediaFileEdit) emits `object-position` via `#showResponsiveImage`
  only — never into entry content.
- Private directories (`MediaFileDirectory.isPrivate()`, toggled on
  MediaFileView): 404 on the base media path except for logged-in editors of
  the owning weblog, excluded from sitemaps, refused by `[gallery]`. A pure
  visibility flag with no bypass of any kind. The share-link feature that
  once punched a tokened hole through it (`ShareController`,
  `roller_share_link`) is gone and not coming back.
- **Alt text (W4): `MediaFile.altText`.** Before W4 every alt fell back to
  `MediaFile.getName()`, the filename. The chain:
  - `ImageShortcode`: an `alt` attribute **present** on the shortcode wins
    verbatim, *including empty* (`[image id=".." alt=""]` means decorative).
    Only an **absent** attribute falls to `altText`, then the filename.
  - `GalleryMarkup`: `altText` → filename (no per-image override attribute).
  - At the `altText` link, **blank counts as absent** at both sites — an
    author who clears the edit field did not declare the image decorative,
    and the field cannot express that. Deliberately unlike the
    shortcode-attribute link.
  - `firstNonBlank` returns `""`, never null (null once rendered a literal
    `alt="null"` in `GalleryMarkup`).
  - **The filename stays the last fallback rather than `alt=""`**: empty alt
    asserts "decorative", wrong for a photograph, and would hide undescribed
    images from the marker.
- **`MediaFileView.jsp` renders the "no alt text" marker from TWO `c:forEach`
  loops**: `childFiles` (primary — the unpaged folder-browse view an author
  sees on every visit) and `pager.items` (`MediaFileViewController` sets
  `pager` only in `mediaFileView!search.rol`, so only after a search). Its
  gate uses `fn:trim` so whitespace-only alt text counts as missing,
  matching the renderer's `isNotBlank`, not EL's `empty`.
- **Alt text is deliberately absent from the upload form**: it is per-image;
  one shared box across a thirty-file batch writes thirty wrong descriptions
  the marker then reports as done.
- **`#showResponsiveImage`'s `$alt` stays caller-supplied.** Theme callers
  pass `$entry.title` for a featured image — right in a card context, not
  the file's own description. Do not "unify" it.
- **Bulk upload (W4) was a form change only.** `MediaFileAddController.save`
  always bound `MultipartFile[]` and looped; the five-file ceiling was five
  `<input type="file">` elements. Now one `multiple` input plus a drop zone;
  `spring.servlet.multipart.max-request-size` is 1GB. The add form's inert
  Name field went too (overwritten by the uploaded filename right after
  `bean.copyTo`).
  **A batch is not a transaction**: `createMediaFile` reports quota and
  forbidden-extension refusals per file *without throwing*, so the
  controller snapshots `RollerMessages.getErrorCount()` around each call,
  and a partly-failed batch shows both what landed and what did not.
- **`MediaFile.sharedForGallery` / `roller_mediafile.is_public` are gone** (`V024`).
- `EntryAddWithMediaFileController` (entry from selected files) seeds the
  draft with `[image id=".."]`, not hand-built `<img>` markup, so alt text
  and the rendition ladder arrive through the normal path.

### SEO (Stage 2 Wave 1)
- Per-entry SEO fields on `WeblogEntry` (metaTitle, searchDescription,
  canonicalUrl, noindex, featuredImageId, ogImageId), edited in the entry
  editor's "SEO & Social Sharing" card with featured/social image pickers.
- `#showSeoHead` (`WEB-INF/velocity/weblog.vm`, called from every bundled
  theme head) emits meta description, canonical, robots noindex, Open
  Graph/Twitter card and JSON-LD; `#showResponsiveImage` is the theme-side
  `<picture>`/srcset emitter.
- `SeoController` serves `/robots.txt`, `/sitemap.xml` (index) and
  `/sitemap-<handle>.xml` (via `*.xml`; a middle-wildcard servlet pattern is
  illegal).

### Travel (Stage 2 Wave 3)
- Three shortcodes in `business/shortcodes`, registered in
  `ShortcodeExpander.DEFAULT` like `[image]`/`[gallery]`: `[map]` with
  `[pin lat lng label]` children (or `auto="<dir>"` mapping a directory's
  GPS-bearing photos, same private-directory refusal as `[gallery]`), `[faq]`
  with `[q]`/`[a]` pairs, and `[cta href label note]` (absolute http(s)
  only, UTM-tagged).
- `MapPins.parse` / `FaqBlocks.parse` are the single source of truth for both
  the shortcode renderers and the JSON-LD head emission, so map and itinerary
  cannot drift.
- Leaflet 1.9.4 (webjar, self-hosted) ships via `#showMapAssets`, the map
  twin of `#showGalleryAssets`; OSM tiles, no API key. Leaflet paints aborted
  tiles with a `data:` GIF, so every theme head's CSP carries
  `img-src * data:`, pinned byte-for-byte by
  `MapAssetsRenderingTest`/`PortfolioThemeRenderingTest`/`TravelThemeRenderingTest`.
- Per-entry structured-data type (`WeblogEntry.jsonLdType` + geo/event
  columns, V008): `EntryJsonLd` emits TouristAttraction/TouristTrip/Event/
  FAQPage as a SECOND `ld+json` block; the BlogPosting block is always
  emitted unchanged, so entries keep author/date/headline.

### Database Schema
Key domain entities: `Weblog` (blog instances, settings), `WeblogEntry`
(posts, content, publishing status), `User` (accounts, roles, permissions),
`WeblogCategory`, `MediaFile` (attachments, media), `WeblogTemplate` (custom
templates).

### Search Implementation
- **Engine**: Apache Lucene, background indexing; asynchronous
  add/remove/rebuild operations
- **Scope**: full-text across entries with category and locale filtering
- **Index Location**: configurable work directory

## Module Organization

- **`app/`** - The web application (executable WAR)
- **`bin/db/`** - Schema migrations and the migrate/install scripts
- **`deploy/`** - Production deploy script and Caddy/backup config for
  `docker-compose.prod.yml` (see `docker_deployment.md`)
- **`it-selenium/`** - Browser integration tests (Selenium, `mvn verify -Pit`
  against the packaged executable WAR; see Coverage gates above)

## Configuration Files

### Key Configuration Locations
- **Boot Config**: `app/src/main/resources/application.properties` (server
  port/context-path, filter ordering, actuator exposure)
- **Dev Properties**: `app/src/test/resources/roller-boot-dev.properties`
  (loaded via `-Droller.custom.config` by `./roller dev` and NetBeans
  run/debug actions)
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
- **Production**: containerized end-to-end and **image-only** — the deploy
  host holds `docker-compose.prod.yml` and `.env`, nothing else. Two images
  per release tag: `ghcr.io/jakefearsd/roller` (WAR, themes, migrations,
  `provision.sh`, `analytics-views.sh`, `umami-views.sql`, `migrate.sh`, backup
  scripts, a PostgreSQL client) and `ghcr.io/jakefearsd/roller-caddy`
  (Caddy, Caddyfile baked in). A one-shot `provision` service creates the
  umami and listmonk databases, applies the migration chain, and grants
  `grafana_ro`; `app`, `umami` and `listmonk` declare
  `depends_on: { provision: { condition: service_completed_successfully } }`,
  so ordering is compose's job, not a bash script's.
  `analytics_traffic` is installed by a separate one-shot, `analytics-views`
  (`analytics-views.sh`), after `umami` starts — see Analytics for why it
  cannot live in `provision`. `deploy/deploy.sh` is just pull/up/wait.
  **Nothing may be bind-mounted from a checkout** — `ProductionComposeTest`
  fails the build if a bind mount, a `build:` stanza, or a non-loopback
  published port other than 80/443 reappears. Runbook:
  `docker_deployment.md`.

## Themes
- A weblog runs either a **shared** theme (id from `themes/<id>/theme.xml`) or
  `WeblogTheme.CUSTOM`. Switching to custom **imports** the shared theme's
  templates as the weblog's own rows and is one-way (it stops tracking the
  shared theme). `ThemeIT` therefore works on weblogs it creates itself;
  never switch the seeded IT weblog.
- `ThemeEdit.jsp` hides its Save buttons until its JS sees a change; pick
  the theme (or the radio) first to reveal the right one.
- A theme switch reaches readers via `saveWeblog` bumping `lastModified`, not
  `CacheManager.invalidate` — see Templates on `WeblogPageCache`.
- **`themes.customtheme.allowed` (default `false`) gates only two of the
  Design tab's three items.** `themeEdit` (shared-theme selection, safe and
  reversible) is ungated; only `stylesheetEdit` and `templates` carry
  `enabledProperty="themes.customtheme.allowed"` in `editor-menu.xml` (the
  whole `tabbedmenu.design` group used to). `MainMenu.jsp` splits its theme
  button the same way: shared-theme link unconditional, custom-theme link
  behind the flag.
- **`themes.customtheme.allowed` is enforced in `ThemeEditController`, not
  just the menu** — a hidden menu entry stops nobody posting to
  `themeEdit!save.rol` directly. Only the `WeblogTheme.CUSTOM` branch of
  `ThemeEditController.save` checks it; the shared-theme branch never has. A
  weblog already on a custom theme is grandfathered (disabling the option
  must not strand it).
- `journal`, `travel` and `portfolio` each ship a `_page` template
  (`themes/<id>/page.vm`); a `WeblogPage` reaches it via the
  `StaticThemeTemplate` fallback, so a static page renders in the theme's
  own chrome (travel's `tg-header`, portfolio's dark frame), not the fallback
  template's bare `<h1>`. `TravelThemeRenderingTest`/
  `PortfolioThemeRenderingTest` pin this: a page carrying `[contact]` must
  render through the theme's header/prose classes and ship the audience
  assets in the head, with an `assertFalse` on the fallback `<h1>`.

## Configuration scope
- **Runtime** (`runtimeConfigDefs.xml` → `roller_properties` → Admin
  Settings): `WebloggerRuntimeConfig`, DB row first, else `WebloggerConfig`.
  Hot.
- **Startup** (`roller.properties` / `roller-custom.properties`): read once
  via `WebloggerConfig`; needs a restart.
- **Per-weblog** (`Weblog` columns, Weblog Settings) and **per-entry**.
- **Environment** (`ROLLER_*`): highest precedence
  (`WebloggerConfig.applyEnvironmentOverrides`): strip `ROLLER_`, lowercase,
  `_` → `.`. A case-insensitive match on an existing key writes to its
  spelling (`ROLLER_DATABASE_JDBC_DRIVERCLASS` →
  `database.jdbc.driverClass`); an unmatched name is used as derived —
  required, since `mail.port` has no entry in `roller.properties` and
  `uploads.dir` is commented out. Production is configured this way.

**Promoting a startup property to runtime** (add a `<property-def>`, read via
`WebloggerRuntimeConfig`) has three traps, pinned by
`PromotedRuntimePropertyTest`:
1. The defaults in both files must match.
2. The DB row wins once it exists, so seeding must take the *startup* value
   (`JPAPropertiesManagerImpl.initialValueFor`) or an upgrade discards the
   deployer's value.
3. The call site must genuinely re-read it: not a `static final`
   (`WeblogEntry`'s anchor separator was one) or a value latched in `init()`.

Promoted: `groupblogging.enabled`, `user.hideUserNames`,
`weblogentry.title.useUnderscoreSeparator` (`comment.throttle.enabled` went
with comments, W1). Throttle *sizing* (threshold/interval/maxentries)
sizes a fixed cache and stays startup-scoped; only the switch is hot.

**Deliberately NOT promoted** (decide first):
- `weblogAdminsUntrusted` — would put "disable HTML sanitization" on a
  form. (`passwds.encryption.enabled` is gone entirely; see Passwords.)
- `rememberme.enabled`, `themes.reload.mode`, `users.firstUserAdmin` —
  structurally boot-scoped.
- `search.enabled` — gates whether a Lucene index is built.

## Passwords
- **Password encryption cannot be configured or turned off.** Gone, all
  four paths that put plaintext in `roller_user.passphrase`:
  `passwds.encryption.enabled=false` (`DelegatingPasswordEncoder` encoding
  id `noop`), the unconditional `noop` encoder (`{noop}` rows authenticated
  anyway), `lazyUpgradeFrom=plaintext` (null-prefix no-op encoder:
  unprefixed strings authenticated), and the `enabled=false` lines in
  `roller-boot-dev.properties` / `roller-custom.properties`. Only
  `passwds.encryption.algorithm` (bcrypt/pbkdf2/scrypt/argon2) remains. An
  explicit `passwds.encryption.enabled` — file or
  `ROLLER_PASSWDS_ENCRYPTION_ENABLED` — **throws at startup**, like an
  unsupported `authentication.method`. A `{noop}` row is refused outright.
  `PasswordEncodingTest` pins it.
- **`TestUtils.setupUser` stores a precomputed `{bcrypt}` constant**
  (`TestUtils.TEST_PASSWORD` / `TEST_PASSWORD_HASH`), not a live `encode()`
  — bcrypt is slow, ~106 call sites (the bare `"password"` it once stored
  never authenticated).
- **The dev admin credential is `.roller-dev-secret`** — git-ignored,
  generated (`umask 077`) on the first `./roller db|dev|reset`, printed once.
  `bin/db/seed-dev-data.sql` applies it via pgcrypto inside Postgres; **not**
  under `bin/db/migrations/`, so it never reaches production. Source of
  truth: the next seed reverts web-UI password changes.
- **The seed's `ON CONFLICT` guard must stay a `CASE`.** `crypt()` raises
  `ERROR: invalid salt` on an unusable salt and PostgreSQL does not
  short-circuit `OR`, so an `OR` chain aborts on exactly the
  `{noop}`/truncated row it repairs. `DevSeedTest` runs the *shipped file*
  over every row shape.
- **`./roller token`** mints an API token for that admin via
  `roller-api auth login --password-stdin`. In `./roller`, never generate
  the secret with `tr -dc … </dev/urandom | head -c N`: under
  `set -o pipefail` `head` exiting early SIGPIPEs `tr` and the script dies
  silently at 141.

Browser tests permute global runtime properties via `RollerIT.setGlobalFlag`
(`setGlobalFlags` for several in one save), which drives the real Admin
Settings page, returning the old value. One shared instance: every caller
must restore in a `finally`.

### Permutation coverage in the browser suite
Four classes carry the configuration matrix:

- `ThemeMatrixIT` — every bundled theme rendering one entry carrying
  `[image]`/`[gallery]`/`[map]`/`[faq]`, on the home page and the permalink
  (different templates). One looping test, since the fixture costs ~9s.
  `frontpage` is excluded — it renders through `$site`, which exists only
  for the weblog named by `site.frontpage.weblog.handle`.
- `WeblogConfigMatrixIT` — per-weblog settings: locale, `entryDisplayCount`,
  and `active` (which also withdraws the weblog from the sitemap index). Each
  test owns its weblog and touches no global state.
- `GlobalConfigMatrixIT` — three tests that mutate site-wide state: the
  feature-refusal switch (uploads/weblog-creation off, batched),
  `groupblogging.enabled`'s own refusal (a user who already owns a weblog is
  refused a second one — its own test, so the assertion cannot pass for the
  wrong reason), and the entry-URL word-separator. One of five classes that
  call `setGlobalFlag`; "Browser ITs run class-parallel" lists them.
- `ScheduledEntryIT` — a future-dated entry is withheld from pages, its Atom
  feed, and the sitemap.

Comment-related tests and their `postCommentDirectly`/`approveComment`
helpers were deleted with the comment subsystem in W1; the invite/accept
ceremony (`invite.rol`) went in W2 — `MembersController.grant()` adds a
collaborator directly and has no `groupblogging.enabled` check of its own
(only the menu entry is gated).

No reachable browser coverage, documented rather than silently skipped:
- `user.hideUserNames` — every bundled theme and feed uses
  `$entry.creator.screenName`, never `.userName`, so the flag changes nothing
  in shipped output. (Per-weblog `analyticsCode` was uncoverable too, gated
  on `weblogAdminsUntrusted` being off; W2 deleted it — see Analytics.)
- `ScheduledEntriesTask` promoting a scheduled entry: an entry is only
  `SCHEDULED` when its pubtime is >1 minute out and the task cadence is whole
  minutes, so observing it costs 1-3 minutes with real variance.

### Browser ITs run class-parallel

`it-selenium/src/test/resources/junit-platform.properties` runs test
**classes** concurrently (fixed parallelism 4) and **methods** on one thread.
Re-measured 2026-09-09 across 35 classes: 412s of test time in 3m18s of
module wall clock. The profile is flat — `ThemeIT` at 27.2s is the slowest,
`VirtualHostIT` 20.4s, nothing else over 25s. Performance numbers in this
file go stale; re-measure before quoting one.

Methods stay serial on purpose: an IT class is a narrative (create a weblog,
edit it, publish, assert the rendered page) whose methods share fixtures
built in `@BeforeAll`; the parallelism worth having is across classes.

Two resource locks, whose failure modes both look like something other than
a race:

- **`RollerIT.GLOBAL_CONFIG`** — held **write** by the five classes that
  call `setGlobalFlag`: `GlobalConfigMatrixIT`, `ThemeIT`
  (`CUSTOM_THEMES_ALLOWED`), `ThemeMatrixIT` (`uploads.enabled`), `ApiIT`,
  `VirtualHostIT`. One shared app instance holds one set of runtime
  properties, so two overlapping would each see the other's flag. **A
  sixth `setGlobalFlag` class must take this lock** — nothing enforces it,
  and the symptom is a wrong-looking assertion, not a concurrency error.
  Held **read** by `RouteSweepIT`, which visits every admin route and so is
  exposed to every site-wide flag (on a `/roller`-prefix run with
  `groupblogging.enabled` off, `createWeblog.rol` returned a healthy 200
  with no form — the `categoryEdit.rol` failure mode the sweep exists to
  catch — because `CreateWeblogController` answers `.GenericError` to an
  admin who already owns a weblog), and by the media classes, which need
  `uploads.enabled` true (`GlobalConfigMatrixIT` sets
  `uploads.enabled=false`; with uploads off the media page renders no
  buttons, and the failure reads
  `Element not found {button[formaction$='entryAddWithMediaFile.rol']}`).
- **`RollerIT.SHARED_MEDIA`** — held **write** by the four classes that
  upload, crop or delete media on the shared `WEBLOG_HANDLE` (`GalleryIT`,
  `MediaCropIT`, `MediaBulkUploadIT`, `EditorSeoIT`); **read** by
  `ErrorCasesIT`, which only browses the media page.

The `GLOBAL_CONFIG` read modes preserve the speed: readers exclude the
mutators, not each other.

### BrowserHealth: three checks, and the third watches the watcher

Every check reports by finding something wrong in what it recorded, so
**every one passes vacuously when nothing is recorded**: a recorder that has
stopped seeing traffic turns all 126 tests green at once. The listeners ride
a CDP binding pinned to one Chrome major (`selenium-devtools-v153`); Chrome
ships a new major every few weeks, and Selenium answers a mismatch by
silently falling back to its nearest binding and logging a line nobody reads.

- `blindnessReport` closes it: an http(s) page with zero recorded responses
  is a broken monitor, not a clean page, and fails saying so. The
  discriminator must come from outside CDP or it is circular — the `page`
  field is itself set by a CDP event, so a blind recorder also believes it
  is on `about:blank`; `getCurrentUrl()` goes over the WebDriver protocol,
  and the two disagreeing is the signal. A test that never navigates stays
  on `about:blank` and is correctly exempt. Proof: with the
  `Network.responseReceived` listener disabled, 29 of `RouteSweepIT`'s 31
  tests fail; before the check, all 31 passed.
- **Keep `selenium-devtools-vNNN` at the newest version the pinned Selenium
  ships.** It is pinned explicitly in `it-selenium/pom.xml`, not taken
  transitively, so a Selenium bump that drops that version breaks the build
  rather than degrading at runtime (4.49.0 drops v150, which 4.47.0
  shipped).
- `assertNoBrokenResources` catches any sub-resource that came back
  4xx/5xx.
- `assertNoFailedRequests` catches requests that produced no response at
  all, closing the first's blind spot: a stylesheet whose URL 404s gets an
  HTML error page, and Chrome, refusing the wrong content type, *aborts* the
  load — no `Network.responseReceived` is emitted, so a theme whose CSS had
  gone missing rendered unstyled and passed. Webfonts refused by the page's
  own CSP arrive the same way. The discriminator is what may legitimately be
  cancelled: page script starts `Image`/`XHR`/`Fetch` and may abandon them
  (Leaflet cancels ~48 tiles per map render; the live preview coalesces
  in-flight requests as the author types), and a `Document` navigation is
  cancelled by navigating again. A `Stylesheet`, `Script` or `Font` is
  declared by the document and nothing cancels it, so an abort there means
  the browser refused it. A **blocked** request is never excused, whatever
  its type.

## Templates
- Add/edit/remove live in `TemplatesController` and `TemplateEditController`;
  both resolve client ids through `BaseController.lookupTemplate` (pinned by
  their `*ControllerTest`s).
- A CUSTOM template gets `link = name` and is served publicly at
  `/<handle>/page/<link>` — including on a weblog running a *shared* theme,
  via `WeblogSharedTheme.getTemplateByLink`'s fallback to the weblog's own
  templates. `TemplateIT` asserts that end to end.
- **Render-cache expiry.** `saveTemplate`/`removeTemplate` bump
  `weblog.lastModified`, the only thing that expires a rendered page:
  `WeblogPageCache` and `WeblogFeedCache` both pass
  `constructCache(null, ...)`, register no CacheHandler, and are never reached
  by `CacheManager.invalidate(...)` — they expire only lazily against
  `weblog.lastModified`. `SiteWideCache` is the sole eager render cache: it
  registers itself (`constructCache(this, ...)`) and `CacheManager` drops it
  wholesale. `RenderCacheHandlerRegistrationTest` pins the split. The two lazy
  caches share `LazyExpiringRenderCache` (config read, backing `Cache`,
  `get`/`put`/`remove`/`clear`); each subclass keeps its `CACHE_ID`, singleton
  and `generateKey`. `SiteWideCache` is deliberately outside that hierarchy —
  its `get` takes no timestamp, having no per-weblog expiry.
- **A Velocity resource loader that cannot report modification may not be
  cached; the two settings only ever change together.** With `cache=true`
  Velocity reuses a parse tree whenever `isSourceModified` says "not stale",
  so a loader answering a constant `false` pins every template for the life of
  the JVM — edits do nothing until a restart, with no error.
  `ThemeResourceLoader` used to answer that constant (hence
  `resource.loader.theme.cache` false); it now reports the theme's real disk
  timestamp, `0` → "modified" when it cannot tell, and is cached.
  `RollerResourceLoader` serves CUSTOM templates from database rows with no
  timestamp and still answers a constant `false`, so
  `resource.loader.roller.cache` must stay `false` until it reports a real
  timestamp. `LoaderCachingContractTest` probes every *cached* loader with a
  maximally stale resource and fails if one answers "unmodified"; loaders that
  need a collaborator to answer (`webapp`, which asks the `ServletContext` for
  a real file) are named there with a reason, the `QualityGatePomTest`
  convention.
- **Velocity is lenient, so deleting a Java member is a live hazard.**
  `velocity.properties` sets no `runtime.references.strict` and turns off
  `runtime.log.invalid.reference`: a reference to a deleted field or getter
  neither throws nor logs — it prints as literal text (e.g.
  `$entry.commentCount`) into the page (W1 shipped `journal/_day.vm`'s
  `$entry.commentCount` and `feeds.vm`'s
  `<comments>$url.comments(...)</comments>` this way). Any deletion of a Java
  member a `.vm` can reach (pojo, wrapper, model) must `grep`
  `app/src/main/webapp/themes` and `app/src/main/webapp/WEB-INF/velocity` for
  it before being called done.
- **A zero-argument macro name written bare inside a comment is INVOKED, not
  printed.** `#name` with no parentheses is a valid velocimacro call when
  `name` takes no arguments, inside a `//` JavaScript comment or a `/* */` CSS
  one alike (it bit `weblog.vm`'s live-preview script via a
  `#showPreviewShellScript` mention, and three themes' `*-custom.css` via
  `#showAudienceAssets`/`#showWeblogCategoryLinksList`). Write "the showX
  macro" in a comment, never `#showX` — `ThemeStylesheetTest` scans every
  theme stylesheet for the bare form and fails the build.

## Admin UI
- **Maintenance is a Global Admin screen, not a per-weblog one.**
  `MaintenanceController`/`Maintenance.jsp` live under
  `ui/controllers/admin`/`jsps/admin` at `/roller-ui/admin/maintenance.rol`,
  listed in `admin-menu.xml` (`globalPerms="admin"`), not `editor-menu.xml`.
  Its three per-weblog actions (flush cache, rebuild index, regenerate
  renditions) pick a weblog in a `<select>`.
- **Design system**: `docs/design/design-system.md` is the committed spec
  ("Quiet Instrument"). Tokens live in `roller-ui/styles/roller-tokens.css`
  (light under `:root`, dark under `@media (prefers-color-scheme: dark)`,
  self-hosted IBM Plex `@font-face`), linked in `head.jsp` *after*
  `bootstrap.min.css` and *before* `roller.css` (each layer overriding the
  last). `DesignTokenTest` enforces that ordering, every hex literal tracing
  to the spec's 21 values, and light/dark defining the same tokens.
- **Never restyle by renaming a selector.** Every admin route's content tile
  must keep emitting the CSS marker `Routes` pins for it in
  `it-selenium/.../support/Routes.java`; `RouteSweepIT` asserts it on every
  route, because full chrome (banner, nav, footer,
  `<h2 class="roller-page-title">`) renders even with no content tile — the
  `categoryEdit.rol` failure mode: a healthy 200 with no form. Update `Routes`
  in the same commit as the CSS.
- **The tiles system** is homegrown, not Apache Tiles: `ViewDefinition`
  (layout JSP + attribute JSPs such as `content`, `menu`) is resolved by
  `RollerViewResolver`, which registers eight base layouts in `init()`:
  `.tiles-mainmenupage`, `.tiles-tabbedpage`, `.tiles-simplepage`,
  `.tiles-loginpage`, `.tiles-installpage`, `.tiles-errorpage`,
  `.tiles-popuppage`, `.tiles-barepage`. `.tiles-barepage` (content, no head)
  exists only for `.MediaFileEditSuccess`, which calls
  `parent.onEditSuccess()` and dies milliseconds later, aborting a full head's
  webfont fetch on every rename (the `GalleryIT` "font ERR_ABORTED" flake).
  Never reuse it for a page a human reads.
  `tiles-tabbedpage.jsp`/`tiles-mainmenupage.jsp` render `#adminRail`
  (`navMenu` tool groups under caps-labels, `.rail-active` on the current
  tab), smoke-tested on the Entries route by
  `RouteSweepIT.adminRailIsPresentWithAnActiveSpineOnATabbedPage`.
- **A confirmation prompt is `data-confirm`, never an inline
  `onclick="return confirm('...')"` — the inline form FAILS OPEN.**
  `fn:escapeXml` renders an apostrophe as `&#039;`, which the HTML parser
  decodes to `'` *before* the JS compiles, so one apostrophe
  (`o'brien@example.com`) breaks the handler and the click proceeds
  unconfirmed; an attribute has no second parser, so `dataset.confirm` gets
  the literal text. In `theme/scripts/roller.js`, `click` owns `data-confirm`
  on a *control* (a `formaction` button, a link), `submit` owns it on the
  *form* (catching Enter in a text field), and the click handler's ancestor
  walk stops at the form or both prompt (shipped once on `UserEdit`).
  **`data-confirm` on a `<form>` is the one legitimate case** —
  `Members.jsp`'s removal confirm depends on radios across the whole table,
  beyond any single control. `data-confirm-when` names a CSS selector; the
  submit handler prompts only when `form.querySelector(selector)` matches
  (`!when || matches`, so without it a form-level `data-confirm` prompts
  unconditionally). `data-confirm`(`-when`) on `click` or `submit` is the
  **only** confirm idiom: `JspConsistencyTest.oneConfirmIdiomAndOneModalShape`
  bans `confirm(` and `onsubmit=` in every JSP, so an inline
  `window.confirm()` fails the build.
- **Buttons theme through Bootstrap's `--bs-btn-*` custom properties**
  (`--bs-btn-hover-bg`, `--bs-btn-active-bg`, `--bs-btn-disabled-bg`, …),
  never our own `:hover`/`:active` rules — Bootstrap's
  `:active`/`.active`/`.show` chain reaches `(0,3,0)` specificity and beats a
  classed override. Per-bucket (primary/secondary/destructive) variables cover
  hover→active→disabled in one place.
- **`.form-stacked`** on a `<form>` converts Bootstrap's
  `row.mb-3 > label.col-sm-3 + div.col-sm-9` grid to labels-above block flow
  without touching fields (`WeblogConfig.jsp`, `GlobalConfig.jsp`, ten more).
- **`.empty-state`/`.empty-state-title`/`.empty-state-body`** are the
  "invitations, not shrugs" signature (600/16px title, one `--ink-soft`
  sentence, at most one primary action, icon-free) on Entries/Pages/
  Submissions/MediaFileView. **`Categories.jsp` renders it INSIDE the table**
  as the lone `<tr>` of an empty tbody, making its `<td>` the tbody's
  first-child (the hook the header's caps-label rule keys off), so
  `.empty-state`'s `font-weight`/`text-transform`/`letter-spacing` resets are
  load-bearing. `Pages.jsp`/`Submissions.jsp` render it as a sibling of the
  table in a `<c:otherwise>`; check the caller.
- `roller-ui/scripts/ajax-user.js` is pulled in with `<%@ include %>`
  (translation-time), so JSP scriptlets inside it **are** interpolated despite
  the `.js` extension. `UserAdmin.jsp` is its only includer
  (`// Used in: UserAdmin.jsp`; `MembersInvite.jsp` is gone,
  `MembersController.grant()` adds collaborators directly).
- Enabling/disabling an account: the checkbox persists whatever happens, so
  only disable-then-sign-in proves it — `UserAdminIT`.
- **One status pill for every entry/page publication state.**
  `WEB-INF/jsps/editor/StatusPill.jsp` is a `<jsp:include>`; callers set
  `pillStatus` (a `PubStatus` name) and optionally `pillWhen` (a `Date`, for
  `SCHEDULED` rows) as **request**-scoped attributes (`<jsp:include>` runs in
  a fresh `JspContext` blind to page scope). It renders
  `<span class="status-pill status-<lower>">` plus a trailing `.status-when`
  when `pillWhen` is set. In a `c:forEach` (`Entries.jsp`, `Pages.jsp`) a row
  that sets no `pillWhen` must `<c:remove>` it, or a `SCHEDULED` row's
  timestamp leaks onto later rows. Five `PubStatus` variants plus `unsaved`
  for the entry editor's publish rail, where a new entry has no `PubStatus`
  yet (once `bg-info` badges). **The message code is composed in EL**
  (`weblogEdit.${fn:toLowerCase(pillStatus)}`), so `MessageKeyTest` cannot see
  it; only the file's header comment, naming all six literally, keeps them off
  the orphan list (that arm matches WHOLE keys, `.` continuing a key:
  `weblogEdit.unsaved.` with a trailing full stop is not
  `weblogEdit.unsaved`).
- **One selection bar for every list with bulk actions.** `.selection-bar`
  (`data-selection-bar="<form id>"`, `hidden` by default) sits above the table
  on Entries/Submissions/MediaFileView; the header checkbox carries
  `data-select-all` and is excluded from the count. `roller.js`'s delegated
  document-level `change` listener finds the bar whose `data-selection-bar`
  matches the checkbox's `form.id`, counts
  `input[type=checkbox]:checked:not([data-select-all])`, toggles `bar.hidden`,
  and writes the count into `.selection-count` via the `selection.count`
  message (`"{0} selected"`, arity 1, all eight bundles). Trash.jsp
  deliberately has none: `TrashController` has no
  `restoreSelected`/`deleteSelected` pair, only per-row and whole-trash
  endpoints.
- **`<rc:date>`** (`app/.../ui/tags/DateTag.java`, declared in
  `WEB-INF/rollerConfig.tld`) is the admin UI's one timestamp renderer,
  replacing `fmt:formatDate` (server zone) and the translated
  `weblogEntryQuery.date.toStringFormat` key (request-locale zone). **That key
  is deleted from every bundle** (a FORMAT in eight translations could
  disagree — `ja` had `yy/MM/dd HH:mm` vs the base `MM/dd/yy hh:mm a`) and
  `JspConsistencyTest` fails if one declares it again. It emits
  `<time datetime="<UTC ISO-8601 instant>" class="data">yyyy-MM-dd HH:mm</time>`:
  `datetime` is absolute UTC; the text is the **weblog's** wall clock in
  `Locale.ROOT` (tabular cells), as `EntryEditController` already parses
  `bean.pubTimeLocal` against
  `getActionWeblog(request).getTimeZoneInstance()`. Null renders nothing
  (every `<c:if test="${x != null}">` keeps working).
- **Field errors point at the field, not just a banner.** `BaseController`
  adds `addFieldError(model, fieldId, key, request)` (and an `Object[] args`
  overload) beside `addError`: plus the control's DOM id into model attribute
  `invalidFields` (`BaseController.INVALID_FIELDS`, an ordered, deduped
  `LinkedHashSet<String>`) and its space-joined `invalidFieldIds`
  (`INVALID_FIELD_IDS`). All three admin layouts render
  `<body data-invalid-fields="${fn:escapeXml(invalidFieldIds)}">` **only when
  non-empty** (sticky, it would mark every later clean render refused).
  `roller.js` reads `document.body.dataset.invalidFields` on
  `DOMContentLoaded` and, for each id that resolves, adds `.is-invalid`, sets
  `aria-invalid="true"`, and focuses the first — null-guarded (it also loads
  on public pages), an unrendered id skipped silently; the CSS
  (`.form-control.is-invalid` etc.) predates it, so no `DesignTokenTest`
  change.
- **Sidebars render on the rail's own grammar, not a Bootstrap card.**
  `<aside class="sidebar">` replaces `.card`/`.card-body`; each block is a
  `.sidebar-group` (`border-top`, padding) with a `.sidebar-label` heading in
  the rail's caps-label role (12px/600/.08em/uppercase). Every sidebar form
  gets `class="form-stacked"`.
- **The top bar's weblog switcher appears only for a user on ≥ 2 weblogs.**
  `BaseController.populateCommonModel` resolves the caller's weblog
  permissions to `List<Weblog>` and adds `userWeblogs` only then.
  `switcherAction` is the current admin action when it is one of nine reusable
  ones (`entries`, `trash`, `submissions`, `categories`, `pages`,
  `mediaFileView`, `themeEdit`, `weblogConfig`, `members`), else `entries`, so
  switching from Global Config lands on the entry list, not a 403. The
  permission lookup is a log-and-return `catch` — a display path, the page
  being built from `actionWeblog`, which the interceptor resolves separately.
- **`URLUtilities.getQueryString` URL-encodes both key and value** (raw, `&`
  started the next parameter, `#` dropped everything after it, `+` became a
  space; `fn:escapeXml` on the `href` is no repair — its `&amp;` decodes back
  to `&` before the request is built). **Every caller of
  `getQueryString`/`getActionURL` passes RAW values** — pre-encoding
  double-encodes (as `MultiWeblogURLStrategy`'s `cat`/`q` and
  `PreviewURLStrategy`'s `theme` params once did). The one exception,
  `getWeblogSearchPageURLTemplate`, keeps its `{searchTerms}` OpenSearch
  placeholder braces unencoded.
- **A JSP tag prefix with no declared taglib renders as literal text, not an
  error** — `<str:truncateNicely>` shipped in three places with no `str`
  taglib declared anywhere (the JSP twin of Velocity's leniency, see
  Templates). `JspConsistencyTest` scans for undeclared-prefix tags.
- **`JspConsistencyTest` pins all of the above as markup shape** — status
  pills, `.selection-bar`/`.selection-count`, the confirm idiom,
  `data-invalid-fields`, sidebar grammar, no inline `onclick` on the switcher,
  undeclared tag prefixes, balanced `<div>`s, heading levels, the href rule
  below. It covers **every** JSP — the `A_OWNED` exemption is deleted, not
  replaced.
- **Every `href` built from an expression is `fn:escapeXml`'d, a `<c:url>` (in
  a var or inline), or a bare `urls.*` helper call — tree-wide.** The obvious
  `grep 'href="${' | grep -v escapeXml | grep -v 'c:url\|urls\.'` is mostly
  false positives and misses a `urls.*` call with a raw field appended
  (`${urls.weblogAbsolute(w)}${p.slug}` on `Pages.jsp`; a slug is checked
  against `/` and reserved names, never quotes), so the scan strips the three
  permitted forms from the whole attribute value and fails on any `${` left;
  it also sees the inline `<c:url .../>` spelling.
- **A message argument reaches the reader UNESCAPED, so escape it at the call
  site.** `tiles/messages.jsp` renders `${msg}` bare and
  `<c:out value="${error}" escapeXml="false"/>`, deliberately (several
  messages carry a link or a `<strong>`), so the controller is the escaping
  boundary. `StringEscapeUtils.escapeHtml4` wraps every user-typed argument;
  `MessageArgumentEscapingTest` is the ratchet: **any** accessor passed as a
  message argument must be escaped or named in its allowlist with the
  mechanism that makes it safe. Blunt on purpose: inferring which accessors
  return author input is what went wrong at `Weblog.getName()`
  (`WeblogWrapper` escapes for themes) and `EntryBean.getTitle()` (the
  *entity* is stored escaped). `Weblog.setName`'s `Utilities.removeHTML` is
  not a boundary: its no-closing-bracket branch appends the rest verbatim, `<`
  included, so an unterminated `<img src=x onerror=…` survives. Over-escaping
  costs too (hence the allowlist): an entry title, already escaped at save by
  `EntryFieldRules`, renders `&amp;lt;`. Deliberately outside the scan:
  `CreateWeblogController` passes `e.getMessage()` as the message **key**, and
  `getText` returns an unresolvable key verbatim to the raw sink — recorded,
  not fixed.

## Categories
- **Ownership-check every id.** `BaseController.lookupEntry`/
  `lookupTemplate`/`lookupCategory`/`lookupPage` are the by-id ownership
  family: the permission interceptor only vouches for the *action* weblog, so
  a global by-id lookup lets any editor rewrite any weblog's data. All four
  treat a blank id as absent. Both `removeId` and `targetCategoryId` need it
  — a foreign move target re-files entries into someone else's blog.
- **Modal JS binds by control NAME, not id.** Struts-generated ids
  (`#categoryEditForm_bean_name`) never survived the JSP migration, so
  add/edit/delete silently did nothing; names are what the server binds.
- **Add and edit are different endpoints** (`categoryAdd!save.rol` /
  `categoryEdit!save.rol`); the shared modal picks by whether `bean.id` is set.
- The Blogger XML-RPC API and `weblog.bloggercatid` (a raw id with no
  cascade; deleting its category left the weblog's settings unsaveable) are
  gone (W2, `V023__drop_w2_fossils.sql`); `removeWeblogCategory` has nothing
  to null out. `CategoryIT.deletingACategoryLeavesTheWeblogSaveable` still
  covers the general shape.

## Comments
The comment subsystem was removed outright in W1 and is not coming back. It
was unreachable by design: `requireAuthenticatedComments` defaulted true (V013:
`weblog.comment_auth_required` `DEFAULT true NOT NULL`) and this fork has no
public self-registration, so only an admin-provisioned account could ever
comment. The contact form and newsletter (see Audience) are the reader-facing
channels now.

A sweep for "comment" must leave alone:
- **`roller_audit_log.comment_text`** — the audit log's own change note; a
  name collision only.
- **`util/GenericThrottle`** — `CommentServlet` was only its most visible
  caller; it throttles `ContactController` (`contact.throttle.*`),
  `NewsletterController` (`newsletter.subscribe.throttle.*`) and
  `PasswordResetController` (`passwordreset.throttle.*`).

Search index: `IndexOperation` no longer writes `C_CONTENT` (`Field.Store.NO`)
and `SearchOperation.SEARCH_FIELDS` is `{CONTENT, TITLE}`, not
`{CONTENT, TITLE, C_CONTENT}`, so pre-wave comment text is already unsearchable
without a rebuild. `C_NAME`/`C_EMAIL` (`Field.Store.YES`) do linger in a
pre-wave index until the entry's document is replaced (Maintenance rebuild or
incidental re-save) — minor PII residue worth clearing.

## Entry editing
- **Layout**: `EntryEdit.jsp` (approved card:
  `docs/design/editor/editor-writing-surface.html`) is a writing surface plus
  a 252px publish rail. Main column: title (large serif, borderless —
  **emphasis elsewhere is weight, never size**), the permalink as a mono line
  with a copy control, the untouched `EntryEditor.jsp` include. Rail: Publish
  box (status pill, the one visible time field, submit buttons), Organize box
  (category/tags; locale is a hidden input), SEO drawer (SEO & Social
  Sharing card, collapsed), newsletter/revisions boxes
  (`.editor-box`/`.rail-group-label`; no Bootstrap `.card` in the rail),
  Delete as a quiet text link, last. The `#entry` form is `display:contents`
  so the newsletter/revisions boxes' own `<form>`s (own CSRF token and POST
  target) sit in the rail's grid column without nesting.
- **`bean.pubTimeLocal` is the only pubtime field and means the WEBLOG's
  clock.** One `<input type="datetime-local">` replaced the old
  three-`<select>` row. `EntryBean.getPubTime(TimeZone)` parses the
  wall-clock string against the caller's `TimeZone`; `EntryEditController`
  always passes `getActionWeblog(request).getTimeZoneInstance()`. A non-blank
  value that fails to parse **throws**; the save is blocked with
  `entryEdit.pubTimeInvalid` via `addFieldError` (marking and focusing
  `entry_bean_pubTimeLocal`) through the normal `hasErrors` gate — the old
  parser silently published "now". Blank still means publish now.
  **The SEO card's `bean.eventStartLocal`/`eventEndLocal` do NOT share those
  semantics** (pre-existing, SEO Wave 1, not fixed): they round-trip through
  `Timestamp.valueOf(LocalDateTime)` with no `TimeZone`, so the stored
  instant is in the **server's default zone** and `eventStart`/`eventEnd`
  drift where the weblog's zone differs; `EntryBean` needs the same
  `TimeZone` accessor.
- **The sidebar is retired.** `EntrySidebar.jsp` is deleted;
  `RollerViewResolver`'s `.EntryEdit` layout maps `"sidebar"` to
  `tiles/empty.jsp`; its four recent-entries lists (20 each) are gone from
  `EntryEditController` too.
- **Entry plugins are gone; the shortcode render seam stays.**
  `ConvertLineBreaksPlugin`, the "Plugins to apply" card and
  `weblogentry.plugins`/`weblog.defaultplugins` (dropped V021, idempotent)
  are deleted (see Plugin System). `PluginManagerImpl`/`WeblogEntryPlugin`
  and `WeblogEntry.render()`'s call into `applyWeblogEntryPlugins` stay
  because `ShortcodeExpander` runs through that call (see Shortcodes); both
  call sites now apply every site-registered plugin unconditionally.
- **Editor**: CodeMirror 6, built by Maven from `app/frontend/` (see "Frontend
  build"); one global, `RollerEditor.create(options)`, called from
  `EditorScript.jsp`. `EditorSurface.jsp` (markup) and `EditorScript.jsp`
  (script) are shared verbatim by the entry and page editors. `options`
  carries `onSave`/`onPublish`/`onHelp`, bound to `Mod-s`/`Mod-Enter`/`Mod-/`
  ahead of CM6's keymap (unsupplied: CM6's default). Exactly three seam
  functions — `insertMediaFile`, `rollerSetEntryText`, `rollerGetEntryText` —
  so replacing the editor is one file; Browser ITs use those and the `Editor`
  test helper, never CM6's API.
  `rollerEditorChangeListeners` is the fourth seam: every change-driven
  feature (autosave, live preview, word count) pushes a listener onto it.
  **Live preview is theme-true**: an iframe onto `PreviewServlet?shell=true`,
  which resolves the theme's own `_preview` template (or a shared shell) so
  the pane carries the weblog's real stylesheet; the editor posts rendered
  fragments over `postMessage` after each debounced render, and
  `#showPreviewShellScript` (`weblog.vm`) swaps `#previewArticle`'s content
  and calls `window.rollerPreviewInit` so galleries/maps/embeds initialise.
  Paste/drop of an image posts to `mediaFileAdd!upload.rol` (the REST API's
  `MediaUploads` helper) and inserts an `[image]` shortcode. The writing
  guide (offcanvas, `Ctrl+/` or the toolbar help control) and status line
  (word count/reading time; Unsaved/Draft-saved-locally/Saved) live there
  too.
- **Autosave is LOCAL ONLY; there is no server endpoint** —
  `theme/scripts/roller-draft.js` writing to `localStorage`, installed from
  `EntryEditor.jsp` and `PageEdit.jsp`. A server endpoint would multiply
  `weblogentry_revision` rows against `entry.revisions.retention`'s default
  **-1, keep everything** (see Revisions) and need a real entry row per
  draft; the work that actually gets lost is lost in the browser, which
  `localStorage` covers. Load-bearing:
  - **Recovery compares content, never timestamps** (a check against
    `entry.updateTime` must reconcile browser, server and weblog clocks). A
    snapshot matching what the server just rendered is a completed save and
    is dropped silently.
  - **`staleKeys` compare the editor text ALONE**, unlike the primary key's
    whole-form comparison: saving a new entry redirects to `entryEdit`, where
    `EntryBean.copyFrom` has populated `bean.status` and `bean.pubTimeLocal`
    (empty on the add form), so a whole-form comparison never matches, the
    `entryAdd:new` snapshot survives its own save, and the next blank editor
    is handed the previous entry's text (`EntryAutosaveIT` caught this). The
    consumption test is text **and** title, or reloading an entry's own tab
    would delete a new-entry draft whose body was copied from it.
  - **The field denylist is by NAME, not `type="hidden"`**:
    `bean.featuredImageId`/`bean.ogImageId` are hidden inputs carrying real
    author choices. `bean.status` is deliberately *not* excluded — a visible
    `<select>` on the page editor, and on the entry editor the submit buttons'
    `formaction` decides status regardless. So on a **page**,
    `PageBean.copyTo` writes the submitted status through and Restore can put
    a stored DRAFT/PUBLISHED choice back into that select (the one place
    Restore changes something other than prose).
  - **Submit saves the snapshot rather than clearing it**, so an expired
    session's login redirect does not take the text; the next load drops it
    by content comparison.
  - **Restore re-saves rather than dropping**, so recovered text keeps a
    backup; it never auto-restores (an unreviewed snapshot must not overwrite
    the server's copy).
  Known limits (in the spec): two tabs on the same *new* entry share the one
  `…:new` slot (later debounce wins); a draft outlives logout, up to 30 days
  in that browser profile, so on a shared machine the next author sees it.
  The leave-warning is bound **once** against a dirty flag under the
  `beforeunload.rollerLeaveWarning` namespace (not a fresh `beforeunload`
  *and* `submit` handler per CodeMirror `change`, as before), and stays
  despite drafts.
- **Preview** is server-side (`entryEdit!preview.rol`): only the server can
  expand shortcodes.
- **List actions**: `Entries.jsp` is ONE form around the table — bulk
  checkboxes, per-row duplicate and the action bar post through it, so the
  duplicate control is a submit button carrying `name="duplicateId"`, not a
  nested form. Bulk actions loop per id through `BaseController`'s
  `lookupEntry`; delete goes through `trashEntryWithIndex` so the Lucene index
  cannot be orphaned. **Delete moves an entry to the trash** (W5, see Trash),
  and the flash copy says so.
- **Revisions**: `weblogentry_revision` (V010) keeps the pre-save title/text/
  summary of every content-changing save, snapshotted by a JPA `post-load`
  callback (`WeblogEntry.snapshotLoadedContent`) because `saveWeblogEntry`
  only sees the caller's NEW values. Runtime property
  `entry.revisions.retention`: **-1 (default) keeps everything**, 0 records
  none, n>0 prunes to the n newest in the save's own transaction. Rendered as
  a rail box below the publish rail, own form/CSRF per restore button.

**Controllers: always name `@RequestParam`/`@PathVariable` explicitly.** The
build does not pass `-parameters`, so a bare `@RequestParam String id` throws
at runtime while unit tests (which call the method directly) pass;
`ControllerMetadataTest` fails on any unnamed one.

## Trash (soft delete, W5)

Deleting an entry moves it to a restorable trash; every design choice keeps
the new dimension from spreading.

- **A fifth `PubStatus` value (`TRASHED`), not a `deleted_at` column**: every
  query that names a status excludes trash by construction, whereas
  `deleted_at IS NULL` would have to be remembered in seven named queries, a
  dynamic query builder and 23 call sites, and fails **open**. `status` is
  stored by name (`<enumerated>STRING</enumerated>`), so no ordinal hazard.
  `weblogentry.trashed_at` (V025) exists only so the trash list can sort and
  the purge can expire.
- **The exclusion lives in exactly one place**: `WeblogEntrySearchCriteria`'s
  `includeTrashed`, default **false**, applied in
  `JPAWeblogEntryManagerImpl.getWeblogEntries` when no explicit status is
  set. The default IS the safety property; never add a status condition to a
  second query.
- **Four status-less queries deliberately still see trash**, each commented
  in `WeblogEntry.orm.xml`: `getByCategory` (a trashed entry must still block
  deleting its category, or there is nothing to restore into), the two anchor
  queries (a trashed entry still occupies its anchor; the permalink lookup is
  safe only because `PageServlet` filters `isPublished()`), and
  `getByWebsite` (the weblog-deletion cascade must take the trash with it).
- **Restore always goes to `DRAFT`, never `PUBLISHED`** — silent republishing
  to feeds, sitemap and subscribers is worse than one extra click; hence no
  column remembers the pre-trash status.
- **`BaseController.trashEntryWithIndex`** (was `removeEntryWithIndex`) is the
  single authoring-side deletion seam, with `deleteEntryForeverWithIndex`
  beside it. `WeblogEntryManager.removeWeblogEntry` remains the one
  permanent-deletion path — `purgeTrash` calls it per entry, never a bulk
  DELETE.
- **The index steps are MORE necessary now the entry survives** — a `TRASHED`
  entry left in Lucene is findable and links to a 404. Two live bugs:
  - `ReIndexEntryOperation` is **asynchronous** and **re-fetches the entry by
    id**, so the old flip-status-to-DRAFT-and-re-index trick only worked
    because the row was gone before the job ran, and re-added trashed entries
    once it survived. It now refuses to add a document for a
    non-`PUBLISHED` entry.
  - **`weblog.lastModified` must be bumped explicitly when a published entry
    is trashed.** `CacheManager.invalidate` never reaches `WeblogPageCache`;
    only `lastModified` expires a rendered page (see Templates), and
    `trashWeblogEntry` sets `TRASHED` before saving, so `saveWeblogEntry`'s
    `isPublished()` bump gate is false.
  Trashing a published entry also sets `refreshAggregates`, or its tags stay
  in the tag cloud until purge — at default retention, never. **Restore
  deliberately does not**: `restoreWeblogEntry` lands on `DRAFT`,
  `trashWeblogEntry` already decremented the tag counts, and re-incrementing
  would over-count a draft's tags.
- **`entry.trash.retention.days`** — runtime property, default **30**, `-1`
  keeps forever. Swept by `TrashPurgeTask` beside `ScheduledEntriesTask`;
  re-read per sweep, not latched in `init()` (the third Configuration-scope
  trap); per-weblog try/catch.
- **Pages and media files are deliberately NOT in the trash**: a soft-deleted
  media file still occupies disk, counts against `uploads.dir.maxsize`, keeps
  its renditions and stays reachable at its media URL unless every such path
  learns about trash — the spreading this design avoids. Pages are few and
  deliberate.
- **A bare `--` inside an XML comment in `runtimeConfigDefs.xml` makes the
  parse fail SILENTLY**: `getRuntimeConfigDefs()` returns null and surfaces
  as unrelated NPEs elsewhere (it bit the `pom.xml` coverage comments too).

## Pages
Static pages (`WeblogPage`, V014) are a separate entity from `WeblogEntry` on
purpose: merging would touch all 25 of `WeblogEntryManager`'s query paths.
- **Routing**: a published page is served at `/<handle>/<slug>`. **Not
  `/<handle>/page/<slug>`**, the CUSTOM-template route (see Templates); both
  admin screens (editor, `Pages.jsp`) once emitted it, so `PageEditJspTest`
  asserts them against each other. `ReservedSlugs` (shared by
  `WeblogPageManager` at save and `WeblogPageRequest` at parse) is the one
  list of reserved slugs (`entry`/`category`/`tags`/`feed`/…).
  `WeblogRequestMapper` forwards any unknown single-segment path to the page
  servlet.
- **Lazy resolution**: parsing sets only `pageSlug` (no DB access; the
  cache key); `getWeblogPageContent()` resolves it memoized, so a cache hit
  never resolves the page. An unknown or draft slug is a 404, never a
  fall-through to the permalink/default-page branches. `WeblogPageCache` and
  `SiteWideCache` keys carry a `/pageslug/<slug>` segment, so a page and a
  same-named context never collide.
- **Rendering**: a theme may override the page template with one named
  `_page`, via the `StaticThemeTemplate`/`VelocityRendererFactory` fallback.
  `savePage`/`removePage` bump `weblog.lastModified`; no explicit eviction
  (see Templates).
- **Editor**: `PageEditController`/`PagesController` reuse the entry editor's
  shape via `PageBean`. `lookupPage` is the fourth by-id ownership member on
  `BaseController` beside `lookupEntry`/`lookupTemplate`/`lookupCategory`
  (see Categories), over the global `getPage`. The `showInNav` field marker
  is `_showInNav`, **not** `_bean.showInNav` — the `bean.` prefix silently
  breaks it; a unit test reads `PageEdit.jsp` to pin it.

## Redirects (301s for URLs that would otherwise 404)

`roller_weblog_redirect` (V028) maps one weblog-relative path to another
(301). Spec: `docs/superpowers/specs/2026-08-24-url-redirects-design.md`;
`docs/api/README.md`'s API section is the admin surface (deliberately no JSP
screen).

- **A rule is consulted ONLY where a 404 is already decided.** Four seams,
  all via `RedirectResponder.answer`: `PageServlet`'s
  `selectTemplate == null` and `rejectionReason != null` exits,
  `WeblogRequestMapper`'s two `sendError(SC_NOT_FOUND)` sites and its
  `calculateForwardUrl == null` decline. So a rule cannot shadow live content
  (`RedirectServingTest.aRuleCanNeverShadowLiveContent`). **Never add a
  fifth call site that has not already decided to 404.** The mapper's decline
  holds only by argument, so it is pinned too.
- **Every failure around a redirect degrades to the decided 404, never a
  500.**
- **The Location derives from the WEBLOG, never the request**:
  `URLStrategy.getWeblogURL` root + target + the original query string (via
  `PageServlet`'s forward attributes). Custom domains and the context path
  are the strategy's, never reimplemented.
- **One hop, enforced from both ends at save time** (a rule may neither
  begin where one ends nor end where one begins), plus open-redirect
  refusals: `//` prefix, schemes, query strings, backslash, control chars,
  external targets. Paths are normalized alike at save and match
  (leading `/`, trailing slashes stripped): `/old` and `/old/` are one rule.
- **Renaming a page's slug mints a `SLUG_HISTORY` rule** via
  `WeblogRedirectManager.recordRename`, from `savePage` in the SAME
  transaction, in order: delete rules on either slug, re-point rules
  targeting the old slug at the new one (or one-hop breaks under repeated
  renames), then mint; A→B→A converges to one rule. The old slug is
  `WeblogPage.loadedSlug`, a JPA post-load snapshot.
- **Entry anchors are immutable, so there is no entry-side hook** —
  `EntryBean` has no anchor field; `createAnchor` fires only when unset.
  Migrated permalinks are manual rules.
- **Hit bookkeeping is best-effort**: `recordHit` is one JPQL
  `UPDATE ... hit_count = hit_count + 1` (no load-modify-store) in its own
  try/catch. `hitCount`/`lastHitAt` are on the API list.
- **Every served redirect and rule mutation logs one INFO line on logger
  `roller.redirects`** (`WeblogRedirectManager.LOG_NAME`): rule id, origin,
  requested URI + query string, target, referer, user-agent — the last
  two attacker-controlled: `{}` slots only, nothing after them.
- **JPA trap**: `getNamedQuery` sets `FlushModeType.COMMIT` (queries never
  see pending writes), so `recordRename`'s mint-after-remove validation reads
  through `getNamedQueryCommitFirst` (AUTO), or a legal rename is refused for
  colliding with a rule just deleted. The hot `resolve` path stays no-flush
  on purpose.
- `removeWeblog` cascades `WeblogRedirect.removeByWeblog`;
  `WeblogOwnership.redirect` is the fifth by-id ownership member, used by
  `RedirectsApi` (404 never 403, POST permission, no update verb — delete and
  recreate).

## Virtual hosts (per-weblog custom domains)

A weblog with `weblog.custom_domain` set is served at that hostname's root:
`https://berlin.thelocalwiki.com/entry/x`, not
`https://blog.example.com/berlin/entry/x`. Spec:
`docs/superpowers/specs/2026-08-18-virtual-host-support-design.md`.

- **Resolution is host-first in `WeblogRequestMapper`, and the forward url
  still carries the handle**, so `PageServlet`, `WeblogPageRequest`, pagers,
  models and caches never learn vhosts exist. `VirtualHostRegistry` holds the
  hostname→handle map in memory (invalidated in `saveWeblog`/`removeWeblog`),
  so it works before `PersistenceSessionFilter` and `ControlPlaneHostFilter`
  can sit at filter order **35**, ahead of Spring Security (40), whose login
  302 would otherwise mint a session and CSRF token on the custom domain.
- **Generated urls derive from the WEBLOG, never the request**:
  `WeblogPageCache` keys on the handle and `#showSeoHead` bakes absolute
  canonical/`og:url` values into those bytes, so a request-derived url would
  stamp one host's canonical onto the other. All eleven weblog-content url
  methods root through `MultiWeblogURLStrategy.getWeblogURL`;
  `AbstractURLStrategy`'s six `/roller-ui/` methods are control plane and
  must **not** become domain-aware.
- **`appProtectedUrls` is a strict subset of `rollerProtectedUrls`**, which
  mixes application paths (`roller-ui`, `api`, `themes`, `webjars`,
  `robots.txt`, `sitemap.xml`, `newsletter`) with weblog request contexts
  (`page`, `search`, `resource`, legacy `flavor`/`rss`/`atom`/`language`). On
  the site host a context is the SECOND segment (`/<handle>/page/x`); on a
  custom domain it is the FIRST, so reserving it there declines
  `/page/<theme>.css` and renders every vhost page unstyled. Only
  `appProtectedUrls` applies in vhost mode; only the `isWeblog()` half of the
  guard is skippable.
- **A custom-domain url still carries the servlet context path**: under a
  prefix its root is `https://host/roller/`, not `https://host/`.
  `getWeblogURL`, the mapper's path-form 301 and `SeoController.robots()`
  got this wrong; `ControlPlaneHostFilter` is right because
  `site.absoluteurl` carries the context path by convention.
- **`/roller-ui/rendering/**` and `/newsletter/**` are exempt from the
  control-plane redirect.** `ContactController`
  (`/roller-ui/rendering/contact.rol`) and `NewsletterController`
  (`/newsletter/subscribe`) are `fetch`-posted under `connect-src 'self'`; a
  redirect to the site host is cross-origin (CSP-blocked; a POST 301 has no
  body), breaking every `[contact]`/`[subscribe]` on vhost weblogs.
- **`site.absoluteurl` is required once any weblog has a custom domain**,
  read directly — never `getAbsoluteContextURL()`, whose `InitFilter`
  fallback can itself be a custom domain. Unset, the filter serves rather
  than redirects: never a loop.
- The path form 301s (never `sendRedirect`, whose 302 tells crawlers not to
  transfer ranking). The site sitemap index **omits** custom-domain weblogs:
  an index may only reference its own host's sitemaps.

### Run the browser suite at BOTH context paths before shipping routing changes

`mvn verify -Pit` covers the root context; `mvn verify -Pit -Dit.context.path=roller`
alone exercises a servlet prefix end-to-end. The prefix pass found a defect
the root pass could not; `SeoController.robots()`'s test, already under
`/roller`, had hardcoded the buggy url. A fixture pinning a context path must
*derive* its expected url, never hardcode a shape.

## Audience
Contact forms, newsletter subscribe, and account tokens. No CAPTCHA anywhere;
no CSP change anywhere — every endpoint is same-origin and `connect-src 'self'`
already allows the fetch.

- **Placeholder-div + `#showAudienceAssets` injection.**
  `[contact]`/`[subscribe]` emit an inert
  `<div class="...-slot" data-*="...">`, never a `<form>`: `HTMLSanitizer`
  strips `<form>` from authored content on purpose (phishing), and
  `#showAudienceAssets` (`weblog.vm`) builds the real forms client-side.
  Endpoints are **server-built** into `data-endpoint`:
  `ContactShortcode.render()` emits
  `WebloggerRuntimeConfig.getRelativeContextURL() + "/roller-ui/rendering/contact.rol"`;
  `SubscribeShortcode.render()` and `#showSubscribeForm` emit
  `WebloggerRuntimeConfig.getRelativeContextURL()` / `$url.site`, not a
  client-guessed `/newsletter/subscribe` — a client heuristic (a `<link>`
  containing `/roller-ui/`) posted to the site root under a context path.
- **Persist-first, then notify.** `ContactController` writes the
  `roller_form_submission` row before any notification email, so the lead
  survives an SMTP outage. Defences in order: per-IP throttle (429); unknown
  weblog handle 404s; **a filled honeypot or a too-fast submit answers 204,
  identically to success, and stores nothing**, so automation learns nothing.
  Subscribe mirrors both.
- **`/newsletter/subscribe` is served by the app, not Caddy.** Throttle and
  `roller_event` recording live in `NewsletterController`; the old Caddy
  `handle /newsletter/subscribe { rewrite ... reverse_proxy listmonk }` block
  bypassed both and **must never come back**. **Roller stores no subscriber
  data** — Listmonk owns the list, opt-in, sending and unsubscribe; Roller
  holds only `weblog.newsletter_list_uuid` and
  `weblogentry.newsletter_sent_at`. List uuids are **not** unique across
  weblogs: `getWeblogByNewsletterListUuid` orders by handle, so a shared uuid
  credits one weblog rather than throwing `NonUniqueResultException`.
- **`roller_event`** (V015) — `FORM_SUBMITTED` (`ContactController`),
  `NEWSLETTER_SUBSCRIBED` (`NewsletterController`, not on an already-subscribed
  409), `ENTRY_PUBLISHED` (`JPAWeblogEntryManagerImpl.saveWeblogEntry`, gated
  on `entry.getLoadedStatus() != PubStatus.PUBLISHED`, the revisions' post-load
  snapshot, see Entry editing; unpublish then republish records a **second**
  `ENTRY_PUBLISHED` because the reload resets `loadedStatus` away from
  `PUBLISHED`). Writes are best-effort and never fail the request. `metadata`
  (jsonb) stays unmapped in JPA until something writes it.
- **`roller_user_token`** (V015) stores a SHA-256 digest only, never the raw
  token — a database read must not yield working reset links. `consume` is an
  atomic rows-affected `UPDATE ... WHERE used_at IS NULL AND ...`, not
  validate-then-mark, closing the double-redemption race; tokens expire after
  `UserTokenManager.TOKEN_TTL_MS` (1 hour). Forgot-password and the admin
  set-password link share `PasswordLinkMailer.sendLink`, so the emailed URL
  shape cannot drift.
- **Forgot-password is enumeration-proof by construction.**
  `PasswordLinkMailer.isReady()` requires BOTH a mail transport
  (`MailUtil.isMailConfigured()`) AND a non-blank `site.adminemail` — transport
  alone would send nowhere. Token issuance + email run off-thread via
  `ThreadManager.executeInBackground`, with `weblogger.release()` in a
  `finally` on the worker (the `AddEntryOperation` convention; an unreleased
  `EntityManager` leaks a connection). Found and not-found paths share one
  timing shape and message.
- **"Send as newsletter" is manual, synchronous, and stamped-on-success** — no
  queue; the human who clicked IS the retry. `EntryEditController` calls
  `ListmonkClient.sendCampaign` in-request and stamps
  `weblogentry.newsletter_sent_at` only after it returns. A send whose
  stamp-save fails shows `newsletter.sentButNotRecorded`, so the editor is not
  invited to double-mail.

## Analytics
Per-weblog Umami tracking plus a read-only Grafana contract over two databases.

- **Structured injection is the only analytics path; no free-text fallback
  (W2).** `Weblog.analyticsSiteId` is a validated UUID
  (`WeblogConfigController.myValidate`). `#showAnalyticsTrackingCode`
  (`weblog.vm`) builds the
  `<script defer src="…" data-website-id="…" data-host-url="…">` tag from that
  UUID plus `ConfigModel.getAnalyticsBasePath()`/`getAnalyticsScriptName()`
  (`analytics.umami.basePath`/`analytics.umami.scriptName` in
  `roller.properties`). The legacy free-text `analyticsCode` textarea (gated on
  *Allow analytics code override* **and** `weblogAdminsUntrusted` off, so never
  reachable here) is gone end to end — `Weblog`/`WeblogConfigBean` plumbing,
  JSP, `ConfigModel`/macro branches, and the `weblog.analyticscode` column
  (`V023__drop_w2_fossils.sql`).
- **Same-origin, so the pinned CSPs never moved.** Served via Caddy's
  `/analytics/*` handle, the tracker runs under every theme's
  `script-src 'self'` / `connect-src 'self'`;
  `ThemeCspCoverageTest.everyPolicyStillAllowsSameOriginScriptsAndBeacons`
  fails otherwise.
- **The Grafana contract splits across two databases — Postgres cannot query
  across them.** `analytics_events` (outcomes from `roller_event`) and
  `analytics_weblog_sites` (handle ↔ Umami website-id) live in `rollerdb`, via
  `bin/db/migrations/V017__analytics_contract.sql`. `analytics_traffic` (over
  Umami's `website_event`) lives in Umami's database:
  `deploy/analytics/umami-views.sql` (in the image at `/app/umami-views.sql`),
  applied by the **separate** one-shot `analytics-views`
  (`deploy/analytics-views.sh`), never by `provision.sh` or the migration chain
  (which only touches `rollerdb`). `website_event` exists only after Umami's
  first boot and `provision` runs before `umami` starts, so applying the view
  from `provision` deadlocked every fresh install; `analytics-views` gates
  nothing — a failure costs only the Grafana traffic panel. Grafana joins the
  halves on `website_id`; no server query spans both.
  `page_slug`/`entry_anchor` on `FORM_SUBMITTED` rows are the contact form's
  reader-controlled `source` (untrusted), and `ENTRY_PUBLISHED` double-counts
  an unpublish/republish (see Audience).
- **`SQLScriptRunner` is dollar-quote-aware.** `V017`'s cluster-global
  `CREATE ROLE grafana_ro` needs a
  `DO $$ … EXCEPTION WHEN duplicate_object … END $$;` guard to survive
  re-application, which the install wizard's `SQLScriptRunner` (beside
  `migrate.sh` and the test harness) used to split on semicolons. It now tracks
  dollar-quote state (`\$[A-Za-z0-9_]*\$` delimiters, any tag including the
  empty `$$`) and suspends semicolon-splitting and `--`-comment-stripping
  inside one; `SqlScriptRunnerMigrationTest` runs the *actual* migration chain
  through it (`DatabaseInstaller`'s applier). One hazard stays: a trailing `--`
  comment after a closing delimiter on the **same line** (`END $$; -- done`) is
  not stripped (the stripper only sees the state *incoming* to the line) and
  silently swallows the next statement — keep them off the line of a
  terminating `;`.
- **The hitcount subsystem is gone; Umami replaced it.** Deleted:
  `HitCountQueue`, `HitCountProcessingJob`, `ResetHitCountsTask`,
  `ContinuousWorkerThread`/`WorkerThread`, `WeblogHitCount` (pojo +
  `.orm.xml`), the `roller_hitcounts` table (`V017`), `WeblogEntryManager`'s
  `getHitCount`, `getHitCountByWeblog`, `getHotWeblogs`, `saveHitCount`,
  `removeHitCount`, `incrementHitCount`, `resetAllHitCounts`, `resetHitCount`,
  `Weblog.getTodaysHits()`/`WeblogWrapper`'s delegate, the Maintenance reset
  button, and the frontpage "Hot blogs" sidebar.
  `WeblogPageRequest.isWebsitePageHit()`/`isOtherPageHit()` **survive**
  (`PageServlet` still classifies request URLs with them); only the counting is
  gone.
- **`grafana_ro` ships `NOLOGIN`.** `V017` creates it with no password (a
  migration cannot carry a secret) and grants `SELECT` on exactly the contract
  views, never the tables. Enable with
  `ALTER ROLE grafana_ro LOGIN PASSWORD '...'` over
  `docker compose exec postgres psql` (`docker_deployment.md`); `provision.sh`
  grants it `CONNECT` on both databases for both Grafana datasources. No
  compose file publishes a Postgres host port — tunnel-only.

## Plugin System
- **The entry-plugin seam has nothing registered, on purpose.**
  `ConvertLineBreaksPlugin` (the only `WeblogEntryPlugin` ever shipped,
  `roller.properties` `plugins.page`), the "Plugins to apply" card and
  `WeblogConfig.jsp`'s per-weblog default are deleted;
  `weblogentry.plugins`/`weblog.defaultplugins` drop via V021 (idempotent),
  discarding live `"ConvertLineBreaks"` data. `PluginManagerImpl`,
  `WeblogEntryPlugin` and `Weblog.getInitializedPlugins()` **stay** as the seam
  `ShortcodeExpander` runs through (`applyWeblogEntryPlugins`); it and
  `WeblogEntry.render()` apply every registered plugin unconditionally. See
  Entry editing.

## Shortcodes
`org.apache.roller.weblogger.business.shortcodes` — `ShortcodeExpander` expands
`[name attr="v"]body[/name]` **unconditionally** at both render seams
(`WeblogEntry.render()` and `PluginManagerImpl.applyWeblogEntryPlugins`),
immediately before sanitization.

- `[image id=".." caption=".." alt=".."]` emits a responsive
  `<figure><picture>` (the media chooser pastes it).
- `[gallery dir=".." row=".." max=".."]` renders a media directory as a
  justified grid (`GalleryMarkup`, flex-grow `--ar` CSS from
  `#showGalleryGridStyles`) with a PhotoSwipe lightbox (`#showGalleryAssets`;
  EXIF overlay, captions); refuses private directories.
- `[video url=".." caption=".."]` (YouTube/Vimeo) matches the url against a
  provider allowlist and never fetches; it emits an inert placeholder `<div>`
  (`HTMLSanitizer` strips iframes) and `#showEmbedAssets` click-injects the
  real `<iframe>` once a reader opts in — nothing from the provider before that
  except the placeholder's thumbnail `<img>` (e.g. `i.ytimg.com`). Each theme
  CSP carries the provider's `frame-src`, pinned byte-for-byte by three
  rendering tests (as `img-src * data:` is).
- `[contact]`/`[subscribe]` use the same placeholder-div pattern (an inert slot
  `<div>`, never a `<form>`; `#showAudienceAssets` injects the real form — see
  Audience). `[contact]` carries a server-built `data-endpoint`; `[subscribe]`
  carries `data-list-uuid` and renders nothing without a uuid-shaped list uuid.
- `[[name ...]]` / `[[/name]]` escape a registered shortcode to literal text;
  unknown names and malformed input pass through byte-for-byte.
- New handlers implement `ShortcodeHandler` and register in
  `defaultExpander()`; the required `ShortcodeCard` (label + snippet) feeds the
  editor's Insert menu, so a new shortcode cannot ship undiscoverable.

## Automation API
`org.apache.roller.weblogger.ui.restapi` (`/api/v1`) is a REST surface for
scripts and agents alongside the JSP admin UI. Reference and error contract:
`docs/api/README.md`, the front door, since no UI mints a token
(`bin/roller-api auth login` is the only route in). `/api/v1` is unstable while
Roller is 0.x.

- **The `/api` prefix is a servlet-spec prefix mapping, not part of any
  `@RequestMapping`.** `ServletRegistrationConfig.API_URL_PATTERNS` (`/api/*`)
  shares the `DispatcherServlet` with `*.rol`, the SEO patterns and
  `/newsletter/*`; Spring strips the matched prefix before routing, so
  controllers under `ui.restapi.v1` are written relative to `/v1/...`
  (`TokensApi` is `@RequestMapping("/v1/tokens")`, not `/api/v1/tokens`;
  `NewsletterController` likewise `/newsletter`, not `/newsletter/subscribe`).
  Getting it wrong compiles and 404s; no test catches it. `api` and
  `newsletter` are reserved roots in
  `rendering.weblogMapper.rollerProtectedUrls` so no weblog handle can shadow
  them.
- **The API and the JSP admin UI share exactly one authorization path:
  `RollerHandlerInterceptor`.** Every `*Api` controller implements
  `UISecurityEnforced` and declares `GlobalPermission`/`WeblogPermission` like
  `CategoryEditController`, `WeblogConfigController` and the rest; no API-side
  permission system exists. `ApiScopeInterceptor` is registered *after* it in
  `WebMvcConfig` (order is load-bearing, see its javadoc) and only narrows by
  token scope.
- **`ApiToken.Role` (`READ`/`POST`/`ADMIN`) and the optional weblog pin are a
  ceiling, never a grant.** `ApiScopeInterceptor` refuses what the scope
  disallows and never approves what `RollerHandlerInterceptor`'s
  `GlobalPermission`/`WeblogPermission` check would refuse: a `READ` token held
  by a `GlobalPermission.ADMIN` user cannot POST; a pinned token cannot act on
  another weblog its owner could edit.
- **`EntryFieldRules` and `WeblogOwnership`
  (`org.apache.roller.weblogger.ui.controllers`) are shared by JSP editor and
  API — one home each for entry-field rules and by-id ownership checks.**
  `EntryBean` and `EntryDtos.applyWrite` both call
  `EntryFieldRules.escapeTitle` and `EntryFieldRules.parsePubTime`
  (weblog-timezone), so the surfaces cannot drift. `WeblogOwnership` is the one
  IDOR defense for a by-id lookup (see Categories): the
  `BaseController.lookup*` family and
  `BaseApiController.requireEntry`/`CategoriesApi`/`PagesApi` all delegate to
  `WeblogOwnership.entry`/`category`/`template`/`page`.
- **A resource the caller may not see is 404, never 403**, in every `*Api`
  controller: a pin-excluded weblog, a foreign entry/category/media/page id and
  a missing id are indistinguishable — a 403 would leak that the resource
  exists. `ApiScopeInterceptor.checkWeblogScope` and every `requireX` helper in
  `BaseApiController` follow this.
- **The OpenAPI document is machine-readable, not a browser explorer.**
  `springdoc-openapi-starter-webmvc-api` serves `GET /api/v1/openapi.json`
  (`/v1/openapi.json` in `application.properties`, per the prefix point),
  scanning only `ui.restapi.v1` so the JSP surface never leaks. The springdoc
  UI is not a dependency; `springdoc.swagger-ui.enabled=false` is defence in
  depth. It sits behind `apiSecurityFilterChain` like all of `/api/**` (Basic
  or Bearer), not exempted like `GET /api/v1/ping`. `OpenApiDocumentTest` pins
  two `docs/api/README.md` claims the document cannot convey — v1 is unstable,
  and `roller-api auth login` is the bootstrap path.
- **`RollerHandlerInterceptor`'s `import ...ui.restapi.ApiException` is the ONE
  permitted import from `ui.restapi` into `ui.controllers`, and must stay the
  only one.** A second interceptor for `/api/**` would be exactly the parallel
  authorization path avoided here.
  `grep -r "import org.apache.roller.weblogger.ui.restapi" app/src/main/java/org/apache/roller/weblogger/ui/controllers/`
  must return exactly one line.
