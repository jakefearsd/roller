# Stage 1B — Spring Boot 4 Conversion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert Roller to Spring Boot 4.1.x as an executable WAR (`java -jar roller.war`) on embedded Tomcat 11 / Servlet 6.1, Spring Framework 7 + Spring Security 7, Java 25 — replacing web.xml/security.xml/spring-mvc.xml with Java config, the Jetty dev server with `spring-boot:run`, and the cargo IT harness with the executable WAR.

**Architecture:** Keep the existing root pom as parent and import `spring-boot-dependencies` as a BOM (project pins win). `RollerApplication` (`@SpringBootApplication` excluding Hibernate/DataSource auto-config, extending `SpringBootServletInitializer` for dual-mode). Startup ordering preserved by a `SmartLifecycle` (default phase 0 — starts BEFORE Boot's web-server lifecycle opens the connector): prepare → bootstrap → initialize → velocity. The 13 hand-written servlets and 8 filters become registration beans with the exact web.xml order; the DispatcherServlet is re-mapped to `*.rol`. `WebloggerConfig`/roller.properties stay untouched (config-system migration is NOT this plan) — Boot config covers only server/servlet concerns. The security XML's exact semantics (verbatim authorities, no `ROLE_` prefix, anonymous-by-default, custom remember-me pair, NO logout filter) are reproduced in two `SecurityFilterChain`s.

**Tech Stack:** Spring Boot 4.1.x BOM, Tomcat 11 embedded (+ Jasper, provided), EclipseLink 5.0 (kept explicit; `HibernateJpaAutoConfiguration` excluded — Roller builds its own EMF), log4j2 via starter with `spring-boot-starter-logging` excluded, JSTL 3.0 (kept explicit — Jasper ships none), Java 25.

## Global Constraints

- Specs: `docs/superpowers/specs/2026-08-01-modernization-roadmap-design.md` (Stage 1 step 2 + Risks 1–3/5). Quality gates unchanged: TDD, browser IT green per task where stated, JaCoCo floors never lowered, diff-coverage 90%.
- **Executable WAR is a hard constraint** — `war` packaging + `spring-boot-maven-plugin` repackage; jar packaging cannot serve JSPs. `<finalName>roller</finalName>` must survive (`app/target/roller.war` is expected by CI artifact upload and it-selenium).
- **BOM discipline:** import `spring-boot-dependencies` in `app/pom.xml` `<dependencyManagement>` AFTER the project's own pins/imports; the project's explicit `<version>` pins always win. Do NOT let Boot move: eclipselink 5.0.0, lucene 9.12.3, log4j2 2.25.4, slf4j 2.0.17, asm 9.9.1, postgresql 42.7.9, testcontainers 2.0.5, junit-bom 5.14.3 (Boot pins JUnit 6 — we defer that bump), mockito 5.23.0, all webjar versions (WebjarReferenceTest guards head.jsp↔pom sync).
- **Logging:** exclude `spring-boot-starter-logging` from every starter; add `spring-boot-starter-log4j2` but exclude its `log4j-jul` + `jul-to-slf4j` duplicates if they clash with existing pins; keep `jcl-over-slf4j` and keep excluding `spring-jcl` wherever it leaks in (commons-logging must keep routing through slf4j; two LogFactory impls on the classpath is the failure mode).
- **Startup ordering invariant** (unchanged from 1A): business beans are lazy; nothing may force them before `WebloggerStartup.prepare()`. The `SmartLifecycle` runs prepare+bootstrap before the connector opens.
- **`WebloggerConfig` is out of scope** — its static loader, roller.properties, `roller.custom.config` override chain, and all `database.*`/`mail.*`/dir keys stay exactly as-is. Only ONE default changes: `database.configurationType` `jndi`→`jdbc` (embedded Tomcat has no JNDI; documented in-task).
- Filter/servlet semantics are transcribed from web.xml — the ordering comments there are load-bearing (esp. "CharEncoding before anything parsing", "SpringFirewallExceptionFilter right above security"). The transcription tables in Task 3 are normative.
- Tests need Docker; FOREGROUND builds only in subagents (never background Maven), timeouts 600000. App suite `mvn -ntp -pl app test`; ITs `mvn -ntp verify -Pit`.
- Commits per task with this repo's trailers.

## File Structure (end state)

| File | Responsibility |
|---|---|
| `app/pom.xml` | Boot BOM import, starters (+exclusions), spring-boot-maven-plugin repackage, drop jakarta.servlet/jsp -api explicit deps (Boot/Tomcat 11 manage; JSTL pair stays) |
| `app/src/main/java/org/apache/roller/weblogger/boot/RollerApplication.java` | `@SpringBootApplication` main + `SpringBootServletInitializer` |
| `.../boot/RollerLifecycle.java` | SmartLifecycle: dirs → prepare → bootstrap → initialize → velocity → context attrs; guarded stop |
| `.../boot/ServletRegistrationConfig.java` | 13 servlet + 8 filter registrations, DispatcherServlet on `*.rol` |
| `.../boot/WebMvcConfig.java` | view resolvers, interceptor, messageSource, webjars handler (replaces spring-mvc.xml) |
| `.../boot/SecurityConfig.java` | two SecurityFilterChains + auth beans (replaces security.xml) |
| `.../boot/WebContainerConfig.java` | error pages, welcome files, jsp-config customizer, session props |
| `app/src/main/resources/application.properties` | server/servlet/multipart/actuator/security-filter props |
| `app/src/main/webapp/WEB-INF/web.xml`, `security.xml`, `spring-mvc.xml` | **deleted** |
| `app/src/test/resources/jetty.xml`, jetty plugin config | **deleted** (dev = spring-boot:run) |
| `app/src/test/resources/roller-jettyrun.properties` | renamed `roller-boot-dev.properties`, adjusted |
| `roller` (script) | dev = spring-boot:run |
| `it-selenium/pom.xml` | cargo + WAR-unpack replaced by exec `java -jar` + readiness gate |
| `it-selenium/src/test/resources/roller-it.properties` | IT app config (filtered) |
| `app/pom.xml` jspc | jetty-jspc 11 → `io.leonard.maven.plugins:jspc-maven-plugin` 5.x (Jasper 11) |
| root `pom.xml` | `<release>25</release>` |
| `.github/workflows/main.yml` | matrix `[ '25' ]`, artifact from 25 leg |
| `CLAUDE.md` | build/run sections updated |

---

### Task 1: Boot scaffold — BOM, starters, main class, actuator smoke

**Files:** `app/pom.xml`; create `app/src/main/java/org/apache/roller/weblogger/boot/RollerApplication.java`; create `app/src/main/resources/application.properties`.

**Interfaces produced:** `RollerApplication` (Tasks 2–5 hang config off its package); the executable WAR exists from here on.

- [ ] **Step 1: pom changes.**
  - `<dependencyManagement>`: keep existing imports (junit-bom, testcontainers-bom) FIRST, then add:
    ```xml
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>4.1.0</version>
        <type>pom</type>
        <scope>import</scope>
    </dependency>
    ```
    (Verify latest 4.1.x patch on Maven Central first; use it.)
  - Dependencies: add `spring-boot-starter-web` (exclude `spring-boot-starter-logging`, exclude `spring-boot-starter-tomcat`), `spring-boot-starter-tomcat` scope `provided`, `org.apache.tomcat.embed:tomcat-embed-jasper` scope `provided`, `spring-boot-starter-log4j2`, `spring-boot-starter-actuator` (exclude starter-logging), `spring-boot-starter-security` (exclude starter-logging) — the security starter replaces the four explicit spring-security artifacts ONLY in versioning; keep `spring-security-taglibs` explicit (Boot doesn't pull it). Remove now-redundant explicit deps: `jakarta.servlet:jakarta.servlet-api`, `jakarta.servlet.jsp:jakarta.servlet.jsp-api` (Boot/Tomcat 11 provide 6.1/4.0). KEEP both JSTL artifacts, jakarta.mail/angus pins, activation, xml.bind. Remove `spring.version`/`spring.security.version` properties and let the BOM govern spring-* (delete the per-artifact `<version>` tags on spring-web/context/webmvc/test/security-*); keep the `spring-jcl` exclusion on whatever now pulls spring-core.
  - Build: add
    ```xml
    <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <executions><execution><goals><goal>repackage</goal></goals></execution></executions>
    </plugin>
    ```
    Keep maven-war-plugin as-is (`attachClasses` stays; repackage rewrites only the primary WAR). web.xml still exists in this task — war plugin stays happy.
- [ ] **Step 2: main class.**
  ```java
  package org.apache.roller.weblogger.boot;

  import org.springframework.boot.SpringApplication;
  import org.springframework.boot.autoconfigure.SpringBootApplication;
  import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
  import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
  import org.springframework.boot.builder.SpringApplicationBuilder;
  import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

  /**
   * Roller manages its own EntityManagerFactory (EclipseLink, via
   * JPAPersistenceStrategy) and its own connections (DatabaseProvider), so
   * Boot's JPA and DataSource auto-configuration are excluded.
   */
  @SpringBootApplication(
          scanBasePackages = "org.apache.roller.weblogger",
          exclude = {HibernateJpaAutoConfiguration.class, DataSourceAutoConfiguration.class})
  public class RollerApplication extends SpringBootServletInitializer {

      public static void main(String[] args) {
          SpringApplication.run(RollerApplication.class, args);
      }

      @Override
      protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
          return builder.sources(RollerApplication.class);
      }
  }
  ```
  CRITICAL scan note: `scanBasePackages` covers controllers AND `WebloggerBeanConfig` (@Configuration @Lazy — replaces the spring-mvc.xml bean registration later). It also picks up `@Component`-annotated classes if any exist — grep first (`grep -rln "@Component\|@Service\|@Repository" app/src/main/java`) and report what the scan will now instantiate that XML didn't; anything problematic gets excluded via `@ComponentScan` excludeFilters in-task.
- [ ] **Step 3: application.properties** (initial):
  ```properties
  server.servlet.context-path=/roller
  server.port=8080
  spring.servlet.multipart.max-file-size=1000000000B
  spring.servlet.multipart.max-request-size=1000000000B
  server.servlet.session.timeout=30m
  server.servlet.session.cookie.http-only=true
  server.error.whitelabel.enabled=false
  management.endpoints.web.exposure.include=health
  spring.devtools.livereload.enabled=false
  ```
- [ ] **Step 4: prove the graph.** `mvn -ntp -pl app clean package -DskipTests` → BUILD SUCCESS, `app/target/roller.war` repackaged (check `unzip -l app/target/roller.war | head` shows `org/springframework/boot/loader`). Then full suite `mvn -ntp -pl app test` — all green (Boot deps must not disturb the unit world; the harness/tests never boot the web layer). If any transitive-version conflict breaks a test (slf4j/log4j duplicates are the likely one), fix with exclusions per the Global Constraints, not by unpinning.
- [ ] **Step 5: smoke `java -jar`.** Start `java -jar app/target/roller.war --server.port=18083` (background this ONE controlled process via `nohup ... & echo $!`, poll with curl up to 60s), assert `curl -sf http://localhost:18083/roller/actuator/health` returns `{"status":"UP"...` (business tier NOT yet bootstrapped — that's Task 2; the app context itself must come up cleanly with lazy business beans untouched). Kill the PID. If context refresh fails on some XML-era bean the scan now sees, fix per Step 2's note.
- [ ] **Step 6: commit** — `"Add Spring Boot 4 scaffold with executable WAR packaging"` (+ trailers).

---

### Task 2: Startup lifecycle — RollerLifecycle replaces RollerContext's listener role

**Files:** create `.../boot/RollerLifecycle.java`; modify `.../ui/core/RollerContext.java` (gut listener methods, keep statics); `web.xml` (remove the two listener entries; keep everything else this task).

**Interfaces produced:** `RollerLifecycle implements SmartLifecycle` (phase 0 → runs before Boot's web-server phase opens the connector; stop() runs after connector closes). `RollerContext.getServletContext()` and `getPasswordEncoder()` keep working (statics now set by the lifecycle).

- [ ] **Step 1: read `RollerContext.contextInitialized` (lines ~107–214) once**; then write `RollerLifecycle`:
  ```java
  package org.apache.roller.weblogger.boot;

  // imports trimmed for the plan; use real ones

  @Component
  public class RollerLifecycle implements SmartLifecycle {

      private static final Log log = LogFactory.getLog(RollerLifecycle.class);

      private final ApplicationContext applicationContext;
      private final ServletContext servletContext;
      private volatile boolean running;

      public RollerLifecycle(ApplicationContext applicationContext, ServletContext servletContext) {
          this.applicationContext = applicationContext;
          this.servletContext = servletContext;
      }

      @Override
      public void start() {
          try {
              RollerContext.hold(servletContext);   // new static setter, see Step 2

              // Directory resolution: getRealPath works when exploded (external
              // Tomcat / spring-boot:run); it is null under `java -jar`, where
              // themes.dir and uploads.dir MUST be configured (roller.properties
              // override chain). setThemesDir/setUploadsDir are placeholder-only
              // no-ops, so configured values always win.
              String realPath = servletContext.getRealPath("/");
              if (realPath != null) {
                  WebloggerConfig.setUploadsDir(realPath + File.separator + "resources");
                  WebloggerConfig.setThemesDir(realPath + File.separator + "themes");
              } else if (WebloggerConfig.getProperty("themes.dir") == null
                      || WebloggerConfig.getProperty("themes.dir").startsWith("${")) {
                  throw new IllegalStateException(
                      "Running from an archive: themes.dir must be configured "
                      + "(roller.properties / roller.custom.config)");
              }

              WebloggerStartup.prepare();
              WebloggerFactory.bootstrap(new SpringWebloggerProvider(applicationContext));
              WebloggerFactory.getWeblogger().initialize();
              WebloggerFactory.getWeblogger().release();

              RollerContext.initializeSecurityFeatures(servletContext);
              RollerContext.setupVelocity();

              running = true;
          } catch (Exception e) {
              throw new IllegalStateException("Roller startup failed", e);
          }
      }

      @Override
      public void stop() {
          if (WebloggerFactory.isBootstrapped()) {
              WebloggerFactory.getWeblogger().shutdown();
          }
          CacheManager.shutdown();
          running = false;
      }

      @Override
      public boolean isRunning() { return running; }
      // default phase 0: starts before WebServerStartStopLifecycle (phase MAX-1),
      // stops after it — requests never see a half-started Roller.
  }
  ```
  Adapt `initializeSecurityFeatures`/`setupVelocity` visibility on RollerContext (make static package-visible/public as needed) — Task 4 later replaces the security-bean-name spelunking inside `initializeSecurityFeatures`; this task just keeps it compiling and running (it looks up beans by namespace-internal names that still exist while security.xml lives).
- [ ] **Step 2: RollerContext surgery.** Remove `extends ContextLoaderListener implements ServletContextListener` and both `contextInitialized`/`contextDestroyed` (the lifecycle owns both); add `static void hold(ServletContext sc) { servletContext = sc; }`. Keep: statics, `initializeSecurityFeatures`, `createPasswordEncoder`, `getPasswordEncoder`, `setupVelocity` (make static if not), `flushAuthenticationUserCache`. Remove both `<listener>` entries from web.xml; register `RollerSession` in ServletRegistrationConfig-to-be — for THIS task add a temporary `@Bean ServletListenerRegistrationBean<RollerSession>` inside RollerApplication (moves in Task 3). Preserve the `rememberMeEnabled` servlet-context attribute (move that line from initializeSecurityFeatures if it lives there, into `start()` or keep in place — verify `Login.jsp:42` still sees it).
- [ ] **Step 3: verify.** Full suite green (`RollerContext` signature change may touch tests — fix test-side only). Then the Task 1 Step-5 smoke again, now asserting the business tier actually starts: launch `./roller db` first (dev PostgreSQL on 5433 + migrations), run `java -jar app/target/roller.war --server.port=18083` with `-Droller.custom.config=` pointing at a MINIMAL temp props file (`database.configurationType=jdbc`, dev JDBC coords, `installation.type=auto`, `mail.configurationType=properties`, `mail.hostname=localhost`, `themes.dir=<abs path to app/src/main/webapp/themes>`, `search.index.dir`/`mediafiles.storage.dir`/`uploads.dir` under /tmp scratch) — assert health UP AND log shows Roller initialized; `curl -sf http://localhost:18083/roller/roller-ui/login.rol` may still 404 (DispatcherServlet not yet mapped) — that's Task 3's exit, not this one's. Kill PID, `./roller stop`.
- [ ] **Step 4: commit** — `"Move Roller startup into a Boot SmartLifecycle"`.

---

### Task 3: Registrations — servlets, filters, MVC config; delete spring-mvc.xml

**Files:** create `.../boot/ServletRegistrationConfig.java`, `.../boot/WebMvcConfig.java`, `.../boot/WebContainerConfig.java`; delete `app/src/main/webapp/WEB-INF/spring-mvc.xml`; web.xml shrinks (servlets/filters/mappings/error-pages/welcome/jsp-config/session-config removed; file may be deleted entirely in Task 5 — here leave the residual contextConfigLocation-free shell OR delete now if security.xml is the only survivor: keep web.xml with ONLY the security contextConfigLocation loading until Task 4 — see Step 4).

**Normative transcription tables (from web.xml — reproduce exactly):**

Servlets (all in `org.apache.roller.weblogger.ui.rendering.servlets` unless noted; loadOnStartup in parens):
| Bean | Class | URL pattern |
|---|---|---|
| pageServlet (5) | PageServlet | `/roller-ui/rendering/page/*` |
| feedServlet (5) | FeedServlet | `/roller-ui/rendering/feed/*` |
| resourceServlet (5) | ResourceServlet | `/roller-ui/rendering/resources/*` |
| mediaResourceServlet (5) | MediaResourceServlet | `/roller-ui/rendering/media-resources/*` |
| searchServlet (5) | SearchServlet | `/roller-ui/rendering/search/*` |
| commentServlet (7) | CommentServlet | `/roller-ui/rendering/comment/*` |
| rsdServlet (7) | RSDServlet | `/roller-ui/rendering/rsd/*` |
| commentAuthenticatorServlet (7) | CommentAuthenticatorServlet | `/CommentAuthenticatorServlet` |
| previewServlet (9) | PreviewServlet | `/roller-ui/authoring/preview/*` |
| previewResourceServlet (9) | PreviewResourceServlet | `/roller-ui/authoring/previewresource/*` |
| commentDataServlet | ui.controllers.ajax.CommentDataServlet | `/roller-ui/authoring/commentdata/*` |
| userDataServlet | ui.controllers.ajax.UserDataServlet | `/roller-ui/authoring/userdata/*` |
| themeDataServlet | ui.controllers.ajax.ThemeDataServlet | `/roller-ui/authoring/themedata/*` |

Filters (order + dispatcher types are the contract):
| Order | Filter (ui.core.filters unless noted) | Pattern | Dispatchers |
|---|---|---|---|
| 10 | CharEncodingFilter | `/*` | REQUEST, FORWARD |
| 20 | IPBanFilter | `/roller-ui/rendering/comment/*` | **FORWARD only** |
| 30 | SpringFirewallExceptionFilter | `/*` | REQUEST, FORWARD |
| 40 | springSecurityFilterChain (Boot-managed — set `spring.security.filter.order=40` and `spring.security.filter.dispatcher-types=request,forward`) | `/*` | REQUEST, FORWARD |
| 50 | BootstrapFilter | `/*` | REQUEST |
| 60 | PersistenceSessionFilter | `/*` | REQUEST |
| 70 | InitFilter | `/*` | REQUEST |
| 80 | ui.rendering.filters.RequestMappingFilter | `/*` | REQUEST |

(DebugFilter had no mapping — do not register it. The "security right after firewall filter" ordering comment is load-bearing: SpringFirewallExceptionFilter converts `RequestRejectedException` to 404.)

- [ ] **Step 1: ServletRegistrationConfig.** One `@Bean ServletRegistrationBean<...>` per row (constructor takes `new XServlet()` + pattern; `setLoadOnStartup(n)`), one `@Bean FilterRegistrationBean<...>` per filter row (`setOrder`, `setUrlPatterns`, `setDispatcherTypes(EnumSet...)`). The DispatcherServlet mapping:
  ```java
  @Bean
  public DispatcherServletRegistrationBean dispatcherServletRegistration(
          DispatcherServlet dispatcherServlet,
          ObjectProvider<MultipartConfigElement> multipartConfig) {
      DispatcherServletRegistrationBean registration =
              new DispatcherServletRegistrationBean(dispatcherServlet, "*.rol");
      registration.setName("springMvc");
      registration.setLoadOnStartup(1);
      multipartConfig.ifAvailable(registration::setMultipartConfig);
      return registration;
  }
  ```
  Move the RollerSession listener registration here from Task 2's temp spot.
- [ ] **Step 2: WebMvcConfig** — transcribe spring-mvc.xml: `RollerHandlerInterceptor` via `WebMvcConfigurer.addInterceptors`; `addResourceHandlers("/webjars/**").addResourceLocations("classpath:/META-INF/resources/webjars/")`; `@Bean messageSource` = `ResourceBundleMessageSource("ApplicationResources")`; `@Bean rollerViewResolver` = `RollerViewResolver` with `initMethod = "init"` order 1 + `InternalResourceViewResolver` prefix `/WEB-INF/jsps/` suffix `.jsp` order 2. Do NOT add `@EnableWebMvc` (it would disable Boot's MVC auto-config). Drop the explicit multipartResolver (Boot auto-configures StandardServletMultipartResolver). Component scan already covers controllers via RollerApplication.
- [ ] **Step 3: WebContainerConfig** — `ErrorPageRegistrar` mapping: `Exception→/roller-ui/errors/error.jsp`, `500→error.jsp`, `403→403.jsp`, `400→404.jsp`, `404→404.jsp`. Welcome files + jsp-config via customizer:
  ```java
  @Bean
  public WebServerFactoryCustomizer<TomcatServletWebServerFactory> rollerTomcatCustomizer() {
      return factory -> factory.addContextCustomizers(context -> {
          context.addWelcomeFile("index.jsp");
          // jsp-config: UTF-8 page encoding + trim directive whitespaces for *.jsp
          JspPropertyGroup group = new JspPropertyGroup();   // org.apache.tomcat.util.descriptor.web
          group.addUrlPattern("*.jsp");
          group.setPageEncoding("UTF-8");
          group.setTrimWhitespace("true");
          context.addJspPropertyGroup(new JspPropertyGroupDescriptorImpl(group));
      });
  }
  ```
  (Verify the exact Tomcat 11 descriptor API names when implementing — `org.apache.tomcat.util.descriptor.web.JspPropertyGroup` + the wrapper class name may differ slightly; the requirement is: `*.jsp` → pageEncoding UTF-8 + trimDirectiveWhitespaces true, matching the old `<jsp-config>`. `home.jsp` welcome-file: check whether webapp root actually has home.jsp; register the ones that exist.)
- [ ] **Step 4: strip web.xml** down to just the `contextConfigLocation` context-param pointing at `/WEB-INF/security.xml` — NO: under Boot, web.xml is ignored by embedded Tomcat entirely. Instead load security.xml into the Boot context via `@ImportResource("/WEB-INF/security.xml")`?? classpath issue: it's a webapp resource, not classpath. Move `security.xml` to `app/src/main/resources/security.xml` unchanged and add `@ImportResource("classpath:security.xml")` on RollerApplication (temporary — Task 4 deletes it). Delete `spring-mvc.xml` and `web.xml` entirely (maven-war-plugin needs `<failOnMissingWebXml>false</failOnMissingWebXml>` — add it).
- [ ] **Step 5: verify end-to-end locally.** Full unit suite green. Then `./roller db` + `java -jar` (same recipe as Task 2 Step 3) and assert with curl: `/roller/roller-ui/login.rol` returns 200 with `j_username` in body; a `/roller/webjars/...` asset from head.jsp returns 200; `/roller/roller-ui/rendering/page/nosuchblog` returns 404 (servlet reached). Kill, stop db.
- [ ] **Step 6: run the browser IT suite** — NOT yet (harness still cargo/war-based and would deploy the same WAR to external Tomcat 10.1... which no longer has web.xml!). External-deploy compatibility now comes from `SpringBootServletInitializer` — but cargo's Tomcat is 10.1 (Servlet 6.0) while Boot 4 requires 6.1 → **the old harness cannot run this WAR. Skip ITs until Task 6 rewrites the harness.** State this loudly in the report.
- [ ] **Step 7: commit** — `"Register servlets, filters, and MVC config in Java; drop web.xml"`.

---

### Task 4: SecurityConfig — replace security.xml with two SecurityFilterChains

**Files:** create `.../boot/SecurityConfig.java`; delete `app/src/main/resources/security.xml` + the `@ImportResource`; modify `RollerContext.initializeSecurityFeatures` (delete the by-name bean spelunking; it becomes mostly empty — keep `rememberMeEnabled` attr + `flushAuthenticationUserCache` no-op); `application.properties` (+2 security filter props).

**Semantics to reproduce EXACTLY (from security.xml, verified against the current code):**
- Authorities are VERBATIM strings `admin`/`editor` (no `ROLE_` prefix; `RollerUserDetailsService` builds them straight from the DB) → use `hasAuthority`/`hasAnyAuthority`, never `hasRole`.
- Protected: `/roller-ui/login-redirect**`, `/roller-ui/profile**`, `/roller-ui/createWeblog**`, `/roller-ui/menu**` → `hasAnyAuthority("admin","editor")`; `/roller-ui/authoring/**` → same; `/roller-ui/admin/**`, `/roller-ui/setup**`, `/rewrite-status*` → `hasAuthority("admin")`. **Everything else `permitAll()`** — the public blog is anonymous; `anyRequest().authenticated()` would take the whole site down.
- Static chain: `/images/**`, `/scripts/**`, `/styles/**` = `security="none"` → a first-order chain with `securityMatcher` on those + `permitAll` + csrf disabled + no session; or `WebSecurityCustomizer.ignoring()` (choose ignoring() — closest to `security="none"`).
- Form login: page `/roller-ui/login.rol`, processing `/roller_j_security_check`, params `j_username`/`j_password`, failureUrl `/roller-ui/login.rol?error=true`. Success = default saved-request behavior (the XML's `myAuthenticationSuccessHandler`/`FailureHandler` beans were declared but NOT wired — do not attach them; delete them with the XML).
- **Logout: DISABLED** (`.logout(AbstractHttpConfigurer::disable)`) — the XML registered no LogoutFilter; logout is app-level (`/logout.rol` → forward → session.invalidate() in logout-redirect.jsp). Letting Boot's default /logout appear would change behavior.
- Remember-me: keep the custom pair — `RollerRememberMeServices` (key comes from `WebloggerConfig rememberme.key`, default `springRocks` — NOT the XML attribute) via `.rememberMe(r -> r.rememberMeServices(rollerRememberMeServices))`, and register `RollerRememberMeAuthenticationProvider` in the AuthenticationManager ONLY when `WebloggerConfig.getBooleanProperty("rememberme.enabled")` (this replaces RollerContext's live provider-list mutation).
- AuthenticationManager: `ProviderManager` of [conditional RollerRememberMeAuthenticationProvider, `DaoAuthenticationProvider` built with `RollerUserDetailsService` + `RollerContext.getPasswordEncoder()` **constructor-injected** (Security 7 removed the setter path — check current constructor shape)].
- Headers: `.headers(h -> h.frameOptions(f -> f.sameOrigin()))` (media-file editor iframes).
- CSRF: leave Security 7 defaults ON (parity with the XML namespace default). RISK FLAG from research: Security 7 changed CSRF token-handling defaults — the browser IT suite (Task 6) is the arbiter; if forms start failing with 403, configure `CsrfTokenRequestAttributeHandler` explicitly to restore 6.x semantics and document it.
- Matchers: use `PathPatternRequestMatcher` (Ant matchers are removed in Security 7); note `**` suffix patterns like `/roller-ui/profile**` need translation — `/roller-ui/profile**` matched `profile.rol`, `profile!save.rol` etc. PathPattern equivalent: `/roller-ui/profile*` covers a single segment with suffix; verify each pattern against the real routes (RouteCoverageTest's route list) and write authorization unit tests (Step 2) that pin every protected route class.
- `application.properties`: `spring.security.filter.order=40`, `spring.security.filter.dispatcher-types=request,forward`.
- `rememberMeEnabled` ServletContext attribute must still be set (Login.jsp reads it).

- [ ] **Step 1: write the authorization tests FIRST** (spec Risk 5 mandate). `SecurityConfigTest` using `spring-security-test` (add test dep, BOM-managed) + MockMvc standalone is impractical against filter chains — use `@WebMvcTest`-free plain integration: build the `SecurityFilterChain` via `HttpSecurity` in a test? Pragmatic approved shape: a `@SpringBootTest(webEnvironment = MOCK)` + `@AutoConfigureMockMvc` class that stubs the business tier via MockWeblogger... business beans are @Lazy so a MOCK-env context CAN refresh without DB; `RollerLifecycle` must be excluded in this test (`@MockitoBean` it or profile-guard it with `@ConditionalOnWebApplication` + test property `roller.lifecycle.enabled=false` — add that `@ConditionalOnProperty(name="roller.lifecycle.enabled", havingValue="true", matchIfMissing=true)` guard to RollerLifecycle). Tests: for each protected pattern, anonymous GET → 302 to `/roller-ui/login.rol`; `SecurityMockMvcRequestPostProcessors.user("u").authorities(new SimpleGrantedAuthority("editor"))` → 200/404-but-not-302 for editor-allowed, 403 for admin-only; anonymous GET `/roller-ui/login.rol` and `/roller-ui/rendering/page/x` → not-302 (permitAll proven). Watch it fail (no SecurityConfig yet → Boot's default secure-everything), then implement.
- [ ] **Step 2: implement SecurityConfig** per the semantics block. Delete security.xml + `@ImportResource` + the dead success/failure-handler beans knowledge (they die with the file). Trim `initializeSecurityFeatures` to just the `rememberMeEnabled` attribute (or move that to RollerLifecycle and delete the method); `securelogin.enabled` forceHttps: port as `LoginUrlAuthenticationEntryPoint` customization only if `securelogin.enabled=true` — grep first: the key is dead-listed in the spec's fossil sweep; if roller.properties still carries it, replicate the behavior conditionally.
- [ ] **Step 3: verify.** Security tests green; full suite green; the Task 3 curl smoke again PLUS: anonymous `/roller-ui/menu.rol` → 302 login; login flow via curl (`-c cookies -d "j_username=..."` against a dev-DB admin user — the dev DB may not have one; acceptable substitute: assert 302-to-login and the login POST endpoint answers 302-not-500).
- [ ] **Step 4: commit** — `"Replace security.xml with SecurityFilterChain configuration"`.

---

### Task 5: Dev loop — ./roller dev on spring-boot:run; delete Jetty

**Files:** `roller` script; `app/pom.xml` (drop jetty-maven-plugin block + its plugin deps; KEEP `jetty.plugin.version` property only if the jspc plugin still uses it — Task 7 swaps that too, coordinate); delete `app/src/test/resources/jetty.xml`; rename `roller-jettyrun.properties` → `roller-boot-dev.properties` (keep filtering; drop dead keys).

- [ ] **Step 1:** `roller-boot-dev.properties`: keep `installation.type=auto`, `database.configurationType=jdbc` + 5433 coords, `mail.configurationType=properties`, `mail.hostname=localhost`, `passwds.encryption.enabled=false`, `themes.dir=${basedir}/src/main/webapp/themes` (note: filtered `${basedir}` now resolves to `app/` — fix the path accordingly), work dirs under `${project.build.directory}/work`, `themes.reload.mode=true`, caches off, TestTask entries.
- [ ] **Step 2:** `roller` script `dev`: `start_db` + `run_migrations` unchanged; then
  ```bash
  ./mvnw? no — mvn -ntp -pl app spring-boot:run \
      -Dspring-boot.run.arguments="--server.port=8083" \
      -Dspring-boot.run.jvmArguments="-Droller.custom.config=${APP_DIR}/target/test-classes/roller-boot-dev.properties"
  ```
  (test-classes because filtering happens there; add `process-test-resources` to the invocation or document `mvn -pl app process-test-resources` first — simplest: make the script run `mvn -ntp -pl app process-test-resources spring-boot:run ...`.)
- [ ] **Step 3:** verify: `./roller dev` (background the SCRIPT with nohup, poll `curl -sf http://localhost:8083/roller/roller-ui/login.rol` up to 120s, then kill process group + `./roller stop`). Delete jetty.xml + plugin config; full `mvn -ntp -pl app test` green.
- [ ] **Step 4: commit** — `"Run the dev server on spring-boot:run; remove Jetty"`.

---

### Task 6: IT harness — executable WAR replaces cargo

**Files:** `it-selenium/pom.xml`; create `it-selenium/src/test/resources/roller-it.properties` (filtered).

Keep: port reservation, docker postgres (`pg-start`), `pg-wait-ready`, `pg-apply-migrations`, `pg-seed-it-data`, failsafe config (`it.base.url` with 127.0.0.1 — comment explains Chrome/IPv6). Delete: `clean-cargo-configuration`, `unpack-war-for-deployment`, the whole cargo plugin. Replace with:

- [ ] **Step 1:** `roller-it.properties` (filtering on for `${it.db.port}` etc. — check it-selenium testResources filtering; enable if absent):
  ```properties
  installation.type=manual
  database.configurationType=jdbc
  database.jdbc.driverClass=org.postgresql.Driver
  database.jdbc.connectionURL=jdbc:postgresql://127.0.0.1:${it.db.port}/${it.db.name}
  database.jdbc.username=roller
  database.jdbc.password=roller
  mail.configurationType=properties
  mail.hostname=localhost
  themes.dir=${project.basedir}/../app/src/main/webapp/themes
  search.index.dir=${project.build.directory}/it-work/search-index
  mediafiles.storage.dir=${project.build.directory}/it-work/mediafiles
  uploads.dir=${project.build.directory}/it-work/uploads
  ```
- [ ] **Step 2:** launch/stop via `exec-maven-plugin` + small scripts (`it-selenium/src/test/script/start-app.sh`, `stop-app.sh`) OR `spring-boot-maven-plugin`'s `start`/`stop` goals executed against the app artifact — DECISION: use exec + scripts (spring-boot:start expects to run in the app module). `start-app.sh`:
  ```bash
  #!/usr/bin/env bash
  set -euo pipefail
  WAR="$1"; PORT="$2"; PROPS="$3"; PIDFILE="$4"; LOG="$5"
  java -Djava.awt.headless=true -Droller.custom.config="$PROPS" \
       -jar "$WAR" --server.port="$PORT" --server.servlet.context-path=/roller \
       > "$LOG" 2>&1 & echo $! > "$PIDFILE"
  for i in $(seq 1 90); do
      if curl -sf "http://127.0.0.1:${PORT}/roller/roller-ui/login.rol" > /dev/null; then exit 0; fi
      sleep 2
  done
  echo "Roller did not answer on ${PORT} within 180s; log tail:" >&2; tail -50 "$LOG" >&2; exit 1
  ```
  (Readiness gate = same contract as cargo's pingURL/180s. `stop-app.sh` kills the PID with TERM, waits, KILLs.) Wire as `exec` executions `app-start` (pre-integration-test, after seed) and `app-stop` (post-integration-test, before pg-stop). WAR path: `${project.basedir}/../app/target/roller.war` — add a comment that `verify -Pit` reactor builds app first so the repackaged WAR exists.
- [ ] **Step 3:** run the FULL suite: `mvn -ntp verify -Pit` — all 35 ITs green against the executable WAR. This is the acceptance test for Tasks 1–5 too (real browser, real login, real rendering, CSRF forms — if Security 7 CSRF defaults broke forms, THIS is where it shows; fix per Task 4's risk note). Expect iteration; debug systematically from `target/it-work` app log.
- [ ] **Step 4:** commit — `"Run browser ITs against the executable WAR; retire cargo"`.

---

### Task 7: JSP precompilation guard for Jasper 11

**Files:** `app/pom.xml` (swap plugin), root `pom.xml` (retire `jetty.plugin.version` if now unused).

- [ ] Replace `jetty-jspc-maven-plugin` with `io.leonard.maven.plugins:jspc-maven-plugin` latest 5.x (Jasper 11 / Pages 4.0; pin exact patch from Maven Central), same role: phase `process-classes`, compile all `**/*.jsp` to a discarded output dir, `failOnError`. Keep execution id `jspc-validate` and the explanatory comment.
- [ ] **Prove the guard**: introduce a deliberate JSP syntax error (`<c:if` unclosed) in a scratch copy — build must FAIL; revert; build passes. (Same proof discipline as Stage 0's ratchet.)
- [ ] Full suite + commit — `"Validate JSPs with Jasper 11 at build time"`.

---

### Task 8: Java 25 + CI

**Files:** root `pom.xml` (`<release>21</release>` → `25`), `.github/workflows/main.yml`.

- [ ] Flip release to 25; `mvn -ntp -pl app clean verify` green locally (JDK 25 toolchain — check `java -version`; if the local JDK is 21, use the available 25 via SDKMAN or report BLOCKED with evidence).
- [ ] Workflow: matrix `[ '25' ]` only; artifact-upload + diff-coverage conditions change from `matrix.java == '21'` to `== '25'`; IT job JDK 21 → 25 (name + setup-java).
- [ ] Full battery: `mvn -ntp clean verify`, `mvn -ntp verify -Pit`. Commit — `"Target Java 25"`.

---

### Task 9: Docs, cleanup, final battery

**Files:** `CLAUDE.md` (build/run/dev sections: `java -jar`, spring-boot:run, no Jetty/cargo; architecture note: Boot 4/embedded Tomcat 11), `app/pom.xml` sweep (anything orphaned by the conversion: `webjars.version` unused property, dead jetty remnants), report `database.configurationType` default decision (flip default `jndi`→`jdbc` in roller.properties with a comment, since embedded has no JNDI — verify install wizard path still works logically).

- [ ] CLAUDE.md + roller.properties default + orphan sweep.
- [ ] Final battery: `mvn -ntp -pl app clean verify` (ratchet), `mvn -ntp verify -Pit`, `mvn -ntp -pl app jacoco:report && bin/check-diff-coverage.sh <plan-base-sha>` (coordinator supplies the base SHA; 90% bar — conversion code is config-heavy, so expect to write registration/lifecycle unit tests if the bar fails: `RollerLifecycleTest` bootstrap-order test with mocked statics, `ServletRegistrationConfigTest` asserting orders/patterns via the registration beans — write them proactively if diff-cover <90).
- [ ] Commit — `"Document the Spring Boot runtime"`.

---

## Self-Review (at plan-writing time)

- **Spec coverage:** Stage 1 step 2's bullets all land: executable WAR (T1), web.xml→Java config with filter order preserved (T3), security.xml→SecurityFilterChain under Security 7 with pre-written authorization tests (T4), Boot-managed... datasource/mail deliberately NOT Boot-managed — deviation from the spec's "JNDI→Boot-managed via env vars" bullet, justified: Roller's DatabaseProvider/MailProvider already consume env-overridable properties through roller.custom.config, and swapping them for spring.datasource would rewrite JPAPersistenceStrategy mid-conversion; recorded as a conscious scope cut for a later stage. Actuator (T1), roller.properties custom-chain preserved (constraint), dev loop (T5), IT harness (T6), EE-11 JSPC guard (T7 = spec Risk 3), Java 25 (T8).
- **Risks with owners:** JSP-in-executable-WAR proven incrementally (T1 health → T3 login.rol → T6 full browser suite); Security 7 CSRF flagged with detection point (T6) and remedy (T4 note); Tomcat 10.1-vs-6.1 harness incompatibility called out (T3 Step 6) so no one runs doomed ITs mid-plan; spring-jcl/log4j2 collision handled by constraint + T1 Step 4.
- **Placeholders:** Tomcat descriptor API names in T3 Step 3 and PathPattern translations in T4 are verify-at-implementation items with the requirement stated exactly; Boot 4.1 patch version pinned at implementation from Maven Central. All intentional.
- **Type consistency:** `RollerContext.hold(...)` introduced T2, used nowhere else; `roller-boot-dev.properties`/`roller-it.properties` names consistent T5/T6; filter orders 10–80 consistent between table and security properties.
