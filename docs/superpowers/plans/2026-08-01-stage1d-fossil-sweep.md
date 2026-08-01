# Stage 1D — Fossil Sweep + Docs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete every verified fossil (dead code, dead config, dead assets, orphaned i18n), rewrite README/CLAUDE.md to the truth, modernize the test toolchain (JUnit 6), and clean the pom of BOM-redundant pins — leaving the tree honest end to end.

**Architecture:** Executed strictly from the verified inventory at `.superpowers/sdd/2026-08-01-stage1d-fossil-sweep/fossil-inventory.md` (every item carries reference-proofs; §-numbers below cite it). Ordering is dependency-safe: zero-blocker deletes → small-blocker deletes → the spam unit (with its migration) → i18n sweep (catches newly-orphaned keys) → docs → pom/JUnit-6 → final re-ratchet + battery. The editor-plugin refactor (§6) is explicitly deferred to Stage 1E.

## Global Constraints
- The inventory file is normative; deviations from it require re-verification with the same rigor (grep-proof every "zero references" claim you rely on).
- `ApprovalStatus.SPAM` and everything in the inventory's KEEP lists are untouchable.
- Schema changes = numbered migrations (next free V number), idempotent, passing `SchemaMigrationTest`.
- Mid-sweep verification is `mvn -ntp -pl app test` ONLY (floors move during deletion); full `verify` + floor re-set happens once, in the final task, per the inventory's "Ratchet caution" — the one sanctioned floor-down case, documented in that commit body.
- FOREGROUND builds; Docker available; commits per task with the repo trailers.
- ITs (`mvn -ntp verify -Pit`) run in the final task and after the spam unit (it touches PageServlet + a migration).

## Tasks

### Task 1: Zero-blocker deletes (inventory §1, §4-partial, §6-partial, §3-partial)
One commit. Delete: RSD chain (all 8 sites + tests + template-guide doc lines, §1); `jquery-2.1.1.min.js`, `jquery.mobile-theme/`, `theme/blue/`, `theme/sun/` (§4, NOT tan yet); `doap_roller.rdf`, `testing/` dir, `binary-includes/` + `assembly-release/` module dir (§6); `TaskRunner` + `StandaloneWebappClassLoader` + its test + the two `docs/examples` script dirs (§6); `LinkbackExtractor` + test + ROME dep + `rome.version` + both log4j2 logger entries (§6); the eight zero-reader config keys + the `hibernate.` prefix branch (§3); `angular.version` property (§9). Each deletion: grep-proof zero remaining references in the report. Suite: `mvn -ntp -pl app test` green (expect count to DROP — record before/after). Commit: "Delete verified fossils with zero blockers".

### Task 2: Small-blocker deletes (§3-rest, §4-tan, §6-rest)
One commit. (a) Fix `themes/frontpage/_css.vm:319,322` (replace the two tan GIF references with equivalent CSS or theme-local copies), then delete `theme/tan/`. (b) Move `TestTask` to `src/test/java` (same package), fix `TaskLockTest:60` import, update `roller-custom.properties` registration, REMOVE the dev-server registration from `roller-boot-dev.properties`. (c) Delete the `uploads.migrate.auto` chain (~170 lines in JPAMediaFileManagerImpl + key). (d) Delete `securelogin.enabled` key + `SecurityConfig` branch/entry-point + its `SecurityConfigTest` block. (e) Delete `weblogger.provider.class` key + no-arg `WebloggerFactory.bootstrap()` + switch `TestUtils:71` to the explicit provider. (f) Strip `RollerDatabaseExtension` to a static schema utility. Suite green; also run `-Dtest=SecurityConfigTest,TaskLockTest,JPAPersistenceStrategyTest,SpringWebloggerProviderTest` explicitly. Commit: "Delete fossils behind small blockers".

### Task 3: The spam unit (§2)
One commit incl. migration. Delete per §2(a) validators chain and §2(b) banned-words/referrer unit INCLUDING the weblog-level banned-words feature (UI, bean, controller preview, wrapper, ORM mapping, `Weblog` accessors) with migration `V<next>__drop_bannedwordslist.sql` dropping the `weblog.bannedwordslist` column (idempotent `ALTER TABLE ... DROP COLUMN IF EXISTS`). Keep `ApprovalStatus.SPAM` + moderation UI untouched (§2c). Fix `TestUtils.setupWeblog` (`setBannedwordslist("")` call dies) and the two asserting tests. Run: suite + `SchemaMigrationTest` + `mvn -ntp verify -Pit` (PageServlet changed + migration must apply in the IT harness). Commit: "Remove the unreachable spam machinery".

### Task 4: i18n sweep (§5)
Follow §5's six-step approach exactly (runtimeConfigDefs `key=` false-positive check FIRST; delete en orphans incl. keys newly orphaned by Tasks 1-3; prune all 7 translations to the en intersection; convert the orphan-report test into a ratcheting assertion at the new count). `MessageKeyTest` fully green. Expect ~1,900+ lines deleted. Commit: "Sweep orphaned message keys and prune translations".

### Task 5: Docs truth (§7, §10)
Rewrite `README.md` wholesale per §7 (accurate feature list, Boot 4.1/Tomcat 11/Java 25 stack, `./roller dev` + `java -jar` + Docker deployment paths, four themes, real feeds/OpenSearch claims, honest i18n note). Apply the four `CLAUDE.md` fixes + add `boot/` to the package tree (§10). Cross-check every technical claim against the tree (the Task-9-of-1B discipline: no claim without a code anchor). Commit: "Tell the truth in README and CLAUDE.md".

### Task 6: Toolchain — surefire bump, JUnit 6, pom/BOM cleanup (§8, §9)
Three commits in one task: (1) surefire/failsafe 3.5.2 → latest 3.5.x, suite green (attributability). (2) JUnit 6: remove junit-bom pins + testcontainers-bom re-import + launcher version per §8; verify `RollerTestBootstrap` (LauncherSessionListener) still fires (the suite bootstraps the container — if the DB harness works, it fired); instancio check — if `InstancioExtension` breaks, bump instancio to the latest 5.x/6.x that supports Jupiter 6; FULL suite green. (3) pom/BOM cleanup per §9's table (drop redundant/downgrading versions; investigate `jakarta.xml.bind-api` removal with `mvn dependency:analyze` + a `java -jar` boot smoke; keep the KEEP-list untouched). Commits: "Bump surefire", "Move to JUnit 6 under Boot's BOM", "Drop BOM-redundant version pins".

### Task 7: Final — re-ratchet + battery
Re-measure coverage on the post-sweep tree; set bundle line/branch + rendering PACKAGE floors at measured-minus-1pt (floors may legitimately move down — code deletion, documented in the commit body per the Ratchet caution); `mvn -ntp -pl app clean verify` green; `mvn -ntp verify -Pit` green; `bin/check-diff-coverage.sh <plan-base>` (deletions + docs → expect trivially high; any new lines from Task 2/3 edits must be covered). Ledger any leftovers for 1E. Commit: "Re-ratchet coverage floors after the sweep".

## Self-Review
- Inventory coverage: every § lands in a task except §6-editor-plugins (explicit 1E deferral, recorded). All KEEP items protected by constraint.
- Ordering matches the inventory's dependency-safe sequence; i18n after the deletes so newly-orphaned keys die in one pass; ITs run where the runtime changed (Task 3) and at the end.
- The one floors-down exception is bounded, justified, and audit-trailed.
- No placeholders: every task cites exact inventory sections whose line references were verified at HEAD 4a611e551.
