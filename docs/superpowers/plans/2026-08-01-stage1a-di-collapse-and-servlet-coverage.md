# Stage 1A — Servlet Coverage + Guice→Spring Collapse Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Raise `ui.rendering.servlets` coverage from 0.37 toward the spec's ≥0.60 target, then collapse the Guice business tier into Spring so one container owns the whole object graph, deleting Guice entirely — while the `WebloggerFactory` static seam keeps every existing test and the rendering path working.

**Architecture:** Coverage first (Tasks 1–3): the untested `PreviewServlet` (265 LOC), `MediaResourceServlet` (165), `ResourceServlet` (180), and `CommentAuthenticatorServlet` (102) get tests on the existing `RenderingTestSupport` harness, then the JaCoCo PACKAGE floor rises. DI collapse second (Tasks 4–9): a `@Lazy` Spring `@Configuration` reproduces the 13 Guice bindings (Spring `@Lazy` parameter proxies replace Guice's circular-dependency proxies), a `SpringWebloggerProvider` slots behind the unchanged `WebloggerFactory` seam, Guice is deleted, and controllers migrate to an injected `Weblogger`. Rendering servlets, models, pagers, background tasks, and POJOs stay on the `WebloggerFactory` shim — their migration lands with the Spring Boot plan (Stage 1B), when they become container-managed.

**Tech Stack:** Spring Framework 6.2.17 (already a compile dep), JUnit 5 + Testcontainers harness from Stage 0, JaCoCo ratchet.

## Global Constraints

- Java stays `<release>21</release>`; no dependency version changes except **removing** Guice.
- Spec: `docs/superpowers/specs/2026-08-01-modernization-roadmap-design.md` (Stage 1 step 1). Stage 1B (Spring Boot) depends on this plan's end state.
- TDD; every task ends green; commit per task with this repo's trailer convention.
- No per-test DB truncation exists: fixtures via `TestUtils.setupX` + explicit `@AfterEach` teardown; one surefire JVM for the whole app suite.
- JaCoCo floors never go down (currently: bundle line 0.72 / branch 0.63; PACKAGE line 0.36 on the three `ui.rendering*` packages).
- **The `WebloggerFactory` seam must keep working unchanged throughout**: `isBootstrapped()`, `getWeblogger()`, `bootstrap()`, `bootstrap(provider)`, package-private `installProvider`/`currentProvider`, and `MockWeblogger.install()/uninstall()/installNotBootstrapped()` (14 test files depend on the static-field swap being cheap).
- Bootstrap ordering invariant: business beans must NOT construct before `WebloggerStartup.prepare()` has run (`JPAPersistenceStrategy` needs the prepared `DatabaseProvider`; `ThemeManagerImpl` needs `themes.dir` resolved; `LuceneIndexManager` reads `search.index.dir`). Everything Spring-side is therefore lazy until `WebloggerFactory.bootstrap()` forces the graph.
- Tests need Docker. App suite: `mvn -ntp -pl app test`. Browser ITs: `mvn -ntp verify -Pit` (several minutes; timeout 600000).

## File Structure

| File | Responsibility |
|---|---|
| `app/src/test/java/.../ui/rendering/servlets/PreviewServletRenderingTest.java` | Preview render paths (new) |
| `app/src/test/java/.../ui/rendering/servlets/MediaResourceServletRenderingTest.java` | Media streaming paths (new) |
| `app/src/test/java/.../ui/rendering/servlets/ResourceServletRenderingTest.java` | Theme/weblog resource paths (new) |
| `app/src/test/java/.../ui/rendering/servlets/CommentAuthenticatorServletTest.java` | Authenticator HTML endpoint (new) |
| `app/src/test/java/org/apache/roller/weblogger/TestUtils.java` | + `setupImageMediaFile(...)` fixture |
| `app/src/test/java/.../ui/rendering/servlets/RenderingTestSupport.java` | + `previewServlet()`, `mediaResourceServlet()`, `resourceServlet()`, `commentAuthenticatorServlet()` |
| `app/src/main/java/org/apache/roller/weblogger/business/jpa/WebloggerBeanConfig.java` | The 13 bindings as Spring `@Bean`s (new) |
| `app/src/main/java/org/apache/roller/weblogger/business/SpringWebloggerProvider.java` | WebloggerProvider over an ApplicationContext (new) |
| `app/src/main/java/org/apache/roller/weblogger/business/GuiceWebloggerProvider.java` | **deleted** (Task 5) |
| `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAWebloggerModule.java` | **deleted** (Task 5) |
| 13 impl classes (`JPAWebloggerImpl`, 6 JPA managers, `LuceneIndexManager`, `ThemeManagerImpl`, `ThreadManagerImpl`, …) | Guice annotations stripped, constructors `protected`→`public` |
| `app/src/main/resources/.../config/roller.properties` | provider default swapped; `guice.backend.module` deleted |
| `app/pom.xml` | Guice dependency removed |
| `app/src/main/webapp/WEB-INF/spring-mvc.xml` | + `WebloggerBeanConfig` bean registration |
| `app/src/main/java/.../ui/core/RollerContext.java` | web-context bootstrap + fixed `contextDestroyed` |
| `app/src/main/java/.../ui/controllers/**` | `WebloggerFactory.getWeblogger()` → injected `weblogger` |
| `pom.xml` (parent) | rendering PACKAGE floor raised (Task 3) |
| `CLAUDE.md` | DI description corrected (Task 9) |

---

### Task 1: PreviewServlet rendering tests

**Files:**
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/servlets/RenderingTestSupport.java` (add one factory)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/servlets/PreviewServletRenderingTest.java`

**Interfaces:**
- Consumes: the Stage 0 harness exactly as-is. Key facts: `PreviewServlet.doGet` needs **no authentication** (`WeblogPreviewRequest.getAuthenticUser()` returns null by design; access control is URL-layer only, which mock requests bypass); servletPath must be `/roller-ui/authoring/preview`, pathInfo `/<handle>`; `?theme=<name>` selects a preview theme; `RollerContext.getServletContext()` and `JspFactory` are already installed by the harness; models come from `rendering.previewModels`.
- Produces: `RenderingTestSupport.previewServlet()` returning an `init()`ed `PreviewServlet`.

- [ ] **Step 1: Add the factory to RenderingTestSupport** (next to `pageServlet()`):

```java
    static PreviewServlet previewServlet() throws ServletException {
        return init(new PreviewServlet());
    }
```

- [ ] **Step 2: Write the failing tests**

```java
package org.apache.roller.weblogger.ui.rendering.servlets;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PreviewServlet is the authoring-side render of a weblog. It requires no
 * request principal (access control is URL-layer, in security.xml) — so these
 * tests drive it exactly like the public servlets.
 */
class PreviewServletRenderingTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("previewuser");
        weblog = TestUtils.setupWeblog("previewblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void frontPagePreviewRendersPublishedEntry() throws Exception {
        TestUtils.setupWeblogEntry("preview-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/authoring/preview", "/previewblog");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.previewServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("text/html"));
        assertTrue(response.getContentAsString().contains("preview-entry"),
                "preview must render the entry:\n" + response.getContentAsString());
    }

    @Test
    void themeParameterPreviewsAnotherTheme() throws Exception {
        TestUtils.setupWeblogEntry("themed-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/authoring/preview", "/previewblog");
        request.setParameter("theme", "gaurav");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.previewServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("themed-entry"),
                "theme-preview must still render the weblog's entries");
    }

    @Test
    void permalinkPreviewRendersTheEntry() throws Exception {
        TestUtils.setupWeblogEntry("preview-permalink", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/authoring/preview", "/previewblog/entry/preview-permalink");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.previewServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("preview-permalink"));
    }

    @Test
    void unknownWeblogPreviewIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/authoring/preview", "/nosuchblog");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.previewServlet(), request);

        assertEquals(404, response.getStatus());
    }
}
```

Before running, read `PreviewServlet.doGet` and `WeblogPreviewRequest` once: if the draft/permalink or theme-preview branch needs an extra parameter the code demands (e.g. previewing draft entries via `?previewEntry=`), adjust the request construction — never the production code. Add a fifth test for the draft-preview branch if `?previewEntry=<anchor>` is supported (it renders a DRAFT entry — the inverse of the public servlet's 404).

- [ ] **Step 3: Run, iterate to green**

```bash
mvn -ntp -pl app test -Dtest=PreviewServletRenderingTest
```

- [ ] **Step 4: Commit** — `git add app/src/test && git commit -m "Cover PreviewServlet render paths"` (+ trailers).

---

### Task 2: MediaFile fixture + MediaResourceServlet tests

**Files:**
- Modify: `app/src/test/java/org/apache/roller/weblogger/TestUtils.java` (add fixture)
- Modify: `RenderingTestSupport.java` (add factory)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/servlets/MediaResourceServletRenderingTest.java`

**Interfaces:**
- Consumes: `MediaFileTest` (`app/src/test/java/.../business/MediaFileTest.java`) is the working pattern — copy its `setUp` lines 53–56 for enabling uploads at runtime and its media-file creation shape (lines ~285–301, using classpath image `/hawk.jpg`). Test storage dirs are already configured (`mediafiles.storage.dir=${project.build.testOutputDirectory}/mediafiles`).
- Produces (Task 3 reuses both):
  - `TestUtils.setupImageMediaFile(Weblog weblog, String name)` → returns a persisted, content-backed `MediaFile` (jpeg from `/hawk.jpg`), created in the weblog's root media directory; enable uploads first the way `MediaFileTest` does.
  - `RenderingTestSupport.mediaResourceServlet()`.

- [ ] **Step 1: Write `TestUtils.setupImageMediaFile`** mirroring `MediaFileTest`'s creation code exactly (root `MediaFileDirectory` via `MediaFileManager`, `setInputStream(TestUtils.class.getResourceAsStream("/hawk.jpg"))`, `setContentType("image/jpeg")`, `createMediaFile(...)`, flush, re-fetch by id). Include the runtime `uploads.enabled` flip (copy `MediaFileTest.setUp` lines 53–56) inside the helper if it isn't already on.

- [ ] **Step 2: Write the failing tests.** Read `MediaResourceServlet.doGet` (165 LOC) and `WeblogMediaResourceRequest` first to confirm the URL shape (servletPath `/roller-ui/rendering/media-resources`, pathInfo `/<handle>/<mediaFileId>`); then:

```java
class MediaResourceServletRenderingTest {
    // setUp/tearDown identical shape to the other rendering tests
    // (user "mediauser", weblog "mediablog")

    @Test
    void mediaFileStreamsWithContentType() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "photo.jpg");
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.mediaResourceServlet(), request);

        assertEquals(200, response.getStatus());
        assertEquals("image/jpeg", response.getContentType());
        assertTrue(response.getContentAsByteArray().length > 1000,
                "the real image bytes must be streamed");
    }

    @Test
    void thumbnailParameterStreamsSmallerImage() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "thumb.jpg");
        TestUtils.endSession(true);

        MockHttpServletRequest full = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        MockHttpServletRequest thumb = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        thumb.setParameter("t", "true");

        int fullSize = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), full).getContentAsByteArray().length;
        MockHttpServletResponse thumbResponse = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), thumb);

        assertEquals(200, thumbResponse.getStatus());
        assertTrue(thumbResponse.getContentAsByteArray().length < fullSize,
                "thumbnail must be smaller than the original");
    }

    @Test
    void unknownMediaFileIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/no-such-id");
        assertEquals(404, RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), request).getStatus());
    }

    @Test
    void unknownWeblogIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/nosuchblog/whatever");
        assertEquals(404, RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), request).getStatus());
    }
}
```

- [ ] **Step 3: Run to green; run `MediaFileTest` too** (the fixture must not disturb it):

```bash
mvn -ntp -pl app test -Dtest='MediaResourceServletRenderingTest,MediaFileTest'
```

- [ ] **Step 4: Commit** — `"Add MediaFile test fixture and cover MediaResourceServlet"` (+ trailers).

---

### Task 3: ResourceServlet + CommentAuthenticatorServlet tests; raise the floor

**Files:**
- Modify: `RenderingTestSupport.java` (two factories)
- Test: `ResourceServletRenderingTest.java`, `CommentAuthenticatorServletTest.java`
- Modify: `pom.xml` (parent — PACKAGE rule minimum)

**Interfaces:**
- Consumes: `TestUtils.setupImageMediaFile` (Task 2). `ResourceServlet` streams weblog uploads by **original path** (`getMediaFileByOriginalPath`) under `/roller-ui/rendering/resources/<handle>/<path>`; read its doGet first for the exact lookup. `CommentAuthenticatorServlet.doGet` writes `authenticator.getHtml(request)` with no-cache headers — the default authenticator emits `<!-- custom authenticator would go here -->`.
- Produces: rendering PACKAGE floor raised to measured-minus-0.01.

- [ ] **Step 1: Tests.** `ResourceServletRenderingTest`: one 200-stream test using a media file fetched by its original path (consult `MediaFileTest`/manager for how originalPath is set — if the servlet only serves legacy-path uploads and a fixture can't reach it cheaply, cover the 404 branches and say so in the report); one unknown-weblog 404; one unknown-path 404. `CommentAuthenticatorServletTest`: GET returns 200, content contains `custom authenticator`, and `Cache-Control` header equals `no-cache`.

- [ ] **Step 2: Run the full suite, measure, raise the floor.**

```bash
mvn -ntp -pl app clean test && mvn -ntp -pl app jacoco:report
```

Re-run the Task 9 (Stage 0) python snippet for the three rendering packages. Set the parent-pom PACKAGE rule minimum to (lowest measured − 0.01, truncated to 2dp) — expected to land ≥ 0.55 with Tasks 1–3 done; state the number in the commit message. Floors never go down; if a package regressed, that's a bug to find, not a floor to keep.

- [ ] **Step 3: Prove the raised gate passes:** `mvn -ntp -pl app verify`.

- [ ] **Step 4: Commit** — `"Cover Resource and CommentAuthenticator servlets; raise rendering floor"` (+ trailers).

---

### Task 4: WebloggerBeanConfig + SpringWebloggerProvider

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/business/jpa/WebloggerBeanConfig.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/business/SpringWebloggerProvider.java`
- Modify: the impl-class constructors listed below, `protected` → `public`
- Test: `app/src/test/java/org/apache/roller/weblogger/business/SpringWebloggerProviderTest.java`

**Interfaces:**
- Consumes: the 13 Guice bindings from `JPAWebloggerModule` (lines 47–66) and these constructor signatures (all currently `protected` unless noted):
  - `JPAWebloggerImpl(JPAPersistenceStrategy, IndexManager, MediaFileManager, FileContentManager, PluginManager, PropertiesManager, ThemeManager, ThreadManager, UserManager, WeblogManager, WeblogEntryManager, URLStrategy) throws WebloggerException`
  - `JPAPersistenceStrategy(DatabaseProvider) throws WebloggerException`
  - `JPAPropertiesManagerImpl(JPAPersistenceStrategy)`, `JPAThreadManagerImpl(JPAPersistenceStrategy)`, `JPAUserManagerImpl(JPAPersistenceStrategy)`
  - `JPAWeblogManagerImpl(Weblogger, JPAPersistenceStrategy)`, `JPAWeblogEntryManagerImpl(Weblogger, JPAPersistenceStrategy)`, `JPAMediaFileManagerImpl(Weblogger, JPAPersistenceStrategy)`
  - `LuceneIndexManager(Weblogger)`, `ThemeManagerImpl(Weblogger)`
  - public no-arg: `PluginManagerImpl`, `FileContentManagerImpl`, `MultiWeblogURLStrategy`
- Produces (Tasks 5–6 rely on):
  - `WebloggerBeanConfig` — `@Configuration @Lazy`, one `@Bean` per binding; **`@Lazy` on every `Weblogger` method parameter** (this replaces Guice's circular-dependency proxying: `JPAWebloggerImpl` needs all managers, five managers need `Weblogger` back); `@Bean(destroyMethod = "shutdown")` on the `Weblogger` bean; `DatabaseProvider` bean = `WebloggerStartup.getDatabaseProvider()` (the prepared one — note: Guice used to JIT-construct a second instance; using the prepared singleton is the honest behavior and identical in configuration).
  - `SpringWebloggerProvider implements WebloggerProvider` — no-arg ctor builds an `AnnotationConfigApplicationContext(WebloggerBeanConfig.class)` **inside `bootstrap()`** (never earlier — the prepare()-ordering invariant); second ctor `SpringWebloggerProvider(ApplicationContext existing)` for the webapp (Task 6); `getWeblogger()` returns the cached `context.getBean(Weblogger.class)`.

- [ ] **Step 1: Widen constructors.** `protected` → `public` on the constructors of: `JPAWebloggerImpl`, `JPAPersistenceStrategy`, `JPAPropertiesManagerImpl`, `JPAThreadManagerImpl`, `JPAUserManagerImpl`, `JPAWeblogManagerImpl`, `JPAWeblogEntryManagerImpl`, `JPAMediaFileManagerImpl`, `LuceneIndexManager`, `ThemeManagerImpl`. (Guice reached them reflectively; Spring `@Bean` methods call them across packages.) Leave every Guice annotation in place for now — they come out with the dependency in Task 5.

- [ ] **Step 2: Write the failing test** (DB-backed; the context must build the same graph `TestUtils.setupWeblogger` builds today):

```java
package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.business.startup.WebloggerStartup;
import org.apache.roller.weblogger.testing.RollerDatabaseExtension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SpringWebloggerProviderTest {

    @Test
    void bootstrapBuildsTheFullGraphWithSingletons() throws Exception {
        RollerDatabaseExtension.ensureSchema();
        if (!WebloggerStartup.isPrepared()) {
            WebloggerStartup.prepare();
        }

        SpringWebloggerProvider provider = new SpringWebloggerProvider();
        provider.bootstrap();
        Weblogger weblogger = provider.getWeblogger();

        assertNotNull(weblogger.getWeblogManager());
        assertNotNull(weblogger.getWeblogEntryManager());
        assertNotNull(weblogger.getUserManager());
        assertNotNull(weblogger.getMediaFileManager());
        assertNotNull(weblogger.getIndexManager());
        assertNotNull(weblogger.getThemeManager());
        assertNotNull(weblogger.getPluginManager());
        assertNotNull(weblogger.getThreadManager());
        assertNotNull(weblogger.getPropertiesManager());
        assertNotNull(weblogger.getUrlStrategy());
        // the circular edge: the manager's Weblogger proxy must resolve back
        // to the same singleton graph (same manager instance both ways)
        assertSame(weblogger.getWeblogManager(), provider.getWeblogger().getWeblogManager());
    }
}
```

(Note: do NOT call `weblogger.initialize()` here — this test proves construction, not lifecycle; `initialize()` starts the scheduler thread and belongs to the seam-level flow that Task 5 exercises via the whole suite. If the JVM already has a bootstrapped Weblogger from earlier tests, this builds a second graph over the same DB — fine for construction assertions; do not install it into `WebloggerFactory`.)

- [ ] **Step 3: Implement** `WebloggerBeanConfig` + `SpringWebloggerProvider` per the Produces block. Run:

```bash
mvn -ntp -pl app test -Dtest=SpringWebloggerProviderTest
```

Circular-dependency failure mode to watch: `BeanCurrentlyInCreationException` means a `@Lazy` is missing on a `Weblogger` parameter.

- [ ] **Step 4: Full suite** (`mvn -ntp -pl app test`) **, commit** — `"Add Spring bean config and provider for the business tier"` (+ trailers).

---

### Task 5: Cut over and delete Guice

**Files:**
- Modify: `app/src/main/resources/org/apache/roller/weblogger/config/roller.properties` (lines ~392–398)
- Delete: `GuiceWebloggerProvider.java`, `JPAWebloggerModule.java`
- Modify: the 13 annotated impl classes (strip `com.google.inject.*`)
- Modify: `app/pom.xml` (remove the Guice dependency, `guice.version` property)

**Interfaces:**
- Consumes: Task 4's provider. `weblogger.provider.class` is read reflectively by `WebloggerFactory.bootstrap()` (no-arg construction) — the no-arg `SpringWebloggerProvider` is what makes the cutover a one-line property change.
- Produces: a Guice-free build. `TestUtils.setupWeblogger()` now transparently builds the Spring graph via the unchanged seam.

- [ ] **Step 1:** In `roller.properties`: set `weblogger.provider.class=org.apache.roller.weblogger.business.SpringWebloggerProvider`; delete the `guice.backend.module` key and its comment block.
- [ ] **Step 2:** Delete `GuiceWebloggerProvider.java` and `JPAWebloggerModule.java`. Strip `@com.google.inject.Inject`/`@com.google.inject.Singleton` annotations and imports from: `WebloggerImpl`, `JPAWebloggerImpl`, `JPAPersistenceStrategy`, `JPAPropertiesManagerImpl`, `JPAThreadManagerImpl`, `JPAUserManagerImpl`, `JPAWeblogManagerImpl`, `JPAWeblogEntryManagerImpl`, `JPAMediaFileManagerImpl`, `LuceneIndexManager`, `ThemeManagerImpl`, `ThreadManagerImpl` (verify the full list with `grep -rln "com.google.inject" app/src/main/java`). Remove the Guice `<dependency>` and `<guice.version>` from `app/pom.xml`.
- [ ] **Step 3:** Prove nothing references Guice: `grep -rn "com.google.inject\|Guice" app/src/main/java app/src/test/java` → only the historical comment in `RollerHandlerInterceptorTest` may remain (update it while there).
- [ ] **Step 4:** Full suite: `mvn -ntp -pl app test` — 24 DB-backed classes now bootstrap through Spring; all ~2070 tests must pass. Then `mvn -ntp -pl app verify` (ratchet).
- [ ] **Step 5: Commit** — `"Replace Guice with Spring as the business-tier container"` (+ trailers).

---

### Task 6: Webapp wiring and lifecycle

**Files:**
- Modify: `app/src/main/webapp/WEB-INF/spring-mvc.xml`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/core/RollerContext.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/InstallController.java`

**Interfaces:**
- Consumes: root `WebApplicationContext` config location is `/WEB-INF/security.xml /WEB-INF/spring-mvc.xml` (web.xml:9-12); `RollerContext` IS the `ContextLoaderListener` (builds the root context at `contextInitialized` line ~116, **before** `prepare()` at ~153 — which is exactly why every business bean is `@Lazy`); `contextDestroyed` (lines ~216–221) currently calls `WebloggerFactory.getWeblogger().shutdown()` unguarded and never calls `super.contextDestroyed`.
- Produces: in the webapp, controllers and the business tier share ONE context; `java -jar`-era Boot conversion (Stage 1B) inherits a single-container world.

- [ ] **Step 1:** Register the config in the root context — in `spring-mvc.xml`, next to the component-scan:

```xml
    <!-- Business tier (all lazy: nothing constructs until WebloggerFactory.bootstrap()
         runs after WebloggerStartup.prepare() -- see RollerContext) -->
    <bean class="org.apache.roller.weblogger.business.jpa.WebloggerBeanConfig"/>
```

- [ ] **Step 2:** In `RollerContext.contextInitialized`, replace the plain `WebloggerFactory.bootstrap()` call with:

```java
            WebloggerFactory.bootstrap(new SpringWebloggerProvider(
                    org.springframework.web.context.support.WebApplicationContextUtils
                            .getRequiredWebApplicationContext(servletContext)));
```

(imports at top, not inline — shown inline here for precision). In `contextDestroyed`, guard and delegate:

```java
    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        if (WebloggerFactory.isBootstrapped()) {
            WebloggerFactory.getWeblogger().shutdown();
        }
        CacheManager.shutdown();
        super.contextDestroyed(sce);   // closes the root context (was never called before)
    }
```

(The `Weblogger` bean's `destroyMethod="shutdown"` makes the context-close path also call shutdown; `shutdown()` must therefore tolerate being called twice — verify `WebloggerImpl.shutdown()`/`JPAWebloggerImpl.shutdown()` are idempotent-enough (they are: executor `shutdownNow` and EMF `close` tolerate repetition; if the EMF close throws IllegalStateException on double-close, add an `isOpen()` guard in `JPAPersistenceStrategy.shutdown()` — that is production code, keep the change minimal and tested by the IT run.)

- [ ] **Step 3:** `InstallController`'s bootstrap call (~line 169): inject the context and use the same provider —

```java
    private final org.springframework.context.ApplicationContext applicationContext;

    public InstallController(org.springframework.context.ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }
```

and replace its `WebloggerFactory.bootstrap()` with `WebloggerFactory.bootstrap(new SpringWebloggerProvider(applicationContext))`.

- [ ] **Step 4:** Prove the webapp: `mvn -ntp verify -Pit` — the full browser IT suite (deploys to real Tomcat, exercises login, authoring, public pages). All green.
- [ ] **Step 5: Commit** — `"Wire the Spring business tier into the webapp context"` (+ trailers).

---

### Task 7: Inject Weblogger into controllers — base + core + admin

**Files:**
- Modify: `ui/controllers/BaseController.java`, all of `ui/controllers/core/` (5 files with calls), `ui/controllers/admin/` (4 files)

**Interfaces:**
- Produces: `BaseController` gains (next to the existing `@Autowired MessageSource` — same house pattern):

```java
    @Autowired
    @org.springframework.context.annotation.Lazy
    protected Weblogger weblogger;
```

  The `@Lazy` is load-bearing: controllers are constructed at context refresh, **before** `prepare()` — the proxy defers business-graph construction to first use. (This is deliberate field injection matching the existing `messageSource` precedent rather than touching 34 constructors; the spec's constructor-injection language is satisfied where it matters — the managers themselves.)
- Migration recipe per class (Tasks 7–9 use it verbatim): every `WebloggerFactory.getWeblogger()` inside a `BaseController` subclass becomes `weblogger`; remove the now-unused `WebloggerFactory` import; controllers NOT extending BaseController (check `ui.controllers.ajax`) are left on the shim with a one-line report note.

- [ ] **Step 1:** Add the field to `BaseController`; migrate its own 4 call sites.
- [ ] **Step 2:** Migrate `ui/controllers/core/` (28 calls / 5 files — note `InstallController`/`SetupController` branch on `WebloggerFactory.isBootstrapped()`: **keep those static `isBootstrapped()` calls** — only `getWeblogger()` migrates) and `ui/controllers/admin/` (18 calls / 4 files).
- [ ] **Step 3:** Run the controller test classes for both packages plus the full suite:

```bash
mvn -ntp -pl app test
```

The 14 `MockWeblogger`-based test files must still pass — field injection means tests that instantiate controllers directly need the field set; check how existing controller tests obtain instances (`ControllerTestFixture`) and set the field the same way it sets `messageSource` (if it doesn't, a one-line reflection assist in the fixture is the right fix — in test code).
- [ ] **Step 4: Commit** — `"Inject Weblogger into base, core, and admin controllers"` (+ trailers).

---

### Task 8: Inject Weblogger into editor controllers (part 1)

**Files:** the first 13 files of `ui/controllers/editor/` in alphabetical order.

Apply the Task 7 recipe verbatim per file. Run `mvn -ntp -pl app test` (editor controller tests are the big suite). Commit — `"Inject Weblogger into editor controllers (1/2)"` (+ trailers).

### Task 9: Editor controllers (part 2) + docs + final verification

**Files:** remaining `ui/controllers/editor/` files, `ui/controllers/ajax/` (migrate only if they extend BaseController), `CLAUDE.md`.

- [ ] **Step 1:** Finish the editor package with the same recipe; check `grep -rn "WebloggerFactory" app/src/main/java/org/apache/roller/weblogger/ui/controllers/` afterwards — remaining hits must be only `isBootstrapped()`/`bootstrap(...)` (Install/Setup) or non-BaseController classes, each named in the report.
- [ ] **Step 2:** `CLAUDE.md`: update the architecture section — DI is now "Spring (single container; business beans in `WebloggerBeanConfig`, lazy until `WebloggerFactory.bootstrap()`)"; remove the Guice mention.
- [ ] **Step 3:** Final verification battery:

```bash
mvn -ntp -pl app clean verify        # suite + ratchet
mvn -ntp verify -Pit                 # browser ITs
bin/check-diff-coverage.sh HEAD~1    # after a fresh jacoco:report
```

- [ ] **Step 4:** Record the remaining `WebloggerFactory` call-site count by package (`grep -rc` summary) in the commit body — the Stage 1B (Boot) plan picks the rest up (servlets, models, pagers, tasks, pojos, themes).
- [ ] **Step 5: Commit** — `"Finish editor-controller injection; document the single-container architecture"` (+ trailers).

---

## Self-Review (performed at plan-writing time)

- **Spec coverage:** Stage 1 step 1's whole scope (Spring beans, constructor-injected managers, shim retained for later call-site migration, Guice deleted) lands in Tasks 4–9; the flagged servlet-coverage follow-up lands in Tasks 1–3 with the floor raise. The spec's "controllers and rendering servlets first" ordering is honored for controllers; rendering servlets deliberately wait for Boot (they aren't container-managed objects until then) — recorded as an explicit decision, not an omission.
- **Ordering hazards addressed:** prepare()-before-graph invariant (lazy everything + provider builds context inside `bootstrap()`); circular `Weblogger` edge (`@Lazy` parameters); double-shutdown (guarded `contextDestroyed` + idempotence check); `MockWeblogger` seam untouched.
- **Placeholder scan:** Task 2/3 defer exact URL-parsing details to a read-the-servlet-first step with concrete fallback instructions — deliberate, since `WeblogMediaResourceRequest`'s parse shape must be confirmed at implementation time; everything else carries exact values.
- **Type consistency:** `SpringWebloggerProvider` ctor pair defined in Task 4 = used in Tasks 5 (no-arg via property) and 6 (context-arg); `setupImageMediaFile` defined in Task 2 = consumed in Task 3.
