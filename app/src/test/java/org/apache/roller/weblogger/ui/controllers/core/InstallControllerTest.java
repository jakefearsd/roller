/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package org.apache.roller.weblogger.ui.controllers.core;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.roller.weblogger.business.DatabaseProvider;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.business.startup.MigrationCatalog;
import org.apache.roller.weblogger.business.startup.StartupException;
import org.apache.roller.weblogger.business.startup.WebloggerStartup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link InstallController}, the wizard that runs before Roller has
 * a usable database.
 *
 * <p>Its whole job is deciding what state the installation is in, and the most
 * important of those decisions is the first one: once the application has been
 * bootstrapped, every install URL must bounce to the site rather than offer to
 * create or upgrade a database that is already in use.
 */
class InstallControllerTest {

    private InstallController controller;
    private ExtendedModelMap model;
    private StartupException previousDbException;
    private boolean previousPrepared;
    private Object previousDbProvider;

    /** Every connection the mocked database provider has handed out. */
    private final List<Connection> openedConnections = new ArrayList<>();

    @BeforeEach
    void setUp() {
        controller = ControllerTestFixture.withMessages(new InstallController());
        model = new ExtendedModelMap();
        previousDbException = WebloggerStartup.getDatabaseProviderException();
        previousPrepared = WebloggerStartup.isPrepared();
        previousDbProvider = getStartupField("dbProvider");
    }

    @AfterEach
    void tearDown() {
        MockWeblogger.uninstall();
        setStartupField("dbProviderException", previousDbException);
        setStartupField("prepared", previousPrepared);
        setStartupField("dbProvider", previousDbProvider);
    }

    @Test
    void everyInstallUrlBouncesToTheSiteOnceRollerIsRunning() {
        MockWeblogger.install();

        assertEquals("redirect:/", controller.execute(request(), model));
        assertEquals("redirect:/", controller.create(request(), model));
        assertEquals("redirect:/", controller.upgrade(request(), model));
        assertEquals("redirect:/", controller.bootstrap(request(), model));
    }

    @Test
    void aDatabaseConnectionFailureIsShownWithItsCauseAndStartupLog() {
        // This is the page an administrator sees when the JDBC settings are
        // wrong, so the underlying cause and the log have to reach the view.
        MockWeblogger.installNotBootstrapped();
        Throwable rootCause = new IllegalStateException("connection refused");
        StartupException failure =
                new StartupException("could not connect", rootCause, List.of("tried localhost:5432"));
        setStartupField("dbProviderException", failure);

        String view = controller.execute(request(), model);

        assertEquals(".DatabaseError", view);
        assertSame(rootCause, model.getAttribute("rootCauseException"));
        assertEquals(List.of("tried localhost:5432"), model.getAttribute("messages"));
        assertEquals("installer.error.connection.pageTitle", model.getAttribute("pageTitle"));
        String stackTrace = (String) model.getAttribute("rootCauseStackTrace");
        assertTrue(stackTrace.contains("connection refused"),
                "the stack trace shown to the administrator has to be the real one: " + stackTrace);
    }

    @Test
    void aStartupFailureWithNoDeeperCauseReportsItself() {
        MockWeblogger.installNotBootstrapped();
        StartupException failure = new StartupException("no database configured");
        setStartupField("dbProviderException", failure);

        String view = controller.execute(request(), model);

        assertEquals(".DatabaseError", view);
        assertSame(failure, model.getAttribute("rootCauseException"),
                "with no root cause the exception itself is what there is to show");
    }

    @Test
    void bootstrappingBeforeTheApplicationIsPreparedFailsOntoTheBootstrapPage() {
        // Hitting install!bootstrap.rol out of order must produce the error page
        // rather than an exception escaping to the container.
        MockWeblogger.installNotBootstrapped();
        setStartupField("prepared", false);

        String view = controller.bootstrap(request(), model);

        assertEquals(".Bootstrap", view);
        assertNotNull(model.getAttribute("rootCauseException"));
        assertEquals("installer.error.unknown.pageTitle", model.getAttribute("pageTitle"));
        assertFalse(((String) model.getAttribute("rootCauseStackTrace")).isEmpty());
    }

    @Test
    void anEmptyDatabaseIsOfferedTheSchemaCreationPage() throws Exception {
        // Pointing the installer at a database with no tables in it is the
        // ordinary first-run case, and this is the page that gets it going.
        MockWeblogger.installNotBootstrapped();
        setStartupField("dbProviderException", null);
        installDatabaseProvider(new String[0]);

        String view = controller.execute(request(), model);

        assertEquals(".CreateDatabase", view);
        assertEquals("installer.database.creation.pageTitle", model.getAttribute("pageTitle"));
        assertEquals("PostgreSQL", model.getAttribute("databaseProductName"),
                "the page names the database it is about to write to");
        // There is no connection pool yet at this point, so every connection the
        // installer opens is one it has to hand back itself.
        assertFalse(openedConnections.isEmpty(), "the installer did look at the database");
        for (Connection connection : openedConnections) {
            verify(connection).close();
        }
    }

    @Test
    void aFullyMigratedDatabaseIsSentStraightToBootstrapping() throws Exception {
        // Nothing to create and nothing to upgrade: the installer has no work
        // left, and the remaining step is starting the application.
        MockWeblogger.installNotBootstrapped();
        setStartupField("dbProviderException", null);
        installDatabaseProvider(new String[]{"schema_migrations", "roller_user"},
                MigrationCatalog.versions().toArray(new String[0]));

        String view = controller.execute(request(), model);

        assertEquals(".Bootstrap", view);
        assertNotNull(model.getAttribute("rootCauseException"));
    }

    @Test
    void aDatabaseWithSchemaButPendingMigrationsIsOfferedTheUpgradePage() throws Exception {
        // Tables exist and some migrations have been applied, but not all of
        // them: that is an upgrade, and offering to create the schema instead
        // would be offering to re-run the baseline over live data.
        MockWeblogger.installNotBootstrapped();
        setStartupField("dbProviderException", null);
        installDatabaseProvider(new String[]{"schema_migrations", "roller_user"}, "V001__baseline");

        String view = controller.execute(request(), model);

        assertEquals(".UpgradeDatabase", view);
        assertEquals("installer.database.upgrade.pageTitle", model.getAttribute("pageTitle"));
        assertEquals(Boolean.TRUE, model.getAttribute("upgradeRequired"));
        assertEquals("PostgreSQL", model.getAttribute("databaseProductName"));
    }

    @Test
    void aSchemaCreationThatFailsStaysOnTheCreationPageWithTheLog() throws Exception {
        // The startup log is the only diagnosis an administrator gets when the
        // installer cannot write to the database.
        MockWeblogger.installNotBootstrapped();
        installUnreachableDatabase();

        String view = controller.create(request(), model);

        assertEquals(".CreateDatabase", view);
        assertEquals(Boolean.TRUE, model.getAttribute("error"));
        assertNull(model.getAttribute("success"));
        assertFalse(((List<?>) model.getAttribute("messages")).isEmpty(),
                "the failure has to come back with something to read");
        assertEquals("installer.database.creation.pageTitle", model.getAttribute("pageTitle"));
    }

    @Test
    void anUpgradeThatFailsStaysOnTheUpgradePageWithTheLog() throws Exception {
        MockWeblogger.installNotBootstrapped();
        installUnreachableDatabase();

        String view = controller.upgrade(request(), model);

        assertEquals(".UpgradeDatabase", view);
        assertEquals(Boolean.TRUE, model.getAttribute("error"));
        assertNull(model.getAttribute("success"));
        assertEquals("installer.database.upgrade.pageTitle", model.getAttribute("pageTitle"));
    }

    @Test
    void theInstallerIsReachableWithNoUserAndNoWeblog() {
        // There are no accounts yet when this wizard runs.
        assertFalse(controller.isUserRequired());
        assertFalse(controller.isWeblogRequired());
    }

    @Test
    void propertiesComeFromTheStaticConfigurationOnlyWhileInstalling() {
        // The database is exactly what is not available here, so this override
        // must not fall through to the runtime (database-backed) properties.
        assertEquals("A-Za-z0-9", controller.getProp("username.allowedChars"));
    }

    @Test
    void anUnknownPropertyComesBackAsItsOwnName() {
        // Which is what the installer JSPs render, rather than "null".
        assertEquals("no.such.property.exists", controller.getProp("no.such.property.exists"));
    }

    private static jakarta.servlet.http.HttpServletRequest request() {
        return ControllerTestFixture.requestFor(null);
    }

    /**
     * Points {@code WebloggerStartup} at a database that reports the given
     * tables and applied migration versions.
     *
     * <p>The installer inspects the database through JDBC metadata, so this is
     * the smallest thing that can stand in for "a database in state X" without
     * one being running.
     */
    private void installDatabaseProvider(String[] tables, String... appliedVersions) throws Exception {
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        // A fresh result set per call: the installer asks more than once and
        // each ask has to start from the beginning of the table list.
        when(metaData.getTables(isNull(), isNull(), eq("%"), isNull()))
                .thenAnswer(invocation -> tableListResultSet(tables));

        Statement statement = mock(Statement.class);
        when(statement.executeQuery(any())).thenAnswer(invocation -> versionResultSet(appliedVersions));

        // A distinct connection per call, all of them remembered, so a test can
        // insist that every one was handed back.
        DatabaseProvider provider = mock(DatabaseProvider.class);
        when(provider.getConnection()).thenAnswer(invocation -> {
            Connection connection = mock(Connection.class);
            when(connection.getMetaData()).thenReturn(metaData);
            when(connection.createStatement()).thenReturn(statement);
            openedConnections.add(connection);
            return connection;
        });
        setStartupField("dbProvider", provider);
    }

    /** A database provider whose connections never open. */
    private void installUnreachableDatabase() throws Exception {
        DatabaseProvider provider = mock(DatabaseProvider.class);
        when(provider.getConnection()).thenThrow(new java.sql.SQLException("connection refused"));
        setStartupField("dbProvider", provider);
    }

    private static ResultSet tableListResultSet(String[] tables) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        stubRows(rs, tables.length);
        when(rs.getString("TABLE_NAME")).thenAnswer(nextValueOf(tables));
        return rs;
    }

    private static ResultSet versionResultSet(String[] versions) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        stubRows(rs, versions.length);
        when(rs.getString(1)).thenAnswer(nextValueOf(versions));
        return rs;
    }

    /** Makes {@code next()} return true the given number of times, then false. */
    private static void stubRows(ResultSet rs, int rows) throws Exception {
        AtomicInteger remaining = new AtomicInteger(rows);
        when(rs.next()).thenAnswer(invocation -> remaining.getAndDecrement() > 0);
    }

    private static Answer<String> nextValueOf(String[] values) {
        AtomicInteger index = new AtomicInteger();
        return invocation -> values[index.getAndIncrement()];
    }

    /**
     * {@code WebloggerStartup} keeps the installation state in private statics
     * with no setters; a test that wants a particular state has to write them.
     */
    private static void setStartupField(String name, Object value) {
        try {
            startupField(name).set(null, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not set WebloggerStartup." + name, e);
        }
    }

    private static Object getStartupField(String name) {
        try {
            return startupField(name).get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not read WebloggerStartup." + name, e);
        }
    }

    private static Field startupField(String name) {
        try {
            Field field = WebloggerStartup.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(
                    "WebloggerStartup no longer has a '" + name + "' field. Set the installation "
                            + "state the way it is now expressed.", e);
        }
    }
}
