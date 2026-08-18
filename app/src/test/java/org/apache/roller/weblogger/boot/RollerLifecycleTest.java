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

import java.util.List;

import jakarta.servlet.ServletContext;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.BootstrapException;
import org.apache.roller.weblogger.business.InitializationException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.startup.StartupException;
import org.apache.roller.weblogger.business.startup.WebloggerStartup;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link RollerLifecycle}'s branching without a live servlet container
 * or database: {@code start()}'s {@code resolveDirectories()} step, its
 * StartupException/incomplete-installation/ittest/bootstrap-success/
 * bootstrap-failure branches, and {@code stop()} in both the "nothing to shut
 * down" and "shut down a bootstrapped Weblogger" shapes.
 *
 * <p>Every {@code start()}-exercising test here mock-installs
 * {@link WebloggerStartup} and {@link WebloggerConfig} so no real database or
 * classpath config is touched, and every test that lets {@code start()} reach
 * the real {@link org.apache.roller.weblogger.business.WebloggerFactory#bootstrap}
 * call wraps itself in {@code MockWeblogger.installNotBootstrapped()}/{@code
 * uninstall()} so the JVM-wide {@code WebloggerFactory} static state a
 * database-backed test elsewhere in the suite depends on is restored
 * afterward, regardless of what this test's own {@code start()} call did to
 * it in between -- {@code uninstall()} always restores the value captured
 * before this test ran, not whatever is installed when it is called (see
 * {@code MockWeblogger.uninstall}'s own javadoc).
 *
 * <p>Full end-to-end startup against a real application context and database
 * is exercised live instead (see the Task 2/2b reports: both a
 * {@code spring-boot:run} exploded run and a {@code java -jar} run) -- not
 * appropriate for a plain unit test in this suite.
 */
class RollerLifecycleTest {

    /**
     * Boot's own {@code WebServerStartStopLifecycle} phase, {@code Integer.MAX_VALUE - 2048}.
     * Verified via {@code javap -c} against
     * {@code org.springframework.boot.web.server.servlet.context.WebServerStartStopLifecycle#getPhase()}
     * in {@code spring-boot-web-server-4.1.0.jar}. {@code RollerLifecycle} must
     * report a phase strictly below this constant so that
     * {@code DefaultLifecycleProcessor} (which starts phases in ascending order
     * and stops them in descending order) starts Roller before the connector
     * opens and stops Roller after the connector closes. Note that
     * {@code SmartLifecycle}'s own default phase
     * ({@link org.springframework.context.SmartLifecycle#DEFAULT_PHASE}, plain
     * {@code Integer.MAX_VALUE}) is *above* this constant -- relying on the
     * default would invert both orderings, which is exactly the bug this test
     * guards against.
     */
    private static final int WEB_SERVER_START_STOP_LIFECYCLE_PHASE = Integer.MAX_VALUE - 2048;

    private ApplicationContext applicationContext;
    private ServletContext servletContext;
    private RollerLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        applicationContext = mock(ApplicationContext.class);
        servletContext = mock(ServletContext.class);
        lifecycle = new RollerLifecycle(applicationContext, servletContext);
    }

    @Test
    void phaseStartsBeforeAndStopsAfterTheWebServerLifecycle() {
        assertTrue(lifecycle.getPhase() < WEB_SERVER_START_STOP_LIFECYCLE_PHASE,
                "RollerLifecycle's phase must be below WebServerStartStopLifecycle's ("
                        + WEB_SERVER_START_STOP_LIFECYCLE_PHASE
                        + ") so it starts before the connector opens and stops after it closes");
    }

    @Test
    void notRunningUntilStarted() {
        assertFalse(lifecycle.isRunning());
    }

    @Test
    void stopIsSafeWhenStartWasNeverCalled() {
        MockWeblogger.installNotBootstrapped();
        try {
            assertFalse(lifecycle.isRunning());
            assertDoesNotThrow(() -> lifecycle.stop());
            assertFalse(lifecycle.isRunning());
        } finally {
            MockWeblogger.uninstall();
        }
    }

    @Test
    void stopShutsDownTheBootstrappedWeblogger() {
        MockWeblogger mocks = MockWeblogger.install();
        try {
            lifecycle.stop();
            verify(mocks.weblogger()).shutdown();
            assertFalse(lifecycle.isRunning());
        } finally {
            MockWeblogger.uninstall();
        }
    }

    // ------------------------------------------------- resolveDirectories()

    @Test
    void startResolvesUploadsAndThemesDirsFromRealPathThenStopsEarlyWhenPrepareFails() {
        when(servletContext.getRealPath("/")).thenReturn("/tmp/roller-lifecycle-test");

        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class);
                MockedStatic<WebloggerStartup> startup = mockStatic(WebloggerStartup.class)) {

            startup.when(WebloggerStartup::prepare).thenThrow(new StartupException("db unreachable"));

            lifecycle.start();

            config.verify(() -> WebloggerConfig.setUploadsDir(
                    contains("/tmp/roller-lifecycle-test/resources")));
            config.verify(() -> WebloggerConfig.setThemesDir(
                    contains("/tmp/roller-lifecycle-test/themes")));
            assertTrue(lifecycle.isRunning(),
                    "start() marks itself running even when prepare() fails, matching the old "
                            + "contextInitialized behavior of leaving the servlet context up");
        }
    }

    @Test
    void startThrowsIllegalStateWhenArchiveModeThemesDirIsNotConfigured() {
        when(servletContext.getRealPath("/")).thenReturn(null);

        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class)) {
            config.when(() -> WebloggerConfig.getProperty("themes.dir")).thenReturn(null);

            assertThrows(IllegalStateException.class, () -> lifecycle.start(),
                    "running from an archive (java -jar) with no themes.dir configured must fail "
                            + "fast rather than silently guess a webapp-context path that does not exist");
        }
    }

    // --------------------------------------------- installation state branches

    @Test
    void startLogsIncompleteAndSkipsBootstrapWhenNotPreparedAndNotIttest() {
        when(servletContext.getRealPath("/")).thenReturn("/tmp/roller-lifecycle-test");

        MockWeblogger.installNotBootstrapped();
        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class);
                MockedStatic<WebloggerStartup> startup = mockStatic(WebloggerStartup.class)) {

            startup.when(WebloggerStartup::prepare).thenAnswer(inv -> null);
            startup.when(WebloggerStartup::isPrepared).thenReturn(false);
            config.when(() -> WebloggerConfig.getProperty("installation.type")).thenReturn("manual");

            lifecycle.start();

            assertTrue(lifecycle.isRunning());
        } finally {
            MockWeblogger.uninstall();
        }
    }

    // ------------------------------------------------------------- ittest

    @Test
    void startCreatesDatabaseThenBootstrapsWhenInstallationTypeIsIttest() throws Exception {
        when(servletContext.getRealPath("/")).thenReturn("/tmp/roller-lifecycle-test");
        Weblogger mockWeblogger = mock(Weblogger.class);
        when(applicationContext.getBean(Weblogger.class)).thenReturn(mockWeblogger);

        MockWeblogger.installNotBootstrapped();
        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class);
                MockedStatic<WebloggerStartup> startup = mockStatic(WebloggerStartup.class)) {

            startup.when(WebloggerStartup::prepare).thenAnswer(inv -> null);
            startup.when(WebloggerStartup::isPrepared).thenReturn(true);
            startup.when(WebloggerStartup::createDatabase).thenReturn(java.util.List.of());
            config.when(() -> WebloggerConfig.getProperty("installation.type")).thenReturn("ittest");

            lifecycle.start();

            startup.verify(WebloggerStartup::createDatabase);
            verify(mockWeblogger).initialize();
            verify(mockWeblogger).release();
            assertTrue(lifecycle.isRunning());
        } finally {
            MockWeblogger.uninstall();
        }
    }

    @Test
    void startWrapsCreateDatabaseFailureDuringIttestAsIllegalState() throws Exception {
        when(servletContext.getRealPath("/")).thenReturn("/tmp/roller-lifecycle-test");

        MockWeblogger.installNotBootstrapped();
        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class);
                MockedStatic<WebloggerStartup> startup = mockStatic(WebloggerStartup.class)) {

            startup.when(WebloggerStartup::prepare).thenAnswer(inv -> null);
            startup.when(WebloggerStartup::isPrepared).thenReturn(true);
            startup.when(WebloggerStartup::createDatabase)
                    .thenThrow(new StartupException("could not create IT database"));
            config.when(() -> WebloggerConfig.getProperty("installation.type")).thenReturn("ittest");

            assertThrows(IllegalStateException.class, () -> lifecycle.start(),
                    "a broken IT-test bootstrap must stop startup rather than being logged and "
                            + "swallowed like the normal-deployment StartupException case");
        } finally {
            MockWeblogger.uninstall();
        }
    }

    // ------------------------------------------------------ bootstrap branch

    @Test
    void startBootstrapsAndInitializesTheBusinessTierWhenPrepared() throws Exception {
        when(servletContext.getRealPath("/")).thenReturn("/tmp/roller-lifecycle-test");
        Weblogger mockWeblogger = mock(Weblogger.class);
        when(applicationContext.getBean(Weblogger.class)).thenReturn(mockWeblogger);

        MockWeblogger.installNotBootstrapped();
        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class);
                MockedStatic<WebloggerStartup> startup = mockStatic(WebloggerStartup.class)) {

            startup.when(WebloggerStartup::prepare).thenAnswer(inv -> null);
            startup.when(WebloggerStartup::isPrepared).thenReturn(true);
            config.when(() -> WebloggerConfig.getProperty("installation.type")).thenReturn("manual");

            lifecycle.start();

            verify(mockWeblogger).initialize();
            verify(mockWeblogger).release();
            assertTrue(lifecycle.isRunning());
        } finally {
            MockWeblogger.uninstall();
        }
    }

    @Test
    void startStillReleasesTheWebloggerWhenInitializeThrows() throws Exception {
        when(servletContext.getRealPath("/")).thenReturn("/tmp/roller-lifecycle-test");
        Weblogger mockWeblogger = mock(Weblogger.class);
        when(applicationContext.getBean(Weblogger.class)).thenReturn(mockWeblogger);
        doThrow(new InitializationException("boom")).when(mockWeblogger).initialize();

        MockWeblogger.installNotBootstrapped();
        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class);
                MockedStatic<WebloggerStartup> startup = mockStatic(WebloggerStartup.class)) {

            startup.when(WebloggerStartup::prepare).thenAnswer(inv -> null);
            startup.when(WebloggerStartup::isPrepared).thenReturn(true);
            config.when(() -> WebloggerConfig.getProperty("installation.type")).thenReturn("manual");

            assertDoesNotThrow(() -> lifecycle.start(),
                    "a WebloggerException from initialize() must be logged and swallowed, matching "
                            + "the old contextInitialized behavior, not abort SpringApplication.run()");

            verify(mockWeblogger).release();
            assertTrue(lifecycle.isRunning());
        } finally {
            MockWeblogger.uninstall();
        }
    }

    @Test
    void startDoesNotReleaseWhenBootstrapItselfFails() {
        when(servletContext.getRealPath("/")).thenReturn("/tmp/roller-lifecycle-test");
        // A null Weblogger from the provider is exactly what makes
        // WebloggerFactory.bootstrap(...) throw BootstrapException.
        when(applicationContext.getBean(Weblogger.class)).thenReturn(null);

        MockWeblogger.installNotBootstrapped();
        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class);
                MockedStatic<WebloggerStartup> startup = mockStatic(WebloggerStartup.class)) {

            startup.when(WebloggerStartup::prepare).thenAnswer(inv -> null);
            startup.when(WebloggerStartup::isPrepared).thenReturn(true);
            config.when(() -> WebloggerConfig.getProperty("installation.type")).thenReturn("manual");

            assertDoesNotThrow(() -> lifecycle.start(),
                    "BootstrapException must be logged and swallowed, same as every other failure "
                            + "mode start() absorbs rather than aborting SpringApplication.run()");

            assertTrue(lifecycle.isRunning());
        } finally {
            MockWeblogger.uninstall();
        }
    }

    // ------------------------------------------- I2: site.absoluteurl warning

    /**
     * {@link RollerLifecycle#needsSiteAbsoluteUrlWarning}, tested directly
     * rather than by driving {@code start()} and capturing log output: it is
     * pure decision logic (no logging itself), taking the already-resolved
     * {@code site.absoluteurl} value as a parameter rather than reading it
     * via the static {@code WebloggerFactory}/{@code WebloggerRuntimeConfig}
     * seam {@code start()} itself uses -- the same reasoning that keeps
     * {@code CustomDomainRules} pure (see its own javadoc): resolving
     * configuration is the caller's job, deciding what to do with it is
     * this method's.
     */
    @Test
    void needsSiteAbsoluteUrlWarningIsTrueWhenAWeblogHasADomainAndSiteAbsoluteUrlIsBlank() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogManager weblogManager = mock(WeblogManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        Weblog withDomain = new Weblog();
        withDomain.setCustomDomain("vhost.example.com");
        when(weblogManager.getWeblogs(null, null, null, null, 0, -1))
                .thenReturn(List.of(withDomain));

        assertTrue(RollerLifecycle.needsSiteAbsoluteUrlWarning(weblogger, null));
        assertTrue(RollerLifecycle.needsSiteAbsoluteUrlWarning(weblogger, "   "));
    }

    @Test
    void needsSiteAbsoluteUrlWarningIsFalseWhenSiteAbsoluteUrlIsConfigured() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogManager weblogManager = mock(WeblogManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        Weblog withDomain = new Weblog();
        withDomain.setCustomDomain("vhost.example.com");
        when(weblogManager.getWeblogs(null, null, null, null, 0, -1))
                .thenReturn(List.of(withDomain));

        assertFalse(RollerLifecycle.needsSiteAbsoluteUrlWarning(
                weblogger, "https://blog.example.com"));
    }

    @Test
    void needsSiteAbsoluteUrlWarningIsFalseWhenNoWeblogHasACustomDomain() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogManager weblogManager = mock(WeblogManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogManager.getWeblogs(null, null, null, null, 0, -1))
                .thenReturn(List.of(new Weblog()));

        assertFalse(RollerLifecycle.needsSiteAbsoluteUrlWarning(weblogger, null));
    }

    /** A failed check must not itself abort or otherwise disrupt startup. */
    @Test
    void needsSiteAbsoluteUrlWarningIsFalseWhenTheWeblogQueryFails() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogManager weblogManager = mock(WeblogManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogManager.getWeblogs(null, null, null, null, 0, -1))
                .thenThrow(new WebloggerException("database down"));

        assertFalse(RollerLifecycle.needsSiteAbsoluteUrlWarning(weblogger, null));
    }

    /**
     * The integration point: {@code start()} must call the check (and
     * therefore be ABLE to warn) once the business tier has bootstrapped,
     * without upsetting anything else {@code start()} already does. A plain
     * {@code mock(Weblogger.class)}'s {@code getWeblogManager()} returns
     * null here (unstubbed), which is exactly what proves the check's own
     * failure handling (see the test above) rather than the log-content
     * itself, which this suite does not capture -- see {@code
     * RollerLifecycleTest}'s class javadoc for why a full log-driving test
     * belongs in the live end-to-end runs instead.
     */
    @Test
    void startChecksForCustomDomainsNeedingSiteAbsoluteUrlWithoutDisruptingBootstrap() throws Exception {
        when(servletContext.getRealPath("/")).thenReturn("/tmp/roller-lifecycle-test");
        Weblogger mockWeblogger = mock(Weblogger.class);
        when(applicationContext.getBean(Weblogger.class)).thenReturn(mockWeblogger);

        MockWeblogger.installNotBootstrapped();
        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class);
                MockedStatic<WebloggerStartup> startup = mockStatic(WebloggerStartup.class)) {

            startup.when(WebloggerStartup::prepare).thenAnswer(inv -> null);
            startup.when(WebloggerStartup::isPrepared).thenReturn(true);
            config.when(() -> WebloggerConfig.getProperty("installation.type")).thenReturn("manual");

            assertDoesNotThrow(() -> lifecycle.start(),
                    "the site.absoluteurl check must never itself abort startup, whatever it finds");

            verify(mockWeblogger).initialize();
            verify(mockWeblogger).release();
            assertTrue(lifecycle.isRunning());
        } finally {
            MockWeblogger.uninstall();
        }
    }
}
