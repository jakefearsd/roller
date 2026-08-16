# Kill Plaintext Passwords Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove Roller's ability to store a plaintext password under any
configuration, and give dev and API-auth testing a credential path that works
out of the box without committing a secret.

**Architecture:** `RollerContext.createPasswordEncoder()` loses the
`passwds.encryption.enabled` branch, the `noop` encoder registration, and
`lazyUpgradeFrom=plaintext`; an explicitly-set flag throws at startup. Dev gets
a git-ignored `.roller-dev-secret` and `bin/db/seed-dev-data.sql`, which hashes
with pgcrypto inside Postgres so no host bcrypt tool is needed. Test fixtures
switch from `NoOpPasswordEncoder` to real bcrypt with a precomputed constant so
the change costs no CPU.

**Tech Stack:** Java 25, Spring Security `DelegatingPasswordEncoder` /
`BCryptPasswordEncoder`, PostgreSQL 16 + `pgcrypto`, bash, JUnit 5,
Testcontainers.

**Spec:** `docs/superpowers/specs/2026-08-16-kill-plaintext-passwords-design.md`
— its 17 acceptance criteria are the definition of done.

## Global Constraints

- **TDD, no exceptions.** Write the failing test, run it, watch it fail for the
  expected reason, then write the minimum code that passes. A test that has
  never been seen to fail has not been shown to test anything.
- **Never run two Maven builds at once in this working tree** — implementers
  share `app/target/`. Check with:
  `pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || echo CLEAR`
  Both the bracket and the `source/roller` scope are load-bearing.
- **Work directly on `master`.** Solo repo; no feature branch.
- **Commit trailer, every commit:**
  `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`
- **Never commit `.roller-dev-secret`.** Its `.gitignore` entry must land in the
  same commit as (or earlier than) the first code that writes it.
- **Do not touch the IT tier.** `it-selenium/src/test/resources/seed-it-data.sql`
  and `ApiIT` are already correct and are explicit non-goals.
- **Test-only bcrypt work uses the precomputed constant**, never a live
  `encode()` call in a loop — bcrypt is deliberately slow and `setupUser` has
  106 call sites.
- **The constant test hash** (bcrypt of the plaintext `password`):
  `{bcrypt}$2a$10$Vav4tnxZRN4O9Uh/gMr0Se5fn4grMKMdIaFYgd68hgGaRXs9UPfni`
- **`substring(passphrase from 9)`** strips the 8-character `{bcrypt}` prefix.
  Do not change this offset without re-deriving it.
- **The `CASE` in the seed's `ON CONFLICT` guard must never be flattened into an
  `OR` chain.** `crypt()` raises `ERROR: invalid salt` on a non-salt second
  argument, and PostgreSQL does not guarantee short-circuit order inside `OR`.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `app/src/test/java/.../TestUtils.java` | fixture users store a real bcrypt hash | 1 |
| `app/src/test/java/.../ui/controllers/admin/ControllerTestFixture.java` | installs a real bcrypt encoder | 1 |
| `app/src/test/java/.../ui/controllers/core/ControllerTestFixture.java` | installs a real bcrypt encoder | 1 |
| `app/src/test/java/.../ui/restapi/v1/AdminApiTest.java` | third private noop installer | 1 |
| 5 test classes with `{noop}` literals | opaque markers vs. real encode assertions | 1 |
| `app/src/main/java/.../ui/core/RollerContext.java` | the only place an encoder is built | 2 |
| `app/src/main/resources/.../roller.properties` | drops `passwds.encryption.enabled` | 2 |
| `app/src/test/resources/roller-custom.properties` | drops the flag | 2 |
| `app/src/test/resources/roller-boot-dev.properties` | drops the flag | 2 |
| `app/src/test/java/.../security/PasswordEncodingTest.java` | the regression guard | 2, 3 |
| `bin/db/seed-dev-data.sql` | dev admin row, hashed in Postgres | 3 |
| `app/src/test/java/.../db/DevSeedTest.java` | runs the real seed SQL against the test container | 3 |
| `bin/roller-api` | `--password-stdin` | 4 |
| `app/src/test/java/.../boot/RollerApiCliTest.java` | script-source assertions | 4 |
| `roller` | `.roller-dev-secret`, `seed_dev_data`, `token` | 5 |
| `.gitignore` | `.roller-dev-secret` | 5 |
| `CLAUDE.md`, `docs/api/README.md` | record the bootstrap | 6 |

---

## Task 1: Test fixtures store and verify real bcrypt

Removes every `NoOpPasswordEncoder` from the test tree. Must come first: Task 2
deletes the config flag, and a fixture still asserting `{noop}` literals would
fail for a confusing reason.

**Files:**
- Modify: `app/src/test/java/org/apache/roller/weblogger/TestUtils.java:107`
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/admin/ControllerTestFixture.java:132-139`
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/core/ControllerTestFixture.java:156-163`
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/AdminApiTest.java:665-671` (+ call sites 333, 742, 796)
- Modify: `CreateUserBeanTest.java:90,94,102,116`, `ProfileBeanTest.java:62,66,75`,
  `ProfileControllerTest.java:139,147,155,161`,
  `PasswordResetControllerTest.java:422,435`,
  `UserEditControllerTest.java:226,431`
- Modify call sites of the renamed installer: `PasswordResetControllerTest.java:70`,
  `ProfileControllerTest.java:60`, `UserEditPasswordLinkTest.java:67`,
  `UserEditControllerTest.java:72`

**Interfaces:**
- Produces: `TestUtils.TEST_PASSWORD` (`String`, value `"password"`) and
  `TestUtils.TEST_PASSWORD_HASH` (`String`, the `{bcrypt}`-prefixed constant).
- Produces: `ControllerTestFixture.installBcryptPasswordEncoder()` returning
  `Object` (the previous encoder), in **both** the `admin` and `core` packages,
  replacing `installNoopPasswordEncoder()`. `restorePasswordEncoder(Object)` is
  unchanged.

**Classify each `{noop}` literal before changing it — there are two kinds:**

1. **Opaque markers.** The test stores a value and asserts it is *unchanged*
   (`CreateUserBeanTest:90/94`, `:102/116`, `ProfileBeanTest:62/66`, `:75`,
   `ProfileControllerTest:155/161`). These never go through an encoder; the
   literal is just an arbitrary stored value. Replace with
   `TestUtils.TEST_PASSWORD_HASH`. Do **not** convert these to
   `encoder.matches(...)` — there is no encoding happening and a match
   assertion would obscure what the test is actually about.
2. **Real encode assertions.** The test calls a path that reaches
   `User.resetPassword` and asserts the resulting stored value
   (`ProfileControllerTest:147`, `PasswordResetControllerTest:435`,
   `UserEditControllerTest:226`, `:431`). These become `matches` assertions.

- [ ] **Step 1: Write the failing test**

Add to `app/src/test/java/org/apache/roller/weblogger/TestUtilsPasswordTest.java` (new file):

```java
package org.apache.roller.weblogger;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.roller.weblogger.pojos.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * {@code setupUser} used to call the raw {@code setPassword("password")},
 * writing an unprefixed plaintext string at 106 call sites. Those users could
 * never authenticate, so no unit test had ever exercised a real login.
 */
class TestUtilsPasswordTest {

    @Test
    void theFixtureHashIsARealBcryptHashOfTheFixturePassword() {
        assertTrue(TestUtils.TEST_PASSWORD_HASH.startsWith("{bcrypt}$2"),
                "fixture hash must be a bcrypt hash, was: " + TestUtils.TEST_PASSWORD_HASH);
        assertTrue(new BCryptPasswordEncoder().matches(
                        TestUtils.TEST_PASSWORD,
                        TestUtils.TEST_PASSWORD_HASH.substring("{bcrypt}".length())),
                "TEST_PASSWORD_HASH does not verify against TEST_PASSWORD");
    }

    @Test
    void aFixtureUserStoresThatHashRatherThanPlaintext() throws Exception {
        User user = TestUtils.setupUser("pwfixture");
        try {
            assertTrue(user.getPassword().startsWith("{bcrypt}$2"),
                    "setupUser stored a non-bcrypt password: " + user.getPassword());
        } finally {
            TestUtils.teardownUser(user.getUserName());
            TestUtils.endSession(true);
        }
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
mvn -pl app test -Dtest=TestUtilsPasswordTest
```

Expected: FAIL — compilation error, `cannot find symbol: TEST_PASSWORD_HASH`.

- [ ] **Step 3: Add the constants and use them in `setupUser`**

In `TestUtils.java`, beside the existing `JUNIT_PREFIX` constant:

```java
    /** Plaintext of {@link #TEST_PASSWORD_HASH}. */
    public static final String TEST_PASSWORD = "password";

    /**
     * A precomputed bcrypt hash of {@link #TEST_PASSWORD}.
     *
     * <p>Precomputed rather than encoded on demand because bcrypt is
     * deliberately slow and {@code setupUser} has ~106 call sites; hashing per
     * call would add ~10s to the suite. Storing a real hash is what lets a unit
     * test authenticate a fixture user at all.
     */
    public static final String TEST_PASSWORD_HASH =
            "{bcrypt}$2a$10$Vav4tnxZRN4O9Uh/gMr0Se5fn4grMKMdIaFYgd68hgGaRXs9UPfni";
```

Then change line 107 from `testUser.setPassword("password");` to:

```java
        testUser.setPassword(TEST_PASSWORD_HASH);
```

- [ ] **Step 4: Run it and watch it pass**

```bash
mvn -pl app test -Dtest=TestUtilsPasswordTest
```

Expected: PASS, 2 tests.

- [ ] **Step 5: Replace both `ControllerTestFixture` noop installers**

Identical change in `ui/controllers/admin/ControllerTestFixture.java` and
`ui/controllers/core/ControllerTestFixture.java`. Delete the
`@SuppressWarnings("deprecation")` annotation — nothing deprecated remains —
and the `NoOpPasswordEncoder` import:

```java
    static Object installBcryptPasswordEncoder() {
        Object previous = getStaticField(RollerContext.class, "encoder");
        setStaticField(RollerContext.class, "encoder",
                new DelegatingPasswordEncoder("bcrypt",
                        Map.of("bcrypt", new BCryptPasswordEncoder())));
        return previous;
    }
```

Add `import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;`.
Update the javadoc's `@return` line to name the new method. Rename the four
call sites (`PasswordResetControllerTest:70`, `ProfileControllerTest:60`,
`UserEditPasswordLinkTest:67`, `UserEditControllerTest:72`).

- [ ] **Step 6: Replace `AdminApiTest`'s private noop installer**

`AdminApiTest.java:665-671`. Note this one installs a **bare**
`NoOpPasswordEncoder`, not a `DelegatingPasswordEncoder`:

```java
    private void installBcryptPasswordEncoder() {
        previousPasswordEncoder = org.apache.roller.weblogger.ui.core.RollerContext.getPasswordEncoder();
        org.apache.roller.weblogger.ui.core.RollerContext.setPasswordEncoder(
                new org.springframework.security.crypto.password.DelegatingPasswordEncoder(
                        "bcrypt",
                        java.util.Map.of("bcrypt",
                                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder())));
        passwordEncoderInstalled = true;
    }
```

Drop its `@SuppressWarnings("deprecation")`, update its javadoc's last sentence,
and rename the three call sites (333, 742, 796).

- [ ] **Step 7: Convert the opaque-marker literals**

In `CreateUserBeanTest` (90, 94, 102, 116), `ProfileBeanTest` (62, 66, 75) and
`ProfileControllerTest` (155, 161), replace each `"{noop}original"` /
`"{noop}secret"` / `"{noop}old"` with `TestUtils.TEST_PASSWORD_HASH`, keeping
the assertions as `assertEquals`. Import `TestUtils` where needed. The point of
these tests is that the stored value is *not modified*; equality is the right
assertion.

- [ ] **Step 8: Convert the real encode assertions**

Four sites. Each becomes a `matches` check against a locally constructed
encoder. `ProfileControllerTest:147`:

```java
        assertTrue(new BCryptPasswordEncoder().matches(
                        "newpassword", user.getPassword().substring("{bcrypt}".length())),
                "the new password should verify against the stored hash");
```

Apply the same shape to `PasswordResetControllerTest:435` (`"newpassword1"`),
`UserEditControllerTest:226` (`"secret"`) and `UserEditControllerTest:431`
(`"newpass"`, on `stored.getPassword()`). Add the
`BCryptPasswordEncoder` import and `assertTrue` static import to each file.

- [ ] **Step 9: Run the affected classes, then the full suite**

```bash
mvn -pl app test -Dtest='TestUtilsPasswordTest+CreateUserBeanTest+ProfileBeanTest+ProfileControllerTest+PasswordResetControllerTest+UserEditControllerTest+UserEditPasswordLinkTest+AdminApiTest'
mvn -pl app test
```

Expected: PASS. Then confirm the noop references are gone:

```bash
grep -rn "NoOpPasswordEncoder\|{noop}" app/src/test/java app/src/main/java || echo "CLEAN"
```

Expected: `CLEAN` except `RollerContext.java` (Task 2 removes those).

- [ ] **Step 10: Commit**

```bash
git add app/src/test
git commit -m "Test fixtures store and verify real bcrypt, not {noop}

TestUtils.setupUser called the raw setPassword(\"password\"), writing an
unprefixed plaintext string at 106 call sites -- users that could never
authenticate, which is why no unit test had ever exercised a real login. It now
stores a precomputed bcrypt constant: a real hash at zero CPU cost.

All three NoOpPasswordEncoder installers (both ControllerTestFixtures and
AdminApiTest's private copy) become real bcrypt. Assertions split by kind:
opaque stored-value markers keep assertEquals against the constant, while the
four that actually assert an encoding result become encoder.matches(), which
tests the property that matters rather than a literal.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Remove the plaintext capability

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/core/RollerContext.java:158-208`
- Modify: `app/src/main/resources/org/apache/roller/weblogger/config/roller.properties:291`
- Modify: `app/src/test/resources/roller-custom.properties:14`
- Modify: `app/src/test/resources/roller-boot-dev.properties:23`
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/AdminApiTest.java:56`
- Modify: `CLAUDE.md` (the "deliberately NOT promoted" list)
- Create: `app/src/test/java/org/apache/roller/weblogger/security/PasswordEncodingTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `RollerContext.rejectRemovedEncryptionFlag(String configuredValue)`
  — package-private `static void`, throws `IllegalStateException` when the
  argument is non-null. Extracted so the guard is testable as a pure function.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/apache/roller/weblogger/security/PasswordEncodingTest.java`:

```java
package org.apache.roller.weblogger.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.apache.roller.weblogger.ui.core.RollerContext;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Pins that Roller cannot be configured to store a plaintext password.
 *
 * <p>Deleting the capability does not prevent its return; this is the guard
 * that does. Same role as {@code ProductionComposeTest} and
 * {@code DesignTokenTest}.
 */
class PasswordEncodingTest {

    private static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath();
        return Files.exists(here.resolve("bin/db")) ? here : here.getParent();
    }

    @Test
    void theBuiltEncoderNeverReturnsItsInput() {
        PasswordEncoder encoder = RollerContext.createPasswordEncoder();
        String raw = "a-plaintext-password";
        String encoded = encoder.encode(raw);
        assertFalse(encoded.contains(raw),
                "the encoder emitted its own input: " + encoded);
        assertTrue(encoder.matches(raw, encoded),
                "the encoder cannot verify what it just encoded");
    }

    @Test
    void aNoopStoredValueDoesNotAuthenticate() {
        PasswordEncoder encoder = RollerContext.createPasswordEncoder();
        assertFalse(encoder.matches("secret", "{noop}secret"),
                "a {noop} stored value still authenticates -- the encoder is still registered");
    }

    @Test
    void anExplicitlySetEncryptionFlagIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> RollerContext.rejectRemovedEncryptionFlag("false"),
                "an explicitly-set passwds.encryption.enabled must fail loudly");
        assertThrows(IllegalStateException.class,
                () -> RollerContext.rejectRemovedEncryptionFlag("true"),
                "even =true must be rejected: the property no longer exists");
        RollerContext.rejectRemovedEncryptionFlag(null); // absent: must not throw
    }

    @Test
    void noPropertiesFileMentionsTheRemovedFlagOrPlaintextUpgrade() throws IOException {
        Path root = repoRoot();
        try (Stream<Path> files = Files.walk(root)) {
            List<String> offenders = files
                    .filter(p -> p.toString().endsWith(".properties"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> {
                        try {
                            String body = Files.readString(p);
                            return body.contains("passwds.encryption.enabled")
                                    || body.contains("lazyUpgradeFrom=plaintext");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(Path::toString)
                    .toList();
            assertTrue(offenders.isEmpty(),
                    "properties files still reference the removed flag: " + offenders);
        }
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
mvn -pl app test -Dtest=PasswordEncodingTest
```

Expected: FAIL — `cannot find symbol: rejectRemovedEncryptionFlag`. After that
compiles, expect `aNoopStoredValueDoesNotAuthenticate` and
`noPropertiesFileMentionsTheRemovedFlag...` to fail on the real defect.

- [ ] **Step 3: Rewrite `createPasswordEncoder()`**

In `RollerContext.java`, replace lines 158-208. Delete the
`@SuppressWarnings("deprecation")` on the method, the `noop` registration, the
`plaintext` branch of the lazy-upgrade chain, and the whole
`if (getBooleanProperty("passwds.encryption.enabled"))` / `else` structure:

```java
    /**
     * The property that used to make this method register a no-op encoder.
     * Removed outright: encryption is not optional. Retained here only so an
     * explicitly-set value fails loudly instead of being silently ignored.
     */
    static final String REMOVED_ENCRYPTION_FLAG = "passwds.encryption.enabled";

    /**
     * Fails when a deployer explicitly sets the removed flag.
     *
     * <p>Silently ignoring it is the failure this exists to prevent: a deploy
     * carrying {@code ROLLER_PASSWDS_ENCRYPTION_ENABLED=false} would boot
     * looking configured and behave differently than its operator believes.
     * Same convention as an unsupported {@code authentication.method}.
     */
    static void rejectRemovedEncryptionFlag(String configuredValue) {
        if (configuredValue != null) {
            throw new IllegalStateException(REMOVED_ENCRYPTION_FLAG
                    + " is no longer supported and must be removed from your"
                    + " configuration. Password encryption is always on;"
                    + " choose the algorithm with passwds.encryption.algorithm.");
        }
    }

    public static DelegatingPasswordEncoder createPasswordEncoder() {

        rejectRemovedEncryptionFlag(WebloggerConfig.getProperty(REMOVED_ENCRYPTION_FLAG));

        Map<String, PasswordEncoder> encoders = new HashMap<>();

        // Outdated digest encoders, for lazy upgrade from old Roller installs.
        // `plaintext` is deliberately NOT accepted: it registers a no-op
        // encoder against the null prefix, which makes an unprefixed stored
        // string authenticate as plaintext.
        String migrateFrom = WebloggerConfig.getProperty("passwds.encryption.lazyUpgradeFrom");

        if (migrateFrom == null || migrateFrom.isEmpty()) {
            log.debug("lazy pw upgrade disabled");
        } else if (migrateFrom.equals("MD5")) {
            encoders.put(null, new org.springframework.security.crypto.password.MessageDigestPasswordEncoder("MD5"));
        } else if (migrateFrom.equals("SHA")) {
            encoders.put(null, new org.springframework.security.crypto.password.MessageDigestPasswordEncoder("SHA-1"));
        } else {
            throw new RuntimeException("passwds.encryption.lazyUpgradeFrom="+migrateFrom+" is no valid encoding to upgrade from.");
        }

        encoders.put("bcrypt", new BCryptPasswordEncoder());
        encoders.put("pbkdf2", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8());
        encoders.put("scrypt", SCryptPasswordEncoder.defaultsForSpringSecurity_v5_8());
        encoders.put("argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());

        String algorithm = WebloggerConfig.getProperty("passwds.encryption.algorithm");

        if ("SHA".equals(algorithm) || "MD5".equals(algorithm)) {
            throw new RuntimeException("passwds.encryption.algorithm="+algorithm+" is outdated,"
                    + " please set passwds.encryption.algorithm to 'bcrypt' for automatic lazy upgrade.");
        }

        if (!encoders.containsKey(algorithm)) {
            throw new RuntimeException("passwds.encryption.algorithm="+algorithm+" is not supported.");
        }

        log.info("Password Encryption Algorithm set to '" + algorithm + "'");

        return new DelegatingPasswordEncoder(algorithm, encoders);
    }
```

Note the `@SuppressWarnings("deprecation")` on the method is no longer needed
once `NoOpPasswordEncoder` is gone — remove it, and remove the now-unused
import if one exists.

- [ ] **Step 4: Delete the property from all three properties files**

- `roller.properties:291` — delete the `passwds.encryption.enabled=true` line.
- `roller-custom.properties:14` — delete the line *and* its
  `# use plain text passwords in testing` comment.
- `roller-boot-dev.properties:23` — same, line and comment.

- [ ] **Step 5: Run the test and watch it pass**

```bash
mvn -pl app test -Dtest=PasswordEncodingTest
```

Expected: PASS, 4 tests.

- [ ] **Step 6: Update the two places that name the property as never-promoted**

`AdminApiTest.java:56` lists `"passwds.encryption.enabled"` among properties
that must not be promoted to runtime scope. Remove it from that list and add a
line comment on the list explaining that the property no longer exists, so
there is nothing to promote.

In `CLAUDE.md`, in the "**Deliberately NOT promoted**" bullet, change the
`weblogAdminsUntrusted` and `passwds.encryption.enabled` entry to name
`weblogAdminsUntrusted` alone, and note that password encryption is no longer
configurable at all.

- [ ] **Step 7: Run the full suite**

```bash
mvn -pl app test
```

Expected: PASS, no regressions.

- [ ] **Step 8: Commit**

```bash
git add app/src CLAUDE.md
git commit -m "Remove the ability to store a plaintext password

Four paths could put plaintext in the database. All are gone:
passwds.encryption.enabled (which flipped the encoding id to noop), the
unconditional noop encoder registration (which made a {noop} value
authenticate however the flag was set), lazyUpgradeFrom=plaintext (a no-op
encoder on the NULL prefix, so an unprefixed string authenticated), and the
config lines in roller-custom/roller-boot-dev that turned the first one on.

An explicitly-set passwds.encryption.enabled now throws at startup rather than
being ignored, matching the convention already used for an unsupported
authentication.method: a deploy carrying ROLLER_PASSWDS_ENCRYPTION_ENABLED=false
must not boot looking configured while behaving otherwise.

PasswordEncodingTest is the guard, since deleting code does not prevent its
return.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: The dev seed SQL

**Files:**
- Create: `bin/db/seed-dev-data.sql`
- Create: `app/src/test/java/org/apache/roller/weblogger/db/DevSeedTest.java`
- Modify: `app/src/test/java/org/apache/roller/weblogger/security/PasswordEncodingTest.java` (adds the fourth assertion)

**Interfaces:**
- Consumes: nothing.
- Produces: `bin/db/seed-dev-data.sql`, applied by
  `psql -v devpw=<password> -f bin/db/seed-dev-data.sql`. Task 5 calls it.

**The seed is NOT a migration.** It lives in `bin/db/`, never
`bin/db/migrations/`, so `migrate.sh`, `DatabaseInstaller` and the test harness
never apply it in production.

**Why the test rewrites the psql variable:** `:'devpw'` is a psql *client*
feature, not server SQL, so JDBC cannot execute the file verbatim. The test
substitutes a quoted literal it controls. That is a small, honest
transformation which still exercises the real shipped `CASE` guard — the part
that carries the risk.

**Use the established isolated-database pattern, copied from
`AnalyticsContractTest` (`app/src/test/java/.../business/startup/AnalyticsContractTest.java:177-224`).**
Do not run the seed against the shared test database: it would insert a real
`admin` row that nothing tears down, and this repo truncates nothing between
tests. The helpers to mirror are `freshDatabase(String)`, `dropDatabase(String)`,
`adminConnection()`, `jdbcUrlFor(String)` and `readMigration(Path)`. Connections
come from `RollerPostgresContainer.getJdbcUrl()/getUsername()/getPassword()` via
`DriverManager`; migrations come from `MigrationFiles.all()`. There is **no**
`RollerTestBootstrap.newConnection()` — do not invent one.

Note `readMigration` substitutes `:app_user` with the container username; the
dev seed has no such placeholder, only `:'devpw'`.

**Tests run with the `app` module as the working directory**, so the repo root
is `..` (see `MigrationFiles.MIGRATIONS_DIR`).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/apache/roller/weblogger/db/DevSeedTest.java`:

```java
package org.apache.roller.weblogger.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.apache.roller.testing.MigrationFiles;
import org.apache.roller.testing.RollerPostgresContainer;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Runs the real {@code bin/db/seed-dev-data.sql} against a scratch database.
 *
 * <p>The seed's guard is the risky part: {@code crypt()} raises "invalid salt"
 * on a non-salt second argument, so a naive OR chain would abort on exactly the
 * {@code {noop}} row the seed exists to repair.
 *
 * <p>Uses its own database rather than the shared one, following
 * {@code AnalyticsContractTest}: the seed inserts a real {@code admin} row and
 * nothing in this suite truncates tables between tests.
 */
class DevSeedTest {

    private static final String PW = "dev-seed-test-password";
    private static final Path SEED = Paths.get("../bin/db/seed-dev-data.sql");
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();

    /** Substitutes psql's client-side :\'devpw\' with a literal this test controls. */
    private static String seedSqlWith(String password) throws Exception {
        return Files.readString(SEED, StandardCharsets.UTF_8)
                .replace(":'devpw'", "'" + password.replace("'", "''") + "'");
    }

    @Test
    void theSeedExistsAndIsNotAMigration() {
        assertTrue(Files.exists(SEED), "bin/db/seed-dev-data.sql is missing");
        assertFalse(SEED.toString().contains("migrations"),
                "the dev seed must never sit under bin/db/migrations/");
    }

    @Test
    void theGuardRewritesEveryWrongShapeAndLeavesACorrectRowAlone() throws Exception {
        String db = "devseed_guard";
        try (Connection con = freshDatabase(db)) {
            assertTrue(guardSaysRewrite(con, "{noop}old-dev-password"), "a {noop} row must be rewritten");
            assertTrue(guardSaysRewrite(con, null),               "a null row must be rewritten");
            assertTrue(guardSaysRewrite(con, "{bcrypt}"),         "a truncated row must be rewritten, not error");
            assertTrue(guardSaysRewrite(con, "password"),         "a bare plaintext row must be rewritten");
            assertFalse(guardSaysRewrite(con, "{bcrypt}" + BCRYPT.encode(PW)),
                    "a row already holding the right password must be left alone");
            assertTrue(guardSaysRewrite(con, "{bcrypt}" + BCRYPT.encode("a-different-password")),
                    "a row holding a different password must be rewritten");
        } finally {
            dropDatabase(db);
        }
    }

    @Test
    void applyingTheSeedTwiceLeavesThePassphraseByteIdentical() throws Exception {
        String db = "devseed_idempotent";
        try (Connection con = freshDatabase(db)) {
            execute(con, seedSqlWith(PW));
            String first = adminPassphrase(con);
            execute(con, seedSqlWith(PW));
            String second = adminPassphrase(con);
            assertEquals(first, second,
                    "a second seed run rewrote the row; the guard is not working");
            assertTrue(first.startsWith("{bcrypt}$2"), "seed stored: " + first);
            assertTrue(BCRYPT.matches(PW, first.substring("{bcrypt}".length())),
                    "the seeded hash does not verify against the seeded password");
        } finally {
            dropDatabase(db);
        }
    }

    @Test
    void changingThePasswordRewritesTheRow() throws Exception {
        String db = "devseed_rotate";
        try (Connection con = freshDatabase(db)) {
            execute(con, seedSqlWith(PW));
            String before = adminPassphrase(con);
            execute(con, seedSqlWith("a-completely-different-password"));
            String after = adminPassphrase(con);
            assertNotEquals(before, after,
                    "the guard never fires -- it suppresses real changes too");
            assertTrue(BCRYPT.matches("a-completely-different-password",
                            after.substring("{bcrypt}".length())),
                    "the new password does not verify");
        } finally {
            dropDatabase(db);
        }
    }

    /** Applies the shipped guard expression to one probe value. */
    private boolean guardSaysRewrite(Connection con, String stored) throws Exception {
        String literal = stored == null ? "NULL" : "'" + stored.replace("'", "''") + "'";
        String sql = """
                SELECT CASE
                         WHEN %s LIKE '{bcrypt}$2%%'
                           THEN substring(%s from 9) <> crypt('%s', substring(%s from 9))
                         ELSE true
                       END
                """.formatted(literal, literal, PW, literal);
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private String adminPassphrase(Connection con) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT passphrase FROM roller_user WHERE username = 'admin'")) {
            assertTrue(rs.next(), "the seed did not create the admin user");
            return rs.getString(1);
        }
    }

    private void execute(Connection con, String sql) throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }

    // --- Isolated-database helpers, mirroring AnalyticsContractTest ---

    private Connection freshDatabase(String dbName) throws Exception {
        try (Connection admin = adminConnection(); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbName);
            st.execute("CREATE DATABASE " + dbName);
        }
        Connection con = DriverManager.getConnection(jdbcUrlFor(dbName),
                RollerPostgresContainer.getUsername(), RollerPostgresContainer.getPassword());
        for (Path migration : MigrationFiles.all()) {
            execute(con, Files.readString(migration, StandardCharsets.UTF_8)
                    .replace(":app_user", RollerPostgresContainer.getUsername()));
        }
        return con;
    }

    private void dropDatabase(String dbName) throws Exception {
        try (Connection admin = adminConnection(); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbName);
        }
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection(RollerPostgresContainer.getJdbcUrl(),
                RollerPostgresContainer.getUsername(), RollerPostgresContainer.getPassword());
    }

    private String jdbcUrlFor(String dbName) {
        String url = RollerPostgresContainer.getJdbcUrl();
        int dbStart = url.lastIndexOf('/') + 1;
        int queryStart = url.indexOf('?', dbStart);
        String tail = queryStart < 0 ? "" : url.substring(queryStart);
        return url.substring(0, dbStart) + dbName + tail;
    }
}
```


- [ ] **Step 2: Run it and watch it fail**

```bash
mvn -pl app test -Dtest=DevSeedTest
```

Expected: FAIL — `bin/db/seed-dev-data.sql is missing`.

- [ ] **Step 3: Write the seed**

Create `bin/db/seed-dev-data.sql`:

```sql
-- Seed data for the LOCAL DEVELOPMENT database.
--
-- Applied by `./roller db|dev|reset` via:
--     psql -v devpw="$ROLLER_DEV_ADMIN_PASSWORD" -f bin/db/seed-dev-data.sql
--
-- NOT a migration, and deliberately not under bin/db/migrations/: migrate.sh,
-- DatabaseInstaller and the test harness apply only that directory, so this
-- file can never reach production.
--
-- The password is hashed HERE, inside Postgres, via pgcrypto. That keeps the
-- plaintext out of the repository (it lives in the git-ignored
-- .roller-dev-secret) and needs no host-side bcrypt tool -- no htpasswd, no
-- Python bcrypt, no JVM for a database-only command. pgcrypto's
-- gen_salt('bf',10) emits $2a$, which Spring's BCryptPasswordEncoder accepts.
--
-- Unlike the IT seed's ON CONFLICT DO NOTHING, this one CORRECTS the row: it is
-- what repairs a database still holding a pre-bcrypt {noop} password, with no
-- reset and no manual SQL.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO roller_user (id, username, passphrase, screenname, fullname,
                         emailaddress, datecreated, locale, timezone, isenabled)
VALUES ('dev-admin-0000-0000-0000-000000000001',
        'admin',
        '{bcrypt}' || crypt(:'devpw', gen_salt('bf', 10)),
        'Dev Admin', 'Development Admin', 'dev-admin@example.invalid',
        NOW(), 'en_US', 'UTC', true)
ON CONFLICT (username) DO UPDATE
   SET passphrase = '{bcrypt}' || crypt(:'devpw', gen_salt('bf', 10))
 WHERE CASE
         -- Verify the stored hash against the configured password. crypt()
         -- raises "invalid salt" on a second argument that is not a usable
         -- salt, and PostgreSQL does NOT guarantee short-circuit evaluation
         -- inside OR -- so this MUST stay a CASE. Flattening it into an OR
         -- chain aborts the seed on exactly the {noop} row it exists to fix.
         WHEN roller_user.passphrase LIKE '{bcrypt}$2%'
           THEN substring(roller_user.passphrase from 9)
                <> crypt(:'devpw', substring(roller_user.passphrase from 9))
         -- null, {noop}, bare plaintext, truncated: rewrite without calling
         -- crypt() on it at all.
         ELSE true
       END;

-- Roller's authorities come from userrole; 'admin' also implies editor access.
INSERT INTO userrole (id, rolename, username)
VALUES ('dev-role-0000-0000-0000-000000000001', 'admin', 'admin')
ON CONFLICT (id) DO NOTHING;

INSERT INTO userrole (id, rolename, username)
VALUES ('dev-role-0000-0000-0000-000000000002', 'editor', 'admin')
ON CONFLICT (id) DO NOTHING;
```

- [ ] **Step 4: Run it and watch it pass**

```bash
mvn -pl app test -Dtest=DevSeedTest
```

Expected: PASS, 4 tests. If `theGuardRewritesEveryWrongShape...` errors with
`invalid salt`, the `CASE` was flattened — restore it.

- [ ] **Step 5: Add the fourth `PasswordEncodingTest` assertion**

Append to `PasswordEncodingTest`:

```java
    @Test
    void noMigrationCarriesAPasswordAndEveryDbPasswordLiteralIsBcrypt() throws IOException {
        Path db = repoRoot().resolve("bin/db");
        try (Stream<Path> files = Files.walk(db)) {
            List<String> offenders = files
                    .filter(p -> p.toString().endsWith(".sql"))
                    .filter(p -> {
                        try {
                            String body = Files.readString(p);
                            boolean isMigration = p.toString().contains("/migrations/");
                            if (isMigration) {
                                return body.contains("passphrase")
                                        && body.toLowerCase().contains("insert into roller_user");
                            }
                            return body.contains("{noop}") || body.contains("{plaintext}");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(Path::toString)
                    .toList();
            assertTrue(offenders.isEmpty(),
                    "SQL under bin/db carries a password it should not: " + offenders);
        }
    }
```

- [ ] **Step 6: Run both, then the full suite**

```bash
mvn -pl app test -Dtest='DevSeedTest+PasswordEncodingTest'
mvn -pl app test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add bin/db/seed-dev-data.sql app/src/test
git commit -m "Dev seed: an admin row hashed inside Postgres

bin/db/seed-dev-data.sql, deliberately NOT under bin/db/migrations/ so it can
never reach production through migrate.sh, DatabaseInstaller or the test
harness. pgcrypto hashes the password server-side, so the plaintext stays in
the git-ignored .roller-dev-secret and no host bcrypt tool is needed.

Unlike the IT seed's DO NOTHING, this corrects the row -- it is what repairs a
database still holding a pre-bcrypt {noop} password without a reset. The guard
must stay a CASE: crypt() raises \"invalid salt\" on a non-salt argument and
Postgres does not guarantee short-circuit order inside OR, so an OR chain would
abort on exactly the row this exists to fix. DevSeedTest runs the real file
against the test container and covers all six row shapes.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `roller-api auth login --password-stdin`

**Files:**
- Modify: `bin/roller-api:111-127` (`cmd_auth_login`) and its usage heredoc (~line 961)
- Modify: `app/src/test/java/org/apache/roller/weblogger/boot/RollerApiCliTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `roller-api auth login --url URL [--user NAME] [--password-stdin]`.
  With the flag, the password is read from stdin (one line, trailing newline
  stripped) instead of prompting. Task 5 pipes into it.

The existing `everyFlagCaseLabelIsDocumentedInSomeUsageHeredoc` test will fail
until the usage text documents the new flag — that is the drift guard working.

- [ ] **Step 1: Write the failing test**

Add to `RollerApiCliTest.java`:

```java
    @Test
    void passwordStdinReadsThePasswordWithoutPrompting() throws Exception {
        String body = Files.readString(scriptPath());
        assertTrue(body.contains("--password-stdin"),
                "auth login must support --password-stdin for non-interactive use");
        int flagAt = body.indexOf("--password-stdin)");
        assertTrue(flagAt > 0, "--password-stdin must be a case label, not just documentation");
        String branch = body.substring(flagAt, Math.min(body.length(), flagAt + 400));
        assertTrue(branch.contains("read -r"),
                "the --password-stdin branch must read the password from stdin");
    }

    @Test
    void theStdinPasswordStillNeverReachesACommandLine() throws Exception {
        String body = Files.readString(scriptPath());
        assertFalse(body.contains("curl -u ") || body.contains("--user \"$user:$password\""),
                "the password must go through --netrc-file, never curl's argv");
        assertTrue(body.contains("--netrc-file"),
                "login must keep using a netrc file for the credential");
    }
```

If `RollerApiCliTest` has no `scriptPath()` helper, extract one from the
existing `Path.of("..", "bin", "roller-api")` fallback logic at lines 16-18 and
use it in the new tests.

- [ ] **Step 2: Run it and watch it fail**

```bash
mvn -pl app test -Dtest=RollerApiCliTest
```

Expected: FAIL — `auth login must support --password-stdin`, plus
`everyFlagCaseLabelIsDocumentedInSomeUsageHeredoc` once the case label exists
but the usage text does not mention it.

- [ ] **Step 3: Implement the flag**

In `cmd_auth_login`, replace the option loop and prompt (lines 112-127):

```bash
cmd_auth_login() {
    local url="" user="" password_stdin=0
    while [ $# -gt 0 ]; do
        case "$1" in
            --url)  url="$2";  shift 2 ;;
            --user) user="$2"; shift 2 ;;
            --password-stdin) password_stdin=1; shift ;;
            *) die "Unknown option: $1" ;;
        esac
    done
    [ -n "$url" ] || die "--url is required."
    [ -n "$user" ] || { printf 'Username: '; read -r user; }

    # The password is read once, sent once, and never stored. Only the returned
    # token is persisted -- the same shape as `gh auth login`.
    #
    # --password-stdin is the standard non-interactive idiom (`docker login`
    # uses it). The password crosses on stdin: never an argv entry, never an
    # environment variable, so it is safe for CI as well as for ./roller token.
    local password
    if [ "$password_stdin" -eq 1 ]; then
        IFS= read -r password || die "--password-stdin was given but stdin was empty."
        [ -n "$password" ] || die "--password-stdin read an empty password."
    else
        printf 'Password: '
        read -rs password
        printf '\n'
    fi
```

Leave everything from the `# \`curl -u user:password\` would put...` comment
onward untouched — the netrc handling is already correct.

- [ ] **Step 4: Document it in the usage heredoc**

At the `auth login` line (~961), change:

```
  auth login --url URL [--user NAME]   mint and store a token
```

to:

```
  auth login --url URL [--user NAME] [--password-stdin]
                                      mint and store a token
                                      --password-stdin: read the password from
                                      stdin instead of prompting (for scripts)
```

- [ ] **Step 5: Run it and watch it pass**

```bash
mvn -pl app test -Dtest=RollerApiCliTest
bash -n bin/roller-api && echo "SYNTAX OK"
```

Expected: PASS, and `SYNTAX OK`.

- [ ] **Step 6: Commit**

```bash
git add bin/roller-api app/src/test/java/org/apache/roller/weblogger/boot/RollerApiCliTest.java
git commit -m "roller-api: --password-stdin for non-interactive login

The standard idiom (docker login uses it). The password crosses on stdin --
never an argv entry, never an environment variable -- so it is safe for CI as
well as for ./roller token. The interactive prompt is unchanged when the flag
is absent, and the credential still reaches curl through --netrc-file rather
than its command line.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: `.roller-dev-secret`, seeding, and `./roller token`

**Files:**
- Modify: `.gitignore`
- Modify: `roller` (header comment, `seed_dev_data`, `dev_secret`, `token` case)

**Interfaces:**
- Consumes: `bin/db/seed-dev-data.sql` (Task 3),
  `roller-api auth login --password-stdin` (Task 4).
- Produces: `.roller-dev-secret` holding `ROLLER_DEV_ADMIN_PASSWORD=...`.

**The `.gitignore` entry must be committed in this same commit** — this is the
first code that writes the file.

- [ ] **Step 1: Write the failing test**

Add to `app/src/test/java/org/apache/roller/weblogger/security/PasswordEncodingTest.java`:

```java
    @Test
    void theDevSecretIsGitIgnoredAndNotTracked() throws IOException {
        Path root = repoRoot();
        String ignores = Files.readString(root.resolve(".gitignore"));
        assertTrue(ignores.contains(".roller-dev-secret"),
                ".roller-dev-secret must be git-ignored before anything writes it");
        assertFalse(Files.exists(root.resolve(".roller-dev-secret"))
                        && isTracked(root, ".roller-dev-secret"),
                ".roller-dev-secret is tracked by git -- it holds a credential");
    }

    private static boolean isTracked(Path root, String relative) {
        try {
            Process p = new ProcessBuilder("git", "ls-files", "--error-unmatch", relative)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void theRollerScriptSeedsAndNeverPrintsThePasswordIntoAnArgv() throws IOException {
        String body = Files.readString(repoRoot().resolve("roller"));
        assertTrue(body.contains("seed-dev-data.sql"),
                "./roller must apply the dev seed");
        assertTrue(body.contains("-v devpw="),
                "the seed must receive the password as a psql variable");
        assertTrue(body.contains("umask 077"),
                ".roller-dev-secret must be created with a restrictive umask");
        assertTrue(body.contains("--password-stdin"),
                "./roller token must pipe the password rather than pass it as an argument");
    }
```

- [ ] **Step 2: Run it and watch it fail**

```bash
mvn -pl app test -Dtest=PasswordEncodingTest
```

Expected: FAIL — `.roller-dev-secret must be git-ignored...`.

- [ ] **Step 3: Add the `.gitignore` entry**

Append to `.gitignore`:

```
# Local dev admin credential, generated by ./roller. Never commit.
.roller-dev-secret
```

- [ ] **Step 4: Add the secret and seed helpers to `roller`**

Insert after `run_migrations()`:

```bash
DEV_SECRET_FILE="${SCRIPT_DIR}/.roller-dev-secret"

# Loads .roller-dev-secret, generating it on first use.
#
# There is deliberately no branch that stops and asks: a fresh checkout runs
# ./roller dev and gets a working login plus the credential printed once. The
# file is git-ignored (see .gitignore) and is the source of truth for the dev
# admin password -- a password changed through the UI is reverted by the next
# seed, which is the intended behaviour for a disposable local database.
load_dev_secret() {
    if [ ! -f "${DEV_SECRET_FILE}" ]; then
        local generated
        generated="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32)"
        ( umask 077; printf 'ROLLER_DEV_ADMIN_PASSWORD=%s\n' "${generated}" > "${DEV_SECRET_FILE}" )
        print_ok "Generated ${DEV_SECRET_FILE} (mode 600)"
        echo "  Dev admin login:  admin / ${generated}"
        echo "  This is the only time it is printed; the file is git-ignored."
    fi
    # shellcheck disable=SC1090
    . "${DEV_SECRET_FILE}"
    : "${ROLLER_DEV_ADMIN_PASSWORD:?${DEV_SECRET_FILE} has no ROLLER_DEV_ADMIN_PASSWORD}"
}

# Applies bin/db/seed-dev-data.sql. The password goes in as a psql variable,
# never interpolated into SQL text.
seed_dev_data() {
    load_dev_secret
    PGPASSWORD="${DB_APP_USER}" psql \
        --no-psqlrc --quiet --set ON_ERROR_STOP=1 \
        -h localhost -p "${DB_PORT}" -U "${DB_APP_USER}" -d "${DB_NAME}" \
        -v devpw="${ROLLER_DEV_ADMIN_PASSWORD}" \
        -f "${SCRIPT_DIR}/bin/db/seed-dev-data.sql" >/dev/null
    print_ok "Dev admin seeded (user: admin)"
}
```

- [ ] **Step 5: Call it from `db`, `dev` and `reset`, and add `token`**

In the `case` block, add `seed_dev_data` after `run_migrations` in the `db`,
`dev` and `reset` branches only — `migrate` and `status` stay pure. In `dev`,
it goes before the `print_ok "Starting Roller..."` line.

Add a new case before `*)`:

```bash
    token)
        load_dev_secret
        printf '%s\n' "${ROLLER_DEV_ADMIN_PASSWORD}" | \
            bin/roller-api auth login \
                --url http://localhost:8083/roller \
                --user admin \
                --password-stdin
        ;;
```

Add to the header comment block (which `usage()` prints):

```
#   ./roller token      mint an API token for the dev admin (needs ./roller dev running)
```

- [ ] **Step 6: Run it and watch it pass**

```bash
mvn -pl app test -Dtest=PasswordEncodingTest
bash -n roller && echo "SYNTAX OK"
```

Expected: PASS and `SYNTAX OK`.

- [ ] **Step 7: Verify end to end against the real dev database**

```bash
./roller db
docker compose exec -T postgres psql -U roller -d rollerdb \
    -c "select username, left(passphrase,12) from roller_user;"
```

Expected: `admin | {bcrypt}$2a`. Then confirm idempotence:

```bash
BEFORE=$(docker compose exec -T postgres psql -U roller -d rollerdb -tAc \
    "select passphrase from roller_user where username='admin'")
./roller db
AFTER=$(docker compose exec -T postgres psql -U roller -d rollerdb -tAc \
    "select passphrase from roller_user where username='admin'")
[ "$BEFORE" = "$AFTER" ] && echo "IDEMPOTENT" || echo "REWROTE -- guard broken"
```

Expected: `IDEMPOTENT`.

- [ ] **Step 8: Commit**

```bash
git add .gitignore roller app/src/test/java/org/apache/roller/weblogger/security/PasswordEncodingTest.java
git commit -m "Dev bootstrap: .roller-dev-secret, seeding, and ./roller token

./roller db|dev|reset now seeds the dev admin from .roller-dev-secret,
generating that file with umask 077 on first use and printing the credential
once. No branch stops to ask for input: a fresh checkout runs ./roller dev and
gets a working login. The .gitignore entry lands in this same commit -- the
first code that writes the file -- so there is never a window where it is
writable but tracked.

./roller token pipes the password into roller-api auth login --password-stdin,
making the manual API test path ./roller dev, ./roller token, call endpoints.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Documentation and full gates

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/api/README.md`

- [ ] **Step 1: Write the failing test**

`OpenApiDocumentTest` already pins claims in `docs/api/README.md` as text. Add
one more there:

```java
    @Test
    void theReadmeRecordsTheLocalBootstrapPath() throws Exception {
        String readme = Files.readString(readmePath());
        assertTrue(readme.contains("./roller token"),
                "the README must record how to get a token locally -- there is no UI for minting one");
    }
```

Reuse whatever helper that class already uses to locate the README.

- [ ] **Step 2: Run it and watch it fail**

```bash
mvn -pl app test -Dtest=OpenApiDocumentTest
```

Expected: FAIL — the README does not mention `./roller token`.

- [ ] **Step 3: Update `docs/api/README.md`**

In the bootstrap section, before the existing `roller-api auth login` recipe:

````markdown
### Local development

```bash
./roller dev      # starts postgres, seeds the dev admin, runs the app
./roller token    # mints a bearer token for that admin
```

The dev admin password lives in `.roller-dev-secret`, generated on first run
and git-ignored. `./roller db` re-seeds it, correcting the row if it drifts.

For any other environment, `auth login` prompts, or reads the password from
stdin:

```bash
printf '%s\n' "$PASSWORD" | roller-api auth login \
    --url https://example.com/roller --user admin --password-stdin
```
````

- [ ] **Step 4: Update `CLAUDE.md`**

Add to the Configuration scope section, replacing the
`passwds.encryption.enabled` mention in the "Deliberately NOT promoted" list:

```markdown
- **Password encryption is not configurable at all any more.**
  `passwds.encryption.enabled`, the unconditional `noop` encoder registration,
  and `lazyUpgradeFrom=plaintext` were all removed — each was a way to get a
  plaintext password into `roller_user.passphrase`, and the dev server used the
  first one by default. An explicitly-set `passwds.encryption.enabled` now
  throws at startup rather than being ignored. Only
  `passwds.encryption.algorithm` remains (bcrypt/pbkdf2/scrypt/argon2), and
  `PasswordEncodingTest` fails if any of this regresses.
- **The dev admin credential lives in `.roller-dev-secret`** (git-ignored,
  generated with `umask 077` on first `./roller db|dev|reset`, printed once).
  `bin/db/seed-dev-data.sql` applies it, hashing with pgcrypto **inside
  Postgres** so no host bcrypt tool is needed. It is **not** under
  `bin/db/migrations/` and never reaches production. Its `ON CONFLICT` guard
  must stay a `CASE`: `crypt()` raises `invalid salt` on a non-salt argument
  and PostgreSQL does not guarantee short-circuit order inside `OR`, so an `OR`
  chain aborts on exactly the `{noop}` row the seed exists to repair. The file
  is the source of truth — a password changed through the UI is reverted by the
  next seed, deliberately.
- **`./roller token`** mints an API token for that admin via
  `roller-api auth login --password-stdin`. Manual API testing is `./roller
  dev`, `./roller token`, call endpoints.
```

- [ ] **Step 5: Run the full gate set**

Check nothing else is building first:

```bash
pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || echo CLEAR
```

Then:

```bash
mvn clean install
mvn -pl app jacoco:report && bin/check-diff-coverage.sh HEAD~5
mvn verify -Pit
```

Expected: all green; JaCoCo floors met; diff coverage ≥90%; ITs pass. If a
stale `roller-it-postgres` container blocks the ITs with a 409, run
`docker rm -f roller-it-postgres` and retry.

- [ ] **Step 6: Verify the acceptance criteria by hand**

Walk the spec's 17 criteria. The ones no automated test covers:

```bash
# AC5: fresh-checkout behaviour
mv .roller-dev-secret /tmp/secret-backup && ./roller db
ls -l .roller-dev-secret     # expect -rw------- and a printed credential
mv /tmp/secret-backup .roller-dev-secret && ./roller db

# AC15: a real token against a running server
./roller dev &   # then, once it is up:
./roller token
```

- [ ] **Step 7: Commit**

```bash
git add CLAUDE.md docs/api/README.md app/src/test
git commit -m "Document the dev credential path and the removed encryption flag

CLAUDE.md records that password encryption is no longer configurable, where the
dev credential lives, and why the seed's ON CONFLICT guard must stay a CASE.
docs/api/README.md gains the local bootstrap, since there is no UI for minting
a token and the README is the API's front door.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage.** All 17 acceptance criteria map to a task: AC1-4 → Task 2;
AC5-8 → Tasks 3 and 5; AC9-10 → Task 3; AC11-12 → Task 1; AC13 → Tasks 2, 3, 5;
AC14 → Task 4; AC15 → Task 5; AC16-17 → Task 6.

**Known gap, recorded rather than hidden.** `PasswordEncodingTest` grows across
three tasks (2, 3, 5) rather than landing whole. This is deliberate — each
assertion is written in the task where it can first fail for the right reason —
but it means no single commit shows the full guard. Task 6's gate run is where
it is exercised complete.

**Sequencing hazard.** Task 1 must precede Task 2: deleting the config flag
while fixtures still assert `{noop}` literals would fail confusingly. Task 5
depends on both 3 and 4.

**Low-risk discovery.** The unit-test JVM may never call
`createPasswordEncoder()` at all (nothing publishes the encoder outside a Spring
context, which is why the fixtures install one by reflection). If so, removing
the flag from `roller-custom.properties` is a no-op there — expected, not a
sign the change did nothing.
