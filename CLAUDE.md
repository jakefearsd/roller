# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Important Rules

- **Never commit or push unless explicitly asked.** Wait for the user to request a commit or push. Do not proactively create commits or push to remote.
- **Work directly on `master`.** This is a solo-developer repo; do not create a feature branch before committing unless explicitly asked.
- **Ship work; don't let agents sit idle.** The goal is finished, verified
  work, not a tidy queue. Default to dispatching the next piece rather than
  waiting for the current one to be fully wrapped up: reviews are read-only, so
  the next task's implementer runs alongside the previous task's reviewer.
  Prepare briefs and fix instructions ahead of time so a dispatch is instant
  when a slot frees. If you find yourself with nothing in flight, that is a
  planning failure, not a pause.
  **The one hard serialisation is the build.** Implementers share
  `app/target/`, so two concurrent `mvn -pl app test` runs clobber each other's
  classes and surefire output — never run two builds at once in the same
  working tree. Everything that does not build (reviews, briefs, rulings,
  ledger writes, greps) overlaps freely. To check whether a build is already
  running, the pattern must be **bracketed and scoped to this repo**:

  ```bash
  pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || echo CLEAR
  ```

  Both details are load-bearing, and getting either wrong has already cost a
  stalled agent here. An unbracketed `pgrep -f surefirebooter` matches the
  checking command's own command line — including a `while pgrep …; do sleep;
  done` loop, which then waits on itself forever (the same self-match that
  makes `pkill -f "spring-boot:run"` kill its own shell; use `[s]pring-boot`
  there too). An unscoped pattern matches builds in *other* checkouts on this
  machine, which are irrelevant and may run for an hour. Never poll for a lock
  as a separate background step and stop — inline the wait in the same command
  as the build, or you have traded a stall for a longer stall.
- **Parallelising implementers means git worktrees — and a worktree's base
  MUST be pinned and verified before you dispatch into it.** Worktrees are the
  right tool: each gets its own `app/target/` and its own git index, which
  dissolves both reasons the build is serialised above. But a worktree created
  without an explicit base can branch from a stale commit, and **that failure is
  silent in the worst possible way**: the agent's edits are correct against the
  code it was given, the merge applies cleanly enough, and the result quietly
  *reverts* whatever else changed in those files since the branch point. Nothing
  catches it — the reverted code compiles, the tests pass (they were passing
  before the reverted fix too), and the static-analysis gates pass (the reverted
  fix is not a violation, it is an improvement).

  This has already happened here. Two parallel logging-migration agents branched
  from a commit ~22 commits behind master; one overlapped master in **14 of its
  20 files** and the other's packages had **44 changed files**, all carrying a
  prior wave's charset, resource-handling, `volatile` and suppression fixes.
  Merging would have reverted them invisibly.

  So, before dispatching into a worktree:

  ```bash
  git -C <worktree> rev-parse --short HEAD        # is this the base you meant?

  # The check that actually matters: does the agent's scope overlap anything
  # that changed on master since that base? LC_ALL=C on BOTH sides is required
  # -- with a locale-sensitive sort, comm prints "input is not in sorted order"
  # and its answer is unreliable, which is worse than no check at all because
  # it still prints an empty (reassuring) result.
  git diff --name-only <base>..<branch> | LC_ALL=C sort > /tmp/a
  git diff --name-only <base>..master   | LC_ALL=C sort > /tmp/b
  LC_ALL=C comm -12 /tmp/a /tmp/b

  # Cross-check with git itself, which needs no sorting and is authoritative:
  git merge-tree --write-tree <branch> master >/dev/null && echo clean
  ```

  A non-empty overlap means a merge can revert real work. **Do not resolve that
  by cherry-picking or rebasing** when the overlap is large: conflict resolution
  then *is* the work, performed in the mode most likely to drop a line by
  accident, and a bad resolution reverts a fix silently. Discard and redo on the
  correct base — the discarded agent's *report* (write reports to the main
  checkout, never inside the worktree) survives as a checklist, so only the
  edits are lost, not the discovery.

  One corollary worth knowing: **a worktree on a stale base may not even contain
  the quality gates**, so its `mvn verify` is a far weaker check than it looks
  while reporting BUILD SUCCESS. That is how the case above was caught — an
  agent noticed the plugins were absent from its `pom.xml` and said so.
- **All development is test-driven.** Write the failing test first, run it and
  watch it fail for the reason you expect, then write the minimum code that
  makes it pass. A test that has never been seen to fail has not been shown to
  test anything — "I wrote the code and then added a test that passes" is not
  TDD and is the specific thing this rule exists to prevent. The order is not
  a formality: it is what makes the test a specification rather than a
  description of whatever the code happens to do.
- **Acceptance criteria live in the spec, not in the code review.** Any work
  large enough to get a spec under `docs/superpowers/specs/` states there what
  "done" means, in terms concrete enough to write a test against. A plan's
  tasks then derive their tests from those criteria. If a requirement cannot
  be phrased as something a test could check, it is not yet an acceptance
  criterion — sharpen it or drop it.
- **Characterisation tests are the exception that proves the rule.** When
  extracting or refactoring existing behaviour, the test is written first and
  is expected to pass immediately against the old code; it exists to prove the
  refactor changed nothing. Say so in the test's javadoc, so a later reader
  does not mistake a passing-on-arrival test for one that was written
  backwards.

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
# Access at http://localhost:8083 -- Roller serves from the ROOT
# (server.servlet.context-path=/, application.properties). The context path is
# a deployment detail and no code may assume one; set DEV_CONTEXT_PATH=/roller
# to reproduce a prefixed deployment locally. See "Context path" below.

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
  `ui.rendering.*`). Floors only ever move up. Raise them after each stage —
  **but "raise" means "raise where there is slack", not "raise all three".**
  Post-W2 the BUNDLE LINE floor is 0.8700 against a measured 0.8705, i.e. it
  is already binding to within a rounding error, and pushing it higher just
  means an unrelated change fails the build for the one uncovered line it
  happened to add. It was therefore left alone while BRANCH went 0.7800 →
  0.7900 and the `ui.rendering.*` PACKAGE LINE rule went 0.55 → 0.60 (binding
  package: `velocity`, 0.6190). Coverage of *new* work is the diff gate's job
  below, not this floor's.
- Changed lines need ~90% coverage: `bin/check-diff-coverage.sh [base-ref]`
  (default `HEAD~1`; needs `pip install diff_cover` and a fresh
  `mvn -pl app jacoco:report`). CI enforces this on every push/PR.
- Browser ITs run in CI (`mvn verify -Pit`) — see `it-selenium/`, and CI
  below for *when*.

### Static-analysis gates

PMD, CPD and SpotBugs run at `verify` in the `app` module and fail the build
on **any** violation. Config: `config/pmd/ruleset.xml`,
`config/spotbugs/exclude.xml`; wiring in the parent `pluginManagement`,
executions in `app/pom.xml`. `bin/quality-report.sh` prints current counts and
sites; `bin/quality-report.sh <RuleName>` lists one rule's sites.

The tree started this wave at 362 PMD / 134 SpotBugs / 4 CPD violations (=
500) against this exact ruleset/filter, whittled down batch by batch across
Tasks 1–12b to zero, at which point the temporary `pmd.max.violations` /
`spotbugs.max.violations` ceiling properties and the `maxAllowedViolations`
wiring that carried the wave were deleted — a gate with `maxAllowedViolations
=0` behaves identically to one with none, so once the tree held at zero the
scaffolding had no job left. **The PMD count is measured against PMD 7.26.0,
and pinning that version is load-bearing, not incidental.** The maven-pmd-
plugin's own bundled PMD is 7.17; an earlier design-time probe run against
that bundled version measured **307** PMD violations on the same source and
ruleset, against **362** under the pinned 7.26.0 — a +55 delta with no rule
or source file touched in between. The single biggest contributor is
`CloseResource`, which goes 13 -> 42 as that detector improved between the
two PMD releases; the rest comes from five rules that did not exist in 7.17
at all (`OverrideBothEqualsAndHashCodeOnComparable`,
`LambdaCanBeMethodReference`, `UseStandardCharsets`,
`AvoidInstanceofChecksInCatchClause`, `AvoidCatchingGenericException`). An
unpinned plugin drifts the gate's count *silently* whenever the plugin's
bundled PMD version moves — a violation appears or disappears on a routine
plugin bump with nobody having touched a rule or a source file. `pom.xml`'s
explicit `pmd-core`/`pmd-java` `7.26.0` dependency override in the parent
`pluginManagement` is what fixes the version the gate actually measures
against.

Zero tolerance is only affordable because the rule set is narrow, and it is
narrow on a stated principle: **a rule is excluded only when violating it is
systematically not a defect in this architecture**, never because there are a
lot of them. `UnnecessaryConstructor` is the clearest case — JPA entities are
required to declare a no-arg constructor, so the rule is wrong here, not noisy.
PMD's `codestyle` category (7,997 violations of pure format opinion) is not
used at all.

Six PMD rules and three SpotBugs families (469 of 603 raw SpotBugs findings,
zero tolerance on the remaining 134) are excluded, each with a reason comment
in the config file. **`QualityGatePomTest` fails the build if an exclusion is
added without a justification comment, or if the excluded set differs from
the list the test names — for both PMD's six rules and SpotBugs's three
families — so silencing a rule is never a quiet act, and widening either
exclusion set is a spec change rather than an implementation decision.** A
rule name outside `PERMITTED_PMD_EXCLUSIONS`, or a `<Match>` block beyond the
three SpotBugs families (or a bug pattern outside
`PERMITTED_SPOTBUGS_PATTERNS`), fails the same way a seventh PMD rule or a
fourth SpotBugs family would.

**The JCL→SLF4J migration that used to sit behind two deferred exclusions is
done, and it changed the two rules' outcomes differently — one genuinely
fixed, one re-founded, neither still "deferred."** `GuardLogStatement` and
`ProperLogger` used to be excluded pending the migration — 178 main-source
files plus 17 test files, ~797 call sites, all now on `org.slf4j` with
parameterized `{}` logging throughout (6 `log.fatal` calls mapped to
`log.error`, since SLF4J has no fatal level). **Parameterized logging is
*why* the guards became unnecessary, not a coincidence:** SLF4J only builds
the formatted message when the level is enabled, so `if
(log.isDebugEnabled()) { log.debug("x " + y); }` around a parameterized
`log.debug("x {}", y)` call is pure ceremony — every hand-written guard whose
only job was gating string concatenation was deleted as part of the
migration, batch by batch.

`ProperLogger` came back genuinely clean — 167 → 0 — confirming the
field/import shape every batch standardized on, and it is active now with no
exclusion. `GuardLogStatement` did not come back clean: reactivating it
flagged **175 violations at first activation**, not the ~0 predicted, and
understanding why matters more than the number. PMD flags *any* non-trivial
argument expression (a method call, a ternary — not just string
concatenation) as a call the rule can't statically prove is cheap, and the
overwhelming majority of the 175 were the ordinary idiomatic shape this
migration standardized on — a `{}` fed by a cheap accessor
(`entry.getId()`, `weblog.getHandle()`). Three sites were genuine waste (an
eagerly-built `StringBuilder`/`MessageFormat.format()` result handed to
`log.info(String)`, and two `stream().collect()` calls at startup) and were
fixed properly. The other 172 were first suppressed with
`@SuppressWarnings("PMD.GuardLogStatement")` at the class declaration across
74 classes — a reasonable first cut, but overruled: a class-level suppression
is *broader* than a ruleset exclusion for those classes (it silences the rule
for all current **and future** code there too, not just today's cheap
accessors), 172 sites across 74 classes is one policy applied 74 times rather
than 74 individual one-off judgements, and this repo's own exclusion policy —
the config files are for whole families, site-level suppressions are for
one-offs with a stated reason — says so directly. `GuardLogStatement` is
excluded in `config/pmd/ruleset.xml` instead, permanently: not because a
migration is pending (it already happened), but because with parameterized
SLF4J the rule cannot distinguish a cheap accessor from expensive work and
fires on idiomatic, correct code — violating it is systematically not a
defect in this architecture, which is exactly what the exclusion principle
above requires. See
`docs/superpowers/specs/2026-08-18-static-analysis-quality-gates-design.md`'s
Follow-up section for the full accounting.

**The one thing a future contributor must know, now that every logging
call is on SLF4J's varargs form: a `Throwable` must stay the LAST argument,
and no `{}` may consume it.** `log.error("x {}", a, e)` preserves the stack
trace; `log.error("x {} {}", a, e)` binds `e` to the second placeholder via
`String.valueOf(e)` instead and the trace is silently gone. This was the
single most common pre-existing bug the migration surfaced — JCL's
`String`/`Object` single-argument overloads (`log.error(ex)`,
`log.warn("msg " + ex)`) that stringify an exception with no trace at all —
and it is exactly as easy to reintroduce by hand as it was to inherit from
JCL. There is no compiler check for it; read the argument list.

CPD runs at **200 tokens**. Two of the four blocks it flags at that threshold
already are the render caches (the other two are the entry pagers, genuinely
extractable) — the `CPD-OFF` markers around them are load-bearing at 200, not
a precaution against some lower setting. Collapsing the caches into a shared
base would be a behavioural change — `WeblogPageCache` has no CacheHandler and
expires only against `weblog.lastModified` while its siblings are invalidated
through `CacheManager`. The threshold stays at 200 rather than dropping to 100
for a separate reason: 100 finds twenty blocks, not zero, and the gate starts
demanding refactors whose risk exceeds the duplication's cost.

One-off suppressions go at the call site (`@SuppressWarnings("PMD.Rule")`,
`@SuppressFBWarnings`, `// CPD-OFF`) **with a reason**; the two config files
are for whole families only. A suppression whose justification only restates
the rule name is not a justification. **A genuinely repeating pattern within
one class** may carry the suppression on the class declaration instead of on
every call site; that is still "at the site" in the sense that matters (the
one class where the pattern recurs, not a ruleset-wide blanket), it just
isn't one-off. But a pattern repeating across *many* classes is neither —
`GuardLogStatement` above is the example of where that line sits: 74 classes
sharing one reason is a family, and belongs in the config file rather than as
74 copies of the same class-level annotation.

Proof the gate actually bites, not just that it is wired: seeding one
violation per tool (an unused import for PMD, an unchecked
`String.getBytes()` for SpotBugs's `DM_DEFAULT_ENCODING`, a 200+-token method
copied into a second class for CPD) each turned `mvn -pl app verify` into a
`BUILD FAILURE` naming the offending file, line and rule — see
`docs/superpowers/specs/2026-08-18-static-analysis-quality-gates-design.md`
for the design this proof validates. Measured cost: PMD+CPD+SpotBugs add
roughly 10 seconds to a warm `verify` (comfortably inside the 30-second
budget); a cold run recompiling everything runs the full `verify` in under 20
seconds total.

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

**Publishing happens only on a `v*.*.*` tag** (`release.yml`): it builds and
pushes both `ghcr.io/jakefearsd/roller:<version>` and
`ghcr.io/jakefearsd/roller-caddy:<version>` (also `:latest` and `:sha-<short>`
for each), extracts the release WAR from the built `roller` image, and cuts a
GitHub Release with the WAR plus the deploy bundle
(`docker-compose.prod.yml`, `.env.example`, `deploy.sh`) attached. Pushing to
master publishes nothing. `docker-compose.prod.yml` requires `IMAGE_VERSION`
to be set in `.env` — there is no floating default — so a deploy always names
a specific tagged release rather than silently picking up whatever was last
published. Testing an untagged tree locally is a `docker build` against the
`Dockerfile`/`deploy/caddy/Dockerfile` directly (see docker_deployment.md's
"Test a release locally before deploying it"), not a `deploy.sh` flag —
`deploy.sh` only ever pulls, since `docker-compose.prod.yml` carries no
`build:` stanza.

**Known flake: `ReferenceError: EasyMDE is not defined`** on
`entryEdit!firstSave.rol`, surfacing through `BrowserHealth`'s
uncaught-exception check (seen in `MediaCropIT`). `head.jsp` loads
`easymde.min.js` synchronously and unconditionally — line 45, *outside* the
`<c:if>` that gates Font Awesome — so this is the page's inline initialiser
racing a script that had not finished executing, not a missing include. Green
on rerun. Before assuming flake, confirm the `<script>` is still unconditional:
if someone widens that `<c:if>` to cover EasyMDE, the identical error becomes a
real breakage on every screen the condition excludes.

### The IT harness cleans up by identity, not by pidfile

Every process, file, directory and container an IT run creates carries that
run's id (`${it.run.id}` = build timestamp + the reserved HTTP port). The app
JVM carries it on its own command line as `-Droller.it.run=<id>`, alongside
`-Droller.it.owner=<pid>@<start time>` naming the Maven build that owns it.
That is what makes cleanup work when nothing else survives, and it replaced a
scheme that leaked an app JVM (~840 MB each), a chromedriver and a Docker
volume on every abnormal run, silently — `build-helper:reserve-network-port`
hands out a *free* port each time, so leaked servers never collided and no
build ever failed because of one. Four app JVMs and thirteen chromedrivers
accumulated that way before anyone looked.

Five things hold it together, all in `it-selenium/src/test/script/`:

- **`start-app.sh` kills what it started on every failure path** (`trap
  cleanup EXIT`, plus INT/TERM turned into exits). Its readiness timeout used
  to `exit 1` with the JVM still running, and because it runs in
  `pre-integration-test`, that exit aborts the build *before*
  `post-integration-test` — so `app-stop` never ran. Guaranteed permanent leak.
- **`sweep-stale.sh` runs first in `pre-integration-test`** and reaps what
  earlier runs left, **reporting every pid and container it takes**. Silence
  was the actual defect; the reaping only bounds it.
- **`supervise-run.sh` starts before `pg-start`, detaches, and outlives the
  build.** Failsafe's split-goal design already protects cleanup from *test*
  failures; what it cannot protect against is an *infrastructure* failure
  earlier in `pre-integration-test` (`pg-wait-ready`, `migrate.sh`, the seed,
  `app-start`) or a Ctrl-C, both of which skip `post-integration-test`
  entirely. The supervisor kills the run's app, the chromedrivers it recorded
  while their JVM was alive (they are unattributable afterwards), and the
  container — `docker rm -f -v`, always with `-v`.
- **Staleness is decided by the owning build, never by "an IT process
  exists".** A concurrent `mvn verify -Pit` is legitimate — the random port
  reservation is what makes it possible — and its processes must survive
  another run's sweep.
- **The supervisor marks itself `-Droller.it.supervisor=`, not
  `-Droller.it.run=`, and the split is load-bearing.** A forked shell subshell
  (command substitution, either side of a pipeline) shows its *parent's*
  command line in `ps`, so a supervisor marked as a member of its own run
  finds its own subshells while enumerating what to kill, and kills those.
  Observed, not theoretical; `ItHarnessLeakTest` pins it.

Consequences worth knowing: the container is `roller-it-postgres-<run id>`, so
two runs no longer collide on one fixed name (the 409 that used to need a
manual `docker rm`), `pg-stop` passes `<removeVolumes>true</removeVolumes>`
(its default of false orphaned an anonymous volume on *successful* runs too,
since `postgres:16` declares `VOLUME /var/lib/postgresql/data`), and pidfiles,
logs and the search/media work dirs are per-run — a shared `app.log` and a
shared `app.pid` were truncated by the next run, destroying exactly the
evidence of the run that had just leaked. `it-work/app-latest.log` symlinks
the newest.

`RollerPostgresContainer` (the unit suite's shared container, which is
deliberately never stopped) now also registers a JVM shutdown hook as defence
in depth *behind* Ryuk, not instead of it: Ryuk is one process that can be
missing, and SIGKILL still leaves it as the only line of defence.

Tests: `ItHarnessLeakTest` (behavioural — drives the real scripts against fake
processes), `ItHarnessPomTest` (the wiring: `removeVolumes`, per-run names,
and the plugin declaration order that schedules `app-stop` before `pg-stop`),
`RollerPostgresContainerTest`. All three are in the fast suite, not under
`-Pit`, so the harness is covered on every push.

### Database

Roller is **PostgreSQL-only**. Development, test, and production all
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

Roller is a multi-user blog server built with:
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
- **Alt text (W4): `MediaFile.altText`, and the chain is not the obvious one.**
  Before W4 every alt attribute this system emitted fell back to
  `MediaFile.getName()` — the uploaded **filename** — so a screen reader
  announced "IMG_4821.jpg" down a whole portfolio page. The chain now is:
  - `ImageShortcode`: an `alt` attribute **present** on the shortcode wins
    verbatim *including empty* — `[image id=".." alt=""]` is the standard way
    to say "decorative" and falling through would defeat it. Only an **absent**
    attribute falls through to `altText`, then to the filename.
  - `GalleryMarkup`: `altText` → filename. A gallery has no per-image
    attribute to carry an override.
  - At the `altText` link, **blank counts as absent** at both sites (an author
    who clears the edit field left `""` behind and did not thereby declare the
    image decorative; the field has no way to express that distinction). This
    is deliberately different from the shortcode-attribute link above, and
    looks inconsistent until you know why.
  - `firstNonBlank` returns `""`, never null. It briefly returned null and
    `GalleryMarkup` appended it straight into the attribute, rendering the
    literal `alt="null"` when an image had blank alt text *and* a blank name.
- **The filename is still the last fallback rather than `alt=""`.** An empty
  alt asserts "decorative", which is the wrong claim about a photograph, and it
  would hide undescribed images from the marker instead of surfacing them.
- **`MediaFileView.jsp` renders the "no alt text" marker from TWO `c:forEach`
  loops** (`childFiles` and `pager.items`). `childFiles` is the ordinary,
  unpaged folder-browse view and is what an author sees on every normal visit
  to a directory; `MediaFileViewController` sets `pager` only in
  `mediaFileView!search.rol`, so `pager.items` renders only after a search.
  Both are real screens an author's eyes land on, so a marker added to only
  one is invisible exactly when it matters — but `childFiles` is the one
  worth remembering as "primary", not `pager.items`. Its gate uses `fn:trim` so that
  whitespace-only alt text counts as missing — matching the renderer's
  `isNotBlank`, not EL's `empty`, which would show such an image as described
  while every page rendered the filename.
- **Alt text is deliberately absent from the upload form.** It is per-image,
  and one shared box across a thirty-file batch writes thirty wrong
  descriptions that the marker then reports as done.
- **`#showResponsiveImage`'s `$alt` stays caller-supplied.** The theme callers
  pass `$entry.title` for a featured image, which is right in a card context
  and is not the file's own description of itself. Do not "unify" it.
- **Bulk upload (W4) was a form change only.** `MediaFileAddController.save`
  always bound `MultipartFile[]` and looped; the five-file ceiling was five
  `<input type="file">` elements in the JSP. One `multiple` input plus a drop
  zone now; `spring.servlet.multipart.max-request-size` is 1GB. The add form's
  Name field went with them — it was inert, overwritten by the uploaded
  filename immediately after `bean.copyTo`.
  **A batch is not a transaction**: quota and forbidden-extension refusals are
  reported per file by `createMediaFile` *without throwing*, so the controller
  snapshots `RollerMessages.getErrorCount()` around each call rather than
  trusting the absence of an exception, and a partly-failed batch shows both
  what landed and what did not on one page. Before W4 a single bad file
  suppressed the entire success list.
- **`MediaFile.sharedForGallery` / `roller_mediafile.is_public` are gone**
  (`V024`), finishing what W2 started when it deleted the last reader.
- `EntryAddWithMediaFileController` ("create an entry from these files") now
  seeds the draft with `[image id=".."]` instead of hand-built `<img>` markup,
  so it picks up alt text and the rendition ladder through the normal path.
  It was the fourth alt-emission site and the only one still bypassing the
  shortcode.

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
- **Production**: containerized end-to-end and **image-only** — the deploy host
  holds `docker-compose.prod.yml` and `.env` and nothing else. Two images ship
  per release tag: `ghcr.io/jakefearsd/roller` (WAR, themes, migrations,
  `provision.sh`, `analytics-views.sh`, `umami-views.sql`, `migrate.sh`, the
  backup scripts, and a PostgreSQL client) and `ghcr.io/jakefearsd/roller-caddy`
  (Caddy with the Caddyfile baked in). A one-shot `provision` service creates
  the umami and listmonk databases, applies the migration chain, and grants
  `grafana_ro`; `app`, `umami` and `listmonk` declare
  `depends_on: { provision: { condition: service_completed_successfully } }`,
  so the migrate-then-start ordering is compose's job, not a bash script's.
  The analytics view (`analytics_traffic`) is installed by a **separate**
  one-shot, `analytics-views` (`analytics-views.sh`), that runs after `umami`
  has started and gates nothing — `analytics_traffic` is defined over
  Umami's own `website_event` table, which does not exist until Umami's own
  first-boot migrations create it, and `provision` runs entirely *before*
  `umami` is allowed to start, so installing the view from `provision` was a
  real bug (a fresh install deadlocked: neither service could go first) and
  not a design that can be un-simplified back into one script. `deploy/deploy.sh`
  is now just pull/up/wait. **Nothing may be bind-mounted from a checkout** —
  `ProductionComposeTest` fails the build if a bind mount, a `build:` stanza,
  or a non-loopback published port other than 80/443 reappears. Full runbook:
  `docker_deployment.md`.

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
- **The Design tab is reachable by default; `themes.customtheme.allowed`
  (default `false`) gates only two of its three items.** `editor-menu.xml`
  used to gate the whole `tabbedmenu.design` group on the flag, which made
  theme *selection* — safe and reversible — invisible on every default
  install alongside theme *customisation* — one-way and rightly gated.
  `themeEdit` (picking among the shared themes) is now ungated; only
  `stylesheetEdit` and `templates` (which only matter once a weblog is
  customised) still carry `enabledProperty="themes.customtheme.allowed"`.
  `MainMenu.jsp`'s theme button is split the same way: the shared-theme link
  is unconditional, the custom-theme link stays behind the flag.
- **`themes.customtheme.allowed` is enforced in `ThemeEditController`, not just
  in the menu.** A hidden menu entry stops nobody who posts to
  `themeEdit!save.rol` directly, so a POST there whatever the setting said
  used to convert the weblog regardless — a one-way conversion behind a
  hidden menu entry. `ThemeEditController.save`'s shared-theme branch has
  never called this check at all (shared-theme selection was never gated
  server-side); only the `WeblogTheme.CUSTOM` branch does. A weblog already
  on a custom theme is grandfathered (turning the option off stops new
  customisations; it must not strand a weblog that has no way back).
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
- **Environment (`ROLLER_*` variables).** The highest-precedence layer, applied
  by `WebloggerConfig.applyEnvironmentOverrides` over everything the properties
  files set: strip the `ROLLER_` prefix, lowercase, turn `_` into `.`. A derived
  name matching an existing key case-insensitively writes to that key's original
  spelling, so `ROLLER_DATABASE_JDBC_DRIVERCLASS` reaches
  `database.jdbc.driverClass` rather than creating a lowercase twin nothing
  reads; a name matching nothing is used as derived, which is required rather
  than incidental (`mail.port` has no entry in `roller.properties` and
  `uploads.dir` is commented out). This is how the production image is
  configured — there is no properties file in it.

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
- `weblogAdminsUntrusted` — promoting it would put "disable HTML sanitization"
  on a web form. Security invariants stay at boot scope.
  (`passwds.encryption.enabled` used to sit here too. It is not merely
  un-promotable now — the property no longer exists at any layer; see
  Passwords below.)
- `rememberme.enabled`, `themes.reload.mode`, `users.firstUserAdmin` —
  structurally boot-scoped (filter chain, Velocity engine config, first-user
  bootstrap).
- `search.enabled` — gates whether a Lucene index is built at all, so making it
  hot would mean either always paying for the index or serving search over one
  that does not exist.

## Passwords
- **Password encryption is not configurable at all any more, and cannot be
  turned off.** Four paths could once put plaintext in
  `roller_user.passphrase`, and the dev server used one of them by default:
  `passwds.encryption.enabled=false` (which flipped the
  `DelegatingPasswordEncoder`'s *encoding* id to `noop`), the unconditional
  `noop` encoder registration (so a `{noop}` value authenticated however the
  flag was set), `lazyUpgradeFrom=plaintext` (a no-op encoder on the **null**
  prefix, so an *unprefixed* stored string authenticated), and the
  `enabled=false` lines in `roller-boot-dev.properties` /
  `roller-custom.properties`. All four are gone. Only
  `passwds.encryption.algorithm` remains (bcrypt/pbkdf2/scrypt/argon2). An
  explicitly-set `passwds.encryption.enabled` — by file or by
  `ROLLER_PASSWDS_ENCRYPTION_ENABLED` — **throws at startup** rather than being
  ignored, the same convention as an unsupported `authentication.method`; a
  deploy must not boot looking configured while behaving otherwise.
  `PasswordEncodingTest` fails if any of this regresses. A `{noop}` row does
  not merely fail to match now — Spring refuses the unknown id outright.
- **`TestUtils.setupUser` stores a precomputed `{bcrypt}` constant**
  (`TestUtils.TEST_PASSWORD` / `TEST_PASSWORD_HASH`), not a live `encode()`
  call: bcrypt is deliberately slow and there are ~106 call sites. It used to
  write the bare string `"password"` through the raw setter, so fixture users
  could never authenticate at all.
- **The dev admin credential lives in `.roller-dev-secret`** — git-ignored,
  generated with `umask 077` on the first `./roller db|dev|reset` and printed
  once. `bin/db/seed-dev-data.sql` applies it, hashing with **pgcrypto inside
  Postgres** so no host bcrypt tool is needed. It is **not** under
  `bin/db/migrations/` and so never reaches production. The file is the source
  of truth: a password changed through the web UI is reverted by the next seed,
  deliberately.
- **The seed's `ON CONFLICT` guard must stay a `CASE`.** `crypt()` raises
  `ERROR: invalid salt` on a second argument that is not a usable salt, and
  PostgreSQL does **not** guarantee short-circuit evaluation inside `OR` — so
  an `OR` chain aborts the seed on exactly the `{noop}`/truncated row it exists
  to repair. `DevSeedTest` runs the *shipped file* over every row shape and
  catches the flattened form; an earlier version reimplemented the guard inline
  and happily passed against the broken SQL.
- **`./roller token`** mints an API token for that admin via
  `roller-api auth login --password-stdin`. Manual API testing is `./roller
  dev`, `./roller token`, call endpoints. In `./roller`, never generate the
  secret with `tr -dc … </dev/urandom | head -c N`: under `set -o pipefail`
  `head` exiting early SIGPIPEs `tr` and the script dies at exit 141 with no
  message.

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
- `GlobalConfigMatrixIT` — three tests that mutate site-wide state:
  the feature-refusal switch (uploads/weblog-creation off, batched together),
  `groupblogging.enabled`'s own refusal (a user who already owns a weblog is
  refused a second one — isolated in its own test rather than batched with
  the other two, so the assertion cannot pass for the wrong reason; it used
  to be batched with the other two and asserted against `invite.rol`, which
  W2 deleted along with the rest of the invite/accept ceremony —
  `MembersController.grant()` now adds a first-time collaborator directly,
  no invitation or acceptance step, and has no `groupblogging.enabled` check
  of its own; only the menu entry is gated by it), and the entry-URL
  word-separator setting. It used to
  carry three more tests covering the site-wide comment switches
  (off-at-the-servlet, moderation, HTML-escaping); those and the
  `postCommentDirectly` helper were deleted in W1 along with the comment
  subsystem they exercised — the class still mutates global state.
  **Not actually the only class that does**, despite an earlier version of
  this paragraph's claim: `ThemeIT` (`CUSTOM_THEMES_ALLOWED`, three tests)
  and `ThemeMatrixIT` (`uploads.enabled`, one test) both call
  `setGlobalFlag` too. Worth knowing for a future parallelisation decision —
  `GlobalConfigMatrixIT` is not the one class that would need serialising,
  it is one of at least three.
- `ScheduledEntryIT` — a future-dated entry is withheld from pages, its Atom
  feed, and the sitemap.

One setting has **no reachable browser coverage**, documented in place
rather than silently skipped:
- `user.hideUserNames` — every bundled theme and feed uses
  `$entry.creator.screenName`, never `.userName`, so the flag changes nothing
  in shipped output.

(Per-weblog `analyticsCode` used to be here too, uncoverable because its
textarea only rendered with `weblogAdminsUntrusted` off, which this fork
never does. W2 deleted the field, its form, and its column outright — see
Analytics below — so there is nothing left to be uncovered.)

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
  `<comments>$url.comments(...)</comments>`, back when RSS feeds still
  existed — the RSS format itself, and the search feed, are gone now (W2);
  every weblog and site feed is Atom only). The mitigation is manual and has
  to stay manual: any change that deletes a Java member
  reachable from a template must `grep` `app/src/main/webapp/themes` and
  `app/src/main/webapp/WEB-INF/velocity` for it before calling the change
  done. This is not comment-specific — it applies to every future deletion
  that touches a pojo, wrapper, or model class a `.vm` file can reach.

## Admin UI
- **Maintenance is a Global Admin screen, not a per-weblog one.**
  `MaintenanceController`/`Maintenance.jsp` live under
  `ui/controllers/admin`/`jsps/admin`, served at
  `/roller-ui/admin/maintenance.rol`, and are listed in `admin-menu.xml`
  (`globalPerms="admin"`) alongside Global Config and User Admin — not in
  `editor-menu.xml`. The three actions (flush cache, rebuild search index,
  regenerate media renditions) are still per-weblog, so the page carries an
  explicit weblog `<select>` (the operator picks a weblog; the old editor
  version got its weblog from the per-weblog action context for free).
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
  `RollerViewResolver`, which registers eight base layouts
  (`.tiles-mainmenupage`, `.tiles-tabbedpage`, `.tiles-simplepage`,
  `.tiles-loginpage`, `.tiles-installpage`, `.tiles-errorpage`,
  `.tiles-popuppage`, `.tiles-barepage`) in `init()`. `.tiles-barepage` is
  chrome-free — content, no head — and exists for exactly one consumer,
  `.MediaFileEditSuccess`: that document only calls `parent.onEditSuccess()`
  and is destroyed by the parent's re-submit milliseconds later, so rendering
  it through the full admin head made the browser abort its webfont fetch
  mid-flight on every media rename (the `GalleryIT` "font ERR_ABORTED" flake).
  It is a fix, not a spare layout — do not reuse it for a page a human reads. `tiles-tabbedpage.jsp`/
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
  it is not a static resource despite the `.js` extension. `UserAdmin.jsp`'s
  only includer now — `MembersInvite.jsp`, the other JSP this file used to be
  shared with, was deleted along with the rest of the invite/accept ceremony
  (`MembersController.grant()` above adds a first-time collaborator directly
  instead); the file's own `// Used in: UserAdmin.jsp` comment already
  reflects this.
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
- The Blogger XML-RPC API (and `weblog.bloggercatid`, its default posting
  category) is gone (W2) — dropped in `V023__drop_w2_fossils.sql`. It used to
  be a raw id with no cascade, so deleting the category it pointed at left a
  weblog's settings unsaveable ("Error updating configuration", forever, with
  no way back through the UI); `removeWeblogCategory` no longer has anything
  like that to null out. `CategoryIT.deletingACategoryLeavesTheWeblogSaveable`
  keeps covering the general shape (delete a category, confirm the settings
  page still saves) even though the specific bug it used to guard cannot
  happen anymore.

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
to write comment content into `C_CONTENT` with `Field.Store.NO` — indexed for
matching but never stored — and `SearchOperation.SEARCH_FIELDS` was
`{CONTENT, TITLE, C_CONTENT}`, so site search really did match text a reader
had posted in a comment. This wave removed both halves: nothing writes
`C_CONTENT` any more, and `SEARCH_FIELDS` is now `{CONTENT, TITLE}`. Because
the query no longer names that field, comment text sitting in an index built
before the wave is already unreachable — a rebuild is not needed to stop it
being searchable.
What an index built before this wave does still hold is commenter name and
email, written to `C_NAME`/`C_EMAIL` with `Field.Store.YES` — those are
physically present in the index files on disk until the affected entry's
document is replaced, whether by a full rebuild from the Maintenance page or
incidentally as entries get re-saved one at a time. That's a minor PII residue
in a local index file worth clearing, not a search-correctness bug.

## Entry editing
- **Layout**: `EntryEdit.jsp` (the approved card is committed at
  `docs/design/editor/editor-writing-surface.html`) is a writing surface plus
  a 252px publish rail, not a top-to-bottom form. The main column carries
  only title (large serif, borderless — **emphasis elsewhere is weight, never
  size**, the rule the whole card follows), the permalink as a mono line with
  a copy control, and the untouched `EntryEditor.jsp` include. Everything
  about *managing* the entry — not writing it — lives in the rail: a Publish
  box (status pill, the one visible time field, the submit buttons), an
  Organize box (category/tags — locale is carried as a hidden input, not a
  visible Organize control), an SEO drawer (the SEO & Social
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
  editor's own API. Byte-untouched by the rail rebuild. Autosave (below) is
  the seam's fourth consumer and reaches the editor only through
  `rollerGetEntryText`/`rollerSetEntryText`, so an editor swap carries draft
  recovery with it for free.
- **Autosave is LOCAL ONLY and there is no server endpoint** —
  `theme/scripts/roller-draft.js` writing to `localStorage`, installed from
  `EntryEditor.jsp` and `PageEdit.jsp`. Not a design accident: a server-side
  autosave would multiply `weblogentry_revision` rows by the autosave rate
  against `entry.revisions.retention`'s default of **-1, keep everything**
  (see Revisions below), and would have to invent a real entry row for
  anything anyone started typing. The work that actually gets lost — tab
  crash, wrong tab closed, a session that expired while the laptop slept and
  turned the save POST into a login redirect — is all lost in the browser it
  was typed in, which is exactly what `localStorage` covers.
  Five things about it that are load-bearing:
  - **Recovery is decided by comparing content, never timestamps.** A
    timestamp check against `entry.updateTime` has to reconcile a browser
    clock, a server clock and the weblog's timezone, and is wrong when any of
    the three drift. A snapshot matching what the server just rendered is one
    whose save went through, and is dropped silently.
  - **`staleKeys` are compared on the editor text ALONE**, unlike the primary
    key's whole-form comparison, and this asymmetry is not laziness. Saving a
    new entry redirects to `entryEdit`, where `EntryBean.copyFrom` has
    populated `bean.status` and `bean.pubTimeLocal` that were empty on the add
    form — so a whole-form comparison never matches, the `entryAdd:new`
    snapshot survives its own save, and the next author to open a blank editor
    is handed the previous entry's text. `EntryAutosaveIT` caught this; no unit
    test could, because the divergence only appears after a real save has
    round-tripped through the controller. The consumption test is text **and**
    title, not text alone: copying an entry's body into a new-entry tab leaves
    a snapshot whose text matches that entry exactly, and reloading the entry's
    own tab would otherwise delete the new draft.
  - **The field denylist is by NAME, not by `type="hidden"`.**
    `bean.featuredImageId`/`bean.ogImageId` are hidden inputs carrying real
    author choices from the image pickers. `bean.status` is deliberately *not*
    excluded — it is a visible `<select>` on the page editor, and on the entry
    editor the submit buttons' `formaction` decides status regardless. One
    consequence worth knowing: on a **page**, `PageBean.copyTo` writes the
    submitted status straight through, so clicking Restore can put a stored
    DRAFT/PUBLISHED choice back into that select. That is correct — it was the
    author's unsaved selection, and the bar says "unsaved changes", not
    "unsaved text" — but it is the one place in the wave where Restore changes
    something other than prose.
  - **Submit saves the snapshot rather than clearing it**, so an expired
    session that redirects to login does not take the text with it. The
    snapshot is dropped on the next load instead, by the content comparison.
  - **Restore re-saves rather than dropping**, so the recovered text is not
    left with no backup at all; and it never auto-restores, because silently
    overwriting the server's copy with an unreviewed snapshot is worse than
    losing the snapshot.
  Two limits recorded rather than fixed: two tabs on the same *new* entry share
  the one `…:new` slot (the later debounce wins), and a draft outlives logout —
  up to 30 days in that browser profile, so on a shared machine the next person
  permitted on the weblog is offered the previous author's unsaved text. Both
  are in the spec's "Known limits".
  The leave-warning both editors carry is bound **once** against a dirty flag,
  under the `beforeunload.rollerLeaveWarning` namespace. It used to register a
  fresh `beforeunload` *and* `submit` handler inside the CodeMirror `change`
  callback — one pair per keystroke, on the form about to be posted. It stays
  even though drafts now survive: a snapshot is a recovery mechanism, not a
  reason to stop telling someone they are walking away from unsaved work.
- **Preview** is rendered server-side (`entryEdit!preview.rol`) because only the
  server can expand shortcodes; a browser-side Markdown library would disagree
  with the published page.
- **List actions**: `Entries.jsp` is ONE form around the table — bulk
  checkboxes, per-row duplicate, and the action bar all post through it, so the
  duplicate control is a submit button carrying `name="duplicateId"` rather than
  a nested form. Every bulk action loops per id through `BaseController`'s
  `lookupEntry`, and delete goes through `trashEntryWithIndex` so the Lucene
  index cannot be orphaned. **Delete moves an entry to the trash rather than
  removing it** as of W5 — see Trash below; the flash copy says so, because the
  whole value of the feature is that the author knows the entry is recoverable.
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

## Trash (soft delete, W5)

Deleting an entry from the authoring UI moves it to a trash it can be restored
from. Everything about the design exists to keep the new dimension from
spreading — the program's own notes flagged soft delete as the one item that
makes the system bigger, and sequenced it last so it would be the cheapest
thing to drop.

- **It is a fifth `PubStatus` value (`TRASHED`), not a `deleted_at` column**,
  and that is the whole trick. Every query that names a status excludes trash
  **by construction** — nobody asks for `TRASHED`, so nobody gets it. A
  `deleted_at IS NULL` condition would instead have to be remembered in seven
  named queries, a dynamic query builder and 23 call sites, and it fails
  **open**: forget it once and deleted entries reappear on a public page.
  `status` is stored by name (`<enumerated>STRING</enumerated>`), so the new
  value carries no ordinal hazard. `weblogentry.trashed_at` (V025) exists only
  so the trash list can sort and the purge can expire.
- **The exclusion lives in exactly one place**: `WeblogEntrySearchCriteria`'s
  `includeTrashed`, defaulting to **false**, applied in
  `JPAWeblogEntryManagerImpl.getWeblogEntries` when no explicit status is set.
  The default IS the safety property — a new caller that thinks about nothing
  gets the safe behaviour. If you find yourself adding a status condition to a
  second query, that is the design failing, not a detail.
- **Four status-less queries deliberately still see trash**, each for a
  reason, and each carries a comment in `WeblogEntry.orm.xml` saying so:
  `getByCategory` (a trashed entry must still block deleting its category —
  otherwise there is nothing to restore into), the two anchor queries (a
  trashed entry still occupies its anchor; and the permalink lookup is safe
  only because `PageServlet` filters `isPublished()`), and `getByWebsite` (the
  weblog-deletion cascade must take the trash with it).
- **Restore always goes to `DRAFT`, never back to `PUBLISHED`.** An undelete
  that silently republishes to feeds, the sitemap and every subscriber is worse
  than one extra click. That is also why no column remembers the pre-trash
  status.
- **`BaseController.trashEntryWithIndex`** (was `removeEntryWithIndex`) is
  still the single authoring-side deletion seam, with
  `deleteEntryForeverWithIndex` beside it for "delete forever".
  `WeblogEntryManager.removeWeblogEntry` remains the one permanent-deletion
  path — `purgeTrash` calls it per entry rather than issuing a bulk DELETE.
- **The index steps are not optional just because the entry now survives —
  they are MORE necessary.** A `TRASHED` entry left in Lucene is findable by
  site search and links to a permalink that 404s. Two things had to change for
  this to actually hold, and both were live bugs first:
  - `ReIndexEntryOperation` is **asynchronous** and **re-fetches the entry from
    the database by id**, so the old seam's "flip the in-memory status to DRAFT
    and re-index" trick never worked the way its javadoc claimed. It was
    accidentally safe only because the row was gone by the time the job ran.
    With the row surviving, the job re-added trashed entries to the index
    moments after the synchronous remove took them out. `ReIndexEntryOperation`
    now refuses to add a document for a non-`PUBLISHED` entry — enforcing an
    invariant its four callers had been maintaining by convention.
  - **`weblog.lastModified` must be bumped when a published entry is trashed.**
    `WeblogPageCache` has no CacheHandler, so `CacheManager.invalidate` never
    reaches it and `lastModified` is the *only* thing that expires a rendered
    page (see Templates). `trashWeblogEntry` sets `TRASHED` before saving, which
    makes `saveWeblogEntry`'s `isPublished()` bump gate false — so the bump has
    to be explicit, or the cached home page keeps serving a post whose
    permalink now 404s.
  Trashing a published entry also sets `refreshAggregates`, or its tags stay
  counted in the tag cloud until it is purged — which at the default
  retention is never. **Restore deliberately does not** — `restoreWeblogEntry`
  always lands on `DRAFT`, `trashWeblogEntry` already decremented the tag
  counts on the way in, and `DRAFT` is not published, so re-incrementing on
  restore would over-count a draft's tags in the cloud.
- **`entry.trash.retention.days`** — runtime property, default **30**, `-1`
  keeps trash forever. Swept by `TrashPurgeTask` beside `ScheduledEntriesTask`.
  It is re-read per sweep, not latched in `init()` (CLAUDE.md's third
  Configuration-scope trap); a per-weblog try/catch means one weblog's failure
  does not stop the others.
- **Pages and media files are deliberately NOT in the trash.** A soft-deleted
  media file still occupies disk, still counts against `uploads.dir.maxsize`,
  still has a rendition ladder and a thumbnail, and is still reachable at its
  media URL unless every one of those paths learns about trash — precisely the
  spreading this design exists to avoid. Pages are few and deliberate.
- **Editing `runtimeConfigDefs.xml` by hand: a bare `--` inside an XML comment
  makes the parse fail SILENTLY.** `getRuntimeConfigDefs()` returns null and it
  surfaces as unrelated NPEs somewhere else entirely. (The same rule bit the
  `pom.xml` coverage comments earlier in this program.)

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

## Virtual hosts (per-weblog custom domains)

A weblog with `weblog.custom_domain` set is served at that hostname's root:
`https://berlin.thelocalwiki.com/entry/x`, not
`https://blog.example.com/berlin/entry/x`. Spec:
`docs/superpowers/specs/2026-08-18-virtual-host-support-design.md`.

- **Resolution is host-first, inside `WeblogRequestMapper`, and the forward url
  still carries the handle.** That is the whole trick: `PageServlet`,
  `WeblogPageRequest`, the pagers, the rendering models and both render caches
  never learn that virtual hosts exist. `VirtualHostRegistry` holds the
  hostname→handle map in memory (invalidated in `saveWeblog`/`removeWeblog`),
  so the lookup costs no query and works before `PersistenceSessionFilter` runs
  — which is what lets `ControlPlaneHostFilter` sit at filter order **35**,
  ahead of the Spring Security chain (40). Later would let security 302 an
  unauthenticated admin request to a login page on the *custom domain*, minting
  a session and CSRF token on the wrong host.
- **Generated urls derive from the WEBLOG, never from the request.**
  `WeblogPageCache` keys on the handle and not the host, and `#showSeoHead`
  bakes absolute canonical/`og:url` values into those cached bytes — so a
  request-derived url would let whichever host rendered first stamp its
  canonical onto the other's response. All eleven weblog-content url methods
  root through `MultiWeblogURLStrategy.getWeblogURL`, so this is one method.
  `AbstractURLStrategy`'s six `/roller-ui/` methods are control plane and must
  **not** become domain-aware.
- **`appProtectedUrls` is a strict subset of `rollerProtectedUrls`, and the
  split is load-bearing.** `rollerProtectedUrls` mixes application paths
  (`roller-ui`, `api`, `themes`, `webjars`, `robots.txt`, `sitemap.xml`,
  `newsletter`) with weblog request *contexts* (`page`, `search`, `resource`,
  plus legacy `flavor`/`rss`/`atom`/`language`). On the site host a context is
  always the SECOND segment (`/<handle>/page/x`) so the collision never shows.
  On a custom domain the handle comes from the Host header and the context
  becomes the FIRST segment — reserving it there declines
  `/page/<theme>.css` and **renders every vhost page unstyled**. Only
  `appProtectedUrls` applies in vhost mode; only the `isWeblog()` half of the
  guard is skippable.
- **A custom-domain url still carries the servlet context path.** The weblog
  owns the hostname, so it drops the *handle* segment — but under a prefix its
  root is `https://host/roller/`, not `https://host/`. Three separate sites got
  this wrong at once (`getWeblogURL`, the mapper's path-form 301, and
  `SeoController.robots()`); a fourth, `ControlPlaneHostFilter`, is correct
  because `site.absoluteurl` carries the context path by convention.
- **`/roller-ui/rendering/**` and `/newsletter/**` are exempt from the
  control-plane redirect, and that exemption is a silent-breakage guard.**
  `ContactController` is at `/roller-ui/rendering/contact.rol` and
  `NewsletterController` at `/newsletter/subscribe`; both are posted by `fetch`
  from the rendered page under a `connect-src 'self'` CSP. Redirecting either
  to the site host makes it cross-origin — blocked by CSP, and a 301 on a POST
  carries no body anyway — so every `[contact]` and `[subscribe]` shortcode on
  every vhost weblog would stop working, visible only in a browser console.
- **`site.absoluteurl` becomes required once any weblog has a custom domain.**
  The control-plane filter reads it **directly**, never
  `getAbsoluteContextURL()`, whose `InitFilter` fallback can itself be a custom
  domain — which would redirect the control plane to a custom domain, forever.
  Unset, the filter serves the request rather than redirecting: a missing
  configuration degrades to pre-vhost behaviour, never to a loop.
- The path form 301s to the domain (`sendRedirect` is not used — it defaults to
  302, and a temporary redirect tells crawlers not to transfer ranking). The
  site sitemap index **omits** custom-domain weblogs, because a sitemap index
  may only reference sitemaps on its own host.

### Run the browser suite at BOTH context paths before shipping routing changes

`mvn verify -Pit` covers the root context; `mvn verify -Pit -Dit.context.path=roller`
is the only thing that exercises a servlet prefix end to end. This is not
belt-and-braces. The vhost wave's unit tests were written almost entirely at the
root, so "passes at root" was carrying far more weight than anyone realised, and
the prefix pass found a defect the root pass structurally could not see. Worse,
`SeoController.robots()`'s unit test already ran under `/roller` and had baked
the buggy url in as its expected value — a test encoding the defect it should
have caught. When a test fixture pins a context path, check that its assertion
*derives* the expected url rather than hardcoding one shape.

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

- **Structured injection is the only analytics path; there is no free-text
  fallback (W2).** `Weblog.analyticsSiteId` is a validated UUID
  (`WeblogConfigController.myValidate` rejects anything else), not markup.
  `#showAnalyticsTrackingCode` (`weblog.vm`) **builds** the
  `<script defer src="…" data-website-id="…" data-host-url="…">` tag itself
  from that UUID plus two startup properties
  (`ConfigModel.getAnalyticsBasePath()`/`getAnalyticsScriptName()`, backed by
  `analytics.umami.basePath`/`analytics.umami.scriptName` in
  `roller.properties`) — no admin-typed text ever reaches the page head. The
  legacy free-text `analyticsCode` textarea this used to sit beside — gated
  on *Allow analytics code override* **and** `weblogAdminsUntrusted` being
  off, and never actually reachable since this fork keeps
  `weblogAdminsUntrusted` on everywhere — is gone end to end: the field, its
  `Weblog`/`WeblogConfigBean` plumbing, the JSP textarea, the
  `ConfigModel`/macro legacy branches, and the `weblog.analyticscode` column
  itself (dropped in `V023__drop_w2_fossils.sql`).
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
  (baked into the app image at `/app/umami-views.sql`) and applied by a
  **separate** one-shot compose service, `analytics-views`
  (`deploy/analytics-views.sh`) — not `provision.sh`, and not the migration
  chain, which only ever touches `rollerdb`. The split exists because
  `analytics_traffic` is defined over `website_event`, a table Umami creates
  on its own first boot, while `provision` runs entirely *before* `umami` is
  allowed to start; applying the view from `provision` deadlocked every
  fresh install (neither service could go first) until Task 10 split it out.
  `analytics-views` runs after `umami` has started and gates nothing — a
  failure there costs only the Grafana traffic panel, never the blog. Grafana
  is the thing that joins the two halves (two datasources, a panel-level join
  on `website_id`); no
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
  postgres psql` (`docker_deployment.md`); the `provision` service's
  `provision.sh` grants it `CONNECT` on both databases so one password works
  for both Grafana datasources.
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

## Automation API
`org.apache.roller.weblogger.ui.restapi` (`/api/v1`) is a REST surface for
scripts and agents — entries, media, categories, pages, SEO/media audits,
and site administration — everything alongside the JSP admin UI, not a
replacement for it. Full endpoint reference, curl examples and the error
contract: `docs/api/README.md` — the API's actual front door, since there is
no UI for minting a token (`bin/roller-api auth login` is the only route
in). `/api/v1` is explicitly unstable while Roller is 0.x.

- **The `/api` prefix is a servlet-spec prefix mapping, not part of any
  `@RequestMapping`.** `ServletRegistrationConfig.API_URL_PATTERNS` (`/api/*`)
  registers on the *same* `DispatcherServlet` instance as `*.rol`, the SEO
  patterns, and `/newsletter/*` — for a prefix-matched request, Spring strips
  the matched servlet-path prefix from the lookup path before routing, so
  every controller under `ui.restapi.v1` is written relative to `/v1/...`
  (`TokensApi` is `@RequestMapping("/v1/tokens")`, not `/api/v1/tokens`) the
  same way `NewsletterController` is written relative to `/newsletter`, not
  `/newsletter/subscribe`. Getting this wrong compiles fine and 404s at
  runtime — there is no test that catches a controller mapped with the `/api`
  segment still on it beyond noticing every request to it fails. `api` and
  `newsletter` are both reserved path roots in
  `rendering.weblogMapper.rollerProtectedUrls`, specifically so no weblog
  handle can ever shadow either prefix.
- **The API and the JSP admin UI share exactly one authorization path:
  `RollerHandlerInterceptor`.** Every `*Api` controller implements
  `UISecurityEnforced` — the same interface `CategoryEditController`,
  `WeblogConfigController` and the rest of the JSP admin controllers
  implement — and declares its required `GlobalPermission`/
  `WeblogPermission` exactly the way they do. There is no separate
  API-side permission system to keep in sync with the UI's; a permission
  change made once in `RollerHandlerInterceptor` or in a shared manager
  method is enforced identically on both surfaces. `ApiScopeInterceptor`
  (registered *after* `RollerHandlerInterceptor` in `WebMvcConfig` — order is
  load-bearing, see its own javadoc) is an independent, API-only layer on
  top of that shared path: it narrows what an authenticated request may do
  based on the token's own scope, but never substitutes for the permission
  check underneath it. A token can only ever narrow what its owning user
  could already do through the ordinary permission system — see the next
  point.
- **`ApiToken.Role` (`READ`/`POST`/`ADMIN`) and the optional weblog pin are a
  ceiling, never a grant.** Nothing about minting a token, at any role,
  widens what the owning user is permitted to do — `ApiScopeInterceptor`
  runs strictly on top of `RollerHandlerInterceptor`'s own
  `GlobalPermission`/`WeblogPermission` check, refusing a request the scope
  disallows, never approving one the underlying permission system would
  have refused. A `READ`-scoped token held by a `GlobalPermission.ADMIN`
  user still cannot POST anything; a token pinned to one weblog cannot act
  on another even if its owner could, through the ordinary UI, edit both.
- **`EntryFieldRules` and `WeblogOwnership`
  (`org.apache.roller.weblogger.ui.controllers`) exist so entry-field rules
  and by-id ownership checks have exactly one home each, shared by the JSP
  editor and the API rather than reimplemented for it.** `EntryFieldRules`
  is where an author's raw entry input becomes a stored value — title
  HTML-escaping (`EntryBean` and `EntryDtos.applyWrite` both call
  `EntryFieldRules.escapeTitle`) and weblog-timezone pubTime parsing
  (`EntryFieldRules.parsePubTime`, used the same way by both) — so the two
  surfaces cannot drift on either rule the way they would if the API had
  grown its own copy. `WeblogOwnership` is this codebase's one IDOR defense
  for a by-id lookup (see Categories above): `BaseController.lookupEntry`/
  `lookupTemplate`/`lookupCategory`/`lookupPage` on the JSP side and
  `BaseApiController.requireEntry`/`CategoriesApi`/`PagesApi` on the API
  side both delegate to the same `WeblogOwnership.entry`/`category`/
  `template`/`page` methods, rather than each surface trusting a
  client-supplied id against its own weblog independently.
- **A resource the caller may not see is 404, never 403** — across every
  `*Api` controller, not just one: a weblog outside a token's pin, a foreign
  entry/category/media/page id, and a genuinely-missing id are indistinguishable
  responses, because a 403 would itself leak that the resource exists under
  someone else's weblog. `ApiScopeInterceptor.checkWeblogScope` and every
  `requireX` helper in `BaseApiController` follow this rule uniformly.
- **The OpenAPI document is machine-readable, not a browser explorer.**
  `springdoc-openapi-starter-webmvc-api` serves `GET /api/v1/openapi.json`
  (configured relative to `/v1/openapi.json` in `application.properties`,
  per the prefix-mapping point above), scoped to scan only
  `ui.restapi.v1` so the document never leaks the JSP admin surface. The
  UI half of springdoc is not even a dependency — `springdoc.swagger-ui
  .enabled=false` is defence in depth against a future dependency change
  re-adding it by accident, not the primary control. The document sits
  behind the same `apiSecurityFilterChain` as everything else under
  `/api/**` (Basic or Bearer); it is not exempted the way `GET
  /api/v1/ping` is. `OpenApiDocumentTest` pins two claims in
  `docs/api/README.md` as text — that v1 is unstable, and that
  `roller-api auth login` is the bootstrap path — because both are things a
  reader cannot recover on their own from the OpenAPI document itself.
- **`RollerHandlerInterceptor`'s `import ...ui.restapi.ApiException` is the
  ONE permitted import from `ui.restapi` into `ui.controllers`, and it must
  stay the only one.** The dependency points the wrong way on purpose: the
  interceptor is genuinely shared by both surfaces, and the alternative — a
  second interceptor for `/api/**` — is exactly the parallel authorization
  path this wave exists to avoid. Anything *else* that wants REST types in
  the JSP packages is a signal the seam is being drawn in the wrong place.
  `grep -r "import org.apache.roller.weblogger.ui.restapi" app/src/main/java/
  org/apache/roller/weblogger/ui/controllers/` must return exactly one line.