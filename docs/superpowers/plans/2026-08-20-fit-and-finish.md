# Fit & Finish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One low-risk pass that fixes every broken user-facing control found by the 2026-08-20 sweeps and lands the fit-and-finish backlog (copy, a11y, visual consistency, small usability features) across the admin UI, editor, themes and message bundles.

**Architecture:** No new subsystems. Fixes are JSP/VM/CSS/properties edits plus small controller changes, each guarded either by a real behavioral test or by extending the repo's established source-scan ratchet tests (`MessageKeyTest`, `MessageFormatRegressionTest`, `EditorJspEscapingTest` pattern). New ratchets are written FIRST and watched to fail against today's tree — they are the TDD backbone for the mechanical sweeps.

**Tech Stack:** Spring MVC + JSP/JSTL admin UI, Velocity themes, Bootstrap 5 + roller-tokens.css, jQuery 4 + jQuery UI 1.14.2 (datepicker IS loaded in `tiles/head.jsp:14`), JUnit 6.

**Spec:** `docs/superpowers/specs/2026-08-20-fit-and-finish-design.md`

## Global Constraints

- **Never run two Maven builds at once in this working tree.** Check first, inline the wait:
  ```bash
  pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || echo CLEAR
  ```
- **Velocity is lenient and fails silently.** Before deleting or renaming ANY Java member, macro, or template variable, grep both template trees:
  ```bash
  grep -rn "<memberName>" app/src/main/webapp/themes app/src/main/webapp/WEB-INF/velocity
  ```
- **Never rename a CSS class or id without checking** `it-selenium/src/test/java/org/apache/roller/it/support/Routes.java` and grepping `it-selenium/src/test/java` for the selector. Marker updates belong in the same commit.
- **No CSP string changes anywhere in this plan.** The theme CSPs are pinned byte-for-byte by rendering tests.
- **Escaped/raw storage split:** entry titles stored HTML-escaped (emit `$entry.title` bare); page titles stored raw (templates must escape); `WeblogWrapper.getName()` pre-escaped (never double-escape). Three tasks touch this; each restates it.
- **Controllers: name every `@RequestParam`/`@PathVariable` explicitly** (no `-parameters` flag; `ControllerMetadataTest` enforces).
- **TDD.** Failing test first, watch it fail for the expected reason, then fix. Source-scan ratchets count: write the scan, watch it list today's offenders, fix, watch zero. Pure bundle-VALUE edits (typos, casing) are the documented exception — they ride the ratchets that DO exist plus grep verification, not per-string tests.
- **Never commit or push unless the human asks.** Task commit steps run only on acceptance. Never push — report "ready to push" and stop.
- **Commit trailers** (every commit):
  ```
  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01WFocXbEdrhUGYXBRy9CwKD
  ```
- **Diff-coverage:** most of this plan is JSP/VM/CSS/properties (not JaCoCo-measured). Java edits carry real tests. If a batch still fails the gate on pre-existing error-path lines, apply the CLAUDE.md policy: accept the red run and say so.

## Parallelization lanes

Tasks within a lane share files — run them serially. Lanes touch disjoint files and may run in parallel worktrees ONLY under the CLAUDE.md base-pinning protocol (verify base, `comm` overlap check, `merge-tree`).

| Lane | Tasks | Shared files |
|---|---|---|
| A: editor JSPs + controllers | T1 T2 T3 T12 T13 T15 T17 T18 T19 | `WEB-INF/jsps/editor/**`, `ui/controllers/editor/**` |
| B: bundle + copy | T7 T8 T9 T10 | `ApplicationResources*.properties` (T9 also touches editor/core JSPs — serialize with A and C where files overlap) |
| C: admin/core JSPs + tiles | T4 T11 T14 T16 | `WEB-INF/jsps/{admin,core,tiles}/**`, `roller-ui/styles/**` |
| D: themes + velocity | T5 T6 T20 T21 | `themes/**`, `WEB-INF/velocity/**` |
| E: deletions + docs | T22 T23 | cross-cutting — run LAST, alone |

Any task that ADDS a bundle key (T1, T9, T12, T17, T18, T19, T21) conflicts with lane B on `ApplicationResources.properties` — schedule those after T8 or accept a trivial append-merge.

---

## Wave 1 — Broken behavior (P1)

### Task 1: Make the entry-list filter work at all

The Entries sidebar filter is broken three ways: the form POSTs to a GET-only mapping (405 on every submit), the datepicker binds dead Struts-era ids so the readonly date fields can't be filled, and paging a filtered list drops the category filter because the pager emits a parameter name the bean doesn't have.

**Files:**
- Modify: `app/src/main/webapp/WEB-INF/jsps/editor/EntriesSidebar.jsp`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/EntriesController.java:320,341-343`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/editor/EntriesControllerTest.java`
- Modify: `app/src/main/resources/ApplicationResources.properties` (one new key)

**Interfaces:**
- Consumes: `EntriesController.buildBaseUrl()` (`:317-342`), `EntriesBean` (has `categoryName`, NOT `categoryPath` — verified).
- Produces: the filter form submits by GET with the same `bean.*` parameter names the pager links already use. Task 19's status chips build on this.

**Changes:**

| Site | Change |
|---|---|
| `EntriesSidebar.jsp:25` | `method="post"` → `method="get"`; delete the `<sec:csrfInput/>` line (GET needs no CSRF token). Keep the hidden `weblog` input. Precedent: `MediaFileViewController.java:74-78` + its verb test. |
| `EntriesSidebar.jsp:59,73` | Add `id="entries_bean_startDateString"` / `id="entries_bean_endDateString"` to the two date inputs (the labels at `:54,:60,:68,:74` already point at `bean.startDateString`-style names — update those `for=` values to the new ids). Remove `readonly` is NOT needed — the picker fills them. Add `placeholder="MM/DD/YY"`. |
| `EntriesSidebar.jsp:109-110` | The script binds `$("#entries_bean_startDateString")` — with the ids added above it now matches. Verify the call is `.datepicker({dateFormat: 'mm/dd/y'})` to match `EntriesBean`'s strict `MM/dd/yy` parse. |
| `EntriesSidebar.jsp:31,41,47` | Add `id=` to the category `<select>`, tags and text inputs + matching `for=` on their labels (they currently have neither). |
| `EntriesController.java:320` | `params.put("bean.categoryPath", …)` → `params.put("bean.categoryName", …)`. |
| `EntriesController.java:341-343` | The synthetic "Any" `WeblogCategory` becomes an `<option value="">` with label from a new key `weblogEntryQuery.label.anyCategory=Any category` (the JSP binds `${opt.name}` at `EntriesSidebar.jsp:34` — restructure so the any-option is emitted directly in the JSP before the loop, value `""`, and the controller stops synthesizing a fake category). Blank `categoryName` already means "no filter" in the criteria. |

**Steps:**

- [x] **Step 1: Write the failing tests**

```java
// EntriesControllerTest additions
@Test
void theFilterFormSubmitsByGet() throws Exception {
    // Source-scan: the sidebar form must be method="get" with no CSRF input —
    // a POST here answers 405 because execute() is @GetMapping only.
    String jsp = Files.readString(Path.of(
        "src/main/webapp/WEB-INF/jsps/editor/EntriesSidebar.jsp"));
    Matcher form = Pattern.compile("<form[^>]*action=\"[^\"]*entries\\.rol[^>]*>")
        .matcher(jsp);
    assertTrue(form.find(), "filter form not found");
    assertTrue(form.group().contains("method=\"get\""), form.group());
    assertFalse(jsp.contains("csrfInput"), "GET form must not carry a CSRF token");
}

@Test
void thePagerBaseUrlUsesTheBeanCategoryNameProperty() {
    // bean.categoryPath does not exist on EntriesBean; emitting it means paging
    // silently drops the category filter.
    // Call buildBaseUrl (or extract it to package-private) with a bean whose
    // categoryName is set and assert the produced URL contains
    // "bean.categoryName=" and not "bean.categoryPath=".
}

@Test
void theDatePickerBindingMatchesRealIds() throws Exception {
    String jsp = Files.readString(Path.of(
        "src/main/webapp/WEB-INF/jsps/editor/EntriesSidebar.jsp"));
    // every $("#id") selector in the script block must reference an id= present in the file
    Matcher sel = Pattern.compile("\\$\\(\"#([A-Za-z0-9_]+)\"\\)").matcher(jsp);
    while (sel.find()) {
        assertTrue(jsp.contains("id=\"" + sel.group(1) + "\""),
            "script binds #" + sel.group(1) + " but no element has that id");
    }
}
```

- [x] **Step 2: Run and watch all three fail** — `mvn -pl app test -Dtest=EntriesControllerTest` — expected: form is `method="post"`, URL contains `bean.categoryPath`, script binds `#entries_bean_startDateString` with no such id.
- [x] **Step 3: Apply the changes table.** Add `weblogEntryQuery.label.anyCategory` to the bundle.
- [x] **Step 4: Re-run; all pass.** Also run `mvn -pl app test -Dtest=MessageKeyTest` (new key referenced from JSP).
- [x] **Step 5: Manual smoke via `./roller dev`:** filter by status + category, page to page 2, confirm the filter holds; open a date picker.
- [x] **Step 6: Commit** — `fix(editor): make the entry-list filter usable (GET form, datepicker ids, pager category param)`

### Task 2: Repair the dead-JS controls (Struts-id fossils)

Four independent breakages, one mechanism: JS addressing `document.<formName>` or Struts-generated ids that the JSP migration never reproduced.

**Files:**
- Modify: `app/src/main/webapp/WEB-INF/jsps/editor/StylesheetEdit.jsp`
- Modify: `app/src/main/webapp/WEB-INF/jsps/editor/Members.jsp`
- Modify: `app/src/main/webapp/WEB-INF/jsps/editor/TemplateEdit.jsp`
- Modify: `app/src/main/webapp/WEB-INF/jsps/editor/MediaFileSidebar.jsp`
- Modify: `app/src/main/webapp/WEB-INF/jsps/editor/MediaFileView.jsp` (only if the folder form moves)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/editor/EditorJspScriptBindingTest.java` (new)

**Changes:**

| Site | Defect | Fix |
|---|---|---|
| `StylesheetEdit.jsp:28,40,45,78-91` | Form has no name/id; `document.stylesheetEdit` undefined; Revert/Delete throw, and because the buttons are `type="submit"` the exception aborts before `return false` and the form submits to `!save` — **Revert and Delete silently save.** | `id="stylesheetEditForm"` on the form; handlers use `document.getElementById('stylesheetEditForm')`; both buttons → `type="button"`. |
| `Members.jsp:22-33,90,154` | `save()` never called (Save is plain submit), `document.memberPermissionsForm` doesn't exist, and `radios[i].value === -1` compares string to number — the remove-member confirm is triply dead. | Delete `save()`. Give the form `id="memberPermissionsForm"` and an `onsubmit` returning `confirm(...)` **only when** any checked radio has `value === "-1"`, using the existing `memberPermissions.confirmRemove` key. |
| `TemplateEdit.jsp:202,205,212,225` | `$("#template-code-tabs").tabs()` targets nothing and throws, killing the whole ready-block: the manual content-type control (`#manual-content-type-control-group`, `display:none` at `:177`) is never revealed, the URL preview reads `undefined`, `launchPage()` NPEs. | Delete line 202. Add `id="template_bean_link"` to the link input (`:68`) and `id="template_bean_autoContentType"` to the checkbox, so the existing selectors match. |
| `TemplateEdit.jsp:84` | `<a id="launchLink" onClick=...>` no href — mouse-only. | `<button type="button" class="btn btn-link" id="launchLink" onclick="launchPage()">`. |
| `MediaFileSidebar.jsp:96` | `<c:if test="${pager}">` coerces an object — search Reset never renders. | `${not empty pager}` (matches `:39`). |
| `MediaFileSidebar.jsp:112-116` | Create New Folder drives `document.mediaFileViewForm`, which doesn't render in an empty library — first-use throw. | Give the sidebar its own tiny `<form method="post" action="<c:url .../mediaFileView!createNewDirectory.rol>">` carrying `weblog`, `newDirectoryName`, `<sec:csrfInput/>`; `onCreateDirectory()` submits it. |

**Steps:**

- [x] **Step 1: Write the failing source-scan test** (new `EditorJspScriptBindingTest`):

```java
/** Every document.<name> reference in an editor JSP must match a form that
 *  declares that name or id — the Struts migration left several that don't,
 *  and the failure is a silent no-op (or worse: StylesheetEdit fell through
 *  to submit). */
@Test
void everyDocumentFormReferenceResolves() throws Exception {
    for (Path jsp : editorJsps()) {
        String src = Files.readString(jsp);
        Matcher m = Pattern.compile("document\\.([A-Za-z][A-Za-z0-9_]*)\\b(?!\\.getElementById)")
            .matcher(src);
        while (m.find()) {
            String name = m.group(1);
            if (Set.of("getElementById", "location", "body", "title", "createElement",
                       "addEventListener", "querySelector", "querySelectorAll", "forms")
                    .contains(name)) continue;
            assertTrue(src.contains("name=\"" + name + "\"") || src.contains("id=\"" + name + "\""),
                jsp.getFileName() + " references document." + name + " but no form declares it");
        }
    }
}
```

- [x] **Step 2: Run; expect failures naming StylesheetEdit and Members.** (`mvn -pl app test -Dtest=EditorJspScriptBindingTest`)
- [x] **Step 3: Apply the table.** For TemplateEdit also extend Task 1's id-binding scan pattern to this file (same test class, second method over `TemplateEdit.jsp`) — watch it fail on `#template-code-tabs`/`#template_bean_link`, then fix.
- [x] **Step 4: All green; manual smoke:** revert a stylesheet, remove a member (confirm appears), edit a custom template (content-type checkbox reveals the manual field), create a folder in a fresh weblog's empty media library.
- [x] **Step 5: Commit** — `fix(editor): repair dead Struts-era JS bindings (stylesheet revert/delete, member-remove confirm, template edit, folder create)`

### Task 3: Kill the apostrophe-in-onclick bug class (six files)

`fn:escapeXml` renders `'` as `&#039;`; the HTML parser decodes it BEFORE the onclick compiles, so the handler is a SyntaxError for any name containing an apostrophe. `MediaFileView.jsp:159-173` documents the mechanism and the fix (data-attributes + delegated handler at `:493-495`). Six sites still have the bug — including the ONLY way to insert an image into an entry. This task also converts these controls from `<a href="#">` to `<button>` and names them for AT (merging the a11y sweep's findings for the same lines).

**Files:**
- Modify: `app/src/main/webapp/WEB-INF/jsps/editor/EntryEdit.jsp:376-377`
- Modify: `app/src/main/webapp/WEB-INF/jsps/editor/Entries.jsp:182-190`
- Modify: `app/src/main/webapp/WEB-INF/jsps/editor/Pages.jsp:84-86`
- Modify: `app/src/main/webapp/WEB-INF/jsps/editor/Templates.jsp:80-81`
- Modify: `app/src/main/webapp/WEB-INF/jsps/editor/Categories.jsp:62-85`
- Modify: `app/src/main/webapp/WEB-INF/jsps/editor/MediaFileImageChooser.jsp:90-94`
- Modify: `app/src/main/webapp/WEB-INF/jsps/editor/CategoriesSidebar.jsp:29` (same pattern; carries an IT-selector coupling)
- Test: extend `EditorJspScriptBindingTest`
- Test (IT selector): grep `it-selenium/src/test/java` for `showCategoryAddModal` / `showDeleteModal` / `confirmPageDelete` / `confirmTemplateDelete` and update any element-type-sensitive selectors in the same commit (`Categories.jsp:103` documents `a[onclick*='showCategoryAddModal']` → becomes `button[onclick*=...]`).

**Pattern (from `MediaFileView.jsp:493`):** the control becomes
`<button type="button" class="btn btn-link p-0 align-baseline border-0" data-entry-id="${post.id}" data-entry-title="${fn:escapeXml(post.title)}" aria-label="<spring:message code='generic.delete'/>: ${fn:escapeXml(post.title)}">` with `aria-hidden="true"` on the icon span, and a delegated `addEventListener('click', ...)` reading `dataset` — never string-interpolated JS arguments.

**Per-site notes:**
- `Categories.jsp` edit control passes name, description AND image URL — all become `data-*`. Its delete gets `aria-label` from existing keys `generic.edit`/`categoriesForm.remove` (used as the `<th>`s at `:47-48`).
- `MediaFileImageChooser.jsp` tile: `data-media-file-id`/`data-media-file-name`, delegated click calling the existing selection function. This file is the image-insert path — smoke it hard.
- Keep the existing `title=` tooltips; ADD `aria-label` on the control (the `title`-on-descendant-span pattern is unreliable — a11y finding 10.4).

**Steps:**

- [x] **Step 1: Write the failing scan** (extend `EditorJspScriptBindingTest`):

```java
/** onclick attributes must not interpolate escaped author text — the entity
 *  decodes before the JS compiles and an apostrophe in a title becomes a
 *  SyntaxError. Fix pattern: data-* + delegated handler (MediaFileView:493). */
@Test
void noOnclickInterpolatesAuthorText() throws Exception {
    Pattern bad = Pattern.compile("onclick=\"[^\"]*\\$\\{fn:escapeXml");
    for (Path jsp : editorJsps()) {
        String src = Files.readString(jsp);
        assertFalse(bad.matcher(src).find(),
            jsp.getFileName() + " interpolates escaped author text into onclick");
    }
}
```

- [x] **Step 2: Run; expect failures naming the six files.**
- [x] **Step 3: Convert each site to the data-attribute pattern + button + aria-label.** Update the IT selectors found in the grep in the same commit.
- [x] **Step 4: Green. Manual smoke:** create an entry titled `Maiia's trip`, delete it from the list; upload a file named `Maiia's portrait.jpg`, insert it via the chooser.
- [x] **Step 5: Run the media/entry browser ITs if practical:** `mvn verify -Pit -Dit.test=GalleryIT` (the chooser path).
- [x] **Step 6: Commit** — `fix(editor): apostrophe-safe action controls via data attributes (6 files)`

### Task 4: Server-side small fixes (routing, reflection, feedback wiring)

Independent one-to-few-line Java/JSP fixes, each with a real test.

**Files & changes:**

| Site | Defect | Fix | Test |
|---|---|---|---|
| `SearchServlet.java:103` | Nonexistent weblog → 400, which `WebContainerConfig:53` renders with the 404 page body — status and body disagree. | `RenderingServletUtils.sendNotFound(response)` like every sibling servlet. | Unit test on the servlet with an unknown handle asserting `SC_NOT_FOUND`. |
| `EntryRemoveController.java:79` | Orphaned endpoint redirects to a blank `entryAdd.rol` after trashing. | Redirect to the entries list like the sibling `entryRemoveViaList`. | Extend its controller test to assert the redirect target. |
| `SetupController` save | Flash attached to `redirect:/` — the rendering servlet renders no messages tile; confirmation silently discarded. | `redirect:/roller-ui/setup.rol`. | Controller test asserts redirect view name. |
| `SetupController.execute` + `core/Setup.jsp:99-111` | Front-page select and aggregated checkbox never reflect stored config — any re-save silently changes the front-page weblog. | Put current `site.frontpage.weblog.handle` + aggregated flag in the model (mirror `Maintenance.jsp:28-52`'s pattern incl. the disabled placeholder option); JSP emits `selected`/`checked`. | Controller test: with a stored handle, model carries it; JSP scan: select renders `${...selected...}` gated on match. |
| `WeblogRemoveConfirm.jsp:45,47` | Cancel POSTs to GET-only `weblogConfig.rol` (405 on the escape hatch of the most destructive screen); Cancel styled `btn-success` next to `btn-danger`. | Replace the cancel form with `<a class="btn btn-secondary" href="...weblogConfig.rol?weblog=...">`. | JSP scan: no `<form` targeting `weblogConfig.rol` in this file. |
| `MediaFileAddSuccess.jsp:198` | `getAttribute("value")` never sees the `.value` property `setEnclosure` writes. | `$("#enclosureURL").val()`. | (JS-only; manual verify in step 5.) |
| `MediaFileEdit.jsp:155`, `MediaFileAddSuccess.jsp:164` | `name="submit"`/`id="submit"` clobber `form.submit`. | Rename `saveButton`/`createPostButton`; grep for selector users first. | Extend `EditorJspScriptBindingTest`: no `name="submit"`/`id="submit"` in the JSP tree. |
| `GlobalConfig.jsp:186`, `UserEdit.jsp:285-287`, `Profile.jsp:141-143` | `.attr("disabled", bool)` under jQuery 4 doesn't reliably clear. | `.prop("disabled", bool)` (same file already uses `.prop()` at `GlobalConfig.jsp:181`). | (Mechanical; grep verification `grep -rn '\.attr("disabled"' app/src/main/webapp` → zero.) |

**Steps:**

- [x] **Step 1:** Write the failing tests for the five Java-side rows (servlet 404, two redirect targets, setup model attrs). Run, watch fail.
- [x] **Step 2:** Apply all changes.
- [x] **Step 3:** Tests green; run the JSP scans; run greps.
- [x] **Step 4:** Manual smoke: cancel out of weblog-remove; save Setup twice and confirm the front-page weblog is stable.
- [x] **Step 5:** Commit — `fix: search-404, setup reflection + flash target, remove-confirm cancel, small JS correctness`

### Task 5: Reader-facing rendering correctness (velocity layer)

**Rule for this task:** entry titles are stored escaped; `WeblogWrapper.getName()` is pre-escaped; category/tag wrappers return RAW. Do not batch the escapes mechanically — each row states its direction.

**Files:**
- Modify: `app/src/main/webapp/WEB-INF/velocity/weblog.vm`
- Modify: `app/src/main/webapp/WEB-INF/velocity/feeds.vm:30`
- Modify: `app/src/main/webapp/WEB-INF/velocity/templates/weblog/page.vm`
- Modify: `app/src/main/webapp/WEB-INF/velocity/templates/error-page.vm`, `error-parse.vm`
- Delete: `app/src/main/webapp/WEB-INF/velocity/templates/navbar/` and `templates/menu/` (5 dead files calling nonexistent `$text.getString`/`$menuModel.getMenus`)
- Modify: `themes/{journal,portfolio,travel}/searchresults.vm` (zero-hit + error message)
- Modify: `themes/portfolio/_day.vm:16,43`, `themes/travel/_day.vm:15,34` (alt double-escape)
- Test: `app/src/test/java/...` — extend the existing theme rendering tests (`JournalThemeRenderingTest` etc.) and add a `VelocityErrorTemplateTest` source-scan.

**Changes:**

| Site | Change |
|---|---|
| `weblog.vm:1081` | `#showPageMenu` iterates `$weblog.pages` — no such property; loop body never runs. Delete the loop (line 1080's `#showPageLinks` already emits nav pages). |
| `weblog.vm:1015,1017,1063,1191,1256,1473` | Category/tag names emitted raw (wrappers return raw) → wrap in `$utils.escapeHTML(...)`, matching the themes' `_day.vm` treatment. |
| `weblog.vm:89-91` | Feed-discovery title renders `$model.tags.toString()` (`[a, b]`) — join with `, ` via a `#foreach`, escape; escape `$model.weblogCategory.name` in the same attribute. |
| `weblog.vm:167-171` (`#showSeoHead`) | Add `#if($model.searchResults)<meta name="robots" content="noindex">#end` — `?q=` permutations are crawlable today. |
| `weblog.vm:476, 563-566` | Fix two stale comments (font-less-CSP claim; "nine call sites / five themes" → 13/4). |
| `feeds.vm:30` | `$entry.creator.screenName` unescaped inside an Atom text construct — `&`/`<` yields a malformed feed → `$utils.escapeXML(...)`. (The `type="html"` title escapes at `feeds.vm:29` are CORRECT — verified — leave them.) |
| `templates/weblog/page.vm:137,145` | `escapeHTML($model.weblog.name)` double-encodes a pre-escaped wrapper value → drop the escape, emit bare. |
| `templates/weblog/page.vm` head | Add `#showAutodiscoveryLinks($model.weblog)` (the only reader template without feed discovery); move the audience/embed asset macros from after `</main>` into `<head>` beside the gallery styles. |
| `error-page.vm:39` | `</style` missing `>` — fix. |
| `error-page.vm:68-77`, `error-parse.vm:114-123` | Exception message/class/source shown to readers at HTTP 200 — replace with the neutral wording `roller-ui/errors/error.jsp` uses; keep detail in the server log only. Add error.jsp's viewport meta to `error-page.vm`. |
| `portfolio/_day.vm:16,43`, `travel/_day.vm:15,34` | `#showResponsiveImage` alt double-escape: they pass `$entry.title` (stored escaped) into a macro that escapes again. Pass `$utils.unescapeHTML($entry.title)` (verified `UtilitiesModel.unescapeHTML` exists at `:224`); prefer `$entry.featuredImage.altText` when non-empty. |
| `searchresults.vm` ×3 | Add `#if($model.hits == 0)` a one-sentence no-results message `#end`, and `#if($utils.isNotEmpty($model.errorMessage))` a FIXED "Search is temporarily unavailable." sentence (never the raw exception) — `SearchResultsModel.getErrorMessage()` is rendered by nothing today, so Lucene-down looks like zero results. |

- [x] **Step 1:** Write failing tests: `VelocityErrorTemplateTest` (source-scan: `error-page.vm`/`error-parse.vm` contain no `$exception`/`$exceptionSource`/class-name references); extend a theme rendering test to assert a category name containing `&` renders escaped in the nav macro output; feeds rendering test for the author escape.
- [x] **Step 2:** Run, watch fail. **Before deleting `templates/navbar/`+`templates/menu/`, run the Velocity grep for each macro/file name** — expect zero references (agent-verified, re-verify).
- [x] **Step 3:** Apply table; delete the dead dirs.
- [x] **Step 4:** Green; run all theme rendering tests (`mvn -pl app test -Dtest='*ThemeRenderingTest,*RenderingTest'`) — pinned CSPs must be byte-identical.
- [x] **Step 5:** Commit — `fix(rendering): escapes, feed validity, error-template leak, dead velocity fossils`

### Task 6: Journal + frontpage pagination and gates

**Files:** `themes/journal/weblog.vm:49-62`, `themes/frontpage/weblog.vm:14-40,67-84`, `themes/frontpage/_blogdirectory.vm:18,34`, `themes/frontpage/_header.vm:8,22`, `themes/frontpage/_footer.vm:4`, `themes/frontpage/_day.vm` + `theme.xml`, `themes/frontpage/frontpage-custom.css:239,249,258-275`. Tests: extend `JournalThemeRenderingTest` + the frontpage rendering test.

| Site | Defect → fix |
|---|---|
| `journal/weblog.vm:49-62` | Older/Newer labels INVERTED (`getNextLink()` = older, `getPrevLink()` = newer — `AbstractWeblogEntriesPager.java:146-178`). Swap the label pairs AND the grid columns so older stays visually left; **keep `rel="prev"` on prevLink / `rel="next"` on nextLink** (those are correct). |
| `frontpage/weblog.vm:32-40` | Arrows contradict labels (`< Next` / `Previous >`); `_blogdirectory.vm:42-50` is correct — mirror it. |
| `frontpage/weblog.vm:16` | `#if(!$pager.nextLink)` shows pinned entries only on the LAST page → `#if(!$pager.prevLink)` (first page). This is why pinned entries have never rendered on a >25-entry site. |
| `frontpage/weblog.vm:25` | `$foreach.index <= $maxResults` off-by-one no-op guard → delete. |
| `frontpage/weblog.vm:67-84` | "Weblogs" panel uses `getNewWeblogs(365, …)` — permanently empty on an old site → wrap label+grid in `#if(!$newblogs.isEmpty())`. |
| `frontpage/_blogdirectory.vm:34` | Hand-built `$url.absoluteSite/$loopblog.handle` → `$loopblog.absoluteURL`. `:18` "1 weblogs" → singular/plural branch. |
| `frontpage/_header.vm:22` | "Main Menu" link shown to anonymous readers → move inside the authenticated `#else` branch (`:23-27`). |
| `frontpage/_header.vm:8` | Favicon link mislabelled (`rel="shortcut icon" type="image/x-icon"` for an SVG) → `rel="icon" type="image/svg+xml"`. |
| `frontpage/_header.vm` | Add `#showAudienceAssets()` (CSP-neutral — `connect-src 'self'` covers it) so `[contact]`/`[subscribe]` on frontpage pages stop rendering inert. **Do NOT add `#showEmbedAssets`/`frame-src` — CSP change, excluded.** |
| `frontpage/_footer.vm:4` | Drop the exact version from "Powered by Roller"; keep attribution. |
| `frontpage/_day.vm` | `## unused` placeholder still declared in `theme.xml:66-76` → delete both, or keep with an explanatory comment (pick delete; grep first). |
| `frontpage-custom.css:239,249` | `.fd-search input.searchButton` targets a class no macro emits (macro emits `.button`) → fix both selectors. `:264-275` dead `.fd-crumb`/`table.fd-table` rules → delete. |

- [x] **Step 1:** Failing rendering-test assertions: journal page-2 render carries "Older"/"Earlier" text on the link whose `rel="next"`; frontpage first-page render contains the pinned-entries block when pinned entries exist.
- [x] **Step 2:** Watch fail, apply, green. Velocity-grep before the `_day.vm` deletion.
- [x] **Step 3:** Commit — `fix(themes): pager direction, pinned-entry gate, frontpage gates and dead CSS`

---

## Wave 2 — Messages & copy (bundle lane; serialize T7→T10)

### Task 7: The i18n ratchet tests (write first, watch them enumerate today's defects)

**Files:**
- Modify: `app/src/test/java/.../MessageKeyTest.java`
- Create: `app/src/test/java/.../MessagePlaceholderContractTest.java`
- Modify: `app/src/test/java/.../MessageFormatRegressionTest.java`

**Four ratchets:**

1. **Java-side key scan** (MessageKeyTest arm): regex over `app/src/main/java` for `getText\(\s*"([^"]+)"`, `add(?:Error|Message|FlashMessage|FlashError)\([^,]+,\s*"([^"]+)"`, and `getPageTitle` return literals; every captured key must exist in the base bundle. **Expected initial failures:** `mediaFileImaegChooser.title`, `mediaFile.edit.title`, `error.closingStream`, plus 19 literal English sentences (full list in Task 8). Keys that are legitimately dynamic (built with `+`) are skipped by the regex by construction.
2. **Placeholder contract** (new test): for every base-bundle key, max `{n}` index in the value must equal (args−1) at every call site found by the same scan; JSP `<spring:message>` with no `arguments` attribute counts as zero args. **Allowlist with reason comment:** `weblogEdit.draftRecovery.message` (client-side `{0}` substitution in `roller-draft.js:349` — deliberate). **Expected initial failures:** `mediaFile.delete.error`, `ConfigForm.invalidBooleanProperty/IntegerProperty/FloatProperty`, `categoryForm.created`, `categoryForm.error.duplicateName`, `createUser.add.success`, `pageForm.save.success`, `stylesheetEdit.{save,revert,default}.success`, `memberPermissions.membersChanged`.
3. **MessageFormat apostrophes, both directions, all bundles** (extend `MessageFormatRegressionTest`): values WITH placeholders must not contain a bare `'`; values WITHOUT placeholders must not contain `''`. Run over every `ApplicationResources*.properties`. **Expected initial failures:** six fr bare-quote values, three fr + one zh doubled-quote values (list in Task 8).
4. **Orphan ratchet word-boundary fix** (MessageKeyTest): `allSources.contains(key)` → word-boundary regex match. **Expected:** orphan count becomes 8 (`categoriesForm.root`, `error`, `error.upload.file`, `macro.weblog.readMore`, `pageForm.template`, `uploadFiles.upload`, `userAdmin.title`, `websiteSettings.removeWebsite`); assert the exact set so Task 22 can delete them and shrink it to zero.

- [x] **Step 1:** Write all four; run `mvn -pl app test -Dtest='MessageKeyTest,MessagePlaceholderContractTest,MessageFormatRegressionTest'`.
- [x] **Step 2:** Confirm each fails listing EXACTLY the expected offenders (differences mean the sweep data drifted — investigate before proceeding). Temporarily `@Disabled` with a `// enabled by Task 8` note OR keep red locally — do NOT commit red: commit the tests together with Task 8's fixes if executing solo, or commit with the known-offender lists as the assertion baseline and ratchet down. **Chosen approach: commit T7+T8 as two commits in one push-unit, T7's tests carrying the offender lists as `expectedLegacyOffenders` sets that T8 empties.**
- [x] **Step 3:** Commit — `test(i18n): ratchets for java-side keys, placeholder contracts, locale apostrophes, orphan word-boundaries`

### Task 8: Fix everything Task 7 enumerates

**Files:** `ApplicationResources*.properties` (base + fr, ru, ja, ko, zh_CN), ~18 controller files, `ThemeManagerImpl.java:371`, `MediaDtos.java:104`.

| Group | Fix |
|---|---|
| Missing keys | Add `mediaFileImageChooser.title=Choose an image` AND fix the `Imaeg` typo at `MediaFileImageChooserController.java:68` (both, or the typo re-orphans the key). Add `mediaFile.edit.title=Edit media file` (`MediaFileEditController.java:59`). Add `error.closingStream=Error reading theme resource.` |
| 19 literal-English keys | Route through the existing `generic.error.check.logs` where the sentence is a generic failure; mint per-screen keys where the sentence carries screen context ("Theme not found" → `themeEditor.error.notFound`, "Unable to locate specified template" → `pageForm.error.notFound`). Sites: `ThemeEditController:142`, `MediaFileViewController:113,263`, `CategoriesController:75`, `EntriesController:116`, `StylesheetEditController:181`, `TemplateEditController:74,90,104,131`, `TemplatesController:120,142,222`, `TrashController:101`, `MaintenanceController:138`, `CreateWeblogController:199`, `WeblogConfigController:99`, `ProfileController:128`. Also `MediaDtos.java:104` `"Upload failed."` → bundle key. |
| Placeholder repairs (en) | `mediaFile.delete.error` → drop `{0}` ("Error deleting the selected media files."). `ConfigForm.invalid*Property` ×3 → pass `new Object[]{propDesc, incomingValue}` at `GlobalConfigController:139,149,159` (value in scope; more useful than dropping `{1}`). `categoryForm.created=Category "{0}" created`. `categoryForm.error.duplicateName=A category named "{0}" already exists.` `createUser.add.success=User "{0}" created`. `pageForm.save.success=Template "{0}" updated.` `stylesheetEdit.{save,revert,default}.success` → drop the argument at the three `StylesheetEditController` call sites (name is noise on a single-stylesheet screen). `memberPermissions.membersChanged` hardcoded `"1"` at `MembersController:219` → new singular `memberPermissions.memberChanged=Permissions updated for {0}.` with the username. |
| Locale literal-`{0}` | fr:469 + ru:338 `userAdmin.title.editUser` → remove `{0}` (no args passed); ja:742 + zh:424 `mediaFileEdit.subtitle` → remove `{0}`; ja:771 `categoryForm.requiredFields` → remove `{0}`. |
| fr apostrophes (bare `'` + args → double them) | fr:58, fr:177, fr:181, fr:314 (also fix `accés`→`accès`), fr:469, fr:525. |
| Doubled `''` in no-arg values → single | fr:292 `maintenance.button.index`, fr:300 `memberPermissions.title`, fr:209 `index.createUserHelp`, zh:781 `userSettings.tip.username`. |

- [x] **Step 1:** With T7's ratchets red (offender lists), apply every row.
- [x] **Step 2:** Empty the `expectedLegacyOffenders` sets; all four ratchets green with zero exceptions.
- [x] **Step 3:** `mvn -pl app test` (controller tests touched by message-key changes).
- [x] **Step 4:** Commit — `fix(i18n): missing keys, literal-english keys, placeholder contracts, locale apostrophes`

### Task 9: Hardcoded English in JSPs → bundle keys

**Files:** `core/Login.jsp:36,41`; `tiles/bannerStatus.jsp:31-32` + `bannerInstallation.jsp:27-28`; `editor/Submissions.jsp:99`; `editor/EntryEditor.jsp:104,157,159` + `editor/PageEdit.jsp:287,327,329`; `editor/EntryEdit.jsp:482,522` + `tiles/messages.jsp:42,50` (aria-label Close ×6); `editor/Entries.jsp:35,38,242,245` + `editor/Submissions.jsp:35,42` (Newer/Older ×6); `editor/MediaFileAdd.jsp:118-120`; `editor/MediaFileEdit.jsp:31,34,47,91,100,174` + `MediaFileAddSuccess.jsp:51`; `editor/TemplateEdit.jsp:226`; bundle.

| Change | Detail |
|---|---|
| Login placeholders | `placeholder="<spring:message code='loginPage.userName'/>"` / `loginPage.password` (pattern: `EntryEdit.jsp:94`). |
| Toggle navigation ×2 | `generic.toggle` already exists — use it for both the aria-label and text. |
| `generic.close` | New key `generic.close=Close`; use for the six `aria-label="Close"` and the two modal-footer Close buttons. |
| Leave warning | New key `weblogEdit.leaveWarning`; reference from both editors' beforeunload strings. |
| Newer/Older | New keys `pager.newer=Newer` / `pager.older=Older`; **fix the top/bottom contradiction in Entries.jsp — `prevLink` is Newer (pager walks newest-first), the bottom nav at `:242/:245` is the wrong one.** |
| Upload summary | `" file, "`/`" files, "`/`" total"` → one `{0} files, {1} total`-style template passed via `data-*` on `#mediaDropZone` (idiom: `#draftRecoveryBar`, `EntryEdit.jsp:82-84`). |
| MediaFileEdit strings | `mediaFileEdit.thumbnail`, `mediaFileEdit.url`, `generic.copyToClipboard` keys; decorative previews (`:34,:47,:174`, AddSuccess `:51`) → `alt=""`. |
| TemplateEdit alert | New key `pageForm.launch.linkChanged`. |
| Submissions | `submissions.showFullMessage` key. |

- [x] **Step 1:** Extend `MessageKeyTest` JSP arm with a hardcoded-English tripwire scoped to the exact attributes fixed here (e.g. assert no `placeholder="Username"`, no `aria-label="Close"`, no `>Newer<`/`>Older<` literals in the JSP tree). Watch fail.
- [x] **Step 2:** Apply; add keys; green (including the existing every-key-resolves arm).
- [x] **Step 3:** Commit — `fix(i18n): route remaining hardcoded UI strings through the bundle`

### Task 10: Bundle value quality sweep (values only — key names never change)

No new tests: this rides T7's ratchets plus grep verification; per-string tests would be assertion theatre per repo policy. Characterisation: `MessageKeyTest` green before and after.

| Fix | Detail |
|---|---|
| Typos | `:457` "Perfom"→"Perform"; `:503` "Select the the"→"Select the"; `:699-710` `pageForm.tip`/`tip.required` "Refer the the"→fix, add the missing space after "generates.", drop the dead "Roller Template Guide" reference; `:687` "effect"→"affect"; `:723` strip trailing `": "` from `pageRemove.confirm` (double colon with `Templates.jsp:121`); `:325` "User not found system"→"User not found."; six double-spaces (`themeEditor.importRequired` et al.). |
| Stale copy | `websiteSettings.removeWebsiteWarning` (:1339) — remove "blogroll", fix the mid-list `<br/>`: "removing a weblog removes everything: all of its entries, media files and settings. This is NOT reversible."; `categoriesForm.rootPrompt` (:100) — rewrite flat-categories copy in the `empty.*` register; `mediaFileView.rootPageTip` (:550) — one sentence; `weblogEntryQuery.tip` — keep "date range" (Task 1 fixed the filter). |
| Terminology | Sweep the 16 "blog" values to "weblog" (worst screens: Entries title "Blog Entries", ThemeEdit body ×4, `websiteSettings.about`, `weblogEdit.publishedEntry`, `pagesForm.themesReminder`, jsonLdType tips). Leave `websiteSettings.analyticsSiteId*` (Umami vendor terms) and all KEY names alone. |
| Casing | Sentence-case the 11 Title-Case button/menu labels (`weblogEdit.deleteEntry`, `fullPreviewMode`, `submitForReview` → "Submit for review", Maintenance ×3, `rememberMe`, `yourWebsites.editProfile`, `generic.yesRemove` → "Yes, remove"); `weblogEdit.copyPermalink` "copy"→"Copy"; unify the three create-weblog labels on "Create a weblog" (fix the trailing space in `yourWebsites.createWeblog`); login-verb drift → "Sign in" for `loginPage.prompt`/`loginPage.login` **(check IT assertions on the login button text first — `#login` is pinned by id, but grep for the literal)**. |
| Markup in values | `:987` drop the class-less `<span>`; `:1373` `New&nbsp;Entry` → `New Entry` + CSS gap in `MainMenu.jsp:56-57`; fix `configForm.prompt` unclosed `<p>` AND change `GlobalConfig.jsp:21` to not double-wrap. |
| Hardcoded sentence | `MainMenu.jsp:73` "You have …" → parameterized `yourWebsites.permission.summary={0}` with the joined localized role list. |
| `denied.jsp:32-39` | Normalize weblog/site terminology; trim to actionable reasons (PENDING review path verified still real — keep that reason). |
| Defaults | `runtimeConfigDefs.xml` `site.shortName` default "Front Page" → "Roller" (**edit carefully: bare `--` in an XML comment breaks this file SILENTLY**). |
| Housekeeping | Delete the 9 orphan section-header comment blocks (Bookmarks/Ping/Planet/etc.); fix `_es.properties:20` `�` → `í` and spot-check that bundle's values for the same lossy transcode; `UserAdmin.jsp:33` dead `inviteMember.userName` label → `userSettings.username`. |

- [x] **Step 1:** Apply; run `mvn -pl app test -Dtest='Message*Test'` + full `mvn -pl app test` (flash-message assertions in controller tests may pin old copy — update those in the same commit and say so).
- [x] **Step 2:** Verification greps return zero: `grep -n "Perfom\|the the\|not found system\|blogroll" app/src/main/resources/ApplicationResources.properties`.
- [x] **Step 3:** Commit — `copy: bundle value sweep (typos, stale features, terminology, casing)`

---

## Wave 3 — Feedback plumbing

### Task 11: messages.jsp overhaul + GenericError body

**Files:** `tiles/messages.jsp`, `tiles/tiles-tabbedpage.jsp:94-97`, `tiles/tiles-mainmenupage.jsp:96-98`, `core/GenericError.jsp`, `roller-ui/styles/roller.css`.

| Change | Detail |
|---|---|
| Live regions | `#messages` div gets `role="status" aria-live="polite"`; `#errors` gets `role="alert"`. (House pattern already at `EntryEdit.jsp:81`.) Highest value-per-character fix in the a11y sweep. |
| Auto-dismiss | Scope the 10-second dismiss (`messages.jsp:20-27`) to `#messages .alert` only — error banners currently vanish while the user is reading them, and on GenericError the whole page empties. |
| Structure | Success currently renders `.alert-info` divs NESTED inside `.alert-success` (box-in-box, wrong color) — flatten to one `.alert-success` wrapper with a plain list, matching the errors block. |
| Inline style | Move the `<style>` block (`:28-36`) into `roller.css` beside the `.alert-*` token rules (~`:1625`). |
| Order | tabbedpage renders messages ABOVE the page title, mainmenupage below — standardize on title-then-messages in both. |
| GenericError | The view has NO body (license + taglib only) — CreateWeblogController's "creation disabled"/"one per user" answers render one auto-dismissing alert on a blank page. Give it an `.empty-state` body: one-sentence explanation + a single "Back to your weblogs" link to `/roller-ui/menu.rol`. |

- [ ] **Step 1:** Failing source-scan (new method in an existing tiles/JSP test or `AdminJspHygieneTest`): `messages.jsp` contains `role="status"` and `role="alert"`; contains no nested `alert-info` inside the success block; `GenericError.jsp` contains `empty-state`.
- [ ] **Step 2:** Apply; green; `RouteSweepIT` markers unaffected (`#messages`/`#errors` ids unchanged — verify no IT greps the alert classes: `grep -rn "alert-info" it-selenium/src/test/java`).
- [ ] **Step 3:** Commit — `fix(admin): flash messages announce, persist errors, render flat; GenericError gets a body`

### Task 12: Missing and mis-wired feedback

**Files & changes:**

| Site | Fix | Test |
|---|---|---|
| `TemplatesController.java:79-125` | Add success flash `pagesForm.added` with the template name after add. | Controller test asserts the message key lands. |
| `TemplatesController.java:128-179` | Add delete success message; the not-found branch adds TWO stacked errors (`:142` + `:175`) — return after one. | Controller test: unknown id → exactly one error. |
| `MediaFileViewController.java:109` | Folder-name collision reported via `addMessage` (green banner) → `addError`. | Controller test asserts error, not message. |
| `MembersController.java:78-154` | No-change save reports nothing → add a neutral `memberPermissions.noneChanged=No changes to save.` message in the else branch. | Controller test. |
| `ThemeEditController.java:127` | Passes the literal constant `"custom"` as the theme name into the success message → use `themeEditor.setCustomTheme.success` (already used at `:111`). | Controller test asserts the key. |
| `MediaFileAddSuccess.jsp:117-118` | Non-image files described as "`0 x 0 pixels`" → drop the dimensions line from the enclosure loop (keep it in the image loop `:68-69`). | JSP scan or characterisation note. |
| `EntryEditController` publish flash | `weblogEdit.publishedEntry` gains a view link: `Blog entry published! <a href="{0}">View it</a>` with `entry.getPermalink()` as the arg. **Constraint: the argument must remain server-built — `messages.jsp` renders unescaped.** | Controller test asserts the permalink arg is passed. |

- [ ] **Step 1:** Failing controller tests per row; **the new bundle keys ride MessageKeyTest** (Java arm from T7 now enforces them).
- [ ] **Step 2:** Apply; green; commit — `fix(feedback): every consequential action reports; errors render as errors`

---

## Wave 4 — A11y & markup (admin JSP lane)

### Task 13: Label bindings + control names

**Files:** the JSPs listed in the table. **Test first:** new `JspLabelBindingTest` (source-scan, same pattern as `EditorJspEscapingTest`):

```java
/** Every <label for="X"> must have a matching id="X" in the same JSP, and the
 *  known form screens must not regress to unbound labels. The sweep of
 *  2026-08-20 found ~80 unbound or dangling labels; this pins the repair. */
@Test
void everyLabelForTargetsAnIdInTheSameFile() throws Exception {
    for (Path jsp : allAdminJsps()) {
        String src = Files.readString(jsp);
        Matcher m = Pattern.compile("<label[^>]*\\bfor=\"([^\"$]+)\"").matcher(src);
        while (m.find()) {
            assertTrue(src.contains("id=\"" + m.group(1) + "\""),
                jsp.getFileName() + ": label for=\"" + m.group(1) + "\" has no target");
        }
    }
}
```

Run → fails today on `MediaFileImageDimension.jsp` (`for="status"` ×3) and `EntriesSidebar.jsp` (fixed in T1 — if T1 landed, only ImageDimension, which T22 deletes; keep the test regardless).

**Then the binding sweep — add id+`for` pairs (or convert non-form "labels" to `<span class="col-form-label ...">`):**

| File | Sites |
|---|---|
| `admin/GlobalConfig.jsp` | `:36,:52,:64,:75,:119` — copy the proven `globalConfig_${pd.nameWithUnderbars}` pattern from the integer/float branches (`:87-95,:103-111`). |
| `editor/WeblogConfig.jsp` | 12 labels `:48-:172`. |
| `admin/UserEdit.jsp` | 10 labels `:62-:161` (three targets already exist: `bean_screenName`,`bean_fullName`,`bean_email` — one-attribute fixes). |
| `core/Profile.jsp` | 8 (`form:input` tags accept `id=`). |
| `editor/MediaFileEdit.jsp` | id+for at `:72,:109,:116,:125,:132,:139`; convert `:31,:44,:77,:91` (non-form content) to spans. |
| `editor/MediaFileSidebar.jsp` | `:66,:69,:76,:91` — targets exist (`beanName`,`beanType`,`sizeFilterTypeCombo`,`beanTags`), add `for=` only. |
| Others | `MediaFileAdd.jsp:29-50`; `TemplateEdit.jsp:40,55,65,92,143,178`; `Categories.jsp:135,142,149` + label the `:315` `targetCategoryId` select (destructive-flow modal); `Setup.jsp:97,109`; `CreateWeblog.jsp` ×6; `Entries.jsp:288,297`; `EntryEdit.jsp:249,261,277` (+ `:591,:600` read-only → span); `PageEdit.jsp:194`; `TemplatesSidebar.jsp:26-27` (wrap the loose `generic.name` text in a label). |
| `editor/ThemeEdit.jsp` | `:38,:57` wrap radio+message in `<label>` inside the h3; `:94` label `#themeSelector`. |
| Loop controls → `aria-label` | Row checkboxes: `Entries.jsp:121` (`${fn:escapeXml(post.title)}`), `Submissions.jsp:80`, `MediaFileView.jsp:196,249`, `MediaFileAddSuccess.jsp:46` + radios `:101,:138`. `Members.jsp:130-145` permission radios: `aria-label` = column header key + `${fn:escapeXml(perm.user.userName)}`. |
| Macros | `weblog.vm:1185,1252` search inputs + `:1189,:1256` category selects get `aria-label` (key `macro.weblog.searchbutton` or new). |

- [ ] Apply; `JspLabelBindingTest` green; `RouteSweepIT` unaffected (only attributes added).
- [ ] Commit — `a11y(admin): bind every form label; name loop controls`

### Task 14: Landmarks, headings, scope, modals, lang, titles

**Files:** the 8 tiles layouts, `core/MainMenuSidebar.jsp`, the modal-bearing editor JSPs, the data-table JSPs. **Known coupling:** `Routes.java:86` and `RouteSweepIT.java:52` mention `<h2 class="roller-page-title">` in COMMENTS only (verified — no assertion on the tag name); update both comments in the same commit.

| Change | Sites |
|---|---|
| `h2` → `h1` page title | `tiles-tabbedpage.jsp:96`, `tiles-mainmenupage.jsp:96` (simplepage/loginpage already `<h1>` with the same class — pure element swap, CSS shared). |
| `<html lang>` | All 8 tiles layouts: `lang="${pageContext.response.locale}"`. |
| `<main>` landmark | tabbed/mainmenu: the `col-md-9 roller-column-right` div → `<main class="...">` (do NOT rename the class); simple/login: `<main>` on the `col-md-10` wrapper. |
| Rail headings | `MainMenuSidebar.jsp:28,35,44` `h4`→`h3`. |
| Card-title skips → `h3` | `MediaFileAddSuccess.jsp:33,91`; `MediaFileAdd.jsp:62`; `EntryEditor.jsp:60`; `TemplateEdit.jsp:131`; `PageEdit.jsp:170`; `MediaFileEdit.jsp:170` (**id `cropSectionTitle` is pinned by MediaCropIT — keep the id, swap the tag only**). |
| `scope="col"` | Every `<th>` in: `Entries.jsp:84-102`, `Submissions.jsp:66-74`, `Members.jsp:101-113`, `Trash.jsp:41-45`, `Pages.jsp:47-53`, `Categories.jsp:45-48`, `Templates.jsp:45-48`, `UserEdit.jsp:181-184`, `EntryEdit.jsp:431`. |
| Modals | Add `tabindex="-1"` to `EntryEditor.jsp:82` + `PageEdit.jsp:275` (Esc is dead on the media pickers today). Add `aria-modal="true"` + `aria-labelledby="<heading id>"` to all 11 listed modals; add ids to the 7 headings that lack one (`Categories.jsp:119`'s target already exists). |
| Collapse toggles | `EntryEdit.jsp:211`, `EntryEditor.jsp:61`, `TemplateEdit.jsp:132`, `PageEdit.jsp:171`: add `role="button" aria-expanded="false" aria-controls="<target id>"` (Bootstrap 5 maintains the state). |
| Layout titles | `tiles-errorpage.jsp:26` → `${site.shortName}: ${pageTitle}` pattern + give the denied path a `denied.title=Access denied` pageTitle; `tiles-installpage.jsp:26` add the shortName prefix; `tiles-popuppage.jsp` add a `<title>` (same line as simplepage `:26`). |
| Flash region order | (done in T11 — no-op here). |

- [ ] **Step 1:** Failing scan additions (`AdminJspHygieneTest`): every tiles layout contains `lang=`; tabbed/mainmenu contain `<h1 class="roller-page-title"`; the 9 tables' header rows contain `scope="col"`; listed modal divs contain `aria-modal`.
- [ ] **Step 2:** Apply; green; run `mvn verify -Pit -Dit.test=RouteSweepIT` (full chrome render per route) and `MediaCropIT` if practical.
- [ ] **Step 3:** Commit — `a11y(admin): landmarks, heading order, table scope, modal semantics, document lang`

### Task 15: Semantics & hygiene sweep

(Excludes the controls already converted in T3.)

| Change | Sites |
|---|---|
| `href="#"` action → `<button>` | `EntryEditor.jsp:52` + `PageEdit.jsp:162` (`onClick`→`onclick` too); `EntryEditor.jsp:40` + `PageEdit.jsp:149` shortcode `dropdown-item` anchors → `<button type="button" class="dropdown-item shortcode-card">` (Bootstrap-native); `weblog.vm:1378` popup OK (low reach). |
| navbar-brand | `bannerStatus.jsp:27` → `href="<c:url value='/'/>"` and drop the now-duplicate shortName nav item at `:40`; `bannerInstallation.jsp:23` → `<span class="navbar-brand">`. |
| Icon names | `Entries.jsp:130,172,184` (list controls), `Pages.jsp:79,84`, `Templates.jsp:80` (post-T3 buttons): `aria-label` on the control (name + item), `aria-hidden="true"` on the icon span. `Templates.jsp:87` bi-lock gets `role="img" aria-label` ("cannot be deleted"). Six stray icons get `aria-hidden`: `Members.jsp:126`, `MediaFileSidebar.jsp:28,44`, `MediaFileImageChooser.jsp:104`, `EntriesSidebar.jsp:61,75`. `MainMenu.jsp:79` `aria-label="..."` literal → `${fn:escapeXml(perms.weblog.name)}`. `MediaFileEdit.jsp:178` `cropper-grid role="grid"` → `role="presentation"`. |
| img alt | `UserEdit.jsp:197,205,213` → replace the tripled PNG with the three `bi-*` spans MainMenu uses (`bi-pencil`/`bi-list`/`bi-gear`), `aria-hidden` (also closes the admin/core sweep's icon-consistency finding); `Templates.jsp:57,60` `alt="icon"`→`alt=""`; `MediaFileAddSuccess.jsp:50` `alt="${fn:escapeXml(newImage.name)}"` + drop `align="center"`; `ThemeEdit.jsp:101` + `CreateWeblog.jsp:95` `alt=""` in markup, JS sets alt=theme name when it sets src. |
| Deprecated attrs | Drop `border="0"` (`MediaFileView.jsp:179,187,234,242`, `MediaFileImageChooser.jsp:98`, `Templates.jsp:57,60`); `<hr size noshade>` ×4 sidebars → `<hr/>`; `td align="center"` `Categories.jsp:60,76` → class; malformed `width="%30"/"%15"` + inline table style `UserEdit.jsp:179-215` → delete widths, let `.rollertable` size. |
| Positive tabindex | Delete every `tabindex` on `MediaFileEdit.jsp` (alt-text field currently tabs AFTER Cancel) and `EntryEdit.jsp:92`/`EntryEditor.jsp:25,71` — DOM order is correct. |
| Duplicate ids | `MediaFileView.jsp:199-200,252-253` `id="mediafileidentity"` in loops, read by nothing → delete the hidden inputs. `weblog.vm` search macros both emit `id="q"` on one frontpage page → parameterize (`q-main`/`q-again`; JS reads `form.q` by NAME — verified safe; grep theme CSS for `#q` first). |
| Empty-state placement | `Pages.jsp:92-104` + `Submissions.jsp:123-132` move the empty state out of the table to a sibling (everyone else's pattern); delete the defensive CSS reset at `roller.css:1279-1289` it necessitated; add `empty.pages.title`/`empty.inquiries.title` keys and retire the two stray non-`empty.*` titles. |

- [ ] **Step 1:** Failing scans: no `tabindex="[1-9]` in the JSP tree; no `border="0"`; no `href="#"` with `onclick` in the editor tree (T3 + this task complete the set).
- [ ] **Step 2:** Apply; green; `RouteSweepIT` (Pages/Submissions empty-state markers — check `Routes.java` pins for those two routes and update in the same commit if they reference the in-table structure).
- [ ] **Step 3:** Commit — `a11y(admin): semantics, icon names, deprecated markup, tab order, duplicate ids`

---

## Wave 5 — Visual polish & protections

### Task 16: CSS + button hierarchy + structural markup

**Files:** `roller-ui/styles/roller.css`, the JSPs named below. **DesignTokenTest constraint:** any new color must be a `var(--*)` token reference — no hex literals.

| Change | Sites |
|---|---|
| `.subtitle` rule | roller.css: `p.subtitle { color: var(--ink-soft); font-size: 14.5px; margin: 0 0 1em; }` — the class is used on ~15 screens and styled NOWHERE (the single largest visual inconsistency found). |
| Badge token | `.badge.bg-secondary` rule beside the four existing overrides (`roller.css:1606-1621`) — MainMenu's entry count currently ships stock Bootstrap grey. |
| Install/error type | Add `.section-head` to the bare `<h3>`s and `.roller-page-title` to the `<h2>`s on `UnknownError.jsp`, `DatabaseError.jsp`, `CreateDatabase.jsp`, `UpgradeDatabase.jsp`, `denied.jsp` (they render LARGER than the page title today — weight-not-size violation). Same for the four sidebar bare `<h3>`s (`MediaFileSidebar.jsp:25,60`, `EntriesSidebar.jsp:20`, `CategoriesSidebar.jsp:20`, `TemplatesSidebar.jsp:20`). |
| Inline styles → classes | `tiles-installpage.jsp:36` margin → `.install-page`; `Setup.jsp:20` → `.pagetip`; `CreateWeblog.jsp:55,95` → `.handle-preview`/`.theme-thumb`; `Setup.jsp:66` + `UserEdit.jsp:232` + `CreateWeblog.jsp:22` + `MainMenuSidebar.jsp:50` + 3 error pages: `<br/>` spacing → `mt-*`/`mb-*`. |
| Footers | `tiles-loginpage.jsp` + `tiles-simplepage.jsp`: include the configured-but-unused `${tile_footer}` (match `tiles-tabbedpage.jsp:108-112`). Close `tiles-installpage.jsp`'s unclosed `#wrapper`; delete the dead `#leftcontent`-era scaffolding divs in errorpage/popuppage/installpage; `<div id="footer">`→`<footer class="footer">`. Fix `tiles-simplepage.jsp:43`'s `<p>` wrapping a block alert. |
| Unclosed markup | `TemplateEdit.jsp:125` `#accordion` missing `</div>` (29 open/28 close — add before `</c:if>` at `:188`); `ThemeEdit.jsp:100` `<p>` containing `<p>`. |
| Form classes | `Maintenance.jsp:22` + `UserAdmin.jsp:29` dead `form-vertical` → `form-stacked`; `GlobalConfig.jsp:79` checkboxes get `form-check-input` (keep the `boolean` JS hook); `GlobalConfig.jsp:139` `<input type="submit">` → `<button type="submit" id="saveButton" class="btn btn-primary">`; rename `:147`'s `var saveBookmarkButton` → `saveButton`. |
| Primary buttons | Promote the single save/submit to `btn-primary` on: GlobalConfig, UserEdit (`:236`), Profile (`:98`), CreateWeblog (`:100`), Setup (`:115`), CreateDatabase (`:57`). MainMenu populated card: New Entry → `btn-primary` (`:85`). UserEdit "Send set-password link" → quiet `btn-link` below the form + `onsubmit` confirm naming the user and email; add a `form-text` note under Enabled that disabling signs the user out immediately. **IT-safe: relevant tests pin ids (`#saveButton`, `#save_button`, `#user-submit`), and `button[type='submit'].btn-success` selectors live in editor ITs only — re-grep before committing.** |
| Maintenance | Wrap each prompt+button pair in a bordered row; add `confirm()` naming the selected weblog to Rebuild Search Index and Regenerate Renditions. |
| Editor odds | `PageEdit.jsp:239-241` red `btn-danger` delete → quiet `.delete-link` (siblings' pattern); `Login.jsp:57-60` delete the `type="reset"` button (keep `#login`); rail cards `EntryEdit.jsp:393,427` → `.editor-box` + `.rail-group-label` instead of `.card`/`.card-header`; `roller.css` `.editor-grid` → `column-gap: 26px; row-gap: 14px;` (fixes the 14px-vs-26px rail seam); SEO drawer (`EntryEdit.jsp:218-368`): replace `row`/`col-sm-*` pairs inside `#collapseSeo` with the stacked `.editor-field-label` pattern the Publish/Organize boxes use — **field ids/names/JS unchanged** (the browser-test contract at `:207-209` holds). |

- [ ] **Step 1:** Failing scans: roller.css contains `p.subtitle`; the six named forms contain exactly one `btn-primary`; `DesignTokenTest` green (no new hex).
- [ ] **Step 2:** Apply; run `RouteSweepIT` + `UserAdminIT` (button assertions) if practical.
- [ ] **Step 3:** Commit — `design(admin): subtitle role, primary actions, install/error type scale, structural markup`

### Task 17: Form protections

| Change | Detail |
|---|---|
| Double-submit guard | New shared helper in the admin script loaded by `tiles/head.jsp` (`theme/scripts/roller.js`): on `submit` of any `form.guard-submit`, disable the ACTIVE submit button after the submit event fires and swap in a busy label from `data-busy-label`. **The formaction caveat is load-bearing: disabling before the event drops the button's name/value, which is how `Entries.jsp` and `Maintenance.jsp` route** — disable inside a `setTimeout(0)` or on the event after default is committed. Opt in the slow four first: `MediaFileAdd.jsp:75` (upload), `Maintenance.jsp` (three ops), `EntryEdit.jsp:490` (newsletter send — irreversible), `ThemeEdit.jsp:134,158,180` (imports), plus `MediaFileEdit.jsp:199` (crop). |
| Autocomplete | `Login.jsp:36,41` `autocomplete="username"`/`"current-password"`; `ResetPassword.jsp:31,36` + `Profile.jsp:69,79` + `UserEdit.jsp` password fields `"new-password"`. |
| Autofocus | Fix the CSRF-focus bug: `UserEdit.jsp:262` + `CreateWeblog.jsp:111` `document.forms[0].elements[0].focus()` focuses the hidden CSRF input — delete the JS, put `autofocus` on the first real field. Add `autofocus`: `ResetPassword.jsp:36`, `Profile.jsp` screen-name, `EntryEdit.jsp:92` title, `PageEdit.jsp:59` title. Category modal: focus the name field on `shown.bs.modal` (`CategoriesSidebar.jsp:38-56` clears fields but never focuses). |
| maxlength vs schema | `WeblogConfig.jsp:50,64,78` 40→255 (columns are varchar(255)); `Profile.jsp:58` email 40→255; password fields: DROP maxlength everywhere (20/30/unlimited drift; validator enforces min 8); `MediaFileEdit.jsp:72,125,132` → 255/255/1023; `MediaFileSidebar.jsp:50` 25→255; `Members.jsp:61` 30→255; `TemplatesSidebar.jsp:27` add 255; `MediaFileAdd.jsp:31,38` add 255/1023. |
| Input types | `WeblogConfig.jsp:78` `type="email"`; `:85` `entryDisplayCount` `min="1" max="${maxEntries}"` (expose `site.pages.maxEntries` in the model) + form-text; `PageEdit.jsp:124` navOrder `min="0"` + one-line hint. `CreateWeblog.jsp:44` handle format `form-text` (localized). |
| Upload cancel | `MediaFileAdd.jsp:76` Cancel is `type="submit"` in a multipart form — uploads the whole batch before discarding → `<a class="btn" href="mediaFileView.rol?weblog=...">`. `MediaFileAddController.java:199` cancel redirect loses `directoryId` → append the resolved directory id. |
| Submissions | `Submissions.jsp:134-137` bulk delete gets the `Entries.jsp:326-352` count-modal (permanent delete of visitor inquiries currently one unconfirmed click — the only naked destructive control in the app); port the 4-line select-all sync from `Entries.jsp:374-377`. |
| Search-vs-sort | `MediaFileView.jsp:117` hide the sort select when `pager` is present (sorting currently discards the search silently). `MediaFileImageChooser.jsp:49-50` label the blank folder option + the select. `MediaFileView.jsp:477` drop the confirm on the non-destructive Move. |

- [ ] **Step 1:** Failing scans: Login carries `autocomplete=`; no `maxlength="40"` in `WeblogConfig.jsp`; `Submissions.jsp` references the bulk-delete modal. Controller test for the cancel-redirect `directoryId`.
- [ ] **Step 2:** Apply; green; manual smoke of the guard on a slow upload (button disables, `formaction` routing still works on Entries bulk bar).
- [ ] **Step 3:** Commit — `ux(admin): submit guards, autocomplete/autofocus, schema-true maxlengths, upload cancel, submissions confirm`

---

## Wave 6 — Usability features

### Task 18: Copy/view affordances

| Feature | Detail |
|---|---|
| Add-success copy buttons | `MediaFileAddSuccess.jsp:93,121`: the just-uploaded URL is plain text — add the `.clipbutton` + readonly-input idiom from `MediaFileEdit.jsp:96-103` (ClipboardJS is global). |
| Copy feedback | `MediaFileEdit.jsp:309`: `clipboard.on('success', ...)` toggling the existing `.copied` class (entry editor already does this). |
| Copy shortcode | New read-only row on MediaFileEdit: `[image id="${mediaFileId}"]` + clipbutton — the canonical embed string is currently never shown outside the picker. |
| Pages → live page | `Pages.jsp:73`: PUBLISHED rows link the slug to `${actionWeblog.absoluteURL}page/${p.slug}` (`target="_blank" rel="noopener"`); `PageEdit.jsp` gains the entry editor's permalink line + copy control when `bean.id` is set. |
| ThemeEdit view link | Persistent "View your blog" `<a target="_blank" rel="noopener" href="${actionWeblog.absoluteURL}">` in the `#sharedNoChange`/`#customNoChange` blocks (the Preview button hides exactly when you want it, right after saving). |
| WeblogConfig URL | Read-only line under General showing `${actionWeblog.absoluteURL}` as link + clipbutton; a live link beside the custom-domain tip when set. |
| copyPermalink hardening | `EntryEdit.jsp:644-649`: guard `navigator.clipboard &&` (undefined on non-HTTPS — control is silently dead there), `.catch()` fallback selecting the text, feedback via `data-copied` text inside the existing `role="status"` region (color-only today). Drafts: hide the copy control for unpublished entries (the URL 404s today). |
| SEO snippet URL | `EntryEdit.jsp:253` empty on entryAdd → fall back to `${actionWeblog.absoluteURL}` + placeholder slug. |
| Alt badge | `MediaFileView.jsp:206-209,259-262`: the "no alt text" badge whose tooltip says "Open it to add one" becomes a `<button>` calling the same `onClickEdit(id, name)` as the tile. |

- [ ] Tests: JSP scans for the permalink-row presence on PageEdit and the clipbutton on AddSuccess; the rest is markup + existing patterns. New keys ride MessageKeyTest.
- [ ] Manual smoke each affordance in `./roller dev`; commit — `ux(admin): copy and view affordances`

### Task 19: Editor niceties

| Feature | Detail |
|---|---|
| Ctrl/Cmd+S, Ctrl+Enter | EasyMDE `extraKeys` (CodeMirror) mapping Cmd-S/Ctrl-S → click Save draft, Ctrl-Enter → click Publish, PLUS a document-level `keydown` (for focus in the title field) — both `preventDefault()` (browser Save dialog opens today). Same for `PageEdit`. Goes through the editor seam functions only. |
| Enter in single-field flows | Categories modal (form has NO submit button — implicit submission never fires): bind Enter on the inputs → `submitEditedCategory()`. `MediaFileSidebar.jsp:68-72` new-folder input: Enter → `onCreateDirectory()`. |
| Session-expiry banner | Read `${pageContext.session.maxInactiveInterval}` into the editor pages; client-side timer reset on input; at T-2min reveal a `.draft-bar`-styled banner "Your session expires soon — save now." Local arithmetic only, no endpoint; drift fails safe (warns early). |
| Trash countdown | `TrashController.execute` adds `trashRetentionDays` (read the runtime property per request — scope trap); `Trash.jsp` renders a three-branch sentence under the subtitle (−1 never / 0 next sweep / N days) + optional per-row "purges in N days". New keys. |
| Tab titles | Append context to `pageTitle`: entry editor → `entry.getDisplayTitle() + " — " + …`; weblog-scoped layouts add `${actionWeblog.handle}` to the `<title>`. (`<spring:message code="${pageTitle}" text="${pageTitle}"/>` already falls through for literals.) |
| favicon.ico | Ship a real `/favicon.ico` (render from `favicon.svg`); retire the BrowserHealth exemption at `BrowserHealth.java:68` in the same commit (the suite currently excuses that 404 on every page). |
| Last-used category | In the editor page JS, store the chosen category id in `localStorage` beside the draft keys on submit; on `entryAdd`, preselect it if present and still in the list. No schema, no server change. |
| Media → new entry | "New entry with selected" button in `MediaFileView.jsp`'s `.image-controls` bar posting checked `selectedMediaFiles` to `entryAddWithMediaFile.rol` via the leftover `#createPostForm` (`:20-25`). **First step: read `EntryAddWithMediaFileController` and confirm the expected param names; if they don't line up in ~15 minutes of work, drop this item and note it.** |
| Entries status | Add a narrow status column to `Entries.jsp` using the `Pages.jsp:79-88` badge pattern + existing `weblogEdit.published/draft/pending/scheduled` keys (color-only rows are an a11y problem); render the five status options as GET link-chips above the table (`entries.rol?weblog=X&bean.status=DRAFT`) marking the active one — possible now that T1 made the filter GET. Bulk actions keep the filter: `EntriesController.backToList` (`:312-315`) reuses `buildBaseUrl` with the bean from the three bulk handlers. |

- [ ] Tests: controller test for `backToList` preserving `bean.status`/`bean.categoryName`; JSP scan for the badge column; BrowserHealth exemption removal compiles + `RouteSweepIT` green (favicon now 200).
- [ ] Commit per coherent chunk (keyboard+session; trash+titles+favicon; entries list; media) — 4 commits.

---

## Wave 7 — Themes (lane D)

### Task 20: Theme a11y & dark mode

**Files:** all four themes' templates + CSS, `WeblogWrapper.java`, `roller-ui/errors/{404,403,error}.jsp`. Tests: extend the four theme rendering tests.

| Change | Detail |
|---|---|
| `<html lang>` | Add `getLanguageTag()` to `WeblogWrapper` (`getLocaleInstance().toLanguageTag()` — the raw locale is `en_US`, not BCP-47); emit `lang="$model.weblog.getLanguageTag()"` in the 12 theme templates + fix `templates/weblog/page.vm:132`'s `lang="en_US"`. TDD: wrapper unit test first. |
| Skip links | First child of `<body>` in each theme: visually-hidden-until-focus "Skip to content" → `#main` (add the id to each `<main>`); one CSS rule per theme. |
| Focus | `journal-custom.css:428-433` + `portfolio-custom.css:295`: replace `outline: none` with the theme-token focus outline (travel `:233` / portfolio `:374` already show the pattern); add the missing global `:focus-visible` rules `body.pf` and `body.tg`. |
| color-scheme | `journal` `:root { color-scheme: light dark; }`; `portfolio` `color-scheme: dark;` (always-dark theme currently gets light scrollbars/form chrome on `#0e0e10`). |
| Dark contrast | `journal-custom.css:444` hardcoded `#FFFFFF` on the accent button ≈2.2:1 against the dark-mode accent → dark-mode override `color: var(--qj-paper)` (portfolio `:399` pattern). **DesignTokenTest does not audit theme CSS, but keep it token-based anyway.** |
| Headings | Site names `p`→`h1` (`journal/travel/portfolio weblog.vm:25`, `frontpage/_header.vm:33` — CSS verified class-scoped, pixel-identical); entry titles step to `h2` under it; portfolio card titles get `<h3>` inside the figure (grid currently has NO headings); `frontpage` `fd-label` paragraphs → `<h2 class="fd-label">` (`weblog.vm:14,67,91`, `_blogdirectory.vm:26,28`); `weblog.vm:1293` search-count `h3` → `<p class="search-summary" role="status">`. |
| Nav labels | `aria-label` on the unnamed `<nav>`s (frontpage `_blogdirectory.vm:41`, portfolio/travel `weblog.vm:42` + `permalink.vm:38` — journal's pattern); `role="search"` on the footer search forms; frontpage live dot `aria-hidden="true"` (it is decorative — always green regardless of activity). |
| Error JSPs dark | `404.jsp/403.jsp/error.jsp` declare `color-scheme: light dark` then style light-only → add the dark `@media` block (background/color/.code). |

- [ ] **Step 1:** Failing wrapper test (`getLanguageTag` returns `en-US` for `en_US`); failing rendering-test assertions (`lang="`, `<h1`, skip link, `color-scheme`).
- [ ] **Step 2:** Apply; all four theme rendering tests green **with pinned CSPs byte-identical**.
- [ ] **Step 3:** Commit — `a11y(themes): lang, skip links, focus, color-scheme, heading structure`

### Task 21: Theme polish

| Change | Detail |
|---|---|
| Titles per context | Each theme's `weblog.vm` `<title>` branches: category → `Category : Weblog`, tags → `Tagged x : Weblog`, else weblog name; `searchresults.vm` ×3 adds the query (`$model.term` is pre-escaped — verified `SearchResultsModel.java:162`); frontpage `_header.vm` accepts a page-title distinction for directory.vm. |
| Dates | `journal/_day.vm:31` + `frontpage/_entry.vm:9` `"MMM dd"`→`"MMM d"`; wrap every rendered date in `<time datetime="...">` (ISO via the utilities model — verify the formatter exists first; if not, skip datetime and note it). |
| Search page styling | Copy portfolio's `#searchAgain` 3-rule block (`portfolio-custom.css:286-320`) into journal + travel with their tokens (journal's search page currently shows raw OS form controls); journal `qj-search-pager` class without the `.qj-pager` top border. |
| Category crumb | `journal/_day.vm:19` plain-text category → link to `$url.category(...)` (portfolio/travel already do; also revives the dead `.qj-crumb a` rule); delete dead `.qj-nav a.active`. |
| Empty states | Zero entries → one intentional sentence in each theme's entry container (home/category/tag wording); (zero-search-hits + search-error landed in T5). |
| Newsletter prompt | `.newsletter-prompt`/`.newsletter-subscribe-block` (emitted into every footer by `#showSubscribeForm`) get a caps-label rule per theme. |
| Shortcode CSS lift | `.faq`, `.cta-card/.cta-label/.cta-note`, `.travel-map` framing, `.shortcode-image`/`.jgrid` margins from `travel-custom.css:134-181` → journal + portfolio with their tokens (`body.qj`/`body.pf` specificity precedent). A `[cta]` currently collapses to an unstyled inline anchor outside travel. `.video-figure`/`.video-embed` per-theme framing. |
| Entry tags | Call `#showEntryTags($entry)` in each theme's permalink `_day.vm` branch (journal byline, travel/portfolio meta) + a small tag-pill rule each — tag pages are currently unreachable from any theme. |
| Favicons | Add the corrected favicon `<link>` one-liner to journal/travel/portfolio heads (frontpage fixed in T6). |
| Dead macros | Delete from `weblog.vm`: the six jQuery-Mobile macros (`#showMobile*`, `#showMobilePopupDialog` — the library is not shipped; they cannot work), `#_showCommonJavascript` (ActiveX), `#_Jave`. KEEP and document `#showWeblogEntryLinksList`, `#showAtomFeedsList`, `#showEntryTags`, `#showMetaDescription` as the custom-theme API. Update the contents list at `:19-59`. **Velocity-grep every macro name over both template trees before deleting.** |

- [ ] **Step 1:** Failing rendering assertions: permalink render contains the tag link when the entry has tags; search results title contains the term; `<time` present.
- [ ] **Step 2:** Apply; all rendering tests green, CSPs untouched; run `mvn verify -Pit -Dit.test=ThemeMatrixIT` (all themes render the shortcode entry on home + permalink).
- [ ] **Step 3:** Commit — `polish(themes): titles, dates, search page, shortcode styling parity, tags, dead macros`

---

## Wave 8 — Dead code & docs (run last, alone)

### Task 22: Deletions

**Protocol per row: grep `app/src` AND `it-selenium/src` for every name before deleting; Velocity-grep for every Java member.**

| Delete | Evidence |
|---|---|
| `tiles/banner.jsp` | License header only; no view definition references it. |
| `MediaFileImageDimension.jsp`, `MediaFileAddSuccessInclude.jsp`, `MediaFileImageDimController` + its test, the two `RollerViewResolver` definitions (`:285,:304` area), `Routes.java:343` SkippedRoute | Dead screen behind a live route; pre-migration markup; bypasses the `insertMediaFile` seam and inserts raw `<img>`. |
| `images/openid-logo-small.gif` | No reference anywhere. |
| `CreateWeblog.jsp:87` `ng-app`/`ng-controller` | No Angular loaded. |
| `UserAdmin.jsp:54` `authMethod != 'LDAP'` c:if + the `authMethod` model attr in 4 controllers | `AuthMethod` has one constant; the test could never match. |
| `ajax-user.js:22-30` IE/ActiveX branch | → bare `new XMLHttpRequest()`; **keep the JSP scriptlet at `:35`** (translation-time include). |
| Dead JS/JSP fragments | `onSelectDirectory()` ×2; `MediaFileView.jsp:264-267` addbutton span (no handler exists); `Templates.jsp:76-79` unused `removeUrl`; `CategoriesSidebar.jsp:24-27` dead `${post.*}` c:sets; `Members.jsp` zebra `c:choose` + `rHeaderTr` (no CSS); empty `<style>` `MediaFileImageChooser.jsp:20-22`; legacy 4-div sidebar wrappers (`MainMenuSidebar.jsp:20-23`, `MediaFileSidebar.jsp:20`). |
| 8 orphan bundle keys | The exact set T7's word-boundary ratchet asserts — delete them, set the ratchet's expected orphans to zero. Locale-only stale keys (`yourWebsites.createOne` ×7, `yourWebsites.prompt.noBlog` ×6, 3 ja/zh) go too. |
| `TemplateEdit.jsp:127` `id="panel-plugins"` → `panel-advanced` | Stale plugins marker; not in Routes (re-grep). |
| `Routes.java:423` comment | Trim the stale `fetchDirectoryContentLight` note to one line (the route is already gone — only this comment remains). |

- [ ] Steps: grep-first per row → delete → `mvn -pl app test` → `RouteSweepIT`. Commit — `chore: delete dead UI code (screens, scripts, keys, fossils)`

### Task 23: Docs, memory, final verification

- [ ] Update `CLAUDE.md` if any invariant changed (candidates: the new i18n ratchets — add one line to the testing section naming `MessagePlaceholderContractTest` and the Java-side MessageKeyTest arm; the confirm-vs-modal rule from the T17 inventory: "native confirm when there is nothing to report beyond a sentence, modal when a count or target must be shown").
- [ ] Full gate: `mvn clean verify` (unit + PMD/SpotBugs/CPD + coverage floors).
- [ ] Browser suite: `mvn verify -Pit`. Nothing in this plan changed URL routing semantics, so the `-Dit.context.path=roller` pass is optional; run it if T4's SearchServlet change or T18's Pages links feel routing-adjacent.
- [ ] `bin/check-diff-coverage.sh <base>` over the whole range — expect JSP/VM/properties changes to be invisible to it; if Java error-path lines ding it, apply the accept-and-say-so policy.
- [ ] Report ready-to-push. **Do not push.**

---

## Self-review notes (performed at plan time)

- **Spec coverage:** every acceptance criterion maps to a task (W1→T1-T6, W2→T7-T10, W3→T11-T12, W4→T13-T15, W5→T16-T17, W6→T18-T19, W7→T20-T21, W8→T22-T23).
- **Conflicts resolved across sweeps:** `MediaFileView` addbutton = DELETE (no handler exists), not "make it a button"; `mediafileidentity` = DELETE (unread), not suffix; Categories/Entries/Pages/Templates controls converted once, in T3, with T15 explicitly excluding them; the h2→h1 swap verified against `Routes.java` (comments only).
- **Verified at plan time:** jQuery UI 1.14.2 loaded (`head.jsp:14`); `UtilitiesModel.unescapeHTML` exists (`:224`); `generic.toggle` exists, `generic.close` does not; `roller-page-title` appears only in test comments.
- **Known judgment calls an executor must NOT widen:** no CSP edits; no PRG conversion beyond the two named redirect targets; no new translations; the `[gallery]`-empty-directory behavior stays as-is.
