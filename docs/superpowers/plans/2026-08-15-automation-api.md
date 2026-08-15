# Automation API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `/api/v1` — a token-authenticated HTTP API for agentic publishing, agentic SEO and command-line administration — plus `bin/roller-api`, without touching the UI.

**Architecture:** A new `ui.restapi` package holds thin `@RestController`s that call the existing business managers. A second Spring Security filter chain, scoped to `/api/**`, authenticates Bearer tokens from a new `roller_api_token` table. Four pieces of logic that the UI already owns are extracted before the API becomes their second caller, so nothing is ever duplicated.

**Tech Stack:** Spring Boot 4.1, Spring MVC, Spring Security, EclipseLink JPA, PostgreSQL, JUnit 5 + MockMvc, Selenium-module ITs (no browser), bash + curl + jq for the CLI.

**Spec:** `docs/superpowers/specs/2026-08-15-automation-api-design.md`

## Global Constraints

- **Controller mappings omit the `/api` prefix.** `/api/*` is a servlet-spec prefix mapping and the prefix is stripped from the Spring MVC lookup path. `@RequestMapping("/v1/weblogs/{handle}/entries")` serves `/api/v1/weblogs/{handle}/entries`. Precedent: `NewsletterController`.
- **Name every `@RequestParam` and `@PathVariable` explicitly.** The build does not pass `-parameters`; a bare `@PathVariable String handle` throws at runtime while a direct-call unit test passes. `ControllerMetadataTest` enforces this.
- **Never commit or push unless the user explicitly asks.** Steps that say "Commit" mean stage and commit locally; never push.
- **Work directly on `master`.** Do not create a feature branch.
- **Commit trailer, every commit:** `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`
- **Every schema change adds a numbered migration** under `bin/db/migrations/`, idempotent, never edited after application. Next free number is `V026`.
- **Changed lines need ~90% coverage:** `bin/check-diff-coverage.sh` after `mvn -pl app jacoco:report`.
- **Token secrets are never stored, logged, or returned twice.** Only the SHA-256 digest is persisted.
- **A token is a ceiling, never a grant.** `GlobalPermission`/`WeblogPermission` checks still run; token scope may only narrow them.
- **Entry titles are stored HTML-escaped; page titles are stored raw.** Never re-derive either rule in a DTO mapper — call `EntryFieldRules` (Task 6).
- **Errors are `application/problem+json`, produced only by `ApiExceptionHandler`.** No controller builds its own error body.
- **404, not 403, for a resource the caller may not see.** A 403 confirms existence.
- Run the whole unit suite with `mvn -pl app test`. Run one class with `mvn -pl app test -Dtest=ClassName`. Tests need Docker (Testcontainers PostgreSQL, one container per JVM).
- **Test first, and watch it fail for the reason you expect.** A step that says "watch it fail" is not paperwork: if it passes, or fails for a different reason, stop and find out why before writing any implementation.
- **Every task that ships a controller ships a MockMvc test for that controller, written before it.** Testing the DTO helper alone is not enough — it leaves routing, status codes, permission wiring and JSON shape unproven, which is where API bugs actually live. Standalone setup needs no container:

```java
    // Pattern for every *ApiTest that covers a controller. standaloneSetup
    // runs no container, so it cannot see the /api prefix stripping (ApiIT
    // covers that) -- but it does exercise routing, status codes, argument
    // binding and the problem+json advice, none of which a DTO test touches.
    private MockMvc mockMvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void anUnknownEntryIs404WithProblemJson() throws Exception {
        mockMvc(new EntriesApi(/* mocked Weblogger */))
                .perform(get("/v1/weblogs/testblog/entries/no-such-id"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
```

- **A characterisation test is the one test expected to pass on arrival.** When a task extracts or moves existing behaviour (Tasks 6, 7, 10's manager assertions, 11's ownership assertion), the test still comes first, and its javadoc must say it is characterising existing behaviour — so a later reader does not mistake a passing-on-arrival test for one written backwards. If a characterisation test *fails*, the behaviour was not what the plan assumed: stop, do not "fix" it to match.

---

## File Structure

**New — `app/src/main/java/org/apache/roller/weblogger/`**

| File | Responsibility |
|---|---|
| `pojos/ApiToken.java` | Entity: digest, owner, scope, lifecycle timestamps. |
| `business/ApiTokenManager.java` | Interface: issue, authenticate, list, revoke. |
| `business/jpa/JPAApiTokenManagerImpl.java` | JPA implementation. |
| `business/MaintenanceService.java` | Flush cache / reindex / regenerate renditions, extracted from `MaintenanceController`. |
| `ui/restapi/ApiProblem.java` | RFC 9457 body record. |
| `ui/restapi/ApiException.java` | Carries status + type + title + detail. |
| `ui/restapi/ApiExceptionHandler.java` | `@RestControllerAdvice`; the only place an error body is built. |
| `ui/restapi/auth/ApiPrincipal.java` | Authenticated user name + token scope. |
| `ui/restapi/auth/ApiTokenAuthFilter.java` | Bearer header → `SecurityContext`. |
| `ui/restapi/auth/ApiScopeInterceptor.java` | Enforces the token ceiling against the resolved weblog and required role. |
| `ui/restapi/dto/*.java` | Records plus static mappers, one file per resource group. |
| `ui/restapi/v1/*Api.java` | One `@RestController` per resource. |
| `ui/controllers/EntryFieldRules.java` | Title escaping + weblog-timezone pubtime parsing, shared by `EntryBean` and the API. |
| `ui/controllers/WeblogOwnership.java` | Ownership-checked by-id lookups, shared by `BaseController` and the API. |

**New — elsewhere**

| File | Responsibility |
|---|---|
| `app/src/main/resources/org/apache/roller/weblogger/pojos/ApiToken.orm.xml` | JPA mapping + named queries. |
| `bin/db/migrations/V026__api_tokens.sql` | `roller_api_token`. |
| `bin/roller-api` | The CLI. |
| `docs/api/README.md` | Recipes + auth walkthrough. |

**Modified**

| File | Change |
|---|---|
| `boot/ServletRegistrationConfig.java` | `API_URL_PATTERNS = {"/api/*"}` added in `configure()`. |
| `boot/SecurityConfig.java` | New `@Order(1)` API chain; existing chain becomes `@Order(2)`. |
| `config/roller.properties` | `api` + `newsletter` in `rollerProtectedUrls`; `api.throttle.*`. |
| `ui/controllers/RollerHandlerInterceptor.java` | Weblog resolution falls back to the `handle` URI template variable. |
| `ui/controllers/BaseController.java` | Lookups delegate to `WeblogOwnership`. |
| `ui/controllers/editor/EntryBean.java` | Escaping/pubtime delegate to `EntryFieldRules`. |
| `ui/controllers/admin/MaintenanceController.java` | Actions delegate to `MaintenanceService`. |
| `business/jpa/WebloggerBeanConfig.java` | `apiTokenManager` bean. |
| `business/Weblogger.java` + `WebloggerImpl.java` | `getApiTokenManager()`. |
| `resources/META-INF/persistence.xml` | `ApiToken.orm.xml` mapping-file entry. |

---

## Phase 1 — Foundations and authentication

### Task 1: Mount `/api/*` and reserve the path root

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/boot/ServletRegistrationConfig.java`
- Modify: `app/src/main/resources/org/apache/roller/weblogger/config/roller.properties:322-326`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/MetaApi.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/ApiMountingTest.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/boot/ProtectedUrlRootsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ServletRegistrationConfig.API_URL_PATTERNS` (`static final String[]`, value `{"/api/*"}`); `MetaApi` serving `GET /api/v1/ping` → `{"status":"ok"}`.

- [ ] **Step 1: Write the failing mounting test**

```java
package org.apache.roller.weblogger.ui.restapi;

import org.apache.roller.weblogger.boot.ServletRegistrationConfig;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class ApiMountingTest {

    /**
     * /api/* is a servlet-spec PREFIX mapping, so the container strips the
     * prefix from the Spring MVC lookup path. Controllers are therefore
     * mapped at "/v1/..." and serve "/api/v1/...". This test exists because
     * writing the full path in @RequestMapping is the single most likely
     * mistake in this wave and produces a 404 with no other symptom.
     */
    @Test
    void apiPrefixIsRegisteredOnTheDispatcher() {
        assertTrue(Arrays.asList(ServletRegistrationConfig.API_URL_PATTERNS).contains("/api/*"),
                "the dispatcher must carry the /api/* prefix mapping");
    }

    @Test
    void metaApiIsMappedRelativeToTheStrippedPrefix() {
        var mapping = org.apache.roller.weblogger.ui.restapi.v1.MetaApi.class
                .getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class);
        assertNotNull(mapping, "MetaApi must carry @RequestMapping");
        assertEquals("/v1", mapping.value()[0],
                "must be /v1, not /api/v1 -- the container already stripped /api");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=ApiMountingTest`
Expected: FAIL — `ServletRegistrationConfig.API_URL_PATTERNS` does not exist (compile error).

- [ ] **Step 3: Add the mapping**

In `ServletRegistrationConfig.java`, beside `NEWSLETTER_URL_PATTERNS`:

```java
    /**
     * Routes the automation API ({@code ui.restapi.v1}) to the dispatcher.
     *
     * <p>A legal servlet-spec <em>prefix</em> mapping, with the same Spring
     * MVC consequence {@code NEWSLETTER_URL_PATTERNS} documents above: the
     * servlet path prefix is stripped from the lookup path, so every API
     * controller is mapped relative to {@code /api} -- {@code /v1/...}, not
     * {@code /api/v1/...}. The {@code api} path root is reserved in
     * {@code rendering.weblogMapper.rollerProtectedUrls} so no weblog handle
     * can shadow it.
     */
    static final String[] API_URL_PATTERNS = {"/api/*"};
```

and add it inside the existing `configure()` override:

```java
                        servletRegistration.addMapping(API_URL_PATTERNS);
```

- [ ] **Step 4: Create the ping controller**

```java
package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness for the API surface. Mapped at {@code /v1} because the container
 * strips the {@code /api} prefix -- see ServletRegistrationConfig's
 * API_URL_PATTERNS.
 */
@RestController
@RequestMapping("/v1")
public class MetaApi {

    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
```

- [ ] **Step 5: Write the failing protected-roots test**

```java
package org.apache.roller.weblogger.boot;

import org.apache.roller.weblogger.config.WebloggerConfig;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WeblogRequestMapper forwards any single-segment path to the weblog
 * renderer, so an application path root that is not in this list can be
 * shadowed by a weblog whose handle matches it.
 *
 * `newsletter` is here because ServletRegistrationConfig's javadoc has
 * always claimed it was reserved while it actually was not -- a weblog with
 * the handle `newsletter` would have shadowed the subscribe endpoint.
 */
class ProtectedUrlRootsTest {

    private static List<String> roots() {
        return Arrays.asList(WebloggerConfig
                .getProperty("rendering.weblogMapper.rollerProtectedUrls").split(","));
    }

    @Test
    void apiRootIsReserved() {
        assertTrue(roots().contains("api"),
                "a weblog handled 'api' would shadow the entire automation API");
    }

    @Test
    void newsletterRootIsReserved() {
        assertTrue(roots().contains("newsletter"),
                "a weblog handled 'newsletter' would shadow /newsletter/subscribe");
    }
}
```

- [ ] **Step 6: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=ProtectedUrlRootsTest`
Expected: FAIL — both assertions; neither root is in the list today.

- [ ] **Step 7: Reserve both roots**

In `roller.properties`, change the `rollerProtectedUrls` value to:

```
rendering.weblogMapper.rollerProtectedUrls=\
roller-ui,images,theme,themes,\
index.jsp,favicon.svg,robots.txt,sitemap.xml,\
page,flavor,rss,atom,language,search,resource,\
webjars,api,newsletter
```

- [ ] **Step 8: Run both test classes**

Run: `mvn -pl app test -Dtest=ApiMountingTest+ProtectedUrlRootsTest`
Expected: PASS, 4 tests.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/boot/ServletRegistrationConfig.java \
        app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/MetaApi.java \
        app/src/main/resources/org/apache/roller/weblogger/config/roller.properties \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/ApiMountingTest.java \
        app/src/test/java/org/apache/roller/weblogger/boot/ProtectedUrlRootsTest.java
git commit -m "api: mount /api/* on the dispatcher and reserve the path root

Also reserves 'newsletter', which ServletRegistrationConfig's javadoc has
claimed since Wave B without it ever being in the list.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: The problem+json error contract

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/ApiProblem.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/ApiException.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/ApiExceptionHandler.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/ApiExceptionHandlerTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `record ApiProblem(String type, String title, int status, String detail, String instance, List<FieldError> errors)` with `record FieldError(String field, String message)`.
  - `ApiException extends RuntimeException` with `ApiException(int status, String type, String title, String detail)`, static factories `notFound(String detail)`, `forbidden(String detail)`, `conflict(String detail)`, `badRequest(String detail)`, `quotaExceeded(String detail)`, `throttled(String detail)`, and `int getStatus()`, `ApiProblem toProblem(String instance)`.
  - `ApiExceptionHandler` — `@RestControllerAdvice(basePackages = "org.apache.roller.weblogger.ui.restapi")`.

- [ ] **Step 1: Write the failing test**

```java
package org.apache.roller.weblogger.ui.restapi;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void anApiExceptionBecomesProblemJsonCarryingItsStatusAndInstance() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/weblogs/nope");
        ResponseEntity<ApiProblem> response =
                handler.handleApiException(ApiException.notFound("No weblog 'nope'."), request);

        assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
        ApiProblem body = response.getBody();
        assertNotNull(body);
        assertEquals(404, body.status());
        assertEquals("No weblog 'nope'.", body.detail());
        assertEquals("/api/v1/weblogs/nope", body.instance(),
                "instance must be the path the client actually called, /api prefix included");
    }

    /**
     * An unexpected exception must not leak its message or stack to a client.
     * The detail is fixed text; the real cause goes to the log.
     */
    @Test
    void anUnexpectedExceptionBecomesAnOpaque500() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/weblogs/x/entries");
        ResponseEntity<ApiProblem> response = handler.handleUnexpected(
                new IllegalStateException("connection pool exhausted at com.example.Secret"), request);

        assertEquals(500, response.getStatusCode().value());
        ApiProblem body = response.getBody();
        assertNotNull(body);
        assertFalse(body.detail().contains("connection pool"),
                "internal detail must never reach the client");
        assertFalse(body.detail().contains("com.example"),
                "internal detail must never reach the client");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=ApiExceptionHandlerTest`
Expected: FAIL — none of the three classes exist.

- [ ] **Step 3: Write `ApiProblem`**

```java
package org.apache.roller.weblogger.ui.restapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * RFC 9457 problem detail. Null members are omitted, so a plain error
 * carries no empty "errors": [] and no null detail.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiProblem(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        List<FieldError> errors) {

    public record FieldError(String field, String message) { }
}
```

- [ ] **Step 4: Write `ApiException`**

```java
package org.apache.roller.weblogger.ui.restapi;

import java.util.List;

/**
 * The one exception the API layer throws for an expected failure. Every
 * response body is built from it by ApiExceptionHandler; no controller
 * assembles its own.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private static final String TYPE_BASE = "https://roller.invalid/problems/";

    private final int status;
    private final String type;
    private final String title;
    private final transient List<ApiProblem.FieldError> errors;

    public ApiException(int status, String type, String title, String detail) {
        this(status, type, title, detail, null);
    }

    public ApiException(int status, String type, String title, String detail,
                        List<ApiProblem.FieldError> errors) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
        this.errors = errors;
    }

    public static ApiException notFound(String detail) {
        return new ApiException(404, TYPE_BASE + "not-found", "Not found", detail);
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(403, TYPE_BASE + "forbidden", "Forbidden", detail);
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(400, TYPE_BASE + "invalid-request", "Invalid request", detail);
    }

    public static ApiException validation(String detail, List<ApiProblem.FieldError> errors) {
        return new ApiException(400, TYPE_BASE + "invalid-request", "Invalid request", detail, errors);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(409, TYPE_BASE + "conflict", "Conflict", detail);
    }

    public static ApiException quotaExceeded(String detail) {
        return new ApiException(413, TYPE_BASE + "quota-exceeded", "Upload quota exceeded", detail);
    }

    public static ApiException throttled(String detail) {
        return new ApiException(429, TYPE_BASE + "throttled", "Too many requests", detail);
    }

    public int getStatus() {
        return status;
    }

    public ApiProblem toProblem(String instance) {
        return new ApiProblem(type, title, status, getMessage(), instance, errors);
    }
}
```

- [ ] **Step 5: Write `ApiExceptionHandler`**

```java
package org.apache.roller.weblogger.ui.restapi;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The only place an API error body is built. Scoped to the restapi package so
 * it cannot change how the JSP controllers report failures.
 */
@RestControllerAdvice(basePackages = "org.apache.roller.weblogger.ui.restapi")
public class ApiExceptionHandler {

    private static final Log log = LogFactory.getLog(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiProblem> handleApiException(ApiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ex.toProblem(request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiProblem> handleValidation(MethodArgumentNotValidException ex,
                                                       HttpServletRequest request) {
        List<ApiProblem.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiProblem.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return handleApiException(
                ApiException.validation("One or more fields are invalid.", errors), request);
    }

    /**
     * Anything unforeseen. The cause is logged; the client is told nothing
     * about it, because an exception message here routinely carries schema
     * names, file paths and connection strings.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiProblem> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled API exception at " + request.getRequestURI(), ex);
        ApiException opaque = new ApiException(500,
                "https://roller.invalid/problems/internal-error",
                "Internal error",
                "The request could not be completed.");
        return handleApiException(opaque, request);
    }
}
```

- [ ] **Step 6: Run the test**

Run: `mvn -pl app test -Dtest=ApiExceptionHandlerTest`
Expected: PASS, 2 tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/ \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/ApiExceptionHandlerTest.java
git commit -m "api: RFC 9457 problem+json error contract

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `roller_api_token` — schema, entity, manager

**Files:**
- Create: `bin/db/migrations/V026__api_tokens.sql`
- Create: `app/src/main/java/org/apache/roller/weblogger/pojos/ApiToken.java`
- Create: `app/src/main/resources/org/apache/roller/weblogger/pojos/ApiToken.orm.xml`
- Create: `app/src/main/java/org/apache/roller/weblogger/business/ApiTokenManager.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAApiTokenManagerImpl.java`
- Modify: `app/src/main/resources/META-INF/persistence.xml:35`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/jpa/WebloggerBeanConfig.java:138-141`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/Weblogger.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/WebloggerImpl.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/business/ApiTokenManagerTest.java`

**Interfaces:**
- Consumes: `TokenGenerator.newToken()`, `TokenGenerator.sha256Hex(String)` (`org.apache.roller.weblogger.util`).
- Produces:
  - `ApiToken.Role` enum: `READ, POST, ADMIN`.
  - `ApiTokenManager`:
    - `String issueToken(User user, String label, String scopeWeblog, ApiToken.Role role, Timestamp expiresAt) throws WebloggerException` — returns the **raw** token, once.
    - `ApiToken authenticate(String rawToken) throws WebloggerException` — null when absent/expired/revoked; touches `lastUsedAt` coarsely.
    - `List<ApiToken> getTokens(User user) throws WebloggerException`
    - `boolean revoke(User user, String tokenId) throws WebloggerException`
  - `Weblogger.getApiTokenManager()`.

**Order note:** the manager test comes first and the migration is written to
make it pass. Writing the schema first and then running the pre-existing
`SchemaMigrationTest` would be a pass-to-pass check — it never fails, so it
proves nothing about this table. The manager test cannot pass without the
table, which is what makes it the real gate.

- [ ] **Step 1: Write the failing manager test**

```java
package org.apache.roller.weblogger.business;

import java.sql.Timestamp;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiTokenManagerTest {

    private User user;
    private ApiTokenManager mgr;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("apitokentestuser");
        TestUtils.endSession(true);
        mgr = WebloggerFactory.getWeblogger().getApiTokenManager();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void anIssuedTokenAuthenticatesAndCarriesItsScope() throws Exception {
        String raw = mgr.issueToken(user, "seo-agent", "testblog", ApiToken.Role.POST, null);
        TestUtils.endSession(true);

        assertTrue(raw.startsWith("rlr_"), "tokens are prefixed so they are recognisable in logs");

        ApiToken found = mgr.authenticate(raw);
        assertNotNull(found);
        assertEquals("testblog", found.getScopeWeblog());
        assertEquals(ApiToken.Role.POST, found.getScopeRole());
    }

    /** A database read must never yield a working credential. */
    @Test
    void theRawTokenIsNeverStored() throws Exception {
        String raw = mgr.issueToken(user, "label", null, ApiToken.Role.READ, null);
        TestUtils.endSession(true);

        ApiToken stored = mgr.authenticate(raw);
        assertNotNull(stored);
        assertNotEquals(raw, stored.getTokenSha256());
        assertEquals(64, stored.getTokenSha256().length(), "SHA-256 hex is 64 chars");
    }

    @Test
    void anExpiredTokenDoesNotAuthenticate() throws Exception {
        Timestamp past = new Timestamp(System.currentTimeMillis() - 1000L);
        String raw = mgr.issueToken(user, "expired", null, ApiToken.Role.READ, past);
        TestUtils.endSession(true);

        assertNull(mgr.authenticate(raw));
    }

    @Test
    void aRevokedTokenDoesNotAuthenticate() throws Exception {
        String raw = mgr.issueToken(user, "doomed", null, ApiToken.Role.ADMIN, null);
        TestUtils.endSession(true);

        ApiToken issued = mgr.authenticate(raw);
        assertNotNull(issued);
        assertTrue(mgr.revoke(user, issued.getId()));
        TestUtils.endSession(true);

        assertNull(mgr.authenticate(raw));
    }

    /** Revocation is scoped to the owner: one user must not revoke another's. */
    @Test
    void revokeRefusesATokenOwnedBySomeoneElse() throws Exception {
        User other = TestUtils.setupUser("apitokenotheruser");
        TestUtils.endSession(true);
        String raw = mgr.issueToken(user, "mine", null, ApiToken.Role.READ, null);
        TestUtils.endSession(true);
        ApiToken mine = mgr.authenticate(raw);
        assertNotNull(mine);

        assertFalse(mgr.revoke(other, mine.getId()));
        TestUtils.endSession(true);
        assertNotNull(mgr.authenticate(raw), "the token must still work");

        TestUtils.teardownUser(other.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void anUnknownTokenDoesNotAuthenticate() throws Exception {
        assertNull(mgr.authenticate("rlr_notarealtoken"));
        assertNull(mgr.authenticate(null));
        assertNull(mgr.authenticate("   "));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=ApiTokenManagerTest`
Expected: FAIL — `ApiToken`, `ApiTokenManager` and `getApiTokenManager()` do not exist.

- [ ] **Step 3: Write the migration**

```sql
-- V026: API tokens for the automation surface.
--
-- Deliberately a separate table from roller_user_token rather than a new
-- UserToken.Purpose: that entity is single-use with a one-hour TTL and an
-- atomic consume, all of which are wrong for a long-lived credential.
--
-- Only the SHA-256 digest is stored. The secret is high-entropy random, so
-- there is nothing to brute-force and authentication must stay a single
-- indexed lookup -- a slow KDF would be wrong on both counts.

CREATE TABLE IF NOT EXISTS roller_api_token (
    id            VARCHAR(48)  NOT NULL PRIMARY KEY,
    userid        VARCHAR(48)  NOT NULL,
    label         VARCHAR(255) NOT NULL,
    token_sha256  VARCHAR(64)  NOT NULL,
    scope_weblog  VARCHAR(255),
    scope_role    VARCHAR(16)  NOT NULL,
    created       TIMESTAMP    NOT NULL,
    last_used_at  TIMESTAMP,
    expires_at    TIMESTAMP,
    revoked_at    TIMESTAMP
);

DO $$
BEGIN
    ALTER TABLE roller_api_token
        ADD CONSTRAINT roller_api_token_userid_fk
        FOREIGN KEY (userid) REFERENCES roller_user (id);
EXCEPTION
    WHEN duplicate_object THEN NULL;
    WHEN duplicate_table THEN NULL;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS roller_api_token_digest_uq
    ON roller_api_token (token_sha256);

CREATE INDEX IF NOT EXISTS roller_api_token_userid_idx
    ON roller_api_token (userid);
```

- [ ] **Step 4: Write the `ApiToken` entity**

Model it on `pojos/UserToken.java`: `private String id = UUIDGenerator.generateUUID();` plus `User user`, `String label`, `String tokenSha256`, `String scopeWeblog`, `Role scopeRole`, `Timestamp created`, `lastUsedAt`, `expiresAt`, `revokedAt`, each with a getter and setter, `equals`/`hashCode` on `id`, and:

```java
    /** What a token may do, at most. Never a grant -- only a ceiling. */
    public enum Role { READ, POST, ADMIN }

    /** True when this token is neither revoked nor past its expiry. */
    public boolean isUsable() {
        if (revokedAt != null) {
            return false;
        }
        return expiresAt == null || expiresAt.after(new java.sql.Timestamp(System.currentTimeMillis()));
    }
```

`toString()` must **not** include `tokenSha256`.

- [ ] **Step 5: Write `ApiToken.orm.xml`**

Copy the structure of `UserToken.orm.xml`, table `roller_api_token`, `access="PROPERTY"`, `metadata-complete="true"`, with:

```xml
        <named-query name="ApiToken.getByDigest">
            <query>SELECT t FROM ApiToken t WHERE t.tokenSha256 = ?1</query>
        </named-query>
        <named-query name="ApiToken.getByUser">
            <query>SELECT t FROM ApiToken t WHERE t.user = ?1 ORDER BY t.created DESC</query>
        </named-query>
        <named-query name="ApiToken.removeByUser">
            <query>DELETE FROM ApiToken t WHERE t.user = ?1</query>
        </named-query>
```

Column names: `id`, `label`, `token_sha256` (unique, not null), `scope_weblog` (nullable), `scope_role` with `<enumerated>STRING</enumerated>`, `created` (not null), `last_used_at`, `expires_at`, `revoked_at` (all nullable), and `<many-to-one name="user">` joining `userid` not null.

Register it in `persistence.xml` after the `UserToken.orm.xml` line:

```xml
    <mapping-file>org/apache/roller/weblogger/pojos/ApiToken.orm.xml</mapping-file>
```

- [ ] **Step 6: Write the manager interface and implementation**

```java
package org.apache.roller.weblogger.business;

import java.sql.Timestamp;
import java.util.List;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.User;

/**
 * Long-lived credentials for the automation API.
 *
 * <p>Distinct from {@link UserTokenManager}, whose tokens are single-use and
 * expire in an hour. These are multi-use, optionally perpetual, and revocable.
 */
public interface ApiTokenManager {

    /** Prefix on every raw token, so one is recognisable in a log or a paste. */
    String TOKEN_PREFIX = "rlr_";

    /**
     * Mints a token and returns the raw secret. This is the only time the
     * secret exists outside the caller's hands -- only its digest is stored.
     */
    String issueToken(User user, String label, String scopeWeblog,
                      ApiToken.Role role, Timestamp expiresAt) throws WebloggerException;

    /** The token behind this secret, or null if unknown, expired or revoked. */
    ApiToken authenticate(String rawToken) throws WebloggerException;

    List<ApiToken> getTokens(User user) throws WebloggerException;

    /** Revokes {@code tokenId} if {@code user} owns it. False otherwise. */
    boolean revoke(User user, String tokenId) throws WebloggerException;
}
```

`JPAApiTokenManagerImpl` mirrors `JPAUserTokenManagerImpl`'s shape (constructor takes `JPAPersistenceStrategy strategy`). Three points to get right:

```java
    private static final long LAST_USED_RESOLUTION_MS = 60L * 60L * 1000L;

    @Override
    public String issueToken(User user, String label, String scopeWeblog,
                             ApiToken.Role role, Timestamp expiresAt) throws WebloggerException {
        String raw = ApiTokenManager.TOKEN_PREFIX + TokenGenerator.newToken();
        ApiToken token = new ApiToken();
        token.setUser(user);
        token.setLabel(label);
        token.setTokenSha256(TokenGenerator.sha256Hex(raw));
        token.setScopeWeblog(scopeWeblog);
        token.setScopeRole(role);
        token.setCreated(new Timestamp(System.currentTimeMillis()));
        token.setExpiresAt(expiresAt);
        strategy.store(token);
        return raw;
    }

    @Override
    public ApiToken authenticate(String rawToken) throws WebloggerException {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        TypedQuery<ApiToken> query = strategy.getNamedQuery("ApiToken.getByDigest", ApiToken.class);
        query.setParameter(1, TokenGenerator.sha256Hex(rawToken));
        ApiToken token;
        try {
            token = query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
        if (!token.isUsable()) {
            return null;
        }
        touchLastUsed(token);
        return token;
    }

    /**
     * Coarse on purpose: writing last_used_at on every call would make each
     * API read a write too. An hour's resolution is plenty for deciding
     * whether a token is still in use before revoking it.
     */
    private void touchLastUsed(ApiToken token) throws WebloggerException {
        long now = System.currentTimeMillis();
        Timestamp last = token.getLastUsedAt();
        if (last == null || now - last.getTime() > LAST_USED_RESOLUTION_MS) {
            token.setLastUsedAt(new Timestamp(now));
            strategy.store(token);
        }
    }

    @Override
    public boolean revoke(User user, String tokenId) throws WebloggerException {
        ApiToken token = strategy.load(tokenId, ApiToken.class);
        // Ownership check, not a convenience: tokenId is client input and this
        // is a global by-id load, so without it any user could revoke any
        // other user's tokens.
        if (token == null || !token.getUser().getId().equals(user.getId())) {
            return false;
        }
        if (token.getRevokedAt() == null) {
            token.setRevokedAt(new Timestamp(System.currentTimeMillis()));
            strategy.store(token);
        }
        return true;
    }
```

- [ ] **Step 7: Wire the bean and the facade**

In `WebloggerBeanConfig`, after the `userTokenManager` bean:

```java
    @Bean
    public ApiTokenManager apiTokenManager(JPAPersistenceStrategy strategy) {
        return new JPAApiTokenManagerImpl(strategy);
    }
```

Add `ApiTokenManager getApiTokenManager();` to `Weblogger` (with javadoc matching the neighbours' style), add the constructor parameter and field to `WebloggerImpl`, and add the argument at the `WebloggerImpl` construction site in `WebloggerBeanConfig` (the `weblogger(...)` bean method, which already takes every other manager).

- [ ] **Step 8: Run the manager test**

Run: `mvn -pl app test -Dtest=ApiTokenManagerTest`
Expected: PASS, 6 tests.

- [ ] **Step 9: Commit**

```bash
git add bin/db/migrations/V026__api_tokens.sql \
        app/src/main/java/org/apache/roller/weblogger/pojos/ApiToken.java \
        app/src/main/resources/org/apache/roller/weblogger/pojos/ApiToken.orm.xml \
        app/src/main/java/org/apache/roller/weblogger/business/ApiTokenManager.java \
        app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAApiTokenManagerImpl.java \
        app/src/main/resources/META-INF/persistence.xml \
        app/src/main/java/org/apache/roller/weblogger/business/jpa/WebloggerBeanConfig.java \
        app/src/main/java/org/apache/roller/weblogger/business/Weblogger.java \
        app/src/main/java/org/apache/roller/weblogger/business/WebloggerImpl.java \
        app/src/test/java/org/apache/roller/weblogger/business/ApiTokenManagerTest.java
git commit -m "api: roller_api_token, entity and manager

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Bearer authentication and the security chain

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/auth/ApiPrincipal.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/auth/ApiTokenAuthFilter.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/boot/SecurityConfig.java:219-280`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/auth/ApiTokenAuthFilterTest.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/boot/ApiSecurityChainTest.java`

**Interfaces:**
- Consumes: `ApiTokenManager.authenticate(String)`, `ApiToken.Role`.
- Produces:
  - `record ApiPrincipal(String userName, String scopeWeblog, ApiToken.Role scopeRole)`.
  - `ApiTokenAuthFilter extends OncePerRequestFilter` — on a valid Bearer header sets an `Authentication` whose **principal is the user name `String`** and whose `getDetails()` is the `ApiPrincipal`.
  - `SecurityConfig.apiSecurityFilterChain(HttpSecurity, ApiTokenAuthFilter)` — `@Bean @Order(1)`.

- [ ] **Step 1: Write the failing filter test**

```java
package org.apache.roller.weblogger.ui.restapi.auth;

import jakarta.servlet.FilterChain;
import org.apache.roller.weblogger.business.ApiTokenManager;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiTokenAuthFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static ApiToken token(String weblog, ApiToken.Role role) {
        User user = new User();
        user.setUserName("agent");
        ApiToken t = new ApiToken();
        t.setUser(user);
        t.setScopeWeblog(weblog);
        t.setScopeRole(role);
        return t;
    }

    @Test
    void aValidBearerTokenAuthenticatesWithTheUserNameAsPrincipal() throws Exception {
        ApiTokenManager mgr = mock(ApiTokenManager.class);
        when(mgr.authenticate("rlr_good")).thenReturn(token("testblog", ApiToken.Role.POST));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ping");
        request.addHeader("Authorization", "Bearer rlr_good");
        FilterChain chain = mock(FilterChain.class);

        new ApiTokenAuthFilter(() -> mgr)
                .doFilter(request, new MockHttpServletResponse(), chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        // RollerHandlerInterceptor.resolveAuthenticatedUser has a branch for a
        // String principal, so identity resolution works unchanged.
        assertEquals("agent", auth.getPrincipal());
        ApiPrincipal details = (ApiPrincipal) auth.getDetails();
        assertEquals("testblog", details.scopeWeblog());
        assertEquals(ApiToken.Role.POST, details.scopeRole());
        verify(chain).doFilter(any(), any());
    }

    @Test
    void anUnknownTokenLeavesTheContextEmptyAndStillCallsTheChain() throws Exception {
        ApiTokenManager mgr = mock(ApiTokenManager.class);
        when(mgr.authenticate(anyString())).thenReturn(null);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ping");
        request.addHeader("Authorization", "Bearer rlr_bad");
        FilterChain chain = mock(FilterChain.class);

        new ApiTokenAuthFilter(() -> mgr)
                .doFilter(request, new MockHttpServletResponse(), chain);

        // The filter never rejects; authorization is the chain's job, so an
        // unauthenticated request reaches it and gets a 401 there.
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(any(), any());
    }

    @Test
    void aMissingOrNonBearerHeaderIsIgnored() throws Exception {
        ApiTokenManager mgr = mock(ApiTokenManager.class);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tokens");
        request.addHeader("Authorization", "Basic am9objpwdw==");
        FilterChain chain = mock(FilterChain.class);

        new ApiTokenAuthFilter(() -> mgr)
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(mgr);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=ApiTokenAuthFilterTest`
Expected: FAIL — `ApiPrincipal` and `ApiTokenAuthFilter` do not exist.

- [ ] **Step 3: Write `ApiPrincipal` and the filter**

```java
package org.apache.roller.weblogger.ui.restapi.auth;

import org.apache.roller.weblogger.pojos.ApiToken;

/**
 * The token ceiling attached to an authenticated API request. Carried as the
 * Authentication's details; the principal itself stays a plain user-name
 * String so RollerHandlerInterceptor resolves the User unchanged.
 */
public record ApiPrincipal(String userName, String scopeWeblog, ApiToken.Role scopeRole) { }
```

```java
package org.apache.roller.weblogger.ui.restapi.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.business.ApiTokenManager;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns {@code Authorization: Bearer rlr_...} into an authenticated
 * SecurityContext.
 *
 * <p>Never rejects a request: an absent or bad token simply leaves the
 * context empty and the security chain answers 401. Keeping rejection in one
 * place means the API cannot grow two different unauthenticated responses.
 *
 * <p>The manager arrives through a Supplier because the business tier is
 * built lazily at {@code WebloggerFactory.bootstrap()}, after this filter
 * bean is constructed.
 */
public class ApiTokenAuthFilter extends OncePerRequestFilter {

    private static final Log log = LogFactory.getLog(ApiTokenAuthFilter.class);
    private static final String BEARER = "Bearer ";

    private final Supplier<ApiTokenManager> tokenManager;

    public ApiTokenAuthFilter(Supplier<ApiTokenManager> tokenManager) {
        this.tokenManager = tokenManager;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            authenticate(header.substring(BEARER.length()).trim());
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String rawToken) {
        try {
            ApiToken token = tokenManager.get().authenticate(rawToken);
            if (token == null) {
                return;
            }
            String userName = token.getUser().getUserName();
            ApiPrincipal principal =
                    new ApiPrincipal(userName, token.getScopeWeblog(), token.getScopeRole());
            var auth = new UsernamePasswordAuthenticationToken(
                    userName, null, List.of(new SimpleGrantedAuthority("api")));
            auth.setDetails(principal);
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (Exception e) {
            // Never let a lookup failure become an authenticated request.
            log.error("Error authenticating API token", e);
        }
    }
}
```

- [ ] **Step 4: Write the failing chain test**

```java
package org.apache.roller.weblogger.boot;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The API gets its own SecurityFilterChain rather than new rules inside the
 * UI's. The UI chain declares no securityMatcher, so it matches everything
 * and MUST be ordered after the API chain or it would swallow /api/**.
 */
class ApiSecurityChainTest {

    private static Method chain(String name) throws Exception {
        for (Method m : SecurityConfig.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError("no method named " + name);
    }

    @Test
    void theApiChainIsOrderedAheadOfTheUiChain() throws Exception {
        Order api = chain("apiSecurityFilterChain").getAnnotation(Order.class);
        Order ui = chain("securityFilterChain").getAnnotation(Order.class);
        assertNotNull(api, "the API chain must declare an explicit order");
        assertNotNull(ui, "the UI chain must declare an explicit order once a second chain exists");
        assertTrue(api.value() < ui.value(),
                "the catch-all UI chain would otherwise match /api/** first");
    }
}
```

- [ ] **Step 5: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=ApiSecurityChainTest`
Expected: FAIL — no `apiSecurityFilterChain` method.

- [ ] **Step 6: Add the API chain**

In `SecurityConfig`, add the bean and annotate the existing `securityFilterChain` with `@Order(2)`:

```java
    /**
     * The automation API's own chain.
     *
     * <p>Separate from the UI chain rather than folded into it. The UI chain's
     * csrf.ignoringRequestMatchers list holds exactly one narrow entry and its
     * narrowness is the point; a matcher-scoped chain disables CSRF only where
     * no cookie-authenticated request can reach.
     *
     * <p>Stateless: an API call must never mint a session cookie. HTTP Basic
     * is reachable only at POST /api/v1/tokens -- a Bearer caller must not be
     * able to mint another token, or any leaked token becomes a permanent one.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http, ApiTokenAuthFilter apiTokenAuthFilter,
            AuthenticationManager authenticationManager) throws Exception {

        http
            .securityMatcher("/api/**")
            .authenticationManager(authenticationManager)
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/v1/tokens").authenticated()
                .requestMatchers("/api/v1/ping").permitAll()
                .anyRequest().authenticated())
            .httpBasic(basic -> { })
            .addFilterBefore(apiTokenAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .logout(AbstractHttpConfigurer::disable)
            .rememberMe(AbstractHttpConfigurer::disable);

        return http.build();
    }

    @Bean
    public ApiTokenAuthFilter apiTokenAuthFilter() {
        return new ApiTokenAuthFilter(
                () -> WebloggerFactory.getWeblogger().getApiTokenManager());
    }
```

- [ ] **Step 7: Write the failing throttle test**

```java
package org.apache.roller.weblogger.ui.restapi.auth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The mint endpoint takes a password, so it is the one place on this API
 * where guessing pays. It is throttled by client address; every other
 * endpoint is throttled by token, so one noisy agent cannot starve another.
 *
 * Sizing stays startup-scoped (api.throttle.threshold / .interval /
 * .maxentries) because it dimensions a fixed cache that cannot be resized
 * under live callers -- the same reason the contact and newsletter throttles
 * are startup-scoped. Only the on/off switch is runtime-settable.
 */
class ApiThrottleTest {

    @Test
    void aCallerIsRefusedOnceItPassesTheThreshold() {
        ApiThrottle throttle = ApiThrottle.forTesting(3, 60);

        assertFalse(throttle.isThrottled("agent-a"));
        assertFalse(throttle.isThrottled("agent-a"));
        assertFalse(throttle.isThrottled("agent-a"));
        assertTrue(throttle.isThrottled("agent-a"), "the fourth call exceeds a threshold of 3");
    }

    @Test
    void oneCallerCannotStarveAnother() {
        ApiThrottle throttle = ApiThrottle.forTesting(1, 60);

        assertFalse(throttle.isThrottled("agent-a"));
        assertTrue(throttle.isThrottled("agent-a"));
        assertFalse(throttle.isThrottled("agent-b"), "buckets are per key, not global");
    }

    @Test
    void disablingTheThrottleLetsEverythingThrough() {
        ApiThrottle throttle = ApiThrottle.disabled();
        for (int i = 0; i < 100; i++) {
            assertFalse(throttle.isThrottled("agent-a"));
        }
    }
}
```

- [ ] **Step 8: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=ApiThrottleTest`
Expected: FAIL — `ApiThrottle` does not exist.

- [ ] **Step 9: Write `ApiThrottle` and apply it**

Create `ui/restapi/auth/ApiThrottle.java` wrapping the existing `org.apache.roller.weblogger.util.GenericThrottle` — read how `ContactController` constructs and consults it first:

Run: `grep -n -B3 -A12 "GenericThrottle" app/src/main/java/org/apache/roller/weblogger/ui/controllers/ContactController.java`

Expose `boolean isThrottled(String key)`, a package-visible `static ApiThrottle forTesting(int threshold, int intervalSeconds)`, and `static ApiThrottle disabled()`. Read `api.throttle.enabled` per call (runtime-settable) and the three sizing values once at construction (startup-scoped).

Apply it in `ApiTokenAuthFilter.doFilterInternal`, before authentication:

```java
        // Key by token where there is one, by client address otherwise -- so
        // the password-taking mint endpoint is rate-limited by caller and
        // every other endpoint by credential.
        String key = (header != null && header.startsWith(BEARER))
                ? sha256Of(header.substring(BEARER.length()).trim())
                : request.getRemoteAddr();
        if (throttle.isThrottled(key)) {
            throw ApiException.throttled("Too many requests. Slow down and retry.");
        }
```

The digest, not the raw token, is the map key: a throttle map holding live credentials in memory is a credential store nobody meant to build.

Add to `roller.properties` beside `newsletter.subscribe.throttle.*`:

```
api.throttle.enabled=true
api.throttle.threshold=120
api.throttle.interval=60
api.throttle.maxentries=500
```

and a matching `<property-def>` for `api.throttle.enabled` in `runtimeConfigDefs.xml`. **A bare `--` inside an XML comment there makes the whole file fail to parse silently**, surfacing later as unrelated NPEs — keep any comment free of double hyphens.

- [ ] **Step 10: Run the tests**

Run: `mvn -pl app test -Dtest=ApiTokenAuthFilterTest+ApiSecurityChainTest+ApiThrottleTest+SecurityConfigTest`
Expected: PASS. `SecurityConfigTest` must stay green — the UI chain's behaviour is unchanged.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/auth/ \
        app/src/main/java/org/apache/roller/weblogger/boot/SecurityConfig.java \
        app/src/main/resources/org/apache/roller/weblogger/config/roller.properties \
        app/src/main/resources/org/apache/roller/weblogger/config/runtimeConfigDefs.xml \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/auth/ \
        app/src/test/java/org/apache/roller/weblogger/boot/ApiSecurityChainTest.java
git commit -m "api: bearer authentication, matcher-scoped security chain, per-token throttle

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Scope enforcement, weblog resolution, and the tokens/me endpoints

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/auth/ApiScopeInterceptor.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/TokensApi.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/TokenDtos.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/MetaApi.java` (add `/me`)
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/RollerHandlerInterceptor.java:82-97`
- Modify: the MVC config that registers `RollerHandlerInterceptor` (find it with `grep -rn "RollerHandlerInterceptor" app/src/main/java --include=*.java`), to also register `ApiScopeInterceptor` for `/api/**`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/auth/ApiScopeInterceptorTest.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/RollerHandlerInterceptorPathVariableTest.java`

**Interfaces:**
- Consumes: `ApiPrincipal`, `ApiToken.Role`, `ApiException`.
- Produces:
  - `ApiScopeInterceptor implements HandlerInterceptor`.
  - `TokenDtos.TokenView(String id, String label, String scopeWeblog, String scopeRole, Instant created, Instant lastUsedAt, Instant expiresAt)`, `TokenDtos.IssueRequest(String label, String weblog, String role, Instant expiresAt)`, `TokenDtos.IssuedToken(String token, TokenView token_info)`.
  - `TokensApi` at `/v1/tokens`, `MetaApi.me()` at `/v1/me`.

- [ ] **Step 1: Write the failing path-variable test**

```java
package org.apache.roller.weblogger.ui.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The UI passes the weblog as a request parameter; REST carries it as a URI
 * template variable. One resolution helper serves both, so there is a single
 * authorization path rather than a parallel one for /api/**.
 */
class RollerHandlerInterceptorPathVariableTest {

    @Test
    void theRequestParameterWinsWhenPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("weblog", "fromparam");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("handle", "frompath"));

        assertEquals("fromparam", RollerHandlerInterceptor.resolveWeblogHandle(request));
    }

    @Test
    void thePathVariableIsUsedWhenTheParameterIsAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("handle", "frompath"));

        assertEquals("frompath", RollerHandlerInterceptor.resolveWeblogHandle(request));
    }

    @Test
    void aBlankParameterFallsThroughRatherThanWinning() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("weblog", "   ");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("handle", "frompath"));

        assertEquals("frompath", RollerHandlerInterceptor.resolveWeblogHandle(request));
    }

    @Test
    void nullWhenNeitherIsPresent() {
        assertEquals(null, RollerHandlerInterceptor.resolveWeblogHandle(new MockHttpServletRequest()));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=RollerHandlerInterceptorPathVariableTest`
Expected: FAIL — `resolveWeblogHandle` does not exist.

- [ ] **Step 3: Add the resolver and use it**

In `RollerHandlerInterceptor`, add:

```java
    /**
     * The action weblog's handle for this request.
     *
     * <p>The JSP UI submits it as a {@code weblog} request parameter; the REST
     * API carries it as a {@code handle} URI template variable. Spring MVC
     * populates URI_TEMPLATE_VARIABLES_ATTRIBUTE during getHandler(), before
     * any interceptor runs, so both are readable here -- which is what lets
     * one interceptor enforce permissions for both surfaces instead of the API
     * growing a second, divergent implementation.
     *
     * <p>Package-visible and static so it can be tested without a container.
     */
    static String resolveWeblogHandle(HttpServletRequest request) {
        String handle = request.getParameter("weblog");
        if (handle != null && !handle.isBlank()) {
            return handle;
        }
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars instanceof Map<?, ?> map) {
            Object fromPath = map.get("handle");
            if (fromPath instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }
```

Replace the existing section 2 body so it reads:

```java
        // --- 2. Resolve weblog from request parameter or URI template ---
        String weblogHandle = resolveWeblogHandle(request);
```

leaving the rest of that block unchanged.

- [ ] **Step 4: Run the test**

Run: `mvn -pl app test -Dtest=RollerHandlerInterceptorPathVariableTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Write the failing scope test**

```java
package org.apache.roller.weblogger.ui.restapi.auth;

import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ApiScopeInterceptorTest {

    private final ApiScopeInterceptor interceptor = new ApiScopeInterceptor();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request(String method, String uri, String handle,
                                                  String scopeWeblog, ApiToken.Role role) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (handle != null) {
            request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                    Map.of("handle", handle));
        }
        var auth = new UsernamePasswordAuthenticationToken("agent", null, List.of());
        auth.setDetails(new ApiPrincipal("agent", scopeWeblog, role));
        SecurityContextHolder.getContext().setAuthentication(auth);
        return request;
    }

    @Test
    void aTokenScopedToOneWeblogCannotReachAnother() {
        MockHttpServletRequest request =
                request("GET", "/api/v1/weblogs/other/entries", "other", "testblog", ApiToken.Role.POST);

        ApiException thrown = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        // 404, not 403: a 403 would confirm that 'other' exists.
        assertEquals(404, thrown.getStatus());
    }

    @Test
    void aReadTokenCannotWrite() {
        MockHttpServletRequest request =
                request("POST", "/api/v1/weblogs/testblog/entries", "testblog", "testblog", ApiToken.Role.READ);

        ApiException thrown = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals(403, thrown.getStatus());
    }

    @Test
    void aPostTokenMayWriteContentButNotReachAdminPaths() throws Exception {
        assertTrue(interceptor.preHandle(
                request("POST", "/api/v1/weblogs/testblog/entries", "testblog", "testblog", ApiToken.Role.POST),
                new MockHttpServletResponse(), new Object()));

        ApiException thrown = assertThrows(ApiException.class, () -> interceptor.preHandle(
                request("POST", "/api/v1/admin/users", null, null, ApiToken.Role.POST),
                new MockHttpServletResponse(), new Object()));
        assertEquals(403, thrown.getStatus());
    }

    @Test
    void anUnscopedAdminTokenPasses() throws Exception {
        assertTrue(interceptor.preHandle(
                request("POST", "/api/v1/admin/users", null, null, ApiToken.Role.ADMIN),
                new MockHttpServletResponse(), new Object()));
    }

    /**
     * Session-authenticated requests (there are none today, but the UI could
     * grow one) carry no ApiPrincipal. The interceptor must not then invent a
     * ceiling -- it defers entirely to RollerHandlerInterceptor.
     */
    @Test
    void aRequestWithNoApiPrincipalIsLeftAlone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ping");
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }
}
```

- [ ] **Step 6: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=ApiScopeInterceptorTest`
Expected: FAIL — `ApiScopeInterceptor` does not exist.

- [ ] **Step 7: Write `ApiScopeInterceptor`**

```java
package org.apache.roller.weblogger.ui.restapi.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Applies the token's ceiling on top of the permission checks
 * RollerHandlerInterceptor already performs.
 *
 * <p>This narrows; it never grants. A request that passes here still has to
 * satisfy the caller's real GlobalPermission/WeblogPermission.
 */
public class ApiScopeInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        ApiPrincipal principal = currentPrincipal();
        if (principal == null) {
            return true;
        }

        String handle = pathHandle(request);
        if (principal.scopeWeblog() != null
                && handle != null
                && !principal.scopeWeblog().equals(handle)) {
            // 404 rather than 403: a 403 confirms the weblog exists.
            throw ApiException.notFound("No such weblog.");
        }

        ApiToken.Role role = principal.scopeRole();
        if (isAdminPath(request) && role != ApiToken.Role.ADMIN) {
            throw ApiException.forbidden("This token is not scoped for administration.");
        }
        if (!isRead(request) && role == ApiToken.Role.READ) {
            throw ApiException.forbidden("This token is read-only.");
        }
        return true;
    }

    private static ApiPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        return auth.getDetails() instanceof ApiPrincipal p ? p : null;
    }

    private static String pathHandle(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars instanceof Map<?, ?> map && map.get("handle") instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    private static boolean isAdminPath(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/api/v1/admin/");
    }

    private static boolean isRead(HttpServletRequest request) {
        String method = request.getMethod();
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }
}
```

- [ ] **Step 8: Register the interceptor**

Find the WebMvcConfigurer registering `RollerHandlerInterceptor`:

Run: `grep -rn "RollerHandlerInterceptor" app/src/main/java --include=*.java`

In that class's `addInterceptors`, add after the existing registration:

```java
        registry.addInterceptor(new ApiScopeInterceptor()).addPathPatterns("/api/**");
```

- [ ] **Step 9: Write the failing TokensApi test**

```java
package org.apache.roller.weblogger.ui.restapi.v1;

import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.apache.roller.weblogger.ui.restapi.auth.ApiPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Token-mints-token is a privilege-escalation path: it turns any leaked token
 * into a permanent one, and into one whose scope the thief chooses. Minting is
 * therefore Basic-only, and this is the test that says so -- the security
 * chain permits Basic on this path, so nothing else would catch a regression
 * here.
 */
class TokensApiTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc mockMvc(TokensApi controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private void authenticateWithABearerToken() {
        var auth = new UsernamePasswordAuthenticationToken("agent", null, List.of());
        auth.setDetails(new ApiPrincipal("agent", null, ApiToken.Role.ADMIN));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void aBearerAuthenticatedCallerCannotMintAToken() throws Exception {
        authenticateWithABearerToken();

        mockMvc(new TokensApi(/* mocked Weblogger */))
                .perform(post("/v1/tokens")
                        .contentType("application/json")
                        .content("{\"label\":\"escalation\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    /**
     * Revoking an id the caller does not own answers 404, not 403 -- a 403
     * would confirm the id exists and let one user enumerate another's tokens.
     */
    @Test
    void revokingSomeoneElsesTokenIs404() throws Exception {
        authenticateWithABearerToken();

        mockMvc(new TokensApi(/* mocked Weblogger whose revoke returns false */))
                .perform(delete("/v1/tokens/{id}", "someone-elses-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownRoleIsRejected() throws Exception {
        mockMvc(new TokensApi(/* mocked Weblogger */))
                .perform(post("/v1/tokens")
                        .contentType("application/json")
                        .content("{\"label\":\"x\",\"role\":\"SUPERUSER\"}"))
                .andExpect(status().isBadRequest());
    }
}
```

Mock `Weblogger` with Mockito the way the existing `*ControllerTest`s do — check one first with `grep -n "mock(" app/src/test/java/org/apache/roller/weblogger/ui/controllers/editor/MembersControllerTest.java | head`.

- [ ] **Step 10: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=TokensApiTest`
Expected: FAIL — `TokensApi` does not exist.

- [ ] **Step 11: Write the token DTOs and endpoints**

`TokenDtos`:

```java
package org.apache.roller.weblogger.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.Timestamp;
import java.time.Instant;
import org.apache.roller.weblogger.pojos.ApiToken;

/** Views of an API token. The secret appears in exactly one of these. */
public final class TokenDtos {

    private TokenDtos() { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenView(String id, String label, String scopeWeblog, String scopeRole,
                            Instant created, Instant lastUsedAt, Instant expiresAt) { }

    public record IssueRequest(String label, String weblog, String role, Instant expiresAt) { }

    /** The one response carrying a raw secret. Returned once, never again. */
    public record IssuedToken(String token, TokenView info) { }

    public static TokenView toView(ApiToken token) {
        return new TokenView(
                token.getId(),
                token.getLabel(),
                token.getScopeWeblog(),
                token.getScopeRole() == null ? null : token.getScopeRole().name(),
                instant(token.getCreated()),
                instant(token.getLastUsedAt()),
                instant(token.getExpiresAt()));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
```

`TokensApi` at `@RequestMapping("/v1/tokens")` with:
- `POST ""` — reads the authenticated user from `SecurityContextHolder`, **rejects with `ApiException.forbidden` when the request carries an `ApiPrincipal`** (a Bearer caller must not mint), parses `role` case-insensitively into `ApiToken.Role` (`ApiException.badRequest` on an unknown value), calls `issueToken`, returns `201` with `IssuedToken`.
- `GET ""` — `getTokens(user).stream().map(TokenDtos::toView).toList()`.
- `DELETE "/{id}"` — `@PathVariable("id") String id`; `revoke` returning false becomes `ApiException.notFound("No such token.")`, so one user cannot probe another's ids.

Add to `MetaApi`:

```java
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        User user = (User) request.getAttribute("authenticatedUser");
        if (user == null) {
            throw ApiException.forbidden("Not authenticated.");
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        ApiPrincipal principal =
                auth != null && auth.getDetails() instanceof ApiPrincipal p ? p : null;
        return ResponseEntity.ok(Map.of(
                "userName", user.getUserName(),
                "screenName", user.getScreenName(),
                "globalAdmin", user.hasGlobalPermission(GlobalPermission.ADMIN),
                "tokenScope", principal == null ? Map.of() : Map.of(
                        "weblog", principal.scopeWeblog() == null ? "" : principal.scopeWeblog(),
                        "role", principal.scopeRole().name())));
    }
```

If `User` has no `hasGlobalPermission` helper, check the global permission the way `RollerHandlerInterceptor` does — `new GlobalPermission(List.of(GlobalPermission.ADMIN))` passed to `UserManager.checkPermission`.

- [ ] **Step 12: Run the scope test, the token test, and the full suite**

Run: `mvn -pl app test -Dtest=ApiScopeInterceptorTest+TokensApiTest`
Expected: PASS, 8 tests.

Run: `mvn -pl app test`
Expected: PASS — no regression in the ~3122 existing tests.

- [ ] **Step 13: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/ \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/RollerHandlerInterceptor.java \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/auth/ApiScopeInterceptorTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/RollerHandlerInterceptorPathVariableTest.java
git add -u
git commit -m "api: token scope ceiling, path-variable weblog resolution, /v1/tokens and /v1/me

One authorization path serves the UI and the API: RollerHandlerInterceptor
now resolves the action weblog from a URI template variable when no weblog
request parameter is present.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Phase 2 — Entries

### Task 6: Extract `EntryFieldRules` (pure refactor)

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/EntryFieldRules.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/EntryBean.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/EntryFieldRulesTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `static String escapeTitle(String rawTitle)` — `StringEscapeUtils.escapeHtml4`, null-safe.
  - `static Timestamp parsePubTime(String wallClock, TimeZone zone)` — blank/null → `null` (meaning "now"); unparseable → `IllegalArgumentException`.

This task changes **no behaviour**. Its whole purpose is that Task 8 has one place to call instead of a second copy of the rules.

- [ ] **Step 1: Write the characterisation test**

```java
package org.apache.roller.weblogger.ui.controllers;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The escaping asymmetry these rules encode is the sharpest trap in the
 * codebase: an ENTRY title is stored escaped (so themes emit it bare), while
 * a PAGE title is stored raw (so templates must escape at render). Re-deriving
 * either rule at a second call site is how that asymmetry becomes stored XSS,
 * which is why both live here and nowhere else.
 */
class EntryFieldRulesTest {

    @Test
    void titlesAreStoredHtmlEscaped() {
        assertEquals("Cats &amp; Dogs", EntryFieldRules.escapeTitle("Cats & Dogs"));
        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;",
                EntryFieldRules.escapeTitle("<script>alert(1)</script>"));
    }

    @Test
    void escapingIsNullSafe() {
        assertNull(EntryFieldRules.escapeTitle(null));
    }

    @Test
    void aBlankPubTimeMeansPublishNow() {
        assertNull(EntryFieldRules.parsePubTime(null, TimeZone.getTimeZone("UTC")));
        assertNull(EntryFieldRules.parsePubTime("", TimeZone.getTimeZone("UTC")));
        assertNull(EntryFieldRules.parsePubTime("   ", TimeZone.getTimeZone("UTC")));
    }

    /**
     * pubTimeLocal is wall-clock time in the WEBLOG's zone -- not the server's
     * and not the browser's. The same string in two zones is two instants.
     */
    @Test
    void theWallClockStringIsReadInTheWeblogsZone() {
        String wall = "2026-03-01T09:30";
        Timestamp utc = EntryFieldRules.parsePubTime(wall, TimeZone.getTimeZone("UTC"));
        Timestamp tokyo = EntryFieldRules.parsePubTime(wall, TimeZone.getTimeZone("Asia/Tokyo"));

        assertNotNull(utc);
        assertNotNull(tokyo);
        assertNotEquals(utc.getTime(), tokyo.getTime(),
                "identical wall clocks in different zones are different instants");
        assertEquals(9L * 3600_000L, utc.getTime() - tokyo.getTime(),
                "Tokyo is UTC+9, so its 09:30 happened nine hours earlier");
    }

    /**
     * A mistyped pubtime must block the save rather than silently publishing
     * "now" -- that was the old dateString parser's failure mode.
     */
    @Test
    void anUnparseableValueThrowsRatherThanDefaultingToNow() {
        assertThrows(IllegalArgumentException.class,
                () -> EntryFieldRules.parsePubTime("not a date", TimeZone.getTimeZone("UTC")));
    }

    @Test
    void aParsedValueRoundTripsToTheSameLocalDateTime() {
        TimeZone zone = TimeZone.getTimeZone("America/New_York");
        Timestamp parsed = EntryFieldRules.parsePubTime("2026-07-04T13:45", zone);
        assertNotNull(parsed);
        LocalDateTime back = LocalDateTime.ofInstant(parsed.toInstant(), zone.toZoneId());
        assertEquals(LocalDateTime.of(2026, 7, 4, 13, 45), back);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=EntryFieldRulesTest`
Expected: FAIL — `EntryFieldRules` does not exist.

- [ ] **Step 3: Read `EntryBean` first, then move the logic**

Run: `grep -n "escapeHtml4\|getPubTime\|pubTimeLocal" app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/EntryBean.java`

Create `EntryFieldRules` with the two static methods, moving the existing implementations verbatim — same parse format, same exception behaviour, same blank handling. Then change `EntryBean.copyTo` to call `EntryFieldRules.escapeTitle(...)` and `EntryBean.getPubTime(TimeZone)` to delegate to `EntryFieldRules.parsePubTime(...)`.

Add this javadoc to the new class:

```java
/**
 * The rules that turn an author's raw entry input into stored values.
 *
 * <p>Shared by {@code EntryBean} (the JSP editor) and the automation API so
 * the two cannot drift. Two rules matter more than the rest:
 *
 * <ul>
 *   <li>An entry title is stored HTML-escaped. This is the only place raw
 *       author input becomes escaped markup for an entry, which is why
 *       {@code WeblogEntry.getTitle()} returns entity-escaped text and every
 *       theme emits {@code $entry.title} bare. A PAGE title is the opposite --
 *       stored raw, escaped at render -- so copying this rule to the page side
 *       double-encodes and copying the page rule here is stored XSS.</li>
 *   <li>A pubtime is wall-clock time in the WEBLOG's zone. Blank means
 *       "publish now"; an unparseable non-blank value throws, so a mistyped
 *       time blocks the save instead of silently publishing immediately.</li>
 * </ul>
 */
```

- [ ] **Step 4: Run the new test and every existing entry test**

Run: `mvn -pl app test -Dtest=EntryFieldRulesTest+EntryEditControllerTest+EntryBeanTest`

(If `EntryBeanTest` does not exist, drop it from the list — find the real set with `ls app/src/test/java/org/apache/roller/weblogger/ui/controllers/editor/`.)

Expected: PASS. The pre-existing tests are the proof this refactor changed nothing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/EntryFieldRules.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/EntryBean.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/EntryFieldRulesTest.java
git commit -m "refactor: extract EntryFieldRules from EntryBean

No behaviour change. The API becomes the second caller in the next task, and
re-deriving the title-escaping rule there would be stored XSS.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Extract `WeblogOwnership` (pure refactor)

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/WeblogOwnership.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/BaseController.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/WeblogOwnershipTest.java`

**Interfaces:**
- Consumes: `Weblogger` managers.
- Produces, all returning `null` when the id is blank, unknown, or owned by a different weblog:
  - `static WeblogEntry entry(Weblogger weblogger, String id, Weblog weblog)`
  - `static WeblogCategory category(Weblogger weblogger, String id, Weblog weblog)`
  - `static WeblogTemplate template(Weblogger weblogger, String id, Weblog weblog)`
  - `static WeblogPage page(Weblogger weblogger, String id, Weblog weblog)`

- [ ] **Step 1: Read the four existing lookups**

Run: `grep -n -A25 "protected WeblogEntry lookupEntry\|protected WeblogCategory lookupCategory\|protected WeblogTemplate lookupTemplate\|protected WeblogPage lookupPage" app/src/main/java/org/apache/roller/weblogger/ui/controllers/BaseController.java`

- [ ] **Step 2: Write the failing test**

```java
package org.apache.roller.weblogger.ui.controllers;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The permission interceptor vouches for the ACTION weblog only. Every
 * by-id lookup is a global lookup, so without this check any editor could
 * read or rewrite any other weblog's rows by guessing an id.
 */
class WeblogOwnershipTest {

    private User user;
    private Weblog mine;
    private Weblog theirs;
    private WeblogEntry entry;
    private Weblogger weblogger;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        weblogger = WebloggerFactory.getWeblogger();
        user = TestUtils.setupUser("ownershiptestuser");
        mine = TestUtils.setupWeblog("ownership-mine", user);
        theirs = TestUtils.setupWeblog("ownership-theirs", user);
        entry = TestUtils.setupWeblogEntry("ownership-entry", mine, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(mine.getId());
        TestUtils.teardownWeblog(theirs.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void anEntryOfTheActionWeblogIsFound() {
        assertNotNull(WeblogOwnership.entry(weblogger, entry.getId(), mine));
    }

    @Test
    void anEntryOfAnotherWeblogIsNotFound() {
        assertNull(WeblogOwnership.entry(weblogger, entry.getId(), theirs),
                "a foreign id must read as absent, not as someone else's entry");
    }

    @Test
    void aBlankIdIsAbsentRatherThanSomethingToLookUp() {
        assertNull(WeblogOwnership.entry(weblogger, null, mine));
        assertNull(WeblogOwnership.entry(weblogger, "", mine));
        assertNull(WeblogOwnership.entry(weblogger, "   ", mine));
    }

    @Test
    void anUnknownIdIsAbsent() {
        assertNull(WeblogOwnership.entry(weblogger, "no-such-id", mine));
    }
}
```

Check the exact `TestUtils` factory signatures before writing this — run `grep -n "public static .* setupWeblog\|public static .* setupWeblogEntry\|public static .* setupUser" app/src/test/java/org/apache/roller/weblogger/TestUtils.java` and match them.

- [ ] **Step 3: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=WeblogOwnershipTest`
Expected: FAIL — `WeblogOwnership` does not exist.

- [ ] **Step 4: Move the bodies**

Create `WeblogOwnership` with the four static methods, each body moved verbatim from `BaseController` (blank-id guard, global by-id load, weblog comparison, exception swallowed and logged). Then rewrite each `BaseController` method as a one-line delegation, keeping its existing signature and javadoc:

```java
    protected WeblogEntry lookupEntry(String id, HttpServletRequest request) {
        return WeblogOwnership.entry(weblogger, id, getActionWeblog(request));
    }
```

- [ ] **Step 5: Run the ownership test plus every controller test**

Run: `mvn -pl app test -Dtest='*ControllerTest'`
Expected: PASS. These already cover the isolation behaviour and are the proof the move changed nothing.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/WeblogOwnership.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/BaseController.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/WeblogOwnershipTest.java
git commit -m "refactor: extract WeblogOwnership from BaseController

No behaviour change. One IDOR defense, about to gain a second caller.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Entry read endpoints

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/BaseApiController.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/EntryDtos.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesApi.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesApiReadTest.java`

**Interfaces:**
- Consumes: `WeblogOwnership.entry`, `WeblogEntrySearchCriteria`, `ApiException`.
- Produces:
  - `BaseApiController` with `protected Weblogger weblogger`, `protected Weblog requireActionWeblog(HttpServletRequest)`, and `protected WeblogEntry requireEntry(HttpServletRequest, String id)` — the last throwing `ApiException.notFound` rather than returning null, since every caller wants that.
  - `EntryDtos.EntryView` — `id, anchor, title, summary, text, status, category, tags, pubTime, updateTime, permalink, metaTitle, searchDescription, canonicalUrl, noindex, featuredImageId, ogImageId`.
  - `EntryDtos.EntryPage(List<EntryView> items, int offset, int limit, boolean hasMore)`.
  - `EntryDtos.parseWritableStatus(String)` — the four writable statuses; rejects `TRASHED`.
  - `EntryDtos.parseFilterStatus(String)` — the same four **plus** `TRASHED`, because reading the trash is exactly what the trash list is for. The two parsers exist separately so a writable-status check can never be softened into a filter check by accident.
  - `EntriesApi` at `/v1/weblogs/{handle}/entries` with `list` and `get`.

- [ ] **Step 1: Write the failing test**

```java
package org.apache.roller.weblogger.ui.restapi.v1;

import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.ui.restapi.dto.EntryDtos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntriesApiReadTest {

    /**
     * Titles are stored escaped, so the DTO carries them through unchanged.
     * Escaping again here would send "&amp;amp;" to every client.
     */
    @Test
    void theViewCarriesTheStoredTitleWithoutReEscaping() {
        WeblogEntry entry = new WeblogEntry();
        entry.setTitle("Cats &amp; Dogs");
        entry.setStatus(WeblogEntry.PubStatus.DRAFT);

        EntryDtos.EntryView view = EntryDtos.toView(entry, null);
        assertEquals("Cats &amp; Dogs", view.title());
    }

    /** TRASHED is reachable only through the explicit status filter. */
    @Test
    void trashedIsNotAWritableStatus() {
        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.parseWritableStatus("TRASHED"));
    }

    @Test
    void anUnknownStatusIsRejected() {
        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.parseWritableStatus("BANANA"));
    }

    @Test
    void theFourWritableStatusesAreAccepted() {
        assertEquals(WeblogEntry.PubStatus.DRAFT, EntryDtos.parseWritableStatus("draft"));
        assertEquals(WeblogEntry.PubStatus.PUBLISHED, EntryDtos.parseWritableStatus("PUBLISHED"));
        assertEquals(WeblogEntry.PubStatus.PENDING, EntryDtos.parseWritableStatus("Pending"));
        assertEquals(WeblogEntry.PubStatus.SCHEDULED, EntryDtos.parseWritableStatus("SCHEDULED"));
    }

    /**
     * Filtering by TRASHED is how the trash list is read, so the filter
     * parser accepts what the write parser refuses. Two parsers, not one with
     * a boolean, so a write check cannot be relaxed into a filter check by
     * flipping an argument.
     */
    @Test
    void theFilterParserAcceptsTrashedWhereTheWriteParserDoesNot() {
        assertEquals(WeblogEntry.PubStatus.TRASHED, EntryDtos.parseFilterStatus("TRASHED"));
        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.parseWritableStatus("TRASHED"));
    }

    @Test
    void theFilterParserRejectsAnUnknownStatus() {
        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.parseFilterStatus("BANANA"));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=EntriesApiReadTest`
Expected: FAIL — `EntryDtos` does not exist.

- [ ] **Step 3: Write `EntryDtos`**

Record plus mappers. The two methods the test names:

```java
    public static EntryView toView(WeblogEntry entry, String permalink) { ... }

    /**
     * Parses a client-supplied status for a WRITE.
     *
     * <p>TRASHED is deliberately not writable: trashing and restoring go
     * through their own endpoints, which are also the paths that keep the
     * Lucene index consistent and bump weblog.lastModified. Letting a PATCH
     * set TRASHED would skip both and leave a trashed entry findable by site
     * search, linking to a permalink that 404s.
     */
    public static WeblogEntry.PubStatus parseWritableStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("status is required.");
        }
        WeblogEntry.PubStatus status;
        try {
            status = WeblogEntry.PubStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown status '" + raw + "'.");
        }
        if (status == WeblogEntry.PubStatus.TRASHED) {
            throw ApiException.badRequest(
                    "Use DELETE to trash an entry and POST .../restore to bring it back.");
        }
        return status;
    }
```

- [ ] **Step 4: Write `EntriesApi` list and get**

```java
@RestController
@RequestMapping("/v1/weblogs/{handle}/entries")
public class EntriesApi extends BaseApiController {

    @GetMapping("")
    public EntryDtos.EntryPage list(
            HttpServletRequest request,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "tags", required = false) List<String> tags,
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "locale", required = false) String locale,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "50") int limit) throws WebloggerException {

        Weblog weblog = requireActionWeblog(request);
        WeblogEntrySearchCriteria criteria = new WeblogEntrySearchCriteria();
        criteria.setWeblog(weblog);
        criteria.setCatName(category);
        criteria.setTags(tags);
        criteria.setText(text);
        criteria.setLocale(locale);
        criteria.setOffset(offset);
        // One extra row decides hasMore without a second count query.
        criteria.setMaxResults(Math.min(limit, 200) + 1);
        if (status != null && !status.isBlank()) {
            criteria.setStatus(parseFilterStatus(status));
            // The only way to see the trash, and only when asked for by name.
            criteria.setIncludeTrashed(criteria.getStatus() == WeblogEntry.PubStatus.TRASHED);
        }
        ...
    }
}
```

Create `BaseApiController` in `ui.restapi.v1` with:

```java
    @Autowired @Lazy
    protected Weblogger weblogger;

    /**
     * The weblog this request acts on. RollerHandlerInterceptor has already
     * resolved and permission-checked it from the {handle} path variable.
     */
    protected Weblog requireActionWeblog(HttpServletRequest request) {
        Object weblog = request.getAttribute("actionWeblog");
        if (weblog instanceof Weblog w) {
            return w;
        }
        throw ApiException.notFound("No such weblog.");
    }
```

`EntriesApi` implements `UISecurityEnforced`, returning `true` for authenticated-user and valid-weblog, and `List.of(WeblogPermission.EDIT_DRAFT)` for the weblog permission actions — so the existing interceptor does the real authorization.

`GET /{id}` uses `WeblogOwnership.entry(weblogger, id, weblog)` and throws `ApiException.notFound` on null.

- [ ] **Step 5: Run the test**

Run: `mvn -pl app test -Dtest=EntriesApiReadTest`
Expected: PASS, 6 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/ \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesApiReadTest.java
git commit -m "api: entry list and get

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Entry write endpoints

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesWriteApi.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/EntryDtos.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesApiWriteTest.java`

**Interfaces:**
- Consumes: `BaseApiController.requireActionWeblog`, `BaseApiController.requireEntry`, `EntryFieldRules.escapeTitle`, `EntryFieldRules.parsePubTime`, `EntryDtos.parseWritableStatus`, `WeblogOwnership.entry`.
- Produces: `EntryDtos.EntryWrite` — a record whose every field is nullable so PATCH can distinguish "absent" from "cleared"; `EntriesWriteApi` at `/v1/weblogs/{handle}/entries` carrying `POST ""` and `PATCH "/{id}"`.

**Why a separate controller from `EntriesApi`:** `UISecurityEnforced` declares
its required `WeblogPermission` per controller, not per method. Reads need only
`EDIT_DRAFT`; writes need `POST`. Putting both in one class would mean
declaring the weaker permission and hand-checking the stronger one inside each
write method — exactly the kind of per-method check the interceptor exists to
remove. Two controllers on the same path, each with one honest declaration.

- [ ] **Step 1: Write the failing test**

```java
package org.apache.roller.weblogger.ui.restapi.v1;

import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.ui.restapi.dto.EntryDtos;
import org.junit.jupiter.api.Test;
import java.util.TimeZone;
import static org.junit.jupiter.api.Assertions.*;

class EntriesApiWriteTest {

    private static Weblog weblogInZone(String zoneId) {
        Weblog weblog = new Weblog();
        weblog.setTimeZone(zoneId);
        return weblog;
    }

    /**
     * The title must arrive escaped exactly once. Themes emit $entry.title
     * bare, so an unescaped store is stored XSS and a double-escaped store
     * renders "&amp;amp;".
     */
    @Test
    void applyingAWriteEscapesTheTitleExactlyOnce() {
        WeblogEntry entry = new WeblogEntry();
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                "Cats & Dogs", null, null, null, null, null, null,
                null, null, null, null, null, null);

        EntryDtos.applyWrite(entry, write, weblogInZone("UTC"));

        assertEquals("Cats &amp; Dogs", entry.getTitle());
    }

    /** A field absent from a PATCH body must not clear the stored value. */
    @Test
    void anAbsentFieldIsLeftAlone() {
        WeblogEntry entry = new WeblogEntry();
        entry.setTitle("kept");
        entry.setText("also kept");
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                null, null, "new body", null, null, null, null,
                null, null, null, null, null, null);

        EntryDtos.applyWrite(entry, write, weblogInZone("UTC"));

        assertEquals("kept", entry.getTitle());
        assertEquals("new body", entry.getText());
    }

    /** pubTime is read in the weblog's zone, not the server's. */
    @Test
    void pubTimeIsParsedInTheWeblogsZone() {
        WeblogEntry utcEntry = new WeblogEntry();
        WeblogEntry tokyoEntry = new WeblogEntry();
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                null, null, null, null, null, "2026-03-01T09:30", null,
                null, null, null, null, null, null);

        EntryDtos.applyWrite(utcEntry, write, weblogInZone("UTC"));
        EntryDtos.applyWrite(tokyoEntry, write, weblogInZone("Asia/Tokyo"));

        assertNotEquals(utcEntry.getPubTime().getTime(), tokyoEntry.getPubTime().getTime());
    }

    @Test
    void aMistypedPubTimeIsRejectedRatherThanPublishingNow() {
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                null, null, null, null, null, "yesterday-ish", null,
                null, null, null, null, null, null);

        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC")));
    }
}
```

Confirm the `EntryWrite` component order against the record you write; the test's positional constructor must match it.

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=EntriesApiWriteTest`
Expected: FAIL — `EntryWrite` and `applyWrite` do not exist.

- [ ] **Step 3: Write `EntryWrite` and `applyWrite`**

```java
    /**
     * A create or partial update. Every component is nullable and null means
     * ABSENT, not "clear this" -- a PATCH that omits a field must leave the
     * stored value alone.
     */
    public record EntryWrite(
            String title, String summary, String text, String status,
            String category, String pubTime, List<String> tags,
            String metaTitle, String searchDescription, String canonicalUrl,
            Boolean noindex, String featuredImageId, String ogImageId) { }

    public static void applyWrite(WeblogEntry entry, EntryWrite write, Weblog weblog) {
        if (write.title() != null) {
            // The one place raw author input becomes escaped markup, shared
            // with the JSP editor so the two cannot drift.
            entry.setTitle(EntryFieldRules.escapeTitle(write.title()));
        }
        if (write.summary() != null) { entry.setSummary(write.summary()); }
        if (write.text() != null) { entry.setText(write.text()); }
        if (write.status() != null) { entry.setStatus(parseWritableStatus(write.status())); }
        if (write.pubTime() != null) {
            try {
                entry.setPubTime(EntryFieldRules.parsePubTime(
                        write.pubTime(), weblog.getTimeZoneInstance()));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest(
                        "pubTime must be a wall-clock time in the weblog's zone, "
                        + "for example 2026-03-01T09:30.");
            }
        }
        if (write.metaTitle() != null) { entry.setMetaTitle(write.metaTitle()); }
        if (write.searchDescription() != null) { entry.setSearchDescription(write.searchDescription()); }
        if (write.canonicalUrl() != null) { entry.setCanonicalUrl(write.canonicalUrl()); }
        if (write.noindex() != null) { entry.setNoindex(write.noindex()); }
        if (write.featuredImageId() != null) { entry.setFeaturedImageId(write.featuredImageId()); }
        if (write.ogImageId() != null) { entry.setOgImageId(write.ogImageId()); }
    }
```

Category and tags are applied in the controller, not here, because both need manager lookups: the category is resolved with `WeblogOwnership.category` (an unknown or foreign name is `ApiException.badRequest`), and tags go through the entry's existing tag-setting path.

- [ ] **Step 4: Add `POST` and `PATCH` to `EntriesApi`**

`POST ""` builds a `WeblogEntry`, sets `website`, `creatorUserName` from the authenticated user, applies the write, requires a category (defaulting to the weblog's first if absent), calls `weblogger.getWeblogEntryManager().saveWeblogEntry(entry)` then `weblogger.flush()`, and returns `201` with a `Location` header.

`PATCH "/{id}"` looks up through `WeblogOwnership.entry`, applies, saves, flushes, returns the view.

`EntriesWriteApi` extends `BaseApiController`, implements `UISecurityEnforced` declaring `List.of(WeblogPermission.POST)`, and is mapped at the same `/v1/weblogs/{handle}/entries` path as `EntriesApi` — Spring MVC resolves by method plus path, so `GET` reaches the read controller and `POST`/`PATCH` the write one with no ambiguity.

- [ ] **Step 5: Run the test**

Run: `mvn -pl app test -Dtest=EntriesApiWriteTest`
Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/ \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesApiWriteTest.java
git commit -m "api: entry create and patch

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Trash, restore, delete forever, and preview

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesWriteApi.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesApiTrashTest.java`

**Interfaces:**
- Consumes: `BaseController.trashEntryWithIndex`, `BaseController.deleteEntryForeverWithIndex`, `WeblogEntryManager.restoreWeblogEntry`, `EntryEditController`'s preview rendering.
- Produces: `DELETE /{id}`, `POST /{id}/restore`, `POST /{id}/delete-forever`, `POST /{id}/preview` and `POST /preview`.

- [ ] **Step 1: Write the failing integration-style test**

```java
package org.apache.roller.weblogger.ui.restapi.v1;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Trash is a fifth PubStatus, not a deleted_at column, so every status-naming
 * query excludes it by construction. These tests pin the two invariants the
 * API must not break: a restore never republishes, and a trashed entry leaves
 * the search index.
 */
class EntriesApiTrashTest {

    private User user;
    private Weblog weblog;
    private WeblogEntry entry;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("apitrashuser");
        weblog = TestUtils.setupWeblog("api-trash-blog", user);
        entry = TestUtils.setupWeblogEntry("api-trash-entry", weblog, user);
        entry.setStatus(WeblogEntry.PubStatus.PUBLISHED);
        WebloggerFactory.getWeblogger().getWeblogEntryManager().saveWeblogEntry(entry);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void trashingHidesTheEntryFromAnOrdinaryListing() throws Exception {
        WeblogEntryManager wem = WebloggerFactory.getWeblogger().getWeblogEntryManager();
        wem.trashWeblogEntry(wem.getWeblogEntry(entry.getId()));
        TestUtils.endSession(true);

        WeblogEntrySearchCriteria criteria = new WeblogEntrySearchCriteria();
        criteria.setWeblog(TestUtils.getManagedWeblog(weblog));
        assertTrue(wem.getWeblogEntries(criteria).isEmpty(),
                "includeTrashed defaults false -- a caller that thinks about nothing is safe");
    }

    /**
     * An undelete that silently republishes to feeds, the sitemap and every
     * subscriber is worse than one extra click. No column remembers the
     * pre-trash status precisely so this cannot regress.
     */
    @Test
    void restoreAlwaysLandsOnDraftEvenForAPreviouslyPublishedEntry() throws Exception {
        WeblogEntryManager wem = WebloggerFactory.getWeblogger().getWeblogEntryManager();
        wem.trashWeblogEntry(wem.getWeblogEntry(entry.getId()));
        TestUtils.endSession(true);

        wem.restoreWeblogEntry(wem.getWeblogEntry(entry.getId()));
        TestUtils.endSession(true);

        assertEquals(WeblogEntry.PubStatus.DRAFT,
                wem.getWeblogEntry(entry.getId()).getStatus());
    }
}
```

Check `TestUtils` for a managed-weblog helper; if there is none, re-fetch with `WeblogManager.getWeblogByHandle`.

- [ ] **Step 2: Run it — this one is expected to PASS**

Run: `mvn -pl app test -Dtest=EntriesApiTrashTest`
Expected: PASS. This is a characterisation test, deliberately not a red step: it pins manager behaviour that already exists and that the API must route *through* rather than reimplement. Its javadoc says so.

**If either assertion fails, stop and escalate.** A red result here does not mean "write code until it is green" — it means the trash invariants are not what this plan assumed, and every later task built on them is suspect.

- [ ] **Step 3: Add the four endpoints**

```java
    /**
     * Trash, not delete. Goes through the same seam the authoring UI uses --
     * trashEntryWithIndex -- because the index removal and the
     * weblog.lastModified bump are not optional now the row survives: a
     * TRASHED entry left in Lucene is findable by site search and links to a
     * permalink that 404s, and WeblogPageCache has no CacheHandler so
     * lastModified is the only thing that expires the cached home page.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> trash(HttpServletRequest request,
                                      @PathVariable("id") String id) throws WebloggerException {
        WeblogEntry entry = requireEntry(request, id);
        trashEntryWithIndex(entry);
        weblogger.flush();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    public EntryDtos.EntryView restore(HttpServletRequest request,
                                       @PathVariable("id") String id) throws WebloggerException { ... }

    @PostMapping("/{id}/delete-forever")
    public ResponseEntity<Void> deleteForever(HttpServletRequest request,
                                              @PathVariable("id") String id) throws WebloggerException { ... }
```

`trashEntryWithIndex` and `deleteEntryForeverWithIndex` live on `BaseController`. Either have `BaseApiController` extend `BaseController`, or — cleaner, and preferred — move those two methods' bodies to a small `EntryDeletion` helper the way Tasks 6 and 7 moved theirs, with `BaseController` delegating. Do the extraction if `BaseController` carries JSP-only state that an API controller should not inherit; check with `grep -n "protected\|private" app/src/main/java/org/apache/roller/weblogger/ui/controllers/BaseController.java | head -40`.

Preview reuses `EntryEditController`'s scratch-entry approach: build an unsaved `WeblogEntry` owned by the action weblog, set the submitted text, call `entry.render()`, return `{"html": "..."}`.

- [ ] **Step 4: Run the test and the trash suite**

Run: `mvn -pl app test -Dtest=EntriesApiTrashTest+TrashPurgeTaskTest`

(Find the real trash test names with `ls app/src/test/java/org/apache/roller/weblogger/business/ | grep -i trash`.)

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/ \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesApiTrashTest.java
git commit -m "api: trash, restore, delete-forever and preview

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: Categories and tags

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/CategoriesApi.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/CategoryDtos.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/CategoriesApiTest.java`

**Interfaces:**
- Consumes: `WeblogOwnership.category`, `WeblogEntryManager.getWeblogCategories/saveWeblogCategory/removeWeblogCategory/moveWeblogCategoryContents`.
- Produces: `CategoryDtos.CategoryView(String id, String name, String description, int entryCount)`; `CategoriesApi` at `/v1/weblogs/{handle}/categories`; `GET /v1/weblogs/{handle}/tags`.

- [ ] **Step 1: Write the failing test**

```java
package org.apache.roller.weblogger.ui.restapi.v1;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.*;
import org.apache.roller.weblogger.ui.controllers.WeblogOwnership;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class CategoriesApiTest {

    private User user;
    private Weblog mine;
    private Weblog theirs;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("apicatuser");
        mine = TestUtils.setupWeblog("api-cat-mine", user);
        theirs = TestUtils.setupWeblog("api-cat-theirs", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(mine.getId());
        TestUtils.teardownWeblog(theirs.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    /**
     * The move target is client input and getWeblogCategory is a global by-id
     * lookup. Without an ownership check on BOTH ids, a delete-with-move
     * silently re-files this weblog's entries into another weblog.
     */
    @Test
    void aForeignMoveTargetIsRefused() throws Exception {
        var wem = WebloggerFactory.getWeblogger().getWeblogEntryManager();
        WeblogCategory foreign = wem.getWeblogCategories(
                WebloggerFactory.getWeblogger().getWeblogManager()
                        .getWeblogByHandle(theirs.getHandle())).get(0);

        assertNull(WeblogOwnership.category(WebloggerFactory.getWeblogger(),
                        foreign.getId(),
                        WebloggerFactory.getWeblogger().getWeblogManager()
                                .getWeblogByHandle(mine.getHandle())),
                "a category from another weblog must read as absent");
    }
}
```

- [ ] **Step 2: Run it — this one is expected to PASS**

Run: `mvn -pl app test -Dtest=CategoriesApiTest`
Expected: PASS. Characterisation again: it pins `WeblogOwnership` from Task 7, which already exists. The red step for this task is the controller test in Step 3.

- [ ] **Step 3: Write the DTO and controller**

`CategoriesApi` implements `UISecurityEnforced` with `WeblogPermission.POST`, and:
- `GET ""` → all categories as views.
- `POST ""` → `{name, description}`; a duplicate name is `ApiException.conflict`.
- `PATCH "/{id}"` → via `WeblogOwnership.category`.
- `DELETE "/{id}"` with optional `?moveTo=<categoryId>` → **both** ids go through `WeblogOwnership.category`; a null result on either is `ApiException.notFound`.

`GET /v1/weblogs/{handle}/tags` returns the weblog's tags with counts, from the existing tag-aggregate query used by the tag cloud.

- [ ] **Step 4: Run the test**

Run: `mvn -pl app test -Dtest=CategoriesApiTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/ \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/CategoriesApiTest.java
git commit -m "api: categories and tags

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Phase 3 — Media

### Task 12: Media read, patch, delete, directories

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/MediaApi.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/MediaDtos.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/MediaApiTest.java`

**Interfaces:**
- Consumes: `MediaFileManager`.
- Produces: `MediaDtos.MediaView(String id, String name, String altText, String contentType, long length, int width, int height, String directory, Double focalX, Double focalY, String url, String blurhash)`; `MediaDtos.MediaPatch(String altText, Double focalX, Double focalY, String directoryId, String name)`; `MediaApi` at `/v1/weblogs/{handle}/media`.

- [ ] **Step 1: Write the failing test**

```java
package org.apache.roller.weblogger.ui.restapi.v1;

import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.ui.restapi.dto.MediaDtos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MediaApiTest {

    /**
     * Blank counts as absent for altText at every consumer -- an author who
     * cleared the field did not thereby declare the image decorative. The
     * audit endpoint and the UI marker both use isNotBlank, so the DTO must
     * report the same thing rather than EL's notion of empty.
     */
    @Test
    void whitespaceOnlyAltTextIsReportedAsMissing() {
        MediaFile file = new MediaFile();
        file.setAltText("   ");
        assertTrue(MediaDtos.isAltTextMissing(file));

        file.setAltText("");
        assertTrue(MediaDtos.isAltTextMissing(file));

        file.setAltText(null);
        assertTrue(MediaDtos.isAltTextMissing(file));

        file.setAltText("A cat on a wall");
        assertFalse(MediaDtos.isAltTextMissing(file));
    }

    /** A patch that omits altText must not erase it. */
    @Test
    void anAbsentAltTextInAPatchLeavesTheStoredValue() {
        MediaFile file = new MediaFile();
        file.setAltText("kept");
        MediaDtos.applyPatch(file, new MediaDtos.MediaPatch(null, 0.5, 0.5, null, null));
        assertEquals("kept", file.getAltText());
    }

    /**
     * An explicitly empty altText is a real edit -- the author cleared the
     * field -- so it is stored, and the audit endpoint then reports it as
     * missing. That is the intended loop, not a contradiction.
     */
    @Test
    void anExplicitlyEmptyAltTextIsStored() {
        MediaFile file = new MediaFile();
        file.setAltText("was here");
        MediaDtos.applyPatch(file, new MediaDtos.MediaPatch("", null, null, null, null));
        assertEquals("", file.getAltText());
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=MediaApiTest`
Expected: FAIL — `MediaDtos` does not exist.

- [ ] **Step 3: Write `MediaDtos` and `MediaApi`**

`isAltTextMissing` uses `org.apache.commons.lang3.StringUtils.isBlank`. `applyPatch` treats null as absent and empty string as a real value, exactly as the test asserts.

`MediaApi` implements `UISecurityEnforced` with `WeblogPermission.POST` and provides `GET ""` (optional `?dir=`), `GET "/{id}"`, `PATCH "/{id}"`, `DELETE "/{id}"`, `GET "/directories"`, `POST "/directories"`.

- [ ] **Step 4: Run the test**

Run: `mvn -pl app test -Dtest=MediaApiTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/ \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/MediaApiTest.java
git commit -m "api: media read, patch, delete and directories

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 13: Multipart upload with per-file results

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/MediaApi.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/MediaDtos.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/MediaUploadTest.java`

**Interfaces:**
- Consumes: `MediaFileManager.createMediaFile(Weblog, MediaFile, RollerMessages)`.
- Produces: `MediaDtos.UploadResult(String fileName, String status, String detail, MediaView file)` with `status` one of `created`, `quota_exceeded`, `forbidden_extension`, `error`; `MediaDtos.UploadResponse(List<UploadResult> results, int created, int failed)`; `POST ""` consuming `multipart/form-data`, returning **207** when any file failed and **201** when all succeeded.

- [ ] **Step 1: Write the failing test**

```java
package org.apache.roller.weblogger.ui.restapi.v1;

import org.apache.roller.weblogger.ui.restapi.dto.MediaDtos;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A batch is not a transaction. createMediaFile reports quota and
 * forbidden-extension refusals through RollerMessages WITHOUT throwing, so
 * the absence of an exception proves nothing -- the error count has to be
 * snapshotted around each call. Before the bulk-upload work in W4, one bad
 * file suppressed the entire success list.
 */
class MediaUploadTest {

    @Test
    void aPartlyFailedBatchReportsBothHalves() {
        var results = List.of(
                new MediaDtos.UploadResult("good.jpg", "created", null, null),
                new MediaDtos.UploadResult("huge.jpg", "quota_exceeded",
                        "Adding 4.2 MB would exceed this weblog's limit.", null),
                new MediaDtos.UploadResult("evil.exe", "forbidden_extension",
                        "Files of this type may not be uploaded.", null));

        MediaDtos.UploadResponse response = MediaDtos.summarise(results);

        assertEquals(1, response.created());
        assertEquals(2, response.failed());
        assertEquals(3, response.results().size());
    }

    @Test
    void anAllSuccessBatchReportsNoFailures() {
        var results = List.of(
                new MediaDtos.UploadResult("a.jpg", "created", null, null),
                new MediaDtos.UploadResult("b.jpg", "created", null, null));

        MediaDtos.UploadResponse response = MediaDtos.summarise(results);

        assertEquals(2, response.created());
        assertEquals(0, response.failed());
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=MediaUploadTest`
Expected: FAIL — `UploadResult`, `UploadResponse` and `summarise` do not exist.

- [ ] **Step 3: Write the records, `summarise`, and the endpoint**

```java
    @PostMapping(value = "", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaDtos.UploadResponse> upload(
            HttpServletRequest request,
            @RequestParam(value = "file") MultipartFile[] files,
            @RequestParam(value = "directoryId", required = false) String directoryId)
            throws WebloggerException {

        Weblog weblog = requireActionWeblog(request);
        MediaFileManager mfm = weblogger.getMediaFileManager();
        RollerMessages messages = new RollerMessages();
        List<MediaDtos.UploadResult> results = new ArrayList<>();

        for (MultipartFile upload : files) {
            // Snapshot the error count around the call: createMediaFile
            // reports quota and forbidden-extension refusals through
            // RollerMessages without throwing, so "no exception" does not
            // mean "it landed".
            int before = messages.getErrorCount();
            MediaFile created = buildMediaFile(upload, weblog, directoryId);
            mfm.createMediaFile(weblog, created, messages);
            results.add(before == messages.getErrorCount()
                    ? new MediaDtos.UploadResult(upload.getOriginalFilename(), "created", null,
                            MediaDtos.toView(created))
                    : MediaDtos.refusal(upload.getOriginalFilename(), messages));
        }
        weblogger.flush();

        MediaDtos.UploadResponse response = MediaDtos.summarise(results);
        return ResponseEntity
                .status(response.failed() == 0 ? HttpStatus.CREATED : HttpStatus.MULTI_STATUS)
                .body(response);
    }
```

`MediaDtos.refusal` reads the newest message out of `RollerMessages` and maps its key onto `quota_exceeded` / `forbidden_extension` / `error`. Find the actual message keys first:

Run: `grep -rn "getErrorCount\|addError" app/src/main/java/org/apache/roller/weblogger/business/MediaFileManager.java app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAMediaFileManagerImpl.java`

- [ ] **Step 4: Run the test**

Run: `mvn -pl app test -Dtest=MediaUploadTest`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/ \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/MediaUploadTest.java
git commit -m "api: multipart media upload with per-file results

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Phase 4 — Audit, admin, pages

### Task 14: SEO and media audit endpoints

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/AuditApi.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/AuditDtos.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/AuditApiTest.java`

**Interfaces:**
- Consumes: `WeblogEntrySearchCriteria`, `MediaFileManager`, `MediaDtos.isAltTextMissing`.
- Produces:
  - `AuditDtos.SeoGap(String entryId, String anchor, String title, List<String> gaps)` where a gap is one of `missing_search_description`, `missing_meta_title`, `missing_featured_image`, `noindex`.
  - `AuditDtos.SeoAudit(int total, Map<String,Integer> counts, List<SeoGap> entries)`.
  - `AuditDtos.MediaGap(String mediaId, String name, String directory)`, `AuditDtos.MediaAudit(int missingAltText, List<MediaGap> items)`.
  - `static List<String> gapsFor(WeblogEntry entry)`.

- [ ] **Step 1: Write the failing test**

```java
package org.apache.roller.weblogger.ui.restapi.v1;

import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.ui.restapi.dto.AuditDtos;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AuditApiTest {

    @Test
    void anEntryWithNothingSetReportsEveryGap() {
        WeblogEntry entry = new WeblogEntry();
        List<String> gaps = AuditDtos.gapsFor(entry);

        assertTrue(gaps.contains("missing_search_description"));
        assertTrue(gaps.contains("missing_meta_title"));
        assertTrue(gaps.contains("missing_featured_image"));
        assertFalse(gaps.contains("noindex"), "noindex is off by default");
    }

    @Test
    void aFullyDescribedEntryReportsNoGaps() {
        WeblogEntry entry = new WeblogEntry();
        entry.setSearchDescription("A short description.");
        entry.setMetaTitle("A title");
        entry.setFeaturedImageId("some-media-id");

        assertTrue(AuditDtos.gapsFor(entry).isEmpty());
    }

    /**
     * Blank means missing, matching the renderer's isNotBlank rather than
     * EL's empty -- whitespace-only text would otherwise report as described
     * while every rendered page fell back to something else.
     */
    @Test
    void whitespaceOnlyValuesCountAsMissing() {
        WeblogEntry entry = new WeblogEntry();
        entry.setSearchDescription("   ");
        entry.setMetaTitle("\t");
        entry.setFeaturedImageId("some-media-id");

        List<String> gaps = AuditDtos.gapsFor(entry);
        assertTrue(gaps.contains("missing_search_description"));
        assertTrue(gaps.contains("missing_meta_title"));
    }

    @Test
    void noindexIsReportedAsItsOwnGap() {
        WeblogEntry entry = new WeblogEntry();
        entry.setSearchDescription("d");
        entry.setMetaTitle("t");
        entry.setFeaturedImageId("m");
        entry.setNoindex(Boolean.TRUE);

        assertEquals(List.of("noindex"), AuditDtos.gapsFor(entry));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=AuditApiTest`
Expected: FAIL — `AuditDtos` does not exist.

- [ ] **Step 3: Write `AuditDtos` and `AuditApi`**

`gapsFor` uses `StringUtils.isBlank` on each field and `Boolean.TRUE.equals(entry.getNoindex())` for the last.

`AuditApi` implements `UISecurityEnforced` with `WeblogPermission.EDIT_DRAFT` (auditing is a read) and provides:
- `GET /v1/weblogs/{handle}/audit/seo` — lists entries whose `gapsFor` is non-empty, over `PUBLISHED` entries by default with `?status=` to widen. Paginated with the same `offset`/`limit` convention as the entry list.
- `GET /v1/weblogs/{handle}/audit/media` — every media file where `MediaDtos.isAltTextMissing` is true.

- [ ] **Step 4: Run the test**

Run: `mvn -pl app test -Dtest=AuditApiTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/ \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/AuditApiTest.java
git commit -m "api: SEO and media audit endpoints

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 15: `MaintenanceService` and the admin action endpoints

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/business/MaintenanceService.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/MaintenanceController.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/AdminActionsApi.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/business/MaintenanceServiceTest.java`

**Interfaces:**
- Consumes: `CacheManager`, `IndexManager`, `MediaFileManager.regenerateRenditions(Weblog)`.
- Produces:
  - `MaintenanceService.flushCache(Weblog)`, `.rebuildIndex(Weblog)`, `.regenerateRenditions(Weblog)`, each `throws WebloggerException`.
  - `AdminActionsApi` at `/v1/admin/weblogs/{handle}/actions`.

- [ ] **Step 1: Read the three existing action methods**

Run: `grep -n -A20 "flushCache\|rebuildIndex\|regenerateRenditions" app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/MaintenanceController.java`

- [ ] **Step 2: Write the failing test**

```java
package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The three Maintenance actions move out of the controller so the API is
 * their second caller rather than their second implementation.
 */
class MaintenanceServiceTest {

    private User user;
    private Weblog weblog;
    private MaintenanceService service;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("maintuser");
        weblog = TestUtils.setupWeblog("maint-blog", user);
        TestUtils.endSession(true);
        service = new MaintenanceService(WebloggerFactory.getWeblogger());
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void flushCacheCompletesForARealWeblog() {
        assertDoesNotThrow(() -> service.flushCache(weblog));
    }

    @Test
    void rebuildIndexCompletesForARealWeblog() {
        assertDoesNotThrow(() -> service.rebuildIndex(weblog));
    }

    @Test
    void regenerateRenditionsCompletesForAWeblogWithNoMedia() {
        assertDoesNotThrow(() -> service.regenerateRenditions(weblog));
    }

    @Test
    void aNullWeblogIsRejectedRatherThanActedOnGlobally() {
        assertThrows(IllegalArgumentException.class, () -> service.flushCache(null));
        assertThrows(IllegalArgumentException.class, () -> service.rebuildIndex(null));
        assertThrows(IllegalArgumentException.class, () -> service.regenerateRenditions(null));
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=MaintenanceServiceTest`
Expected: FAIL — `MaintenanceService` does not exist.

- [ ] **Step 4: Write the service and rewire the controller**

Move each action body verbatim, adding the null guard. Rewrite `MaintenanceController`'s three handlers to call the service and keep their existing flash messages and view names untouched.

- [ ] **Step 5: Write `AdminActionsApi`**

Three `@PostMapping`s, each `@PathVariable("handle")`, resolving the weblog through the interceptor's `actionWeblog` attribute, requiring `GlobalPermission.ADMIN` via `UISecurityEnforced`, returning `202 Accepted` with `{"action": "...", "weblog": "..."}` — 202 because reindex and rendition regeneration are asynchronous.

- [ ] **Step 6: Run the service test and the controller's existing test**

Run: `mvn -pl app test -Dtest=MaintenanceServiceTest+MaintenanceControllerRequestBindingTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/business/MaintenanceService.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/MaintenanceController.java \
        app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/AdminActionsApi.java \
        app/src/test/java/org/apache/roller/weblogger/business/MaintenanceServiceTest.java
git commit -m "api: extract MaintenanceService and expose the three admin actions

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 16: Admin users, weblogs and runtime config

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/AdminApi.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/WeblogsApi.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/AdminDtos.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/AdminApiTest.java`

**Interfaces:**
- Consumes: `UserManager`, `WeblogManager`, `PropertiesManager`, `WebloggerRuntimeConfig`.
- Produces: `AdminDtos.UserView(String userName, String screenName, String emailAddress, boolean enabled, List<String> globalRoles)`; `AdminDtos.UserPatch(Boolean enabled, String screenName, String emailAddress)`; `AdminDtos.ConfigEntry(String name, String value, String type)`; `AdminApi` at `/v1/admin`; `WeblogsApi` at `/v1/weblogs`.

- [ ] **Step 1: Write the failing test**

```java
package org.apache.roller.weblogger.ui.restapi.v1;

import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.dto.AdminDtos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Only runtime-scoped properties are settable. The boot-scoped ones are boot
 * -scoped deliberately: promoting them would put "stop hashing passwords" and
 * "disable HTML sanitization" on an HTTP endpoint.
 */
class AdminApiTest {

    @Test
    void securityInvariantsAreNotSettable() {
        for (String name : new String[] {
                "weblogAdminsUntrusted", "passwds.encryption.enabled",
                "rememberme.enabled", "themes.reload.mode",
                "users.firstUserAdmin", "search.enabled" }) {
            assertThrows(ApiException.class,
                    () -> AdminDtos.requireRuntimeProperty(name),
                    name + " must not be settable through the API");
        }
    }

    @Test
    void aKnownRuntimePropertyIsAccepted() {
        assertDoesNotThrow(() -> AdminDtos.requireRuntimeProperty("groupblogging.enabled"));
        assertDoesNotThrow(() -> AdminDtos.requireRuntimeProperty("entry.trash.retention.days"));
    }

    @Test
    void anUnknownPropertyIsRejected() {
        assertThrows(ApiException.class,
                () -> AdminDtos.requireRuntimeProperty("no.such.property"));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=AdminApiTest`
Expected: FAIL — `AdminDtos` does not exist.

- [ ] **Step 3: Write `requireRuntimeProperty` and the controllers**

```java
    /**
     * Accepts a property name only if runtimeConfigDefs.xml declares it.
     *
     * <p>The declaration file IS the allowlist -- there is no second hardcoded
     * list to drift from it. A startup-scoped setting is absent from
     * runtimeConfigDefs.xml by definition, so it is rejected here for free.
     */
    public static void requireRuntimeProperty(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("A property name is required.");
        }
        boolean declared = WebloggerRuntimeConfig.getRuntimeConfigDefs()
                .getDisplayGroups().stream()
                .flatMap(group -> group.getPropertyDefs().stream())
                .anyMatch(def -> def.getName().equals(name));
        if (!declared) {
            throw ApiException.badRequest(
                    "'" + name + "' is not a runtime-settable property.");
        }
    }
```

Check the actual accessor names on `RuntimeConfigDefs` first:

Run: `grep -n "public " app/src/main/java/org/apache/roller/weblogger/config/runtime/RuntimeConfigDefs.java`

`AdminApi` requires `GlobalPermission.ADMIN` and provides `GET/POST /v1/admin/users`, `PATCH /v1/admin/users/{userName}`, `GET/PATCH /v1/admin/config`. `WeblogsApi` provides `GET /v1/weblogs`, `GET /v1/weblogs/{handle}`, `PATCH /v1/weblogs/{handle}`.

**`POST /v1/admin/users` must not accept a password.** Create the account disabled and return the set-password link through the existing `PasswordLinkMailer.sendLink` path, so no plaintext password ever crosses this API.

- [ ] **Step 4: Run the test**

Run: `mvn -pl app test -Dtest=AdminApiTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/ \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/AdminApiTest.java
git commit -m "api: admin users, weblogs and runtime config

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 17: Pages

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/PagesApi.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/PageDtos.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/PagesApiTest.java`

**Interfaces:**
- Consumes: `WeblogPageManager`, `ReservedSlugs`, `WeblogOwnership.page`.
- Produces: `PageDtos.PageView(String id, String slug, String title, String text, String status, boolean showInNav, Instant updateTime)`; `PageDtos.PageWrite(String slug, String title, String text, String status, Boolean showInNav)`; `PagesApi` at `/v1/weblogs/{handle}/pages`.

- [ ] **Step 1: Write the failing test**

```java
package org.apache.roller.weblogger.ui.restapi.v1;

import org.apache.roller.weblogger.pojos.WeblogPage;
import org.apache.roller.weblogger.ui.restapi.dto.PageDtos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PagesApiTest {

    /**
     * A PAGE title is stored RAW -- the opposite of an entry title, which is
     * stored escaped. Templates escape page titles at render, so escaping
     * here would double-encode every one of them.
     */
    @Test
    void thePageTitleIsStoredRaw() {
        WeblogPage page = new WeblogPage();
        PageDtos.applyWrite(page, new PageDtos.PageWrite(null, "Cats & Dogs", null, null, null));
        assertEquals("Cats & Dogs", page.getTitle());
    }

    /**
     * ReservedSlugs is the single source of truth shared by the save
     * validator and the request parser, so a slug that would collide can
     * never be stored in the first place.
     */
    @Test
    void aReservedSlugIsRefused() {
        for (String slug : new String[] {"entry", "category", "tags", "feed"}) {
            assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                    () -> PageDtos.requireUsableSlug(slug),
                    slug + " must be refused");
        }
    }

    @Test
    void anOrdinarySlugIsAccepted() {
        assertDoesNotThrow(() -> PageDtos.requireUsableSlug("about"));
    }

    @Test
    void aBlankSlugIsRefused() {
        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> PageDtos.requireUsableSlug("  "));
    }
}
```

Confirm the real reserved values first: `grep -rn "RESERVED\|Set.of\|List.of" app/src/main/java/org/apache/roller/weblogger/pojos/ReservedSlugs.java` (search the tree for the file if that path is wrong).

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=PagesApiTest`
Expected: FAIL — `PageDtos` does not exist.

- [ ] **Step 3: Write `PageDtos` and `PagesApi`**

`requireUsableSlug` delegates to `ReservedSlugs`; `applyWrite` copies the title through with **no** escaping, carrying a comment that says so and why.

`PagesApi` implements `UISecurityEnforced` with `WeblogPermission.POST` and provides `GET ""`, `POST ""`, `GET/PATCH/DELETE "/{id}"`.

- [ ] **Step 4: Run the test**

Run: `mvn -pl app test -Dtest=PagesApiTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/ \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/PagesApiTest.java
git commit -m "api: static pages

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Phase 5 — Integration tests and the CLI

### Task 18: Real-WAR integration tests

**Files:**
- Create: `it-selenium/src/test/java/org/apache/roller/it/ApiIT.java`
- Test: itself

**Interfaces:**
- Consumes: the packaged WAR started by the `it-selenium` module's antrun `app-start`.
- Produces: nothing consumed by later tasks.

These cannot be proved by MockMvc: it does not run a servlet container, so it cannot see prefix-mapping path resolution, the real filter chain, or true multipart handling.

**This task is acceptance testing, not TDD, and the distinction is deliberate.** Every behaviour here was driven out by a failing unit test in Tasks 1-17; these ITs confirm the assembled artifact still exhibits it. They cannot be written test-first against nothing, because the thing they test is the *packaging*. Do not treat a green run as evidence the unit tests were unnecessary, and do not weaken an IT to make it pass — a failure here means the WAR does not behave like the unit tests said it would, which is exactly the discovery this task exists to make.

- [ ] **Step 1: Read how an existing IT reaches the running app**

Run: `grep -n "baseUrl\|http://localhost" it-selenium/src/test/java/org/apache/roller/it/RollerIT.java | head -20`

- [ ] **Step 2: Write the IT**

```java
package org.apache.roller.it;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The API against the packaged WAR. No browser: these prove things MockMvc
 * structurally cannot, because MockMvc never runs a servlet container.
 */
class ApiIT extends RollerIT {

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The whole point of the prefix-mapping constraint: /api/v1/ping must
     * reach a controller mapped at /v1/ping. A controller written with the
     * full path 404s here and nowhere else.
     */
    @Test
    void thePrefixMappingResolvesToAController() throws Exception {
        HttpResponse<String> response = get("/api/v1/ping", null);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\""));
    }

    @Test
    void anUnauthenticatedRequestIsRefusedWithoutASessionCookie() throws Exception {
        HttpResponse<String> response = get("/api/v1/me", null);
        assertEquals(401, response.statusCode());
        assertTrue(response.headers().allValues("Set-Cookie").stream()
                        .noneMatch(c -> c.startsWith("JSESSIONID")),
                "the API chain is stateless -- it must not mint a session");
    }

    @Test
    void aBadBearerTokenIsRefused() throws Exception {
        assertEquals(401, get("/api/v1/me", "rlr_definitelynotreal").statusCode());
    }

    @Test
    void anErrorRespondsAsProblemJson() throws Exception {
        HttpResponse<String> response = get("/api/v1/me", null);
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                        .startsWith("application/problem+json"),
                "every API error carries the problem+json content type");
    }
}
```

If `RollerIT` has no `baseUrl()`, use whatever the existing ITs use to build a URL and match it.

- [ ] **Step 3: Run the IT profile**

Run: `mvn verify -Pit -Dit.test=ApiIT`
Expected: PASS, 4 tests.

If it fails with a 409 at `docker-maven-plugin:start`, a killed run left a container behind: `docker rm -f roller-it-postgres` and re-run.

- [ ] **Step 4: Commit**

```bash
git add it-selenium/src/test/java/org/apache/roller/it/ApiIT.java
git commit -m "api: real-WAR integration tests for mounting, auth and error shape

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 19: `bin/roller-api`

**Files:**
- Create: `bin/roller-api`
- Modify: `Dockerfile` (bake it into the app image beside `provision.sh`)
- Modify: `.github/workflows/release.yml` (attach it to the release)
- Test: `app/src/test/java/org/apache/roller/weblogger/boot/RollerApiCliTest.java`

**Interfaces:**
- Consumes: the HTTP API.
- Produces: the `roller-api` command.

- [ ] **Step 1: Write the failing shape test**

```java
package org.apache.roller.weblogger.boot;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The CLI is shell, so this pins the properties that would otherwise only
 * fail in someone's terminal: it must not store a password, and it must
 * refuse to run without the tools it shells out to.
 */
class RollerApiCliTest {

    private static String cli() throws Exception {
        Path path = Path.of("..", "bin", "roller-api");
        if (!Files.exists(path)) {
            path = Path.of("bin", "roller-api");
        }
        assertTrue(Files.exists(path), "bin/roller-api must exist");
        return Files.readString(path);
    }

    @Test
    void itFailsFastOnErrorsAndUnsetVariables() throws Exception {
        assertTrue(cli().contains("set -euo pipefail"),
                "a partial run against a live blog is worse than no run");
    }

    @Test
    void thePasswordIsReadSilentlyAndNeverWrittenToTheCredentialsFile() throws Exception {
        String script = cli();
        assertTrue(script.contains("read -rs"), "the password must not echo");
        assertFalse(script.contains("password=") && script.contains(">> \"$CRED_FILE\""),
                "only the token is ever persisted");
    }

    @Test
    void theCredentialsFileIsCreatedPrivate() throws Exception {
        assertTrue(cli().contains("chmod 600"),
                "a token in a world-readable file is a leaked credential");
    }

    @Test
    void itChecksForCurlAndJqBeforeDoingAnything() throws Exception {
        String script = cli();
        assertTrue(script.contains("command -v curl"));
        assertTrue(script.contains("command -v jq"));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=RollerApiCliTest`
Expected: FAIL — `bin/roller-api` does not exist.

- [ ] **Step 3: Write the CLI**

```bash
#!/usr/bin/env bash
# roller-api -- command-line client for Roller's automation API.
#
# Configuration precedence: flags, then ROLLER_API_URL / ROLLER_API_TOKEN,
# then ~/.roller/credentials. The environment layer is what makes this usable
# from CI without an interactive login.
set -euo pipefail

CRED_FILE="${ROLLER_CREDENTIALS:-$HOME/.roller/credentials}"

die() { printf '%s\n' "$*" >&2; exit 1; }

require_tools() {
    command -v curl >/dev/null 2>&1 || die "roller-api needs curl on PATH."
    command -v jq   >/dev/null 2>&1 || die "roller-api needs jq on PATH."
}

load_credentials() {
    if [ -z "${ROLLER_API_URL:-}" ] || [ -z "${ROLLER_API_TOKEN:-}" ]; then
        # shellcheck source=/dev/null
        [ -f "$CRED_FILE" ] && . "$CRED_FILE"
    fi
    : "${ROLLER_API_URL:?No API URL. Run 'roller-api auth login --url ...' first.}"
}

api() {
    local method="$1" path="$2"; shift 2
    curl -sS --fail-with-body \
        -X "$method" \
        -H "Authorization: Bearer ${ROLLER_API_TOKEN:-}" \
        -H "Content-Type: application/json" \
        "$@" \
        "${ROLLER_API_URL}/api/v1${path}"
}

cmd_auth_login() {
    local url="" user=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --url)  url="$2";  shift 2 ;;
            --user) user="$2"; shift 2 ;;
            *) die "Unknown option: $1" ;;
        esac
    done
    [ -n "$url" ] || die "--url is required."
    [ -n "$user" ] || { printf 'Username: '; read -r user; }

    # The password is read silently, sent once, and never stored. Only the
    # returned token is persisted -- the same shape as `gh auth login`.
    printf 'Password: '
    read -rs password
    printf '\n'

    local token
    token=$(curl -sS --fail-with-body \
        -u "${user}:${password}" \
        -H "Content-Type: application/json" \
        -X POST "${url}/api/v1/tokens" \
        -d "$(jq -n --arg label "roller-api cli" '{label: $label, role: "ADMIN"}')" \
        | jq -r '.token')
    unset password

    [ -n "$token" ] && [ "$token" != "null" ] || die "Login failed."

    mkdir -p "$(dirname "$CRED_FILE")"
    umask 077
    printf 'ROLLER_API_URL=%s\nROLLER_API_TOKEN=%s\n' "$url" "$token" > "$CRED_FILE"
    chmod 600 "$CRED_FILE"
    printf 'Saved credentials to %s\n' "$CRED_FILE"
}

usage() {
    cat <<'EOF'
roller-api -- Roller automation API client

  auth login --url URL [--user NAME]   mint and store a token
  auth status                          show who the stored token belongs to

  entries list    --weblog H [--status S] [--limit N]
  entries create  --weblog H --title T --text-file F [--publish]
  entries patch   --weblog H --id ID [--search-description D] [--meta-title T]
  entries preview --weblog H --text-file F

  media upload --weblog H [--dir D] FILE...
  media patch  --weblog H --id ID --alt-text TEXT

  audit seo   --weblog H
  audit media --weblog H

  admin reindex --weblog H
  admin flush-cache --weblog H
  admin regenerate-renditions --weblog H
EOF
}

main() {
    require_tools
    [ $# -gt 0 ] || { usage; exit 1; }
    local group="$1"; shift
    case "$group" in
        auth)
            local sub="${1:-}"; shift || true
            case "$sub" in
                login)  cmd_auth_login "$@" ;;
                status) load_credentials; api GET /me | jq . ;;
                *) usage; exit 1 ;;
            esac
            ;;
        entries|media|audit|admin) load_credentials; "cmd_${group}" "$@" ;;
        -h|--help|help) usage ;;
        *) usage; exit 1 ;;
    esac
}

main "$@"
```

Write `cmd_entries`, `cmd_media`, `cmd_audit` and `cmd_admin` in the same style, each parsing its own flags and calling `api`. Entry bodies come from `--text-file` and are assembled with `jq -n --arg`, never string concatenation.

- [ ] **Step 4: Make it executable and smoke it**

```bash
chmod +x bin/roller-api
bin/roller-api --help
```

Expected: the usage block, exit 0.

- [ ] **Step 5: Run the shape test**

Run: `mvn -pl app test -Dtest=RollerApiCliTest`
Expected: PASS, 4 tests.

- [ ] **Step 6: Ship it in the image and the release**

In `Dockerfile`, beside the existing `provision.sh` copy, add `COPY bin/roller-api /app/roller-api` and a `RUN chmod +x /app/roller-api`. In `release.yml`, add `bin/roller-api` to the files attached to the GitHub Release, alongside `deploy.sh` and `docker-compose.prod.yml`.

- [ ] **Step 7: Commit**

```bash
git add bin/roller-api Dockerfile .github/workflows/release.yml \
        app/src/test/java/org/apache/roller/weblogger/boot/RollerApiCliTest.java
git commit -m "api: bin/roller-api command-line client

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Phase 6 — Documentation

### Task 20: OpenAPI and the recipes page

**Files:**
- Modify: `app/pom.xml` (springdoc-openapi dependency)
- Create: `docs/api/README.md`
- Modify: `CLAUDE.md` (an "Automation API" section)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/OpenApiDocumentTest.java`

**Interfaces:**
- Consumes: every controller written so far.
- Produces: `/api/v1/openapi.json`.

- [ ] **Step 1: Write the failing docs test**

```java
package org.apache.roller.weblogger.ui.restapi;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The docs page is the API's front door. This pins the two things a reader
 * cannot recover for themselves: that /api/v1 is unstable while Roller is
 * 0.x, and that a token is minted by the CLI rather than in the UI.
 */
class OpenApiDocumentTest {

    private static String docs() throws Exception {
        Path path = Path.of("..", "docs", "api", "README.md");
        if (!Files.exists(path)) {
            path = Path.of("docs", "api", "README.md");
        }
        assertTrue(Files.exists(path), "docs/api/README.md must exist");
        return Files.readString(path);
    }

    @Test
    void theInstabilityOfV1IsStated() throws Exception {
        String text = docs().toLowerCase();
        assertTrue(text.contains("unstable"),
                "a client author must be told v1 can change while Roller is 0.x");
    }

    @Test
    void theBootstrapPathIsDocumented() throws Exception {
        assertTrue(docs().contains("roller-api auth login"),
                "there is no UI for minting a token -- the CLI is the only route");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=OpenApiDocumentTest`
Expected: FAIL — the docs file does not exist.

- [ ] **Step 3: Add springdoc**

In `app/pom.xml`, add the `springdoc-openapi-starter-webmvc-api` dependency at the version matching Spring Boot 4.1, and configure it in `application.properties` to serve at `/api/v1/openapi.json` with the UI disabled — an automation API needs a machine-readable document, not a browser explorer.

- [ ] **Step 4: Write `docs/api/README.md`**

Cover, with a working `curl` for each: the stability statement; `roller-api auth login`; token scopes and what each role may do; the four writable entry statuses and why `TRASHED` is not among them; the 207 upload contract; the audit endpoints; the problem+json error shape and the status-code table; the throttle and its 429.

- [ ] **Step 5: Add the CLAUDE.md section**

A new "Automation API" section recording: the prefix-mapping constraint; that the API and UI share one authorization path through `RollerHandlerInterceptor`; that `EntryFieldRules` and `WeblogOwnership` exist so the rules have one home; that a token is a ceiling and never a grant; and that `api`/`newsletter` are reserved path roots.

- [ ] **Step 6: Run the test, then the whole suite**

Run: `mvn -pl app test -Dtest=OpenApiDocumentTest`
Expected: PASS, 2 tests.

Run: `mvn clean install`
Expected: BUILD SUCCESS, coverage gates met.

Run: `mvn -pl app jacoco:report && bin/check-diff-coverage.sh master`
Expected: changed lines at or above the ~90% floor.

- [ ] **Step 7: Commit**

```bash
git add app/pom.xml docs/api/README.md CLAUDE.md \
        app/src/main/resources/application.properties \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/OpenApiDocumentTest.java
git commit -m "api: OpenAPI document and the recipes page

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Final verification

- [ ] `mvn clean install` — full unit suite plus coverage gates.
- [ ] `mvn verify -Pit` — browser ITs plus `ApiIT`. A stale container fails at `docker-maven-plugin:start` with a 409; `docker rm -f roller-it-postgres` and re-run.
- [ ] `bin/check-diff-coverage.sh master` — changed-line coverage.
- [ ] Manual: `./roller dev`, then `bin/roller-api auth login --url http://localhost:8083/roller`, then `bin/roller-api entries list --weblog testing`.
