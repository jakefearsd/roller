/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.roller.weblogger.boot;

import java.io.File;

import jakarta.servlet.ServletContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.BootstrapException;
import org.apache.roller.weblogger.business.SpringWebloggerProvider;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.startup.StartupException;
import org.apache.roller.weblogger.business.startup.WebloggerStartup;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.ui.core.RollerContext;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Drives the Roller startup/shutdown sequence that {@code RollerContext}
 * used to run from {@code ServletContextListener.contextInitialized}/
 * {@code contextDestroyed}.
 *
 * <p>Registered as a Boot {@link SmartLifecycle} bean at phase 0 (explicit
 * {@link #getPhase()} override -- {@code SmartLifecycle}'s own default phase
 * is {@link SmartLifecycle#DEFAULT_PHASE}, {@code Integer.MAX_VALUE}, which
 * is numerically *larger* than Boot's own
 * {@code WebServerStartStopLifecycle} phase
 * ({@code Integer.MAX_VALUE - 2048}, verified via {@code javap} against
 * {@code spring-boot-web-server-4.1.0.jar}). {@code DefaultLifecycleProcessor}
 * starts phases in ascending order and stops them in descending order, so
 * leaving the default phase in place would start Roller *after* the
 * connector opens and stop it *before* the connector closes -- exactly
 * inverted from what's wanted. Phase 0 is safely below
 * {@code WebServerStartStopLifecycle}'s phase, so {@link #start()} runs
 * before the connector opens and {@link #stop()} runs after it has closed --
 * requests never see a half-started or half-shutdown Roller.
 *
 * <p>This transcribes {@code RollerContext.contextInitialized}'s behavior
 * stage by stage, including its failure handling: most failure modes here
 * are logged as fatal and swallowed (the app keeps coming up, same as the
 * old listener never rethrowing out of {@code contextInitialized}) rather
 * than propagating, because a {@code SmartLifecycle.start()} exception
 * aborts the entire {@code SpringApplication.run()} -- a much bigger blast
 * radius than the old per-webapp listener failure ever had. The one
 * genuinely new failure mode (archive-mode {@code themes.dir}
 * misconfiguration, see {@link #resolveDirectories()}) is intentionally a
 * hard failure: there is no sensible fallback for it.
 */
@Component
@ConditionalOnProperty(name = "roller.lifecycle.enabled", havingValue = "true", matchIfMissing = true)
public class RollerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RollerLifecycle.class);

    private final ApplicationContext applicationContext;
    private final ServletContext servletContext;
    private volatile boolean running;

    public RollerLifecycle(ApplicationContext applicationContext, ServletContext servletContext) {
        this.applicationContext = applicationContext;
        this.servletContext = servletContext;
    }

    @Override
    public void start() {
        // Keep a reference to the ServletContext, same as the old
        // contextInitialized's very first line.
        RollerContext.hold(servletContext);

        resolveDirectories();

        // Prepare the core services (database/mail providers). A
        // StartupException here means the configured database could not be
        // reached at all. The old code logged this as fatal and returned
        // from contextInitialized, leaving the servlet context up (if
        // largely unusable) rather than failing the deployment outright;
        // reproduce that rather than aborting the whole Boot process.
        try {
            WebloggerStartup.prepare();
        } catch (StartupException ex) {
            log.error("Roller Weblogger startup failed during app preparation", ex);
            running = true;
            return;
        }

        final boolean ittest = "ittest".equals(WebloggerConfig.getProperty("installation.type"));

        // If preparation left the app unprepared (e.g. installation.type=auto
        // with pending schema work) and this isn't the IT-test bootstrap
        // path, do NOT try to bootstrap the business tier -- same as before,
        // this is a normal "not installed yet" state, not a failure.
        // BootstrapFilter forwards every request to the install wizard while
        // WebloggerFactory.isBootstrapped() is false, exactly as today.
        if (!WebloggerStartup.isPrepared() && !ittest) {

            log.info("\n--------------------------------------------------------------"
                    + "\nRoller Weblogger startup INCOMPLETE, user interaction required"
                    + "\n--------------------------------------------------------------");

        } else {
            if (ittest) {
                try {
                    WebloggerStartup.createDatabase();
                } catch (StartupException e) {
                    // Old code let this one propagate out of
                    // contextInitialized (wrapped as a plain
                    // RuntimeException) rather than logging and continuing
                    // -- a broken IT-test bootstrap should stop startup.
                    throw new IllegalStateException("Failed to create database to IT testing", e);
                }
            }

            Weblogger weblogger = null;
            try {
                // Bootstrap against the Boot application context, which
                // already imports WebloggerBeanConfig via component scan
                // (scanBasePackages = "org.apache.roller.weblogger"), so
                // controllers and the business tier share a single Spring
                // context -- same intent as the old code's reuse of the
                // root WebApplicationContext via
                // WebApplicationContextUtils.getRequiredWebApplicationContext.
                WebloggerFactory.bootstrap(new SpringWebloggerProvider(applicationContext));

                weblogger = WebloggerFactory.getWeblogger();
                weblogger.initialize();

                // I2: site.absoluteurl becomes required the moment any
                // weblog has a custom domain (see needsSiteAbsoluteUrlWarning's
                // javadoc for the mechanism) -- warn once at startup rather
                // than relying solely on ControlPlaneHostFilter's own
                // warning, which fires only once a control-plane request
                // actually arrives on a custom domain, by which point
                // InitFilter may already have latched the wrong host.
                if (needsSiteAbsoluteUrlWarning(weblogger,
                        WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl"))) {
                    log.warn("At least one weblog has a custom domain but site.absoluteurl is "
                            + "unset -- every weblog WITHOUT a custom domain will inherit "
                            + "whichever hostname the first request after boot happens to "
                            + "arrive on (InitFilter's latch) in its canonical url, og:url, "
                            + "feed id, sitemap, robots.txt and password-reset links. Set "
                            + "site.absoluteurl (Admin -> Global Config, or "
                            + "ROLLER_SITE_ABSOLUTEURL) to the site's own address.");
                }

            } catch (BootstrapException ex) {
                log.error("Roller Weblogger bootstrap failed", ex);
            } catch (WebloggerException ex) {
                log.error("Roller Weblogger initialization failed", ex);
            } finally {
                if (weblogger != null) {
                    weblogger.release();
                }
            }
        }

        // Do a small amount of work to initialize the web tier -- always
        // runs, whether or not the business tier bootstrapped above, same
        // as the old code's unconditional final block.
        //
        // The rememberMeEnabled attribute (Login.jsp reads it via
        // ${rememberMeEnabled}) used to be set by RollerContext.initializeSecurityFeatures,
        // which also looked up Spring Security beans by the internal names
        // the security.xml namespace parser assigned them -- a lookup this
        // catch used to be widened to catch(Exception) for, since that XML
        // file was only imported into the context as Task 3 scaffolding
        // (@ImportResource("classpath:security.xml"), removed in Task 4) and
        // the by-name lookups threw an unchecked NoSuchBeanDefinitionException
        // before that landed. Stage 1B Task 4 replaces security.xml with real
        // @Bean-based wiring in SecurityConfig: those beans are constructed
        // during context refresh, which completes before this SmartLifecycle
        // phase ever runs, so a broken SecurityConfig now fails
        // SpringApplication.run() outright with a BeanCreationException and
        // never reaches this line at all. The only failure this try block can
        // still see is setupVelocity()'s checked WebloggerException, so the
        // catch is narrowed back to that -- same as the pre-Boot
        // contextInitialized code caught before the by-name lookup was ever
        // introduced here.
        servletContext.setAttribute("rememberMeEnabled", WebloggerConfig.getProperty("rememberme.enabled"));
        try {
            RollerContext.setupVelocity();
        } catch (WebloggerException ex) {
            // Decision: keep this log-fatal-and-continue, matching the old
            // contextInitialized behavior (a Velocity init failure never
            // propagated out of it either). A SmartLifecycle.start()
            // exception aborts the entire SpringApplication.run(), a much
            // bigger blast radius than the old per-webapp listener failure
            // ever had, and Velocity's own failure mode here (a missing/
            // malformed /WEB-INF/velocity.properties) doesn't warrant taking
            // the whole deployment down -- rendering would be broken either
            // way, but the admin UI, install wizard, and every non-Velocity
            // code path stay usable for an operator to fix the config.
            log.error("Error initializing Roller Weblogger web tier", ex);
        }

        running = true;
    }

    /**
     * Resolve uploads/themes directories from the webapp context path, same
     * as the old {@code contextInitialized}'s early lines -- with one
     * change: {@code ServletContext.getRealPath("/")} works when exploded
     * (external Tomcat, {@code spring-boot:run}), but returns null under
     * {@code java -jar} (the executable WAR's nested-jar resources are never
     * extracted to a real path). The old code required an exploded WAR and
     * simply gave up (logged fatal, returned) when {@code getRealPath}
     * returned null; that's no longer acceptable now that {@code java -jar}
     * is the primary deployment mode, so archive mode instead requires
     * {@code themes.dir} (and by the same reasoning, {@code uploads.dir}) to
     * already be configured via {@code roller.properties}/
     * {@code roller.custom.config}. {@code WebloggerConfig.setUploadsDir}/
     * {@code setThemesDir} are placeholder-only no-ops -- they only replace
     * a literal {@code ${webapp.context}} value -- so a configured value
     * always wins over the webapp-context guess either way.
     */
    private void resolveDirectories() {
        String realPath = servletContext.getRealPath("/");
        if (realPath != null) {
            String resourcesPath = realPath.endsWith(File.separator)
                    ? realPath + "resources"
                    : realPath + File.separator + "resources";
            WebloggerConfig.setUploadsDir(resourcesPath);
            WebloggerConfig.setThemesDir(realPath + File.separator + "themes");
        } else {
            String themesDir = WebloggerConfig.getProperty("themes.dir");
            if (themesDir == null || themesDir.startsWith("${")) {
                throw new IllegalStateException(
                        "Running from an archive: themes.dir must be configured "
                        + "(roller.properties / roller.custom.config)");
            }
        }
    }

    @Override
    public void stop() {
        if (WebloggerFactory.isBootstrapped()) {
            WebloggerFactory.getWeblogger().shutdown();
        }
        // do we need a more generic mechanism for presentation layer shutdown?
        CacheManager.shutdown();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * True when {@code site.absoluteurl} is unset (or blank) AND at least
     * one weblog already has a custom domain -- the condition {@link
     * #start()} warns about once at startup (I2). Pure decision logic,
     * package-private so it can be unit tested directly with the already-
     * resolved config value as a parameter, rather than needing to drive
     * {@link #start()} end to end or capture log output to prove it.
     *
     * <p>A failure reading the weblog list (no schema yet, a transient
     * database error) is treated as "nothing to warn about" -- this check
     * exists to help an operator, not to become a second way startup can
     * fail.
     */
    static boolean needsSiteAbsoluteUrlWarning(Weblogger weblogger, String configuredSiteAbsoluteUrl) {
        if (configuredSiteAbsoluteUrl != null && !configuredSiteAbsoluteUrl.isBlank()) {
            return false;
        }
        try {
            return weblogger.getWeblogManager()
                    .getWeblogs(null, null, null, null, 0, -1).stream()
                    .anyMatch(w -> w.getCustomDomain() != null);
        } catch (WebloggerException | RuntimeException ex) {
            return false;
        }
    }

    /**
     * Explicit phase 0 -- see the class javadoc. {@code SmartLifecycle}'s
     * own default ({@link SmartLifecycle#DEFAULT_PHASE}) is
     * {@code Integer.MAX_VALUE}, which is *higher* than Boot's
     * {@code WebServerStartStopLifecycle} phase
     * ({@code Integer.MAX_VALUE - 2048}); leaving the default in place would
     * start Roller after the connector opens and stop it before the
     * connector closes. Phase 0 fixes both.
     */
    @Override
    public int getPhase() {
        return 0;
    }
}
