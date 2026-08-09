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
package org.apache.roller.weblogger.business.startup;

import org.apache.roller.testing.MigrationFiles;
import org.apache.roller.testing.RollerPostgresContainer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Grafana contract shipped by {@code V017__analytics_contract.sql}:
 * the {@code grafana_ro} role can read exactly the two contract views and
 * nothing else, and {@code analytics_events} rolls up {@code roller_event}
 * rows correctly by weblog handle, event type and day.
 *
 * <p>These are PIN tests, not defect tests -- on a healthy HEAD both must
 * pass. A failure here means the contract itself regressed (see the "Grafana
 * analytics contract" section of CLAUDE.md), not that the test is wrong.
 *
 * <p>Follows the {@code freshDatabase} shape from {@link SchemaMigrationTest}:
 * a scratch database on the shared test container, the real migration chain
 * applied verbatim.
 */
public class AnalyticsContractTest {

    @Test
    public void grafanaRoCanReadTheContractViewsAndNothingElse() throws Exception {
        try (Connection con = freshDatabase("grafana_privileges")) {
            assertTrue(canSelect(con, "analytics_events"),
                    "grafana_ro must be able to SELECT analytics_events");
            assertTrue(canSelect(con, "analytics_weblog_sites"),
                    "grafana_ro must be able to SELECT analytics_weblog_sites");

            assertFalse(canSelect(con, "roller_event"),
                    "grafana_ro must NOT be able to SELECT the underlying roller_event table");
            assertFalse(canSelect(con, "weblog"),
                    "grafana_ro must NOT be able to SELECT the underlying weblog table");
            assertFalse(canSelect(con, "roller_form_submission"),
                    "grafana_ro must NOT be able to SELECT roller_form_submission");
            assertFalse(canSelect(con, "roller_user_token"),
                    "grafana_ro must NOT be able to SELECT roller_user_token");
        }
    }

    @Test
    public void analyticsEventsRollsUpByHandleTypeAndDay() throws Exception {
        try (Connection con = freshDatabase("events_rollup")) {
            String weblogId = UUID.randomUUID().toString();
            String handle = "handle-" + UUID.randomUUID().toString().substring(0, 8);
            insertWeblog(con, weblogId, handle);

            Instant today = Instant.now();
            Instant yesterday = today.minus(1, ChronoUnit.DAYS);

            // Two rows same type/day, one row a different day -- same type.
            insertEvent(con, weblogId, "FORM_SUBMITTED", today);
            insertEvent(con, weblogId, "FORM_SUBMITTED", today);
            insertEvent(con, weblogId, "FORM_SUBMITTED", yesterday);

            assertEquals(2, eventCount(con, handle, "FORM_SUBMITTED", today),
                    "today's rollup should count the two same-day events");
            assertEquals(1, eventCount(con, handle, "FORM_SUBMITTED", yesterday),
                    "yesterday's rollup should count only the one event on that day");
        }
    }

    private boolean canSelect(Connection con, String tableName) throws Exception {
        try (var ps = con.prepareStatement(
                "SELECT has_table_privilege('grafana_ro', 'public.' || ?, 'SELECT')")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private void insertWeblog(Connection con, String id, String handle) throws Exception {
        try (var ps = con.prepareStatement("""
                INSERT INTO weblog (id, name, handle, emailaddress, datecreated)
                VALUES (?, ?, ?, ?, now())
                """)) {
            ps.setString(1, id);
            ps.setString(2, "Test Weblog " + handle);
            ps.setString(3, handle);
            ps.setString(4, "test@example.com");
            ps.executeUpdate();
        }
    }

    private void insertEvent(Connection con, String weblogId, String eventType, Instant occurredAt)
            throws Exception {
        try (var ps = con.prepareStatement("""
                INSERT INTO roller_event (id, weblogid, event_type, occurred_at)
                VALUES (?, ?, ?, ?)
                """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, weblogId);
            ps.setString(3, eventType);
            ps.setTimestamp(4, Timestamp.from(occurredAt));
            ps.executeUpdate();
        }
    }

    private long eventCount(Connection con, String handle, String eventType, Instant day) throws Exception {
        try (var ps = con.prepareStatement("""
                SELECT events FROM analytics_events
                WHERE weblog_handle = ? AND event_type = ? AND day = CAST(? AS date)
                """)) {
            ps.setString(1, handle);
            ps.setString(2, eventType);
            ps.setTimestamp(3, Timestamp.from(day));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0;
                }
                return rs.getLong("events");
            }
        }
    }

    /** Applies the full chain to a brand-new database and returns a connection to it. */
    private Connection freshDatabase(String suffix) throws Exception {
        String dbName = "analyticscontracttest_" + suffix;
        try (Connection admin = adminConnection();
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbName);
            st.execute("CREATE DATABASE " + dbName);
        }

        Connection con = DriverManager.getConnection(
                jdbcUrlFor(dbName),
                RollerPostgresContainer.getUsername(),
                RollerPostgresContainer.getPassword());
        for (Path migration : MigrationFiles.all()) {
            try (Statement st = con.createStatement()) {
                st.execute(readMigration(migration));
            }
        }
        return con;
    }

    private String readMigration(Path migration) throws Exception {
        return Files.readString(migration, StandardCharsets.UTF_8)
                .replace(":app_user", RollerPostgresContainer.getUsername());
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection(
                RollerPostgresContainer.getJdbcUrl(),
                RollerPostgresContainer.getUsername(),
                RollerPostgresContainer.getPassword());
    }

    /** Rewrites the container's JDBC URL to point at a different database. */
    private String jdbcUrlFor(String dbName) {
        String url = RollerPostgresContainer.getJdbcUrl();
        int dbStart = url.lastIndexOf('/') + 1;
        int queryStart = url.indexOf('?', dbStart);
        String tail = queryStart < 0 ? "" : url.substring(queryStart);
        return url.substring(0, dbStart) + dbName + tail;
    }
}
