# W1 — Comments Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the comment subsystem from Roller entirely — code, schema, config, i18n, themes and tests — leaving the contact form and newsletter as the reader-interaction channels.

**Architecture:** A pure-subtraction wave executed **outside-in**: reader-facing templates first, then servlets and request plumbing, then the admin UI, then the manager/persistence layer, then the pojos, then schema, then config and tests. Each task deletes only things whose callers are already gone, so the reactor compiles and the suite is green at every task boundary.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring MVC, JPA/EclipseLink, PostgreSQL 16, Velocity (blog rendering), JSP/JSTL (admin UI), JUnit 5, Selenide/Selenium.

## Global Constraints

- **Schema changes require a numbered migration** under `bin/db/migrations/`, idempotent, never editing an applied one. This wave owns `V022` and no other number.
- **`roller_audit_log.comment_text` is NOT a comment column.** It is the audit log's change note. No migration, grep-sweep or edit may touch it.
- **`util/GenericThrottle.java` and `GenericThrottleTest` SURVIVE.** They are shared by `ContactController` (`contact.throttle.*`), `NewsletterController` (`newsletter.subscribe.throttle.*`) and `PasswordResetController` (`passwordreset.throttle.*`). Only `comment.throttle.*` properties and `CommentServlet`'s own throttle field are removed.
- **`weblog.lastModified` bumping must survive.** `saveComment`/`removeComment` were two of its callers; `WeblogPageCache` has no CacheHandler and expires lazily against that timestamp. Entry save, template save, page save and theme switch must still bump it.
- **Deleting a route means deleting its `Routes.java` entry** in the same commit, or `RouteSweepIT` fails on a route that no longer exists.
- **Deleting a JSP means deleting its message keys** from all 8 bundles in the same commit. `MessageKeyTest:162` asserts `unused.size() <= KNOWN_DYNAMIC_KEY_COUNT` (currently `55` at `MessageKeyTest:177`); orphaning keys fails the build. Lower the ratchet to the new actual count — it only ever moves down.
- **Properties-file encoding is per-bundle.** `ApplicationResources_fr.properties` stores literal UTF-8 accents; every other translated bundle stores `\uXXXX` escapes. This matters only for edits; this wave is deletion-only, so delete whole lines and change nothing else.
- **Controllers must name `@RequestParam`/`@PathVariable` explicitly** — the build does not pass `-parameters`. `ControllerMetadataTest` enforces it.
- **Never commit or push beyond what this plan's Commit steps specify.**

## File Structure

| Area | Disposition |
|---|---|
| `themes/{journal,portfolio,travel}/permalink.vm` + `*-custom.css` | modified — comments section and CSS removed |
| `WEB-INF/velocity/weblog.vm` | modified — 3 macros, 2 head links, entry-summary call sites removed |
| `WEB-INF/velocity/templates/feeds/*comments*` (4), `templates/weblog/popupcomments.vm`, `themes/base.css` | deleted |
| `ui/rendering/servlets/Comment*.java` (2), `ui/rendering/util/WeblogComment*.java` (2), `ui/rendering/plugins/comments/` | deleted |
| `boot/ServletRegistrationConfig.java`, `boot/SecurityConfig.java`, `PageServlet.java`, `FeedServlet.java`, `WeblogRequestMapper.java`, `URLModel.java`, `URLStrategy.java` (+impl) | modified |
| `ui/controllers/editor/Comments*.java`, `ui/controllers/admin/GlobalCommentManagement*.java`, `ui/controllers/ajax/CommentDataServlet.java`, both `pagers/CommentsPager.java`, `jsps/editor/Comments*.jsp` | deleted |
| `RollerViewResolver.java`, `editor-menu.xml`, `admin-menu.xml`, `MainMenu.jsp`, `EntryEdit.jsp`, `WeblogConfig.jsp` | modified |
| `business/WeblogEntryManager.java` + JPA impl, `business/plugins/comment/` | modified / deleted |
| `pojos/WeblogEntryComment.java`, `CommentSearchCriteria.java`, `wrapper/WeblogEntryCommentWrapper.java`, `WeblogEntryComment.orm.xml` | deleted |
| `pojos/Weblog.java`, `WeblogEntry.java`, their wrappers, `WeblogConfigBean.java`, `EntryBean.java`, `SiteModel.java` | modified |
| `bin/db/migrations/V022__drop_comments.sql` | created |
| `runtimeConfigDefs.xml`, `roller.properties`, 8 `ApplicationResources*.properties` | modified |
| `CommentIT.java`, `WeblogEntryCommentFormTest.java`, `WeblogCommentRequestTest.java` | deleted |
| `Routes.java`, `GlobalConfigMatrixIT.java`, `WeblogConfigMatrixIT.java`, `MessageKeyTest.java`, `PromotedRuntimePropertyTest.java`, `ReflectionTest.java`, `EqualsContractTest.java`, `PojoComparatorTest.java` | modified |
| `CLAUDE.md` | modified |

---

### Task 1: Reader-facing rendering

Remove every trace of comments from what a reader's browser receives. No Java compiles differently after this task — it is templates, theme CSS and two message-key families.

**Files:**
- Modify: `app/src/main/webapp/themes/journal/permalink.vm`, `themes/portfolio/permalink.vm`, `themes/travel/permalink.vm`
- Modify: `app/src/main/webapp/themes/journal/journal-custom.css` (20 comment lines), `themes/portfolio/portfolio-custom.css` (17), `themes/travel/travel-custom.css` (1)
- Modify: `app/src/main/webapp/WEB-INF/velocity/weblog.vm` (macros at ~992, ~1037, ~1090; head links at 90-91; call sites at 39-41)
- Delete: `app/src/main/webapp/WEB-INF/velocity/templates/weblog/popupcomments.vm`
- Delete: `app/src/main/webapp/themes/base.css`
- Delete: `app/src/main/webapp/WEB-INF/velocity/templates/feeds/weblog-comments-atom.vm`, `weblog-comments-rss.vm`, `site-comments-atom.vm`, `site-comments-rss.vm`
- Modify: `app/src/main/resources/ApplicationResources.properties` + 7 translated bundles — remove `macro.weblog.comment`, `macro.weblog.comments`, `macro.weblog.comment.unknown`, `macro.weblog.commentpermalink.title`, `macro.weblog.commentwarning`, `comments.at`, `comments.disabled`, `comments.email`, `comments.htmlenabled`, `comments.loginRequired`, `comments.loginRequired.link`, `comments.title`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/JournalThemeRenderingTest.java`, `TravelThemeRenderingTest.java`, `PortfolioThemeRenderingTest.java`, `ui/MessageKeyTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `weblog.vm` no longer defines `#showWeblogEntryComments`, `#showMobileWeblogEntryComments` or `#showWeblogEntryCommentForm`. Task 2 relies on `$model.commentForm` having no remaining template reader.

- [ ] **Step 1: Confirm the current state renders comments, so the removal is observable**

Run: `mvn -pl app test -Dtest=JournalThemeRenderingTest`
Expected: PASS. Read the test source and note any assertion mentioning comments — those are the assertions Step 4 replaces.

- [ ] **Step 2: Write the failing assertions**

In each of `JournalThemeRenderingTest`, `TravelThemeRenderingTest`, `PortfolioThemeRenderingTest`, find the permalink-rendering test and add:

```java
assertFalse(rendered.contains("qj-comments"),
        "the comments section must be gone from the permalink");
assertFalse(rendered.contains("commentForm"),
        "no comment form may survive in rendered output");
```

Use the theme's own class in place of `qj-comments`: `pf-comments` for portfolio, `tg-comments` for travel.

- [ ] **Step 3: Run them to verify they fail**

Run: `mvn -pl app test -Dtest=JournalThemeRenderingTest,TravelThemeRenderingTest,PortfolioThemeRenderingTest`
Expected: FAIL — each new assertion trips because the section still renders.

- [ ] **Step 4: Delete the templates, macros, theme markup and CSS**

Delete the six files listed above. In `weblog.vm` remove the three `#macro` definitions in full, the two `<link rel="alternate" ... Comments ...>` head lines (90-91), and the three call sites at 39-41. In each theme's `permalink.vm` remove the whole `<section class="*-comments">` block. In each theme CSS remove the comment rule block.

`base.css` is deleted rather than repaired: `popupcomments.vm:10` is its only reference, and it goes in the same step. This closes the "base.css carries non-token colors" item by deletion.

- [ ] **Step 5: Remove the 12 reader-facing message keys from all 8 bundles**

Delete whole lines only. Do not reformat, re-encode or reorder anything else in those files.

- [ ] **Step 6: Lower the MessageKeyTest ratchet**

Run: `mvn -pl app test -Dtest=MessageKeyTest`
If `reportsBundleKeysNoJspOrControllerUses` reports a count below `55`, set `KNOWN_DYNAMIC_KEY_COUNT` at `MessageKeyTest:177` to the reported count. It only moves down.

- [ ] **Step 7: Run the theme tests and the key test**

Run: `mvn -pl app test -Dtest=JournalThemeRenderingTest,TravelThemeRenderingTest,PortfolioThemeRenderingTest,MessageKeyTest,ThemeCspCoverageTest,DesignTokenTest`
Expected: PASS. `ThemeCspCoverageTest` and `DesignTokenTest` are included because theme CSS changed.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/webapp app/src/main/resources/ApplicationResources*.properties app/src/test/java
git commit -m "W1: readers stop being offered a conversation nobody could join"
```

---

### Task 2: Servlets and request plumbing

**Files:**
- Delete: `ui/rendering/servlets/CommentServlet.java`, `CommentAuthenticatorServlet.java`
- Delete: `ui/rendering/util/WeblogCommentRequest.java`, `WeblogEntryCommentForm.java`
- Delete: `ui/rendering/plugins/comments/` (whole package: `CommentAuthenticator`, `DefaultCommentAuthenticator`, `CommentAuthenticatorUtils`)
- Delete: `ui/rendering/pagers/CommentsPager.java`
- Delete: `app/src/test/java/.../WeblogEntryCommentFormTest.java`, `WeblogCommentRequestTest.java`
- Modify: `boot/ServletRegistrationConfig.java` — remove 3 imports (26, 37, 38), `commentServletRegistration()` (123-129), `commentAuthenticatorServletRegistration()` (131-137), `commentDataServletRegistration()` (155-157), and the `/roller-ui/rendering/comment/*` filter pattern at 275
- Modify: `boot/SecurityConfig.java` — remove the `isPublicCommentPost` CSRF exemption (~266) and the method (~304), keeping the contact/subscribe exemption intact
- Modify: `ui/rendering/servlets/PageServlet.java` — remove `commentForm` wiring and the `_popupcomments` branch (358-390)
- Modify: `ui/rendering/servlets/FeedServlet.java` — remove the `"comments"` feed type branch
- Modify: `ui/rendering/WeblogRequestMapper.java` — remove comment-path forwarding
- Modify: `ui/rendering/model/URLModel.java` — remove `getCommentAuthenticator()` (132), `comment(...)` (172), `comments(...)` (177), `getComments()` (320) and the whole `CommentFeedURLS` inner class (360-377)
- Modify: `business/URLStrategy.java` + its impl — remove `getWeblogCommentsURL` (117), `getWeblogCommentURL` (126)
- Test: `app/src/test/java/.../ServletRegistrationConfigTest.java`, `URLModelTest.java`, `FeedModelTest.java`, `PageServletDecisionTest.java`, `WeblogRequestMapperTest.java`

**Interfaces:**
- Consumes: Task 1 removed every template reader of `$model.commentForm` and `$url.comment*`.
- Produces: no `/roller-ui/rendering/comment/*`, no `/CommentAuthenticatorServlet`, no `/roller-ui/authoring/commentdata/*`. `URLStrategy` no longer exposes comment URLs. Task 3 relies on `CommentDataServlet` being unregistered before its class is deleted.

- [ ] **Step 1: Write the failing test**

In `ServletRegistrationConfigTest`, add:

```java
@Test
void noCommentServletsAreRegistered() {
    assertTrue(registeredPatterns().stream()
                    .noneMatch(p -> p.contains("comment") || p.contains("Comment")),
            "comment servlets must be unregistered: " + registeredPatterns());
}
```

Match the existing helper name in that test class for enumerating patterns; if it differs from `registeredPatterns()`, use the existing one rather than adding a new helper.

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -pl app test -Dtest=ServletRegistrationConfigTest`
Expected: FAIL, listing `/roller-ui/rendering/comment/*`, `/CommentAuthenticatorServlet`, `/roller-ui/authoring/commentdata/*`.

- [ ] **Step 3: Delete the servlets and plumbing**

Delete the files listed. Apply the modifications listed. In `PageServletDecisionTest`, delete the two `_popupcomments` stubs (102, 114) and the test at 136 that documents the popup preference.

`SecurityConfig`'s contact/subscribe CSRF exemption must remain — read the comment above it before editing so the two exemptions are not conflated.

- [ ] **Step 4: Run the tests**

Run: `mvn -pl app test -Dtest=ServletRegistrationConfigTest,URLModelTest,FeedModelTest,PageServletDecisionTest,WeblogRequestMapperTest,SiteModelTest`
Expected: PASS.

- [ ] **Step 5: Verify the reactor still compiles**

Run: `mvn -pl app -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add -A app/src/main/java app/src/test/java
git commit -m "W1: the endpoints a comment travelled through are gone"
```

---

### Task 3: Admin and authoring UI

> **Amended mid-execution.** Task 2's implementer found that the plan missed
> `MailUtil.java`. Its comment-notification methods call
> `URLStrategy.getWeblogCommentsURL` (`MailUtil:295,489`) and two
> `commentServlet.email.*` keys (`309,327`), and are themselves called from
> `CommentsController:221`. Deleting the URL methods in Task 2 would not
> compile. Task 2 therefore deferred four things to this task — they are folded
> into the file list below and marked **(deferred from T2)**.

**Files:**
- Delete: `ui/controllers/editor/CommentsController.java`, `CommentsBean.java`
- **(deferred from T2)** Modify: `util/MailUtil.java` — delete
  `sendEmailNotification(WeblogEntryComment, …)`,
  `sendEmailApprovalNotifications`, `sendEmailApprovalNotification`, and the two
  `commentServlet.email.thereAreSystemMessages` /
  `…thereAreErrorMessages` lookups. Every other mailer in this class survives.
- **(deferred from T2)** Modify: `app/src/test/java/.../MailUtilTest.java` —
  remove the comment-notification cases only
- **(deferred from T2)** Modify: `business/URLStrategy.java` + its
  `MultiWeblogURLStrategy` impl — remove `getWeblogCommentsURL` (117),
  `getWeblogCommentURL` (126)
- **(deferred from T2)** Modify: `ui/rendering/model/URLModel.java` — remove
  `comment(String, String)` (172) and `comments(String)` (177). Task 2 already
  removed `getCommentAuthenticator()` and the `CommentFeedURLS` class.
- Delete: `ui/controllers/admin/GlobalCommentManagementController.java`, `GlobalCommentManagementBean.java`
- Delete: `ui/controllers/ajax/CommentDataServlet.java`
- Delete: `ui/controllers/pagers/CommentsPager.java`
- Delete: `webapp/WEB-INF/jsps/editor/Comments.jsp`, `CommentsSidebar.jsp`
- Delete: `webapp/images/comment.png`
- Modify: `ui/controllers/RollerViewResolver.java` — remove `.GlobalCommentManagement` (265-270) and `.Comments` (355-360)
- Modify: `webapp/WEB-INF/classes/editor-menu.xml` (or wherever `editor-menu.xml` resolves) — remove the `comments` `<menu-item>`
- Modify: `admin-menu.xml` — remove the global comment management item
- Modify: `webapp/WEB-INF/jsps/core/MainMenu.jsp:129-136` — remove the Manage-comments button and its `${perms.weblog.commentCount}` badge
- Modify: `webapp/WEB-INF/jsps/editor/EntryEdit.jsp` — remove the Comments drawer (the `#collapseAdvanced` block)
- Modify: `it-selenium/src/test/java/org/apache/roller/it/support/Routes.java` — remove entries at 209, 213, 246
- Modify: `ApplicationResources.properties` + 7 bundles — remove all 35 `commentManagement.*` keys, all 5 `commentServlet.*` keys, and `yourWebsites.manageComments`
- Test: `app/src/test/java/.../MenuDefinitionTest.java`, `MessageKeyTest.java`, `EditorJspEscapingTest.java`

**Interfaces:**
- Consumes: Task 2 unregistered `CommentDataServlet`, so deleting the class here breaks nothing.
- Produces: three fewer admin routes. Task 7 relies on `Routes.java` already being correct so `RouteSweepIT` passes on the first full IT run.

- [ ] **Step 1: Write the failing test**

In `MenuDefinitionTest`, add:

```java
@Test
void noMenuOffersCommentModeration() {
    assertFalse(menuXml().contains("\"comments\""),
            "editor-menu.xml must not offer a comments tab");
    assertFalse(adminMenuXml().contains("globalCommentManagement"),
            "admin-menu.xml must not offer global comment management");
}
```

Use whatever accessors that test class already has for the two XML files; add them only if absent.

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -pl app test -Dtest=MenuDefinitionTest`
Expected: FAIL on both assertions.

- [ ] **Step 3: Delete the controllers, JSPs, view definitions and menu items**

Apply every deletion and modification listed. The `EntryEdit.jsp` Comments drawer carries the legacy id `collapseAdvanced`; CLAUDE.md documents that id as a pinned `CommentIT` contract. `CommentIT` is deleted in Task 7, so removing the drawer here is correct — Task 7 removes the CLAUDE.md note. Do not leave an empty drawer behind.

- [ ] **Step 4: Remove the 41 admin message keys from all 8 bundles**

Delete whole lines only.

- [ ] **Step 5: Lower the ratchet again if it dropped**

Run: `mvn -pl app test -Dtest=MessageKeyTest`
Adjust `KNOWN_DYNAMIC_KEY_COUNT` down to the reported count if lower.

- [ ] **Step 6: Run the tests**

Run: `mvn -pl app test -Dtest=MenuDefinitionTest,MessageKeyTest,EditorJspEscapingTest,ApplicationResourcesTest,ControllerMetadataTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A app/src/main app/src/test it-selenium
git commit -m "W1: two moderation screens and a tab nobody needed"
```

---

### Task 4: Manager and persistence layer

**Files:**
- Modify: `business/WeblogEntryManager.java` — remove `saveComment` (200), `removeComment` (205), `getComment` (210), `getComments` (217), `removeMatchingComments` (228), `applyCommentDefaultsToEntries` (260), `getCommentCount()` (309), `getCommentCount(Weblog)` (315)
- Modify: `business/jpa/JPAWeblogEntryManagerImpl.java` — remove the corresponding implementations and every comment-related named query
- Modify: `resources/.../pojos/WeblogEntry.orm.xml` — remove comment named queries and the comments relationship
- Delete: `business/plugins/comment/` (whole package, including `WeblogEntryCommentPlugin`)
- Modify: `business/plugins/PluginManagerImpl.java` — remove comment-plugin registration and the `getCommentPlugins`/`applyCommentPlugins` surface
- Modify: `ui/rendering/model/SiteModel.java` — remove `getCommentCount()` (511-516)
- Test: `app/src/test/java/.../SiteModelTest.java`, `LazyLookupTest.java`, `ReflectionTest.java`

**Interfaces:**
- Consumes: Tasks 2 and 3 removed every caller of the manager comment methods.
- Produces: `WeblogEntryManager` has no comment surface. Task 5 relies on no JPA mapping referencing `roller_comment` before the table is dropped.

- [ ] **Step 1: Write the failing test**

In `ReflectionTest` (or a new `CommentSurfaceGoneTest` in the same package if `ReflectionTest` has no suitable home), add:

```java
@Test
void weblogEntryManagerHasNoCommentSurface() {
    List<String> offenders = Arrays.stream(WeblogEntryManager.class.getMethods())
            .map(Method::getName)
            .filter(n -> n.toLowerCase(Locale.ROOT).contains("comment"))
            .sorted().toList();
    assertTrue(offenders.isEmpty(), "comment methods survive: " + offenders);
}
```

Note `getMostCommentedWeblogEntries` (line 120) matches this filter and **must also be removed** — it ranks entries by comment count and is meaningless now. Confirm it has no callers before deleting; if it does, delete those too.

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -pl app test -Dtest=ReflectionTest`
Expected: FAIL, listing nine method names.

- [ ] **Step 3: Remove the manager surface, JPA impl, named queries and plugin package**

Apply every change listed. Grep the ORM XML for `Comment` and remove each named query; a stale named query referencing a dropped entity fails at EntityManagerFactory creation, not at compile time, so this cannot be left for later.

- [ ] **Step 4: Verify `weblog.lastModified` still gets bumped**

Run: `grep -rn "setLastModified\|lastModified" app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAWeblogEntryManagerImpl.java app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAWeblogManagerImpl.java`
Expected: entry save, template save, page save and theme switch still bump it. If a shared private helper was deleted alongside its comment-only callers, restore it.

- [ ] **Step 5: Run the tests**

Run: `mvn -pl app test -Dtest=ReflectionTest,SiteModelTest,LazyLookupTest,FeedModelTest,PageModelTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A app/src/main app/src/test
git commit -m "W1: the manager stops knowing what a comment is"
```

---

### Task 5: Pojos and schema

**Files:**
- Delete: `pojos/WeblogEntryComment.java`, `pojos/CommentSearchCriteria.java`, `pojos/wrapper/WeblogEntryCommentWrapper.java`, `resources/.../pojos/WeblogEntryComment.orm.xml`
- Modify: `resources/META-INF/persistence.xml` (or the equivalent mapping-file list) — remove the `WeblogEntryComment.orm.xml` entry
- Modify: `pojos/Weblog.java` — remove `allowComments` (63), `emailComments` (64), `defaultAllowComments` (72), `defaultCommentDays` (73), `moderateComments` (74), `requireAuthenticatedComments` (75), their accessors, `getCommentCount()` (762-767), and the corresponding lines in the copy-constructor-style `setData`/`copyFrom` around 365
- Modify: `pojos/WeblogEntry.java` — remove `allowComments` (93), `commentDays` (94), accessors, `getCommentCount()` (963), `getComments()`, `commentsStillAllowed`
- Modify: `pojos/wrapper/WeblogWrapper.java` (`getCommentCount` 339), `pojos/wrapper/WeblogEntryWrapper.java`
- Modify: `ui/controllers/editor/WeblogConfigBean.java` — remove the 6 comment fields and their accessors
- Modify: `ui/controllers/editor/EntryBean.java` — remove `commentCount` (66, 179-184) and any comment fields
- Modify: `webapp/WEB-INF/jsps/editor/WeblogConfig.jsp` — remove the `settings-comments` and `settings-comment-defaults` sections in full, and their two entries from the `settings-rail` section index
- Create: `bin/db/migrations/V022__drop_comments.sql`
- Test: `app/src/test/java/.../SchemaMigrationTest.java`, `EqualsContractTest.java`, `PojoComparatorTest.java`, `WeblogStatsTest.java`

**Interfaces:**
- Consumes: Task 4 removed every JPA mapping and manager reference.
- Produces: `Weblog` and `WeblogEntry` carry no comment state. Task 6 relies on the runtime properties being the last comment configuration left.

- [ ] **Step 1: Write the migration**

Create `bin/db/migrations/V022__drop_comments.sql`:

```sql
-- Migration: drop the comment subsystem.
--
-- Comments were unreachable by any real reader: comment_auth_required
-- defaulted to true (V013) and public self-registration had already been
-- removed, so only an administrator-provisioned account could post one.
-- The contact form and newsletter are the reader channels now.
--
-- NOTE: roller_audit_log.comment_text is NOT a comment column -- it is the
-- audit log's change note. It is deliberately untouched here.

DROP INDEX IF EXISTS co_entryid_idx;
DROP INDEX IF EXISTS co_status_idx;
DROP TABLE IF EXISTS roller_comment;

ALTER TABLE weblog
    DROP COLUMN IF EXISTS allowcomments,
    DROP COLUMN IF EXISTS emailcomments,
    DROP COLUMN IF EXISTS defaultallowcomments,
    DROP COLUMN IF EXISTS defaultcommentdays,
    DROP COLUMN IF EXISTS commentmod,
    DROP COLUMN IF EXISTS comment_auth_required;

ALTER TABLE weblogentry
    DROP COLUMN IF EXISTS allowcomments,
    DROP COLUMN IF EXISTS commentdays;
```

- [ ] **Step 2: Run the schema test to verify it is discovered and idempotent**

Run: `mvn -pl app test -Dtest=SchemaMigrationTest`
Expected: PASS. This test applies the real chain twice; a non-idempotent statement fails here.

- [ ] **Step 3: Write the failing pojo test**

In `EqualsContractTest` or alongside it, add:

```java
@Test
void weblogAndEntryCarryNoCommentState() {
    for (Class<?> c : List.of(Weblog.class, WeblogEntry.class)) {
        List<String> offenders = Arrays.stream(c.getDeclaredFields())
                .map(Field::getName)
                .filter(n -> n.toLowerCase(Locale.ROOT).contains("comment"))
                .sorted().toList();
        assertTrue(offenders.isEmpty(), c.getSimpleName() + " keeps: " + offenders);
    }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `mvn -pl app test -Dtest=EqualsContractTest`
Expected: FAIL, listing 6 fields on `Weblog` and 2 on `WeblogEntry`.

- [ ] **Step 5: Delete the pojos, fields, beans and settings sections**

Apply every change listed. Remove the `WeblogEntryComment.orm.xml` entry from the mapping-file list — a mapping file listed but absent fails at startup.

`WeblogConfig.jsp`: remove both sections **and** their links in the `settings-rail` section index, or the rail links to anchors that no longer exist.

- [ ] **Step 6: Run the tests**

Run: `mvn -pl app test -Dtest=EqualsContractTest,PojoComparatorTest,ReflectionTest,SchemaMigrationTest,WeblogStatsTest,SqlScriptRunnerMigrationTest`
Expected: PASS.

- [ ] **Step 7: Full unit suite**

Run: `mvn -pl app test`
Expected: PASS. Any failure here is a missed reference — fix it in this task rather than deferring.

- [ ] **Step 8: Commit**

```bash
git add -A app bin/db/migrations
git commit -m "W1: the entity, the columns, and the table"
```

---

### Task 6: Configuration and remaining i18n

**Files:**
- Modify: `resources/org/apache/roller/weblogger/config/runtimeConfigDefs.xml` — remove the whole `commentSettings` display group (139-170): `users.comments.enabled`, `users.comments.htmlenabled`, `users.comments.plugins`, `users.comments.emailnotify`, `users.moderation.required`, `comment.throttle.enabled`
- Modify: `resources/org/apache/roller/weblogger/config/roller.properties` — remove `comment.throttle.*` sizing properties and any `plugins.comment` registration
- Modify: `ApplicationResources.properties` + 7 bundles — remove `configForm.commentSettings`, `configForm.enableComments`, `configForm.commentHtmlAllowed`, `configForm.commentPlugins`, `configForm.emailComments`, `configForm.moderationRequired`, `configForm.commentThrottle`, and the `websiteSettings.*` keys belonging to the two deleted settings sections
- Modify: `app/src/test/java/.../PromotedRuntimePropertyTest.java` — remove `comment.throttle.enabled` from the promoted list
- Test: `PromotedRuntimePropertyTest.java`, `ConfigModelTest.java`, `MessageKeyTest.java`, `RollerMessagesTest.java`

**Interfaces:**
- Consumes: Task 5 removed the last reader of these properties.
- Produces: no comment configuration anywhere. `GenericThrottle` is untouched.

- [ ] **Step 1: Write the failing test**

In `PromotedRuntimePropertyTest`, add:

```java
@Test
void noCommentPropertiesRemainInRuntimeConfig() throws Exception {
    String xml = Files.readString(Path.of(
            "src/main/resources/org/apache/roller/weblogger/config/runtimeConfigDefs.xml"));
    assertFalse(xml.contains("users.comments."), "comment runtime properties survive");
    assertFalse(xml.contains("comment.throttle."), "comment throttle property survives");
    assertFalse(xml.contains("users.moderation."), "moderation property survives");
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -pl app test -Dtest=PromotedRuntimePropertyTest`
Expected: FAIL on all three.

- [ ] **Step 3: Verify GenericThrottle's surviving callers before touching properties**

Run: `grep -rn "throttle\." app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/ContactController.java app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/NewsletterController.java app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/PasswordResetController.java`
Expected: only `contact.throttle.*`, `newsletter.subscribe.throttle.*`, `passwordreset.throttle.*`. If any reads a `comment.throttle.*` key, stop and report — the plan's assumption is wrong.

- [ ] **Step 4: Remove the properties and keys**

Apply every change listed. `util/GenericThrottle.java` and `GenericThrottleTest` are **not** touched.

- [ ] **Step 5: Lower the ratchet a final time**

Run: `mvn -pl app test -Dtest=MessageKeyTest`
Set `KNOWN_DYNAMIC_KEY_COUNT` to the reported count if lower.

- [ ] **Step 6: Run the tests**

Run: `mvn -pl app test -Dtest=PromotedRuntimePropertyTest,ConfigModelTest,MessageKeyTest,RollerMessagesTest,ApplicationResourcesTest,MessageFormatRegressionTest,GenericThrottleTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A app
git commit -m "W1: six runtime properties for a feature that no longer exists"
```

---

### Task 7: Browser suite and documentation

**Files:**
- Delete: `it-selenium/src/test/java/org/apache/roller/it/CommentIT.java`
- Modify: `it-selenium/src/test/java/org/apache/roller/it/GlobalConfigMatrixIT.java` — delete `switchingCommentsOffSiteWideRefusesThemAtTheServlet` (113-131) and the `postCommentDirectly` helper
- Modify: `it-selenium/src/test/java/org/apache/roller/it/WeblogConfigMatrixIT.java` — delete the comment-approval test and any `.comment-row` selector usage; keep every other per-weblog test
- Modify: `it-selenium/.../support/Routes.java` — confirm Task 3's removals landed
- Modify: `app/src/main/webapp/roller-ui/styles/roller.css` — remove the `.comment-list`/`.comment-row`/`.comment-body`/`.comment-meta`/`.comment-who`/`.comment-contact`/`.comment-when`/`.comment-on`/`.comment-text`/`.comment-actions`/`.selection-bar` block and `.comment-row > .form-check-input`
- Modify: `CLAUDE.md` — replace the whole **Comments** section; remove the `#collapseAdvanced` pinned-contract paragraph from **Entry editing**; note `base.css`/`popupcomments.vm` deletion
- Test: full unit suite + full browser suite

**Interfaces:**
- Consumes: everything from Tasks 1-6.
- Produces: a green tree with no comment subsystem.

- [ ] **Step 1: Delete the IT and the two matrix tests**

Apply the deletions listed. `WeblogConfigMatrixIT`'s remaining tests must still own their own weblog and touch no global state.

- [ ] **Step 2: Remove the dead admin CSS**

Those selectors styled `Comments.jsp`, which no longer exists. `DesignTokenTest` sweeps this file, so the removal must not orphan a token reference.

- [ ] **Step 3: Rewrite the CLAUDE.md Comments section**

Replace it with a short record: the subsystem was removed in W1 because it was unreachable by design (auth required, no self-registration); the contact form and newsletter are the reader channels; `roller_audit_log.comment_text` is unrelated; `GenericThrottle` survives for contact/newsletter/password-reset. State plainly that it is not coming back.

Also remove the **Entry editing** paragraph documenting `#collapseAdvanced` as a `CommentIT` contract — both the drawer and the test are gone, and a doc describing a selector that no longer exists is worse than no doc.

- [ ] **Step 4: Full unit suite**

Run: `mvn clean install -DskipTests=false`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Diff coverage gate**

Run: `mvn -pl app jacoco:report && bin/check-diff-coverage.sh HEAD~6`
Expected: pass. A deletion-heavy diff has few changed *added* lines; if the tool reports no covered-line denominator, record that rather than forcing a number.

- [ ] **Step 6: Full browser suite**

Run: `docker rm -f roller-it-postgres 2>/dev/null; mvn verify -Pit`
Expected: PASS. A stale container makes this fail at `docker-maven-plugin:start` with a 409 name conflict before any test runs — that is not a test failure.

Known flake: `ReferenceError: EasyMDE is not defined` on `entryEdit!firstSave.rol`. Green on rerun. Confirm `head.jsp`'s `easymde.min.js` `<script>` is still unconditional before treating it as a flake.

- [ ] **Step 7: Verify nothing comment-shaped survives**

Run: `grep -rni "comment" app/src/main --include=*.java --include=*.jsp --include=*.vm --include=*.xml --include=*.properties | grep -viE "^\S+:\s*(//|\*|<!--|#)|comment_text|commonmark|Comparator|//.*comment|documented|commented out"`
Expected: only ordinary code comments and `roller_audit_log.comment_text`. Anything else is a miss.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "W1: the browser suite and the docs stop describing comments"
```

---

## Self-Review

**Spec coverage:** every "Deleted outright" and "Edited" bullet in the spec maps to a task — rendering (T1), servlets (T2), admin UI (T3), managers (T4), pojos + `V022` (T5), runtime properties + i18n (T6), tests + CLAUDE.md (T7). The spec's two error-handling requirements are covered: `weblog.lastModified` at T4 Step 4, and old-endpoint 404 behaviour at T2 via `WeblogRequestMapperTest`. The spec's `GenericThrottle` survival is verified at T6 Step 3 and re-run at T6 Step 6.

**Placeholder scan:** none. Every code step carries the actual code or the actual command.

**Type consistency:** `KNOWN_DYNAMIC_KEY_COUNT` is referenced by the same name in T1/T3/T6. `getCommentCount()` is removed in three places under the same name (T4 `SiteModel`, T5 `Weblog`/`WeblogEntry`/wrappers). `getMostCommentedWeblogEntries` is caught by T4's reflection filter and explicitly called out there rather than left to surprise the implementer.

**One risk the plan cannot remove:** Task 4's ORM named-query cleanup fails at EntityManagerFactory creation rather than at compile time. That is why T4 Step 3 says to grep the ORM XML rather than trust the compiler, and why T5 Step 7 runs the whole unit suite before the wave continues.
