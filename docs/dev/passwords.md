# Passwords and the dev credential

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## Passwords
- **Password encryption cannot be configured or turned off.** Gone, all
  four paths that put plaintext in `roller_user.passphrase`:
  `passwds.encryption.enabled=false` (`DelegatingPasswordEncoder` encoding
  id `noop`), the unconditional `noop` encoder (`{noop}` rows authenticated
  anyway), `lazyUpgradeFrom=plaintext` (null-prefix no-op encoder:
  unprefixed strings authenticated), and the `enabled=false` lines in
  `roller-boot-dev.properties` / `roller-custom.properties`. Only
  `passwds.encryption.algorithm` (bcrypt/pbkdf2/scrypt/argon2) remains. An
  explicit `passwds.encryption.enabled` — file or
  `ROLLER_PASSWDS_ENCRYPTION_ENABLED` — **throws at startup**, like an
  unsupported `authentication.method`. A `{noop}` row is refused outright.
  `PasswordEncodingTest` pins it.
- **`TestUtils.setupUser` stores a precomputed `{bcrypt}` constant**
  (`TestUtils.TEST_PASSWORD` / `TEST_PASSWORD_HASH`), not a live `encode()`
  — bcrypt is slow, ~106 call sites (the bare `"password"` it once stored
  never authenticated).
- **The dev admin credential is `.roller-dev-secret`** — git-ignored,
  generated (`umask 077`) on the first `./roller db|dev|reset`, printed once.
  `bin/db/seed-dev-data.sql` applies it via pgcrypto inside Postgres; **not**
  under `bin/db/migrations/`, so it never reaches production. Source of
  truth: the next seed reverts web-UI password changes.
- **The seed's `ON CONFLICT` guard must stay a `CASE`.** `crypt()` raises
  `ERROR: invalid salt` on an unusable salt and PostgreSQL does not
  short-circuit `OR`, so an `OR` chain aborts on exactly the
  `{noop}`/truncated row it repairs. `DevSeedTest` runs the *shipped file*
  over every row shape.
- **`./roller token`** mints an API token for that admin via
  `roller-api auth login --password-stdin`. In `./roller`, never generate
  the secret with `tr -dc … </dev/urandom | head -c N`: under
  `set -o pipefail` `head` exiting early SIGPIPEs `tr` and the script dies
  silently at 141.
