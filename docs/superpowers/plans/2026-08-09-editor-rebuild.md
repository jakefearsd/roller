# Entry Editor Rebuild Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild EntryEdit as the approved "writing surface + publish rail" design and delete the fossils (three-select time picker, enclosure URL, per-entry RTL, the plugin system's last plugin and its UI).

**Architecture:** Server-side first (bean/controller surface shrinks, one `datetime-local` pubtime), then the plugin removal (V021), then the JSP rebuild against the frozen contract of names/ids the ITs pin, then IT ports and docs. The design authority is the approved card (`docs/design/` copy landed in Task 3; artifact + design project "Editor" group) plus the confirmed rule: **emphasis is weight, never size**.

**Tech Stack:** Spring MVC controllers, EasyMDE (seam preserved verbatim), roller.css design system, Selenide ITs.

## Global Constraints

- **Contract-frozen names/ids (the ITs' hard contract — never rename):** form `#entry`; `input[name='bean.title']`, `bean.id`, `bean.status`, `bean.tagsAsString`, `bean.categoryId`, `bean.locale`, `bean.summary`, `bean.text`, `bean.commentDays`, `bean.allowComments`, `bean.pinnedToMain`; ALL twelve SEO names/ids from the research table (`seo_metaTitle` … `seo_eventLocation`, `bean.metaTitle` … `bean.eventLocation`); `#entry_bean_permalink`; buttons `formaction$='entryAdd!publish.rol'|'entryAdd!saveDraft.rol'|'entryEdit!publish.rol'|'entryEdit!saveDraft.rol'`; `#mediafile_edit_lightbox` + iframe `mediaFileEditor`; `#newsletterCard` family; `#entryRevisionsCard` family (`#entryRevisionsTable`, `.revision-diff-button[data-revision-id]`, `#revision-diff-modal`, `#revisionDiffBody`, `.revision-restore-button`); `#delete-entry-modal` + `showDeleteModal()` + `#postTitleLabel`/`#postIdLabel`/`#removeId`; `.CodeMirror`.
- **EasyMDE seam verbatim:** `rollerSetEntryText`/`rollerGetEntryText`/`insertMediaFile`/`onClickMediaFileInsert`/`onSelectMediaFile`/`rollerRenderPreview`; `${shortcodeCards}`-driven insert menu (ShortcodeCardTest reads EntryEditor.jsp as a file — path and pattern both pinned).
- **Emphasis is weight, never size.** No font-size increases anywhere new; caps-label role for box/drawer labels; tokens only in CSS (DesignTokenTest); no modal `fade`.
- **Pubtime semantics preserved:** the new `bean.pubTimeLocal` (`yyyy-MM-dd'T'HH:mm`) parses in the WEBLOG's timezone (today's behavior), via a `getPubTime(TimeZone)` overload; `copyFrom` emits it in the weblog's zone. The rail displays `${actionWeblog.timeZone}` as a mono hint. (The event fields' server-default-zone quirk is pre-existing and out of scope.)
- **PluginManagerImpl infrastructure STAYS** — `applyWeblogEntryPlugins` is a shortcode render seam (CLAUDE.md). Only the last plugin, its config, its UI, and its data columns die.
- All builds foreground (timeout 600000); never commit red; nothing under `.superpowers/`.

---

### Task 1: Server side — one pubtime field, fossils off the bean

**Files:**
- Modify: `EntryBean.java` (add `pubTimeLocal` + weblog-zone `getPubTime(TimeZone)`; DELETE `dateString`/`hours`/`minutes`/`seconds`/`rightToLeft`/`enclosureURL` fields + their copyTo/copyFrom handling; `copyFrom` emits `pubTimeLocal` from `entry.getPubTime()` in `entry.getWebsite().getTimeZoneInstance()`)
- Modify: `EntryEditController.java` (drop `hoursList`/`minutesList`/`secondsList` model attrs; `setPublishStatus`/`doSave` use the new accessor — same weblog-zone TimeZone argument; DELETE the mediacast/enclosure branch in `doSave` ~611-628 and the `att_mediacast_*` attribute handling)
- Test: `EntryEditControllerTest` — REPLACE `theEditorOffersTheFullRangeOfPublicationTimes` with pubTimeLocal round-trip tests (parse in weblog zone; a future pubTimeLocal ⇒ SCHEDULED; blank ⇒ published-now fallback; bad string ⇒ error surfaced, NOT silently null — fix the swallowed-exception behavior while in there); DELETE the enclosure tests; `EntryBeanTest` ports.

- [ ] Step 1 (TDD): new controller/bean tests red first.
- [ ] Step 2: implement; scoped green; full `mvn -pl app test` green (EntryEdit.jsp still references removed bean fields at this point — it must NOT: leave a temporary hidden-field-free page? NO — do the minimal JSP surgery in THIS task: delete the Advanced-settings rows for time/enclosure/RTL and replace with a plain `bean.pubTimeLocal` datetime-local row so jspc + ITs stay green; the full layout rebuild is Task 3).
- [ ] Step 3: `mvn verify -Pit -Dit.test='ScheduledEntryIT,AuthoringJourneyIT'` — ScheduledEntryIT MUST be ported in this task (fill `bean.pubTimeLocal` instead of `bean.dateString`; drop its `M/d/yy` formatter).
- [ ] Step 4: Commit `"One field says when: pubtime becomes datetime-local"`.

### Task 2: The last plugin dies

**Files:**
- Delete: `ConvertLineBreaksPlugin.java`
- Modify: `roller.properties` (`plugins.page` property gone), `EntryBean` (`plugins` field + handling), `EntryEditController` (`entryPlugins` model attr + `getEntryPlugins`), `EntryEdit.jsp` (plugins card), `WeblogConfigBean`/`WeblogConfigController`/`WeblogConfig.jsp` (the Plugins "formatting" section — now permanently empty), `Weblog.java`/`Weblog.orm.xml` (`defaultplugins`), `WeblogEntry.java`/`WeblogEntry.orm.xml` (`plugins`)
- Create: `bin/db/migrations/V021__drop_entry_plugins.sql` — idempotent drops of `weblogentry.plugins` and `weblog.defaultplugins`, doc noting live `"ConvertLineBreaks"` strings are being discarded deliberately (unlike V012's always-false boolean)
- Tests: `EntryEditControllerTest` plugins assertions, `WeblogWrapperTest:68,97` fixtures, `EntryEditControllerTest:129,143` fixtures, `PluginManagerImpl` tests (infrastructure stays; registration-of-zero tests adjust), MessageKeyTest ratchet (bundle keys in all 8)

- [ ] Step 1: migration + SchemaMigrationTest green; full sweep; `mvn -pl app test` green.
- [ ] Step 2: Commit `"The plugin checkboxes had one plugin left; both die"`.

### Task 3: The rebuild — writing surface + publish rail

**Files:**
- Rewrite: `EntryEdit.jsp` per the approved card. Layout: two-column grid (main + 252px rail). Main: `bean.title` as the large-but-not-larger serif title input (weight 600, size ≤ the design card's 26px — this is layout hierarchy, not emphasis; keep it modest), permalink as a small mono line with copy control (`#entry_bean_permalink` id preserved on the anchor), then the `EntryEditor.jsp` include untouched. Rail boxes: **Publish** (status pill from `bean.status` states + updateTime, `bean.pubTimeLocal` datetime-local + `${actionWeblog.timeZone}` mono hint, Publish/Save-draft submit buttons with their existing formactions, preview control, `bean.pinnedToMain` for global admins as a quiet check); **Organize** (`bean.categoryId` select, `bean.tagsAsString`, `bean.locale` when enableMultiLang); **SEO & Social drawer** (collapse holding the ENTIRE existing SEO card content — every id/name/JS verbatim, snippet-preview hex → tokens); **Comments drawer** (`bean.allowComments`, `bean.commentDays`, comment count + link to comments.rol — absorbed from the retired sidebar); newsletter + revisions cards join below the rail (ids intact); delete = quiet text link at rail bottom calling `showDeleteModal()` (modal + ids untouched).
- Copy: the approved card HTML into `docs/design/editor/` (source of record, like the journal cards).
- Modify: `RollerViewResolver.java` — `.EntryEdit`'s `sidebar` → `empty.jsp`; Delete: `EntrySidebar.jsp` (its only consumer was .EntryEdit; the recent-lists live on Entries; comment count moves to the Comments drawer). Chase `RollerViewResolverTest` if it pins the sidebar attr.
- Modify: `roller.css` — the rail/box/drawer styles (tokens only; caps-label box labels; reuse `.rail`-family conventions where sensible).
- Test: extend the JSP-reading tests if any pin structure; jspc green.

- [x] Step 1: rebuild + jspc + `mvn -pl app test` green.
- [x] Step 2: `mvn verify -Pit -Dit.test='AuthoringJourneyIT,EditorSeoIT,MarkdownPreviewIT,EntryRevisionIT,ScheduledEntryIT,BulkEntryActionsIT'` green — the contract-frozen list is exactly what these pin; expected ZERO test edits in this task (Task 1 already ported ScheduledEntryIT). Any needed test edit is a spec deviation to flag, not to quietly make.
- [x] Step 3: Commit `"The editor is a writing surface with a publish rail"`.

### Task 4: Full verification, docs, evidence

- [ ] Step 1: CLAUDE.md Entry editing section rewrite (rail layout, pubTimeLocal semantics, the retired sidebar, plugins gone, the weight-not-size rule); design-system.md editor-card reference.
- [ ] Step 2: FULL `mvn verify -Pit` green (GalleryIT font-abort = fixed; CategoryIT modal race = fixed; anything new gets the scoped-rerun protocol).
- [ ] Step 3: `bin/check-diff-coverage.sh <wave-base>`.
- [ ] Step 4: Commit `"Editor rebuild: document it"`.
