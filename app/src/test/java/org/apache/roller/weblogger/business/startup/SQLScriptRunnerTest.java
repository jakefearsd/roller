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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SQLScriptRunner} is the install wizard's own SQL splitter -- a third
 * applier alongside {@code migrate.sh} (psql) and the test harness (whole-string
 * JDBC). Unlike those two, it splits accumulated lines on trailing semicolons,
 * so a {@code DO $$ ... $$;} block (V017's cluster-global {@code CREATE ROLE}
 * guard) would be corrupted into broken fragments unless the splitter tracks
 * dollar-quote state.
 *
 * <p>The comment-stripping and {@code runScript} tests (error reporting,
 * stop-on-error, the tolerated {@code drop index} failure) are characterisation
 * tests, written against the existing behaviour and expected to pass
 * immediately.
 */
class SQLScriptRunnerTest {

    @Test
    void aDollarQuotedDoBlockStaysOneStatement() throws Exception {
        String sql = """
                CREATE TABLE IF NOT EXISTS t1 (id int);
                DO $$ BEGIN
                    CREATE ROLE somerole;
                EXCEPTION WHEN duplicate_object THEN
                    NULL;
                END $$;
                CREATE TABLE IF NOT EXISTS t2 (id int);
                """;
        SQLScriptRunner runner = new SQLScriptRunner(
                new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));

        List<String> commands = runner.getCommands();

        assertEquals(3, commands.size(), "got: " + commands);
        assertTrue(commands.get(1).startsWith("DO $$"), commands.get(1));
        assertTrue(commands.get(1).contains("EXCEPTION WHEN duplicate_object"),
                "the block must survive intact: " + commands.get(1));
    }

    @Test
    void aTaggedDollarQuoteAlsoStaysOneStatement() throws Exception {
        String sql = "DO $guard$ BEGIN PERFORM 1; END $guard$;\nSELECT 1;";
        SQLScriptRunner runner = new SQLScriptRunner(
                new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, runner.getCommands().size(),
                "got: " + runner.getCommands());
    }

    /**
     * A single, unpaired "$" -- as in a price literal -- must never be mistaken
     * for a dollar-quote delimiter: the delimiter regex requires a MATCHING pair
     * of "$"s, so one lone "$" can never open a quote and strand every statement
     * after it as "still inside a block".
     */
    @Test
    void aLoneDollarInAStringLiteralDoesNotOpenAQuote() throws Exception {
        String sql = "INSERT INTO price (amount) VALUES ('$5');\nSELECT 1;";
        SQLScriptRunner runner = new SQLScriptRunner(
                new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, runner.getCommands().size(),
                "got: " + runner.getCommands());
    }

    private static SQLScriptRunner runnerFor(String sql) throws Exception {
        return new SQLScriptRunner(new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * A connection whose statements record what they execute and fail any
     * command containing {@code BAD} or {@code drop index} with {@code failure}.
     */
    private static Connection recordingConnection(List<String> executed, SQLException failure)
            throws SQLException {
        Connection con = mock(Connection.class);
        when(con.getAutoCommit()).thenReturn(true);
        when(con.createStatement()).thenAnswer(invocation -> {
            Statement st = mock(Statement.class);
            when(st.executeUpdate(anyString())).thenAnswer(call -> {
                String command = call.getArgument(0);
                if (command.contains("BAD") || command.contains("drop index")) {
                    throw failure;
                }
                executed.add(command);
                return 0;
            });
            return st;
        });
        return con;
    }

    @Test
    void aTrailingCommentIsStrippedAndWhitespaceCollapsed() throws Exception {
        SQLScriptRunner runner = runnerFor("""
                -- a whole-line comment is dropped
                CREATE TABLE t (id   int,    -- the key
                    name text);  -- trailing note
                """);

        assertEquals(List.of("CREATE TABLE t (id int, name text)"), runner.getCommands());
    }

    @Test
    void withoutStopOnErrorAFailureIsReportedAndTheScriptContinues() throws Exception {
        List<String> executed = new ArrayList<>();
        SQLScriptRunner runner = runnerFor("SELECT 1;\nBAD STATEMENT;\nSELECT 2;");

        runner.runScript(recordingConnection(executed, new SQLException("syntax error at BAD")), false);

        assertEquals(List.of("SELECT 1", "SELECT 2"), executed);
        assertFalse(runner.getFailed());
        List<String> messages = runner.getMessages();
        assertEquals(4, messages.size(), messages.toString());
        assertEquals("SELECT 1", messages.get(0));
        assertEquals("ERROR: SQLException executing SQL [BAD STATEMENT] : syntax error at BAD",
                messages.get(1));
        assertTrue(messages.get(2).startsWith("java.sql.SQLException: syntax error at BAD"),
                "the stack trace follows the error line: " + messages.get(2));
        assertEquals("SELECT 2", messages.get(3));
    }

    @Test
    void withStopOnErrorTheFirstFailureIsRethrownAndNothingAfterItRuns() throws Exception {
        List<String> executed = new ArrayList<>();
        SQLException failure = new SQLException("syntax error at BAD");
        SQLScriptRunner runner = runnerFor("SELECT 1;\nBAD STATEMENT;\nSELECT 2;");

        SQLException thrown = assertThrows(SQLException.class,
                () -> runner.runScript(recordingConnection(executed, failure), true));

        assertSame(failure, thrown);
        assertTrue(runner.getFailed());
        assertEquals(List.of("SELECT 1"), executed);
    }

    @Test
    void aFailedDropIndexIsToleratedEvenWhenStoppingOnError() throws Exception {
        List<String> executed = new ArrayList<>();
        SQLScriptRunner runner = runnerFor("alter table t drop index ix_t;\nSELECT 2;");

        runner.runScript(recordingConnection(executed, new SQLException("no such index")), true);

        assertFalse(runner.getFailed());
        assertEquals(List.of("SELECT 2"), executed);
        assertEquals(List.of("INFO: SQL command [alter table t drop index ix_t] failed, ignored.", "SELECT 2"),
                runner.getMessages());
    }

    @Test
    void replacedCommandsAreWhatRuns() throws Exception {
        List<String> executed = new ArrayList<>();
        SQLScriptRunner runner = runnerFor("SELECT 1;");

        runner.setCommands(List.of("SELECT 42", "SELECT 43"));
        runner.runScript(recordingConnection(executed, new SQLException("unused")), true);

        assertEquals(2, runner.getCommandCount());
        assertEquals(List.of("SELECT 42", "SELECT 43"), executed);
    }
}
