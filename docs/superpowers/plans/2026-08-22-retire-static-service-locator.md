# Retire the Static Service Locator — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Every dependency on the business tier is visible in a constructor.
`WebloggerFactory` — the process-global static through which 73 main-source
files (164 call sites) reach the `Weblogger` facade — is deleted; the nine JPA
entities that run queries and build URLs from getters become data plus
invariants; and a guard test keeps it that way.

**Architecture:** `WebloggerProvider` (already the SPI behind the static) becomes
a Spring bean with `isBootstrapped()`; container-managed classes take `@Lazy
Weblogger` (or the provider where "is the tier up?" is a real question); the
`new`'d servlets/filters/interceptors become beans; the reflective model and
task lists stay and receive the facade through their `init` hooks; everything
hand-constructed on the request path takes it in its constructor; entity
behaviour moves to services, with the Velocity wrappers (which already hold a
`URLStrategy`) absorbing it for the template surface and a `urls` view helper
absorbing it for the JSPs.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring MVC, JPA/EclipseLink,
Velocity, JUnit 5, Mockito, Selenide (browser ITs).

**Spec:** `docs/superpowers/specs/2026-08-22-retire-static-service-locator-design.md`
(decisions are numbered there; tasks cite them as **D1**…**D9**).

## Global Constraints

- **TDD is mandatory.** Write the failing test, run it, watch it fail for the
  reason you expect, then write the minimum code that passes. A test that has
  never been seen to fail has not been shown to test anything.
- **Characterisation tests are the exception** and must say so in their
  javadoc. Most of this wave is characterisation: the rendering, controller
  and servlet tests keep their assertions and change only their *wiring*. A
  test whose assertions must change (other than Task 14's preview URLs) is a
  signal to stop and look, not to edit the assertion.
- **Never run two builds at once** in this working tree — implementers share
  `app/target/`. Check first, and inline the wait rather than polling
  separately:
  `pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || echo CLEAR`
  Both the brackets and the `source/roller` scoping are load-bearing.
- **Parallel implementers mean git worktrees, with the base pinned and
  verified before dispatch** — see CLAUDE.md. Stage B's tasks are disjoint by
  design and are the ones to parallelise; run the overlap check
  (`LC_ALL=C comm -12` + `git merge-tree --write-tree`) before merging each.
  Stages C and D touch shared test support and are serial.
- **Never commit or push unless explicitly asked.** Work directly on `master`.
- **Every task ends by shrinking `StaticServiceLocatorTest.ALLOWED`** (Task 2)
  to exactly the files still carrying a `WebloggerFactory` reference. A task
  whose files are still on the list is not done.
- **Velocity is lenient here.** A template reference to a deleted Java member
  does not throw and does not log — it prints as literal text. Task 1's leak
  test exists for this; do not start Stage D without it green.
- **No new static config reads** in any code this wave touches (Stage 2's
  business). Where a moved method read `WebloggerRuntimeConfig`/`WebloggerConfig`,
  the read moves with it — still static for now, but not multiplied.
- **`@Lazy` on every `Weblogger`/manager injection point outside
  `WebloggerBeanConfig`.** Task 2's `ContextRefreshDoesNotBootstrapTest` is
  what tells you if you forgot one.
- **A `Throwable` stays the last SLF4J argument**; name every
  `@RequestParam`/`@PathVariable`; a bare `--` in an XML comment breaks the
  parse (applies to `velocity.properties` too — it is a Java properties file,
  so that one is fine, but `runtimeConfigDefs.xml` is not touched here).
- Run tests with `mvn -pl app test -Dtest=ClassName`. Browser ITs are
  `mvn verify -Pit` (~16 min) and `mvn verify -Pit -Dit.context.path=roller`;
  both are required at the end of Stage D, not per task.

## File Structure

New files:

| File | Responsibility |
|---|---|
| `app/src/test/java/.../ui/rendering/ThemeReferenceLeakTest.java` | renders every bundled theme × template; fails on any unresolved `$reference` (Task 1) |
| `app/src/test/java/.../StaticServiceLocatorTest.java` | source scan: allowlisted `WebloggerFactory` references, no business-tier statics, clean pojos (Task 2) |
| `app/src/test/java/.../boot/ContextRefreshDoesNotBootstrapTest.java` | Boot context refresh builds no business bean (Task 2) |
| `app/src/main/java/.../business/EntryRenderer.java` | the render pipeline as a bean (Task 15) |
| `app/src/main/java/.../ui/controllers/AdminUrls.java` | the `urls` JSP view helper (Task 16) |

Principal modified files, by stage (the call-site inventory in the spec's
baseline table is the authoritative list; these are the structural ones):

| Stage | File | Change |
|---|---|---|
| A | `business/WebloggerProvider.java`, `business/SpringWebloggerProvider.java` | `isBootstrapped()`; `@Component`; `bootstrap()` owns guard+getBean+initialize+release |
| A | `boot/RollerLifecycle.java`, `ui/controllers/core/InstallController.java` | inject the provider |
| B | `business/jpa/WebloggerBeanConfig.java` | `VirtualHostRegistry`, `ShortcodeExpander`, `EntryRenderer` beans; `threadManager`/`weblogManager` signatures |
| B | `boot/ServletRegistrationConfig.java`, `boot/WebMvcConfig.java`, `boot/SecurityConfig.java` | servlets/filters/listener/interceptors/`RollerUserDetailsService` become beans |
| B | `ui/rendering/velocity/RollerVelocity.java`, `ui/core/RollerContext.java` | explicit `initialize(servletContext, weblogger)`; application attribute |
| B | `business/runnable/RollerTask.java`, `ThreadManagerImpl.java`, `TaskScheduler.java` | `init(Weblogger, name)`; constructor |
| B | `ui/rendering/filters/RequestMappingFilter.java`, `WeblogRequestMapper.java`, `config/roller.properties` | mapper constructed directly; two properties deleted |
| C | `ui/rendering/model/ModelLoader.java` + 9 models, 6 pagers, 7 `*Request` classes, `util/cache/WeblogCacheWarmupJob.java` | `initData.weblogger`; constructors |
| C | `pojos/wrapper/*Wrapper.java` | `wrap(pojo, URLStrategy, Weblogger)`; URL methods use the injected strategy |
| D | `pojos/WeblogEntry.java`, `Weblog.java`, `MediaFile.java`, `WeblogCategory.java`, `WeblogEntryTag.java`, `User.java`, `WeblogPermission.java`, `GlobalPermission.java`, `WeblogPage.java` | behaviour out |
| D | `business/ContentRenderer.java` | **deleted** (replaced by `EntryRenderer`) |
| D | `ui/controllers/BaseController.java`, 14 JSPs under `WEB-INF/jsps/` | `urls` helper; explicit model attributes |
| E | `business/WebloggerFactory.java` | **deleted** |
| E | `config/WebloggerRuntimeConfig.java` | `attach`/`detach` |
| E | `app/src/test/.../TestUtils.java`, `business/MockWeblogger.java`, `ui/controllers/*/ControllerTestFixture.java` | `weblogger()`; builder-only; supplier |
| E | `CLAUDE.md` | DI paragraph rewritten |

---

## Stage A — Foundation

### Task 1: `ThemeReferenceLeakTest` — the Velocity safety net

**Why first:** Velocity prints an unresolved reference as literal text and
logs nothing. Stages C and D delete a dozen getters that templates reach
through wrappers. This is a **characterisation test** — expected to pass on
arrival — and its javadoc must say so. **D7.**

**Files:**
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/ThemeReferenceLeakTest.java`
- Reuse: `RenderingTestSupport`, `TestUtils` fixtures (a weblog per theme via
  `TestUtils.setupWeblog` + `weblog.setEditorTheme(...)`, one published entry
  carrying `[image]`, `[gallery]`, `[map]`, `[faq]`, a category, tags, a
  featured image; one published `WeblogPage`).

- [x] **Step 1:** For each of `journal`, `portfolio`, `travel`: render the
  home page, a permalink, a category page, a tag page, a date archive, a
  static page (`/<handle>/<slug>`), and `searchresults` (via `SearchServlet`)
  through the real servlets; also render `frontpage`'s home (needs
  `site.frontpage.weblog.handle`), the two Atom feeds, and the error page
  (force a 404 through `PageServlet`).
- [x] **Step 2:** Assert on each body: no match for
  `\$!?\{?[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*` **outside**
  `<script>`/`<style>` blocks (strip those first — inline JS may legitimately
  use `$x` identifiers), and no literal `#showX(`/`#foreach`/`#if` text.
  Collect all failures per page and report them together.
- [x] **Step 3:** Prove it bites: temporarily rename
  `WeblogEntryWrapper.getDisplayContent` and watch every `_day.vm` page fail
  with `$entry.displayContent` in the body; restore. Record in the javadoc
  that this was done.
- [x] **Step 4:** `mvn -pl app test -Dtest=ThemeReferenceLeakTest` green.

### Task 2: The two guard tests

**Files:**
- Test: `app/src/test/java/org/apache/roller/weblogger/StaticServiceLocatorTest.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/boot/ContextRefreshDoesNotBootstrapTest.java`
- Modify: `business/WebloggerFactory.java` (javadoc: deprecated, deleted by this plan's Task 20; the allowlist is the migration ledger)

- [x] **Step 1 — `StaticServiceLocatorTest`.** Three assertions, each a
  walk of `app/src/main/java`:
  1. The set of files containing the token `WebloggerFactory` (excluding
     `WebloggerFactory.java` itself and the four javadoc-only mentions:
     `SpringWebloggerProvider`, `WebloggerStartup`, `CategoriesApi`,
     `EntryDtos`) equals `ALLOWED` — a `Set<String>` of repo-relative paths,
     seeded with the **73 files** from the baseline. Assert equality in both
     directions: a file on the list that no longer references the shim is an
     error too ("remove it from the ledger").
  2. No main source declares a `static` field whose type matches
     `\b(Weblogger|WebloggerProvider|[A-Z][A-Za-z]*Manager|URLStrategy|VirtualHostRegistry)\b`,
     except `STATIC_RESIDUALS` = {`config/WebloggerRuntimeConfig.java`,
     `ui/rendering/velocity/RollerVelocity.java`} (**D4, D8**). Seed it to
     pass today (the current tree has none besides `WebloggerFactory`'s own
     field — verify).
  3. No file under `pojos/` references
     `\b(Weblogger|WebloggerProvider|[A-Z][A-Za-z]*Manager|URLStrategy|ShortcodeExpander|PluginManager|ThemeManager)\b`
     — **this one starts with its own allowlist** (`POJO_ALLOWED`: the 9
     pojo/wrapper files) and Stage D empties it.
  The failure message for each names the file and says what to do (add to /
  remove from the ledger, or inject it).
- [x] **Step 2 — `ContextRefreshDoesNotBootstrapTest`.** Start
  `RollerApplication` with `SpringApplicationBuilder(...).properties(
  "roller.lifecycle.enabled=false", "server.port=0")`, refresh, and assert
  `beanFactory.containsSingleton(name)` is **false** for every bean name
  defined in `WebloggerBeanConfig` (enumerate them from the context's bean
  definitions whose factory bean is `webloggerBeanConfig` — do not hardcode
  the list). Close the context. Watch it **pass today** (it is
  characterisation for the `@Lazy` scheme) and keep it in the suite; it turns
  red the moment a future injection point forgets `@Lazy`. `RollerTestBootstrap`
  has already pointed `WebloggerConfig` at the test DB, so the context's
  static config loads fine; the business tier must simply never be asked for.
- [x] **Step 3:** Both green; commit message names them as the wave's ledger
  and invariant.

### Task 3: `WebloggerProvider` as the bean; the two bootstrap sites

**D2.** After this task the static still exists and is still installed — by
the provider's own `bootstrap()` — so nothing else changes behaviour.

**Files:**
- Modify: `business/WebloggerProvider.java` (add `boolean isBootstrapped()`)
- Modify: `business/SpringWebloggerProvider.java` (`@Component`; `volatile boolean bootstrapped`; `bootstrap()` = `WebloggerStartup.isPrepared()` guard → `getBean(Weblogger.class)` → `initialize()` → `release()` in `finally` → set flag → **transitional:** `WebloggerFactory.installProvider(this)`; `getWeblogger()` throws `IllegalStateException` until bootstrapped; no-arg constructor kept for `TestUtils`)
- Modify: `boot/RollerLifecycle.java` (inject `WebloggerProvider`; `start()` calls `provider.bootstrap()` where it called `WebloggerFactory.bootstrap(new SpringWebloggerProvider(ctx))` + `initialize()`; `stop()` uses `provider.isBootstrapped()`/`getWeblogger().shutdown()`)
- Modify: `ui/controllers/core/InstallController.java` (same; its four `isBootstrapped()` checks use the provider)
- Modify: `boot/SecurityConfig.java:369` (`() -> weblogger.getApiTokenManager()` with a `@Lazy Weblogger` parameter on the `@Bean` method)
- Modify: `ui/restapi/auth/ApiTokenAuthFilter.java` (constructor gains `WebloggerProvider`; `:174-175` use it)
- Tests: `SpringWebloggerProviderTest`, `RollerLifecycleTest`, `InstallControllerTest` (or wherever install flows are covered — they use `MockWeblogger.installNotBootstrapped()` today and will instead pass a mock `WebloggerProvider` with `isBootstrapped()` stubbed), `ApiTokenAuthFilterTest`
- Ledger: remove `boot/RollerLifecycle.java`, `boot/SecurityConfig.java`, `ui/controllers/core/InstallController.java`, `ui/restapi/auth/ApiTokenAuthFilter.java` from `ALLOWED`

- [x] **Step 1:** Failing tests: `SpringWebloggerProviderTest` —
  `isBootstrapped()` false before, true after `bootstrap()`; `getWeblogger()`
  throws before; `bootstrap()` refuses when `!WebloggerStartup.isPrepared()`;
  `bootstrap()` is idempotent; `initialize()` was called exactly once
  (spy the `Weblogger` bean in a test `@Configuration`).
- [x] **Step 2:** Implement; `RollerLifecycleTest` constructs the lifecycle
  with a mock provider and asserts the call order `prepare → bootstrap`, and
  that a `BootstrapException` from the provider is logged and leaves
  `running=true` exactly as today.
- [x] **Step 3:** `InstallController` tests: pre-bootstrap branches reachable
  via a stubbed provider — no `installNotBootstrapped()` in these tests any
  more.
- [x] **Step 4:** `mvn -pl app test` green; ledger shrunk by 4.

---

## Stage B — Container-managed classes

Disjoint files. Tasks 4–9 may run in parallel worktrees (pinned base). Each
rewrites the affected tests to pass the collaborator instead of
`mockStatic`/`MockWeblogger.install` (**D9**).

### Task 4: Pure substitutions inside the tier; themes; Lucene; `VirtualHostRegistry`

**Files & sites:**
- `business/jpa/JPAMediaFileManagerImpl.java` (10 sites → `roller.getFileContentManager()`), `JPAWeblogManagerImpl.java` (:192 → `roller.getMediaFileManager()`), `business/themes/ThemeManagerImpl.java` (:303 → `roller.getWeblogManager()`) — these three already hold `private final Weblogger roller`.
- `business/themes/WeblogSharedTheme.java` (4 sites), `WeblogCustomTheme.java` (5 sites): constructors gain `WeblogManager`; `ThemeManagerImpl:171,177` pass `roller.getWeblogManager()`.
- `business/search/lucene/LuceneIndexManager.java:497` — `convertHitsToEntryList` becomes an instance method (or takes `WeblogEntryManager`); fix its callers.
- `business/VirtualHostRegistry.java` — from a static utility with a static map to a `@Bean` in `WebloggerBeanConfig` with instance state, constructor `(@Lazy WeblogManager)`; `JPAWeblogManagerImpl` injects it (`@Lazy`, cycle) for `invalidate()` in `saveWeblog`/`removeWeblog`; its `isBootstrapped()` log-level branch goes (callers that may run pre-bootstrap guard themselves — Task 6).
- Tests: `VirtualHostRegistryTest`, `WeblogSharedTheme`/`WeblogCustomTheme` tests, `LuceneIndexManager` tests, `JPAWeblogManagerImplTest`.
- Ledger: remove the 7 files.

- [x] Failing tests first for the theme constructors and the registry bean;
  substitutions are characterisation (existing tests pass unchanged).
- [x] `SpringWebloggerProviderTest.bootstrapBuildsTheFullGraphWithSingletons`
  still passes (the registry is now in the graph).

### Task 5: Background tasks

**D3.** `RollerTask.init(String)` → `init(Weblogger, String)`;
`ThreadManagerImpl` (bean; `JPAThreadManagerImpl` constructor gains `@Lazy
Weblogger`, `WebloggerBeanConfig.threadManager(...)` updated) passes it at
`initialize()`; `TaskScheduler(Weblogger, List<RollerTask>)`;
`RollerTaskWithLeasing`, `ScheduledEntriesTask`, `TrashPurgeTask` use the
field. The test-only `TestTask` in `app/src/test/resources/roller-custom.properties`
gets the new signature.

- Tests: `TrashPurgeTaskTest` (drops `mockStatic`), `ScheduledEntriesTask`
  tests, `TaskSchedulerTest`/`ThreadManagerImplTest` if present.
- Ledger: `business/runnable/{RollerTaskWithLeasing,ScheduledEntriesTask,TaskScheduler,TrashPurgeTask}.java`.

### Task 6: Filters, session listener, interceptors, security beans

**Files:**
- `boot/ServletRegistrationConfig.java`: `BootstrapFilter(WebloggerProvider)`, `PersistenceSessionFilter(WebloggerProvider)`, `RequestMappingFilter(@Lazy Weblogger, @Lazy VirtualHostRegistry, WebloggerProvider)`, `ControlPlaneHostFilter(@Lazy VirtualHostRegistry, WebloggerProvider)` become `@Bean`s passed into their `FilterRegistrationBean`s (orders unchanged: 50, 60, 80, 35). The `RollerSession` listener likewise.
- `ui/core/RollerSession.java`: the static `getRollerSession(HttpServletRequest)` and the user resolution at `:86-89,:138` — verify its callers (the survey found only the declaring file; check for unqualified `getRollerSession(` calls) and give the resolution an explicit `UserManager`/`Weblogger` parameter from each caller; the per-session object stores only the username.
- `boot/WebMvcConfig.java`: `RollerHandlerInterceptor(WebloggerProvider, @Lazy Weblogger)` as a `@Bean` (its `:94,:185,:322` use the facade, `:170` the provider); `ApiScopeInterceptor` unchanged unless it also reaches the static (it does not).
- `boot/SecurityConfig.java:102`: `new RollerUserDetailsService()` → constructor `(WebloggerProvider)`; the try/catch that maps a pre-bootstrap `IllegalStateException` to `UsernameNotFoundException` stays, now on `provider.getWeblogger()`.
- `ui/rendering/WeblogRequestMapper.java`: constructor `(Weblogger, VirtualHostRegistry, WebloggerProvider)`; `:426` and the vhost lookup guard on `provider.isBootstrapped()`; **delete** `rendering.rollerRequestMappers`/`rendering.userRequestMappers` from `roller.properties` and the `Reflection.newInstancesFromProperty` call in `RequestMappingFilter` (**D3**). Grep `app/src/main/webapp` and the docs for the two property names before calling it done.
- Tests: `BootstrapFilterTest`, `ControlPlaneHostFilterTest`, `RollerSessionTest` (all drop `mockStatic`), `RollerHandlerInterceptorTest`, `WeblogRequestMapperTest` (constructs the mapper with a `MockWeblogger` facade — and stays running at both context paths), `RequestMappingFilterTest`.
- Ledger: `ui/core/RollerSession.java`, `ui/core/filters/{BootstrapFilter,PersistenceSessionFilter}.java`, `ui/core/security/RollerUserDetailsService.java`, `ui/controllers/RollerHandlerInterceptor.java`, `ui/rendering/WeblogRequestMapper.java`, and `business/VirtualHostRegistry.java` if Task 4 left the `isBootstrapped()` site.

### Task 7: Rendering and ajax servlets as beans

**Files:**
- `boot/ServletRegistrationConfig.java`: nine `@Bean` servlet instances (`PageServlet`, `FeedServlet`, `ResourceServlet`, `MediaResourceServlet`, `SearchServlet`, `PreviewServlet`, `PreviewResourceServlet`, `UserDataServlet`, `ThemeDataServlet`), each constructed with `@Lazy Weblogger`, handed to the existing `ServletRegistrationBean`s (mappings and load-on-startup unchanged).
- The servlets: field `private final Weblogger weblogger`; the static helpers gain a parameter — `PageServlet.rejectionReason(…, WeblogEntryManager)`, `FeedServlet.isServable(…, WeblogEntryManager)`, `MediaResourceServlet.requesterMayEditWeblog(…, UserManager)`, `RenderingServletUtils.reloadThemeFromDisk(…, ThemeManager)` and `loadModels(…)` (which from Task 10 also puts `weblogger` into `initData` — add the parameter now, wire it now, so Task 10 is a one-line change per model).
- `ui/rendering/util/PreviewThemeLookup.byName(name, ThemeManager)`; callers `WeblogPreviewRequest:103`, `WeblogPreviewResourceRequest:82` (those two classes get their facade in Task 12 — for now they pass `WebloggerFactory.getWeblogger().getThemeManager()` and stay on the ledger; the point is that `PreviewThemeLookup` itself leaves it).
- `app/src/test/.../ui/rendering/servlets/RenderingTestSupport.java`: `pageServlet()` etc. construct with `TestUtils`' real `Weblogger` (`WebloggerFactory.getWeblogger()` until Task 20, then `TestUtils.weblogger()` — the sed covers it).
- Tests: every `*ServletRenderingTest` is characterisation (wiring only). `ThemeDataServletTest`/`UserDataServletTest` if present.
- Ledger: the 8 `ui/rendering/servlets/*` files, `ui/controllers/ajax/{ThemeDataServlet,UserDataServlet}.java`, `ui/rendering/util/PreviewThemeLookup.java`.

### Task 8: Static utilities and form beans

**Files:**
- `ui/core/util/menu/MenuHelper.getMenu(…, UserManager)` (:107); callers (`BaseController`, `MenuModel`) have it.
- `util/MailUtil` (:79, :105): the two methods gain `Weblogger`; callers `ContactController:273,296`, `PasswordLinkMailer:55` (and any other) pass theirs.
- `ui/controllers/admin/CreateUserBean.copyFrom` (:175 — the `administrator` latch): the permission check moves to `UserAdminController`/`UserEditController`, which set the bean field after `copyFrom`.
- `ui/controllers/editor/EntryBean.copyTo` (:401): the `WeblogEntryManager` call moves to `EntryEditController`/`EntryAddWithMediaFileController` around `copyTo`.
- `ui/controllers/editor/TemplateEditBean.copyTo` (:149,:158 `saveTemplateRendition`): moves to `TemplateEditController`.
- Tests: `MenuHelperTest` (drops `mockStatic`), `MailUtil` tests, `CreateUserBean`/`EntryBean`/`TemplateEditBean` tests become pure; the three controllers' tests cover the moved calls.
- Ledger: `ui/core/util/menu/MenuHelper.java`, `util/MailUtil.java`, `ui/controllers/admin/CreateUserBean.java`, `ui/controllers/editor/{EntryBean,TemplateEditBean}.java`.

### Task 9: Velocity — explicit engine initialisation; loaders via application attribute

**D4.**

**Files:**
- `ui/rendering/velocity/RollerVelocity.java`: the static-initialiser engine build becomes `static synchronized void initialize(ServletContext, Weblogger)` (idempotent; throws if called twice with a different weblogger in tests? — no: make it a no-op on repeat, since `RenderingTestSupport` notes the engine cannot be re-initialised); sets `engine.setApplicationAttribute(Weblogger.class.getName(), weblogger)`; `getEngine()`/`getTemplate()` throw `IllegalStateException("RollerVelocity not initialised")` before that.
- `ui/core/RollerContext.setupVelocity()` → deleted or delegating; `boot/RollerLifecycle.start():203` calls `RollerVelocity.initialize(servletContext, provider.getWeblogger())` **after** bootstrap (and only when bootstrapped — today `setupVelocity()` runs unconditionally; if the tier failed to bootstrap the install wizard is JSP-rendered and needs no Velocity, so guarding it is correct; confirm nothing else renders Velocity pre-bootstrap).
- `RollerResourceLoader.init(ExtProperties)` / `ThemeResourceLoader.init(ExtProperties)`: read `(Weblogger) rsvc.getApplicationAttribute(Weblogger.class.getName())`, fail fast if absent; `:86`/`:96` use it.
- `RenderingTestSupport.installServletContext()` → also `RollerVelocity.initialize(ctx, weblogger)`.
- Tests: `RollerVelocityTest` (new — initialise-before-use contract), the two loaders' tests; the whole rendering suite is characterisation.
- Ledger: `ui/rendering/velocity/{RollerResourceLoader,ThemeResourceLoader}.java`.

**Stage B exit:** ledger down to the 9 models, 6 pagers, 7 `*Request` objects, `WeblogCacheWarmupJob` (if it referenced the static — it does not directly, but check), the 10 pojo/wrapper files, the 3 shortcode files, `config/WebloggerRuntimeConfig.java`, and `business/SpringWebloggerProvider.java`'s transitional install line. `mvn -pl app verify` green (PMD/CPD/SpotBugs included — new constructors and parameters are exactly the kind of thing `UnusedFormalParameter` or `CloseResource` notice).

---

## Stage C — The rendering request path (serial)

### Task 10: Models — `initData.weblogger`

**Files:** `ui/rendering/model/ModelLoader.java` (assert `initData` carries
`weblogger` — throw `WebloggerException` naming the missing key, same as a
missing model class today); the 9 models read it in `init()` and **delete the
`urlStrategy` null-fallback** (`PageModel:119-122`, `SiteModel:98-101`,
`FeedModel:81-84`, `URLModel:80-83`, `PreviewPageModel:64-67`,
`PreviewURLModel:60-63`, `SearchResultsModel:77-80`) — `urlStrategy` missing
is now also an error; `ui/rendering/util/cache/WeblogCacheWarmupJob.java:117-119`
supplies both `weblogger` and `urlStrategy` (it is the reason the fallback
existed; fixing the caller is the right end of the stick). `ConfigModel`'s
three build-info reads use the facade from `initData`.

- Tests: the 7 model tests (`Config`, `Feed`, `Menu`, `Page`, `PreviewModels`,
  `Site`, `URL`) drop `mockStatic` and put `MockWeblogger`'s facade in
  `initData`; `ModelLoaderTest` (new or extended): missing `weblogger` →
  exception naming it; `WeblogCacheWarmupJobTest` passes both.
- Ledger: the 9 model files.

### Task 11: Pagers

**Files:** `AbstractWeblogEntriesPager` and its four subclasses,
`WeblogEntriesListPager` (+ `FeedModel.FeedEntriesPager`), `UsersPager`,
`WeblogsPager`: constructor gains `Weblogger` after `URLStrategy` (the
signature pattern `SearchResultsPager` already has); construction sites in
`PageModel:381`, `PreviewPageModel:105,116`, `SiteModel:129,186,216,238`,
`FeedModel:134` pass the model's facade.

- Tests: `EntriesPagerTestSupport` stops `mockStatic`ing and passes the mock
  facade; `AbstractWeblogEntriesPagerTest`, `UsersPagerTest`,
  `WeblogEntriesListPagerTest`, `WeblogsPagerTest`.
- Ledger: the 6 pager files.

### Task 12: `*Request` objects

**Files:** `ui/rendering/util/ParsedRequest` (constructor gains `Weblogger`;
`:85` uses it), `WeblogRequest` (:218), `WeblogPageRequest` (:323,:430,:506),
`WeblogFeedRequest` (:224), `WeblogPreviewRequest` (:134 + the
`PreviewThemeLookup` call from Task 7), `WeblogSearchRequest` (:150),
`WeblogPreviewResourceRequest` (the other `PreviewThemeLookup` caller).
Construction sites: `PageServlet:113`, `SearchServlet:91,154`,
`FeedServlet:97`, `PreviewServlet:91`, `UserDataServlet:75`,
`WeblogCacheWarmupJob:108`, `PreviewResourceServlet`.

- Tests: `LazyLookupTest`, `ParsedRequestTest` (drop `mockStatic`); the
  request-object tests construct with the mock facade.
- Ledger: the 7 files.

### Task 13: Wrappers take the facade

**D5 (the template API), D6.**

**Files:** `pojos/wrapper/WeblogEntryWrapper.wrap(entry, strat, weblogger)`,
`WeblogWrapper.wrap(weblog, strat, weblogger)`,
`WeblogCategoryWrapper.wrap(cat, strat, weblogger)`,
`MediaFileWrapper.wrap(file, strat, weblogger)` (gains both),
`WeblogEntryTagWrapper.wrap(tag, weblogger)`; `WeblogEntryWrapper
.resolveMediaFile:308` uses `weblogger.getMediaFileManager()`; the
construction sites (every model and pager `wrap(...)` call; `UtilitiesModel`;
the wrappers' own nested `wrap`s of related objects) pass what they hold.

This task **does not yet delete** the pojo getters — it only makes the
wrappers hold what Stage D will need, and does the one behaviour change:

- [x] **Step 1 (D6, the failing test):** `PreviewUrlRenderingTest` — render a
  home page and a permalink through `PreviewServlet` with `?theme=journal`
  and assert `$model.weblog.absoluteURL`, `$entry.permalink` and an
  `$image.permalink` in the body carry the preview URL shape the installed
  `PreviewURLStrategy` produces. Watch it fail (today they carry the
  production shape).
- [x] **Step 2:** `WeblogWrapper.getURL/getAbsoluteURL` and
  `WeblogEntryWrapper.getPermalink` use `urlStrategy` (the field they already
  have); `MediaFileWrapper.getPermalink/getThumbnailURL/getSrcset/url(int)/webpUrl(int)`
  use the new field. Test green.
- [x] **Step 3:** Wrapper tests (`MediaFileWrapperTest`,
  `SmallWrapperDelegationTest`, `WeblogEntryWrapperTest`,
  `WeblogWrapperDelegationTest`) drop `mockStatic`; `ThemeReferenceLeakTest`
  still green.
- Ledger: `pojos/wrapper/WeblogEntryWrapper.java`. (`UserWrapper` reads
  runtime config only — Stage 2; it was never on the ledger.)

---

## Stage D — Entities (serial; Task 1 must be green)

### Task 14: `EntryRenderer` and the shortcode expander as beans; render pipeline off the entity

**D5 (rendering cluster).**

**Files:**
- New `business/EntryRenderer.java`: `transformedText(WeblogEntry)`,
  `transformedSummary(WeblogEntry)`, `displayContent(WeblogEntry, String readMoreLink)`,
  `pageContent(WeblogPage)`; constructor `(ShortcodeExpander, PluginManager)`;
  the `I18nMessages` read-more link and `HTMLSanitizer.conditionallySanitize`
  move here verbatim (the sanitizer stays static — Stage 2). Exposed on the
  facade: `Weblogger.getEntryRenderer()` (`WebloggerImpl`, `JPAWebloggerImpl`
  constructor, `WebloggerBeanConfig.weblogger(...)`, `MockWeblogger`).
- `business/shortcodes/ShortcodeExpander`: `DEFAULT`/`defaultExpander()`
  deleted; `WebloggerBeanConfig.shortcodeExpander(@Lazy MediaFileManager)`
  builds it with `GalleryShortcode(MediaFileManager)`,
  `ImageShortcode(MediaFileManager)`, `MapShortcode(MediaFileManager)` (its
  `autoPins` becomes an instance method) and the rest; `PluginManagerImpl
  .applyWeblogEntryPlugins` uses the injected expander (constructor).
- `pojos/WeblogEntry`: delete `render`, `getTransformedText`,
  `getTransformedSummary`, `displayContent`, `getDisplayContent`,
  `createAnchor()` (dead), `hasWritePermissions` (test-only);
  `createAnchorBase(char separator)`; `pojos/Weblog.getInitializedPlugins`
  deleted (the `PluginManager` owns any per-weblog cache, or none — the
  registry is empty); `pojos/WeblogPage` rendering → `EntryRenderer.pageContent`;
  `business/ContentRenderer.java` **deleted**.
- Callers: `WeblogEntryWrapper:218,223,228,233` → `weblogger.getEntryRenderer()`;
  `EntriesWriteApi:228`, `EntryEditController:237,501` → injected facade;
  `JPAWeblogEntryManagerImpl:791` → `createAnchorBase(separator)` where the
  manager reads `weblogentry.title.useUnderscoreSeparator` (the read moves,
  not multiplies); `EntryWritePermissionTest` → the check lives in whichever
  controller/service called it (survey: zero production callers — so the test
  moves to cover `UserManager.checkPermission` + `WeblogPermission.hasAction`
  directly, or is deleted with a note if it duplicates `WeblogPermissionTest`).
- Tests (failing first for the new bean, characterisation for output):
  `EntryRendererTest` (byte-identical output to the old `WeblogEntry.render`
  for the existing shortcode/markdown fixtures — lift them from
  `WeblogEntryRenderingTest`), `ShortcodeExpanderTest` and the three
  `*ShortcodeTest`s (drop `mockStatic`, construct handlers with a mock
  `MediaFileManager`), `EntriesApiReadTest` (rendered-content field
  unchanged), `PluginManagerImplTest`.
- Ledger: `business/shortcodes/{Gallery,Image,Map}Shortcode.java`; `POJO_ALLOWED` loses nothing yet (other getters remain).

### Task 15: URL generation off the entities; the `urls` JSP helper

**D5 (URL cluster).**

**Files:**
- New `ui/controllers/AdminUrls.java` (`weblog`, `weblogAbsolute`, `entry`,
  `media`, `mediaThumbnail` over `URLStrategy`); `BaseController` exposes it
  as `@ModelAttribute("urls")` built from `weblogger.getUrlStrategy()`.
- Delete `WeblogEntry.getPermalink` (keep the deprecated pure `getPermaLink`
  only if something still calls it — survey: wrapper only; delete both and
  let the wrapper compute), `Weblog.getURL`, `Weblog.getAbsoluteURL`,
  `MediaFile.getPermalink`, `MediaFile.getThumbnailURL`.
- Java callers → `urlStrategy`: `EntryEditController:502,699`,
  `MediaFileBean:144,145`, `EntryAddWithMediaFileController:95`,
  `MediaFileAddController:184`, `GalleryMarkup:73`, `ImageShortcode:104`
  (these two now hold `MediaFileManager` — give them `URLStrategy` too via
  the expander bean), `JPAWeblogManagerImpl:286`-adjacent if any.
- JSPs (raw entities): `editor/EntryEdit.jsp:187,190,198,344`,
  `editor/Entries.jsp:206` (`${post.permalink}` → `${urls.entry(post)}`);
  `editor/ThemeEdit.jsp:87,174`, `editor/PageEdit.jsp:72,73`,
  `editor/Pages.jsp:65`, `editor/TemplateEdit.jsp:81,196`,
  `editor/WeblogConfig.jsp:53,54,56`, `editor/EntryEdit.jsp:344`,
  `tiles/userStatus.jsp:31` (`${actionWeblog.absoluteURL}` →
  `${urls.weblogAbsolute(actionWeblog)}`); `editor/MediaFileImageChooser.jsp:74,75`,
  `editor/MediaFileAddSuccess.jsp:83,119,147`, `editor/MediaFileView.jsp:184,240`
  (`${urls.media(f)}` / `${urls.mediaThumbnail(f)}`); `core/MainMenu.jsp:61,62`,
  `admin/UserEdit.jsp:192` (`${perms.weblog.absoluteURL}` — handled in Task
  16 with the permission view row). `MediaFileEdit.jsp`'s `${bean.permalink}`
  is `MediaFileBean`, a copy — the bean is populated by the controller from
  `urlStrategy`.
- Tests: `AdminUrlsTest`; `BaseControllerTest` (the attribute is present on
  every model); the editor controller tests assert the same URLs from the
  new source (characterisation); `JournalThemeRenderingTest`/`Portfolio…`/
  `Travel…`/`FrontpageRenderingTest` unchanged (wrappers were already the
  source of truth for themes after Task 13).
- Run `grep -rn "\.absoluteURL\|\.permalink\|\.thumbnailURL\|\.permaLink" app/src/main/webapp/WEB-INF/jsps` — every remaining hit must be on `bean.*` or `urls.*`.
- `POJO_ALLOWED` still non-empty.

### Task 16: Queries-behind-getters, identity resolution, authorisation, the entity write

**D5 (three clusters).**

- **Queries:** delete `Weblog.getWeblogEntry`, `getWeblogCategory`,
  `getRecentWeblogEntries`, `getRecentWeblogEntriesByTag`, `getPopularTags`,
  `getEntryCount`; `WeblogCategory.retrieveWeblogEntries`, `isInUse`.
  `WeblogWrapper:259,270,275,282,289,294` and `WeblogCategoryWrapper:81,88`
  call `weblogger.getWeblogEntryManager()` (keeping the `MAX_ENTRIES=100`
  clamp and the "first category" fallback verbatim — they are the template
  API's contract); `JPAWeblogEntryManagerImpl:102,137` call their own query
  (breaking the manager→entity→manager cycle; keep the
  `includeTrashed(true)` branch and its comment — it is the
  category-deletion invariant from the Trash design); `Categories.jsp:89`
  `${category.inUse}` → the two `getCategories` controller methods put a
  `Set<String> categoriesInUse` (or a list of view rows) in the model.
- **Identity:** delete `WeblogEntry.getCreator`, `Weblog.getCreator`,
  `MediaFile.getCreator`, `WeblogEntryTag.getUser`, `WeblogPermission.getWeblog`,
  `WeblogPermission.getUser`. Wrappers resolve via `weblogger.getUserManager()`
  (`WeblogEntryWrapper:90`, `WeblogWrapper:104`, `MediaFileWrapper:100`,
  `WeblogEntryTagWrapper:49`); Java callers (`MailUtil:81-82`,
  `JPAWeblogManagerImpl:224,286,507`, `MembersController:99,104,123,127,129`,
  `SiteModel:313`) resolve through the manager they hold;
  `MainMenuController:63`/`UserEditController:184,212,313,392` build a
  permission view row carrying the resolved `Weblog` (and its URL via
  `urls`), replacing `${perms.weblog.absoluteURL}` in `MainMenu.jsp:61,62`
  and `UserEdit.jsp:192`.
- **Authorisation:** delete `User.hasGlobalPermission(s)`,
  `Weblog.hasUserPermission(s)`; callers (`MetaApi:60`, `UserDataServlet:83`,
  `UtilitiesModel:98,110`, `EntriesController:196`,
  `EntryEditController:442,606,756`, `MailUtil:95`) call
  `userManager.checkPermission(new GlobalPermission(actions), user)` /
  `new WeblogPermission(weblog, user, actions)` directly — keep the exact
  permission objects constructed today; `GlobalPermission(User)` becomes
  `JPAUserManagerImpl.globalPermissionOf(user)` (its only caller is
  `JPAUserManagerImpl:365`; the `role.action.<role>` config read moves with
  it); `EntryEdit.jsp:271` `${authenticatedUser.hasGlobalPermission('admin')}`
  → a model attribute (`isGlobalAdmin`) set where `authenticatedUser` is.
- **The write:** `MediaFile.updateTags` → `JPAMediaFileManagerImpl.updateTags(mediaFile, tags)`;
  `MediaFile.setTagsAsString` becomes pure (or moves alongside).
- Tests: `WeblogLogicTest`, `WeblogCategoryTest`, `MediaFileLogicTest`,
  `GlobalPermissionTest`, `EntryWritePermissionTest` (all drop `mockStatic`;
  the moved behaviour is tested where it now lives — wrapper tests, manager
  tests, controller tests); `MainMenuControllerTest`/`UserEditControllerTest`
  cover the view rows; `MembersControllerTest`.
- `POJO_ALLOWED` down to `Weblog.java` (for `getTheme`).

### Task 17: `Weblog.getTheme()` — last, highest fan-out

- Delete `Weblog.getTheme()`; every caller calls
  `themeManager.getTheme(weblog)` with the `ThemeManager` it holds:
  `WeblogWrapper:64,69,74,79,209,210` (via `weblogger.getThemeManager()` —
  `$model.weblog.stylesheet`, `$weblog.getTemplateByName(...)` in
  `weblog.vm:82,922` keep working through the wrapper),
  `WeblogPageRequest:450`, `PageServlet:368,392,402,411`,
  `SearchServlet:123,127`, `PreviewServlet:245,260`,
  `PreviewResourceServlet:119`, `ResourceServlet:111`, `PageModel:302`,
  `ThemeEditController:82,155,183,231,253,256`,
  `TemplatesController:162,163,165,194,197`, `StylesheetEditController:265`;
  `ThemeEdit.jsp:77` `${actionWeblog.theme.name}` → model attribute
  `currentThemeName`.
- Tests: characterisation throughout; `ThemeEditControllerTest`,
  `TemplatesControllerTest`, `StylesheetEditControllerTest` construct with
  the mock facade; `ThemeReferenceLeakTest` green.
- `POJO_ALLOWED` empty. **`StaticServiceLocatorTest` assertion 3 is now
  permanent.**

### Task 18: The JSP sweep and the browser suite at both context paths

- [x] `grep -rn "actionWeblog\.\(absoluteURL\|theme\)\|\.permalink\|\.thumbnailURL\|\.creator\b\|\.inUse\|hasGlobalPermission\|perms\.weblog" app/src/main/webapp/WEB-INF/jsps` — every hit must be a `bean.*`/`urls.*`/explicit-attribute form.
- [x] `mvn verify -Pit` green; `mvn verify -Pit -Dit.context.path=roller`
  green. `RouteSweepIT` is the acceptance test for every JSP touched in Tasks
  15–17 (a missing EL property is a 500, which the sweep catches as a
  non-200 — and a page that renders chrome with no tile is what the route
  markers catch). Also watch `ThemeIT`, `ThemeMatrixIT`, `GalleryIT`,
  `EditorSeoIT`, `UserAdminIT`, `CategoryIT`.
- [x] If a known flake fires (CLAUDE.md lists three), rerun once; a 403 on an
  admin POST is traced, not rerun.

---

## Stage E — Delete the shim

### Task 19: `WebloggerRuntimeConfig.attach`

**D8.**

- `config/WebloggerRuntimeConfig.java`: `private static volatile PropertiesManager propertiesManager`;
  package-private `attach(PropertiesManager)`/`detach()`; `:75` uses it;
  `null` → every read returns `null` exactly as the broad `catch` does today.
- `SpringWebloggerProvider.bootstrap()` attaches after `initialize()`;
  `shutdown` path detaches. `MockWeblogger` gains `attachRuntimeConfig()`/
  `detachRuntimeConfig()` for the tests that drive runtime reads through a
  mocked `PropertiesManager` (find them: `grep -l "propertiesManager()" app/src/test`
  intersected with `WebloggerRuntimeConfig` users).
- Tests: `WebloggerRuntimeConfigTest` — attached manager's value is returned;
  nothing attached → `null`; `getBooleanProperty` falls back to
  `WebloggerConfig` as today.
- Ledger: `config/WebloggerRuntimeConfig.java`.

### Task 20: Delete `WebloggerFactory`; the test-suite mechanics

**D9.**

- [x] `TestUtils`: `private static volatile Weblogger weblogger`;
  `setupWeblogger()` builds it once per JVM through
  `new SpringWebloggerProvider()` + `bootstrap()` (no static install);
  `public static Weblogger weblogger()` (throws if not set up — the message
  says "call `TestUtils.setupWeblogger()`"); `shutdownWeblogger()`.
- [x] `sed -i '' 's/WebloggerFactory\.getWeblogger()/TestUtils.weblogger()/g'`
  over `app/src/test/java`, then fix imports (`WebloggerFactory` →
  `TestUtils` where not already imported). ~82 files; compile, then run the
  suite.
- [x] `MockWeblogger`: delete `install()`, `uninstall()`,
  `installNotBootstrapped()`, `previousProvider`; it is a builder over a
  mocked facade. `MockWebloggerTest` shrinks accordingly. Any remaining
  `install()` caller is a class whose test was not rewritten in its own task
  — go back and do that, do not work around it here.
- [x] `ControllerTestFixture` (both copies): `LAZY_WEBLOGGER` forwards to a
  fixture-held `Supplier<Weblogger>` (`useWeblogger(...)`, default
  `TestUtils::weblogger`) — the install/setup tests set a mock provider
  instead.
- [x] `SpringWebloggerProvider.bootstrap()`: delete the transitional
  `WebloggerFactory.installProvider(this)`. `WebloggerFactory.java` deleted.
  `RollerLifecycleTest`: no `MockWeblogger.installNotBootstrapped()`
  bracketing — it already uses a mock provider since Task 3.
- [x] `StaticServiceLocatorTest`: assertion 1 becomes "no main or test source
  references `WebloggerFactory`"; `ALLOWED` and `POJO_ALLOWED` deleted; the
  two named residuals remain.
- [x] `grep -rl "mockStatic(WebloggerFactory" app/src/test` empty;
  `grep -rl WebloggerFactory app/src` empty.
- [x] `mvn -pl app verify` green (all gates); `bin/check-diff-coverage.sh`
  against the range that will be pushed — expect green; if the
  parameter-threading churn in error paths trips it, apply CLAUDE.md's ruling
  (accept, say so, no coverage theatre).

### Task 21: CLAUDE.md and the spec

- [x] CLAUDE.md "Architecture Overview → DI Container": rewrite the paragraph
  that says rendering servlets/models/pagers/tasks/`RollerHandlerInterceptor`
  "intentionally still go through the `WebloggerFactory` static shim" —
  they do not; describe the injection shapes table from the spec in two
  sentences, name the two residual statics and the guard test, and point at
  the Stage 2 follow-up.
- [x] CLAUDE.md "Testing Commands": `TestUtils.weblogger()` is how a test
  reaches the tier; `MockWeblogger` is a builder; the `ContextRefreshDoesNotBootstrapTest`
  invariant.
- [x] Spec status → implemented; record the final measured numbers (files
  touched, test files migrated) and any ruling made mid-wave.

## Post-implementation

- Stage 2 spec (configuration as beans) — write it against the landed tree.
- Improvement #2 (cache invalidation as an event) and #3 (application-service
  layer) are now unblocked; `EntryRenderer` is the first service of the
  latter.
