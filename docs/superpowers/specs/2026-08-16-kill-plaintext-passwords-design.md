# Kill Plaintext Passwords, and Make Dev/API Auth Testable

**Date:** 2026-08-16
**Status:** Approved for planning

## Problem

`app/src/test/resources/roller-boot-dev.properties:23` sets
`passwds.encryption.enabled=false`, commented "use plain text passwords in
testing". `RollerContext.createPasswordEncoder()` reads that flag and, when it
is false, reassigns the `DelegatingPasswordEncoder`'s *encoding* id to `noop`
and logs `New passwords are stored in plain text!`. Every password the dev
server writes is therefore stored in the clear. The live dev database holds
exactly one user — `admin`, `{noop}old-dev-password`.

`roller-custom.properties:14` sets the same flag for the unit-test JVM.

This matters more than it did before the automation API shipped: `POST
/api/v1/tokens` is HTTP Basic-authenticated, so the stored password is the
root of trust for every bearer token the API issues.

### The four plaintext paths

1. **`passwds.encryption.enabled=false`** — flips the encoding id to `noop`
   (`RollerContext:190-203`). The two properties files above are the only
   places it is set. This is what put `{noop}old-dev-password` on disk.
2. **`encoders.put("noop", NoOpPasswordEncoder…)`** (`RollerContext:186`) —
   registered unconditionally, so a `{noop}` value authenticates regardless of
   how the flag is set.
3. **`passwds.encryption.lazyUpgradeFrom=plaintext`** (`RollerContext:168`) —
   registers a NoOp encoder against the **null** prefix, so an *unprefixed*
   bare string in `passphrase` authenticates as plaintext. Dormant today (the
   default is `SHA`) but it is the same hole in a different hat.
4. **`TestUtils.setupUser` calls the raw `setPassword("password")`** — not
   `resetPassword`, so it bypasses the encoder entirely and writes a bare
   unprefixed string at 106 call sites. Those users cannot authenticate (no
   `{id}` prefix), which is why no unit test has ever exercised a real login —
   but path 3 would make every one of them a working plaintext credential.

### What is already correct

`roller.properties` ships `enabled=true` / `algorithm=bcrypt`.
`User.resetPassword` (`User.java:117-120`) is a single chokepoint that always
encodes. `it-selenium/src/test/resources/seed-it-data.sql` seeds a genuine
bcrypt hash, with a comment explaining the choice, and `ApiIT` runs the full
Basic-mint → Bearer-use → revoke bootstrap against it. The pattern this spec
wants already exists in the repo; it just never reached dev or unit tests.

## Goals

1. No configuration, in any file or environment variable, can cause Roller to
   store a plaintext password. The capability is removed, not defaulted off.
2. A fresh checkout produces a working dev login with nothing to configure.
3. The dev password is never committed.
4. Minting an API token for manual testing is one command.
5. A test fails if any of this regresses.

## Non-goals

- Changing the production password policy, algorithm, or bcrypt strength.
- Touching `seed-it-data.sql`, `ApiIT`, or anything in the IT tier. It is
  already correct.
- Password rotation, complexity rules, or lockout policy.

---

## 1. Remove the plaintext capability

### `RollerContext.createPasswordEncoder()`

- **Delete the `passwds.encryption.enabled` branch.** The encoding id is
  unconditionally `passwds.encryption.algorithm`, which remains configurable
  across `bcrypt` / `pbkdf2` / `scrypt` / `argon2`. The `else` branch that
  assigns `algorithm = "noop"` and logs the plaintext warning is deleted.
- **Delete `encoders.put("noop", …)`.** No `{noop}` value decodes. This closes
  the path even for data already on disk, so the flag cannot be smuggled back
  in through a stored value.
- **Delete `plaintext` as an accepted `lazyUpgradeFrom` value.** `SHA`, `MD5`,
  and empty remain. An explicit `plaintext` now hits the existing
  "is no valid encoding to upgrade from" throw.

### Fail loudly on an explicit setting

`passwds.encryption.enabled` present in any properties file or as
`ROLLER_PASSWDS_ENCRYPTION_ENABLED` **throws at startup** with a message
naming the property and stating that encryption is always on.

Implemented at the top of `createPasswordEncoder()` as
`if (WebloggerConfig.getProperty("passwds.encryption.enabled") != null) throw …`.
Because the property is deleted from `roller.properties` in the same change,
`getProperty` returns null unless a deployer set it explicitly — the check
detects exactly the case it is meant to catch, and env overrides land in the
same map, so `ROLLER_PASSWDS_ENCRYPTION_ENABLED` is covered by the same line.

Silently ignoring it is the failure mode this spec exists to prevent: a
production deploy carrying `ROLLER_PASSWDS_ENCRYPTION_ENABLED=false` would boot
looking configured and behave differently than its operator believes. This
matches the precedent CLAUDE.md already records for an unsupported
`authentication.method` value.

### Delete the property from our own configs

Remove the `enabled=false` lines from `roller-boot-dev.properties` and
`roller-custom.properties`, and the `passwds.encryption.enabled=true` line from
`roller.properties` — the property no longer exists at any layer.

### Known consequence

The existing dev login stops working the moment this ships. Section 2 corrects
the row in place on the next `./roller db`; no reset and no manual SQL.

Any *other* `{noop}` row anywhere stops authenticating with no upgrade path.
This is accepted: the fork has never shipped a release that could produce one
outside a local dev database.

---

## 2. Dev bootstrap

### `.roller-dev-secret` (git-ignored, mode 0600, repo root)

Shell-sourceable, one key today:

```
ROLLER_DEV_ADMIN_PASSWORD=<generated, or chosen by the developer>
```

`.gitignore` gains the entry in the **same commit** that creates the first
reader, so there is never a window in which the file is writable but tracked.

### `bin/db/seed-dev-data.sql`

Modelled on `seed-it-data.sql`, including a comment explaining why the hash is
real bcrypt rather than a shortcut. It seeds user `admin` with the `admin` and
`editor` roles.

**Hashing happens inside Postgres.** `pgcrypto`'s
`crypt(pw, gen_salt('bf', 10))` emits `$2a$10$…`, which Spring's
`BCryptPasswordEncoder` accepts (verified against the running dev container).
The seed does:

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;
...
'{bcrypt}' || crypt(:'devpw', gen_salt('bf', 10))
```

The password arrives as a psql variable (`-v devpw=…`), never interpolated into
SQL text. This needs no host-side bcrypt tool — no `htpasswd`, no Python
`bcrypt`, no JVM for a database-only command.

**`ON CONFLICT (username) DO UPDATE`, guarded so it writes only when the stored
value is actually wrong** — deliberately unlike the IT seed's `DO NOTHING`.
Correcting in place is what un-bricks the existing `{noop}` row without a reset
or manual SQL:

```sql
ON CONFLICT (username) DO UPDATE
   SET passphrase = '{bcrypt}' || crypt(:'devpw', gen_salt('bf', 10))
 WHERE CASE
         WHEN roller_user.passphrase LIKE '{bcrypt}$2%'
           THEN substring(roller_user.passphrase from 9)
                <> crypt(:'devpw', substring(roller_user.passphrase from 9))
         ELSE true   -- null, {noop}, or anything not a bcrypt hash: rewrite
       END;
```

`substring(… from 9)` strips the 8-character `{bcrypt}` prefix, and pgcrypto's
`crypt(candidate, stored_hash) = stored_hash` is the standard verify idiom
(confirmed against the running dev container: the correct password matches, a
wrong one does not). A correct row is left untouched, salt included.

**The `CASE` is load-bearing and must not be flattened into an `OR` chain.**
`crypt()` raises `ERROR: invalid salt` when its second argument is not a usable
salt — `crypt('x','')` errors — so a `passphrase` of exactly `'{bcrypt}'`, or
any row whose remainder is too short, would abort the seed on the very row it
exists to repair. PostgreSQL does not guarantee short-circuit evaluation order
within `OR`, so a `NOT LIKE` sibling clause is not reliable protection;
`CASE` does guarantee it. The `LIKE '{bcrypt}$2%'` test is deliberately narrow
— every bcrypt hash begins `$2` — so anything else takes the `ELSE true` branch
and is rewritten without `crypt()` ever being called on it.

The existing `{noop}old-dev-password` row would in fact survive a naive `OR` version,
but only by accident: `{noop}` is 6 characters, not 8, so `substring(from 9)`
yields `icken123`, which DES-crypt happens to accept as a 2-character salt and
which of course fails to match. Correct outcome, wrong reason — do not rely on
it.

The guard buys an exact invariant rather than a hedge: the seed is a true no-op
when the row is already right, so "running it twice changes nothing" is
assertable (AC7). Without it, any *unintended* write to that row would hide
behind "the salt changed anyway".

What the guard does **not** change: `.roller-dev-secret` remains the
unconditional source of truth. A password changed through the Profile screen
will not verify against the file either, so it is still reverted on the next
`./roller db`. That is the intended invariant for a disposable local database
whose purpose is a *known* credential.

### `./roller` integration

`db`, `dev`, and `reset` call a new `seed_dev_data` after `run_migrations`.
`migrate` and `status` do not — they stay pure.

`seed_dev_data`:

- **File present** → source it, seed/update `admin` to that password.
- **File absent** → generate a strong random password (`openssl rand`), write
  the file with `umask 077`, print it once prominently, then seed.

There is no branch that stops and asks the developer for input. A fresh
checkout runs `./roller dev` and gets a working login plus the credential
printed to the terminal.

### Password choice

The generated default is random. A developer who prefers a memorable string
edits the file; nothing else changes. No password is committed in any case.

---

## 3. Test fixtures

### `TestUtils.setupUser`

Replace `setPassword("password")` with a **precomputed `{bcrypt}` constant** for
a known plaintext, exposed as `TestUtils.TEST_PASSWORD` /
`TestUtils.TEST_PASSWORD_HASH`.

A constant costs zero CPU across all 106 call sites — the reason the flag was
turned off in the first place was almost certainly bcrypt's deliberate slowness
— while making the stored value a real hash. For the first time a unit test can
drive a genuine authentication rather than only asserting on stored strings.

### The two `ControllerTestFixture` classes

`ui/controllers/admin/ControllerTestFixture:135-137` and
`ui/controllers/core/ControllerTestFixture:159-161` install a noop-encoding
`DelegatingPasswordEncoder`. They get a real `BCryptPasswordEncoder`.

The roughly 14 `assertEquals("{noop}secret", user.getPassword())` assertions in
`CreateUserBeanTest`, `UserEditControllerTest`, `ProfileBeanTest`,
`ProfileControllerTest`, and `PasswordResetControllerTest` become
`assertTrue(encoder.matches("secret", user.getPassword()))` — a better
assertion regardless, since it tests the property that matters instead of a
literal.

---

## 4. Regression guard: `PasswordEncodingTest`

Deleting code does not prevent its return. This repo's habit is a test that
pins the invariant (`ProductionComposeTest`, `DesignTokenTest`,
`ControllerMetadataTest`). Four assertions:

1. For every supported algorithm, the encoder built from real configuration
   never returns its input from `encode()`.
2. No `{noop}`-prefixed value authenticates against the built encoder.
3. No properties file in the repository mentions
   `passwds.encryption.enabled`, and none sets `lazyUpgradeFrom=plaintext`.
4. Every password literal under `bin/db/**` is `{bcrypt}`-prefixed, and no
   `bin/db/migrations/V*.sql` contains a password literal at all — which also
   pins that the dev seed never migrates into production.

`AdminApiTest:56` and CLAUDE.md's "deliberately NOT promoted" list both name
`passwds.encryption.enabled`; both are updated. The property cannot be promoted
to runtime scope if it does not exist.

---

## 5. API auth testing

### `roller-api auth login --password-stdin`

The standard non-interactive idiom (`docker login` uses it). The password
crosses on stdin — never an argv entry, never an environment variable — so it
is safe for CI as well as dev. The existing interactive prompt is unchanged
when the flag is absent.

### `./roller token`

Sources `.roller-dev-secret`, pipes the password to
`bin/roller-api auth login --password-stdin` against
`http://localhost:8083/roller`, and reports the resulting token.

The manual API test path becomes: `./roller dev`, `./roller token`, call
endpoints.

### Unit coverage

With a real hash in `TestUtils`, a unit-level test can cover
`POST /api/v1/tokens` authenticating against a genuinely stored password —
a path reachable today only through the 16-minute IT tier. `ApiIT` is unchanged.

### Why the IT seed keeps its committed hash

CI has no git-ignored file to read, and the IT credential is deliberately fixed
and public so a failing run is reproducible. The spec records the difference so
a later reader does not "unify" the two and break CI.

---

## Acceptance criteria

1. `passwds.encryption.enabled` appears in no source file, properties file, or
   documentation except as a rejected legacy name.
2. Starting the app with `passwds.encryption.enabled` set — by file or by
   `ROLLER_*` environment variable — fails at startup with a message naming the
   property.
3. `RollerContext.createPasswordEncoder()` registers no `noop` encoder, and a
   `{noop}<plaintext>` stored value fails authentication.
4. `passwds.encryption.lazyUpgradeFrom=plaintext` throws at startup.
5. `./roller db` on a checkout with no `.roller-dev-secret` creates the file
   mode 0600, prints the generated password once, and leaves `admin`
   authenticating with a `{bcrypt}`-prefixed `passphrase`.
6. `./roller db` on a checkout **with** `.roller-dev-secret` leaves `admin`
   authenticating with that file's password, `{bcrypt}`-prefixed.
7. Running `./roller db` a second time with an unchanged `.roller-dev-secret`
   writes nothing: `roller_user.passphrase` for `admin` is byte-for-byte
   identical before and after, salt included.
8. The guard fires when it should, not merely never: after editing
   `.roller-dev-secret` to a different password, `./roller db` rewrites
   `passphrase`, and the new password authenticates while the old one does not.
   A `{noop}` row is likewise rewritten. (Without this, criterion 7 would be
   satisfied by a guard that never updates anything.)
9. `.gitignore` contains `.roller-dev-secret`, committed no later than the file's
   first reader.
10. `bin/db/seed-dev-data.sql` is not under `bin/db/migrations/`, and
   `SchemaMigrationTest` still passes.
11. No `bin/db/migrations/V*.sql` contains a password literal.
12. `TestUtils.setupUser` stores a `{bcrypt}`-prefixed value, and a unit test
    authenticates a `setupUser` user through the real encoder.
13. Neither `ControllerTestFixture` references `NoOpPasswordEncoder`; no
    assertion in the test tree compares against a `{noop}` literal.
14. `PasswordEncodingTest` exists and its four assertions pass.
15. `roller-api auth login --password-stdin` mints a token without prompting
    and without the password appearing in argv or the environment.
16. `./roller token` prints a usable bearer token against a running dev server.
17. `mvn clean install` passes with JaCoCo floors met;
    `bin/check-diff-coverage.sh` passes; `mvn verify -Pit` passes.

## Hazards

- **Lockout.** Anyone whose only credential is a `{noop}` row loses access.
  Mitigated for the known case (the dev `admin` row) by the `DO UPDATE` seed.
- **`pgcrypto`.** Present in the `postgres:16` image; `CREATE EXTENSION IF NOT
  EXISTS` in the dev seed only, never in a migration.
- **Test-fixture churn.** ~14 assertions across 5 test classes change shape.
  They must change to `encoder.matches(...)`, not be deleted.
- **A committed `.roller-dev-secret`.** Prevented by ordering the `.gitignore`
  entry no later than the file's first reader, and by acceptance criterion 9.
