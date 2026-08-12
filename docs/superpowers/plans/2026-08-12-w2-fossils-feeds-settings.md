# W2 — Fossils, Feeds, Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Remove eight independent fossils and fix one gating bug, reaching the program's "minimal working system" checkpoint.

**Architecture:** Unlike W1, these removals are **largely independent** — calendar, feeds, members and analytics do not touch each other. Tasks are therefore ordered by risk (smallest and most isolated first) rather than outside-in. Each task removes its own Java fields AND their `.orm.xml` mappings; **one migration (`V023`) at the end drops every column in a single statement group**. A column whose mapping is already gone is inert, so this ordering is safe.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring MVC, JPA/EclipseLink, PostgreSQL 16, Velocity, JSP/JSTL, JUnit 5, Selenide.

## Global Constraints

- **THE VELOCITY RULE — W1's most expensive lesson.** `velocity.properties` sets no `runtime.references.strict` and sets `runtime.log.invalid.reference=false`. A template reference to a deleted Java member **prints as literal text into the page** — no exception, no failing test, no log line. W1 shipped two such bugs. **Every task must run `grep -rn -i "<what you deleted>" app/src/main/webapp/themes app/src/main/webapp/WEB-INF/velocity` and report the result.** This is not optional and not satisfied by the unit suite.
- **`java.util.Calendar` is not the calendar feature.** `AbstractWeblogEntriesPager`, `WeblogEntriesMonthPager`, `WeblogEntriesDayPager`, `Weblog.java:641`, `SiteModel.java:424`, `DateUtil` all use the JDK class. Date archives stay.
- **`localesList`, `weblog.locale` and `user.locale` all stay.** Only `enableMultiLang`/`showAllLangs` and the per-*entry* locale select are multi-locale.
- **`it-selenium/src/test/resources/seed-it-data.sql` is unchecked raw SQL.** A migration dropping a column it writes fails the whole browser suite at fixture load. Task 9 owns this.
- **Schema changes: `V023` only.** Idempotent, never edit an applied migration. `V024`/`V025` are reserved for W4/W5.
- **Message bundles:** `ApplicationResources_fr.properties` stores literal UTF-8 accents; the other six store `\uXXXX`. Deletion-only, whole lines, no reformatting.
- **`MessageKeyTest.KNOWN_DYNAMIC_KEY_COUNT` is 47** and may only ever move DOWN. Deleting a JSP/menu item without its keys fails the build.
- **Deleting a route means deleting its `Routes.java` entry** in the same commit, or `RouteSweepIT` fails.
- **Controllers must name `@RequestParam`/`@PathVariable` explicitly** — no `-parameters` in this build. `ControllerMetadataTest` enforces it.
- **Do not touch:** `util/GenericThrottle`, `Utilities.addNofollow`/`UtilitiesModel.addNofollow`, `ConfigModel.getTrackbacksEnabled` (judged separately), or `roller_audit_log.comment_text`.
- **No `git add -A`.** Stage exactly what you changed. One commit per task.
- Run the FULL unit suite (`mvn -pl app test`) before finishing any task.

---

### Task 1: Calendar subsystem

**Files:** Delete `ui/core/tags/calendar/` (`CalendarTag`, `CalendarModel`, `WeblogCalendarModel`, `BigWeblogCalendarModel`, `package-info`) and `ui/rendering/model/CalendarModel.java`. Modify `weblog.vm` (macros at ~1145 and ~1152, call site at ~45), `ModelLoader` (drop `$calendarModel`). Delete any `*CalendarModelTest`.

- [ ] **Step 1: Prove the JDK class is untouched.** Run `grep -rn "java.util.Calendar" app/src/main/java | wc -l` and record the count. Re-run after your changes — it must be identical.
- [ ] **Step 2: Confirm zero theme callers.** `grep -rn -i "calendar" app/src/main/webapp/themes` → expect no hits.
- [ ] **Step 3: Delete the six files, the two macros, the call site, and the `ModelLoader` registration.**
- [ ] **Step 4: Velocity sweep.** `grep -rn -i "calendar\|calendarModel" app/src/main/webapp/themes app/src/main/webapp/WEB-INF/velocity` → report every hit.
- [ ] **Step 5:** `mvn -pl app test`. Then commit: `W2: the month grid nobody rendered`

---

### Task 2: Multi-locale weblogs

**Files:** `Weblog.java` (`enableMultiLang` 78, `showAllLangs` 79, accessors 491-511), `Weblog.orm.xml` mappings, `WeblogWrapper:236`, `WeblogConfigBean`, `WeblogConfig.jsp:136,144` (+ rail links if the Language section loses entries), `EntryEdit.jsp:178-190`, `EntryEditController` (`localesList` for the *entry* only), `WeblogConfigController:128-129`, `FeedServlet:183`, `PageServlet:457`.

**Interfaces:** An entry still HAS a locale, defaulted from its weblog by `saveWeblogEntry`. You are removing the *picker* and the *site-wide branching*, not the column.

- [ ] **Step 1: Establish what must survive.** Confirm `localesList` still populates `Profile.jsp:86`, `UserEdit.jsp:126`, `CreateWeblog.jsp:72` and the weblog-locale field in `WeblogConfig.jsp`. Those four are NOT yours.
- [ ] **Step 2: Write the failing test** in `WeblogConfigControllerTest`:
```java
@Test
void multiLocaleFieldsAreGone() {
    List<String> offenders = Arrays.stream(WeblogConfigBean.class.getDeclaredFields())
            .map(Field::getName)
            .filter(n -> n.contains("MultiLang") || n.contains("AllLangs"))
            .toList();
    assertTrue(offenders.isEmpty(), "multi-locale fields survive: " + offenders);
}
```
- [ ] **Step 3:** Run it — expect FAIL naming both fields.
- [ ] **Step 4:** Delete the fields, mappings, checkboxes, the `EntryEdit.jsp` locale block (replace the `<c:choose>` with the hidden input the `<c:otherwise>` already provides), and the two servlet branches.
- [ ] **Step 5: Velocity sweep** for `enableMultiLang`, `showAllLangs`, `locale`.
- [ ] **Step 6:** `mvn -pl app test`. Commit: `W2: one language, which is what every weblog here speaks`

---

### Task 3: Blogger API category

**Files:** `WeblogConfigBean` (61, 209-214, 290-291), `Weblog.java` (89, 234-237, 365), `Weblog.orm.xml`, `WeblogWrapper:107-108`, `WeblogConfig.jsp` (the `settings-blogger-api` section + its rail link), `WeblogConfigController:109-120`, `JPAWeblogManagerImpl:292`, `JPAWeblogEntryManagerImpl` (119-120, 148-150, 725).

- [ ] **Step 1: Confirm the API is genuinely absent.** `grep -rli "xmlrpc\|metaweblog\|bloggerapi" app/src` → expect zero.
- [ ] **Step 2:** Read `JPAWeblogEntryManagerImpl:186`. The fallback becomes "first category found" — the existing null path. Confirm that path is reachable and correct before deleting the branch above it.
- [ ] **Step 3:** Delete everything listed. Remove the `websiteSettings.bloggerApi*` message keys from all 8 bundles.
- [ ] **Step 4: Velocity sweep** for `bloggerCategory`.
- [ ] **Step 5:** `mvn -pl app test` (watch `MessageKeyTest`). Commit: `W2: a setting for an API this codebase does not contain`

---

### Task 4: Feeds — Atom only

**The highest-risk task in the wave.** Four live template sites reference what you are deleting.

**Files:** Delete `templates/feeds/{weblog,site}-entries-rss.vm`, `{weblog,site}-search-atom.vm`, `weblog-files-atom.vm`, `roller-ui/styles/rss.xsl`, `SearchResultsFeedModel`, `SearchResultsFeedPager`. Modify `URLStrategy` (+impl, `getWeblogSearchFeedURLTemplate`), `URLModel` (every `rss`/`files` accessor on the feed classes, ~313-350), `FeedServlet` (search branch, and the styled-feed logic at 138 which must keep working for Atom), `weblog.vm` (head links 85, 89, 92, 96; `#showRSSFeedsList` ~1082-1091 whole macro; the `#showAtomFeedsList` call site list), `themes/frontpage/weblog.vm:87`, `roller-ui/styles/atom.xsl` (two body links → https).

- [ ] **Step 1: Enumerate the live sites BEFORE deleting.** Run and record:
`grep -rn "url.feed\|feedStyle\|rss" app/src/main/webapp/themes app/src/main/webapp/WEB-INF/velocity`
Every `rss` hit is a site you must fix or delete. `frontpage/weblog.vm:87` is a visible anchor on the site's front door — it becomes an Atom link, not a deletion.
- [ ] **Step 2:** Delete the six files and the model/pager pair.
- [ ] **Step 3:** Remove the RSS/search/files accessors from `URLModel` and `URLStrategy`, and the `FeedServlet` search branch. Leave Atom entries feeds fully working.
- [ ] **Step 4:** Fix the four template sites. Head autodiscovery keeps only Atom. `#showRSSFeedsList` goes entirely.
- [ ] **Step 5:** `atom.xsl` — change the two `http://www.ietf.org/rfc/rfc4287.txt` body links to `https://`. **Do not touch any `xmlns` declaration** — those are namespace identifiers, not URLs.
- [ ] **Step 6: Velocity sweep.** `grep -rn -i "rss\|openSearch\|searchFeed" app/src/main/webapp/themes app/src/main/webapp/WEB-INF/velocity` → every survivor must be prose or a live Atom path.
- [ ] **Step 7:** `mvn -pl app test`, plus `mvn -pl app test -Dtest=FeedModelTest,URLModelTest,FeedServletRenderingTest`. Commit: `W2: Atom only; the search feed had no subscribers`

---

### Task 5: Legacy free-text analytics code

**Files:** `Weblog.java` (76, 455-459), `Weblog.orm.xml:49-50`, `WeblogConfigBean` (44, 157-161, 200, 224), `WeblogConfig.jsp` (27-32 the `showAnalyticsCodeOverride` computation, 173 the textarea, the section + rail link), `ConfigModel` (`getDefaultAnalyticsTrackingCode` 103, `getAnalyticsOverrideAllowed` 107), `runtimeConfigDefs.xml` (`analytics.default.tracking.code` 185, `analytics.code.override.allowed` 191), `weblog.vm:393-394`, `WeblogWrapper:114`.

- [ ] **Step 1:** Read `weblog.vm`'s `#showAnalyticsTrackingCode` macro in full. The **structured Umami branch must survive untouched** — it is the only working path. You are deleting the legacy `$weblog.analyticsCode` branch and the config-default fallback.
- [ ] **Step 2:** Delete everything listed, plus the `configForm.defaultAnalyticsTrackingCode` / `configForm.allowAnalyticsCodeOverride` keys from all 8 bundles.
- [ ] **Step 3: Velocity sweep** for `analyticsCode`, `analyticsOverrideAllowed`, `defaultAnalyticsTrackingCode`.
- [ ] **Step 4:** `mvn -pl app test -Dtest=ConfigModelTest,PromotedRuntimePropertyTest,MessageKeyTest` then the full suite. Commit: `W2: the analytics box that could never render`

---

### Task 6: Group-blogging ceremony

**Files:** Delete `MembersInviteController`, `MemberResignController`, `MembersInvite.jsp`, `MemberResign.jsp`, `MembersSidebar.jsp`, and their tests. Modify `ObjectPermission.java` (`pending` 36, 98-103), `MailUtil.sendWeblogInvitation`, `MembersController`, `Members.jsp`, `RollerViewResolver`, `editor-menu.xml`, `Routes.java`, `roller.css` (`.pendingCommentBox`).

**W1 handoff:** `Members.jsp:52-53` renders `<span class="pendingCommentBox">` + the key `commentManagement.pending` for pending invitations — copy-paste residue W1 deliberately kept alive because Members still rendered it. **Delete the span, the key from all 7 bundles that have it, and the `.pendingCommentBox` CSS rule in this task.**

- [ ] **Step 1:** Confirm what survives: `MembersController` + `Members.jsp` remain as a grant/revoke list over existing accounts. No invitation, no acceptance, no self-resign.
- [ ] **Step 2:** Delete the controllers, JSPs, view definitions, menu item, `Routes.java` entries, and `MailUtil.sendWeblogInvitation` (every other mailer stays).
- [ ] **Step 3:** Remove `ObjectPermission.pending` and every read of it. Check `WeblogPermission`, `JPAUserManagerImpl` and any named query in `*.orm.xml`.
- [ ] **Step 4:** Delete `commentManagement.pending`, the `pendingCommentBox` span, and the CSS rule. Remove orphaned `members*`/`invite*` keys from all 8 bundles; lower `KNOWN_DYNAMIC_KEY_COUNT` if the true count drops.
- [ ] **Step 5: Velocity sweep** for `pending`, `invite`.
- [ ] **Step 6:** `mvn -pl app test`. Commit: `W2: you can grant access without holding a ceremony`

---

### Task 7: Maintenance moves to Global Admin

**Files:** Move `ui/controllers/editor/MaintenanceController.java` → `ui/controllers/admin/`, remap to `/roller-ui/admin/maintenance.rol`. Move `jsps/editor/Maintenance.jsp` → `jsps/admin/`. Modify `RollerViewResolver`, `editor-menu.xml` (remove item), `admin-menu.xml` (add item), `Routes.java`, `MaintenanceControllerTest`, `MenuHelperTest`, `AnalyticsInjectionIT` (references the route).

- [ ] **Step 1:** The three actions are per-weblog (flush cache, rebuild index, regenerate renditions). The editor version got its weblog from the action context; the admin version needs an explicit weblog selector. Read `MaintenanceController` and decide the smallest correct form — a `<select>` of weblogs posted as a named `@RequestParam`. **Name it explicitly** (no `-parameters` in this build).
- [ ] **Step 2:** Move and remap. Keep the CSS marker `Routes.java` pins for the content tile, and update the `Routes` entry to the new path and `Role.ADMIN`.
- [ ] **Step 3:** Confirm the cache-flush action still reaches `CacheManager` from its new home.
- [ ] **Step 4:** Message keys: `maintenance.*` keys stay but may need a new admin-menu key. Do not orphan any.
- [ ] **Step 5:** `mvn -pl app test`. Commit: `W2: cache flushes and index rebuilds are operator work`

---

### Task 8: Design-tab gating fix

**This is a FIX, not a removal — it widens access. Get the enforcement boundary right.**

**Files:** `editor-menu.xml:71`, `MainMenu.jsp:131`, and read (do not weaken) `ThemeEditController:190-203`.

- [ ] **Step 1: Understand the two different things.** Choosing among `journal`/`portfolio`/`travel` is safe and should always be available to a weblog admin. Converting to a CUSTOM theme is **one-way** and stays behind `themes.customtheme.allowed`. `ThemeEditController:203` is the real enforcement and **must not change**.
- [ ] **Step 2: Write the failing test** in `MenuDefinitionTest`:
```java
@Test
void themeSelectionIsNotGatedOnTheCustomThemeFlag() {
    String xml = menuXml();
    int designIdx = xml.indexOf("tabbedmenu.design");
    String group = xml.substring(designIdx, xml.indexOf("</menu>", designIdx));
    assertFalse(group.substring(0, group.indexOf("themeEdit")).contains("themes.customtheme.allowed"),
            "the Design group and its themeEdit item must not be gated on the custom-theme flag");
}
```
- [ ] **Step 3:** Run it — expect FAIL.
- [ ] **Step 4:** Ungate the group and `themeEdit`. Keep `enabledProperty="themes.customtheme.allowed"` on `stylesheetEdit` and `templates` individually.
- [ ] **Step 5:** `MainMenu.jsp:131` — read what that link targets. If it points at theme selection, ungate it; if at custom templates, leave it gated. State which in your report.
- [ ] **Step 6:** `mvn -pl app test -Dtest=MenuDefinitionTest,MenuHelperTest,ThemeEditControllerTest` then the full suite. Commit: `W2: picking a theme stops depending on a flag about forking one`

---

### Task 9: Migration V023, docs, and the full suite

**Files:** Create `bin/db/migrations/V023__drop_w2_fossils.sql`. Modify `it-selenium/src/test/resources/seed-it-data.sql`, `SchemaMigrationTest` (if it pins table/column expectations), `CLAUDE.md`, `README.md`, `docs/roller-template-guide.adoc`.

- [ ] **Step 1: Write the migration.**
```sql
-- Migration: drop the W2 fossils.
--
-- Multi-locale (no theme ever read it), the Blogger API category (the API is
-- not in this codebase), the free-text analytics override (unreachable while
-- weblogAdminsUntrusted stays on), and the invitation `pending` flag (the
-- invite/accept ceremony is gone; access is granted directly).

ALTER TABLE weblog
    DROP COLUMN IF EXISTS enablemultilang,
    DROP COLUMN IF EXISTS showalllangs,
    DROP COLUMN IF EXISTS bloggercatid,
    DROP COLUMN IF EXISTS analyticscode;

ALTER TABLE roller_permission
    DROP COLUMN IF EXISTS pending;
```
**Check `bloggercatid`'s real column name and any FK constraint on it in `V002` before writing this** — a dropped column with a surviving constraint fails.
- [ ] **Step 2:** `mvn -pl app test -Dtest=SchemaMigrationTest` — it applies the chain twice and proves idempotency.
- [ ] **Step 3: The seed fixture.** `grep -niE "enablemultilang|showalllangs|bloggercatid|analyticscode|pending" it-selenium/src/test/resources/seed-it-data.sql`. Every hit is a column that will not exist. Fix them, or the browser suite fails at fixture load with no test failure to point at it.
- [ ] **Step 4: Documentation.** CLAUDE.md: remove the calendar/multi-locale/Blogger-API/RSS claims; update the **Permutation coverage in the browser suite** section again (tasks 2 and 6 both shrink it); record that Design is now reachable by default and what is still gated. Also fix the two inherited README claims (GHCR-on-every-push; ~2,200 tests → 3117) and the template guide's `getHotWeblogs` references.
- [ ] **Step 5:** `PageServlet.selectTemplate`'s unused `request` parameter — remove it and update callers.
- [ ] **Step 6: Final sweep.** `grep -rniE "calendar|multilang|bloggercat|analyticscode|rss" app/src/main --include=*.java --include=*.jsp --include=*.vm --include=*.xml --include=*.properties` — every survivor must be `java.util.Calendar`, prose, or a live Atom path.
- [ ] **Step 7:** `docker rm -f roller-it-postgres 2>/dev/null; true` then `mvn clean install` and `mvn verify -Pit`. The IT suite takes ~16 minutes; let it finish. Known flake: `ReferenceError: EasyMDE is not defined` — green on rerun; confirm `head.jsp`'s script tag is still unconditional before calling it a flake.
- [ ] **Step 8:** Commit: `W2: V023, the seed, and docs that match the code`

---

## Self-Review

**Spec coverage:** all eight removals plus the gating fix map to tasks 1-8; the migration, seed, docs and inherited W1 items to task 9. The four scoping traps are each pinned in Global Constraints or in the task that would trip them.

**Placeholder scan:** none — every step carries a command or the actual code.

**Type consistency:** `KNOWN_DYNAMIC_KEY_COUNT` (47) referenced identically in tasks 3, 5, 6. `V023` is the wave's only migration, written once in task 9.

**Known risk the plan cannot remove:** task 9's migration drops columns that tasks 2, 3, 5 and 6 stopped mapping — so between those tasks and task 9 the database carries unmapped columns. That is inert for EclipseLink, but it means **no task before 9 can prove its column drop works**. Task 9's `SchemaMigrationTest` step is the only gate on all five drops at once.
