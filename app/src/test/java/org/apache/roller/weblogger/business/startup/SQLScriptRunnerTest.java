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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SQLScriptRunner} is the install wizard's own SQL splitter -- a third
 * applier alongside {@code migrate.sh} (psql) and the test harness (whole-string
 * JDBC). Unlike those two, it splits accumulated lines on trailing semicolons,
 * so a {@code DO $$ ... $$;} block (V017's cluster-global {@code CREATE ROLE}
 * guard) would be corrupted into broken fragments unless the splitter tracks
 * dollar-quote state.
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
}
