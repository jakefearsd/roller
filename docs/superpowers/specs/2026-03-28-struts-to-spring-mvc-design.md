# Struts 2 to Spring MVC Migration — Design Spec

## Summary

Remove Apache Struts 2 (and all OGNL exposure) from the Roller admin/authoring UI by replacing it with Spring MVC. The public-facing blog rendering (Velocity templates, PageServlet, FeedServlet) is unaffected.

**Decisions:**
- View layer: Keep JSP + Apache Tiles (no view technology change)
- Migration strategy: Big-bang (no dual-framework coexistence)
- URL scheme: Keep existing `*.rol` URLs unchanged
- Controller mapping: 1:1 — each Struts action class becomes one Spring `@Controller`

**Scope:**
- 44 action classes → 44 controller classes
- 84 JSP files — Struts tags replaced with Spring/JSTL tags
- 59 action mappings in struts.xml → `@RequestMapping` annotations
- 64 Tiles definitions — unchanged (loaded by Spring's TilesConfigurer)
- 9 Struts utility/interceptor classes → 1 interceptor + 1 base controller
- 41 JSPs with custom CSRF salt → Spring Security built-in CSRF

---

## 1. Spring MVC Infrastructure

### New Components

**`WebMvcConfig.java`** — `@Configuration` class that registers:
- `TilesConfigurer` pointing at existing `/WEB-INF/tiles.xml`
- `TilesViewResolver` as the view resolver
- `RollerHandlerInterceptor` in the interceptor chain
- `MessageSource` pointing at existing `ApplicationResources` resource bundle
- `StandardServletMultipartResolver` for file uploads (Jakarta Servlet 6.0 native, no additional dependency)

**`RollerHandlerInterceptor.java`** — Single `HandlerInterceptor` replacing three Struts interceptors. Runs in `preHandle()`:
1. Resolves authenticated user from Spring Security's `SecurityContextHolder` (replaces `UIActionInterceptor`'s session lookup via `RollerSession`)
2. Resolves the weblog from the `weblog` request parameter and stores it as a request attribute (replaces `UIActionInterceptor`'s weblog setup)
3. Reads the controller's `UISecurityEnforced` interface to check required permissions (replaces `UISecurityInterceptor`)
4. Calls the controller's `myPrepare()` if it implements `UIActionPreparable` (replaces `UIActionPrepareInterceptor`)

Returns `false` (blocking the request) if authentication or permission checks fail, sending a redirect to the access-denied page.

**`BaseController.java`** — Abstract base class providing shared utilities that `UIAction` provides today:
- `getAuthenticatedUser()` — reads user from request attribute (set by interceptor)
- `getActionWeblog()` — reads weblog from request attribute (set by interceptor)
- `addError(Model, String key)` / `addSuccess(Model, String key)` — i18n message helpers
- `getProp(String key)` — runtime config property access
- `getPageTitle()` — for Tiles page header
- Implements `UISecurityEnforced` with safe defaults (require login, no specific permissions)
- Implements `UIActionPreparable` with no-op `myPrepare()`

### web.xml Changes

**Remove:**
- `struts2` filter definition and `*.rol` filter-mapping
- `LoadSaltFilter` filter definition and mapping
- `ValidateSaltFilter` filter definition and mapping

**Add:**
```xml
<servlet>
    <servlet-name>springMvc</servlet-name>
    <servlet-class>org.springframework.web.servlet.DispatcherServlet</servlet-class>
    <init-param>
        <param-name>contextConfigLocation</param-name>
        <param-value>/WEB-INF/spring-mvc.xml</param-value>
    </init-param>
    <load-on-startup>1</load-on-startup>
</servlet>
<servlet-mapping>
    <servlet-name>springMvc</servlet-name>
    <url-pattern>*.rol</url-pattern>
</servlet-mapping>
```

### New Config File: `/WEB-INF/spring-mvc.xml`

Spring MVC servlet context configuration:
- `<context:component-scan base-package="org.apache.roller.weblogger.ui.controllers"/>`
- `TilesConfigurer` bean loading `/WEB-INF/tiles.xml`
- `TilesViewResolver` bean
- `<mvc:interceptors>` registering `RollerHandlerInterceptor`
- `ResourceBundleMessageSource` bean with basename `ApplicationResources`
- Multipart resolver with max upload size matching current 1GB limit

### POM Changes

**Remove:**
- `org.apache.struts:struts2-core:7.1.1`
- `org.apache.struts:struts2-spring-plugin:7.1.1`
- `org.apache.struts:struts2-convention-plugin:7.1.1`
- `org.apache.struts:struts2-tiles-plugin:7.1.1`

**Add:**
- `org.springframework:spring-webmvc:${spring.version}` (6.2.17)
- `org.apache.tiles:tiles-jakarta-servlet` (standalone Tiles with Jakarta support)
- `org.apache.tiles:tiles-jsp`

---

## 2. Controller Conversion Pattern

Each of the 44 Struts action classes becomes a Spring `@Controller` in a new package structure:

| Current Package | New Package |
|---|---|
| `ui.struts2.editor.*` | `ui.controllers.editor.*` |
| `ui.struts2.admin.*` | `ui.controllers.admin.*` |
| `ui.struts2.core.*` | `ui.controllers.core.*` |
| `ui.struts2.ajax.*` | `ui.controllers.ajax.*` |
| `planet.ui.*` | `ui.controllers.planet.*` |

### Conversion Rules

**Class level:**
- `public class EntryEdit extends UIAction` → `@Controller @RequestMapping("/roller-ui/authoring") public class EntryEditController extends BaseController`
- Namespace comes from the struts.xml package: `/roller-ui/authoring`, `/roller-ui/admin`, `/roller-ui`, etc.

**Method level:**
- `public String execute()` returning a result name → `@GetMapping("/entryEdit.rol")` returning a Tiles view name string
- `public String save()` → `@PostMapping("/entryEdit!save.rol")` returning a Tiles view name
- The `!method` URL convention is preserved via explicit mappings: `@PostMapping("/entryEdit!saveDraft.rol")`, etc.
- Only methods listed in `<allowed-methods>` get a `@RequestMapping` — all others are simply not mapped (no route = no access)

**Data binding:**
- Action properties with getters/setters (e.g., `bean`, `weblog`) → `@ModelAttribute` parameters and `model.addAttribute()` calls
- `withServletRequest(request)` → method parameter `HttpServletRequest request`
- `withServletResponse(response)` → method parameter `HttpServletResponse response`
- `withParameters(params)` → `@RequestParam` or `request.getParameterMap()`

**Result mapping:**
- `return INPUT` / `return SUCCESS` → return the actual Tiles view name from the corresponding `<result>` in struts.xml (e.g., `".EntryEdit"`, `".EntryEditSuccess"`)
- `return "access-denied"` → throw `org.springframework.security.access.AccessDeniedException`
- `type="redirectAction"` results → `return "redirect:/roller-ui/..."`
- `type="chain"` results → `return "forward:/roller-ui/..."` or direct method call

**i18n:**
- `getText("key")` in action code → `messageSource.getMessage("key", null, locale)` (inject `MessageSource`)
- `addError(getText("key"))` → `model.addAttribute("errors", List.of(messageSource.getMessage(...)))`

**File uploads:**
- Struts `fileUpload` interceptor → Spring's `@RequestParam("uploadedFile") MultipartFile file`
- `uploadedFileContentType` / `uploadedFileFileName` → `file.getContentType()` / `file.getOriginalFilename()`

### Ajax Servlet Actions

The 4 ajax classes (`CommentDataServlet`, `ThemeDataServlet`, `UserDataServlet`, and the pager classes) extend `HttpServlet` directly rather than `UIAction`. These are **not Struts actions** — they're plain servlets registered in web.xml. They stay as-is with no changes needed.

---

## 3. JSP Tag Migration

### Taglib Header

Replace `taglibs-struts2.jsp` include with a new `taglibs-spring.jsp`:

```jsp
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>
<%@ taglib prefix="spring" uri="http://www.springframework.org/tags" %>
<%@ taglib prefix="sec" uri="http://www.springframework.org/security/tags" %>
<%@ taglib prefix="tiles" uri="http://tiles.apache.org/tags-tiles" %>
```

### Tag Conversion Table

| Struts Tag | Spring/JSTL Replacement |
|---|---|
| `<s:text name="key"/>` | `<spring:message code="key"/>` |
| `<s:text name="key"><s:param value="x"/></s:text>` | `<spring:message code="key" arguments="${x}"/>` |
| `<s:property value="bean.title"/>` | `${bean.title}` |
| `<s:property value="bean.title" escapeHtml="false"/>` | `<c:out value="${bean.title}" escapeXml="false"/>` (explicit opt-out of escaping, audited case by case) |
| `<s:if test="condition">` | `<c:if test="${condition}">` |
| `<s:if>...<s:elseif>...<s:else>` | `<c:choose><c:when>...<c:when>...<c:otherwise>` |
| `<s:hidden name="salt"/>` | Delete entirely — replaced by `<sec:csrfInput/>` in `<form:form>` |
| `<s:hidden name="x" value="%{y}"/>` | `<input type="hidden" name="x" value="${y}"/>` or `<form:hidden path="x"/>` |
| `<s:url value="/path" var="u"/>` | `<c:url value="/path" var="u"/>` |
| `<s:url action="foo" var="u"><s:param .../>` | `<c:url value="/roller-ui/.../foo.rol" var="u"><c:param .../>` |
| `<s:form action="entryEdit">` | `<form:form modelAttribute="bean" action="${pageContext.request.contextPath}/roller-ui/.../entryEdit!save.rol" method="post">` + `<sec:csrfInput/>` |
| `<s:textfield name="bean.x" label="%{getText('key')}"/>` | `<label><spring:message code="key"/></label><form:input path="x"/>` |
| `<s:textarea name="bean.x"/>` | `<form:textarea path="x"/>` |
| `<s:select name="bean.x" list="opts" listKey="id" listValue="name"/>` | `<form:select path="x" items="${opts}" itemValue="id" itemLabel="name"/>` |
| `<s:checkbox name="bean.x"/>` | `<form:checkbox path="x"/>` |
| `<s:checkboxlist name="bean.x" list="opts"/>` | `<form:checkboxes path="x" items="${opts}"/>` |
| `<s:radio name="bean.x" list="opts"/>` | `<form:radiobuttons path="x" items="${opts}"/>` |
| `<s:password name="bean.x"/>` | `<form:password path="x"/>` |
| `<s:file name="uploadedFile"/>` | `<input type="file" name="uploadedFile"/>` |
| `<s:submit value="%{getText('key')}"/>` | `<button type="submit"><spring:message code="key"/></button>` |
| `<s:iterator value="list" var="item">` | `<c:forEach items="${list}" var="item">` |
| `<s:set var="x" value="y"/>` | `<c:set var="x" value="${y}"/>` |
| `<s:date name="entry.pubTime" format="..."/>` | `<fmt:formatDate value="${entry.pubTime}" pattern="..."/>` |
| `<s:a href="...">` | `<a href="...">` |
| `<s:include value="..."/>` | `<jsp:include page="..."/>` |
| `<s:actionmessage/>` | Custom fragment: `<c:forEach items="${actionMessages}" var="msg">...` |

### OGNL → EL Expression Conversion

- `%{getText('key')}` → replaced by `<spring:message>` tag (not an EL expression)
- `%{bean.title}` → `${bean.title}`
- `%{weblog}` → `${weblog}`
- `%{actionWeblog.handle}` → `${actionWeblog.handle}`
- `%{#attr.item.name}` → `${item.name}`
- `%{getProp('site.name')}` → `${siteName}` (controller adds it to model)
- `%{authenticatedUser != null}` → `${not empty authenticatedUser}`

---

## 4. Security Replacement

### CSRF Protection

**Delete:**
- `LoadSaltFilter.java` and its web.xml filter definition/mapping
- `ValidateSaltFilter.java` and its web.xml filter definition/mapping
- All `<s:hidden name="salt"/>` occurrences in 41 JSP files
- The `salt` field, `getSalt()`, `setSalt()` in `UIAction`

**Enable:**
- In `security.xml`, remove `<csrf disabled="true"/>` (enables Spring Security's `CsrfFilter`)
- Each `<form:form>` or `<form>` that POSTs must include `<sec:csrfInput/>` (adds a hidden `_csrf` token field automatically)

### Permission Enforcement

**Layer 1 — URL-based (unchanged):**
`security.xml` intercept-url rules remain as-is. Spring Security enforces role requirements at the URL level before the controller is invoked.

**Layer 2 — Domain-specific (new interceptor):**
`RollerHandlerInterceptor` checks weblog-level permissions by reading the `UISecurityEnforced` interface from the controller:
- `requiredWeblogPermissionActions()` — list of weblog permission actions needed
- `requiredGlobalPermissionActions()` — list of global permission actions needed
- `isUserRequired()` — whether authentication is required

Controllers implement this interface (via `BaseController` defaults) and override as needed, exactly as the Struts actions do today via `UIAction`.

### Authentication Context

**Current:** `UIActionInterceptor` reads `RollerSession` from the HTTP session to get the authenticated user.

**New:** `RollerHandlerInterceptor` reads `SecurityContextHolder.getContext().getAuthentication()` to get the Spring Security principal, then looks up the Roller `User` object. This is more correct — it uses Spring Security as the source of truth rather than a parallel session attribute.

---

## 5. Files Created, Modified, and Deleted

### Created

| File | Purpose |
|---|---|
| `ui/controllers/core/*.java` (~12 files) | Core controllers (login, register, menu, profile, etc.) |
| `ui/controllers/admin/*.java` (~11 files) | Admin controllers (global config, user admin, etc.) |
| `ui/controllers/editor/*.java` (~40 files) | Editor controllers (entries, media, templates, etc.) — 1:1 with current action classes |
| `ui/controllers/planet/*.java` (~5 files) | Planet controllers |
| `ui/controllers/BaseController.java` | Abstract base controller |
| `ui/controllers/RollerHandlerInterceptor.java` | Combined interceptor |
| `WEB-INF/spring-mvc.xml` | Spring MVC servlet context config |
| `WEB-INF/jsps/taglibs-spring.jsp` | New taglib declarations |

### Modified

| File | Change |
|---|---|
| `pom.xml` | Remove Struts deps, add spring-webmvc + tiles |
| `web.xml` | Remove Struts filter, add DispatcherServlet, remove salt filters |
| `security.xml` | Remove `<csrf disabled="true"/>` |
| `tiles.xml` | No changes needed |
| 84 JSP files | Replace `<s:*>` tags with Spring/JSTL equivalents |
| `taglibs-struts2.jsp` → `taglibs-spring.jsp` | Replace taglib declarations |

### Deleted

| File | Reason |
|---|---|
| `struts.xml` | Replaced by `@RequestMapping` annotations |
| `struts.properties` | Replaced by `spring-mvc.xml` and defaults |
| `ui/struts2/editor/*.java` (40 files) | Replaced by `ui/controllers/editor/` |
| `ui/struts2/admin/*.java` (11 files) | Replaced by `ui/controllers/admin/` |
| `ui/struts2/core/*.java` (12 files) | Replaced by `ui/controllers/core/` |
| `ui/struts2/util/UIAction.java` | Replaced by `BaseController` |
| `ui/struts2/util/UIActionInterceptor.java` | Replaced by `RollerHandlerInterceptor` |
| `ui/struts2/util/UISecurityInterceptor.java` | Replaced by `RollerHandlerInterceptor` |
| `ui/struts2/util/UIActionPrepareInterceptor.java` | Replaced by `RollerHandlerInterceptor` |
| `ui/struts2/pagers/*.java` (4 files) | Move to `ui/controllers/pagers/` (no Struts dependency, just rename package) |
| `ui/core/filters/LoadSaltFilter.java` | Replaced by Spring Security CSRF |
| `ui/core/filters/ValidateSaltFilter.java` | Replaced by Spring Security CSRF |
| `planet/ui/*.java` (5 files) | Replaced by `ui/controllers/planet/` |

### Untouched

- All Velocity templates and blog rendering code (`ui/rendering/`)
- All business logic (`business/`, `pojos/`)
- All servlets in `webservices/` (OAuth, Atom, OpenSearch, TagData)
- All servlets in `ui/struts2/ajax/` (these are plain servlets, not Struts actions)
- All theme files
- Guice module configuration
- JPA/ORM layer
