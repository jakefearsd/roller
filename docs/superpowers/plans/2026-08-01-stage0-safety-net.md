# Stage 0 — Safety Net Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put the untested public request path (rendering servlets + Velocity) under test, run the browser IT suite in CI, and turn on the JaCoCo coverage ratchet — before any Stage 1 platform surgery begins.

**Architecture:** In-JVM servlet tests drive the real `PageServlet`/`FeedServlet`/`SearchServlet`/`CommentServlet` with spring-test mock requests against the per-JVM Testcontainers PostgreSQL and the real Velocity templates (a small static harness satisfies the container-only dependencies: `RollerContext.servletContext`, `JspFactory`, context URLs). A new anonymous-visitor browser IT covers the same surface end-to-end through Chrome. CI gains an IT job and a diff-coverage gate; the existing (currently skipped) JaCoCo check gets real floors.

**Tech Stack:** JUnit 5, spring-test 6.2.17 (MockHttpServletRequest/Response, MockServletContext), Mockito, Testcontainers PostgreSQL 16, Selenide 7 + headless Chrome, JaCoCo 0.8.15, diff-cover (pip), GitHub Actions.

## Global Constraints

- Java compile target stays `<release>21</release>`; do not upgrade any dependency versions in this stage (Boot/Tomcat/Java 25 land in Stage 1).
- Spec: `docs/superpowers/specs/2026-08-01-modernization-roadmap-design.md`. Stage 0 exit criteria: every anonymous route exercised in CI; JaCoCo `check` active and failing on regression; browser ITs green in CI.
- TDD for every task: write the failing test, watch it fail, make it pass, commit.
- **There is no per-test DB truncation.** `RollerDatabaseExtension` is never registered (its `beforeEach` never runs; only its static `ensureSchema()` is used). Every DB test creates fixtures via `TestUtils.setupX` and removes them in `@AfterEach` via `TestUtils.teardownWeblog(id)` / `TestUtils.teardownUser(userName)` + `TestUtils.endSession(true)` — copy the shape of `app/src/test/java/org/apache/roller/weblogger/business/WeblogPageTest.java`.
- One surefire JVM for the whole app suite (`reuseForks=true`). Static state installed by the rendering harness (ServletContext, JspFactory) is installed once and **never torn down**; render caches are per-JVM singletons that are never cleared automatically — tests must call `CacheManager.clear()` and/or use unique weblog handles.
- `it-selenium/.../support/Routes.java` path convention: every route is a single `"/roller-ui/..."` string literal (the app module's `RouteCoverageTest` reads that file as text).
- Never lower a JaCoCo minimum once set. Never edit an applied migration.
- Unit tests need Docker running. Run app-module tests with `mvn -ntp -pl app test`; a single class with `-Dtest=ClassName`.
- Commit messages end with the standard Co-Authored-By/Claude-Session trailer used in this repo.

## File Structure

| File | Responsibility |
|---|---|
| `app/pom.xml` | + `spring-test` (test scope) |
| `app/src/test/java/org/apache/roller/weblogger/ui/rendering/servlets/RenderingTestSupport.java` | One-time rendering runtime (ServletContext into `RollerContext`, JspFactory stub, context URLs), servlet/request/response builders |
| `.../servlets/PageServletRenderingTest.java` | Front page, permalink, category, 404 paths |
| `.../servlets/FeedServletRenderingTest.java` | RSS/Atom/comments feeds, 404 paths |
| `.../servlets/SearchServletRenderingTest.java` | Search page render, 400 path |
| `.../servlets/CommentServletRenderingTest.java` | Comment POST: approved, moderated, rejected, 404 |
| `app/src/test/java/org/apache/roller/weblogger/ui/rendering/WeblogRequestMapperTest.java` | Public-URL → servlet forward mapping |
| `app/src/test/resources/WEB-INF/velocity.properties` | **deleted** (stale copy missing `resource.loader.webapp.path`) |
| `it-selenium/src/test/resources/seed-it-data.sql` | + one published entry |
| `it-selenium/src/test/java/org/apache/roller/it/PublicSurfaceIT.java` | Anonymous browser sweep of the public surface |
| `it-selenium/.../support/RollerIT.java` | + protected `getAnonymously(url)` (hoisted from `AuthoringJourneyIT`) |
| `.github/workflows/main.yml` | + IT job, + diff-coverage step, − stale comment |
| `pom.xml` (parent) | JaCoCo floors set, check un-skipped, PACKAGE rule for rendering |
| `bin/check-diff-coverage.sh` | Local/CI diff-coverage gate |
| `CLAUDE.md` | Corrected test-harness description, gates documented |

---

### Task 1: Rendering test harness + front-page smoke test

**Files:**
- Modify: `app/pom.xml` (test dependencies block, near `mockito-core`)
- Delete: `app/src/test/resources/WEB-INF/velocity.properties`
- Create: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/servlets/RenderingTestSupport.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/servlets/PageServletRenderingTest.java`

**Interfaces:**
- Consumes: `TestUtils` fixture builders; `RollerContext` private static `servletContext` field; spring-test mocks.
- Produces (Tasks 2–5 rely on these exact signatures):
  - `RenderingTestSupport.ensureRenderingRuntime()` — idempotent, call first in every `@BeforeEach`
  - `RenderingTestSupport.clearRenderCaches()`
  - `RenderingTestSupport.pageServlet() / feedServlet() / searchServlet() / commentServlet()` — returns an `init()`ed servlet
  - `RenderingTestSupport.anonymousGet(String servletPath, String pathInfo)` / `anonymousPost(...)` — returns `MockHttpServletRequest` with contextPath `/roller` and `skipCache` attribute set
  - `RenderingTestSupport.execute(HttpServlet, MockHttpServletRequest)` — returns `MockHttpServletResponse`, releases the JPA session afterwards

- [ ] **Step 1: Add spring-test and delete the stale velocity.properties**

In `app/pom.xml`, next to the existing `mockito-core` test dependency, add:

```xml
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-test</artifactId>
            <version>${spring.version}</version>
            <scope>test</scope>
        </dependency>
```

Then:

```bash
git rm app/src/test/resources/WEB-INF/velocity.properties
```

(That copy lacks `resource.loader.webapp.path`, so macros like `#showWeblogEntriesPager` could never resolve through it; the harness serves the real `app/src/main/webapp/WEB-INF/velocity.properties` instead.)

- [ ] **Step 2: Write the failing smoke test**

`PageServletRenderingTest.java`:

```java
package org.apache.roller.weblogger.ui.rendering.servlets;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders real Velocity themes through the real PageServlet against the
 * Testcontainers database. First tests ever on the anonymous-visitor path.
 */
class PageServletRenderingTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("pagerenderuser");
        weblog = TestUtils.setupWeblog("pagerenderblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void frontPageRendersPublishedEntry() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("smoke-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("text/html"),
                "front page must be html but was: " + response.getContentType());
        String body = response.getContentAsString();
        assertTrue(body.contains("smoke-entry"),
                "entry title must appear on the front page:\n" + body);
        assertTrue(body.contains("blah blah entry"),
                "entry text must appear on the front page:\n" + body);
    }
}
```

(`TestUtils.setupWeblogEntry(anchor, weblog, user)` sets title = anchor and text = `"blah blah entry"`, status PUBLISHED, and picks the weblog's `General` category — created automatically because the test config sets `newuser.categories=General`.)

- [ ] **Step 3: Run it to verify it fails**

```bash
mvn -ntp -pl app test -Dtest=PageServletRenderingTest
```
Expected: COMPILATION ERROR — `RenderingTestSupport` does not exist.

- [ ] **Step 4: Write RenderingTestSupport**

```java
package org.apache.roller.weblogger.ui.rendering.servlets;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.jsp.JspApplicationContext;
import jakarta.servlet.jsp.JspEngineInfo;
import jakarta.servlet.jsp.JspFactory;
import jakarta.servlet.jsp.PageContext;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.ui.core.RollerContext;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.mock.web.MockServletContext;

/**
 * Boots just enough of the container environment for the rendering servlets
 * to run in a plain JUnit JVM: a ServletContext rooted at src/main/webapp
 * (RollerVelocity's one-shot static init reads /WEB-INF/velocity.properties
 * through it), a JspFactory (PageServlet and SearchServlet call
 * JspFactory.getDefaultFactory() unguarded), and the context URLs that
 * InitFilter would normally set. Installed once per JVM and never torn down:
 * the whole app suite shares one forked JVM and RollerVelocity cannot be
 * re-initialized.
 */
final class RenderingTestSupport {

    private static boolean runtimeReady;

    private RenderingTestSupport() {
    }

    static synchronized void ensureRenderingRuntime() throws Exception {
        TestUtils.setupWeblogger();
        if (runtimeReady) {
            return;
        }
        installServletContext();
        JspFactory.setDefaultFactory(new MapBackedJspFactory());
        WebloggerRuntimeConfig.setAbsoluteContextURL("http://localhost:8080/roller");
        WebloggerRuntimeConfig.setRelativeContextURL("/roller");
        runtimeReady = true;
    }

    /** Render caches are per-JVM singletons and nothing clears them between tests. */
    static void clearRenderCaches() {
        CacheManager.clear();
    }

    private static void installServletContext() throws Exception {
        MockServletContext context = new WebappServletContext();
        Field field = RollerContext.class.getDeclaredField("servletContext");
        field.setAccessible(true);
        field.set(null, context);
    }

    /**
     * Serves app/src/main/webapp. The Velocity webapp resource loader asks for
     * some paths without a leading slash ("templates/weblog/..."), which the
     * base MockServletContext would reject.
     */
    private static final class WebappServletContext extends MockServletContext {
        WebappServletContext() {
            super("file:src/main/webapp");
        }

        @Override
        public InputStream getResourceAsStream(String path) {
            return super.getResourceAsStream(path.startsWith("/") ? path : "/" + path);
        }
    }

    /**
     * The PageContext stub is map-backed so CalendarModel's
     * setAttribute/findAttribute round-trip and the sidebar calendar renders.
     */
    private static final class MapBackedJspFactory extends JspFactory {
        @Override
        public PageContext getPageContext(Servlet servlet, ServletRequest request,
                ServletResponse response, String errorPageURL, boolean needsSession,
                int buffer, boolean autoflush) {
            Map<String, Object> attributes = new HashMap<>();
            PageContext pageContext = Mockito.mock(PageContext.class);
            Mockito.doAnswer(invocation -> {
                attributes.put(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(pageContext).setAttribute(Mockito.anyString(), Mockito.any());
            Mockito.when(pageContext.findAttribute(Mockito.anyString()))
                    .thenAnswer(invocation -> attributes.get(invocation.getArgument(0, String.class)));
            return pageContext;
        }

        @Override
        public void releasePageContext(PageContext pc) {
        }

        @Override
        public JspEngineInfo getEngineInfo() {
            return null;
        }

        @Override
        public JspApplicationContext getJspApplicationContext(ServletContext context) {
            return null;
        }
    }

    static PageServlet pageServlet() throws ServletException {
        return init(new PageServlet());
    }

    static FeedServlet feedServlet() throws ServletException {
        return init(new FeedServlet());
    }

    static SearchServlet searchServlet() throws ServletException {
        return init(new SearchServlet());
    }

    static CommentServlet commentServlet() throws ServletException {
        return init(new CommentServlet());
    }

    private static <T extends HttpServlet> T init(T servlet) throws ServletException {
        servlet.init(new MockServletConfig(RollerContext.getServletContext()));
        return servlet;
    }

    static MockHttpServletRequest anonymousGet(String servletPath, String pathInfo) {
        return anonymousRequest("GET", servletPath, pathInfo);
    }

    static MockHttpServletRequest anonymousPost(String servletPath, String pathInfo) {
        return anonymousRequest("POST", servletPath, pathInfo);
    }

    private static MockHttpServletRequest anonymousRequest(String method,
            String servletPath, String pathInfo) {
        MockHttpServletRequest request =
                new MockHttpServletRequest(method, "/roller" + servletPath + pathInfo);
        request.setContextPath("/roller");
        request.setServletPath(servletPath);
        request.setPathInfo(pathInfo);
        // honoured by PageServlet only; FeedServlet has no cache escape, use
        // clearRenderCaches() plus unique handles there
        request.setAttribute("skipCache", "true");
        return request;
    }

    static MockHttpServletResponse execute(HttpServlet servlet,
            MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            servlet.service(request, response);
        } finally {
            // what PersistenceSessionFilter would do at end of request
            TestUtils.endSession(false);
        }
        return response;
    }
}
```

- [ ] **Step 5: Run the smoke test until it passes**

```bash
mvn -ntp -pl app test -Dtest=PageServletRenderingTest
```
Expected: PASS. If the body renders but assertions fail, print the body (the assertion message includes it) — a missing macro means the ServletContext is not serving `/WEB-INF/velocity/weblog.vm`; check the working directory is `app/` (surefire default) so `file:src/main/webapp` resolves.

- [ ] **Step 6: Run the full app suite to prove no cross-test damage**

```bash
mvn -ntp -pl app test
```
Expected: all tests pass (the harness's static installs must not break the ~90 existing test classes sharing the JVM).

- [ ] **Step 7: Commit**

```bash
git add -A app/pom.xml app/src/test
git commit -m "Add in-JVM rendering harness and first PageServlet render test"
```

---

### Task 2: PageServlet behavior tests

**Files:**
- Test (extend): `app/src/test/java/org/apache/roller/weblogger/ui/rendering/servlets/PageServletRenderingTest.java`

**Interfaces:**
- Consumes: `RenderingTestSupport` exactly as produced by Task 1.
- Produces: nothing new.

- [ ] **Step 1: Add the failing tests**

Add to `PageServletRenderingTest` (imports: `org.apache.roller.weblogger.pojos.WeblogCategory`, `org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus`):

```java
    @Test
    void permalinkRendersEntryContent() throws Exception {
        TestUtils.setupWeblogEntry("perma-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog/entry/perma-entry");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("perma-entry"), "permalink must show the entry:\n" + body);
        assertTrue(body.contains("blah blah entry"), "permalink must show the text:\n" + body);
    }

    @Test
    void draftEntryPermalinkIsNotFound() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        WeblogCategory category = managed.getWeblogCategories().iterator().next();
        TestUtils.setupWeblogEntry("draft-entry", category, PubStatus.DRAFT, managed, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog/entry/draft-entry");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(404, response.getStatus(), "a draft must never render publicly");
    }

    @Test
    void unknownWeblogIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/nosuchblog");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(404, response.getStatus());
    }

    @Test
    void categoryPageRendersItsEntries() throws Exception {
        TestUtils.setupWeblogEntry("category-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog/category/General");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("category-entry"),
                "category page must list the entry");
    }

    @Test
    void unknownCategoryIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog/category/Nope");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(404, response.getStatus());
    }
```

- [ ] **Step 2: Run to verify the new tests fail or pass for the right reason**

```bash
mvn -ntp -pl app test -Dtest=PageServletRenderingTest
```
Expected: all PASS (the production code exists; these lock in behavior). If `draftEntryPermalinkIsNotFound` returns 200, that is a real security bug — stop and report it rather than adjusting the assertion.

- [ ] **Step 3: Commit**

```bash
git add app/src/test
git commit -m "Cover PageServlet permalink, category, draft and 404 paths"
```

---

### Task 3: FeedServlet tests

**Files:**
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/servlets/FeedServletRenderingTest.java`

**Interfaces:**
- Consumes: `RenderingTestSupport` (Task 1). Feed pathInfo shape is `/<handle>/<type>/<format>`.
- Produces: nothing new.

**FeedServlet has no `skipCache` escape** — every test uses its own weblog handle AND `clearRenderCaches()` runs in `@BeforeEach`.

- [ ] **Step 1: Write the tests**

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

class FeedServletRenderingTest {

    private User user;
    private Weblog weblog;
    private String handle;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        // unique handle per test method: FeedServlet has no cache bypass
        handle = "feedblog" + System.nanoTime();
        user = TestUtils.setupUser("feeduser");
        weblog = TestUtils.setupWeblog(handle, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private MockHttpServletResponse feed(String typeAndFormat) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/feed", "/" + handle + "/" + typeAndFormat);
        return RenderingTestSupport.execute(RenderingTestSupport.feedServlet(), request);
    }

    @Test
    void rssEntriesFeedContainsPublishedEntry() throws Exception {
        TestUtils.setupWeblogEntry("rss-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletResponse response = feed("entries/rss");

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().contains("xml"),
                "feed content type must be xml but was: " + response.getContentType());
        String body = response.getContentAsString();
        assertTrue(body.contains("<rss"), "must be an RSS document:\n" + body);
        assertTrue(body.contains("rss-entry"), "entry must appear in the feed:\n" + body);
    }

    @Test
    void atomEntriesFeedContainsPublishedEntry() throws Exception {
        TestUtils.setupWeblogEntry("atom-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletResponse response = feed("entries/atom");

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("<feed"), "must be an Atom document:\n" + body);
        assertTrue(body.contains("atom-entry"), "entry must appear in the feed:\n" + body);
    }

    @Test
    void commentsFeedRenders() throws Exception {
        TestUtils.setupWeblogEntry("commented-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletResponse response = feed("comments/rss");

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("<rss"));
    }

    @Test
    void unknownCategoryFeedIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/feed", "/" + handle + "/entries/rss");
        request.setParameter("cat", "Nope");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.feedServlet(), request);

        assertEquals(404, response.getStatus());
    }

    @Test
    void unknownWeblogFeedIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/feed", "/nosuchblog/entries/rss");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.feedServlet(), request);

        assertEquals(404, response.getStatus());
    }
}
```

- [ ] **Step 2: Run**

```bash
mvn -ntp -pl app test -Dtest=FeedServletRenderingTest
```
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/test
git commit -m "Cover FeedServlet rss/atom/comments feeds and 404 paths"
```

---

### Task 4: SearchServlet tests

**Files:**
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/servlets/SearchServletRenderingTest.java`

**Interfaces:**
- Consumes: `RenderingTestSupport` (Task 1). Search pathInfo is `/<handle>` only (any extra path segment is rejected); the query is the `q` parameter.
- Produces: nothing new.

A zero-hit search still renders the theme's `searchresults.vm` — no Lucene index priming needed, which keeps this deterministic. The `basic` theme emits `<title>Search Results for '$model.term' ...` and `<h1 class="weblogName">` with the weblog name (`TestUtils.setupWeblog` names every weblog "Test Weblog").

- [ ] **Step 1: Write the tests**

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

class SearchServletRenderingTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("searchuser");
        weblog = TestUtils.setupWeblog("searchblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void searchPageRendersWithNoResults() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/search", "/searchblog");
        request.setParameter("q", "zzznope");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("text/html"));
        String body = response.getContentAsString();
        assertTrue(body.contains("Search Results for"),
                "search results template must render:\n" + body);
        assertTrue(body.contains("Test Weblog"),
                "weblog name must appear on the search page:\n" + body);
    }

    @Test
    void unknownWeblogSearchIsBadRequest() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/search", "/nosuchblog");
        request.setParameter("q", "anything");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);

        // SearchServlet sends SC_BAD_REQUEST (not 404) for a missing weblog
        assertEquals(400, response.getStatus());
    }
}
```

- [ ] **Step 2: Run**

```bash
mvn -ntp -pl app test -Dtest=SearchServletRenderingTest
```
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/test
git commit -m "Cover SearchServlet render and bad-request paths"
```

---

### Task 5: CommentServlet tests

**Files:**
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/servlets/CommentServletRenderingTest.java`

**Interfaces:**
- Consumes: `RenderingTestSupport` (Task 1). Comment pathInfo is `/<handle>/entry/<anchor>`; params `method=post`, `name`, `email`, `url`, `content`. The servlet's only output is a `RequestDispatcher.forward` — assert `response.getForwardedUrl()` plus database state via `WeblogEntryManager.getComments(CommentSearchCriteria)` (same API `CommentTest` uses).
- Produces: nothing new.

Notes baked into the tests:
- Moderation is `weblog.getModerateComments() || runtime prop users.moderation.required`. The POJO's `setCommentModerationRequired` is a **no-op**; use `setModerateComments(Boolean.TRUE)`.
- A PENDING comment triggers a mail attempt (`MailUtil`) regardless of the email-notify setting; it is saved to the DB **before** mail is attempted, so DB assertions are safe even if mail throws inside the servlet's catch block.
- Comment window: set `entry.setAllowComments(Boolean.TRUE)` + `entry.setCommentDays(Integer.valueOf(7))` and `weblog.setAllowComments(Boolean.TRUE)` explicitly — don't rely on POJO defaults.

- [ ] **Step 1: Write the tests**

```java
package org.apache.roller.weblogger.ui.rendering.servlets;

import java.util.List;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.pojos.CommentSearchCriteria;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntryComment;
import org.apache.roller.weblogger.pojos.WeblogEntryComment.ApprovalStatus;
import org.apache.roller.weblogger.ui.rendering.util.WeblogEntryCommentForm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommentServletRenderingTest {

    private User user;
    private Weblog weblog;
    private WeblogEntry entry;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("commentuser");
        weblog = TestUtils.setupWeblog("commentblog", user);
        entry = TestUtils.setupWeblogEntry("comment-entry", weblog, user);
        TestUtils.endSession(true);

        // open the comment window explicitly instead of relying on defaults
        Weblog managedWeblog = TestUtils.getManagedWebsite(weblog);
        managedWeblog.setAllowComments(Boolean.TRUE);
        WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(managedWeblog);
        WeblogEntry managedEntry = TestUtils.getManagedWeblogEntry(entry);
        managedEntry.setAllowComments(Boolean.TRUE);
        managedEntry.setCommentDays(Integer.valueOf(7));
        WebloggerFactory.getWeblogger().getWeblogEntryManager().saveWeblogEntry(managedEntry);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private MockHttpServletRequest commentPost(String content, String email) {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousPost("/roller-ui/rendering/comment", "/commentblog/entry/comment-entry");
        request.setParameter("method", "post");
        request.setParameter("name", "Anonymous Reader");
        request.setParameter("email", email);
        request.setParameter("url", "");
        request.setParameter("content", content);
        return request;
    }

    private List<WeblogEntryComment> commentsInDb() throws Exception {
        WeblogEntryManager manager = WebloggerFactory.getWeblogger().getWeblogEntryManager();
        CommentSearchCriteria criteria = new CommentSearchCriteria();
        criteria.setEntry(TestUtils.getManagedWeblogEntry(entry));
        return manager.getComments(criteria);
    }

    @Test
    void validCommentIsApprovedSavedAndForwarded() throws Exception {
        MockHttpServletResponse response = RenderingTestSupport.execute(
                RenderingTestSupport.commentServlet(),
                commentPost("first comment", "reader@example.com"));

        assertEquals("/roller-ui/rendering/page/commentblog/entry/comment-entry",
                response.getForwardedUrl());
        List<WeblogEntryComment> saved = commentsInDb();
        assertEquals(1, saved.size(), "comment must be persisted");
        assertEquals(ApprovalStatus.APPROVED, saved.get(0).getStatus());
        assertEquals("first comment", saved.get(0).getContent());
    }

    @Test
    void moderatedWeblogHoldsCommentAsPending() throws Exception {
        Weblog managedWeblog = TestUtils.getManagedWebsite(weblog);
        managedWeblog.setModerateComments(Boolean.TRUE);
        WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(managedWeblog);
        TestUtils.endSession(true);

        MockHttpServletResponse response = RenderingTestSupport.execute(
                RenderingTestSupport.commentServlet(),
                commentPost("hold me", "reader@example.com"));

        assertEquals("/roller-ui/rendering/page/commentblog/entry/comment-entry",
                response.getForwardedUrl());
        List<WeblogEntryComment> saved = commentsInDb();
        assertEquals(1, saved.size(), "moderated comment must still be persisted");
        assertEquals(ApprovalStatus.PENDING, saved.get(0).getStatus());
    }

    @Test
    void invalidEmailIsRejectedWithoutSaving() throws Exception {
        MockHttpServletRequest request = commentPost("bad email", "not-an-email");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.commentServlet(), request);

        assertEquals("/roller-ui/rendering/page/commentblog/entry/comment-entry",
                response.getForwardedUrl());
        WeblogEntryCommentForm form =
                (WeblogEntryCommentForm) request.getAttribute("commentForm");
        assertNotNull(form, "the error form must ride back on the request");
        assertTrue(form.isError(), "an invalid email must flag an error");
        assertEquals(0, commentsInDb().size(), "nothing may be saved");
    }

    @Test
    void unknownEntryIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousPost("/roller-ui/rendering/comment", "/commentblog/entry/nope");
        request.setParameter("method", "post");
        request.setParameter("name", "x");
        request.setParameter("email", "x@example.com");
        request.setParameter("content", "x");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.commentServlet(), request);

        assertEquals(404, response.getStatus());
    }

    @Test
    void getIsAlwaysNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/comment", "/commentblog/entry/comment-entry");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.commentServlet(), request);

        assertEquals(404, response.getStatus());
    }
}
```

If `CommentSearchCriteria` lives in a different package, copy the import from `app/src/test/java/org/apache/roller/weblogger/business/CommentTest.java` (it uses the identical `new CommentSearchCriteria()` / `setEntry` / `getComments` pattern).

- [ ] **Step 2: Run**

```bash
mvn -ntp -pl app test -Dtest=CommentServletRenderingTest
```
Expected: PASS. If the moderated test logs a mail exception, that's expected (no SMTP in tests); the DB assertion is what matters.

- [ ] **Step 3: Run the whole rendering package together, then commit**

```bash
mvn -ntp -pl app test -Dtest='*RenderingTest'
git add app/src/test
git commit -m "Cover CommentServlet approval, moderation, rejection and 404 paths"
```

---

### Task 6: WeblogRequestMapper URL-mapping tests

**Files:**
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/WeblogRequestMapperTest.java`

**Interfaces:**
- Consumes: `org.apache.roller.weblogger.ui.rendering.WeblogRequestMapper#handleRequest(HttpServletRequest, HttpServletResponse)` → boolean; it parses `request.getRequestURI()` minus `request.getContextPath()`, checks the handle against the DB, and forwards/redirects. spring-test's `MockHttpServletRequest.getRequestDispatcher` records into `response.getForwardedUrl()`.
- Produces: nothing new.

- [ ] **Step 1: Write the tests**

```java
package org.apache.roller.weblogger.ui.rendering;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The public-URL front door: /roller/<handle>/... → rendering servlet forwards. */
class WeblogRequestMapperTest {

    private User user;
    private Weblog weblog;
    private WeblogRequestMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("mapperuser");
        weblog = TestUtils.setupWeblog("mapperblog", user);
        TestUtils.endSession(true);
        mapper = new WeblogRequestMapper();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private MockHttpServletRequest publicUrl(String method, String uriAfterContext) {
        MockHttpServletRequest request =
                new MockHttpServletRequest(method, "/roller" + uriAfterContext);
        request.setContextPath("/roller");
        return request;
    }

    @Test
    void weblogHomeForwardsToPageServlet() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean handled = mapper.handleRequest(request, response);

        assertTrue(handled);
        assertEquals("/roller-ui/rendering/page/mapperblog", response.getForwardedUrl());
    }

    @Test
    void permalinkForwardsToPageServlet() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/entry/my-post");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/page/mapperblog/entry/my-post",
                response.getForwardedUrl());
    }

    @Test
    void feedUrlForwardsToFeedServlet() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/feed/entries/rss");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/feed/mapperblog/entries/rss",
                response.getForwardedUrl());
    }

    @Test
    void searchUrlForwardsToSearchServlet() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/search");
        request.setParameter("q", "term");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/search/mapperblog", response.getForwardedUrl());
    }

    @Test
    void commentPostForwardsToCommentServlet() throws Exception {
        MockHttpServletRequest request = publicUrl("POST", "/mapperblog/entry/my-post");
        request.setParameter("content", "a comment body");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/comment/mapperblog/entry/my-post",
                response.getForwardedUrl());
    }

    @Test
    void missingTrailingSlashRedirects() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertNotNull(response.getRedirectedUrl(), "must redirect to the canonical slash form");
        assertTrue(response.getRedirectedUrl().endsWith("/mapperblog/"),
                "redirect target was: " + response.getRedirectedUrl());
    }

    @Test
    void unknownHandleIsNotHandled() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/nosuchblog/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(request, response),
                "an unknown handle must fall through to the next filter");
        assertNull(response.getForwardedUrl());
    }
}
```

- [ ] **Step 2: Run**

```bash
mvn -ntp -pl app test -Dtest=WeblogRequestMapperTest
```
Expected: PASS. If `handleRequest`'s signature differs (checked exception list), adjust the test's `throws` clause only — do not change the production class in this task.

- [ ] **Step 3: Full suite + coverage checkpoint, then commit**

```bash
mvn -ntp -pl app test
mvn -ntp -pl app jacoco:report
```
Open `app/target/site/jacoco/index.html` and record the `ui.rendering`, `ui.rendering.servlets`, `ui.rendering.velocity` package line percentages — Task 9 sets floors from them. Expected: `ui.rendering.servlets` well above 0% (target ≥60%; PreviewServlet/MediaResourceServlet remain untested and hold the package below 100%).

```bash
git add app/src/test
git commit -m "Cover WeblogRequestMapper public-URL forwarding"
```

---

### Task 7: Anonymous-visitor browser IT

**Files:**
- Modify: `it-selenium/src/test/resources/seed-it-data.sql` (append)
- Modify: `it-selenium/src/test/java/org/apache/roller/it/support/RollerIT.java` (add helper)
- Modify: `it-selenium/src/test/java/org/apache/roller/it/AuthoringJourneyIT.java` (use hoisted helper)
- Create: `it-selenium/src/test/java/org/apache/roller/it/PublicSurfaceIT.java`

**Interfaces:**
- Consumes: `RollerIT.openPath(String)`, `RollerIT.baseUrl()`, `Routes.WEBLOG_HANDLE` (= `it_weblog`), the seeded weblog (theme `basic`, category id `it-cat-0000-0000-0000-000000000001`).
- Produces: `protected static String getAnonymously(String url)` on `RollerIT` — cookie-less HTTP GET returning the body; and seeded entry anchor `it-seeded-entry`.

- [ ] **Step 1: Seed a published entry**

Append to `seed-it-data.sql` (same idempotent style as the rest of the file):

```sql
-- A published entry so anonymous-surface tests have real content to render.
INSERT INTO weblogentry (id, anchor, creator, title, text, pubtime, updatetime,
                         websiteid, categoryid, publishentry, link, plugins,
                         allowcomments, commentdays, righttoleft, pinnedtomain,
                         locale, status, summary, content_type, content_src,
                         search_description)
VALUES ('it-entry-0000-0000-0000-000000000001', 'it-seeded-entry', 'it_admin',
        'IT Seeded Entry', '<p>Seeded entry body for public rendering checks.</p>',
        now() - interval '1 hour', now() - interval '1 hour',
        'it-weblog-0000-0000-0000-00000000001',
        'it-cat-0000-0000-0000-000000000001',
        true, NULL, NULL, true, 7, false, false,
        'en_US', 'PUBLISHED', NULL, NULL, NULL, NULL)
ON CONFLICT (id) DO NOTHING;
```

- [ ] **Step 2: Hoist the anonymous-GET helper into RollerIT**

Move the private `getAnonymously(String url)` method from `AuthoringJourneyIT` (around lines 215–227) into `RollerIT` **verbatim**, changed only to `protected static String getAnonymously(String url)` with its own local `HttpClient` (`HttpClient.newHttpClient()`) instead of the journey test's `http` field. Update `AuthoringJourneyIT` to call the inherited method and delete its private copy. Run the journey IT compile check:

```bash
mvn -ntp -pl it-selenium test-compile -Pit
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Write PublicSurfaceIT**

```java
package org.apache.roller.it;

import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Selenide.$;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The anonymous reader's view: weblog front page, permalink, search and feeds,
 * with no login. BrowserHealthExtension (via RollerIT) fails these tests if the
 * public theme's CSS/JS sub-resources 404 or the console shows errors — the
 * exact class of breakage that motivated Stage 0.
 *
 * Markers are content-specific per the Routes discipline: the seeded entry's
 * title/body can only appear if the Velocity pipeline actually rendered.
 */
class PublicSurfaceIT extends RollerIT {

    @Test
    void frontPageShowsSeededEntry() {
        openPath("/" + WEBLOG_HANDLE + "/");
        $("h1.weblogName").shouldHave(text("IT Weblog"));
        $("body").shouldHave(text("IT Seeded Entry"));
    }

    @Test
    void permalinkShowsEntryBody() {
        openPath("/" + WEBLOG_HANDLE + "/entry/it-seeded-entry");
        $("body").shouldHave(text("IT Seeded Entry"));
        $("body").shouldHave(text("Seeded entry body for public rendering checks."));
    }

    @Test
    void searchPageRendersAnonymously() {
        openPath("/" + WEBLOG_HANDLE + "/search?q=zzznope");
        $("h1.weblogName").shouldHave(text("IT Weblog"));
    }

    @Test
    void rssFeedServesTheSeededEntry() {
        String body = getAnonymously(baseUrl() + "/" + WEBLOG_HANDLE + "/feed/entries/rss");
        assertTrue(body.contains("<rss"), "must serve an RSS document:\n" + body);
        assertTrue(body.contains("IT Seeded Entry"), "seeded entry must be in the feed");
    }

    @Test
    void atomFeedServesTheSeededEntry() {
        String body = getAnonymously(baseUrl() + "/" + WEBLOG_HANDLE + "/feed/entries/atom");
        assertTrue(body.contains("<feed"), "must serve an Atom document:\n" + body);
        assertTrue(body.contains("IT Seeded Entry"), "seeded entry must be in the feed");
    }
}
```

- [ ] **Step 4: Run the IT suite locally**

```bash
mvn -ntp verify -Pit
```
Expected: all ITs pass, including the two pre-existing classes. If BrowserHealth flags a 404ing asset on a public theme page, that is a real production bug this stage exists to catch — fix it in this task (smallest possible fix) and note it in the commit message.

- [ ] **Step 5: Commit**

```bash
git add it-selenium
git commit -m "Add anonymous-visitor browser IT over the public rendering surface"
```

---

### Task 8: Run the browser ITs in CI

**Files:**
- Modify: `.github/workflows/main.yml`

**Interfaces:**
- Consumes: the `it` Maven profile (`mvn verify -Pit` starts PostgreSQL via docker-maven-plugin, seeds, deploys to Tomcat via cargo, runs failsafe). Chrome is preinstalled on `ubuntu-latest`; `RollerIT` sets `Configuration.headless = true`, so no Xvfb is needed.
- Produces: a required CI job named `integration-test`.

- [ ] **Step 1: Delete the stale comment and add the job**

In `.github/workflows/main.yml`, delete this entire comment block from the `build-test` job:

```yaml
      # The it-selenium module is commented out of the parent pom and its page
      # objects still drive removed features (public registration), so it cannot
      # pass as written. Restore this step -- along with the Xvfb setup it needs
      # -- once those tests are updated and the module is back in the reactor.
```

Add a sibling job after `build-test`:

```yaml
  integration-test:
    name: Browser ITs on Linux/JDK 21
    runs-on: ubuntu-latest
    timeout-minutes: 45

    steps:
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'zulu'

      - name: Checkout Project
        uses: actions/checkout@v4
        with:
          persist-credentials: false
          submodules: false
          show-progress: false

      # Chrome ships on ubuntu-latest; RollerIT runs it headless.
      - name: Run browser integration tests
        run: mvn -V -ntp verify -Pit

      - name: Publish IT Report
        uses: test-summary/action@v2
        if: always()
        with:
          paths: "it-selenium/target/failsafe-reports/TEST-*.xml"
```

- [ ] **Step 2: Validate the workflow syntax**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/main.yml')); print('OK')"
```
Expected: `OK`.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/main.yml
git commit -m "Run the browser IT suite in CI"
```

(The job is proven on the next push to master; if it fails there, treat the failure as Stage 0 work, not as a reason to remove the job.)

---

### Task 9: Engage the JaCoCo ratchet

**Files:**
- Modify: `pom.xml` (parent — properties + the existing jacoco `check` execution)

**Interfaces:**
- Consumes: measured coverage from the full suite including Tasks 1–6.
- Produces: `jacoco.check.skip=false` with non-zero floors; a PACKAGE rule for the rendering packages. Later stages only ever raise these numbers.

- [ ] **Step 1: Measure actual coverage**

```bash
mvn -ntp -pl app clean test
mvn -ntp -pl app jacoco:report
python3 - <<'EOF'
import xml.etree.ElementTree as ET
root = ET.parse('app/target/site/jacoco/jacoco.xml').getroot()
for counter in root.findall('counter'):
    if counter.get('type') in ('LINE', 'BRANCH'):
        missed, covered = int(counter.get('missed')), int(counter.get('covered'))
        print('BUNDLE', counter.get('type'), round(covered / (missed + covered), 4))
for pkg in root.findall('package'):
    name = pkg.get('name').replace('/', '.')
    if name.startswith('org.apache.roller.weblogger.ui.rendering'):
        for counter in pkg.findall('counter'):
            if counter.get('type') == 'LINE':
                missed, covered = int(counter.get('missed')), int(counter.get('covered'))
                print(name, 'LINE', round(covered / (missed + covered), 4))
EOF
```

Record the BUNDLE line/branch ratios and the LINE ratio for `ui.rendering`, `ui.rendering.servlets`, and `ui.rendering.velocity`.

- [ ] **Step 2: Set the floors**

In the parent `pom.xml` properties, replace the three placeholder-era values. **Rule: floor = measured value minus 0.01, truncated to two decimals** (e.g. measured 0.7268 → `0.71`):

```xml
<jacoco.line.minimum>SET_FROM_STEP_1</jacoco.line.minimum>
<jacoco.branch.minimum>SET_FROM_STEP_1</jacoco.branch.minimum>
<jacoco.check.skip>false</jacoco.check.skip>
```

In the jacoco `check` execution's `<rules>` element, after the existing BUNDLE rule, add a PACKAGE rule pinning the Stage 0 gains (minimums again = measured − 0.01 per package from Step 1):

```xml
<rule>
    <element>PACKAGE</element>
    <includes>
        <include>org.apache.roller.weblogger.ui.rendering</include>
        <include>org.apache.roller.weblogger.ui.rendering.servlets</include>
        <include>org.apache.roller.weblogger.ui.rendering.velocity</include>
    </includes>
    <limits>
        <limit>
            <counter>LINE</counter>
            <value>COVEREDRATIO</value>
            <minimum>SET_FROM_STEP_1_PER_LOWEST_INCLUDED_PACKAGE</minimum>
        </limit>
    </limits>
</rule>
```

(A single PACKAGE rule applies its minimum to every included package, so use the lowest of the three measured values minus 0.01. If `ui.rendering.servlets` measured below 0.60, flag it in the commit message — the spec's exit target is ≥0.60 and more servlet tests are the fix, not a lower floor.)

Also update the comment above the properties to say the ratchet is **active** and floors only move up.

- [ ] **Step 3: Prove the gate actually fails**

```bash
mvn -ntp -pl app verify -Djacoco.line.minimum=0.99
```
Expected: **BUILD FAILURE** with a jacoco "coverage checks have not been met" message. A gate that has never failed is not proven.

- [ ] **Step 4: Prove the real floors pass**

```bash
mvn -ntp -pl app verify
```
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add pom.xml
git commit -m "Engage the JaCoCo ratchet with measured floors"
```

---

### Task 10: Diff-coverage gate + documentation truth

**Files:**
- Create: `bin/check-diff-coverage.sh`
- Modify: `.github/workflows/main.yml` (build-test job)
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: `app/target/site/jacoco/jacoco.xml`; `diff-cover` (pip) reads JaCoCo XML natively via `--src-roots`.
- Produces: `bin/check-diff-coverage.sh [base-ref]` (default `HEAD~1`), exit 1 under 90% diff coverage.

- [ ] **Step 1: Write the script**

`bin/check-diff-coverage.sh`:

```bash
#!/usr/bin/env bash
# Fails if lines changed since <base-ref> (default HEAD~1) are <90% covered.
# Usage: bin/check-diff-coverage.sh [base-ref]
# Needs: pip install diff_cover; and a fresh coverage report:
#   mvn -ntp -pl app test && mvn -ntp -pl app jacoco:report
set -euo pipefail
cd "$(dirname "$0")/.."

BASE_REF="${1:-HEAD~1}"
REPORT="app/target/site/jacoco/jacoco.xml"

command -v diff-cover >/dev/null 2>&1 || {
    echo "diff-cover not found: pip install diff_cover" >&2; exit 2; }
[ -f "$REPORT" ] || {
    echo "no coverage report at $REPORT — run: mvn -ntp -pl app test && mvn -ntp -pl app jacoco:report" >&2; exit 2; }

exec diff-cover "$REPORT" \
    --src-roots app/src/main/java \
    --compare-branch="$BASE_REF" \
    --fail-under=90
```

```bash
chmod +x bin/check-diff-coverage.sh
```

- [ ] **Step 2: Add the CI step**

In `.github/workflows/main.yml`, `build-test` job: give the checkout full history (diffing needs it) by adding `fetch-depth: 0` to the existing Checkout step's `with:` block. Then after "Build Roller and run JUnit Tests" add:

```yaml
      - name: Diff coverage gate (90% on changed lines)
        if: matrix.java == '21'
        run: |
          pip install diff_cover
          mvn -ntp -pl app jacoco:report
          BASE="${{ github.event.pull_request.base.sha || github.event.before }}"
          if [ -z "$BASE" ] || [ "$BASE" = "0000000000000000000000000000000000000000" ] \
              || ! git cat-file -e "$BASE" 2>/dev/null; then
            BASE="HEAD~1"
          fi
          if ! git cat-file -e "$BASE" 2>/dev/null; then
            echo "No base commit to diff against; skipping."
            exit 0
          fi
          bin/check-diff-coverage.sh "$BASE"
```

Validate: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/main.yml')); print('OK')"` → `OK`.

- [ ] **Step 3: Run the script locally against the last commit**

```bash
mvn -ntp -pl app test && mvn -ntp -pl app jacoco:report
bin/check-diff-coverage.sh HEAD~1
```
Expected: a diff-coverage report; exit 0 (the last commit was pom-only or fully tested).

- [ ] **Step 4: Correct CLAUDE.md**

In `CLAUDE.md`, replace the false sentence:

> `RollerDatabaseExtension` truncates all data tables before each test, so tests do not need to unwind their own fixtures.

with:

> Tests create fixtures through `TestUtils.setupX(...)` and must remove them in
> `@AfterEach` (`teardownWeblog`/`teardownUser` + `endSession(true)`) — nothing
> truncates tables between tests. Render caches are per-JVM singletons; tests
> touching the rendering path call `CacheManager.clear()` in `@BeforeEach`
> (see `RenderingTestSupport`).

And add to the Testing Commands section:

```markdown
### Coverage gates

- JaCoCo `check` runs at `verify` with floors in the parent `pom.xml`
  (`jacoco.line.minimum` / `jacoco.branch.minimum`, plus a PACKAGE rule for
  `ui.rendering.*`). Floors only ever move up. Raise them after each stage.
- Changed lines need ~90% coverage: `bin/check-diff-coverage.sh [base-ref]`
  (default `HEAD~1`; needs `pip install diff_cover` and a fresh
  `mvn -pl app jacoco:report`). CI enforces this on every push/PR.
- Browser ITs run in CI (`mvn verify -Pit`) — see `it-selenium/`.
```

- [ ] **Step 5: Commit**

```bash
git add bin/check-diff-coverage.sh .github/workflows/main.yml CLAUDE.md
git commit -m "Add diff-coverage gate and correct the test-harness docs"
```

---

## Self-Review (performed at plan-writing time)

- **Spec coverage:** Stage 0 items 1–4 map to Tasks 1–6 (rendering tests incl. `WeblogRequestMapper` and the wrapper-enforced models exercised through real templates), Task 7 (anonymous browser ITs), Task 8 (ITs in CI), Tasks 9–10 (ratchet + diff gate). Exit criteria all land: anonymous routes tested in CI (7+8), JaCoCo check active and proven to fail (9), browser ITs green in CI (8).
- **Known intentional gaps:** `PreviewServlet`, `MediaResourceServlet`, `ResourceServlet`, `RSDServlet` and the AJAX servlets stay untested — Preview requires auth (admin-side), RSD is scheduled for deletion in Stage 1, and the resource servlets are I/O plumbing; the PACKAGE floor is set from measured reality, not aspiration.
- **Type consistency:** `RenderingTestSupport`'s API is defined once in Task 1 and consumed with identical signatures in Tasks 2–5; `getAnonymously` is defined in Task 7 Step 2 and used in Step 3.
- **Placeholders:** the two `SET_FROM_STEP_1` tokens in Task 9 are measurement-parameterized values with an explicit computation rule and example, not deferred design.
