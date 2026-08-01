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
package org.apache.roller.testing;

import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts the shared PostgreSQL container and builds the schema that every
 * test runs against.
 *
 * <p>The live entry point is the static {@link #ensureSchema()}: once per JVM
 * it starts the container, points Roller's configuration at it, and builds
 * the schema by applying the real {@code bin/db/migrations} chain.
 * {@code RollerTestBootstrap} and {@code TestUtils} call it directly. Tests
 * currently tear down their own fixtures rather than relying on any
 * per-test reset.
 *
 * <p>This class also implements {@link BeforeEachCallback} to truncate the
 * data tables and clear Roller's caches before each test, which is what
 * would let tests stop hand-rolling {@code teardownUser}/{@code
 * teardownWeblog} pairs. That callback is <strong>not currently
 * registered</strong> anywhere — there is no {@code @ExtendWith} on any test
 * and no JUnit auto-detection configured — so {@link #beforeEach} never
 * runs today. Anyone wiring it up should audit existing tests for
 * cross-test state assumptions first.
 *
 * <p>Two tables are deliberately preserved from truncation:
 * <ul>
 *   <li>{@code schema_migrations} — truncating it would make the installer
 *       re-run every migration.</li>
 *   <li>{@code roller_properties} — runtime configuration populated at
 *       bootstrap, not test fixture data.</li>
 * </ul>
 */
public class RollerDatabaseExtension implements BeforeEachCallback {

    private static final List<String> PRESERVED_TABLES =
            List.of("schema_migrations", "roller_properties");

    private static volatile boolean schemaReady;

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        ensureSchema();
        truncateDataTables();
        CacheManager.clear();
        // Drop any EntityManager still holding rows that no longer exist.
        if (WebloggerFactory.isBootstrapped()) {
            WebloggerFactory.getWeblogger().release();
        }
    }

    /**
     * Start the container, publish its coordinates to Roller's config, and
     * apply the migration chain. Idempotent and safe under concurrent access.
     */
    public static void ensureSchema() {
        if (schemaReady) {
            return;
        }
        synchronized (RollerDatabaseExtension.class) {
            if (schemaReady) {
                return;
            }
            publishDatabaseConfig();
            applyMigrations();
            schemaReady = true;
        }
    }

    /**
     * Roller reads {@code roller.custom.config} last, so writing the
     * container's JDBC coordinates there overrides the checked-in test
     * properties without them needing to know the randomly-assigned port.
     */
    private static void publishDatabaseConfig() {
        try {
            Path overrides = Files.createTempFile("roller-testdb", ".properties");
            overrides.toFile().deleteOnExit();
            String contents = """
                    database.configurationType=jdbc
                    database.jdbc.driverClass=org.postgresql.Driver
                    database.jdbc.connectionURL=%s
                    database.jdbc.username=%s
                    database.jdbc.password=%s
                    """.formatted(
                    RollerPostgresContainer.getJdbcUrl(),
                    RollerPostgresContainer.getUsername(),
                    RollerPostgresContainer.getPassword());
            Files.writeString(overrides, contents, StandardCharsets.UTF_8);

            // WebloggerConfig loads this in a static initializer, so it must be
            // set before anything touches that class.
            System.setProperty("roller.custom.config", overrides.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not publish test database config", e);
        }
    }

    /**
     * Builds the schema from {@code bin/db/migrations} — the same files
     * {@code migrate.sh} and the install wizard use. There is deliberately no
     * separate test schema to drift.
     */
    private static void applyMigrations() {
        List<Path> migrations = MigrationFiles.all();
        try (Connection con = newConnection()) {
            con.setAutoCommit(false);
            for (Path migration : migrations) {
                String sql = Files.readString(migration, StandardCharsets.UTF_8)
                        .replace(":app_user", RollerPostgresContainer.getUsername());
                try (Statement st = con.createStatement()) {
                    st.execute(sql);
                }
            }
            recordApplied(con, migrations);
            con.commit();
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Could not apply migrations to the test database", e);
        }
    }

    private static void recordApplied(Connection con, List<Path> migrations) throws SQLException {
        for (Path migration : migrations) {
            String version = migration.getFileName().toString().replace(".sql", "");
            try (var ps = con.prepareStatement(
                    "INSERT INTO schema_migrations (version) VALUES (?) ON CONFLICT DO NOTHING")) {
                ps.setString(1, version);
                ps.executeUpdate();
            }
        }
    }

    /**
     * One TRUNCATE over every data table. CASCADE handles the foreign keys, so
     * the order of the table list does not matter.
     */
    private static void truncateDataTables() {
        try (Connection con = newConnection()) {
            List<String> tables = dataTables(con);
            if (tables.isEmpty()) {
                return;
            }
            try (Statement st = con.createStatement()) {
                st.execute("TRUNCATE TABLE " + String.join(", ", tables)
                        + " RESTART IDENTITY CASCADE");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not reset the test database", e);
        }
    }

    private static List<String> dataTables(Connection con) throws SQLException {
        List<String> tables = new ArrayList<>();
        String sql = "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename";
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String table = rs.getString(1);
                if (!PRESERVED_TABLES.contains(table.toLowerCase())) {
                    tables.add(table);
                }
            }
        }
        return tables;
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(
                RollerPostgresContainer.getJdbcUrl(),
                RollerPostgresContainer.getUsername(),
                RollerPostgresContainer.getPassword());
    }
}
