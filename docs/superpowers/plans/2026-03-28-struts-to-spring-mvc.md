# Struts 2 to Spring MVC Migration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove Apache Struts 2 (and all OGNL attack surface) from the admin/authoring UI, replacing it with Spring MVC.

**Architecture:** Each Struts action class becomes a Spring `@Controller`. A custom `RollerViewResolver` replaces Apache Tiles (which is javax-only and incompatible with Jakarta EE). A single `HandlerInterceptor` replaces three Struts interceptors. Spring Security's built-in CSRF replaces the custom salt system.

**Tech Stack:** Spring MVC 6.2.17, JSP + JSTL, Spring Security 6.5.9, custom view resolver replacing Tiles

**Spec:** `docs/superpowers/specs/2026-03-28-struts-to-spring-mvc-design.md`

**Important deviation from spec:** The spec assumed Tiles would stay as-is. Spring 6 dropped Tiles support (`TilesConfigurer` removed) and Apache Tiles 3.0.8 uses `javax.servlet`. This plan replaces Tiles with a lightweight custom `ViewResolver` (~100 lines) that reads the same definition structure.

---

## File Structure

### New Files

```
app/src/main/java/org/apache/roller/weblogger/ui/controllers/
├── BaseController.java                    # Abstract base (replaces UIAction)
├── RollerHandlerInterceptor.java          # Auth + permissions + prepare (replaces 3 interceptors)
├── RollerViewResolver.java                # Tiles replacement — maps view names to layout+content
├── ViewDefinition.java                    # Simple record: layout path + attribute map
├── core/
│   ├── SetupController.java
│   ├── LoginController.java
│   ├── RegisterController.java
│   ├── ProfileController.java
│   ├── OAuthKeysController.java
│   ├── OAuthAuthorizeController.java
│   ├── CreateWeblogController.java
│   ├── MainMenuController.java
│   └── InstallController.java
├── admin/
│   ├── GlobalConfigController.java
│   ├── UserAdminController.java
│   ├── UserEditController.java
│   ├── GlobalCommentManagementController.java
│   ├── PingTargetsController.java
│   ├── PingTargetEditController.java
│   ├── CacheInfoController.java
│   ├── PlanetConfigController.java
│   ├── PlanetGroupSubsController.java
│   └── PlanetGroupsController.java
├── editor/
│   ├── EntryEditController.java
│   ├── EntryRemoveController.java
│   ├── EntriesController.java
│   ├── CommentsController.java
│   ├── CategoriesController.java
│   ├── CategoryEditController.java
│   ├── CategoryRemoveController.java
│   ├── BookmarksController.java
│   ├── BookmarkEditController.java
│   ├── BookmarksImportController.java
│   ├── FolderEditController.java
│   ├── WeblogConfigController.java
│   ├── WeblogRemoveController.java
│   ├── ThemeEditController.java
│   ├── StylesheetEditController.java
│   ├── TemplatesController.java
│   ├── TemplateEditController.java
│   ├── MembersController.java
│   ├── MembersInviteController.java
│   ├── MemberResignController.java
│   ├── PingsController.java
│   ├── MaintenanceController.java
│   ├── MediaFileAddController.java
│   ├── MediaFileEditController.java
│   ├── MediaFileViewController.java
│   ├── MediaFileImageDimController.java
│   ├── MediaFileImageChooserController.java
│   └── EntryAddWithMediaFileController.java
└── pagers/                                # Moved from ui/struts2/pagers/ (no code changes)
    ├── CommentsPager.java
    ├── EntriesPager.java
    └── MediaFilePager.java

app/src/main/webapp/WEB-INF/
├── spring-mvc.xml                         # New: Spring MVC servlet context
├── jsps/taglibs-spring.jsp                # New: replaces taglibs-struts2.jsp

app/src/test/java/org/apache/roller/weblogger/ui/controllers/
├── RollerViewResolverTest.java
└── RollerHandlerInterceptorTest.java
```

### Modified Files

```
app/pom.xml                                # Remove Struts deps, add spring-webmvc
app/src/main/webapp/WEB-INF/web.xml        # Struts filter → DispatcherServlet, remove salt filters
app/src/main/webapp/WEB-INF/security.xml   # Enable CSRF
app/src/main/webapp/WEB-INF/jsps/tiles/    # 7 layout JSPs: tiles:insertAttribute → jsp:include
app/src/main/webapp/WEB-INF/jsps/**/*.jsp  # 84 JSPs: Struts tags → Spring/JSTL tags
```

### Deleted Files

```
app/src/main/resources/struts.xml
app/src/main/resources/struts.properties
app/src/main/webapp/WEB-INF/tiles.xml
app/src/main/webapp/WEB-INF/jsps/taglibs-struts2.jsp
app/src/main/java/.../ui/struts2/           # Entire package (44 action classes + 9 utils)
app/src/main/java/.../planet/ui/            # 5 planet action classes + base
app/src/main/java/.../ui/core/filters/LoadSaltFilter.java
app/src/main/java/.../ui/core/filters/ValidateSaltFilter.java
```

---

## Task 0: Move Framework-Neutral Interfaces Out of Struts Package

The `UISecurityEnforced` and `UIActionPreparable` interfaces have no Struts dependency but live in `ui.struts2.util`. Move them to `ui.controllers` before deleting the Struts package.

**Files:**
- Move: `ui/struts2/util/UISecurityEnforced.java` → `ui/controllers/UISecurityEnforced.java`
- Move: `ui/struts2/util/UIActionPreparable.java` → `ui/controllers/UIActionPreparable.java`

- [ ] **Step 1: Copy interfaces to new package**

Copy both files to `app/src/main/java/org/apache/roller/weblogger/ui/controllers/`, changing only the `package` declaration to `org.apache.roller.weblogger.ui.controllers`.

- [ ] **Step 2: Update imports in existing Struts code**

So the old code still compiles during migration, add re-export wrappers in the old location that extend the new interfaces:

```java
// In ui/struts2/util/UISecurityEnforced.java — replace contents with:
package org.apache.roller.weblogger.ui.struts2.util;
/** @deprecated Use org.apache.roller.weblogger.ui.controllers.UISecurityEnforced */
public interface UISecurityEnforced
        extends org.apache.roller.weblogger.ui.controllers.UISecurityEnforced {}
```

```java
// In ui/struts2/util/UIActionPreparable.java — replace contents with:
package org.apache.roller.weblogger.ui.struts2.util;
/** @deprecated Use org.apache.roller.weblogger.ui.controllers.UIActionPreparable */
public interface UIActionPreparable
        extends org.apache.roller.weblogger.ui.controllers.UIActionPreparable {}
```

This lets old Struts actions and new Spring controllers both compile until the old code is deleted in Task 9.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/UISecurityEnforced.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/UIActionPreparable.java \
        app/src/main/java/org/apache/roller/weblogger/ui/struts2/util/UISecurityEnforced.java \
        app/src/main/java/org/apache/roller/weblogger/ui/struts2/util/UIActionPreparable.java
git commit -m "Move UISecurityEnforced and UIActionPreparable to controllers package"
```

---

## Task 1: POM Dependencies — Swap Struts for Spring MVC

**Files:**
- Modify: `app/pom.xml`

- [ ] **Step 1: Remove Struts dependencies from app/pom.xml**

Remove these four dependency blocks:

```xml
<!-- DELETE: struts2-core -->
<dependency>
    <groupId>org.apache.struts</groupId>
    <artifactId>struts2-core</artifactId>
    ...
</dependency>

<!-- DELETE: struts2-spring-plugin -->
<dependency>
    <groupId>org.apache.struts</groupId>
    <artifactId>struts2-spring-plugin</artifactId>
    ...
</dependency>

<!-- DELETE: struts2-convention-plugin -->
<dependency>
    <groupId>org.apache.struts</groupId>
    <artifactId>struts2-convention-plugin</artifactId>
    ...
</dependency>

<!-- DELETE: struts2-tiles-plugin -->
<dependency>
    <groupId>org.apache.struts</groupId>
    <artifactId>struts2-tiles-plugin</artifactId>
    ...
</dependency>
```

Also remove the `<struts.version>7.1.1</struts.version>` property.

- [ ] **Step 2: Add Spring MVC dependency**

Add in the dependencies section (near the existing Spring deps):

```xml
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-webmvc</artifactId>
    <version>${spring.version}</version>
</dependency>
```

- [ ] **Step 3: Verify dependency resolution**

Run: `mvn dependency:resolve -pl app 2>&1 | tail -5`
Expected: BUILD SUCCESS (will not compile yet — that's expected)

- [ ] **Step 4: Commit**

```bash
git add app/pom.xml
git commit -m "Remove Struts 2 dependencies, add Spring MVC"
```

---

## Task 2: View Resolver — Replace Tiles with Custom ViewResolver

Spring 6 removed built-in Tiles support and Apache Tiles 3.0.8 uses `javax.servlet`. This task creates a lightweight replacement that reads view definitions (layout + content JSP paths) and composes them via `RequestDispatcher.include()`.

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/ViewDefinition.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/RollerViewResolver.java`
- Create: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/RollerViewResolverTest.java`

- [ ] **Step 1: Create ViewDefinition record**

```java
package org.apache.roller.weblogger.ui.controllers;

import java.util.Map;

/**
 * Defines a view as a layout JSP path plus named attribute JSP paths.
 * Replaces an Apache Tiles definition.
 */
public record ViewDefinition(String layout, Map<String, String> attributes) {

    /**
     * Returns a new ViewDefinition with the given attribute overridden.
     */
    public ViewDefinition withAttribute(String name, String path) {
        var merged = new java.util.HashMap<>(attributes);
        merged.put(name, path);
        return new ViewDefinition(layout, Map.copyOf(merged));
    }
}
```

- [ ] **Step 2: Create RollerViewResolver**

This class maps view names (like `.EntryEdit`) to a `ViewDefinition`, sets attributes on the request, and forwards to the layout JSP.

```java
package org.apache.roller.weblogger.ui.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.view.InternalResourceView;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight replacement for Apache Tiles.
 * Maps view names to layout JSP + content JSP attribute paths,
 * then forwards to the layout JSP with attributes set on the request.
 */
public class RollerViewResolver implements ViewResolver, org.springframework.core.Ordered {

    private final Map<String, ViewDefinition> definitions = new HashMap<>();
    private int order = 0;

    public void setOrder(int order) {
        this.order = order;
    }

    @Override
    public int getOrder() {
        return order;
    }

    /**
     * Register a base layout definition.
     */
    public void addDefinition(String name, String layout, Map<String, String> attributes) {
        definitions.put(name, new ViewDefinition(layout, Map.copyOf(attributes)));
    }

    /**
     * Register a definition that extends a base, overriding specific attributes.
     */
    public void addDefinition(String name, String extendsName, Map<String, String> overrides) {
        ViewDefinition base = definitions.get(extendsName);
        if (base == null) {
            throw new IllegalArgumentException("Base definition not found: " + extendsName);
        }
        var merged = new HashMap<>(base.attributes());
        merged.putAll(overrides);
        definitions.put(name, new ViewDefinition(base.layout(), Map.copyOf(merged)));
    }

    @Override
    public View resolveViewName(String viewName, Locale locale) {
        ViewDefinition def = definitions.get(viewName);
        if (def == null) {
            return null; // Let the next ViewResolver handle it
        }
        return new RollerLayoutView(def);
    }

    /**
     * A View that sets tile attributes as request attributes, then forwards to the layout JSP.
     */
    private static class RollerLayoutView implements View {
        private final ViewDefinition definition;

        RollerLayoutView(ViewDefinition definition) {
            this.definition = definition;
        }

        @Override
        public String getContentType() {
            return "text/html";
        }

        @Override
        public void render(Map<String, ?> model, HttpServletRequest request,
                           HttpServletResponse response) throws Exception {
            // Make Spring model attributes available to JSPs
            if (model != null) {
                model.forEach((key, value) -> request.setAttribute(key, value));
            }
            // Set tile attributes as request attributes
            definition.attributes().forEach(request::setAttribute);
            // Forward to the layout JSP
            request.getRequestDispatcher(definition.layout()).forward(request, response);
        }
    }
}
```

- [ ] **Step 3: Write test for RollerViewResolver**

```java
package org.apache.roller.weblogger.ui.controllers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.View;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RollerViewResolverTest {

    private RollerViewResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new RollerViewResolver();
        resolver.addDefinition(".tiles-tabbedpage", "/WEB-INF/jsps/tiles/tiles-tabbedpage.jsp",
                Map.of(
                        "head", "/WEB-INF/jsps/tiles/head.jsp",
                        "content", "/WEB-INF/jsps/tiles/empty.jsp",
                        "sidebar", "/WEB-INF/jsps/tiles/empty.jsp",
                        "footer", "/WEB-INF/jsps/tiles/footer.jsp"
                ));
    }

    @Test
    void resolveBaseDefinition() {
        View view = resolver.resolveViewName(".tiles-tabbedpage", Locale.ENGLISH);
        assertNotNull(view);
        assertEquals("text/html", view.getContentType());
    }

    @Test
    void resolveExtendedDefinition() {
        resolver.addDefinition(".EntryEdit", ".tiles-tabbedpage",
                Map.of("content", "/WEB-INF/jsps/editor/EntryEdit.jsp",
                        "sidebar", "/WEB-INF/jsps/editor/EntrySidebar.jsp"));

        View view = resolver.resolveViewName(".EntryEdit", Locale.ENGLISH);
        assertNotNull(view);
    }

    @Test
    void resolveUnknownViewReturnsNull() {
        View view = resolver.resolveViewName("unknown", Locale.ENGLISH);
        assertNull(view);
    }

    @Test
    void extendingNonExistentBaseThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                resolver.addDefinition(".Bad", ".nonexistent", Map.of()));
    }
}
```

- [ ] **Step 4: Run test**

Run: `mvn test -Dtest=RollerViewResolverTest -pl app`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/ViewDefinition.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/RollerViewResolver.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/RollerViewResolverTest.java
git commit -m "Add RollerViewResolver as lightweight Tiles replacement"
```

---

## Task 3: Base Controller and Handler Interceptor

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/BaseController.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/RollerHandlerInterceptor.java`
- Create: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/RollerHandlerInterceptorTest.java`

- [ ] **Step 1: Create BaseController**

The abstract base class providing shared utilities. Preserves the `UISecurityEnforced` and `UIActionPreparable` interfaces so the interceptor can read permission requirements.

```java
package org.apache.roller.weblogger.ui.controllers;

import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.core.util.menu.Menu;
import org.apache.roller.weblogger.ui.core.util.menu.MenuHelper;
import org.apache.roller.weblogger.ui.struts2.util.UIActionPreparable;
import org.apache.roller.weblogger.ui.struts2.util.UISecurityEnforced;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.ui.Model;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Abstract base for all Roller MVC controllers.
 * Replaces UIAction from the Struts 2 era.
 */
public abstract class BaseController implements UISecurityEnforced, UIActionPreparable {

    public static final String DENIED = "access-denied";

    @Autowired
    protected MessageSource messageSource;

    // Populated by RollerHandlerInterceptor before controller method runs
    private static final String ATTR_USER = "authenticatedUser";
    private static final String ATTR_WEBLOG = "actionWeblog";

    protected User getAuthenticatedUser(HttpServletRequest request) {
        return (User) request.getAttribute(ATTR_USER);
    }

    protected Weblog getActionWeblog(HttpServletRequest request) {
        return (Weblog) request.getAttribute(ATTR_WEBLOG);
    }

    protected String getText(String key, HttpServletRequest request) {
        return messageSource.getMessage(key, null, key, request.getLocale());
    }

    protected String getText(String key, Object[] args, HttpServletRequest request) {
        return messageSource.getMessage(key, args, key, request.getLocale());
    }

    protected void addError(Model model, String key, HttpServletRequest request) {
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) model.getAttribute("errors");
        if (errors == null) {
            errors = new ArrayList<>();
            model.addAttribute("errors", errors);
        }
        errors.add(getText(key, request));
    }

    protected void addMessage(Model model, String key, HttpServletRequest request) {
        @SuppressWarnings("unchecked")
        List<String> messages = (List<String>) model.getAttribute("messages");
        if (messages == null) {
            messages = new ArrayList<>();
            model.addAttribute("messages", messages);
        }
        messages.add(getText(key, request));
    }

    protected String getProp(String key) {
        return WebloggerRuntimeConfig.getProperty(key);
    }

    protected boolean getBooleanProp(String key) {
        return WebloggerRuntimeConfig.getBooleanProperty(key);
    }

    protected void populateCommonModel(HttpServletRequest request, Model model) {
        model.addAttribute("authenticatedUser", getAuthenticatedUser(request));
        model.addAttribute("actionWeblog", getActionWeblog(request));
        model.addAttribute("pageTitle", getPageTitle());
        model.addAttribute("desiredMenu", getDesiredMenu());
        model.addAttribute("siteURL", WebloggerRuntimeConfig.getAbsoluteContextURL());
        model.addAttribute("absoluteSiteURL", WebloggerRuntimeConfig.getAbsoluteContextURL());
    }

    // Subclasses override these
    protected String getPageTitle() { return ""; }
    protected String getDesiredMenu() { return null; }
    protected String getActionName() { return null; }

    protected Menu getMenu(HttpServletRequest request) {
        return MenuHelper.getMenu(getDesiredMenu(), getActionName(),
                getAuthenticatedUser(request), getActionWeblog(request));
    }

    // UISecurityEnforced defaults — require login + weblog admin
    @Override
    public boolean isUserRequired() { return true; }

    @Override
    public boolean isWeblogRequired() { return true; }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.singletonList(WeblogPermission.ADMIN);
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return Collections.singletonList(GlobalPermission.LOGIN);
    }

    // UIActionPreparable default
    @Override
    public void myPrepare() {
        // no-op by default
    }
}
```

- [ ] **Step 2: Create RollerHandlerInterceptor**

```java
package org.apache.roller.weblogger.ui.controllers;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.struts2.util.UIActionPreparable;
import org.apache.roller.weblogger.ui.struts2.util.UISecurityEnforced;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.List;

/**
 * Replaces UIActionInterceptor + UISecurityInterceptor + UIActionPrepareInterceptor.
 *
 * 1. Resolves authenticated user from Spring Security context
 * 2. Resolves target weblog from "weblog" request parameter
 * 3. Checks permissions via UISecurityEnforced interface on the controller
 * 4. Calls myPrepare() on UIActionPreparable controllers
 */
public class RollerHandlerInterceptor implements HandlerInterceptor {

    private static final Log log = LogFactory.getLog(RollerHandlerInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        Object controller = handlerMethod.getBean();

        // --- Step 1: Resolve authenticated user ---
        User authenticatedUser = resolveUser();
        if (authenticatedUser != null) {
            request.setAttribute("authenticatedUser", authenticatedUser);
        }

        // --- Step 2: Resolve weblog ---
        String weblogHandle = request.getParameter("weblog");
        Weblog actionWeblog = null;
        if (weblogHandle != null && !weblogHandle.isEmpty()) {
            WeblogManager wmgr = WebloggerFactory.getWeblogger().getWeblogManager();
            actionWeblog = wmgr.getWeblogByHandle(weblogHandle);
            if (actionWeblog != null) {
                request.setAttribute("actionWeblog", actionWeblog);
            }
        }

        // --- Step 3: Security checks ---
        if (controller instanceof UISecurityEnforced secured) {
            if (secured.isUserRequired()) {
                if (authenticatedUser == null) {
                    response.sendRedirect(request.getContextPath() + "/roller-ui/login.rol");
                    return false;
                }

                // Check global permissions
                List<String> globalPerms = secured.requiredGlobalPermissionActions();
                if (globalPerms != null && !globalPerms.isEmpty()) {
                    UserManager umgr = WebloggerFactory.getWeblogger().getUserManager();
                    GlobalPermission perm = new GlobalPermission(globalPerms);
                    if (!umgr.checkPermission(perm, authenticatedUser)) {
                        response.sendRedirect(request.getContextPath()
                                + "/roller-ui/access-denied");
                        return false;
                    }
                }

                // Check weblog permissions
                if (secured.isWeblogRequired()) {
                    if (actionWeblog == null) {
                        log.warn("Weblog required but not found for handle: " + weblogHandle);
                        response.sendRedirect(request.getContextPath()
                                + "/roller-ui/access-denied");
                        return false;
                    }

                    List<String> weblogPerms = secured.requiredWeblogPermissionActions();
                    if (weblogPerms != null && !weblogPerms.isEmpty()) {
                        UserManager umgr = WebloggerFactory.getWeblogger().getUserManager();
                        WeblogPermission perm = new WeblogPermission(actionWeblog, weblogPerms);
                        if (!umgr.checkPermission(perm, authenticatedUser)) {
                            response.sendRedirect(request.getContextPath()
                                    + "/roller-ui/access-denied");
                            return false;
                        }
                    }
                }
            }
        }

        // --- Step 4: Call myPrepare() ---
        if (controller instanceof UIActionPreparable preparable) {
            preparable.myPrepare();
        }

        return true;
    }

    private User resolveUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof UserDetails userDetails) {
            try {
                UserManager umgr = WebloggerFactory.getWeblogger().getUserManager();
                return umgr.getUserByUserName(userDetails.getUsername());
            } catch (Exception e) {
                log.error("Error looking up user", e);
                return null;
            }
        }
        return null;
    }
}
```

- [ ] **Step 3: Write interceptor test**

```java
package org.apache.roller.weblogger.ui.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.*;

class RollerHandlerInterceptorTest {

    @Test
    void nonHandlerMethodPassesThrough() throws Exception {
        var interceptor = new RollerHandlerInterceptor();
        // Non-HandlerMethod handler should pass through
        boolean result = interceptor.preHandle(null, null, new Object());
        assertTrue(result);
    }
}
```

- [ ] **Step 4: Run test**

Run: `mvn test -Dtest=RollerHandlerInterceptorTest -pl app`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/BaseController.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/RollerHandlerInterceptor.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/RollerHandlerInterceptorTest.java
git commit -m "Add BaseController and RollerHandlerInterceptor"
```

---

## Task 4: Spring MVC Configuration

**Files:**
- Create: `app/src/main/webapp/WEB-INF/spring-mvc.xml`
- Modify: `app/src/main/webapp/WEB-INF/web.xml`
- Modify: `app/src/main/webapp/WEB-INF/security.xml`

- [ ] **Step 1: Create spring-mvc.xml**

This is the DispatcherServlet context. It registers the view resolver with all 64 Tiles definitions converted to `RollerViewResolver` calls, plus the interceptor and message source.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:context="http://www.springframework.org/schema/context"
       xmlns:mvc="http://www.springframework.org/schema/mvc"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="
           http://www.springframework.org/schema/beans
           http://www.springframework.org/schema/beans/spring-beans.xsd
           http://www.springframework.org/schema/context
           http://www.springframework.org/schema/context/spring-context.xsd
           http://www.springframework.org/schema/mvc
           http://www.springframework.org/schema/mvc/spring-mvc.xsd">

    <context:component-scan base-package="org.apache.roller.weblogger.ui.controllers"/>

    <mvc:annotation-driven/>

    <mvc:interceptors>
        <bean class="org.apache.roller.weblogger.ui.controllers.RollerHandlerInterceptor"/>
    </mvc:interceptors>

    <!-- Message source for i18n (uses existing ApplicationResources*.properties) -->
    <bean id="messageSource"
          class="org.springframework.context.support.ResourceBundleMessageSource">
        <property name="basename" value="ApplicationResources"/>
        <property name="defaultEncoding" value="UTF-8"/>
    </bean>

    <!-- Multipart file upload support -->
    <bean id="multipartResolver"
          class="org.springframework.web.multipart.support.StandardServletMultipartResolver"/>

    <!-- View resolver: lightweight Tiles replacement.
         Definitions are registered programmatically in RollerViewConfig. -->
    <bean id="rollerViewResolver"
          class="org.apache.roller.weblogger.ui.controllers.RollerViewResolver"
          init-method="init">
        <property name="order" value="1"/>
    </bean>

    <!-- Fallback for plain JSP views (redirect:, forward:, or direct paths) -->
    <bean class="org.springframework.web.servlet.view.InternalResourceViewResolver">
        <property name="prefix" value="/WEB-INF/jsps/"/>
        <property name="suffix" value=".jsp"/>
        <property name="order" value="2"/>
    </bean>

</beans>
```

Note: The `init-method="init"` on `RollerViewResolver` calls an `init()` method that registers all view definitions. Add this method to `RollerViewResolver`:

```java
/**
 * Registers all view definitions (equivalent of tiles.xml).
 * Called by Spring after bean construction.
 */
public void init() {
    // --- Base layouts ---
    addDefinition(".tiles-tabbedpage", "/WEB-INF/jsps/tiles/tiles-tabbedpage.jsp", Map.of(
            "banner", "/WEB-INF/jsps/tiles/bannerStatus.jsp",
            "userStatus", "/WEB-INF/jsps/tiles/userStatus.jsp",
            "head", "/WEB-INF/jsps/tiles/head.jsp",
            "styles", "/WEB-INF/jsps/tiles/empty.jsp",
            "messages", "/WEB-INF/jsps/tiles/messages.jsp",
            "content", "/WEB-INF/jsps/tiles/empty.jsp",
            "sidebar", "/WEB-INF/jsps/tiles/empty.jsp",
            "footer", "/WEB-INF/jsps/tiles/footer.jsp"
    ));

    // ... (all 7 base layouts and 57 page definitions from tiles.xml)
    // Each definition from tiles.xml becomes one addDefinition() call.
    // Extended definitions use the 3-arg form: addDefinition(name, extendsName, overrides)

    // Example page definitions:
    addDefinition(".EntryEdit", ".tiles-tabbedpage", Map.of(
            "content", "/WEB-INF/jsps/editor/EntryEdit.jsp",
            "sidebar", "/WEB-INF/jsps/editor/EntrySidebar.jsp"
    ));

    addDefinition(".Entries", ".tiles-tabbedpage", Map.of(
            "content", "/WEB-INF/jsps/editor/Entries.jsp",
            "sidebar", "/WEB-INF/jsps/editor/EntriesSidebar.jsp"
    ));

    // ... continue for all 64 definitions from tiles.xml
}
```

The full `init()` method must contain one `addDefinition()` call per `<definition>` element in the current `tiles.xml`. Translate each XML definition mechanically:
- `<definition name="X" template="T">` → `addDefinition("X", "T", Map.of(...))`
- `<definition name="X" extends="Y">` → `addDefinition("X", "Y", Map.of(...))`
- Each `<put-attribute name="N" value="V"/>` → `"N", "V"` in the Map

- [ ] **Step 2: Update web.xml**

Remove the Struts filter definition and mapping. Remove salt filter definitions and mappings. Remove StrutsTilesListener. Add ContextLoaderListener (for Spring Security context) and DispatcherServlet.

In web.xml, make these changes:

**Remove** the `struts2` filter definition and both filter-mappings (`*.rol` and `/struts/*`).

**Remove** the `LoadSaltFilter` and `ValidateSaltFilter` filter definitions and their filter-mappings.

**Remove** the `StrutsTilesListener` listener entry.

**Add** the ContextLoaderListener (needed now that struts2-spring-plugin is gone):
```xml
<listener>
    <listener-class>org.springframework.web.context.ContextLoaderListener</listener-class>
</listener>
```

**Add** the DispatcherServlet:
```xml
<servlet>
    <servlet-name>springMvc</servlet-name>
    <servlet-class>org.springframework.web.servlet.DispatcherServlet</servlet-class>
    <init-param>
        <param-name>contextConfigLocation</param-name>
        <param-value>/WEB-INF/spring-mvc.xml</param-value>
    </init-param>
    <load-on-startup>1</load-on-startup>
    <multipart-config>
        <max-file-size>1000000000</max-file-size>
        <max-request-size>1000000000</max-request-size>
    </multipart-config>
</servlet>
<servlet-mapping>
    <servlet-name>springMvc</servlet-name>
    <url-pattern>*.rol</url-pattern>
</servlet-mapping>
```

- [ ] **Step 3: Enable CSRF in security.xml**

In `security.xml`, remove the line:
```xml
<csrf disabled="true"/>
```

This enables Spring Security's built-in CSRF protection.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/webapp/WEB-INF/spring-mvc.xml \
        app/src/main/webapp/WEB-INF/web.xml \
        app/src/main/webapp/WEB-INF/security.xml
git commit -m "Add Spring MVC DispatcherServlet config, enable CSRF"
```

---

## Task 5: Taglib Header and Layout JSP Conversion

**Files:**
- Create: `app/src/main/webapp/WEB-INF/jsps/taglibs-spring.jsp`
- Modify: 7 layout JSPs in `app/src/main/webapp/WEB-INF/jsps/tiles/`

- [ ] **Step 1: Create taglibs-spring.jsp**

```jsp
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>
<%@ taglib prefix="spring" uri="http://www.springframework.org/tags" %>
<%@ taglib prefix="sec" uri="http://www.springframework.org/security/tags" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="tags" %>
```

- [ ] **Step 2: Convert layout JSPs**

In each of the 7 layout JSPs under `WEB-INF/jsps/tiles/`:

1. Replace `<%@ include file="/WEB-INF/jsps/taglibs-struts2.jsp" %>` with `<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>`
2. Replace every `<tiles:insertAttribute name="X"/>` with `<jsp:include page="${X}"/>`
3. Remove `<%@ taglib prefix="tilesx" uri="http://tiles.apache.org/tags-tiles-extras" %>` and any `<tilesx:useAttribute>` calls — replace with direct EL access to request attributes
4. Replace `<s:property value="..."/>` with `${...}` EL expressions
5. Replace `<s:text name="..."/>` with `<spring:message code="..."/>`
6. Replace `<s:url value="..."/>` with `<c:url value="..."/>`
7. Replace `<s:if test="...">` with `<c:if test="${...}">`

For example, in `tiles-tabbedpage.jsp`:
- `<tiles:insertAttribute name="head"/>` → `<jsp:include page="${head}"/>`
- `<tiles:insertAttribute name="banner"/>` → `<jsp:include page="${banner}"/>`
- `<tiles:insertAttribute name="content"/>` → `<jsp:include page="${content}"/>`
- `<s:property value="getProp('site.shortName')"/>` → `${siteProp_site_shortName}` (set by BaseController)
- `<s:property value="pageTitle"/>` → `${pageTitle}`
- `<s:text name="generic.poweredBy"/>` → `<spring:message code="generic.poweredBy"/>`
- `<s:if test="authenticatedUser != null">` → `<c:if test="${not empty authenticatedUser}">`
- `<s:url value="/roller-ui/images/feather.svg"/>` → `<c:url value="/roller-ui/images/feather.svg"/>`

Apply the same pattern to all 7 layout JSPs: `tiles-tabbedpage.jsp`, `tiles-simplepage.jsp`, `tiles-loginpage.jsp`, `tiles-installpage.jsp`, `tiles-errorpage.jsp`, `tiles-mainmenupage.jsp`, `tiles-popuppage.jsp`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/webapp/WEB-INF/jsps/taglibs-spring.jsp \
        app/src/main/webapp/WEB-INF/jsps/tiles/
git commit -m "Convert layout JSPs from Tiles/Struts tags to JSP includes and JSTL"
```

---

## Task 6: Convert Core Controllers (9 classes)

Convert the `/roller-ui` namespace actions: Setup, Login, Register, Profile, OAuthKeys, OAuthAuthorize, CreateWeblog, MainMenu, Install.

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/*.java` (9 files)
- Modify: corresponding 12 JSP files in `WEB-INF/jsps/core/`

- [ ] **Step 1: Convert each action class to a controller**

For each action class under `ui/struts2/core/`, create a corresponding controller under `ui/controllers/core/`. Follow this mechanical pattern for every class:

1. Read the Struts action class
2. Read its struts.xml mappings (action name, results, allowed-methods)
3. Create a `@Controller` class with `@RequestMapping("/roller-ui")`
4. For each allowed method, create a `@GetMapping` or `@PostMapping` with the URL pattern `/<actionName>.rol` or `/<actionName>!<method>.rol`
5. Move the business logic from the action method to the controller method
6. Replace `getText()` calls with `messageSource.getMessage()`
7. Replace `return SUCCESS` / `return INPUT` with `return ".TilesViewName"` (the actual Tiles definition name from struts.xml)
8. Replace `addError()`/`addMessage()` with `BaseController.addError(model, ...)`
9. Override `UISecurityEnforced` methods if the action overrides them

Example — `MainMenuController.java`:

```java
package org.apache.roller.weblogger.ui.controllers.core;

import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;

@Controller
@RequestMapping("/roller-ui")
public class MainMenuController extends BaseController {

    @Override
    public boolean isWeblogRequired() { return false; }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return Collections.singletonList(GlobalPermission.LOGIN);
    }

    @Override
    protected String getPageTitle() { return ""; }

    @GetMapping("/menu.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        // MainMenu logic: load user's weblogs, pending invitations, etc.
        // ... (copy business logic from MainMenu.execute())
        return ".MainMenu";
    }
}
```

Apply this pattern to all 9 core action classes. For actions with multiple struts.xml mappings (like `Install` which maps to `/roller-ui/install` namespace), use the appropriate `@RequestMapping` namespace.

- [ ] **Step 2: Convert core JSP files**

For each of the 12 JSP files under `WEB-INF/jsps/core/`, apply the tag conversion table from the spec:
- Replace `<%@ include file="/WEB-INF/jsps/taglibs-struts2.jsp" %>` → `<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>`
- `<s:text name="key"/>` → `<spring:message code="key"/>`
- `<s:property value="x"/>` → `${x}`
- `<s:form action="...">` → `<form:form action="..." method="post">` + `<sec:csrfInput/>`
- `<s:hidden name="salt"/>` → delete
- `<s:if test="...">` → `<c:if test="${...}">`
- `<s:iterator>` → `<c:forEach>`
- All other Struts tags per the conversion table in the spec

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl app 2>&1 | tail -5`
Expected: Compilation succeeds (or known errors from unconverted packages)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/ \
        app/src/main/webapp/WEB-INF/jsps/core/
git commit -m "Convert core Struts actions to Spring MVC controllers"
```

---

## Task 7: Convert Admin Controllers (10 classes)

Convert the `/roller-ui/admin` namespace actions: GlobalConfig, UserAdmin, UserEdit, GlobalCommentManagement, PingTargets, PingTargetEdit, CacheInfo, PlanetConfig, PlanetGroupSubs, PlanetGroups.

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/*.java` (10 files)
- Modify: 10 JSP files in `WEB-INF/jsps/admin/`

- [ ] **Step 1: Convert each admin action to a controller**

Same mechanical pattern as Task 6. Key differences for admin controllers:
- Namespace is `/roller-ui/admin`
- Most override `requiredGlobalPermissionActions()` to return `GlobalPermission.ADMIN`
- Most set `isWeblogRequired()` to `false`
- `GlobalConfig` and `PlanetConfig` implement `ParametersAware` — replace with `HttpServletRequest.getParameterMap()` in the controller method
- `GlobalCommentManagement` and `PlanetGroupSubs`/`PlanetGroups` implement `ServletRequestAware` — replace with `HttpServletRequest` method parameter

- [ ] **Step 2: Convert admin JSP files**

Same tag conversion pattern as Task 6 for the 10 admin JSPs.

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl app 2>&1 | tail -5`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/ \
        app/src/main/webapp/WEB-INF/jsps/admin/
git commit -m "Convert admin Struts actions to Spring MVC controllers"
```

---

## Task 8: Convert Editor Controllers (~27 classes)

Convert the `/roller-ui/authoring` namespace actions. This is the largest group.

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/*.java` (~27 files)
- Modify: ~36 JSP files in `WEB-INF/jsps/editor/`

- [ ] **Step 1: Convert entry/comment controllers**

Start with `EntryEdit`, `EntryRemove`, `Entries`, `Comments`, `EntryAddWithMediaFile`. These are the most complex controllers — `EntryEdit` has multiple methods (execute, save, saveDraft, publish).

For `EntryEdit`, the controller needs:
- `@GetMapping("/entryAdd.rol")` — new entry form
- `@GetMapping("/entryEdit.rol")` — edit existing entry
- `@PostMapping("/entryEdit!save.rol")` — save
- `@PostMapping("/entryEdit!publish.rol")` — publish

- [ ] **Step 2: Convert category/bookmark controllers**

`Categories`, `CategoryEdit`, `CategoryRemove`, `Bookmarks`, `BookmarkEdit`, `BookmarksImport`, `FolderEdit`.

- [ ] **Step 3: Convert media file controllers**

`MediaFileAdd`, `MediaFileEdit`, `MediaFileView`, `MediaFileImageDim`, `MediaFileImageChooser`. These handle file uploads — use `@RequestParam("uploadedFile") MultipartFile` instead of Struts file upload properties.

- [ ] **Step 4: Convert weblog/theme/template controllers**

`WeblogConfig`, `WeblogRemove`, `ThemeEdit`, `StylesheetEdit`, `Templates`, `TemplateEdit`.

- [ ] **Step 5: Convert member/ping/maintenance controllers**

`Members`, `MembersInvite`, `MemberResign`, `Pings`, `Maintenance`.

- [ ] **Step 6: Convert all editor JSP files**

Apply the tag conversion to all 36 editor JSPs. This is the largest JSP batch.

- [ ] **Step 7: Verify compilation**

Run: `mvn compile -pl app 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/ \
        app/src/main/webapp/WEB-INF/jsps/editor/
git commit -m "Convert editor Struts actions to Spring MVC controllers"
```

---

## Task 9: Move Pager Classes and Delete Struts Code

**Files:**
- Move: `ui/struts2/pagers/*.java` → `ui/controllers/pagers/*.java`
- Delete: `ui/struts2/` entire package
- Delete: `planet/ui/` action classes (already replaced in Task 7)
- Delete: `ui/core/filters/LoadSaltFilter.java`
- Delete: `ui/core/filters/ValidateSaltFilter.java`
- Delete: `struts.xml`, `struts.properties`
- Delete: `tiles.xml`, `taglibs-struts2.jsp`

- [ ] **Step 1: Move pager classes**

The pager classes have zero Struts dependencies. Move them to the new package and update their `package` declarations:

```bash
mkdir -p app/src/main/java/org/apache/roller/weblogger/ui/controllers/pagers
```

Copy each file, changing only the package declaration from `org.apache.roller.weblogger.ui.struts2.pagers` to `org.apache.roller.weblogger.ui.controllers.pagers`.

Update any imports of these pagers in the new controller classes.

- [ ] **Step 2: Delete old Struts code**

```bash
# Delete Struts action classes and utilities
rm -rf app/src/main/java/org/apache/roller/weblogger/ui/struts2/

# Delete planet UI action classes (replaced by controllers/admin/)
rm app/src/main/java/org/apache/roller/weblogger/planet/ui/PlanetConfig.java
rm app/src/main/java/org/apache/roller/weblogger/planet/ui/PlanetGroupSubs.java
rm app/src/main/java/org/apache/roller/weblogger/planet/ui/PlanetGroups.java
rm app/src/main/java/org/apache/roller/weblogger/planet/ui/PlanetUIAction.java

# Delete salt filters
rm app/src/main/java/org/apache/roller/weblogger/ui/core/filters/LoadSaltFilter.java
rm app/src/main/java/org/apache/roller/weblogger/ui/core/filters/ValidateSaltFilter.java

# Delete Struts config
rm app/src/main/resources/struts.xml
rm app/src/main/resources/struts.properties

# Delete Tiles config and old taglib include
rm app/src/main/webapp/WEB-INF/tiles.xml
rm app/src/main/webapp/WEB-INF/jsps/taglibs-struts2.jsp
```

- [ ] **Step 3: Verify clean compilation**

Run: `mvn clean compile -pl app 2>&1 | tail -5`
Expected: BUILD SUCCESS with zero references to Struts

- [ ] **Step 4: Verify no Struts references remain**

```bash
grep -r "org.apache.struts2\|com.opensymphony.xwork2\|import org.apache.struts" \
    app/src/main/java --include="*.java" | grep -v "controllers/pagers"
```
Expected: no output

```bash
grep -r "<s:" app/src/main/webapp/WEB-INF/jsps --include="*.jsp"
```
Expected: no output

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Delete all Struts code, config, salt filters, and Tiles XML"
```

---

## Task 10: Update Tests and Final Verification

**Files:**
- Modify: existing test files that reference Struts or salt
- Modify: `app/src/test/java/.../ui/core/filters/LoadSaltFilterTest.java` → delete
- Modify: `app/src/test/java/.../ui/core/filters/ValidateSaltFilterTest.java` → delete

- [ ] **Step 1: Delete salt filter tests**

```bash
rm app/src/test/java/org/apache/roller/weblogger/ui/core/filters/LoadSaltFilterTest.java
rm app/src/test/java/org/apache/roller/weblogger/ui/core/filters/ValidateSaltFilterTest.java
```

- [ ] **Step 2: Update remaining tests that reference Struts types**

Search for any test files that import Struts types:
```bash
grep -rl "org.apache.struts2\|com.opensymphony\|UIAction\|struts2" \
    app/src/test --include="*.java"
```

For each found file, update imports to reference the new controller classes. For example, tests that mock `UIAction` should mock `BaseController` instead.

- [ ] **Step 3: Run full test suite**

Run: `mvn clean test 2>&1 | tail -15`
Expected: All tests pass (158 tests, 0 failures)

- [ ] **Step 4: Verify no javax.servlet references leaked back**

```bash
grep -r "import javax\." app/src/main/java --include="*.java" | \
    grep -v "javax.naming\|javax.sql\|javax.swing\|javax.imageio\|javax.xml.parsers\|javax.crypto"
```
Expected: no output

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "Remove salt filter tests, update test imports, verify clean build"
```

---

## Task 11: Documentation and Cleanup

- [ ] **Step 1: Update CLAUDE.md**

Update the Architecture Overview section to reflect the new web framework:
- Replace "Web Framework: Apache Struts 2" with "Web Framework: Spring MVC"
- Remove references to struts.xml, struts.properties
- Update the security section to note Spring Security CSRF instead of custom salt

- [ ] **Step 2: Remove Struts references from README.md**

Update the Technology Stack section.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "Update documentation to reflect Spring MVC migration"
```
