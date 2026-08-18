# Virtual-Host Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a weblog own a hostname — `https://berlin.thelocalwiki.com/entry/x`
instead of `https://blog.example.com/berlin/entry/x` — with one canonical address
per page and no per-blog deploy step.

**Architecture:** Host resolution happens inside `WeblogRequestMapper`, which
still forwards to `/roller-ui/rendering/page/<handle>/…`, so `PageServlet`,
`WeblogPageRequest`, the pagers, the rendering models and both render caches stay
unaware that virtual hosts exist. Generated URLs derive from the weblog rather
than the request, which is what keeps the handle-keyed page cache correct. A
filter ahead of the security chain sends control-plane paths back to the site
host.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring MVC, JPA/EclipseLink,
PostgreSQL 16, JUnit 5, Selenide (browser ITs), Caddy 2 (proxy).

**Spec:** `docs/superpowers/specs/2026-08-18-virtual-host-support-design.md`

## Global Constraints

- **TDD is mandatory.** Write the failing test, run it, watch it fail for the
  reason you expect, then write the minimum code that passes. A test that has
  never been seen to fail has not been shown to test anything.
- **Characterisation tests are the exception** and must say so in their javadoc:
  they are written first and expected to pass immediately, proving a refactor
  changed nothing.
- **Never run two builds at once** in this working tree — implementers share
  `app/target/`. Check first, and inline the wait rather than polling separately:
  `pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || echo CLEAR`
  Both the brackets and the `source/roller` scoping are load-bearing.
- **Never commit or push unless explicitly asked.** Work directly on `master`.
- **Every schema change adds a numbered migration** under `bin/db/migrations/`
  with idempotent DDL. The next free number is **V027**.
- **A bare `--` inside an XML comment makes the parse fail silently.** Applies to
  `pom.xml`, `Weblog.orm.xml`, and `runtimeConfigDefs.xml`. Do not write one.
- **Velocity is lenient here.** A template reference to a deleted Java member
  does not throw and does not log — it prints as literal text. Task 5 deletes a
  config property; grep `app/src/main/webapp/themes` and
  `app/src/main/webapp/WEB-INF/velocity` before calling it done.
- **Name every `@RequestParam`/`@PathVariable` explicitly.** The build does not
  pass `-parameters`; `ControllerMetadataTest` fails on any unnamed one.
- Run tests with `mvn -pl app test -Dtest=ClassName`. Browser ITs are
  `mvn verify -Pit` (~16 min) and are **not** on the push path.

---

### Task 1: Schema and domain model for `custom_domain`

**Files:**
- Create: `bin/db/migrations/V027__weblog_custom_domain.sql`
- Modify: `app/src/main/java/org/apache/roller/weblogger/pojos/Weblog.java`
- Modify: `app/src/main/resources/org/apache/roller/weblogger/pojos/Weblog.orm.xml`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/WeblogManager.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAWeblogManagerImpl.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/business/WeblogCustomDomainTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `Weblog.getCustomDomain()` / `setCustomDomain(String)`;
  `WeblogManager.getWeblogByCustomDomain(String host)` returning `Weblog` or
  `null`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/apache/roller/weblogger/business/WeblogCustomDomainTest.java`:

```java
package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The hostname-to-weblog lookup virtual hosting resolves every request through. */
class WeblogCustomDomainTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("vhostuser");
        weblog = TestUtils.setupWeblog("vhostblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void aWeblogIsFoundByItsCustomDomain() throws Exception {
        WeblogManager mgr = WebloggerFactory.getWeblogger().getWeblogManager();
        Weblog stored = mgr.getWeblogByHandle("vhostblog");
        stored.setCustomDomain("vhost.example.com");
        mgr.saveWeblog(stored);
        TestUtils.endSession(true);

        Weblog found = mgr.getWeblogByCustomDomain("vhost.example.com");
        assertEquals("vhostblog", found.getHandle());
    }

    @Test
    void anUnclaimedHostFindsNothing() throws Exception {
        WeblogManager mgr = WebloggerFactory.getWeblogger().getWeblogManager();
        assertNull(mgr.getWeblogByCustomDomain("nobody.example.com"));
    }

    /**
     * A null host must not become a query that matches the many weblogs whose
     * custom_domain is NULL -- that would make every unclaimed hostname resolve
     * to an arbitrary weblog.
     */
    @Test
    void aNullHostFindsNothing() throws Exception {
        WeblogManager mgr = WebloggerFactory.getWeblogger().getWeblogManager();
        assertNull(mgr.getWeblogByCustomDomain(null));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl app test -Dtest=WeblogCustomDomainTest`
Expected: compilation failure — `setCustomDomain` and `getWeblogByCustomDomain` do not exist.

- [ ] **Step 3: Write the migration**

Create `bin/db/migrations/V027__weblog_custom_domain.sql`:

```sql
-- Migration: per-weblog custom domain (virtual-host support).
--
-- NULL means "this weblog has no hostname of its own" and is served under
-- /<handle>/ on the site host, which is every weblog's behaviour before this
-- migration and stays the default afterwards.
--
-- The unique index is the real guarantee that two weblogs cannot claim one
-- hostname; the save-time 409 in WeblogConfigController exists to produce a
-- readable error rather than a constraint-violation 500. It is a partial index
-- so that the many NULL rows do not collide with each other -- PostgreSQL
-- already treats NULLs as distinct in a unique index, but stating it makes the
-- intent explicit and keeps the index small.

ALTER TABLE weblog
    ADD COLUMN IF NOT EXISTS custom_domain varchar(255);

CREATE UNIQUE INDEX IF NOT EXISTS uq_weblog_custom_domain
    ON weblog (custom_domain)
    WHERE custom_domain IS NOT NULL;
```

- [ ] **Step 4: Add the field, mapping and named query**

In `Weblog.java`, beside `newsletterListUuid` (around line 76):

```java
    private String  customDomain     = null;
```

and a standard getter/setter pair following the file's existing style:

```java
    /**
     * The hostname this weblog is served at, or null to be served under
     * /<handle>/ on the site host. Stored lowercased; see
     * WeblogConfigController for the validation, and VirtualHostRegistry for
     * how it is resolved on a request.
     */
    public String getCustomDomain() {
        return customDomain;
    }

    public void setCustomDomain(String customDomain) {
        this.customDomain = customDomain;
    }
```

In `Weblog.orm.xml`, add the named query beside `Weblog.getByNewsletterListUuid`:

```xml
		<named-query name="Weblog.getByCustomDomain">
			<query>SELECT w FROM Weblog w WHERE w.customDomain = ?1</query>
		</named-query>
```

and the column beside `newsletterListUuid`:

```xml
            <basic name="customDomain">
                <column name="custom_domain" insertable="true" updatable="true" unique="true"/>
            </basic>
```

In `WeblogManager.java`, beside `getWeblogByNewsletterListUuid`:

```java
    /**
     * The weblog served at the given hostname, or null when no weblog claims
     * it. Unlike getWeblogByNewsletterListUuid, custom_domain carries a unique
     * index, so at most one row can ever match.
     *
     * @param host a hostname, already lowercased and stripped of any port
     */
    Weblog getWeblogByCustomDomain(String host) throws WebloggerException;
```

In `JPAWeblogManagerImpl.java`, beside `getWeblogByNewsletterListUuid`:

```java
    @Override
    public Weblog getWeblogByCustomDomain(String host) throws WebloggerException {
        // A null host must not reach the query: it would match every weblog
        // whose custom_domain is NULL and resolve an unclaimed hostname to an
        // arbitrary weblog.
        if (host == null || host.isBlank()) {
            return null;
        }
        TypedQuery<Weblog> query = strategy.getNamedQuery(
                "Weblog.getByCustomDomain", Weblog.class);
        query.setParameter(1, host);
        List<Weblog> results = query.getResultList();
        return results.isEmpty() ? null : results.get(0);
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -pl app test -Dtest=WeblogCustomDomainTest,SchemaMigrationTest`
Expected: PASS. `SchemaMigrationTest` independently proves V027 is discoverable and idempotent.

- [ ] **Step 6: Commit**

```bash
git add bin/db/migrations/V027__weblog_custom_domain.sql \
        app/src/main/java/org/apache/roller/weblogger/pojos/Weblog.java \
        app/src/main/resources/org/apache/roller/weblogger/pojos/Weblog.orm.xml \
        app/src/main/java/org/apache/roller/weblogger/business/WeblogManager.java \
        app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAWeblogManagerImpl.java \
        app/src/test/java/org/apache/roller/weblogger/business/WeblogCustomDomainTest.java
git commit -m "feat: add weblog.custom_domain column and hostname lookup"
```

---

### Task 2: `VirtualHostRegistry` — the in-memory host map

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/business/VirtualHostRegistry.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAWeblogManagerImpl.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/business/VirtualHostRegistryTest.java`

**Interfaces:**
- Consumes: `WeblogManager.getWeblogByCustomDomain` (Task 1).
- Produces: `VirtualHostRegistry.handleFor(String hostHeader)` returning a
  weblog handle or `null`; `VirtualHostRegistry.hostFor(String handle)`
  returning a hostname or `null`; `VirtualHostRegistry.invalidate()`;
  `VirtualHostRegistry.normalise(String hostHeader)`.

**Why this exists rather than a lookup per request:** the control-plane filter in
Task 6 must run *before* the Spring Security chain, which is registered at filter
order 40 — earlier than `PersistenceSessionFilter` (60), so no `EntityManager` is
available at that point. An in-memory map lets the filter answer "is this host a
custom domain?" with no persistence context at all. It also removes a database
round trip from every public request in Task 3.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/apache/roller/weblogger/business/VirtualHostRegistryTest.java`:

```java
package org.apache.roller.weblogger.business;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Host-header normalisation, which decides whether a request resolves at all. */
class VirtualHostRegistryTest {

    @Test
    void aPortIsStripped() {
        assertEquals("b.example.com", VirtualHostRegistry.normalise("b.example.com:8443"));
    }

    @Test
    void caseIsFolded() {
        assertEquals("b.example.com", VirtualHostRegistry.normalise("B.Example.COM"));
    }

    /** A fully-qualified name with the root label is the same host. */
    @Test
    void aTrailingDotIsStripped() {
        assertEquals("b.example.com", VirtualHostRegistry.normalise("b.example.com."));
    }

    @Test
    void nullAndBlankNormaliseToNull() {
        assertNull(VirtualHostRegistry.normalise(null));
        assertNull(VirtualHostRegistry.normalise("   "));
    }

    /**
     * An IPv6 literal Host header is bracketed and contains colons that are not
     * a port separator. Stripping at the first colon would corrupt it, so the
     * brackets are kept and only a port after the closing bracket is removed.
     */
    @Test
    void anIpv6LiteralKeepsItsColons() {
        assertEquals("[::1]", VirtualHostRegistry.normalise("[::1]:8080"));
        assertEquals("[::1]", VirtualHostRegistry.normalise("[::1]"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl app test -Dtest=VirtualHostRegistryTest`
Expected: compilation failure — `VirtualHostRegistry` does not exist.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/org/apache/roller/weblogger/business/VirtualHostRegistry.java`:

```java
package org.apache.roller.weblogger.business;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.Weblog;

/**
 * The hostname to weblog-handle map, held in memory.
 *
 * <p>Two callers need it and neither can afford a database round trip:
 * {@code WeblogRequestMapper} runs on every public request, and the
 * control-plane redirect filter runs at filter order 35 -- ahead of the Spring
 * Security chain (40) and therefore ahead of {@code PersistenceSessionFilter}
 * (60), so it has no {@code EntityManager} at all.
 *
 * <p>The map is loaded lazily and rebuilt after any weblog save. Before Roller
 * has bootstrapped there are no weblogs, so an empty map is the correct answer
 * rather than an error -- which is what lets the filter run that early.
 */
public final class VirtualHostRegistry {

    private static final Log log = LogFactory.getLog(VirtualHostRegistry.class);

    private static volatile Map<String, String> hostToHandle = null;

    private VirtualHostRegistry() {
    }

    /**
     * Lowercases a Host header and strips the port and any trailing root
     * label, returning null for anything unusable.
     */
    public static String normalise(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return null;
        }
        String host = hostHeader.trim().toLowerCase(Locale.ROOT);

        // An IPv6 literal is bracketed and full of colons that are not port
        // separators, so only a port AFTER the closing bracket may be removed.
        int portAt = host.startsWith("[")
                ? host.indexOf(':', host.indexOf(']') + 1)
                : host.indexOf(':');
        if (portAt >= 0) {
            host = host.substring(0, portAt);
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host.isBlank() ? null : host;
    }

    /** The handle of the weblog serving this Host header, or null. */
    public static String handleFor(String hostHeader) {
        String host = normalise(hostHeader);
        return host == null ? null : map().get(host);
    }

    /** The hostname this weblog is served at, or null if it has none. */
    public static String hostFor(String handle) {
        if (handle == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : map().entrySet()) {
            if (handle.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** True when at least one weblog claims a hostname. */
    public static boolean isEmpty() {
        return map().isEmpty();
    }

    /** Drops the cached map; the next read rebuilds it. */
    public static void invalidate() {
        hostToHandle = null;
    }

    private static Map<String, String> map() {
        Map<String, String> current = hostToHandle;
        if (current != null) {
            return current;
        }
        Map<String, String> built = new HashMap<>();
        try {
            for (Weblog weblog : WebloggerFactory.getWeblogger()
                    .getWeblogManager().getWeblogs(null, null, null, null, 0, -1)) {
                String host = normalise(weblog.getCustomDomain());
                if (host != null) {
                    built.put(host, weblog.getHandle());
                }
            }
        } catch (WebloggerException | RuntimeException e) {
            // Before bootstrap there is nothing to read and no weblog can have
            // a domain, so an empty map is correct rather than an error. Do
            // NOT cache it in that case, or the map stays empty for the life
            // of the JVM.
            log.debug("Virtual-host map unavailable yet; treating as empty", e);
            return Collections.emptyMap();
        }
        Map<String, String> immutable = Collections.unmodifiableMap(built);
        hostToHandle = immutable;
        return immutable;
    }
}
```

- [ ] **Step 4: Invalidate on save**

In `JPAWeblogManagerImpl.saveWeblog`, after `strategy.store(weblog)`:

```java
        // The host map is derived from this column, so any save may change it.
        // Cheap: the map is rebuilt lazily on the next read, not here.
        VirtualHostRegistry.invalidate();
```

Add the same call to `removeWeblog`, after the weblog is removed.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -pl app test -Dtest=VirtualHostRegistryTest,WeblogCustomDomainTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/business/VirtualHostRegistry.java \
        app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAWeblogManagerImpl.java \
        app/src/test/java/org/apache/roller/weblogger/business/VirtualHostRegistryTest.java
git commit -m "feat: add in-memory virtual-host registry"
```

---

### Task 3: Resolve the weblog from the Host header

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/rendering/WeblogRequestMapper.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/WeblogRequestMapperTest.java`

**Interfaces:**
- Consumes: `VirtualHostRegistry.handleFor` (Task 2).
- Produces: no new public API; `handleRequest` gains host-first resolution.

- [ ] **Step 1: Write the failing test**

Append to `WeblogRequestMapperTest`, reusing its existing `publicUrlAt` helper
(added in 0.1.4) and its `mapperblog` fixture:

```java
    // ------------------------------------------------- virtual hosts

    private MockHttpServletRequest vhostUrl(String method, String uri) {
        MockHttpServletRequest request = publicUrlAt("", method, uri);
        request.addHeader("Host", "vhost.example.com");
        request.setServerName("vhost.example.com");
        return request;
    }

    /**
     * On a custom domain the WHOLE path is weblog-relative: there is no handle
     * segment, because the host supplied it.
     */
    @Test
    void aPermalinkOnACustomDomainForwardsToThePageServlet() throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(vhostUrl("GET", "/entry/my-post"), response));
        assertEquals("/roller-ui/rendering/page/mapperblog/entry/my-post",
                response.getForwardedUrl());
    }

    @Test
    void theWeblogHomeOnACustomDomainForwardsToThePageServlet() throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(vhostUrl("GET", "/"), response));
        assertEquals("/roller-ui/rendering/page/mapperblog", response.getForwardedUrl());
    }

    /**
     * The point of the feature: the first path segment is NOT a handle on a
     * custom domain, so a segment that happens to name another weblog is a page
     * slug on THIS one, not a route to that other weblog.
     */
    @Test
    void aSegmentNamingAnotherWeblogIsAPageSlugOnACustomDomain() throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(vhostUrl("GET", "/someotherblog/"), response));
        assertEquals("/roller-ui/rendering/page/mapperblog/someotherblog",
                response.getForwardedUrl());
    }

    /** An unclaimed host falls through to today's path-segment resolution. */
    @Test
    void anUnclaimedHostStillResolvesByPathSegment() throws Exception {
        MockHttpServletRequest request = publicUrlAt("", "GET", "/mapperblog/entry/my-post");
        request.addHeader("Host", "unclaimed.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/page/mapperblog/entry/my-post",
                response.getForwardedUrl());
    }

    private void givenCustomDomain(String host) throws Exception {
        Weblog stored = WebloggerFactory.getWeblogger().getWeblogManager()
                .getWeblogByHandle("mapperblog");
        stored.setCustomDomain(host);
        WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(stored);
        TestUtils.endSession(true);
        VirtualHostRegistry.invalidate();
    }
```

Add the required imports (`WebloggerFactory`, `VirtualHostRegistry`) and, in
`tearDown`, a `VirtualHostRegistry.invalidate()` so a custom domain set by one
test cannot leak into the next.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl app test -Dtest=WeblogRequestMapperTest`
Expected: the four new tests FAIL — the custom-domain ones forward to
`/roller-ui/rendering/page/entry/my-post` (treating `entry` as the handle) or
decline outright.

- [ ] **Step 3: Write the implementation**

In `WeblogRequestMapper.handleRequest`, replace the block that derives
`weblogHandle` and `pathInfo` from the request URI with host-first resolution:

```java
        // Host-first resolution. A weblog that owns a hostname supplies its own
        // handle, so the ENTIRE path is weblog-relative and the first segment
        // is content (a page slug, a context) rather than a handle. Everything
        // after this block -- locale detection, context/data splitting, the
        // trailing-slash rules, the forward url -- is identical either way,
        // which is the whole reason resolution lives here instead of in a
        // second mapper that would have to reimplement it.
        String vhostHandle = VirtualHostRegistry.handleFor(request.getHeader("Host"));

        String servlet = request.getRequestURI();
        String pathInfo = null;

        if (request.getContextPath() != null) {
            servlet = servlet.substring(request.getContextPath().length());
        }
        if (servlet.startsWith("/")) {
            servlet = servlet.substring(1);
        }
        if (servlet.endsWith("/")) {
            servlet = servlet.substring(0, servlet.length() - 1);
            trailingSlash = true;
        }

        if (vhostHandle != null) {
            weblogHandle = vhostHandle;
            pathInfo = servlet.isEmpty() ? null : servlet;
            // A custom domain's root IS the weblog home, and it is already the
            // canonical url -- so it must not be redirected for a trailing
            // slash the way /<handle> is.
            trailingSlash = true;
        } else if (!servlet.isEmpty()) {
            int slash = servlet.indexOf('/');
            if (slash != -1) {
                weblogHandle = servlet.substring(0, slash);
                pathInfo = servlet.substring(slash + 1);
            } else {
                weblogHandle = servlet;
            }
        }
```

Then make the restricted-path and `isWeblog` guard skip the handle check when
the host resolved it — a host-resolved handle is a weblog by construction, and
running it through `restricted` would make a weblog whose handle collides with a
reserved word unreachable on its own domain:

```java
        if (vhostHandle == null
                && (restricted.contains(weblogHandle) || !this.isWeblog(weblogHandle))) {
            log.debug("SKIPPED " + weblogHandle);
            return false;
        }
```

Delete the `weblog.absoluteurl.<handle>` Host check that follows it — Task 5
removes that property entirely, and `custom_domain` replaces its purpose.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl app test -Dtest=WeblogRequestMapperTest`
Expected: PASS, all pre-existing cases included — the non-vhost path must be
byte-identical.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/rendering/WeblogRequestMapper.java \
        app/src/test/java/org/apache/roller/weblogger/ui/rendering/WeblogRequestMapperTest.java
git commit -m "feat: resolve the weblog from the Host header"
```

---

### Task 4: 301 the path form to the custom domain

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/rendering/WeblogRequestMapper.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/WeblogRequestMapperTest.java`

**Interfaces:**
- Consumes: `VirtualHostRegistry.hostFor` (Task 2), host resolution (Task 3).
- Produces: no new API.

- [ ] **Step 1: Write the failing test**

```java
    /**
     * The custom domain is the single canonical address, so the old path form
     * permanently redirects to it -- which is what keeps already-indexed urls
     * and existing inbound links working after a weblog gains a hostname.
     */
    @Test
    void thePathFormRedirectsToTheCustomDomain() throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletRequest request = publicUrlAt("", "GET", "/mapperblog/entry/my-post");
        request.addHeader("Host", "blog.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals(301, response.getStatus());
        assertEquals("https://vhost.example.com/entry/my-post", response.getRedirectedUrl());
    }

    @Test
    void thePathFormRedirectKeepsTheQueryString() throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletRequest request = publicUrlAt("", "GET", "/mapperblog/entry/my-post");
        request.addHeader("Host", "blog.example.com");
        request.setQueryString("p=2");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("https://vhost.example.com/entry/my-post?p=2",
                response.getRedirectedUrl());
    }

    /** A request already ON the custom domain must not redirect to itself. */
    @Test
    void aRequestOnTheCustomDomainIsNotRedirected() throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(vhostUrl("GET", "/entry/my-post"), response));
        assertNull(response.getRedirectedUrl());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl app test -Dtest=WeblogRequestMapperTest`
Expected: the two redirect tests FAIL — the request is forwarded, not redirected.

- [ ] **Step 3: Write the implementation**

In `handleRequest`, immediately after the resolution block from Task 3 and before
the trailing-slash handling:

```java
        // The custom domain is canonical: a weblog that has one is reachable at
        // exactly one address per page, and any other host permanently
        // redirects there. Absolute by necessity -- this crosses hosts, so
        // unlike the trailing-slash redirect below there is no context path to
        // prepend.
        // Redirect precisely when the weblog has a hostname and THIS request
        // did not arrive on it. vhostHandle != null means the host already
        // resolved the weblog, i.e. we are on the canonical domain already.
        String canonicalHost = VirtualHostRegistry.hostFor(weblogHandle);
        if (vhostHandle == null && canonicalHost != null) {
            StringBuilder target = new StringBuilder("https://").append(canonicalHost);
            if (pathInfo != null) {
                target.append('/').append(pathInfo);
            }
            if (trailingSlash && (pathInfo == null || !pathInfo.endsWith("/"))) {
                target.append('/');
            }
            if (request.getQueryString() != null) {
                target.append('?').append(request.getQueryString());
            }
            response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
            response.setHeader("Location", target.toString());
            return true;
        }
```

Note `sendRedirect` is not used: it defaults to 302, and this must be 301 so
crawlers transfer the ranking rather than treating the move as temporary.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl app test -Dtest=WeblogRequestMapperTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/rendering/WeblogRequestMapper.java \
        app/src/test/java/org/apache/roller/weblogger/ui/rendering/WeblogRequestMapperTest.java
git commit -m "feat: 301 the path form to a weblog's custom domain"
```

---

### Task 5: Generate weblog URLs against the custom domain

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/MultiWeblogURLStrategy.java`
- Modify: `app/src/main/resources/org/apache/roller/weblogger/config/roller.properties`
- Test: `app/src/test/java/org/apache/roller/weblogger/business/MultiWeblogURLStrategyTest.java`

**Interfaces:**
- Consumes: `Weblog.getCustomDomain()` (Task 1).
- Produces: no new API — `getWeblogURL` changes behaviour for custom-domain weblogs.

**Scope note:** all eleven weblog-content URL methods delegate their root to
`getWeblogURL(weblog, locale, absolute)`, so this is a **one-method** change.
`AbstractURLStrategy`'s `getLoginURL`, `getLogoutURL`, `getActionURL`,
`getEntryAddURL`, `getEntryEditURL` and `getWeblogConfigURL` build control-plane
urls under `/roller-ui/` and **must not** be touched: those always live on the
site host, and pointing them at a weblog's domain would send an author to a
screen that redirects straight back.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/apache/roller/weblogger/business/MultiWeblogURLStrategyTest.java`:

```java
package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Url generation for a weblog that owns a hostname.
 *
 * <p>These are pure-function tests on the strategy: they build a detached
 * Weblog rather than persisting one, because getWeblogURL reads only the
 * handle and the custom domain.
 */
class MultiWeblogURLStrategyTest {

    private final MultiWeblogURLStrategy strategy = new MultiWeblogURLStrategy();

    private static Weblog weblog(String handle, String customDomain) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        weblog.setCustomDomain(customDomain);
        return weblog;
    }

    @Test
    void anAbsoluteUrlUsesTheCustomDomainAndDropsTheHandle() {
        assertEquals("https://vhost.example.com/",
                strategy.getWeblogURL(weblog("vhostblog", "vhost.example.com"), null, true));
    }

    /**
     * Root-relative with no handle segment. The page is only ever served on its
     * own domain (any other host 301s), so a relative link needs no host -- and
     * it must not carry the handle, which does not exist in that url space.
     */
    @Test
    void aRelativeUrlDropsTheHandleToo() {
        assertEquals("/",
                strategy.getWeblogURL(weblog("vhostblog", "vhost.example.com"), null, false));
    }

    @Test
    void aLocaleStillFollowsTheWeblogRoot() {
        assertEquals("https://vhost.example.com/de/",
                strategy.getWeblogURL(weblog("vhostblog", "vhost.example.com"), "de", true));
    }

    @Test
    void anEntryUrlIsBuiltOnTheCustomDomainRoot() {
        assertEquals("https://vhost.example.com/entry/my-post",
                strategy.getWeblogEntryURL(
                        weblog("vhostblog", "vhost.example.com"), null, "my-post", true));
    }

    /**
     * CHARACTERISATION: a weblog with no custom domain keeps today's shape
     * exactly. Expected to pass on arrival.
     */
    @Test
    void aWeblogWithoutACustomDomainIsUnchanged() {
        assertEquals("/plainblog/",
                strategy.getWeblogURL(weblog("plainblog", null), null, false));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl app test -Dtest=MultiWeblogURLStrategyTest`
Expected: the four custom-domain tests FAIL, each producing the handle-bearing
form (`https://vhost.example.com/vhostblog/`). The characterisation test PASSES.

- [ ] **Step 3: Write the implementation**

Replace `MultiWeblogURLStrategy.getWeblogURL` in full:

```java
    /**
     * Get root url for a given weblog. Optionally for a certain locale.
     *
     * <p>Every other weblog url on this strategy roots here, so this is the one
     * place virtual hosting changes url generation.
     *
     * <p>The custom domain is read off the WEBLOG, never off the request. The
     * render caches key on the weblog handle and not the host, so one weblog
     * reachable at two hostnames shares a single cached rendering -- and
     * #showSeoHead bakes absolute canonical/og:url values into those bytes.
     * Request-derived urls would let whichever host rendered first stamp its
     * own canonical onto the other's response; weblog-derived urls make the
     * bytes identical by construction.
     */
    @Override
    public String getWeblogURL(Weblog weblog, String locale, boolean absolute) {

        StringBuilder url = new StringBuilder();
        String customDomain = weblog == null ? null : weblog.getCustomDomain();

        if (customDomain != null && !customDomain.isBlank()) {
            // A custom domain replaces the HANDLE segment and nothing else.
            // The context path, the locale segment, and every reserved path
            // root are unchanged (see the final-review spec correction:
            // three real defects -- the protected-path swallow, the
            // context-path drop, and WeblogRequestMapper's locale-only
            // redirect -- traced to the earlier, wrong "no context path and
            // no handle segment" phrasing of this rule).
            if (absolute) {
                url.append("https://").append(customDomain);
            }
            url.append('/');
        } else {
            if (absolute) {
                url.append(WebloggerRuntimeConfig.getAbsoluteContextURL());
            } else {
                url.append(WebloggerRuntimeConfig.getRelativeContextURL());
            }
            url.append('/').append(weblog.getHandle()).append('/');
        }

        if (locale != null) {
            url.append(locale).append('/');
        }

        return url.toString();
    }
```

Remove the now-unused `WebloggerConfig` import if nothing else in the file uses it.

- [ ] **Step 4: Remove the superseded property**

`weblog.absoluteurl.<handle>` never worked as a virtual-host mechanism (it
appended the handle anyway), is startup-scoped so a new weblog needed a restart,
and cannot express a handle containing a hyphen or a capital through the
`ROLLER_*` environment overlay. Delete any reference in `roller.properties` and
its commentary.

Then confirm nothing in a template reads it — Velocity prints a missing
reference as literal text without logging:

```bash
grep -rn "absoluteurl" app/src/main/webapp/themes app/src/main/webapp/WEB-INF/velocity app/src/main/java
```

Expected: no hits outside `site.absoluteurl`, which is a different, still-live
property.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -pl app test -Dtest=MultiWeblogURLStrategyTest`
Expected: PASS.

- [ ] **Step 6: Run the full unit suite**

Run: `pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || mvn -pl app test`
Expected: PASS. This task changes a method every rendering test reaches, so the
whole suite is the real gate.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/business/MultiWeblogURLStrategy.java \
        app/src/main/resources/org/apache/roller/weblogger/config/roller.properties \
        app/src/test/java/org/apache/roller/weblogger/business/MultiWeblogURLStrategyTest.java
git commit -m "feat: build weblog urls on the custom domain; drop weblog.absoluteurl"
```

---

### Task 6: Control-plane redirect filter

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/core/filters/ControlPlaneHostFilter.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/boot/ServletRegistrationConfig.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/core/filters/ControlPlaneHostFilterTest.java`

**Interfaces:**
- Consumes: `VirtualHostRegistry.handleFor` (Task 2).
- Produces: `ControlPlaneHostFilter` registered at filter order **35**.

**Order 35 is load-bearing.** It sits between `SpringFirewallExceptionFilter`
(30) and the Spring Security chain (40). Running after security would let
security 302 an unauthenticated admin request to a login page on the custom
domain first — minting a session and a CSRF token on the wrong host and costing
an extra hop. Running this early is only possible because
`VirtualHostRegistry` needs no `EntityManager` (`PersistenceSessionFilter` is 60).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/apache/roller/weblogger/ui/core/filters/ControlPlaneHostFilterTest.java`:

```java
package org.apache.roller.weblogger.ui.core.filters;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which paths leave a custom domain for the site host.
 *
 * <p>The exempt list is the security-relevant half. ContactController is mapped
 * at /roller-ui/rendering/contact.rol and NewsletterController at
 * /newsletter/subscribe; both are posted by fetch from the rendered blog page,
 * and every bundled theme's CSP is connect-src 'self'. Redirecting either makes
 * it cross-origin -- blocked by CSP, and a 301 on a POST carries no body anyway
 * -- so every [contact] and [subscribe] shortcode on every vhost weblog would
 * silently stop working, visible only in a browser console.
 */
class ControlPlaneHostFilterTest {

    @Test
    void adminPathsBelongToTheSiteHost() {
        assertTrue(ControlPlaneHostFilter.belongsToSiteHost("/roller-ui/menu.rol"));
        assertTrue(ControlPlaneHostFilter.belongsToSiteHost("/roller-ui/authoring/entries.rol"));
        assertTrue(ControlPlaneHostFilter.belongsToSiteHost("/roller-ui/admin/globalConfig.rol"));
        assertTrue(ControlPlaneHostFilter.belongsToSiteHost("/api/v1/ping"));
    }

    @Test
    void thePublicRenderingNamespaceStays() {
        assertFalse(ControlPlaneHostFilter.belongsToSiteHost("/roller-ui/rendering/contact.rol"));
        assertFalse(ControlPlaneHostFilter.belongsToSiteHost(
                "/roller-ui/rendering/media-resources/blog/photo.jpg"));
        assertFalse(ControlPlaneHostFilter.belongsToSiteHost("/roller-ui/rendering/page/blog"));
    }

    @Test
    void publicReaderEndpointsAndAssetsStay() {
        assertFalse(ControlPlaneHostFilter.belongsToSiteHost("/newsletter/subscribe"));
        assertFalse(ControlPlaneHostFilter.belongsToSiteHost("/themes/journal/style.css"));
        assertFalse(ControlPlaneHostFilter.belongsToSiteHost("/webjars/leaflet/leaflet.js"));
        assertFalse(ControlPlaneHostFilter.belongsToSiteHost("/robots.txt"));
        assertFalse(ControlPlaneHostFilter.belongsToSiteHost("/sitemap.xml"));
        assertFalse(ControlPlaneHostFilter.belongsToSiteHost("/blog/entry/x"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl app test -Dtest=ControlPlaneHostFilterTest`
Expected: compilation failure — `ControlPlaneHostFilter` does not exist.

- [ ] **Step 3: Write the implementation**

Create `ControlPlaneHostFilter.java`:

```java
package org.apache.roller.weblogger.ui.core.filters;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.business.VirtualHostRegistry;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;

/**
 * Sends admin and automation-API requests that arrived on a weblog's custom
 * domain back to the site host, so there is one session cookie, one login and
 * one API base url however many weblogs exist.
 *
 * <p>Registered at filter order 35 -- ahead of the Spring Security chain (40),
 * so an unauthenticated admin request is moved to the right host before
 * security can mint a session and a CSRF token on the wrong one.
 */
public class ControlPlaneHostFilter implements Filter {

    private static final Log log = LogFactory.getLog(ControlPlaneHostFilter.class);

    /** The public rendering namespace, which must stay on the custom domain. */
    private static final String PUBLIC_RENDERING = "/roller-ui/rendering/";

    /**
     * True when this path is control plane and belongs on the site host.
     * Package-private-plus for the test; the ordering of the two /roller-ui
     * checks is the whole rule.
     */
    public static boolean belongsToSiteHost(String path) {
        if (path == null) {
            return false;
        }
        if (path.startsWith(PUBLIC_RENDERING)) {
            return false;
        }
        return path.startsWith("/roller-ui/") || path.equals("/roller-ui")
                || path.startsWith("/api/") || path.equals("/api");
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path = request.getRequestURI();
        if (request.getContextPath() != null && !request.getContextPath().isEmpty()) {
            path = path.substring(request.getContextPath().length());
        }

        if (belongsToSiteHost(path)
                && VirtualHostRegistry.handleFor(request.getHeader("Host")) != null) {

            String siteUrl = siteHostUrl();
            if (siteUrl == null) {
                // No host-independent statement of where the control plane
                // lives. Redirecting to getAbsoluteContextURL() here would use
                // whatever InitFilter latched from the first request after
                // boot -- which under virtual hosts can be a custom domain,
                // producing an infinite redirect. Degrade to pre-vhost
                // behaviour instead: serve the request.
                log.warn("A weblog has a custom domain but site.absoluteurl is unset; "
                        + "serving " + path + " on the custom domain rather than "
                        + "redirecting. Set site.absoluteurl to enable the "
                        + "control-plane boundary.");
                chain.doFilter(req, res);
                return;
            }

            StringBuilder target = new StringBuilder(siteUrl).append(path);
            if (request.getQueryString() != null) {
                target.append('?').append(request.getQueryString());
            }
            response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
            response.setHeader("Location", target.toString());
            return;
        }

        chain.doFilter(req, res);
    }

    /**
     * The site host, read from site.absoluteurl DIRECTLY and never through
     * getAbsoluteContextURL(), which falls back to InitFilter's latched value.
     */
    private static String siteHostUrl() {
        String configured = WebloggerRuntimeConfig.getProperty("site.absoluteurl");
        if (configured == null || configured.isBlank()) {
            configured = WebloggerConfig.getProperty("site.absoluteurl");
        }
        if (configured == null || configured.isBlank()) {
            return null;
        }
        return configured.endsWith("/")
                ? configured.substring(0, configured.length() - 1)
                : configured;
    }
}
```

Register it in `ServletRegistrationConfig`, beside the other filter beans:

```java
    /**
     * Order 35: between SpringFirewallExceptionFilter (30) and the Spring
     * Security chain (spring.security.filter.order=40). Running after security
     * would let an unauthenticated admin request be 302'd to a login page on
     * the custom domain before this filter ever sees it. Running this early is
     * only possible because VirtualHostRegistry reads an in-memory map and
     * needs no EntityManager -- PersistenceSessionFilter is order 60.
     */
    @Bean
    public FilterRegistrationBean<ControlPlaneHostFilter> controlPlaneHostFilterRegistration() {
        FilterRegistrationBean<ControlPlaneHostFilter> registration =
                new FilterRegistrationBean<>(new ControlPlaneHostFilter());
        registration.setOrder(35);
        registration.setUrlPatterns(List.of("/*"));
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        return registration;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl app test -Dtest=ControlPlaneHostFilterTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/core/filters/ControlPlaneHostFilter.java \
        app/src/main/java/org/apache/roller/weblogger/boot/ServletRegistrationConfig.java \
        app/src/test/java/org/apache/roller/weblogger/ui/core/filters/ControlPlaneHostFilterTest.java
git commit -m "feat: send control-plane requests on a custom domain to the site host"
```

---

### Task 7: `CustomDomainRules`, the Settings field, and the zone warning

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/CustomDomainRules.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/WeblogConfigBean.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/WeblogConfigController.java`
- Modify: `app/src/main/webapp/WEB-INF/jsps/editor/WeblogConfig.jsp`
- Modify: `app/src/main/resources/ApplicationResources.properties`
- Modify: `app/src/main/resources/org/apache/roller/weblogger/config/roller.properties`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/CustomDomainRulesTest.java`

**Interfaces:**
- Consumes: `Weblog.setCustomDomain` (Task 1).
- Produces: `CustomDomainRules.normalise(String) -> String`,
  `CustomDomainRules.isWellFormed(String) -> boolean`,
  `CustomDomainRules.isOutsideCertZones(String, String) -> boolean`;
  `bean.customDomain` on Weblog Settings; the `vhost.cert.zones` property.

**Why a separate rules class.** The JSP editor and the automation API (Task 9)
must apply identical rules, and this codebase already has the pattern for that:
`EntryFieldRules` is where an author's raw entry input becomes a stored value,
shared by `EntryBean` and `EntryDtos.applyWrite` precisely so the two surfaces
cannot drift. A hostname has exactly that shape. Making the rules a pure class
also makes them cheap to test without a controller harness.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/apache/roller/weblogger/ui/controllers/CustomDomainRulesTest.java`:

```java
package org.apache.roller.weblogger.ui.controllers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one home for custom-domain rules, shared by the JSP editor and the
 * automation API so the two surfaces cannot drift -- the same reason
 * EntryFieldRules exists for entry titles and pubtimes.
 */
class CustomDomainRulesTest {

    // ---------------------------------------------------------- normalise

    @Test
    void normaliseLowercasesAndTrims() {
        assertEquals("vhost.example.com", CustomDomainRules.normalise("  VHost.Example.COM "));
    }

    /** Blank means "no custom domain", which is null, not the empty string. */
    @Test
    void normaliseTurnsBlankIntoNull() {
        assertNull(CustomDomainRules.normalise(""));
        assertNull(CustomDomainRules.normalise("   "));
        assertNull(CustomDomainRules.normalise(null));
    }

    // -------------------------------------------------------- isWellFormed

    @Test
    void ordinaryHostnamesAreWellFormed() {
        assertTrue(CustomDomainRules.isWellFormed("vhost.example.com"));
        assertTrue(CustomDomainRules.isWellFormed("berlin.thelocalwiki.com"));
        assertTrue(CustomDomainRules.isWellFormed("maiiavorobiova.com"));
        assertTrue(CustomDomainRules.isWellFormed("a-b.example.co.uk"));
    }

    /**
     * A single-label name cannot be reached from the public internet, so
     * accepting one would store a value that can never work. Deliberately
     * stricter than the RFC.
     */
    @Test
    void aSingleLabelNameIsRejected() {
        assertFalse(CustomDomainRules.isWellFormed("localhost"));
    }

    @Test
    void junkIsRejected() {
        assertFalse(CustomDomainRules.isWellFormed("not a hostname"));
        assertFalse(CustomDomainRules.isWellFormed("https://vhost.example.com"));
        assertFalse(CustomDomainRules.isWellFormed("vhost.example.com/path"));
        assertFalse(CustomDomainRules.isWellFormed("vhost.example.com:8443"));
        assertFalse(CustomDomainRules.isWellFormed("-lead.example.com"));
        assertFalse(CustomDomainRules.isWellFormed("trail-.example.com"));
        assertFalse(CustomDomainRules.isWellFormed("under_score.example.com"));
    }

    // ------------------------------------------------- isOutsideCertZones

    @Test
    void aHostInsideAConfiguredZoneIsNotOutside() {
        assertFalse(CustomDomainRules.isOutsideCertZones(
                "berlin.thelocalwiki.com", "thelocalwiki.com"));
    }

    @Test
    void aHostInAnyOfSeveralZonesIsNotOutside() {
        assertFalse(CustomDomainRules.isOutsideCertZones(
                "berlin.thelocalwiki.com", "example.com, thelocalwiki.com"));
    }

    @Test
    void anApexOrForeignHostIsOutside() {
        assertTrue(CustomDomainRules.isOutsideCertZones(
                "maiiavorobiova.com", "thelocalwiki.com"));
        assertTrue(CustomDomainRules.isOutsideCertZones(
                "berlin.otherwiki.com", "thelocalwiki.com"));
    }

    /**
     * A wildcard covers ONE label. *.thelocalwiki.com does not cover
     * a.b.thelocalwiki.com, so a deeper name must still warn -- otherwise the
     * warning silently misses the case most likely to surprise someone.
     */
    @Test
    void aDeeperSubdomainIsOutsideBecauseAWildcardCoversOneLabel() {
        assertTrue(CustomDomainRules.isOutsideCertZones(
                "a.b.thelocalwiki.com", "thelocalwiki.com"));
    }

    /** The zone apex itself is not covered by *.zone either. */
    @Test
    void theZoneApexItselfIsOutside() {
        assertTrue(CustomDomainRules.isOutsideCertZones(
                "thelocalwiki.com", "thelocalwiki.com"));
    }

    /** No zones configured means warn about nothing. */
    @Test
    void noConfiguredZonesWarnsAboutNothing() {
        assertFalse(CustomDomainRules.isOutsideCertZones("anything.example.com", ""));
        assertFalse(CustomDomainRules.isOutsideCertZones("anything.example.com", null));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl app test -Dtest=CustomDomainRulesTest`
Expected: compilation failure — `CustomDomainRules` does not exist.

- [ ] **Step 3: Write `CustomDomainRules`**

Create `app/src/main/java/org/apache/roller/weblogger/ui/controllers/CustomDomainRules.java`:

```java
package org.apache.roller.weblogger.ui.controllers;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The rules for a weblog's custom domain, in one place because two surfaces
 * apply them -- the JSP Weblog Settings form and the automation API's weblog
 * PATCH. Same reason {@code EntryFieldRules} exists: a rule reimplemented per
 * surface is a rule that drifts.
 *
 * <p>NOTE there are two normalise methods in this wave and they are not
 * interchangeable. {@link org.apache.roller.weblogger.business.VirtualHostRegistry#normalise}
 * cleans an inbound HTTP {@code Host} header, so it also strips a port and a
 * trailing root label. This one cleans a value an author typed into a form
 * before it is stored, where a port or a trailing dot is a validation failure
 * rather than something to quietly remove. Do not merge them.
 *
 * <p>Uniqueness is deliberately NOT here: it needs a database lookup, and this
 * class is pure so it can be tested without one. Callers check it themselves
 * against {@code WeblogManager.getWeblogByCustomDomain}, and the unique index
 * added in V027 is the actual guarantee either way.
 */
public final class CustomDomainRules {

    /**
     * A hostname label set, deliberately stricter than the RFC: no
     * underscores, no leading or trailing hyphen, and at least two labels. A
     * single-label name cannot be reached from the public internet, so
     * accepting one would store a value that can never work.
     */
    private static final Pattern HOSTNAME = Pattern.compile(
            "^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");

    private CustomDomainRules() {
    }

    /** Trims and lowercases; blank becomes null, meaning "no custom domain". */
    public static String normalise(String raw) {
        if (raw == null) {
            return null;
        }
        String host = raw.trim().toLowerCase(Locale.ROOT);
        return host.isEmpty() ? null : host;
    }

    /** True when the value is a usable public hostname. Null is not. */
    public static boolean isWellFormed(String normalisedHost) {
        return normalisedHost != null && HOSTNAME.matcher(normalisedHost).matches();
    }

    /**
     * True when no configured wildcard zone covers this host, which is a
     * WARNING and never an error (spec Decision 4) -- hard-validating it would
     * couple the app to the certificate model and make apex-domain support a
     * Roller change instead of a proxy change.
     *
     * <p>A wildcard covers exactly ONE label: {@code *.thelocalwiki.com} covers
     * {@code berlin.thelocalwiki.com} but neither {@code thelocalwiki.com} nor
     * {@code a.b.thelocalwiki.com}. Treating it as a plain suffix match would
     * stay silent on precisely the deeper name most likely to surprise someone.
     *
     * @param zones comma-separated apex names, or null/blank to warn about nothing
     */
    public static boolean isOutsideCertZones(String normalisedHost, String zones) {
        if (normalisedHost == null || zones == null || zones.isBlank()) {
            return false;
        }
        for (String raw : zones.split(",")) {
            String zone = raw.trim().toLowerCase(Locale.ROOT);
            if (zone.isEmpty()) {
                continue;
            }
            String suffix = "." + zone;
            if (normalisedHost.endsWith(suffix)) {
                String label = normalisedHost.substring(
                        0, normalisedHost.length() - suffix.length());
                if (!label.isEmpty() && label.indexOf('.') < 0) {
                    return false;
                }
            }
        }
        return true;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl app test -Dtest=CustomDomainRulesTest`
Expected: PASS.

- [ ] **Step 5: Wire it into the bean, the controller and the JSP**

Add `private String customDomain = null;` plus a getter/setter to
`WeblogConfigBean`, and copy it in both `copyFrom` and `copyTo` alongside
`analyticsSiteId`.

In `WeblogConfigController.myValidate`, beside the existing `analyticsSiteId`
check (the controller already has a `protected Weblogger weblogger` field from
`BaseController`):

```java
        String customDomain = CustomDomainRules.normalise(bean.getCustomDomain());
        bean.setCustomDomain(customDomain);
        if (customDomain != null) {
            if (!CustomDomainRules.isWellFormed(customDomain)) {
                addError(model, "websiteSettings.customDomain.invalid", request);
            } else {
                try {
                    Weblog claimant = weblogger.getWeblogManager()
                            .getWeblogByCustomDomain(customDomain);
                    if (claimant != null && !claimant.getHandle().equals(bean.getHandle())) {
                        addError(model, "websiteSettings.customDomain.taken", request);
                    }
                } catch (WebloggerException e) {
                    addError(model, "websiteSettings.customDomain.invalid", request);
                }
            }
        }
```

and, on the successful-save path, surface the zone warning as a model attribute
so the save still succeeds:

```java
        if (CustomDomainRules.isOutsideCertZones(customDomain,
                WebloggerConfig.getProperty("vhost.cert.zones"))) {
            model.addAttribute("customDomainWarning", customDomain);
        }
```

In `roller.properties`:

```properties
# Certificate zones for per-weblog custom domains (virtual hosting).
#
# Comma-separated apex names covered by the proxy's wildcard certificate, e.g.
# "thelocalwiki.com" for a *.thelocalwiki.com cert. Saving a weblog domain
# outside these zones produces a WARNING on Weblog Settings and nothing more --
# no code path gates on this property. It exists so a typo'd subdomain is
# caught at the moment of the mistake rather than surfacing as a browser TLS
# error later. Empty means warn about nothing.
vhost.cert.zones=
```

In `ApplicationResources.properties`:

```properties
websiteSettings.customDomain=Custom domain
websiteSettings.customDomain.tip=Serve this weblog at its own hostname, e.g. berlin.example.com. Leave blank to serve it under /{handle}/ on the site address. Point the hostname at this server before saving.
websiteSettings.customDomain.invalid=Custom domain must be a hostname such as berlin.example.com.
websiteSettings.customDomain.taken=Another weblog is already served at that hostname.
websiteSettings.customDomain.outsideZone=Saved, but this server has no wildcard certificate covering that hostname, so it will not be reachable over HTTPS until the proxy is configured for it.
```

In `WeblogConfig.jsp`, following the `analyticsSiteId` row's exact markup:

```jsp
    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.customDomain"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.customDomain" value="${fn:escapeXml(bean.customDomain)}" size="40" maxlength="255" class="form-control"/>
            <div class="form-text"><spring:message code="websiteSettings.customDomain.tip"/></div>
            <c:if test="${not empty customDomainWarning}">
                <div class="form-text text-warning">
                    <spring:message code="websiteSettings.customDomain.outsideZone"/>
                </div>
            </c:if>
        </div>
    </div>
```

- [ ] **Step 6: Add the cache characterisation test**

Add to `WeblogCustomDomainTest` (Task 1):

```java
    /**
     * CHARACTERISATION: saveWeblog already bumps lastModified unconditionally,
     * which is the ONLY thing that expires a page from WeblogPageCache -- it
     * has no CacheHandler, so CacheManager.invalidate never reaches it.
     * Without the bump, every cached page would keep serving handle-form urls
     * after a domain is set. Expected to pass on arrival; pinned so it is not
     * turned into a conditional bump later.
     */
    @Test
    void settingACustomDomainBumpsLastModified() throws Exception {
        WeblogManager mgr = WebloggerFactory.getWeblogger().getWeblogManager();
        Weblog stored = mgr.getWeblogByHandle("vhostblog");
        java.util.Date before = stored.getLastModified();

        Thread.sleep(10);
        stored.setCustomDomain("bump.example.com");
        mgr.saveWeblog(stored);
        TestUtils.endSession(true);

        Weblog reloaded = mgr.getWeblogByHandle("vhostblog");
        assertTrue(reloaded.getLastModified().after(before),
                "lastModified must advance, or WeblogPageCache keeps serving handle-form urls");
    }
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn -pl app test -Dtest=CustomDomainRulesTest,WeblogCustomDomainTest,EditorJspEscapingTest`
Expected: PASS. `EditorJspEscapingTest` independently proves the new
author-controlled field goes through `fn:escapeXml`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/CustomDomainRules.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/WeblogConfigBean.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/WeblogConfigController.java \
        app/src/main/webapp/WEB-INF/jsps/editor/WeblogConfig.jsp \
        app/src/main/resources/ApplicationResources.properties \
        app/src/main/resources/org/apache/roller/weblogger/config/roller.properties \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/CustomDomainRulesTest.java \
        app/src/test/java/org/apache/roller/weblogger/business/WeblogCustomDomainTest.java
git commit -m "feat: edit and validate a weblog custom domain via CustomDomainRules"
```

---

### Task 8: Per-host robots.txt and sitemap.xml

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/SeoController.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/core/SeoControllerVirtualHostTest.java`

**Interfaces:**
- Consumes: `VirtualHostRegistry.handleFor` (Task 2), `getWeblogURL` (Task 5).
- Produces: no new API — `robots()` and `sitemapIndex()` become host-aware.

- [ ] **Step 1: Write the failing test**

Create `SeoControllerVirtualHostTest`. Build the controller the way the existing
`SeoControllerTest` in the same package does, and drive it with a
`MockHttpServletRequest` carrying a `Host` header:

```java
    private MockHttpServletRequest onHost(String host) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/robots.txt");
        request.addHeader("Host", host);
        return request;
    }

    /** On a custom domain, robots.txt points at THAT weblog's sitemap. */
    @Test
    void robotsOnACustomDomainAdvertisesTheWeblogSitemap() {
        String body = controller.robots(onHost("vhost.example.com")).getBody();
        assertTrue(body.contains("Sitemap: https://vhost.example.com/sitemap.xml"),
                "robots.txt was: " + body);
    }

    /** On a custom domain, /sitemap.xml IS the weblog's sitemap, not the index. */
    @Test
    void sitemapOnACustomDomainIsTheWeblogsOwnSitemap() {
        String xml = controller.sitemapIndex(onHost("vhost.example.com")).getBody();
        assertTrue(xml.contains("<urlset"), "expected a urlset, got: " + xml);
        assertFalse(xml.contains("<sitemapindex"));
        assertTrue(xml.contains("https://vhost.example.com/"));
    }

    /**
     * A sitemap index may only reference sitemaps on its own host, so a
     * custom-domain weblog must be dropped from the site index -- leaving it in
     * produces an index that is invalid for exactly the entries most wanted in
     * the crawl. Each such weblog is discovered through its own robots.txt.
     */
    @Test
    void theSiteIndexOmitsCustomDomainWeblogs() {
        String xml = controller.sitemapIndex(onHost("blog.example.com")).getBody();
        assertTrue(xml.contains("<sitemapindex"));
        assertFalse(xml.contains("sitemap-vhostblog.xml"),
                "a weblog with its own hostname must not appear in the site index");
    }

    /**
     * CHARACTERISATION: a weblog with no custom domain still appears in the
     * site index, on the site host, exactly as before. Expected to pass on
     * arrival.
     */
    @Test
    void theSiteHostIsUnchangedForWeblogsWithoutADomain() {
        String xml = controller.sitemapIndex(onHost("blog.example.com")).getBody();
        assertTrue(xml.contains("sitemap-plainblog.xml"));
    }
```

The fixture needs two weblogs: `vhostblog` with `customDomain =
"vhost.example.com"`, and `plainblog` with none. Call
`VirtualHostRegistry.invalidate()` after creating them and again in `@AfterEach`,
so a domain set here cannot leak into another test class.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl app test -Dtest=SeoControllerVirtualHostTest`
Expected: FAIL — robots advertises the site sitemap, `/sitemap.xml` returns the
index, and the index still lists the custom-domain weblog.

- [ ] **Step 3: Write the implementation**

In `robots()`, resolve the Host header first; when it names a weblog, advertise
`https://<host>/sitemap.xml`. In `sitemapIndex()`, when the Host names a weblog,
delegate to the existing `weblogSitemap(handle)` body rather than building an
index. In the index branch, skip any weblog whose `getCustomDomain()` is
non-blank.

`robots()` and `sitemapIndex()` take no parameters today; add
`HttpServletRequest request` to both and read `request.getHeader("Host")`.
`@GetMapping` methods may declare it without any annotation.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl app test -Dtest=SeoControllerVirtualHostTest,SeoControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/SeoController.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/core/SeoControllerVirtualHostTest.java
git commit -m "feat: per-host robots.txt and sitemap.xml"
```

---

### Task 9: Expose the domain on the API and the analytics contract

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/AdminDtos.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/WeblogsApi.java`
- Modify: `bin/db/migrations/V027__weblog_custom_domain.sql`
- Modify: `docs/api/README.md`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/WeblogsApiCustomDomainTest.java`

**Interfaces:**
- Consumes: `Weblog.getCustomDomain()` (Task 1), the validation rules (Task 7).
- Produces: `WeblogView.customDomain`; `WeblogPatch.customDomain`; the
  `custom_domain` column on the `analytics_weblog_sites` view.

This is the deliverable the whole wave exists to produce: `analytics_weblog_sites`
then carries **weblog handle ↔ Umami website id ↔ hostname** in one place, which
is the map that turns a Search Console property into a weblog an agent can edit.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void theWeblogViewCarriesTheCustomDomain() throws Exception {
        // GET /v1/weblogs/vhostblog on an ADMIN-scoped, unpinned token
        AdminDtos.WeblogView view = weblogsApi.get("vhostblog");
        assertEquals("vhost.example.com", view.customDomain());
    }

    @Test
    void aPatchSetsTheCustomDomain() throws Exception {
        weblogsApi.patch("vhostblog",
                new AdminDtos.WeblogPatch(null, null, null, null, null, null, null,
                        "moved.example.com"));
        assertEquals("moved.example.com",
                weblogsApi.get("vhostblog").customDomain());
    }

    /** Same rules as the JSP editor, because both call CustomDomainRules. */
    @Test
    void aMalformedCustomDomainIsA400() {
        ApiException thrown = assertThrows(ApiException.class, () ->
                weblogsApi.patch("vhostblog",
                        new AdminDtos.WeblogPatch(null, null, null, null, null, null, null,
                                "not a hostname")));
        assertEquals(400, thrown.getStatus());
    }

    @Test
    void aDuplicateCustomDomainIsA409() {
        ApiException thrown = assertThrows(ApiException.class, () ->
                weblogsApi.patch("plainblog",
                        new AdminDtos.WeblogPatch(null, null, null, null, null, null, null,
                                "vhost.example.com")));
        assertEquals(409, thrown.getStatus());
    }
```

Confirm `WeblogPatch`'s component count against the record as it stands when you
write this — the constructor call above assumes `customDomain` is appended as
the final component, which is what Step 3 does.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl app test -Dtest=WeblogsApiCustomDomainTest`
Expected: compilation failure — `WeblogView` has no `customDomain` component.

- [ ] **Step 3: Implement**

Add `String customDomain` as the final component of both `WeblogView` and
`WeblogPatch`, populate it in `AdminDtos.toView`, and apply it in `WeblogsApi`'s
patch handler through **`CustomDomainRules`** (Task 7) — normalise, then
`isWellFormed` (400 via `ApiException.badRequest`), then the
`getWeblogByCustomDomain` uniqueness check (409 via `ApiException.conflict`).
Calling the same class the JSP controller calls is the point: a rule
reimplemented per surface is a rule that drifts. The zone warning has no API
equivalent — it is advisory UI text, and an API client has nowhere to show it.

Extend the V027 migration (it has not shipped, so editing it is legitimate —
only migrations already applied somewhere other than local dev are frozen):

```sql
-- The Grafana/SEO join key. analytics_weblog_sites already carries
-- handle <-> Umami website id; adding the hostname makes it the single place
-- that maps a Search Console property to a weblog.
CREATE OR REPLACE VIEW analytics_weblog_sites AS
SELECT handle            AS weblog_handle,
       analytics_site_id AS website_id,
       custom_domain     AS custom_domain
FROM weblog
WHERE analytics_site_id IS NOT NULL
   OR custom_domain IS NOT NULL;

GRANT SELECT ON analytics_weblog_sites TO grafana_ro;
```

Note the widened `WHERE`: a weblog with a hostname but no Umami id must still
appear, or the search-side join loses exactly the weblogs that have only just
been given a domain.

Document the field in `docs/api/README.md` under "Site administration".

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl app test -Dtest=WeblogsApiCustomDomainTest,SchemaMigrationTest,OpenApiDocumentTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/AdminDtos.java \
        app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/WeblogsApi.java \
        bin/db/migrations/V027__weblog_custom_domain.sql \
        docs/api/README.md \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/WeblogsApiCustomDomainTest.java
git commit -m "feat: expose custom_domain on the API and the analytics contract"
```

---

### Task 10: Caddy wildcard certificate (DNS-01)

**Files:**
- Modify: `deploy/caddy/Dockerfile`
- Modify: `deploy/caddy/Caddyfile`
- Modify: `deploy/.env.example`
- Modify: `docker_deployment.md`
- Test: `app/src/test/java/org/apache/roller/testing/ProductionComposeTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: a Caddy image able to answer DNS-01 for `*.<zone>`.

**This is the riskiest task and the only one that cannot be fully verified
locally** — it needs real DNS provider credentials. Do it last, and treat the
manual verification step as part of it.

- [ ] **Step 1: Write the failing test**

Extend `ProductionComposeTest` with an assertion that the Caddy Dockerfile
builds via `xcaddy` and names a DNS provider module — stock `caddy:*` cannot
perform DNS-01, so a plain `FROM caddy` would silently fail to obtain the
wildcard at runtime rather than at build time.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl app test -Dtest=ProductionComposeTest`
Expected: FAIL — the Dockerfile is a plain `FROM caddy`.

- [ ] **Step 3: Rebuild the image with xcaddy**

Two-stage `deploy/caddy/Dockerfile`: a `caddy:<version>-builder` stage running
`xcaddy build --with github.com/caddy-dns/<provider>`, then copy the binary into
the runtime `caddy:<version>` image. Pin both to the same version.

Add the wildcard site block to the Caddyfile, keeping the existing
`{$SITE_DOMAIN}` block for the control plane:

```caddyfile
*.{$VHOST_ZONE} {
	tls {
		dns <provider> {$VHOST_DNS_API_TOKEN}
	}
	handle {
		reverse_proxy app:8080
	}
}
```

Add `VHOST_ZONE` and `VHOST_DNS_API_TOKEN` to `.env.example` with commentary,
and document the whole procedure in `docker_deployment.md` beside "The context
path": set `vhost.cert.zones` on the app, set `VHOST_ZONE` on the proxy, point a
wildcard DNS record, then set the domain on Weblog Settings.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl app test -Dtest=ProductionComposeTest`
Expected: PASS.

- [ ] **Step 5: Manual verification (cannot be automated)**

Build the image locally (`docker build deploy/caddy`) to prove the xcaddy stage
compiles. Certificate issuance itself needs real credentials and a real zone;
verify on the deploy host and record the result.

- [ ] **Step 6: Commit**

```bash
git add deploy/caddy/Dockerfile deploy/caddy/Caddyfile deploy/.env.example \
        docker_deployment.md \
        app/src/test/java/org/apache/roller/testing/ProductionComposeTest.java
git commit -m "feat: wildcard DNS-01 certificate for weblog custom domains"
```

---

### Task 11: `VirtualHostIT` — the acceptance criteria end to end

**Files:**
- Create: `it-selenium/src/test/java/org/apache/roller/it/VirtualHostIT.java`
- Test: itself

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

The IT harness reaches the app over `127.0.0.1`, so a custom domain is simulated
by sending an explicit `Host` header rather than resolving a real name. Use a
plain HTTP client for the redirect assertions (Selenide follows redirects and
cannot see the 301 itself) and the browser only where a rendered page is needed.
Follow `ApiIT` for the HTTP-client pattern already used in this module.

- [ ] **Step 1: Write the tests**

One test per acceptance criterion. Criteria 3, 4 and 11 are the ones no unit
test can reach, because they depend on real filter ordering against the live
security chain — and criterion 4 is the regression that would otherwise reach
production silently, since a broken contact form only shows up in a browser
console.

```java
    private static final String VHOST = "vhost.example.com";

    /** Does NOT follow redirects: the 301 itself is the assertion. */
    private HttpResponse<String> get(String path, String host) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Host", host)
                .GET().build();
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test // criterion 2
    void thePathFormRedirectsToTheCustomDomain() throws Exception {
        HttpResponse<String> response = get("/" + VHOST_HANDLE + "/entry/x?p=2", "127.0.0.1");
        assertEquals(301, response.statusCode());
        assertEquals("https://" + VHOST + "/entry/x?p=2",
                response.headers().firstValue("Location").orElseThrow());
    }

    @Test // criterion 3
    void adminAndApiLeaveTheCustomDomain() throws Exception {
        assertEquals(301, get("/roller-ui/menu.rol", VHOST).statusCode());
        assertEquals(301, get("/api/v1/ping", VHOST).statusCode());
    }

    @Test // criterion 4 -- the silent-breakage guard
    void theContactAndSubscribeEndpointsStayOnTheCustomDomain() throws Exception {
        assertNotEquals(301, get("/roller-ui/rendering/contact.rol", VHOST).statusCode());
        assertNotEquals(301, get("/newsletter/subscribe", VHOST).statusCode());
    }

    @Test // criterion 6
    void robotsOnTheCustomDomainNamesItsOwnSitemap() throws Exception {
        assertTrue(get("/robots.txt", VHOST).body()
                .contains("Sitemap: https://" + VHOST + "/sitemap.xml"));
    }

    @Test // criterion 7
    void theSiteIndexOmitsTheCustomDomainWeblog() throws Exception {
        assertFalse(get("/sitemap.xml", "127.0.0.1").body().contains(VHOST_HANDLE));
    }
```

Criteria 1, 5 and 10 need a rendered page, so drive those through Selenide.
Criterion 5 (`rel=canonical` and `og:url` are the domain form whichever host
served the request) is the one that proves the weblog-derived-url rule end to
end, so assert it on BOTH hosts — fetching the path form with redirects enabled
and checking the final page's canonical.

Criterion 8 is covered by `CustomDomainRulesTest` and Task 9's API tests;
criterion 9 by `WeblogCustomDomainTest`; criterion 12 by every pre-existing IT
in the suite continuing to pass. Criterion 11 needs `site.absoluteurl` cleared,
which `RollerIT.setGlobalFlag` can do — **restore it in a `finally`**, since the
suite shares one instance.

The fixture: give the seeded IT weblog no domain (other tests depend on it) and
create a dedicated `VHOST_HANDLE` weblog with `customDomain = VHOST`, removing it
in `@AfterEach`.

- [ ] **Step 2: Run the browser suite**

Run: `pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || mvn verify -Pit`
Expected: PASS (~16 min). Also run it once under a context path —
`mvn verify -Pit -Dit.context.path=roller` — since virtual hosting and a context
prefix interact in the redirect builders.

- [ ] **Step 3: Commit**

```bash
git add it-selenium/src/test/java/org/apache/roller/it/VirtualHostIT.java
git commit -m "test: end-to-end virtual-host acceptance criteria"
```

---

## Post-implementation

- [ ] Update `CLAUDE.md` with a "Virtual hosts" section: the host-first
      resolution in `WeblogRequestMapper`, why generated urls derive from the
      weblog rather than the request (the handle-keyed render cache), the
      `/roller-ui/rendering/**` exemption and what breaks without it, and the
      `site.absoluteurl` requirement that prevents the redirect loop.
- [ ] Run `bin/check-diff-coverage.sh master` — changed lines need ~90% coverage.
- [ ] Raise the JaCoCo floors where there is slack, per CLAUDE.md's rule that
      floors only ever move up and that BUNDLE LINE is already binding.
