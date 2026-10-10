# First-admin bootstrap and account recovery — design

Date: 2026-10-10. Design approved in conversation the same day; this spec
states what "done" means.

## Problem

Self-registration was removed (`73e670e20`), so creating a user is
admin-only (`/roller-ui/admin/createUser`, `/api/v1/admin/users`). A fresh
production install therefore has no way to get its first admin:
`users.firstUserAdmin` exists but nothing can reach it. docker2 was
bootstrapped with hand-written SQL. The runbook still says "register the
first user account, which becomes the site administrator", a page that no
longer exists.

Recovery has the same gap. Roller already has an emailed password reset
(`PasswordResetController`, `PasswordLinkMailer`), but no production install
has had mail configured. A lost admin password has meant SQL again.

## Trust model

The installer is whoever controls the host: they can run `docker compose`
there, and they can read and edit `.env`, which already holds every database
credential. Creating or resetting an admin for that person grants nothing
they did not already have.

It follows that **nothing reachable over HTTP may create an admin or reset a
password without the emailed token**, because HTTP is what goes public
behind Cloudflare. Three alternatives are ruled out for that reason:

- *First visitor becomes admin*: it trusts whoever arrives first.
- *A management-port endpoint*: every container on the `internal` network
  can reach port 8090, so it would trust them too.
- *A setup token in the logs*: it adds an unauthenticated page, and the
  logs may leave the host.

## Design

### D1. An `admin` tool service

`docker-compose.prod.yml` gains a service named `admin`:

- It uses the app's image and tag.
- `entrypoint: ["/app/admin-account.sh"]`, `profiles: ["tools"]`, so `up`
  and `deploy.sh` never start it. `docker compose run` starts a service
  in a profile when the command names it.
- `restart: "no"`, `networks: [internal]`, no `ports`, no `volumes`.
- `depends_on: postgres: service_healthy`.
- It gets only the `POSTGRES_*` variables, as `provision` does, and no
  `env_file`, so it never receives the rest of `.env`'s secrets.

The Dockerfile copies `deploy/admin-account.sh` to `/app/admin-account.sh`
and makes it executable.

Usage, from the deploy directory:

```bash
C="docker compose -f docker-compose.prod.yml"
$C run --rm admin create --username jake --email jake@example.com   # prompts twice
$C run --rm admin reset-password --username jake                    # prompts twice
$C run --rm admin status
printf '%s\n' "$PW" | $C run --rm -T admin create --username jake --email jake@example.com --password-stdin
```

### D2. Subcommands

- **`create --username U --email E [--password-stdin]`** creates an
  enabled account with the `admin` and `editor` roles, which is what
  `JPAUserManagerImpl.addUser` grants a first admin.
  - Row values: `id` is a random UUID, the same form `User` uses.
    `screenname` and `fullname` are the username. `locale=en_US`,
    `timezone=UTC`, `isenabled=true`, `datecreated=now()`.
  - Each role is one `userrole` row with its own UUID id.
  - Nothing else is written.
- **`reset-password --username U [--password-stdin]`** replaces the hash
  of an existing account and touches nothing else. It is the last resort
  when mail itself is broken. If the account is disabled, it says so,
  because a disabled account still cannot log in.
  - It ends by printing the exact command to restart the app. A running
    app caches accounts it has already loaded
    (`JPAUserManagerImpl.userNameToIdMap` plus EclipseLink's shared
    cache), so a password changed underneath it does not take effect
    until the app restarts. `create` has no such issue, because nothing
    has loaded an account that did not exist.
- **`status`** prints the usernames of the enabled admin accounts. It
  exits 0 if there is at least one, and 3 if there are none.
- **No subcommand, or an unknown one,** prints usage and exits 2.

### D3. Validation, before anything is written

Each check fails with a non-zero exit and a message naming the problem.

- **Username:** must match `^[A-Za-z0-9]+$` (the default
  `username.allowedChars`) and be at most 255 characters
  (`ColumnLimits.USERNAME`).
- **Existing username:** `create` refuses one that already exists,
  compared case-insensitively. Unlike `addUser`'s check, this includes
  disabled accounts.
- **Email:** must match `AdminApi`'s `^[^@\s]+@[^@\s]+\.[^@\s]+$`.
- **Password:** at least 8 characters, the minimum the reset page
  enforces (`PasswordResetController.MIN_PASSWORD_LENGTH`).
- **Prompt mode:** the confirmation must match the password.
- **`--password-stdin`:** reads exactly one line, and refuses it if it is
  empty.

Each write is one transaction, so a failure leaves the database unchanged.

### D4. How the password is handled

- **Input:** an interactive prompt with echo off, or one line on stdin.
  The tool never accepts the password as an argument or as an
  environment variable.
- **Output:** it never prints the password.
- **Reaching psql:** the script exports it to psql's environment only.
  psql reads it with `\getenv` (psql 15 or later; the image ships 18) and
  quotes it as `:'pw'`. The password never appears on any process command
  line, and the shell never interpolates it into SQL.
- **Hashing:** inside PostgreSQL, as `'{bcrypt}' || crypt(pw,
  gen_salt('bf', 10))`, after `CREATE EXTENSION IF NOT EXISTS pgcrypto`.
  pgcrypto is a trusted extension, so the database owner can create it.
  The app's `DelegatingPasswordEncoder` accepts `{bcrypt}$2a$…`, and
  login never rewrites the hash.

### D5. `deploy.sh` points at the tool

After `up`, `deploy.sh` runs `admin status`. On exit 3 it prints the exact
`create` command. The deploy still succeeds. Any other non-zero exit from
`status` is reported as a warning and does not fail the deploy.

### D6. Mail and recovery are configuration only

This needs no code.

- `deploy/.env.example` and the runbook show a worked Brevo example:
  - `smtp-relay.brevo.com`, port 587, `mail.security` left at its
    default, `starttls`;
  - the SMTP login as `ROLLER_MAIL_USERNAME`, the key as
    `ROLLER_MAIL_PASSWORD`.
- They also state that reset mail needs **both** the SMTP settings **and**
  a non-blank Admin → Global Config "site admin email", which is used as
  the From address. Until both are set, the forgot-password page shows a
  "mail not configured" notice.
- Reset links are built from the site's absolute URL. They are valid for
  1 hour and work once.

### D7. Documentation

**Repository**

- `docker_deployment.md`, First run section, in this order: create the
  admin, set the Site URL and the admin email, set up mail, then recovery
  (the emailed reset, then `reset-password` as the last resort).
- Every mention of registering the first user goes.
- `deploy/.env.example`: the mail block as in D6.
- `docs/dev/passwords.md`: the tool, the trust model, and the D4 rules.

**Wiki** (wiki.wikantik.com, the live corpus; written through the admin
MCP)

- **`RollerHub`** (new): `type: hub`, `cluster: roller`, audience humans
  and agents, and an "If you are an agent, start here" section like
  `SimpleAgilityStackHub`'s. It links to the pages below and back to
  `SimpleAgilityStackHub`.
- **`RollerOnDocker`** (new, article): the operator guide. It covers
  install, `.env`, LAN vs public mode, the first admin, mail via Brevo,
  recovery, upgrades and backups.
- **`RollerAdminAccountRunbook`** (new, `type: runbook`): the agent
  runbook. Its `runbook:` block carries `when_to_use`, at least 2 `steps`
  and `pitfalls`. The body gives the exact commands and how to verify each
  step.
- **`SimpleAgilityStackHub`** (edit): the Components row for Roller and
  the "each has its own entry page" sentence point to `RollerHub`, and
  `related` gains it.
- **`RollerBlogPlatform`, `RollerW3tPlugins`** (edit): `cluster: [roller,
  web-properties]`. Their content is outdated; the hub labels them as
  history and does not refresh them.

## Acceptance criteria

Each item names how it is checked.

1. **Create works end to end.** On a freshly migrated database, `create
   --username jake --email jake@example.com --password-stdin` with an
   8+-character password exits 0. The app's real login components then
   authenticate `jake` with that password: `RollerUserDetailsService`
   plus `RollerContext.createPasswordEncoder()`, through a
   `DaoAuthenticationProvider`. The account is enabled and has exactly
   the authorities `admin` and `editor`.
   *Test: Testcontainers PostgreSQL 18, running the shipped script.*
2. **Refusals write nothing.** Each D3 violation exits non-zero, names the
   problem on stderr, and leaves `roller_user` and `userrole` unchanged:
   - an existing username, in either case, enabled or disabled;
   - a bad character in the username;
   - a malformed email;
   - a 7-character password;
   - a prompt confirmation that doesn't match;
   - empty stdin.
   *Test: same harness; row counts compared before and after.*
3. **The password never leaks.**
   - It does not appear in any subcommand's stdout or stderr.
   - It does not appear in any argument passed to `psql`.
   *Test: the script runs with a `psql` shim on `PATH` that logs its
   argv and then calls the real psql; the log must not contain the
   password.*
4. **`reset-password` works.**
   - For an existing user, the new password authenticates through the
     criterion 1 path and the old one does not.
   - An unknown user exits non-zero and changes nothing.
   - A disabled user gets the new hash plus a warning on stderr.
   - stdout ends with the app-restart command.
   - A second test records why the restart is needed: once the running
     tier has loaded an account, a reset leaves the old password working.
     If someone removes the cache, that test fails, and the restart
     advice goes with it.
   *Test: same harness.*
5. **`status` reports correctly.** It exits 3 with an explanatory
   message when no enabled admin exists, and exits 0 listing usernames
   otherwise. *Test: same harness.*
6. **The compose service has the D1 shape.**
   - `profiles` contains `tools`.
   - It has no `ports`, no `env_file` and no `volumes`.
   - `networks` is exactly `[internal]`.
   - `image` equals the `app` service's image.
   - `entrypoint` is `/app/admin-account.sh`.
   - The Dockerfile copies and chmods the script.
   *Test: `ProductionComposeTest` and `DockerContextTest`.*
7. **`deploy.sh` hints and still succeeds.** When `status` exits 3, it
   prints the `create` command and exits 0.
   *Test: there is no deploy-script harness yet. A new test runs
   `deploy.sh` with a stub `docker` on `PATH`; the stub answers `status`
   with exit 3 and records every other call.*
8. **The runbook matches the script.** `docker_deployment.md` names every
   subcommand the script's usage text lists, and no longer contains
   "register the first user".
   *Test: a doc-consistency test reading both files.*
9. **The wiki is complete.** Reading back through
   `https://wiki.wikantik.com/api/pages/<name>`:
   - `RollerHub` exists with `type: hub` and `cluster: roller`, and it
     links to `RollerOnDocker`, `RollerAdminAccountRunbook`,
     `RollerBlogPlatform`, `RollerW3tPlugins` and `SimpleAgilityStackHub`.
   - `SimpleAgilityStackHub` links to `RollerHub`.
   - `RollerAdminAccountRunbook` saved, which means it passed the wiki's
     save-time runbook validation.
10. **docker2 runs it all.**
    - Mail is configured, and a reset email for `admin` reaches its
      owner. *Manual: the owner confirms receipt.*
    - After the release, `admin status` on docker2 reports `admin`.
      *Manual.*

## Out of scope

- Hiding the forgot-password link while mail is not configured (the page
  already says so).
- A full Roller manual, and refreshing the outdated Roller wiki pages.
- Password policy on the other user-creation paths.
- LDAP and SSO provisioning.
- Invalidating outstanding reset tokens on `reset-password`.
