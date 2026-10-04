# Build, testing and CI

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## Frontend build

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

## Testing Commands
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

## Unit-suite flakes root-caused

Each entry: symptom, mechanism, fix, and how it was made to fail on demand.
A flake goes here once it is explained; a rerun that passes explains nothing.

- **`UserQueryAndRoleTest.listingIsPageable` / `listingIsTriStateOnEnabled`**
  ("an offset must move the window", "got: []"), fixed 2026-10-03.
  `JPAUserManagerImpl.getUsers` turned a null `endDate` into "now" and
  compared `dateCreated < now`, so an account stamped in the same
  millisecond as the listing was not listed. Warm, the class's `setUp` runs
  in ~1 ms, so the newest fixture often shared the query's millisecond:
  hidden from one query, visible to the next (which shifts an offset window
  onto the row the first page already returned). A production bug: every
  caller passes null and the interface documents null as "no bound". Now a
  null date adds no predicate, as `JPAWeblogManagerImpl.getWeblogs` already
  did. Reproduced by looping setUp + `listingWalksEveryUser...` + the two
  tests in one JVM (the class's own method order): 100 of 900 failed before,
  0 of 900 after; `aListingWithNoEndDateIncludesAnAccountStampedAtOrAfterNow`
  pins it deterministically with a `dateCreated` just ahead of the clock.

- **`SearchIndexQueryTest.aSearchCanBeNarrowedToACategory`** (CI
  35431937572, "got: []") **and `rebuildingAWeblogsIndexRestoresIt`**,
  fixed 2026-10-03. `addEntryIndexOperation` only schedules the write, and
  `setUp` returned with it still in flight 194-199 times in 200 (measured
  by opening the index directly at the end of `setUp`), so each test's first
  search depended on queueing behind that write. Even queued, it could lose:
  `WriteToIndexOperation` invalidated the shared `IndexReader` *after*
  releasing the write lock, so a search taking the read lock in that window
  answered from the reader cached before the write (here, the previous
  test's). `rebuilding...`'s own failure was not reproduced; the same
  stale or premature empty search would let its "index is empty" wait pass
  before the removal had run, leaving removal and rebuild to race on the
  unordered thread pool. Fixed in production (reset before
  unlock) and in the test (`setUp` waits for its own entries). Reproduced
  naturally once in 240 with the JVM pinned to one core (`taskset -c 15`),
  the test after `rebuilding...` as in CI; `SharedReaderHandoffTest` holds
  the window open deterministically (fails every run without the fix).
  Residual: the hit filter dropped an entry with `pubTime == now` (strict
  `before`), so publish-then-search in one millisecond found nothing; now
  `!after(now)` (`anEntryPublishedAtExactlyNowIsFound`, also `SearchResultsModel`).

## CI: three tiers, and nothing publishes on a push

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
