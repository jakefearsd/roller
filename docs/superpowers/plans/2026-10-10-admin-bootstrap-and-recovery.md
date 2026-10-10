# First-admin bootstrap and account recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the person who controls a production host a way to create
the first Roller admin and recover a lost password: a profile-gated
`admin` compose service running `deploy/admin-account.sh`. Document it,
and the emailed reset, in the repo and on the wiki.

**Architecture:** A bash script in the app image runs `create`,
`reset-password` and `status` against PostgreSQL with `psql`. Hashing
happens inside the database (pgcrypto bcrypt). The password reaches psql
only through its environment (`\getenv`). The script's tests run the
shipped file inside the suite's PostgreSQL container, then log in through
the app's real Spring Security components. `deploy.sh` calls `status` and
prints the `create` command when there is no admin yet.

**Tech Stack:** bash, psql 18 (`\getenv` needs 15+), pgcrypto, JUnit 5,
Testcontainers (the suite's `RollerPostgresContainer`), Spring Security's
`DaoAuthenticationProvider`, SnakeYAML (`ProductionComposeTest`), and the
Wikantik admin MCP (JSON-RPC over HTTP).

**Spec:** `docs/superpowers/specs/2026-10-10-admin-bootstrap-and-recovery-design.md`
(D1–D7, acceptance criteria 1–10). Read it before starting any task.

## Global Constraints

- **Build serialisation.** Never run two Maven builds at once in this
  tree. Before every `mvn`, wait inline for the busy check:
  `while pgrep -f "[s]urefirebooter.*source/roller" >/dev/null; do sleep 10; done; mvn ...`
- **TDD.** Write each test, run it, and watch it fail for the expected
  reason before writing code.
- **Commits.** Commit each task locally, ending the message with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
  **No agent ever pushes.**
- **Validation values.** Username `^[A-Za-z0-9]+$`, at most 255
  characters. Email `^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$`.
  Password at least 8 characters.
- **Written rows.** Roles are `admin` and `editor`. `locale=en_US`,
  `timezone=UTC`, `isenabled=true`. `screenname` and `fullname` are the
  username. The hash is `'{bcrypt}' || crypt(pw, gen_salt('bf', 10))`.
- **Exit codes.** 0 = done. 1 = refused, or a database error. 2 = usage
  error. 3 = `status` found no enabled admin.
- **The password** is never an argument, never printed, and never in any
  `psql` argv. It goes `IFS= read -r`, then the environment variable
  `ROLLER_ADMIN_PASSWORD`, then psql's `\getenv pw ROLLER_ADMIN_PASSWORD`,
  then `:'pw'`.
- **psql stdin.** Every psql call takes its SQL from a heredoc, never
  `-c`: psql does not substitute `-v` variables inside `-c`. A heredoc
  also keeps psql from consuming the script's stdin, where the password
  is.
- **Test layout.** Tests run from `app/`, so repo files are `../deploy/…`,
  `../docker-compose.prod.yml`, `../docker_deployment.md`.
- **CPD at 110 tokens covers test code.** Do not paste a third copy of
  the scratch-database helpers (see Task 1).

## Review Focus

These five inputs are implied by the spec but no acceptance criterion
tests them. Each has a test in the task that owns it.

1. **A password with quotes, backslashes, `$`, non-ASCII, or leading and
   trailing spaces.** It must authenticate exactly as typed. The danger
   is `read` without `IFS=` trimming spaces, or shell interpolation into
   SQL. *Task 1, `passwordIsStoredExactlyAsTyped`.*
2. **A reset while the app is running.** The operator expects the new
   password to work, but the app's account cache keeps the old one until
   a restart. The tool must say so. *Task 2,
   `resetEndsWithTheRestartCommand` and
   `aRunningTierKeepsTheOldPasswordUntilRestart`.*
3. **Running the tool before `deploy.sh` has installed the schema.** It
   must give a plain message, not a psql error.
   *Task 1, `createOnAnEmptyDatabaseExplainsTheSchemaIsMissing`.*
4. **A flag without its value, or an unknown flag**, such as
   `create --username`. It must print usage and exit 2, not crash on an
   unbound variable. *Task 1, `aFlagWithoutItsValueIsAUsageError`.*
5. **Admins that exist but are all disabled.** `status` must count only
   enabled admins: exit 3 if every admin is disabled.
   *Task 2, `statusReportsEnabledAdminsOnly`.*

---

### Task 1: `admin-account.sh` — `create` and validation

**Files:**
- Create: `deploy/admin-account.sh`
- Create: `app/src/test/java/org/apache/roller/testing/ScratchDatabase.java`
- Modify: `app/src/test/java/org/apache/roller/weblogger/db/DevSeedTest.java`
  (lines ~180–215: use `ScratchDatabase`)
- Modify: `app/src/test/java/org/apache/roller/weblogger/business/startup/AnalyticsContractTest.java`
  (lines ~265–312: use `ScratchDatabase`)
- Test: `app/src/test/java/org/apache/roller/weblogger/business/startup/AdminAccountScriptTest.java`

**Interfaces:**
- Produces: the script's CLI, `create --username U --email E
  [--password-stdin]`, with exit codes as in Global Constraints. Its usage
  block lists one `admin-account.sh <subcommand>` per line (Task 5
  parses it).
- Produces: `org.apache.roller.testing.ScratchDatabase`:
  - `static Connection migrated(String name) throws Exception`: drops,
    creates, applies `MigrationFiles.all()` with `:app_user` replaced,
    and returns an open connection.
  - `static void empty(String name) throws Exception`: drops and creates
    the database with no schema.
  - `static void drop(String name) throws Exception`.
  - `static String jdbcUrl(String name)`.
- Produces: the `AdminAccountScriptTest` helpers that Task 2 extends:
  - `Run run(String db, String stdin, String... args)`, where
    `record Run(int exit, String stdout, String stderr)`;
  - `Authentication login(String user, String password)`;
  - `String uniqueName()`;
  - `String suiteDb()`.

- [ ] **Step 1: Extract `ScratchDatabase` (characterisation refactor)**

`DevSeedTest` and `AnalyticsContractTest` each have private
`freshDatabase`, `dropDatabase`, `adminConnection` and `jdbcUrlFor`
helpers. Move one copy into `org.apache.roller.testing.ScratchDatabase`
with the API above. Keep the admin connection that `adminConnection()`
builds today (read both copies first). Switch both classes to the
fixture. `DevSeedTest` keeps its own
`CREATE EXTENSION IF NOT EXISTS pgcrypto` after `migrated(...)`. Javadoc
the class:

```java
/**
 * Throwaway databases in the suite's PostgreSQL container, for tests that
 * must not share the business tier's database (an empty schema, a
 * migration-only schema, a count that other test classes could disturb).
 * Extracted from DevSeedTest and AnalyticsContractTest, which carried
 * identical private copies; their tests passing unchanged is the
 * characterisation that the move altered nothing.
 */
```

Run: `mvn -pl app test -Dtest='DevSeedTest,AnalyticsContractTest'`.
Expected: PASS before and after the move.

- [ ] **Step 2: Write the failing tests**

Create `AdminAccountScriptTest`. Testcontainers in this repo may be 2.x,
so check `RollerPostgresContainer`'s imports for the container class and
package. `Transferable` is `org.testcontainers.images.builder.Transferable`.

```java
package org.apache.roller.weblogger.business.startup;

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
        assertEquals(Set.of("admin", "editor"), a.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet()));
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
```

- [ ] **Step 3: Run and watch them fail**

Run: `mvn -pl app test -Dtest=AdminAccountScriptTest`.
Expected: everything fails, because `../deploy/admin-account.sh` does
not exist (`MountableFile` throws in `@BeforeAll`).

- [ ] **Step 4: Write `deploy/admin-account.sh`**

```bash
#!/usr/bin/env bash
#
# Roller admin accounts, for whoever controls the host:
#
#   C="docker compose -f docker-compose.prod.yml"
#   $C run --rm admin create --username NAME --email ADDRESS
#
# create prompts for the password twice. With --password-stdin it reads
# exactly one line from stdin instead (use `run --rm -T`).
#
# Trust model: running this at all means controlling the host and its
# .env, which already holds every database credential. Nothing reachable
# over HTTP can do what this does. See
# docs/superpowers/specs/2026-10-10-admin-bootstrap-and-recovery-design.md.
#
# The password is never an argument and is never printed. It reaches psql
# only through psql's environment (\getenv), and psql quotes it itself.
# Every psql call reads its SQL from a heredoc: -c would not substitute -v
# variables, and the heredoc stops psql eating this script's stdin, where
# the password is.
set -euo pipefail

export PGHOST="${PGHOST:-postgres}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${POSTGRES_USER:?POSTGRES_USER must be set}"
export PGPASSWORD="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set}"
export PGDATABASE="${POSTGRES_DB:-rollerdb}"

MIN_PASSWORD_LENGTH=8
USERNAME_RE='^[A-Za-z0-9]+$'
EMAIL_RE='^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'

usage() {
    cat >&2 <<'EOF'
usage: admin-account.sh create --username NAME --email ADDRESS [--password-stdin]
EOF
    exit 2
}

die() {
    echo "admin-account: $*" >&2
    exit 1
}

# sql [psql -v args...] <<'SQL' ... -- unaligned, tuples only, stops on error
sql() {
    psql -X -q -v ON_ERROR_STOP=1 -tA "$@"
}

require_schema() {
    local present
    present=$(sql <<'SQL'
SELECT to_regclass('public.roller_user') IS NOT NULL;
SQL
) || die "cannot reach database ${PGDATABASE} on ${PGHOST}:${PGPORT}"
    [[ "${present}" == t ]] \
        || die "the Roller schema is not installed in ${PGDATABASE}: run deploy.sh first"
}

# Sets PASSWORD. IFS= keeps leading/trailing spaces; -r keeps backslashes.
read_password() {
    local confirm=""
    PASSWORD=""
    if [[ "${FROM_STDIN}" -eq 1 ]]; then
        IFS= read -r PASSWORD || true
        [[ -n "${PASSWORD}" ]] || die "no password on stdin"
    else
        IFS= read -r -s -p "Password: " PASSWORD || true
        if [[ -t 0 ]]; then echo >&2; fi
        IFS= read -r -s -p "Repeat the password: " confirm || true
        if [[ -t 0 ]]; then echo >&2; fi
        [[ "${PASSWORD}" == "${confirm}" ]] || die "the two passwords do not match"
    fi
    (( ${#PASSWORD} >= MIN_PASSWORD_LENGTH )) \
        || die "the password must be at least ${MIN_PASSWORD_LENGTH} characters"
}

cmd_create() {
    [[ "${USERNAME}" =~ ${USERNAME_RE} && ${#USERNAME} -le 255 ]] \
        || die "username must be 1-255 letters and digits"
    [[ "${EMAIL}" =~ ${EMAIL_RE} ]] || die "not an email address: ${EMAIL}"
    require_schema
    local taken
    taken=$(sql -v username="${USERNAME}" <<'SQL'
SELECT count(*) FROM roller_user WHERE lower(username) = lower(:'username');
SQL
)
    [[ "${taken}" == 0 ]] || die "an account named ${USERNAME} already exists (perhaps disabled)"
    read_password
    ROLLER_ADMIN_PASSWORD="${PASSWORD}" sql -v username="${USERNAME}" -v email="${EMAIL}" >/dev/null <<'SQL'
\getenv pw ROLLER_ADMIN_PASSWORD
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
INSERT INTO roller_user (id, username, passphrase, screenname, fullname,
                         emailaddress, datecreated, locale, timezone, isenabled)
VALUES (gen_random_uuid()::text, :'username',
        '{bcrypt}' || crypt(:'pw', gen_salt('bf', 10)),
        :'username', :'username', :'email', now(), 'en_US', 'UTC', true);
INSERT INTO userrole (id, rolename, username)
VALUES (gen_random_uuid()::text, 'admin', :'username'),
       (gen_random_uuid()::text, 'editor', :'username');
COMMIT;
SQL
    echo "created admin account ${USERNAME} (roles: admin, editor)"
}

COMMAND="${1:-}"
[[ -n "${COMMAND}" ]] || usage
shift
USERNAME="" EMAIL="" FROM_STDIN=0
while (( $# > 0 )); do
    case "$1" in
        --username) (( $# >= 2 )) || usage; USERNAME="$2"; shift 2 ;;
        --email)    (( $# >= 2 )) || usage; EMAIL="$2"; shift 2 ;;
        --password-stdin) FROM_STDIN=1; shift ;;
        *) usage ;;
    esac
done

case "${COMMAND}" in
    create)
        [[ -n "${USERNAME}" && -n "${EMAIL}" ]] || usage
        cmd_create ;;
    *) usage ;;
esac
```

`chmod 755 deploy/admin-account.sh`. Also `git update-index --chmod=+x`
once it is added.

- [ ] **Step 5: Run the tests until they pass**

Run: `mvn -pl app test -Dtest='AdminAccountScriptTest,DevSeedTest,AnalyticsContractTest'`.
Expected: PASS.

Two things are worth checking first if a case fails:
- Prompt mode with non-TTY stdin reads two lines. `read -p` shows no
  prompt when stdin isn't a terminal, which is fine.
- The `$HOME` in the tricky password must arrive literally. If it
  doesn't, something is interpolating it.

- [ ] **Step 6: Commit**

```bash
git add deploy/admin-account.sh app/src/test/java/org/apache/roller/testing/ScratchDatabase.java \
  app/src/test/java/org/apache/roller/weblogger/db/DevSeedTest.java \
  app/src/test/java/org/apache/roller/weblogger/business/startup/AnalyticsContractTest.java \
  app/src/test/java/org/apache/roller/weblogger/business/startup/AdminAccountScriptTest.java
git update-index --chmod=+x deploy/admin-account.sh
git commit -m "feat(deploy): admin-account.sh create -- the installer makes the first admin

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `status` and `reset-password`

**Files:**
- Modify: `deploy/admin-account.sh` (usage, `cmd_status`, `cmd_reset`, dispatch)
- Test: `app/src/test/java/org/apache/roller/weblogger/business/startup/AdminAccountScriptTest.java`

**Interfaces:**
- Consumes: the Task 1 helpers `run`, `login`, `uniqueName`, `suiteDb`
  and `ScratchDatabase.migrated`/`drop`.
- Produces: `reset-password --username U [--password-stdin]`, which
  exits 0 and whose last stdout line is the restart command
  `docker compose -f docker-compose.prod.yml restart app`. `status`
  exits 0, or 3 when there is no enabled admin. The usage block gains
  both lines.

- [ ] **Step 1: Write the failing tests**, added to `AdminAccountScriptTest`:

```java
    private String createWith(String password) throws Exception {
        String name = uniqueName();
        Run r = run(suiteDb(), password + "\n", "create", "--username", name,
                "--email", name + "@example.com", "--password-stdin");
        assertEquals(0, r.exit(), r.stderr());
        return name;
    }

    // --- acceptance criterion 4 ---
    // The account is never logged in before the reset, so nothing has cached it.
    @Test
    void resetReplacesThePassword() throws Exception {
        String name = createWith(GOOD_PASSWORD);
        Run r = run(suiteDb(), "a brand new one\n", "reset-password", "--username", name, "--password-stdin");
        assertEquals(0, r.exit(), r.stderr());
        assertNotNull(login(name, "a brand new one"));
        assertThrows(BadCredentialsException.class, () -> login(name, GOOD_PASSWORD));
    }

    // --- Review Focus 2 ---
    @Test
    void resetEndsWithTheRestartCommand() throws Exception {
        String name = createWith(GOOD_PASSWORD);
        Run r = run(suiteDb(), "a brand new one\n", "reset-password", "--username", name, "--password-stdin");
        assertEquals(0, r.exit(), r.stderr());
        List<String> lines = r.stdout().strip().lines().toList();
        assertEquals("  docker compose -f docker-compose.prod.yml restart app", lines.get(lines.size() - 1));
    }

    /**
     * Why reset-password tells the operator to restart the app: a running
     * tier that has loaded an account keeps serving the password it loaded
     * (JPAUserManagerImpl.userNameToIdMap, then EclipseLink's shared cache
     * behind getUser). This pins that limitation. If it starts failing, the
     * cache went away, and the restart advice in admin-account.sh and the
     * runbook should go with it.
     */
    @Test
    void aRunningTierKeepsTheOldPasswordUntilRestart() throws Exception {
        String name = createWith(GOOD_PASSWORD);
        assertNotNull(login(name, GOOD_PASSWORD));   // the tier now holds the account
        assertEquals(0, run(suiteDb(), "a brand new one\n", "reset-password",
                "--username", name, "--password-stdin").exit());
        assertNotNull(login(name, GOOD_PASSWORD), "expected the cached hash to still be served");
    }

    @Test
    void resetOfAnUnknownAccountChangesNothing() throws Exception {
        String name = uniqueName();
        Run r = run(suiteDb(), "a brand new one\n", "reset-password", "--username", name, "--password-stdin");
        assertEquals(1, r.exit());
        assertTrue(r.stderr().contains("no account named"), r.stderr());
        assertEquals(0, accountsNamed(name));
    }

    @Test
    void resetOfADisabledAccountWarns() throws Exception {
        String name = createWith(GOOD_PASSWORD);
        try (Connection c = DriverManager.getConnection(RollerPostgresContainer.getJdbcUrl(),
                RollerPostgresContainer.getUsername(), RollerPostgresContainer.getPassword());
             PreparedStatement s = c.prepareStatement("UPDATE roller_user SET isenabled = false WHERE username = ?")) {
            s.setString(1, name);
            s.executeUpdate();
        }
        Run r = run(suiteDb(), "a brand new one\n", "reset-password", "--username", name, "--password-stdin");
        assertEquals(0, r.exit(), r.stderr());
        assertTrue(r.stderr().contains("disabled"), r.stderr());
    }

    // --- acceptance criterion 5 and Review Focus 5 (isolated database: the
    // suite's own may hold other tests' admins) ---
    @Test
    void statusReportsEnabledAdminsOnly() throws Exception {
        String db = "admintool_status";
        try (Connection c = ScratchDatabase.migrated(db)) {
            Run none = run(db, "", "status");
            assertEquals(3, none.exit(), none.stderr());
            assertTrue(none.stdout().contains("run --rm admin create"), none.stdout());

            assertEquals(0, run(db, GOOD_PASSWORD + "\n", "create", "--username", "alice",
                    "--email", "alice@example.com", "--password-stdin").exit());
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE roller_user SET isenabled = false WHERE username = 'alice'");
            }
            assertEquals(3, run(db, "", "status").exit(), "a disabled admin is no admin");

            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE roller_user SET isenabled = true WHERE username = 'alice'");
            }
            Run some = run(db, "", "status");
            assertEquals(0, some.exit(), some.stderr());
            assertTrue(some.stdout().contains("alice"), some.stdout());
        } finally {
            ScratchDatabase.drop(db);
        }
    }
```

- [ ] **Step 2: Run and watch them fail**

Run: `mvn -pl app test -Dtest=AdminAccountScriptTest`.
Expected: every new test fails with exit 2 (usage), because neither
`reset-password` nor `status` exists yet.

- [ ] **Step 3: Implement `status` and `reset-password`**

Add the usage lines, after the `create` line:

```
       admin-account.sh reset-password --username NAME [--password-stdin]
       admin-account.sh status
```

Add the functions:

```bash
cmd_status() {
    require_schema
    local admins
    admins=$(sql <<'SQL'
SELECT u.username FROM roller_user u
 WHERE u.isenabled
   AND EXISTS (SELECT 1 FROM userrole r
                WHERE r.username = u.username AND r.rolename = 'admin')
 ORDER BY u.username;
SQL
)
    if [[ -z "${admins}" ]]; then
        echo "no enabled admin account. Create one with:"
        echo "  docker compose -f docker-compose.prod.yml run --rm admin create --username NAME --email ADDRESS"
        exit 3
    fi
    echo "enabled admin accounts:"
    sed 's/^/  /' <<<"${admins}"
}

cmd_reset() {
    require_schema
    local enabled
    enabled=$(sql -v username="${USERNAME}" <<'SQL'
SELECT isenabled FROM roller_user WHERE username = :'username';
SQL
)
    [[ -n "${enabled}" ]] || die "no account named ${USERNAME}"
    read_password
    ROLLER_ADMIN_PASSWORD="${PASSWORD}" sql -v username="${USERNAME}" >/dev/null <<'SQL'
\getenv pw ROLLER_ADMIN_PASSWORD
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
UPDATE roller_user SET passphrase = '{bcrypt}' || crypt(:'pw', gen_salt('bf', 10))
 WHERE username = :'username';
COMMIT;
SQL
    if [[ "${enabled}" != t ]]; then
        echo "admin-account: warning: ${USERNAME} is disabled and still cannot log in;" \
             "enable it from another admin account, or create a new admin" >&2
    fi
    echo "password reset for ${USERNAME}."
    echo "The running app caches accounts it has loaded; restart it so the new password takes effect:"
    echo "  docker compose -f docker-compose.prod.yml restart app"
}
```

Add the dispatch arms, before `*) usage ;;`:

```bash
    reset-password)
        [[ -n "${USERNAME}" && -z "${EMAIL}" ]] || usage
        cmd_reset ;;
    status)
        [[ -z "${USERNAME}${EMAIL}" && "${FROM_STDIN}" -eq 0 ]] || usage
        cmd_status ;;
```

Update the header comment's usage lines to match.

- [ ] **Step 4: Run until green**

Run: `mvn -pl app test -Dtest=AdminAccountScriptTest`. Expected: PASS.

- [ ] **Step 5: Commit**

Message: `feat(deploy): admin-account.sh status and reset-password`
plus the attribution line.

---

### Task 3: The `admin` compose service and the image

**Files:**
- Modify: `docker-compose.prod.yml` (add the `admin` service after `provision`)
- Modify: `Dockerfile` (COPY plus chmod)
- Test: `app/src/test/java/org/apache/roller/weblogger/business/startup/ProductionComposeTest.java`

**Interfaces:**
- Consumes: `/app/admin-account.sh` (Task 1).
- Produces: compose service `admin`. Tasks 4 and 5 invoke it as
  `docker compose -f docker-compose.prod.yml run --rm [-T] admin <subcommand>`.

- [ ] **Step 1: Write the failing test**, added to `ProductionComposeTest`:

```java
    @Test
    @SuppressWarnings("unchecked")
    void theAdminToolIsProfileGatedAndReachesOnlyTheDatabase() {
        Map<String, Object> admin = service("admin");
        assertEquals(List.of("tools"), admin.get("profiles"),
                "admin must never start with `up`: only `docker compose run` may start it");
        assertEquals(service("app").get("image"), admin.get("image"),
                "the tool must ship in the very image the app runs");
        assertEquals(List.of("/app/admin-account.sh"), admin.get("entrypoint"));
        assertEquals("no", admin.get("restart"));
        assertNull(admin.get("ports"), "the admin tool must publish nothing");
        assertNull(admin.get("env_file"),
                "env_file would hand the tool every secret in .env; it needs the database's only");
        assertNull(admin.get("volumes"));
        assertEquals(List.of("internal"), admin.get("networks"));
        Map<String, Object> env = (Map<String, Object>) admin.get("environment");
        assertEquals(Set.of("POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD"), env.keySet());
        Map<String, Object> deps = (Map<String, Object>) admin.get("depends_on");
        assertEquals(Map.of("condition", "service_healthy"), deps.get("postgres"));
    }
```

The existing `everyEntrypointPathIsActuallyBakedIntoTheImage` covers the
Dockerfile automatically once the service exists.

- [ ] **Step 2: Run and watch it fail**

Run: `mvn -pl app test -Dtest=ProductionComposeTest`.
Expected: the new test fails, because `service("admin")` is null.

- [ ] **Step 3: Implement**

In `docker-compose.prod.yml`, after `provision`:

```yaml
  # The installer's account tool: create the first admin, reset a lost
  # password, or check that an admin exists. Never started by `up` -- only
  #   docker compose -f docker-compose.prod.yml run --rm admin create --username NAME --email ADDRESS
  # Being able to run it at all means controlling this host, which is the
  # trust it relies on. It gets the database credentials and nothing else
  # from .env. See docker_deployment.md, "First run".
  admin:
    image: ghcr.io/jakefearsd/roller:${IMAGE_VERSION:?IMAGE_VERSION must be set in .env}
    entrypoint: ["/app/admin-account.sh"]
    profiles: ["tools"]
    restart: "no"
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-rollerdb}
      POSTGRES_USER: ${POSTGRES_USER:-roller}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set in .env}
    networks:
      - internal
```

In `Dockerfile`, next to `COPY deploy/provision.sh /app/provision.sh`:

```dockerfile
COPY deploy/admin-account.sh /app/admin-account.sh
```

Then add `/app/admin-account.sh` to the existing `chmod 755` list.

- [ ] **Step 4: Run until green**

Run: `mvn -pl app test -Dtest=ProductionComposeTest`. Expected: PASS.

Then validate the compose file, with no build:

```bash
S=$(mktemp -d); cp docker-compose.prod.yml "$S"/; cp deploy/.env.example "$S"/.env
sed -i 's/^#\?IMAGE_VERSION=.*/IMAGE_VERSION=0.0.0/; s/^#\?POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=x/' "$S"/.env
(cd "$S" && docker compose -f docker-compose.prod.yml config --services --profile tools | grep -x admin)
```

Expected output: `admin`. Without `--profile tools`, `admin` must be
absent from `config --services`.

- [ ] **Step 5: Commit**

Message: `feat(deploy): profile-gated admin service runs admin-account.sh`
plus the attribution line.

---

### Task 4: `deploy.sh` points at the tool

**Files:**
- Modify: `deploy/deploy.sh` (after the health wait, before `--prune`)
- Test: `app/src/test/java/org/apache/roller/weblogger/business/startup/DeployScriptTest.java`

**Interfaces:**
- Consumes: `admin status`, which exits 0 or 3 (Task 2), and the service
  `admin` (Task 3).

- [ ] **Step 1: Write the failing test**

```java
package org.apache.roller.weblogger.business.startup;

/**
 * Runs the shipped deploy/deploy.sh against a stub `docker` on PATH (no
 * daemon involved): the stub succeeds at everything, answers
 * `compose ... run --rm -T admin status` with $STUB_STATUS_RC, and records
 * every call. Spec acceptance criterion 7.
 */
class DeployScriptTest {

    private static final Path DEPLOY = Paths.get("../deploy/deploy.sh").toAbsolutePath();

    @TempDir Path dir;

    private record Result(int exit, String out, String calls) {}

    private Result deploy(int statusRc) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path stub = bin.resolve("docker");
        Files.writeString(stub, """
                #!/bin/bash
                echo "$*" >> "$STUB_LOG"
                case "$*" in
                  *" run --rm -T admin status"*) exit "$STUB_STATUS_RC" ;;
                esac
                exit 0
                """);
        stub.toFile().setExecutable(true);
        Files.writeString(dir.resolve("docker-compose.prod.yml"), "");
        Files.writeString(dir.resolve(".env"), "");
        ProcessBuilder pb = new ProcessBuilder("bash", DEPLOY.toString()).directory(dir.toFile())
                .redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("STUB_LOG", dir.resolve("calls.log").toString());
        pb.environment().put("STUB_STATUS_RC", String.valueOf(statusRc));
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "deploy.sh hung");
        return new Result(p.exitValue(), out, Files.readString(dir.resolve("calls.log")));
    }

    @Test
    void noAdminYetPrintsTheCreateCommandAndStillSucceeds() throws Exception {
        Result r = deploy(3);
        assertEquals(0, r.exit(), r.out());
        assertTrue(r.out().contains(
                "docker compose -f docker-compose.prod.yml run --rm admin create --username NAME --email ADDRESS"),
                r.out());
        assertTrue(r.calls().contains("compose -f docker-compose.prod.yml run --rm -T admin status"), r.calls());
    }

    @Test
    void anExistingAdminPrintsNoHint() throws Exception {
        Result r = deploy(0);
        assertEquals(0, r.exit(), r.out());
        assertFalse(r.out().contains("admin create"), r.out());
    }

    @Test
    void aFailedCheckWarnsButDoesNotFailTheDeploy() throws Exception {
        Result r = deploy(1);
        assertEquals(0, r.exit(), r.out());
        assertTrue(r.out().contains("warning: could not check for an admin account"), r.out());
    }
}
```

- [ ] **Step 2: Run and watch it fail**

Run: `mvn -pl app test -Dtest=DeployScriptTest`.
Expected: `noAdminYet…` and `aFailedCheck…` fail, because deploy.sh never
calls `admin status`. `anExistingAdminPrintsNoHint` passes, which is
expected: it guards against the hint being printed unconditionally.

- [ ] **Step 3: Implement**, in `deploy/deploy.sh` right after
`echo "    app healthy."`:

```bash
# A fresh install has no admin account, and there is no web page to make
# one (deliberately: see docker_deployment.md, "First run"). Point the
# operator at the tool rather than leave them at a login page they cannot
# pass. A failed check must never fail an otherwise good deploy.
echo "==> Checking for an admin account..."
admin_rc=0
"${COMPOSE[@]}" run --rm -T admin status </dev/null || admin_rc=$?
case "${admin_rc}" in
    0) ;;
    3)
        echo "    No admin account yet. Create the first one (it prompts for the password):"
        echo "      ${COMPOSE[*]} run --rm admin create --username NAME --email ADDRESS"
        ;;
    *)
        echo "    warning: could not check for an admin account (exit ${admin_rc});" \
             "try: ${COMPOSE[*]} run --rm admin status" >&2
        ;;
esac
```

Also update the script's header comment, if it lists steps, to mention
the check.

- [ ] **Step 4: Run until green**

Run: `mvn -pl app test -Dtest=DeployScriptTest`. Expected: PASS.

- [ ] **Step 5: Commit**

Message: `feat(deploy): deploy.sh tells a fresh install how to make its admin`
plus the attribution line.

---

### Task 5: Repository documentation

**Files:**
- Modify: `docker_deployment.md` (First run, Mail, Troubleshooting)
- Modify: `deploy/.env.example` (the mail block, around lines 136–146)
- Modify: `docs/dev/passwords.md` (new section "The admin account tool")
- Test: `app/src/test/java/org/apache/roller/weblogger/business/startup/AdminRunbookTest.java`

**Interfaces:**
- Consumes: the usage block of `deploy/admin-account.sh`, one
  `admin-account.sh <subcommand>` per line (Tasks 1 and 2).

- [ ] **Step 1: Write the failing test**

```java
package org.apache.roller.weblogger.business.startup;

/** Spec acceptance criterion 8: the runbook documents the tool that ships. */
class AdminRunbookTest {

    @Test
    void theRunbookDocumentsEverySubcommandAndNoLongerSaysRegister() throws IOException {
        String script = Files.readString(Paths.get("../deploy/admin-account.sh"), StandardCharsets.UTF_8);
        String runbook = Files.readString(Paths.get("../docker_deployment.md"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("admin-account\\.sh ([a-z-]+)").matcher(
                script.substring(script.indexOf("usage:"), script.indexOf("EOF", script.indexOf("usage:"))));
        Set<String> subcommands = new TreeSet<>();
        while (m.find()) {
            subcommands.add(m.group(1));
        }
        assertEquals(Set.of("create", "reset-password", "status"), subcommands);
        for (String sub : subcommands) {
            assertTrue(runbook.contains("run --rm admin " + sub) || runbook.contains("run --rm -T admin " + sub),
                    "docker_deployment.md never shows `admin " + sub + "`");
        }
        assertFalse(runbook.toLowerCase(Locale.ROOT).contains("register the first user"),
                "self-registration is gone; the runbook must not send anyone looking for it");
        assertTrue(runbook.contains("site admin email") || runbook.contains("site.adminemail"),
                "reset mail needs the From address; the runbook must say so");
    }
}
```

- [ ] **Step 2: Run and watch it fail**

Run: `mvn -pl app test -Dtest=AdminRunbookTest`.
Expected: FAIL, because the runbook has no `run --rm admin create` yet.

- [ ] **Step 3: Write the docs**

1. **`docker_deployment.md`, "First run".** Replace the
   "register the first user account…" text with these steps, in order:
   1. After `./deploy.sh`, it prints the create command if there is no
      admin. Show:
      `docker compose -f docker-compose.prod.yml run --rm admin create --username NAME --email ADDRESS`
      It prompts twice. For scripts, use `run --rm -T admin create …
      --password-stdin`. Give the rules: letters and digits only, a real
      email (reset links go there), at least 8 characters.
   2. Log in, then in Admin → Global Config set the Site URL and the
      site admin email (`site.adminemail`). The site admin email is the
      From address of every mail Roller sends.
   3. Set up mail (next section).
   4. `docker compose -f docker-compose.prod.yml run --rm admin status`
      lists the enabled admins.
2. **New "Mail and account recovery" section.**
   - A Brevo worked example: `ROLLER_MAIL_CONFIGURATIONTYPE=properties`,
     `ROLLER_MAIL_HOSTNAME=smtp-relay.brevo.com`, `ROLLER_MAIL_PORT=587`,
     `ROLLER_MAIL_USERNAME=<Brevo SMTP login>`,
     `ROLLER_MAIL_PASSWORD=<Brevo SMTP key>`. `mail.security` defaults to
     `starttls`. The From address must be a sender or domain verified in
     Brevo.
   - Reset mail needs **both** the SMTP settings **and** the site admin
     email. Until then, the forgot-password page shows a "mail not
     configured" notice.
   - Recovery, in order: "Forgot password?" on the login page (the link
     is valid for 1 hour and works once, and goes to the account's
     email). Last resort, when mail is broken:
     `docker compose -f docker-compose.prod.yml run --rm admin reset-password --username NAME`,
     then `docker compose -f docker-compose.prod.yml restart app`. Explain
     why the restart is needed: the app caches loaded accounts.
   - Applying `.env` changes needs
     `docker compose -f docker-compose.prod.yml up -d --force-recreate app`.
   - Note that runtime settings changed directly in the database also
     need an app restart.
3. **Troubleshooting entries.**
   - "Forgot-password says mail is not configured": check both settings.
   - "The new password from reset-password doesn't work": restart the app.
4. **`deploy/.env.example` mail block.** Add the Brevo example as
   comments, plus a line saying the site admin email (Admin → Global
   Config) is also required.
5. **`docs/dev/passwords.md`.** Add a section "The admin account tool":
   - The trust model (spec "Trust model", two sentences).
   - The D4 password route, and why there is a heredoc and no `-c`.
   - `IFS= read -r`.
   - The restart caveat, and `aRunningTierKeepsTheOldPasswordUntilRestart`.
   - The tests live in `AdminAccountScriptTest`, and run the shipped
     script in the PostgreSQL container with a psql argv shim.

- [ ] **Step 4: Run until green**

Run: `mvn -pl app test -Dtest='AdminRunbookTest,ProductionComposeTest'`.
Expected: PASS. `ProductionComposeTest` pins `.env.example` contents too.

- [ ] **Step 5: Commit**

Message: `docs(deploy): first admin via the admin tool; mail and recovery`
plus the attribution line.

---

### Task 6: Wiki — RollerHub and its pages

There is no Maven build in this task. **Draft** the pages any time.
**Publish** only after Tasks 1–5 have passed review, so every command on
the wiki matches what shipped.

**Access:**
- The wiki is https://wiki.wikantik.com, through its admin MCP.
- The client is
  `/tmp/claude-1000/-home-jakefear-source-roller/04b57319-2265-4074-9f8b-20e347bc7911/scratchpad/wmcp.sh <method> '<params-json>'`.
  It reads the bearer key itself. **Never print, log or copy the key.**
- Tool calls are `wmcp.sh tools/call '{"name":"read_page","arguments":{"pageName":"SimpleAgilityStackHub"}}'`.
- Tool semantics are in
  `/home/jakefear/source/jspwiki/wikantik-admin-mcp/src/main/resources/wikantik-mcp-instructions.txt`:
  - `write_pages` takes `{pages:[{pageName, content, metadata?}]}` (check
    the exact shape there) and fails for a page that already exists.
  - `update_page` needs `expectedContentHash` from `read_page`.
  - `canonical_id` is assigned by the server; never send it.
- The live wiki is authoritative. Do not write into
  `../jspwiki/docs/wikantik-pages/`.

**Conventions:**
- Read the live `SimpleAgilityStackHub`, `JakemonHub` and
  `WikantikPlatformHub` for the hub layout. That includes the
  "If you are an agent, start here" section and `audience: [humans, agents]`.
- Read the frontmatter schema in
  `wikantik-api/.../frontmatter/schema/FrontmatterSchema.java`.
- A `type: runbook` page must carry a `runbook:` map with
  `when_to_use` (at least 1 entry), `steps` (at least 2) and `pitfalls`
  (at least 1). `inputs`, `related_tools` and `references` are optional.
  Example: `docs/wikantik-pages/BuildingAndDeployingLocally.md` in the
  jspwiki repo.

- [ ] **Step 1: Draft `RollerHub`**
  - Frontmatter: `type: hub`, `cluster: roller`,
    `audience: [humans, agents]`, a `summary`,
    `tags: [roller, simple-agility, blogging, hub]`,
    `related: [SimpleAgilityStackHub, RollerOnDocker, RollerAdminAccountRunbook, RollerBlogPlatform, RollerW3tPlugins]`.
  - Body:
    - What Roller is in the Simple Agility stack: the customer
      communication component. Blog, newsletter (listmonk) and
      analytics (Umami).
    - "If you are an agent, start here", pointing to the runbook and to
      the repo's `docs/dev/` and `docs/api/README.md` for the Automation
      API.
    - A page index: "Operating Roller" (RollerOnDocker,
      RollerAdminAccountRunbook) and "History" (RollerBlogPlatform,
      RollerW3tPlugins, marked as describing earlier work).
    - A link back to SimpleAgilityStackHub.
- [ ] **Step 2: Draft `RollerOnDocker`** (`type: article`, `cluster: roller`)
  - The operator guide, condensed from `docker_deployment.md` as Task 5
    left it:
    - what runs (the compose services);
    - install (`.env`, `deploy.sh`);
    - LAN vs public mode;
    - the first admin (the `admin` tool);
    - Site URL and admin email;
    - mail via Brevo;
    - recovery (forgot password first, then `reset-password` plus a
      restart);
    - upgrades (download the release's compose file and `deploy.sh`,
      bump `IMAGE_VERSION`);
    - backups.
  - State that the `admin` tool ships in the first release after
    0.1.10.
  - Link to the repo runbook for exhaustive detail.
- [ ] **Step 3: Draft `RollerAdminAccountRunbook`** (`type: runbook`, `cluster: roller`)
  - `runbook:`:
    - `when_to_use`: fresh install with no admin; admin locked out;
      checking which admins exist.
    - `steps`: (1) `status`; (2) `create` with `--password-stdin` and
      `run -T`; (3) verify by logging in, or by `status`;
      (4) recovery via forgot password; (5) last resort `reset-password`
      plus `restart app`.
    - `pitfalls`:
      - `run` without `-T` when piping a password;
      - forgetting the restart after `reset-password`;
      - mail not working until both the SMTP settings and the site
        admin email are set;
      - a password passed as an argument;
      - usernames other than letters and digits.
  - Body: the exact commands and the expected output for each step.
- [ ] **Step 4: Publish (after Tasks 1–5 pass review)**
  1. `write_pages` the three new pages.
  2. `read_page SimpleAgilityStackHub`, then `update_page` it with the
     returned hash:
     - in the Components table's Roller row, the "see" column points to
       `[RollerHub](RollerHub)`;
     - the "each has its own entry page" sentence gains
       `[RollerHub](RollerHub)`;
     - `related` gains `RollerHub`.
  3. For `RollerBlogPlatform` and `RollerW3tPlugins`: `read_page`, then
     `update_page` with metadata `cluster: [roller, web-properties]` and
     the content unchanged.
- [ ] **Step 5: Verify (acceptance criterion 9)**
  - `curl -s https://wiki.wikantik.com/api/pages/RollerHub`: it exists,
    with `type: hub` and `cluster: roller`, and its content links all
    five pages.
  - `SimpleAgilityStackHub`'s content contains `RollerHub`.
  - `RollerAdminAccountRunbook` exists, meaning the save passed runbook
    validation.
  - Report the page URLs.

---

### Task 7: Whole-branch verification

- [ ] **Step 1:** Run the full build with every gate, alone (busy check
  first):
  `mvn verify` from the repo root. Expected: BUILD SUCCESS, with JaCoCo
  floors, diff coverage, PMD, CPD and SpotBugs all clean.
- [ ] **Step 2:** Build the image locally, then run the tool against a
  throwaway stack:

```bash
docker build -t roller-admin-check .
docker network create rac
docker run -d --name rac-pg --network rac -e POSTGRES_PASSWORD=pw -e POSTGRES_USER=roller \
  -e POSTGRES_DB=rollerdb postgres:18
until docker exec rac-pg pg_isready -U roller -d rollerdb >/dev/null 2>&1; do sleep 1; done
A=(--rm --network rac -e PGHOST=rac-pg -e POSTGRES_USER=roller -e POSTGRES_PASSWORD=pw)
docker run "${A[@]}" --entrypoint /app/provision.sh roller-admin-check     # migrations
printf 'correct horse battery\n' | docker run -i "${A[@]}" --entrypoint /app/admin-account.sh \
  roller-admin-check create --username jake --email jake@example.com --password-stdin
docker run "${A[@]}" --entrypoint /app/admin-account.sh roller-admin-check status
docker rm -f rac-pg; docker network rm rac; docker image rm roller-admin-check
```

  Expected: provisioning completes, then
  `created admin account jake (roles: admin, editor)`, then
  `enabled admin accounts:` followed by `jake`. This proves the script
  runs on the app image's own bash and psql, not only in the test
  container.

---

## After the release (needs the user's go-ahead to push and tag)

Not part of execution. These are the docker2 steps for spec acceptance
criterion 10:

1. Download the release's `docker-compose.prod.yml` and `deploy.sh` into
   `~/roller`.
2. Set `IMAGE_VERSION` to the new tag.
3. Run `./deploy.sh`.
4. Run `docker compose -f docker-compose.prod.yml run --rm admin status`.
   Expected: it lists `admin`.
