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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.roller.testing.RollerPostgresContainer;
import org.apache.roller.testing.ScratchDatabase;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.ui.core.RollerContext;
import org.apache.roller.weblogger.ui.core.security.RollerUserDetailsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

/**
 * Runs the SHIPPED deploy/admin-account.sh inside the suite's PostgreSQL
 * container (which has bash and psql 18, as the app image does), then
 * proves the result through the app's real login path:
 * RollerUserDetailsService + RollerContext.createPasswordEncoder() behind
 * a DaoAuthenticationProvider, wired as SecurityConfig wires them.
 * Spec: docs/superpowers/specs/2026-10-10-admin-bootstrap-and-recovery-design.md
 */
class AdminAccountScriptTest {

    private static final Path SCRIPT = Paths.get("../deploy/admin-account.sh");
    private static final String IN_CONTAINER = "/tmp/admin-account.sh";
    private static final String GOOD_PASSWORD = "correct horse battery";
    private final List<String> created = new ArrayList<>();

    record Run(int exit, String stdout, String stderr) {}

    @BeforeAll
    static void up() throws Exception {
        TestUtils.setupWeblogger();
        var pg = RollerPostgresContainer.get();
        pg.copyFileToContainer(MountableFile.forHostPath(SCRIPT, 0755), IN_CONTAINER);
        // A psql stand-in that records its argv, then runs the real psql:
        // acceptance criterion 3 (the password is in no psql argument).
        String shim = """
                #!/bin/bash
                printf '%s\\n' "$@" >> /tmp/shim/argv.log
                real=$(PATH=${PATH#/tmp/shim:} command -v psql)
                exec "$real" "$@"
                """;
        pg.copyFileToContainer(Transferable.of(shim.getBytes(StandardCharsets.UTF_8), 0755),
                "/tmp/shim/psql");
    }

    @AfterEach
    void deleteCreatedAccounts() throws Exception {
        try (Connection c = DriverManager.getConnection(RollerPostgresContainer.getJdbcUrl(),
                RollerPostgresContainer.getUsername(), RollerPostgresContainer.getPassword())) {
            for (String name : created) {
                try (PreparedStatement r = c.prepareStatement("DELETE FROM userrole WHERE username = ?");
                     PreparedStatement u = c.prepareStatement("DELETE FROM roller_user WHERE username = ?")) {
                    r.setString(1, name); r.executeUpdate();
                    u.setString(1, name); u.executeUpdate();
                }
            }
        }
    }

    static String suiteDb() {
        return RollerPostgresContainer.get().getDatabaseName();
    }

    String uniqueName() {
        String name = "tool" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        created.add(name);
        return name;
    }

    /** stdin comes from a file, so the password is on no command line here either. */
    static Run run(String db, String stdin, String... args) throws Exception {
        var pg = RollerPostgresContainer.get();
        String in = "/tmp/stdin-" + UUID.randomUUID();
        pg.copyFileToContainer(Transferable.of(stdin == null ? "" : stdin), in);
        StringBuilder cmd = new StringBuilder()
                .append("POSTGRES_USER=").append(q(pg.getUsername()))
                .append(" POSTGRES_PASSWORD=").append(q(pg.getPassword()))
                .append(" POSTGRES_DB=").append(q(db))
                .append(" PGHOST=localhost PATH=/tmp/shim:$PATH ").append(IN_CONTAINER);
        for (String a : args) {
            cmd.append(' ').append(q(a));
        }
        cmd.append(" < ").append(in);
        var r = pg.execInContainer("bash", "-c", cmd.toString());
        pg.execInContainer("rm", "-f", in);
        return new Run(r.getExitCode(), r.getStdout(), r.getStderr());
    }

    static String q(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    static Authentication login(String user, String password) {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider(
                new RollerUserDetailsService(TestUtils.provider()));
        p.setPasswordEncoder(RollerContext.createPasswordEncoder());
        return p.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(user, password));
    }

    static long accountsNamed(String name) throws Exception {
        try (Connection c = DriverManager.getConnection(RollerPostgresContainer.getJdbcUrl(),
                RollerPostgresContainer.getUsername(), RollerPostgresContainer.getPassword());
             PreparedStatement s = c.prepareStatement(
                     "SELECT (SELECT count(*) FROM roller_user WHERE lower(username) = lower(?))"
                             + " + (SELECT count(*) FROM userrole WHERE lower(username) = lower(?))")) {
            s.setString(1, name);
            s.setString(2, name);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // --- acceptance criterion 1 ---
    @Test
    void createMakesAnEnabledAdminTheRealLoginPathAccepts() throws Exception {
        String name = uniqueName();
        Run r = run(suiteDb(), GOOD_PASSWORD + "\n",
                "create", "--username", name, "--email", name + "@example.com", "--password-stdin");
        assertEquals(0, r.exit(), r.stderr());
        Authentication a = login(name, GOOD_PASSWORD);
        // Spring Security 7 adds a FACTOR_PASSWORD authority to a password
        // login; the account's own roles are what is under test.
        assertEquals(Set.of("admin", "editor"), a.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(role -> !role.startsWith("FACTOR_"))
                .collect(Collectors.toSet()));
    }

    // --- Review Focus 1 ---
    @Test
    void passwordIsStoredExactlyAsTyped() throws Exception {
        String name = uniqueName();
        String tricky = "  it's a \"p@ss\" $HOME \\ über \t ";
        Run r = run(suiteDb(), tricky + "\n",
                "create", "--username", name, "--email", name + "@example.com", "--password-stdin");
        assertEquals(0, r.exit(), r.stderr());
        assertNotNull(login(name, tricky));
        assertThrows(BadCredentialsException.class, () -> login(name, tricky.strip()));
    }

    // --- prompt mode: two lines, which must match ---
    @Test
    void promptModeCreatesWhenBothEntriesMatch() throws Exception {
        String name = uniqueName();
        Run r = run(suiteDb(), GOOD_PASSWORD + "\n" + GOOD_PASSWORD + "\n",
                "create", "--username", name, "--email", name + "@example.com");
        assertEquals(0, r.exit(), r.stderr());
        assertNotNull(login(name, GOOD_PASSWORD));
    }

    // --- acceptance criterion 2: every refusal writes nothing ---
    @Test
    void refusalsWriteNothing() throws Exception {
        String existing = uniqueName();
        assertEquals(0, run(suiteDb(), GOOD_PASSWORD + "\n", "create", "--username", existing,
                "--email", existing + "@example.com", "--password-stdin").exit());
        long before = accountsNamed(existing);

        String fresh = uniqueName();
        record Case(String why, String stdin, String user, String email, boolean fromStdin) {}
        List<Case> cases = List.of(
                new Case("existing username, other case", GOOD_PASSWORD + "\n",
                        existing.toUpperCase(Locale.ROOT), "x@example.com", true),
                new Case("bad username character", GOOD_PASSWORD + "\n", fresh + "-x", "x@example.com", true),
                new Case("malformed email", GOOD_PASSWORD + "\n", fresh, "not-an-email", true),
                new Case("7-character password", "1234567\n", fresh, "x@example.com", true),
                new Case("confirmation differs", GOOD_PASSWORD + "\nsomething else\n", fresh, "x@example.com", false),
                new Case("empty stdin", "", fresh, "x@example.com", true));
        for (Case c : cases) {
            List<String> args = new ArrayList<>(List.of("create", "--username", c.user(), "--email", c.email()));
            if (c.fromStdin()) {
                args.add("--password-stdin");
            }
            Run r = run(suiteDb(), c.stdin(), args.toArray(String[]::new));
            assertEquals(1, r.exit(), c.why() + ": " + r.stderr());
            assertFalse(r.stderr().isBlank(), c.why() + " must say what was wrong");
            assertEquals(0, accountsNamed(fresh), c.why() + " wrote rows");
        }
        assertEquals(before, accountsNamed(existing), "the existing account was touched");
    }

    @Test
    void anExistingDisabledAccountAlsoBlocksCreate() throws Exception {
        String name = uniqueName();
        assertEquals(0, run(suiteDb(), GOOD_PASSWORD + "\n", "create", "--username", name,
                "--email", name + "@example.com", "--password-stdin").exit());
        try (Connection c = DriverManager.getConnection(RollerPostgresContainer.getJdbcUrl(),
                RollerPostgresContainer.getUsername(), RollerPostgresContainer.getPassword());
             PreparedStatement s = c.prepareStatement("UPDATE roller_user SET isenabled = false WHERE username = ?")) {
            s.setString(1, name);
            s.executeUpdate();
        }
        Run r = run(suiteDb(), GOOD_PASSWORD + "\n", "create", "--username", name,
                "--email", name + "@example.com", "--password-stdin");
        assertEquals(1, r.exit());
        assertTrue(r.stderr().contains("already exists"), r.stderr());
    }

    // --- acceptance criterion 3 ---
    @Test
    void thePasswordAppearsInNoOutputAndNoPsqlArgument() throws Exception {
        RollerPostgresContainer.get().execInContainer("rm", "-f", "/tmp/shim/argv.log");
        String name = uniqueName();
        String secret = "s3cret-" + UUID.randomUUID();
        Run r = run(suiteDb(), secret + "\n" + secret + "\n",
                "create", "--username", name, "--email", name + "@example.com");
        assertEquals(0, r.exit(), r.stderr());
        String argv = RollerPostgresContainer.get().execInContainer("cat", "/tmp/shim/argv.log").getStdout();
        assertFalse(argv.isBlank(), "the shim saw no psql call: is psql resolved through PATH?");
        assertFalse(argv.contains(secret), "password passed to psql as an argument");
        assertFalse(r.stdout().contains(secret) || r.stderr().contains(secret), "password printed");
    }

    // --- Review Focus 3 ---
    @Test
    void createOnAnEmptyDatabaseExplainsTheSchemaIsMissing() throws Exception {
        String db = "admintool_empty";
        ScratchDatabase.empty(db);
        try {
            Run r = run(db, GOOD_PASSWORD + "\n", "create", "--username", "someone",
                    "--email", "someone@example.com", "--password-stdin");
            assertEquals(1, r.exit());
            assertTrue(r.stderr().contains("schema is not installed"), r.stderr());
        } finally {
            ScratchDatabase.drop(db);
        }
    }

    // --- Review Focus 4 ---
    @Test
    void aFlagWithoutItsValueIsAUsageError() throws Exception {
        for (String[] args : List.of(new String[] {"create", "--username"},
                new String[] {"create", "--bogus"}, new String[] {}, new String[] {"frobnicate"},
                new String[] {"create", "--username", "x"})) {
            Run r = run(suiteDb(), "", args);
            assertEquals(2, r.exit(), String.join(" ", args) + ": " + r.stderr());
            assertTrue(r.stderr().contains("usage:"), r.stderr());
        }
    }
}
