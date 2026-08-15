# Version reset to 0.1.0

Date: 2026-08-15
Status: approved, not yet implemented

## Goal

Renumber this project from `6.2.0` to `0.1.0`, and stop its user-visible output
claiming to be Apache Roller.

The inherited `6.2.0` is upstream Apache Roller's number. This fork has diverged
far enough that carrying it is misleading in both directions: it overstates
maturity for what is an unreleased, never-deployed codebase, and it implies an
upstream identity the code no longer has. Nothing has ever been published — no
image, no release tag, no deployment — so the renumbering is free now and gets
progressively more expensive the moment a `v*.*.*` tag exists.

The fork is called **Roller**. Not "Apache Roller".

## Non-goals

- **Renaming Java packages.** `org.apache.roller.weblogger.*` stays. It touches
  roughly a thousand files, every `.orm.xml` mapping, Velocity and Spring config
  strings, and template references stored in the database — high risk, no
  user-visible benefit, and it would destroy the test suite's value as a safety
  net during the very change that most needs one.
- **Renaming Maven coordinates.** `org.apache.roller:roller-project` /
  `roller-webapp` stay. They appear in the local `.m2` repository and nowhere a
  user looks.
- **Renaming the container images.** `ghcr.io/jakefearsd/roller` and
  `-roller-caddy` are already correct for a fork called Roller.
- **Deleting the 70 inherited upstream git tags** (`roller-5.1.2` …
  `roller-5.2.x`). They are provenance, they cannot collide with the `v*.*.*`
  pattern `release.yml` triggers on, and deleting remote tags is destructive with
  no upside.
- **Renumbering the migration chain.** `V001`–`V025` are schema versions and are
  independent of the application version.

## Decisions

| Decision | Choice | Why |
|---|---|---|
| New version | `0.1.0` | Honest for an unreleased codebase; the break from upstream numbering is the point |
| Scope | Version + user-visible identity | Packages and coordinates are invisible to users; changing them is cost without benefit |
| Name | "Roller" | Honest about lineage, no new name to propagate, and the existing image names already fit |
| Attribution | `LICENSE.txt` and `NOTICE.txt` unchanged | Apache-2.0 requires retaining them regardless of what the fork is called |
| Schema guard | Reworded, not deleted | It is the only thing preventing someone pointing this at a real upstream database |

## The version number

```bash
mvn -Pit versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
```

**The `-Pit` is load-bearing.** `it-selenium` is only a module inside the `it`
profile (`pom.xml:87-92`), and its `<parent>` pins `6.2.0`
(`it-selenium/pom.xml:24-28`). Without the profile active, `versions:set` skips
it and the browser ITs then fail to resolve their parent — a breakage that
appears only when someone next runs `mvn verify -Pit`, long after the change.

`versions:set` does **not** touch the root pom's separate
`<roller.version>6.2.0</roller.version>` property (`pom.xml:46`). That needs a
manual edit.

Everything displayed then follows on its own:
`app/src/main/resources-filtered/roller-version.properties` carries
`ro.version=${project.version}`, which `WebloggerImpl` loads at startup
(`WebloggerImpl.java:104-109`) and exposes through `getVersion()`. That single
value reaches the startup banner (`WebloggerFactory.java:128`), the admin footer,
the frontpage footer, and both Atom `<generator>` tags via
`ConfigModel.getRollerVersion()`.

## User-visible identity

Four strings name Apache Roller to users. All lose "Apache" and the
`roller.apache.org` link:

| File | Current |
|---|---|
| `WEB-INF/velocity/templates/feeds/site-entries-atom.vm:38` | `<generator uri="http://roller.apache.org" …>Apache Roller Weblogger</generator>` |
| `WEB-INF/velocity/templates/feeds/weblog-entries-atom.vm:38` | `<generator uri="http://roller.apache.org" …>Apache Roller</generator>` |
| `themes/frontpage/_footer.vm:4` | `Powered by <a href="https://roller.apache.org">Apache Roller</a> $config.rollerVersion` |
| `ApplicationResources.properties:273-274` | `footer.productName` / `footer.productNameNoVersion` |

`footer.productName` also exists in **three translated bundles** —
`ApplicationResources_zh_CN`, `_de`, `_ja` — and they must be updated too, or the
admin footer keeps saying "Apache Roller Weblogger" in those locales. Note the
translated copies use a one-argument pattern (`Version {0}`) where the base uses
two (`Version {0} ({1})`); `footer.jsp` supplies both and `MessageFormat` ignores
the surplus, so this asymmetry is pre-existing and harmless. Do not "fix" it as
part of this change.

`LICENSE.txt` and `NOTICE.txt` are untouched. Removing the *branding* is the
correct move for a fork — it currently implies an upstream identity the code does
not have — but the *attribution* is a licence obligation, not a style choice.

## The remaining `6.2.0` mentions

Seventeen files mention `6.2.0`, and they mean three different things. Apply by
category, not by search-and-replace:

**"This project's current version"** → becomes `0.1.0`.
`README.md:5`.

**"The point at which something changed"** → reword to name the lineage, because
after renumbering these cite a version this project no longer has.
`README.md:58`, `CLAUDE.md`, `RollerPostgresContainer.java:25`,
`SchemaMigrationTest.java:68,132`, `bin/db/migrations/README.md`,
`V001__schema_migrations.sql:22`, `V002__baseline_schema.sql:15-20`.

The rewording names upstream explicitly — for example "PostgreSQL-only since this
fork diverged from Apache Roller 6.1.x" rather than "PostgreSQL-only as of
6.2.0". These statements are true today; the problem is only that their reference
point disappears with the renumbering.

**Example values in documentation and config** → become `0.1.0`.
`deploy/.env.example:23` (`IMAGE_VERSION`), `.github/workflows/release.yml`
(the release-procedure comment at lines 42-49, the race explanation at line 86,
and the tag-parsing example at line 110), and `docker_deployment.md` (the three
release-download URLs at lines 67-69, and the version-tag example at line 764).
`docker_deployment.md`'s upgrade example uses `6.2.1` at lines 669-674 — that is
this project's version too, and becomes `0.1.1`.

**`docker-compose.prod.yml` is NOT in this list, and must not be swept.** Its two
`6.2.0` mentions (lines 181, 185) are **listmonk's** version — the pin comment
recording that `listmonk/listmonk:v3` did not exist and v6.2.0 was pinned by
digest instead. The collision with this project's old version is a coincidence.
A search-and-replace here would rewrite that comment to cite a listmonk v0.1.0
that does not exist, corrupting the record of why the pin was chosen. Leave both
lines alone.

`ConfigModelTest.java:257,261` uses `"6.2.0"` as a mock return value. It is
arbitrary test data, but it should move to `0.1.0` so a reader does not take it
for a real version.

## The schema guard

`DatabaseInstaller.isCreationRequired()` (`DatabaseInstaller.java:100-108`)
throws a user-facing `IllegalStateException` naming `6.2.0` three times, when it
finds Roller tables but no `schema_migrations` table.

It is reworded, not deleted. The new message references upstream rather than a
local version number: the database has Roller tables but no `schema_migrations`
table, so it predates this fork; there is no in-place upgrade path from Apache
Roller 6.1.x or earlier, nor from any database other than PostgreSQL; export the
content and load it into a fresh database.

Deleting it would be defensible on YAGNI grounds — this fork has never been
deployed, so no database that would trigger it can exist. It stays because it is
the only thing standing between someone pointing this at a real upstream Roller
database and silent schema corruption, and because the cost of keeping it is one
paragraph of prose.

## Verification

- `mvn -Pit clean install` must pass. This is the check that matters: it is the
  only thing that proves all three poms still resolve against each other, which
  is exactly what the `it-selenium` trap breaks.
- `grep -rn "6\.2\.0"` over tracked files, excluding `docs/superpowers/`, must
  return only the deliberate historical-lineage rewordings **and the two listmonk
  pin comments in `docker-compose.prod.yml`**. A clean sweep to zero here is a
  sign the listmonk comment was corrupted, not a sign of success.
- `grep -rn "Apache Roller"` over `app/src/main/webapp/` and
  `app/src/main/resources/ApplicationResources*` must return nothing.
- `roller.war` must still be the built artifact name — it comes from
  `<finalName>roller</finalName>` and not from the version, so the `Dockerfile`'s
  `COPY --from=builder /build/app/target/roller.war` path is unaffected. Confirm
  rather than assume, because a change here breaks the image build silently.
- The full unit suite must stay green, including `ConfigModelTest` and
  `SchemaMigrationTest`, both of which assert on strings this change edits.

## Risks

- **The `-Pit` omission** is the one genuine trap, and its failure is delayed:
  everything builds and passes until someone runs the browser ITs.
- **Version going backwards** (6.2.0 → 0.1.0) is safe here only because nothing
  has been published. There is no Maven artifact in any remote repository, no
  GHCR image, and no `v*.*.*` tag. This must be done before the first release,
  not after.
- **`docs/superpowers/` is excluded from the sweep** deliberately. Those specs
  and plans are historical records of decisions made when the project was
  numbered 6.2.0, and rewriting them would falsify the record.
- **`6.2.0` is ambiguous in this repository.** It is simultaneously this
  project's old version and listmonk's current pinned version. Any mechanical
  find-and-replace will corrupt the latter. Every edit in this change must be
  made by reading the surrounding line, not by pattern.
