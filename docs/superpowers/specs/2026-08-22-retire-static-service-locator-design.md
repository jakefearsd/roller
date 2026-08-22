# Retire the static service locator — Design

**Date:** 2026-08-22
**Status:** proposed, not yet implemented
**Scope:** how code *obtains* the business tier. Every `WebloggerFactory`
call site in `app/src/main/java` goes away, the class is deleted, and the
behaviour JPA entities currently reach into the container for moves to
services. **No change to the schema, the themes' rendered output, the public
URL surface, or any admin screen's behaviour.** One visible bug fix falls out
(preview URLs, Decision 6) and is called out as such.

## Goal

Make every dependency on the business tier visible in a constructor. Today
the `Weblogger` facade is reached two ways: `@Autowired @Lazy` injection in the
controllers (done in Stage 1A, 2026-08), and `WebloggerFactory.getWeblogger()`
everywhere else — a process-global static resolved at call time. The static is
the last piece of the pre-Spring architecture, and it is why:

- a unit test of a pager, a model, a wrapper or an entity needs either a
  bootstrapped business tier or `mockStatic(WebloggerFactory)` (33 test files
  do the latter);
- nine JPA entities run queries and build URLs from inside getters, so
  `WeblogEntry` cannot be constructed in a test without the container and
  `Weblog.getAbsoluteURL()` costs a config read per call in a `#foreach`;
- the question "what does this class actually depend on?" has no compile-time
  answer — `SiteModel` reaches four managers, `Weblog` reaches five, and
  nothing in their signatures says so;
- two further improvements already identified — explicit cache-invalidation
  events, and an application-service layer shared by the JSP and REST surfaces
  — both want to be built from services that receive their collaborators,
  which is exactly what the entities and the rendering path cannot do today.

## Non-goals

- **Narrowing or dismantling the `Weblogger` facade.** See Decision 1. The
  facade is still how most consumers name the tier; this wave changes how they
  *get* it, not what it is.
- **Retiring `WebloggerConfig` / `WebloggerRuntimeConfig`'s static readers**
  (178 sites in 82 files). That is Stage 2 of the same program, with its own
  spec; see Follow-ups. This wave leaves exactly one deliberate residual on
  its behalf (Decision 8).
- **Converting name-keyed entity lookups into JPA associations**
  (`WeblogEntry.creator`, `WeblogPermission.weblog`). A persistence-model
  change with its own cascade/teardown risks; noted as a follow-up.
- **Touching the static infrastructure singletons that hold no business-tier
  reference**: `CacheManager` and the render caches, `RendererManager`,
  `RollerContext`, `MenuHelper`'s menu XML, `HTMLSanitizer`. They are a
  different family (config-at-class-load, Stage 2's problem, and the cache
  half is improvement #2's). The one exception is `RollerVelocity`, which must
  change shape for the Velocity resource loaders — Decision 4.
- **Making the reflectively-instantiated families Spring beans.** Models and
  tasks keep their config-driven class lists; they receive their dependencies
  through their existing `init(...)` hooks. See Decision 3.

## Measured baseline (2026-08-22, `2f8386ffa`)

`app/src/main/java`: **73 files, 164 call sites** — `getWeblogger()` 149,
`isBootstrapped()` 13, `bootstrap()` 2. **21 sites are inside `static`
methods** (no `this` to inject into). Four further files mention the name in
javadoc only.

| Package | Files | Sites | How the classes are constructed today |
|---|---|---|---|
| `ui.rendering.model` | 9 | 29 | reflectively, from `rendering.*Models` via `ModelLoader` |
| `pojos` + `pojos.wrapper` | 9 | 29 | JPA / `new` |
| `ui.controllers` (+admin/ajax/core/editor) | 7 | 16 | interceptor and servlets `new`'d in Java config; form beans by the data binder |
| `ui.rendering.servlets` | 8 | 13 | `new` inside `ServletRegistrationConfig` `@Bean` methods |
| `business.runnable` | 4 | 12 | reflectively, from `tasks.<name>.class`; raw `Thread` |
| `business.jpa` | 2 | 11 | Spring beans that **already hold an injected `Weblogger`** |
| `business.themes` | 3 | 10 | `WeblogSharedTheme`/`WeblogCustomTheme` `new`'d by `ThemeManagerImpl` |
| `ui.rendering.util` | 7 | 9 | `new` per request by the servlets |
| `ui.core` (+filters/security/menu) | 5 | 8 | filters/listener `new`'d in Java config; static utilities |
| `ui.rendering.pagers` | 6 | 8 | `new` by the models |
| `boot` | 2 | 5 | Spring |
| `business.shortcodes` | 3 | 3 | `new` inside `ShortcodeExpander.DEFAULT`'s static initialiser |
| remaining (`business`, `config`, `search`, `restapi.auth`, `util`, `rendering`, `velocity`) | 8 | 11 | static utilities; Velocity-instantiated loaders; reflective mapper |

Test side: **82 files / 435 direct `WebloggerFactory.getWeblogger()` calls**
(almost all fixture CRUD through `TestUtils`), **40 files using
`MockWeblogger.install()`**, **33 files using
`mockStatic(WebloggerFactory.class)`**, **0 `@SpringBootTest`**. The real
business tier is built once per JVM by `TestUtils.setupWeblogger()` through
`SpringWebloggerProvider`'s no-arg constructor — a standalone
`AnnotationConfigApplicationContext(WebloggerBeanConfig.class)` outside any
Boot context.

Three facts from the survey that make this cheaper than the counts suggest:

1. **Every manager is already an individual Spring bean** (`WebloggerBeanConfig`,
   one `@Bean` per interface), and `@Autowired @Lazy Weblogger` is already the
   proven pattern in `BaseController` and `BaseApiController`. Nothing new has
   to be invented for the container-managed half.
2. **Every rendering model already receives `urlStrategy` through `initData`**
   and calls the static only as a null-fallback; every pager already takes
   `URLStrategy` as its first constructor argument; `SearchResultsPager` is
   already fully injected and is the target shape.
3. **No main-source class latches `WebloggerFactory.getWeblogger()` in a
   field.** Every call resolves at use, so there is no hidden initialisation
   order to preserve beyond the one that already exists (Decision 2).

## Decisions

Settled in design; recorded so a later reader does not relitigate them.

1. **The `Weblogger` facade stays, and is injected — never located.** This
   wave is about *how* the tier is obtained. Whether a class should take
   `WeblogEntryManager` instead of `Weblogger` is the application-service
   improvement's question, and answering it here would double the diff. Rule
   of thumb for the migration: a container-managed class takes `@Lazy
   Weblogger` (or, inside `WebloggerBeanConfig`, whatever sibling it needs, as
   today); a hand-constructed object takes the narrowest thing that works —
   `Weblogger` when it needs several managers, one manager when it needs one.
   New code after this wave is expected to take the narrow form.

2. **`WebloggerProvider` — already the two-method SPI behind the static —
   becomes the bean that replaces it.** `isBootstrapped()` joins `bootstrap()`
   and `getWeblogger()` on the interface; `SpringWebloggerProvider` becomes a
   `@Component` constructed with the `ApplicationContext`, and its
   `bootstrap()` owns the whole sequence the two bootstrap sites currently
   spell out by hand: the `WebloggerStartup.isPrepared()` guard,
   `getBean(Weblogger.class)`, `initialize()`, `release()` in a `finally`.
   `RollerLifecycle` and `InstallController` inject it and call it.
   - **`WebloggerProvider` is injected only where "is the tier up?" is a real
     runtime question**: `BootstrapFilter`, `PersistenceSessionFilter`,
     `RollerSession`, `RollerHandlerInterceptor`, `ApiTokenAuthFilter`,
     `ControlPlaneHostFilter`/`WeblogRequestMapper` (vhost lookup before
     bootstrap must answer "no weblog", not throw), `RollerUserDetailsService`,
     and the two bootstrap sites. Everything else takes `@Lazy Weblogger`.
   - **The prepare-before-construct invariant is preserved by ordering, not by
     a runtime throw, and a test pins the ordering.** Today
     `WebloggerFactory.getWeblogger()` throws before bootstrap; a `@Lazy`
     proxy touched before bootstrap would instead *build* the graph (and fail
     loudly inside `WebloggerBeanConfig.databaseProvider()` if `prepare()` has
     not run, or build an un-`initialize()`d graph if it has). The only window
     in which that could happen — between `prepare()` and `bootstrap()` inside
     `RollerLifecycle.start()` — is same-thread and before the connector
     opens (phase 0, see `RollerLifecycle`'s javadoc). `ContextRefreshDoesNot
     BootstrapTest` asserts that a Boot context refresh with
     `roller.lifecycle.enabled=false` instantiates **no** `WebloggerBeanConfig`
     bean, which is the property every `@Lazy` is there to provide and the one
     a forgotten `@Lazy` would break.
   - `initialize()` is **not** made a bean `initMethod`. It does real JPA work
     and starts the task scheduler thread; keeping it an explicit step inside
     `bootstrap()` preserves the install-mode retry exactly as it behaves now.

3. **Each construction mechanism gets the smallest change that lets a
   dependency in, and the config-driven class lists survive where they are
   genuinely plural.**
   - **Rendering servlets, filters, the session listener and the MVC
     interceptors become beans**, constructed by Spring and handed to
     `ServletRegistrationBean`/`FilterRegistrationBean`/`addInterceptor`
     instead of `new`'d inside them. This is the single cheapest structural
     win: 13 servlet sites, 3 filter/listener sites and 4 interceptor sites
     become constructor injection with no behavioural change.
   - **Models keep `rendering.*Models`**; `initData` gains a `weblogger` entry
     next to the `urlStrategy` it already carries, and every model's
     null-fallback to the static is deleted (the fallback existed only for
     `WeblogCacheWarmupJob`, which passes neither and is fixed to pass both).
   - **Tasks keep `tasks.enabled` / `tasks.<name>.class`**; `RollerTask.init(name)`
     becomes `init(Weblogger, name)`, `ThreadManagerImpl` passes its own
     injected facade, `TaskScheduler` takes it in its constructor.
   - **The request-mapper list is retired.** `rendering.rollerRequestMappers`
     names one class and `rendering.userRequestMappers` is empty; an extension
     point with one implementation, no consumer, and no way to pass a
     dependency is not an extension point. `RequestMappingFilter` (now a bean)
     constructs `WeblogRequestMapper` directly. This is the one reflective
     list deleted, and it is deleted for that reason — not because reflection
     is distasteful; the model and task lists stay.
   - **Pagers, `*Request` objects, wrappers, shortcode handlers and the two
     `WeblogTheme` subclasses take their collaborator in the constructor**
     from whoever constructs them, which is always — transitively — a
     container-managed class.
   - **Static utility methods gain a parameter** (`MenuHelper.getMenu`,
     `MailUtil.*`, `RenderingServletUtils.*`, `PreviewThemeLookup.byName`,
     `PageServlet.rejectionReason`, `FeedServlet.isServable`,
     `MediaResourceServlet.requesterMayEditWeblog`,
     `LuceneIndexManager.convertHitsToEntryList`). Those are the 21 sites with
     no `this`; every caller has the value to pass.
   - **Form beans (`CreateUserBean`, `EntryBean`, `TemplateEditBean`) become
     pure.** The data binder instantiates them, so the manager calls inside
     `copyTo`/`copyFrom` move into the controllers that own the save. This is
     the same principle as Decision 5 applied to the write side.

4. **The two Velocity resource loaders get the facade from the engine's
   application attributes, and `RollerVelocity` is initialised explicitly at
   bootstrap.** Velocity instantiates `RollerResourceLoader` and
   `ThemeResourceLoader` from `velocity.properties`; the sanctioned way to hand
   them a collaborator is `VelocityEngine.setApplicationAttribute(...)`, read
   back via `RuntimeServices` in `init(ExtProperties)`. That means the engine
   can no longer be built in `RollerVelocity`'s static initialiser (which has
   nothing to put in the attribute): `RollerLifecycle.start()` — which already
   calls `RollerContext.setupVelocity()` after bootstrap — calls
   `RollerVelocity.initialize(servletContext, weblogger)` instead, and
   `RenderingTestSupport` does the same. **The engine itself remains a static
   singleton.** That is a deliberate, named residual: `RuntimeSingleton` is
   also static, `RendererManager` and `VelocityRendererFactory` are reached
   statically, and un-staticing the Velocity stack is neither needed for this
   wave's goal nor small. What matters is that the only business-tier
   reference it holds is the one attribute, set once at bootstrap, and the
   guard test (Decision 7) names it.

5. **Entities become data plus invariants; behaviour that needs a
   collaborator moves out.** The survey's per-method map is the checklist;
   the destinations:

   | Cluster | Moves to | Notes |
   |---|---|---|
   | URL generation (`WeblogEntry.getPermalink`, `Weblog.getURL/getAbsoluteURL`, `MediaFile.getPermalink/getThumbnailURL`) | callers call `URLStrategy`; Velocity wrappers use the strategy they already hold (Decision 6); JSPs use a `urls` view helper (`BaseController @ModelAttribute`) | `Weblog.getAbsoluteURL()` is read raw from **11 JSPs**, `entry.permalink` from 4, `mediaFile.permalink/thumbnailURL` from 4, `perms.weblog.absoluteURL` from 2 |
   | Rendering pipeline (`WeblogEntry.render/getTransformedText/getTransformedSummary/displayContent/getDisplayContent`, `Weblog.getInitializedPlugins`, `WeblogPage.render`) | new `EntryRenderer` bean on the facade (`Weblogger.getEntryRenderer()`), replacing static `ContentRenderer`; `ShortcodeExpander` becomes an injected instance whose handlers take `MediaFileManager` | one of two facade additions this wave makes — it is needed by the wrappers, which hold the facade. (The other, found in Task 4: `Weblogger.getVirtualHostRegistry()`. The registry has more consumers than the plan listed — `InitFilter`, `SeoController`, `ControlPlaneHostFilter`, `WeblogRequestMapper` — and a second static holder for it broke `VirtualHostRegistryDbTest` when a test builds a second standalone tier in the same JVM; the facade accessor is what lets the filters and the mapper reach the live instance through what they already hold.) |
   | Query-behind-a-getter (`Weblog.getWeblogEntry/getWeblogCategory/getRecentWeblogEntries(ByTag)/getPopularTags/getEntryCount`, `WeblogCategory.retrieveWeblogEntries/isInUse`) | deleted from the entity; the wrapper (the template API) calls `WeblogEntryManager` directly; `JPAWeblogEntryManagerImpl:102,137` use the manager's own query | breaks the manager→entity→manager cycle |
   | Identity resolution (`*.getCreator`, `WeblogEntryTag.getUser`, `WeblogPermission.getWeblog/getUser`) | deleted; callers resolve via `UserManager`/`WeblogManager`; `MainMenu.jsp`/`UserEdit.jsp` get a view row that carries the resolved weblog | the entity keeps the *name* column it already stores |
   | Authorisation (`User.hasGlobalPermission(s)`, `Weblog.hasUserPermission(s)`, `GlobalPermission(User)`) | callers call `UserManager.checkPermission` directly; `GlobalPermission(User)` becomes a factory on `JPAUserManagerImpl`, its only caller | `${authenticatedUser.hasGlobalPermission('admin')}` in `EntryEdit.jsp` becomes a model attribute |
   | Theme resolution (`Weblog.getTheme`) | callers call `ThemeManager.getTheme(weblog)`; `WeblogSharedTheme`/`WeblogCustomTheme` take `WeblogManager` in their constructors | highest fan-out (20+ Java sites, `weblog.vm`, 10 theme files via `$model.weblog.stylesheet`); sequenced last |
   | A write from an entity (`MediaFile.updateTags` → `MediaFileManager.removeMediaFileTag`) | `JPAMediaFileManagerImpl.updateTags(mediaFile, tags)` | unambiguous |
   | Dead (`WeblogEntry.createAnchor()` — zero callers; `WeblogEntry.hasWritePermissions` — test-only) | deleted | pure subtraction |

   **What stays** is pure: the `post-load` snapshot trio on `WeblogEntry`
   (`snapshotLoadedContent`/`getLoadedStatus`/`contentBeingReplaced` — XML
   `<post-load>`, no container), `createAnchorBase(char separator)` once the
   separator is a parameter, the tag-delta bookkeeping, the permission
   *algebra* (`implies`, `hasAction`, the empty-actions guard), the mapped
   one-to-many traversals on `Weblog` (`getWeblogCategories`, `hasCategory`,
   `getMediaFileDirectories`…), `MediaFileDirectory` entirely,
   `getLocaleInstance`/`getTimeZoneInstance`.

   **Wrappers are the template API and absorb the moved behaviour.** Velocity
   almost never sees a raw pojo (the survey confirms every model and pager
   returns wrappers; the three `getPojo()` uses in `weblog.vm` are for
   unescaped SEO fields and do not call the moved methods), so the theme
   surface — `$entry.displayContent`, `$entry.permalink`, `$entry.creator`,
   `$model.weblog.stylesheet`, `$weblog.getTemplateByName(...)` and the rest —
   is preserved by giving each wrapper the facade:
   `wrap(pojo, URLStrategy, Weblogger)`. **JSPs are the opposite: they see raw
   entities exclusively**, so their call sites are listed per task and
   replaced with the `urls` helper or an explicit model attribute. JSP EL
   throws on a missing property, which is loud; Velocity prints the literal
   reference, which is silent — hence Decision 7's leak test is a
   *prerequisite* of this cluster, not a verification of it.

6. **The wrappers' URL methods use the `URLStrategy` they are constructed
   with, which fixes a preview bug as a side effect.** `WeblogWrapper.getURL/
   getAbsoluteURL` and `WeblogEntryWrapper.getPermalink` today ignore the
   injected (preview-aware) strategy and delegate to the pojo's static lookup
   of the production strategy, so a theme preview renders production-shaped
   weblog/entry URLs. `MediaFileWrapper` has no strategy at all and gains one.
   This is the one intentional behaviour change in the wave; a rendering test
   through `PreviewServlet` pins it.

7. **Two guard tests make the end state hold, and one makes the middle
   safe.**
   - `StaticServiceLocatorTest` (source scan, same family as
     `QualityGatePomTest`/`ControllerMetadataTest`): no main source references
     `WebloggerFactory`; no main-source `static` field has a business-tier
     type (`Weblogger`, any `*Manager`, `URLStrategy`, `VirtualHostRegistry`)
     except the named residuals (`WebloggerRuntimeConfig`'s attached
     `PropertiesManager`, Decision 8; `RollerVelocity`'s engine, Decision 4);
     and no `pojos/**` source references a business-tier type. **During the
     wave it carries an explicit file allowlist, not a count** — a new call
     site in a new file fails immediately even mid-migration, which a ceiling
     would not catch — and each task's definition of done includes removing
     its files from that list. The allowlist is empty, and the
     `WebloggerFactory` clause becomes "the class does not exist", at the end.
   - `ContextRefreshDoesNotBootstrapTest` — Decision 2.
   - `ThemeReferenceLeakTest` renders every bundled theme's templates
     (`weblog`, `permalink`, `page`, `searchresults`, plus `frontpage`, the
     Atom feeds and the error page) through the real pipeline and fails on
     any unresolved `$reference` in the body. It is written **first**, as a
     characterisation test, because Velocity here is lenient and logs nothing
     (`velocity.properties`), and the wave deletes a dozen getters that
     templates reach through wrappers. Existing tests assert this only for
     `$entry.`/`$utils.`/`$model` on a handful of pages.

8. **`WebloggerFactory` is deleted in this wave, and the one residual static
   it leaves behind is `WebloggerRuntimeConfig`'s.** `WebloggerRuntimeConfig
   .getProperty` (line 75) is the single line through which the whole
   runtime-config subsystem reaches the locator, and its 85-odd static callers
   are Stage 2's work. Until then it needs a `PropertiesManager` from
   somewhere: `WebloggerRuntimeConfig.attach(PropertiesManager)` /
   `detach()`, package-private, called by `SpringWebloggerProvider.bootstrap()`
   and `shutdown`, and by `MockWeblogger` for the tests that drive runtime
   reads against a mock. The static holder thus shrinks from "the whole tier,
   reachable from 73 files" to "one `PropertiesManager` behind one deprecated
   facade", pre-bootstrap reads still return `null` exactly as today, and
   Stage 2 deletes it. Deleting the class now rather than leaving it with one
   caller is what makes "nobody can add a call site" true by the compiler
   rather than by a test.

9. **The test suite migrates with the classes, not in a separate pass.** A
   task that gives a class constructor injection rewrites that class's tests
   to pass the collaborator (`MockWeblogger`'s mocked facade or
   `TestUtils.weblogger()`), which is what TDD requires anyway. What remains
   for the final task is mechanical: `WebloggerFactory.getWeblogger()` →
   `TestUtils.weblogger()` across the fixture-CRUD sites (a `sed`),
   `MockWeblogger` loses `install`/`uninstall`/`installNotBootstrapped` and
   becomes a builder, `mockStatic(WebloggerFactory.class)` has no remaining
   users, and `ControllerTestFixture`'s lazy proxy forwards to a
   fixture-held supplier instead of the static. `TestUtils` owns the one
   real graph per JVM (still built through `SpringWebloggerProvider`'s
   standalone context, which keeps its no-arg constructor for exactly this).

## Architecture

### The provider bean

```java
public interface WebloggerProvider {
    boolean isBootstrapped();
    void bootstrap() throws BootstrapException;   // prepare-guard, getBean, initialize, release
    Weblogger getWeblogger();                       // throws IllegalStateException until bootstrapped
}
```

`SpringWebloggerProvider` is `@Component` in production (constructed with the
Boot `ApplicationContext`, into which `WebloggerBeanConfig` is already
component-scanned) and `new SpringWebloggerProvider()` in `TestUtils`, where it
builds its private `AnnotationConfigApplicationContext(WebloggerBeanConfig.class)`
as it does now. During the wave its `bootstrap()` also installs itself into
the legacy static so unmigrated callers keep working; that line goes with the
class in the final task.

### Injection shapes, by family

| Family | Shape | Where the value comes from |
|---|---|---|
| Controllers, REST controllers | `@Autowired @Lazy Weblogger` | unchanged |
| Business beans in `WebloggerBeanConfig` | constructor, siblings or `@Lazy Weblogger` | unchanged; the static calls inside them are pure substitutions for the field they already hold |
| Rendering servlets, ajax servlets | `@Bean` + constructor `@Lazy Weblogger` | `ServletRegistrationConfig` |
| Filters, session listener, interceptors | `@Bean` + constructor (`WebloggerProvider` and/or `@Lazy Weblogger`) | `ServletRegistrationConfig`, `WebMvcConfig`, `SecurityConfig` |
| Models | `init(Map)` reads `initData.get("weblogger")` | servlets (and `WeblogCacheWarmupJob`) |
| Pagers, `*Request` objects, wrappers, theme subclasses, shortcode handlers | constructor | models / servlets / `ThemeManagerImpl` / `WebloggerBeanConfig` |
| Tasks | `init(Weblogger, name)` | `ThreadManagerImpl` |
| Velocity resource loaders | `rsvc.getApplicationAttribute(Weblogger.class.getName())` | `RollerVelocity.initialize(servletContext, weblogger)` |
| Static utilities | explicit parameter | each caller |
| Form beans | none — logic moves to the controller | — |

### What an entity may import after this wave

`org.apache.roller.weblogger.pojos.*` — the entities, **wrappers excepted**
— may not reference `Weblogger`, `WebloggerProvider`, any manager interface,
`URLStrategy`, `PluginManager`, `ThemeManager`, or `ShortcodeExpander`.
`WebloggerException` and the `util` helpers are fine. `pojos.wrapper.*` is
the template API and holds the facade by design (Decision 5), so it is
outside this rule. `StaticServiceLocatorTest` pins this as a source scan, so
the rule cannot rot back into prose. (Corrected 2026-08-22 while implementing
Task 2: the first draft said `pojos/**`, which contradicted Decision 5.)

### The JSP view helper

`BaseController` exposes `@ModelAttribute("urls")` — a small final class
(`AdminUrls`) over the injected `URLStrategy` with `weblog(Weblog)`,
`weblogAbsolute(Weblog)`, `entry(WeblogEntry)`, `media(MediaFile)`,
`mediaThumbnail(MediaFile)`. `${actionWeblog.absoluteURL}` becomes
`${urls.weblogAbsolute(actionWeblog)}`, and so on. It works inside a
`c:forEach`, which a per-page model attribute would not, and it is the one
place the admin JSPs build weblog-content URLs. Nothing else about the JSPs'
use of raw entities changes.

## Sequencing

Five stages, each leaving the tree green and the allowlist shorter.

- **A — Foundation.** The leak test; the guard tests with the full allowlist;
  the provider bean and the two bootstrap sites.
- **B — Container-managed classes.** Pure substitutions inside the tier;
  servlets/filters/interceptors as beans; static utilities gain parameters;
  tasks; themes; `VirtualHostRegistry` as a bean; the mapper list retired;
  Velocity loaders; form beans. Disjoint files — parallelisable in worktrees
  (with the base pinned and verified, per CLAUDE.md).
- **C — The rendering request path.** Models (`initData`), pagers,
  `*Request` objects, wrappers, the warm-up job. Shared test support
  (`RenderingTestSupport`, `EntriesPagerTestSupport`) — serial.
- **D — Entities.** `EntryRenderer`; URL getters; queries/identity/
  authorisation; `MediaFile.updateTags`; `Weblog.getTheme` last; the JSP
  sweep with the browser suite at both context paths. Serial.
- **E — Delete the shim.** `WebloggerRuntimeConfig.attach`, `WebloggerFactory`
  deleted, the test-suite mechanics, guard tests in final form, CLAUDE.md.

B must precede C (servlets are where `weblogger` enters the request path) and
C must precede D (the wrappers must hold the facade before the pojo getters
they delegate to can go).

## Testing

- Unit tests carry every rule above; the browser suite carries the JSP side,
  because no unit test renders a JSP. `RouteSweepIT` at both context paths is
  the acceptance test for Stage D's JSP changes.
- The JaCoCo floors hold throughout (line 0.8700 / branch 0.7900 / rendering
  PACKAGE 0.60). The diff-coverage gate is expected to stay green: unlike the
  SLF4J sweep, the changed lines here are overwhelmingly constructor/injection
  lines on paths the existing tests already drive. If a stage trips it on
  static-method-to-parameter churn inside error paths, the CLAUDE.md ruling
  applies — accept and say so, do not write coverage theatre.
- **Characterisation throughout.** Nothing in Stages B–D changes what a page
  renders, what a feed contains, or what a controller returns, except
  Decision 6. Every existing rendering/controller test should pass with only
  its *wiring* changed; a test whose assertions have to change is a signal to
  stop and look.

## Acceptance criteria

Each is a test (or a named command).

1. `grep -rl WebloggerFactory app/src/main/java` is empty and the class does
   not exist. `StaticServiceLocatorTest`'s allowlist is empty.
2. `StaticServiceLocatorTest`: no main-source `static` field of a
   business-tier type except `WebloggerRuntimeConfig`'s attached
   `PropertiesManager` and `RollerVelocity`'s engine; no entity under
   `pojos/` (wrappers excepted) references a business-tier type.
3. `ContextRefreshDoesNotBootstrapTest`: a Boot context refreshed with
   `roller.lifecycle.enabled=false` and `server.port=0` contains no singleton
   for any bean defined in `WebloggerBeanConfig`.
4. `RollerLifecycleTest` / `InstallControllerTest`: both bootstrap sites go
   through an injected `WebloggerProvider`; `BootstrapFilter` forwards to the
   install wizard while `provider.isBootstrapped()` is false and passes the
   request through once it is true.
5. Every rendering servlet, model, pager, `*Request` object and wrapper is
   constructed with an explicit `Weblogger`; `ModelLoader` throws if
   `initData` lacks `weblogger`; `WeblogCacheWarmupJob` supplies both
   `weblogger` and `urlStrategy`.
6. **Preview URLs (the one behaviour change):** a page rendered through
   `PreviewServlet` with a `theme=` parameter emits preview-shaped URLs
   (`/roller-ui/authoring/preview/<handle>/…?theme=…`, as
   `PreviewURLStrategy.weblogRoot`/`commonParams` build them) from
   `$model.weblog.absoluteURL` and `$entry.permalink`; `$image.permalink`
   follows whatever `PreviewURLStrategy.getMediaFileURL` produces — the test
   asserts it equals that strategy's output, not a hardcoded shape. Today all
   three bypass the preview strategy (`WeblogWrapper.java:248-255`,
   `WeblogEntryWrapper.java:187-189`, `MediaFileWrapper.java:110-117`
   delegate to the pojo's static lookup of the production strategy).
7. `ThemeReferenceLeakTest`: every bundled theme × template renders with no
   unresolved `$reference` in the body — and the test was seen to fail (the
   plan has the implementer delete one wrapper getter temporarily to prove
   it).
8. `EntryRenderer`: `$entry.displayContent`, `$entry.transformedText` and the
   API's rendered-content field produce byte-identical output to before for
   the existing shortcode/markdown fixtures (`WeblogEntryRenderingTest`,
   `ShortcodeExpanderTest`, `EntriesApiReadTest`).
9. `pojos/**`: `WeblogEntry`, `Weblog`, `MediaFile`, `WeblogCategory`,
   `WeblogEntryTag`, `User`, `WeblogPermission`, `GlobalPermission` can each
   be constructed and exercised in a unit test with no `Weblogger` present
   (no `mockStatic`, no `MockWeblogger`). `EntryWritePermissionTest` moves to
   the service that now owns the check.
10. `mvn verify -Pit` and `mvn verify -Pit -Dit.context.path=roller` are
    green after Stage D — in particular `RouteSweepIT`, `ThemeIT`,
    `ThemeMatrixIT`, `GalleryIT`, `EditorSeoIT`, `UserAdminIT`.
11. `WebloggerRuntimeConfigTest`: reads return the attached manager's value;
    with nothing attached they return `null` (pre-bootstrap behaviour
    unchanged).
12. Tests: `grep -rl "mockStatic(WebloggerFactory" app/src/test` is empty;
    `MockWeblogger` has no `install`/`uninstall`/`installNotBootstrapped`;
    `grep -rl WebloggerFactory app/src/test` is empty.
13. **Characterisation:** the full unit suite and the browser suite pass with
    no assertion changed other than for criterion 6.

## Follow-ups (out of scope here, cleared by this wave)

- **Stage 2 — configuration as beans.** `WebloggerConfig` (file-backed,
  loaded in a static initialiser) and `WebloggerRuntimeConfig` (DB-backed,
  now via `attach`) become `StartupConfig` (immutable, the existing loader's
  output) and `RuntimeConfig` (over `PropertiesManager`, with the same
  fallback chain), both injectable; the 178 static reads migrate; the
  reflection-into-a-private-`Properties` test hack, the class-load latching in
  the render caches and `HTMLSanitizer.xssEnabled`, and `attach()` all go.
  Needs its own spec; this wave's rule is only that no code it touches adds a
  new static config read.
- **Render-cache invalidation as an event** (improvement #2) — now possible
  from services that hold their collaborators.
- **An application-service layer** (improvement #3) — `EntryRenderer` is its
  first member; the narrow-injection rule of thumb in Decision 1 is its
  on-ramp.
- **`creator`/`weblog` as mapped associations** rather than name lookups.
- **Un-staticing the Velocity stack** (`RollerVelocity`, `RendererManager`,
  `RuntimeSingleton`) if a second collaborator ever needs to reach a loader.
- **The remaining reflective lists** (`rendering.*Models`, `tasks.*`) could
  become explicit Java registration; not worth it until something needs a
  model or task with a non-facade dependency.
