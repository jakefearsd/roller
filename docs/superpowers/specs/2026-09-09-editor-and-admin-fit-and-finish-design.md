# Editor & Admin Fit-and-Finish — Design

**Date:** 2026-09-09
**Status:** Approved in conversation; awaiting written-spec review
**Plan:** `docs/superpowers/plans/2026-09-09-editor-and-admin-fit-and-finish.md` (to be written)

## What this is

A wave focused on how the publishing and admin experience *feels*: one
package rebuilds the Markdown editor on CodeMirror 6 with a theme-true live
preview, and one package sweeps the admin screens for consistency, control
quality and the small bugs a walkthrough turned up. Entries stay Markdown;
there is no WYSIWYG surface and none is planned.

The inventory comes from two sources on 2026-09-09: a code survey of every
editor/admin JSP, `roller.css`, `head.jsp`, `pom.xml` and the browser-test
support layer, and a signed-in walkthrough of every admin screen on the dev
server. Findings from the walkthrough are marked **(seen)** below; each was
then verified in the source before inclusion.

## Decisions locked in conversation

1. **Editor base: CodeMirror 6**, replacing EasyMDE (CodeMirror 5). The core
   CM6 packages are not published as npm webjars (`@codemirror/state`, `view`,
   `commands`, `search` and every `@lezer/*` package are absent from Maven
   Central), so an import-map approach is not available.
2. **Bundle build: `frontend-maven-plugin` + esbuild** at `generate-resources`.
   Node is pinned and downloaded by Maven; `package.json` and
   `package-lock.json` are committed; `node_modules` and the bundle are not.
   CI needs no new step.
3. **Preview is theme-true**: the live preview renders inside the weblog's own
   theme stylesheet and asset macros, not in a neutral admin style.
4. **One spec, two packages, run in parallel worktrees** under the CLAUDE.md
   base-pinning protocol, with the file-ownership split in "Parallelisation"
   below.

## Goals

1. **Editing Markdown is the best part of the product.** Fast, quiet,
   keyboard-first, with a preview that is the published page, images that
   drop straight in, and help that knows about this system's shortcodes.
2. **The same thing looks and behaves the same everywhere.** One status
   vocabulary, three button buckets, one selection bar, one modal shape, one
   date format, one confirm idiom.
3. **Controls are excellent.** Native date inputs, marked invalid fields with
   focus, a weblog switcher, selection bars that appear when needed.
4. **Nothing user-reachable is broken.** The walkthrough's defects are fixed
   with failing-first tests.

## Non-goals (do not creep these in)

- WYSIWYG or any rich-text surface.
- Server-side autosave (see CLAUDE.md, Entry editing: local-only by design).
- The inline-`onclick` sweep (59 sites, 18 JSPs) and an admin CSP — hygiene,
  not feel; deferred and named so it is not re-found.
- Tag-input chips with autocomplete — deferred.
- Locale translation gaps — vendor work; new keys land in every bundle, no
  new translations beyond that.
- A per-weblog upload-directory scheme for pasted images — they land in the
  weblog's default media directory.
- Any change to how entries are stored or rendered.

---

## Package A — The editor

### A1. Build wiring

- `app/frontend/` holds `package.json`, `package-lock.json`, `src/editor.js`,
  `src/editor.css` (if any CSS is not expressible as an `EditorView.theme`)
  and `build.mjs` (esbuild, IIFE, minified, one output).
- `frontend-maven-plugin` in the parent `pluginManagement`, executions in
  `app/pom.xml`: `install-node-and-npm` (Node 22 LTS, pinned), `npm ci`,
  `npm run build`, bound to `generate-resources`. Output:
  `${project.build.outputDirectory}/static/roller-ui/scripts/roller-editor.js`,
  served by Spring Boot's static-resource merge alongside the Plex webjars.
- `.gitignore`: `app/frontend/node_modules/`, `app/frontend/node/`.
- `EditorBundlePomTest` (fast suite) pins: the plugin is declared with a pinned
  Node version, the three executions exist in that order, the output path
  matches what `head.jsp` references, and `easymde`/`font-awesome` no longer
  appear in `pom.xml` or `head.jsp`. `WebjarReferenceTest`'s CSS scan is
  unchanged.
- Measured cost (cold and warm `mvn -pl app verify`) is recorded in CLAUDE.md
  next to the existing 30-second budget note. Expected: 20–40s cold, a few
  seconds warm (npm ci is skipped when the lockfile hash is unchanged).

### A2. Editor core (`roller-editor.js`)

One global, `window.RollerEditor`, with `create({ textarea, placeholder,
shortcodes, onChange, uploadUrl, csrf })` returning
`{ getValue, setValue, insert, focus, on, destroy }`. Extensions:

- `@codemirror/lang-markdown` with GFM (tables, strikethrough, task lists),
  `@codemirror/language` highlighting, `history`, `defaultKeymap`,
  `historyKeymap`, `markdownKeymap`, `indentWithTab`, `bracketMatching`,
  `closeBrackets` (asterisks/backticks/brackets), `EditorView.lineWrapping`,
  `placeholder`, `search` (Ctrl+F stays inside the editor).
- **Highlighting on the type rule**: headings and strong are weight 600 at
  the same size; emphasis italic; inline/block code in `--font-data`; links
  quietly underlined in `--accent`; blockquote in `--ink-soft`. No font-size
  changes anywhere inside the editor (design-system.md, signature move 4).
- **Shortcode decoration**: a `ViewPlugin` that marks `[name …]`, `[/name]`
  and `[[…]]` spans (names from the `shortcodes` list the JSP passes, i.e.
  the registry) with an accent-coloured mark so they read as controls.
- **Shortcode completion**: typing `[` offers the registered shortcode
  snippets via `@codemirror/autocomplete`, sourced from the same list the
  Insert menu is generated from.
- **Theme from tokens**: `EditorView.theme` references `var(--ink)`,
  `var(--surface)`, `var(--accent-quiet)` (selection), `var(--line)`,
  `var(--focus)` (focus ring), `var(--font-ui)`, so dark mode is inherited.
  No hex literals (`DesignTokenTest` extends its audit to
  `app/frontend/src/`).
- Ctrl/Cmd+S and Ctrl/Cmd+Enter continue to save-draft / publish via the
  existing page-level handler; the editor calls `preventDefault` and lets the
  document listener act, replacing today's two-binding dance.

### A3. Toolbar and modes

Hand-built in the JSP-adjacent script (not inside the bundle), Bootstrap
Icons only: bold, italic, heading, quote, bulleted list, numbered list, link,
inline code, table, image (opens the existing media chooser), and the
Insert-shortcode dropdown moved into the same row. Right-aligned: a
Write / Split / Preview segmented control (`role="radiogroup"`) whose choice
persists in `localStorage` per install, and a help button opening the guide
(A6). Every button has `title` naming its shortcut; the row never wraps —
overflow collapses into a "more" menu below 720px.

**(seen)** Today's EasyMDE toolbar renders in three rows with the table button
orphaned on the far right, and side-by-side forces fullscreen with the second
toolbar row painted over the first lines of text. Both go away with EasyMDE.

### A4. Theme-true preview

- **Shell**: `GET /roller-ui/authoring/entryEdit!previewShell.rol?weblog=…`
  returns a minimal HTML document rendered by Velocity: `<link rel=stylesheet
  href="$model.weblog.stylesheet">`, `#showGalleryGridStyles`,
  `#showGalleryAssets`, `#showEmbedAssets`, `#showMapAssets`, and an
  `<article>` in the theme's prose container. Template resolution mirrors
  `_page`: a theme may ship `_preview` (`themes/<id>/preview.vm`); otherwise
  the shared `WEB-INF/velocity/templates/preview.vm` is used. `journal`,
  `travel` and `portfolio` each ship one (the article wrapper and the
  theme's own class names, ~15 lines each). The shell carries the theme's
  CSP as pinned by the rendering tests, plus `frame-ancestors 'self'`.
- **Fragment**: the existing `POST entryEdit!preview.rol` is unchanged.
- **Wiring**: the Split/Preview pane is a sandboxed iframe
  (`sandbox="allow-scripts allow-same-origin"`) loading the shell once. Editor
  changes debounce at ~400ms of idle, POST for the fragment, and
  `postMessage` it to the iframe; the shell's script swaps `article.innerHTML`
  and re-runs the asset initialisers (gallery/PhotoSwipe, Leaflet, embeds) for
  the new nodes. The asset macros expose an idempotent `init(root)` for this;
  today they initialise once on `DOMContentLoaded`.
- **Scroll sync**: proportional, editor → preview, one direction. Good
  enough; a block-mapping sync is not in scope.
- **Full preview** (the whole page in a new window) stays as the quiet link
  in the Publish box.

### A5. Paste and drop image upload

- New session-authenticated endpoint `POST
  /roller-ui/authoring/mediaFileAdd!upload.rol` (multipart; CSRF token as a
  form field; `weblog` handle; optional `directoryId`). Returns JSON
  `{ results: [{ name, status, message, id, url }] }` with 201 / 207 exactly
  like `MediaApi.upload`. The per-file logic (`processUpload`, the
  content-type-length and name-length refusals, the quota/forbidden-extension
  refusals reported through `RollerMessages`) is extracted to a shared helper
  in `ui.controllers` (working name `MediaUploads`) that both `MediaApi` and
  the new endpoint call — the `EntryFieldRules` precedent. `directoryId` goes
  through `WeblogOwnership`; a foreign id is 404, never 403.
- Editor side: dropping or pasting image files inserts a placeholder line
  `[uploading name…]` at the cursor, uploads, and replaces it with
  `[image id="<id>"]` on success or removes it and shows the refusal in the
  status line (A6) on failure. Non-image files are refused client-side with
  the same status-line message. Multiple files upload sequentially, each with
  its own placeholder.

### A6. Guide, shortcuts and status line

- The external markdownguide.org link is replaced by an in-app writing guide
  (Bootstrap offcanvas, right side, `Ctrl+/` or the toolbar help button):
  a Markdown cheatsheet, every registered shortcode's card (label, snippet,
  one-line description — from `shortcodeCards`, so a new shortcode is
  documented automatically), and the keyboard shortcuts. All strings are
  bundle keys.
- A 12px caps-label status line under the editor: word count, reading time
  (words / 230, rounded up, "1 min read"), and save state — "Unsaved
  changes" from the dirty flag, "Draft saved locally" from `roller-draft.js`'s
  snapshot events, "Saved" after a successful submit-and-reload. Upload
  refusals from A5 show here for ~8s.

### A7. Page editor parity

`PageEdit.jsp` is rebuilt on the entry editor's grid (`editor-grid` /
`editor-main` / `editor-rail`): title (26px serif, the one oversized element)
and slug (mono line with the reserved-slug help) in the main column; the
editor; a Publish box (status select, Save, Full preview); a Navigation box
(show in nav, order); the SEO drawer unchanged; a quiet delete link. It gets
the session-expiry banner the entry editor already has. Both editors call
`RollerEditor.create` and keep the three seam functions (`insertMediaFile`,
`rollerSetEntryText`, `rollerGetEntryText`) plus draft recovery's
`onEditorChange`.

### A8. Entry editor fixes found in the walkthrough

- **(seen)** The Newsletter box renders "…in Weblog Settings first.Settings":
  the `newsletter.noList` message and the Settings link are concatenated with
  no separator. The link becomes its own sentence.
- **(seen)** The Newsletter card uses `.card/.card-header` while the rail's
  other boxes use `.editor-box/.rail-group-label`; the Delete link sits
  between them at a different indent. Newsletter and Revisions take the
  `.editor-box` shape and Delete moves to the end of the rail, as the card
  shows.
- The "Summary (optional)" accordion becomes a quiet drawer under the editor
  in the same `.editor-drawer` style as the SEO drawer.

### A9. Tests and docs for Package A

- 25 IT classes drive the editor through `.CodeMirror`. A `Editor` support
  helper in `it-selenium/.../support/` owns the selectors (`.cm-editor`,
  `.cm-content`) and the type/clear/read operations; the classes call it
  instead of naming selectors. This is the wave's largest mechanical change
  and is its own task.
- `MarkdownPreviewIT`: the Split pane's iframe carries the theme stylesheet
  and the fragment renders with the theme's article class; a pasted PNG
  becomes `[image id="…"]` and the file exists in the media library.
- Rendering tests: `PreviewShellRenderingTest` for the shared shell and one
  case per bundled theme's `_preview`, pinning the CSP string byte-for-byte
  like the other theme heads.
- Unit: the shared upload helper (both callers, one implementation — a
  source test pins that `MediaApi` no longer carries its own copy); the new
  endpoint's ownership check, 201/207 shape, and per-file refusal without
  throwing; `PageEditController` on the rebuilt form.
- Design card `docs/design/editor/editor-markdown-surface.html` (toolbar,
  segmented mode control, split preview, status line, guide offcanvas) drawn
  **before** the JSP work and listed in `design-system.md` so
  `DesignCardsTest` passes.
- CLAUDE.md: the Entry editing "Editor" bullet rewritten for CM6; the EasyMDE
  `ReferenceError` flake note deleted with EasyMDE; the frontend build and its
  measured cost added to Build and Development Commands.

---

## Package B — Admin fit-and-finish sweep

### B1. One status vocabulary

Entries, Pages, Trash and the editor rail each pick their own Bootstrap badge
class: Scheduled is `bg-primary` on Entries and `bg-info` in the rail; Draft
is `bg-info` everywhere while the pills card says warn tint; Entries also
renders a centred legend of coloured boxes and tints whole rows
(`tr.draftentry` etc.). One `.status-pill.status-{published,draft,pending,
scheduled,trashed}` component matching `components-pills.html` replaces all of
it; Scheduled carries its mono date; the legend and row tints are deleted.
`badge bg-*` remains only for the MainMenu entry count.

### B2. Three button buckets

`btn-success` (7 sites) → `btn-primary`; bare `btn` (Save draft, media
Toggle, Delete folder, Members' second Save) → `btn-secondary` or removed;
the media page's five `<input type="button" style="display:inline">` → real
`<button>` elements. One primary per screen. **(seen)** Pages shows two
primaries when empty (top "New page" plus the invitation's): the top button
is hidden in the empty state. **(seen)** Members has two Save buttons: the
grant form and the permissions table are one form with one Save. The IT that
pins `btn-success` moves to `btn-primary` in the same commit.

### B3. One selection bar

Entries, Media, Submissions and Trash each grew their own bulk controls;
**(seen)** Entries' bar is always visible with "Submit selected for review"
as a primary even with nothing checked. One `.selection-bar` component
(count, then actions, destructive last and quiet) above each table, hidden
until a row is checked (`roller.js` delegated handler keyed on
`data-selection-bar`).

### B4. Native dates; jQuery UI leaves

The Entries date filters are read-only `MM/DD/YY` jQuery UI pickers, the
library's only consumer. They become `<input type="date">`;
`EntriesBean` parses ISO (`yyyy-MM-dd`) and the chips/pager carry the value
through unchanged. `jquery-ui` script and stylesheet leave `head.jsp` and
`pom.xml`. (`jquery-validation` stays; it has other consumers.)

### B5. Entries list feel

- **(seen)** The title links to the public permalink; editing is the small
  pencil. The title becomes the edit link (600 weight, per the tables card)
  with the mono slug under it; a quiet "View" link appears for published
  entries.
- **(seen)** Three status-filter surfaces coexist (chips in the main column,
  radios in the sidebar, the legend) plus sort radios. Chips stay as the one
  status control; the sidebar keeps category, tags, text and dates; sort
  becomes a select. The chips carry the sidebar's other filters (the prior
  wave's open item).
- Dates and counts in `--font-data` with tabular numerals; one date-format
  tag (`<roller:date>`) replaces the `fmt:formatDate` variants across the
  admin JSPs.

### B6. Sidebars on the rail's grammar

The four sidebar cards (Entries filter, Media actions/search, Categories,
Templates) render an `<h3>` with an `<hr>` in a Bootstrap card. They take the
caps-label header and `.form-stacked`; **(seen)** the media search's
"Size > 0 Bytes" triple control becomes one "Larger than" field with a unit
select; "Add new folder" becomes a single row with the input and a
secondary button.

### B7. Modals in one shape, one confirm idiom

Ten files carry modals with `h3`/`h4` Bootstrap-sized titles and footers in
varying order. `.roller-modal`: caps-label title, primary right, quiet cancel,
red only on a destructive confirm. The four remaining `window.confirm()`
functions (Templates, MediaFileEdit, Members, Trash) move to `data-confirm`.

### B8. Errors point at fields

Nothing in the JSP tree uses `.is-invalid`. `BaseController.addError` gains an
overload naming the field; `messages.jsp` renders the same banner; the form
marks the named field `.is-invalid` with `aria-invalid` and focuses the first.
Applied to every controller that validates a form field (WeblogConfig,
CreateWeblog, Categories, Pages, Templates, Members, UserEdit, Profile, the
editor's pubtime).

### B9. Layout consistency

- **(seen)** Submissions (Inquiries) renders on `.tiles-simplepage` with no
  rail although it is a rail item; `.Submissions` moves to
  `.tiles-tabbedpage`. Profile moves to `.tiles-mainmenupage` so the rail
  persists from the main menu.
- **(seen)** GlobalConfig is a long unstructured form, checkboxes below their
  labels, Save at the bottom. It takes the `settings-grid` + `settings-rail`
  treatment WeblogConfig already has (section index, always-reachable Save)
  and `form-check` label-beside checkboxes.
- **(seen)** MediaFileAdd puts three full-width textareas above the drop
  zone. The drop zone comes first; description/copyright/tags collapse into a
  "Details" drawer.
- **(seen)** ThemeEdit shows a single "Shared theme" radio in a tinted box
  when custom themes are off, a bare `<select>`, and a broken thumbnail. The
  type chooser is hidden when there is one option; themes render as cards
  (thumbnail, name, description, Select). The thumbnail bug is traced:
  `ThemeDataServlet` hand-builds its JSON with `pw.print` and no escaping,
  and journal's `theme.xml` description spans lines, so the response carries
  a raw newline inside a string, jQuery's parse fails, and the `success`
  callback that sets the `src` and description never runs. It moves to a
  real JSON writer, with a test on a description containing a newline and a
  quote.
- **(seen)** MainMenu's weblog cards: full-width four-column button group and
  the raw URL as the link. Rows instead: name (600), handle in mono, role,
  entry count, and actions as quiet links.

### B10. Weblog switcher

The shell card specifies a switcher in the top bar. A quiet dropdown of the
user's weblogs, rendered by `bannerStatus.jsp` from a `userWeblogs` model
attribute set by `BaseController` when the user has more than one; choosing
one lands on the same screen for that weblog when it exists there, else
Entries.

### B11. Small bugs, verified in source

- Entries chips drop the text/tag/date filters (B5 covers).
- `MediaFileAddController.cancel` concatenates an unencoded `directoryId`.
- Two unescaped message arguments: `TemplatesController`
  (`pagesForm.error.alreadyExists`) and `MediaFileViewController`
  (`directoryCreate.success`).
- `weblog.vm` search form `selected=` compares `$cat.name` to `$model.term`.
- `TemplateEdit.jsp` `#accordion` div imbalance.
- Heading-level skips on the admin screens, with `Routes.java` markers
  updated in the same commit.

### B12. Tests and docs for Package B

- Source scans (fast suite, `JspConsistencyTest`): one `.status-pill`
  component and zero `badge bg-*` in editor JSPs; zero `btn-success`; zero
  `window.confirm(`; zero `jquery-ui` references; every `.selection-bar` has
  a `data-selection-bar` target; no `<h3>`/`<hr>` sidebar headers.
- Controller unit tests for B8 (field named, first field focused is a JSP
  concern pinned by a source scan), B4 ISO parsing, B11 each bug failing
  first, the `themedata` thumbnail path.
- ITs: `RouteSweepIT` markers updated; `MultiUserJourneyIT` covers the
  switcher; `BulkEntryActionsIT`/`MediaBulkUploadIT`/`TrashIT` on the
  selection bar; `GlobalConfigMatrixIT` on the rebuilt Global Config.
- New strings in all seven bundles; the three i18n ratchets stay at
  `Set.of()`.
- CLAUDE.md Admin UI section: status pills, selection bar, `data-confirm`
  as the sole idiom, the date tag, the switcher.

---

## Acceptance criteria

Each is concrete enough to write a test against; the plan derives its tests
from these.

**Package A**
- The built WAR contains `static/roller-ui/scripts/roller-editor.js` and no
  path containing `easymde` or `font-awesome` (pom test + a WAR-content scan).
- `mvn -pl app verify` on a machine with no Node installed succeeds (the
  plugin provides Node); the measured cold cost is recorded.
- `RollerEditor.create` exists on both editor pages; the three seam functions
  behave as before (`EntryAutosaveIT`, `ShortcodeCardIT`, `PageIT` green).
- In Split mode the iframe document contains the weblog's stylesheet `<link>`
  and, for a journal weblog, an article carrying the journal prose class;
  typing `## Heading` yields an `<h2>` in the iframe within 2s.
- `preview.vm` and each bundled theme's `_preview` render the pinned CSP.
- Pasting a PNG into the editor produces `[image id="<id>"]` and the media
  file exists under the weblog's default directory; a foreign `directoryId`
  returns 404; a quota refusal returns 207 with the file's status `error`
  and nothing thrown.
- The guide lists every registered shortcode by name (test compares against
  `ShortcodeExpander.DEFAULT`).
- The status line shows the word count of the current text.
- `newsletter.noList` renders with the link as a separate sentence.
- No hex literal in `app/frontend/src/`.

**Package B**
- Source scans in B12 report zero violations.
- Entries: the title cell's first link targets `entryEdit.rol`; the chips
  carry `bean.text`/`bean.tagsAsString`/date params when set; the selection
  bar is hidden with nothing checked and shows "N selected" after a check.
- Date filter inputs are `type="date"` and a `2026-09-01` value filters
  correctly (controller test).
- Submissions renders inside `#adminRail`'s layout (`RouteSweepIT` marker
  plus the rail smoke assertion).
- GlobalConfig renders a section index and a rail Save (`RouteSweepIT`).
- `themedata` returns parseable JSON for a description containing a newline
  and a quote (unit); ThemeEdit's thumbnail `<img>` has a `src` that returns
  200 (IT).
- Members renders exactly one submit button.
- Pages, when empty, renders exactly one `btn-primary`.
- A WeblogConfig save with an invalid list UUID marks
  `#weblog_bean_newsletterListUuid.is-invalid` and focuses it (IT).
- The switcher is absent for a one-weblog user and present for a two-weblog
  user, and selecting a weblog on Entries lands on that weblog's Entries.
- Each B11 bug has a unit test that failed before the fix.

## Gates and build

- PMD, SpotBugs, CPD stay at zero; the diff-coverage gate applies normally
  (new logic, not a mechanical sweep — no "expected red run").
- The three i18n ratchets stay at `Set.of()`.
- Coverage floors untouched unless measured slack appears after the wave.
- Both context paths get a browser run before shipping (`mvn verify -Pit`
  and `-Dit.context.path=roller`): the preview shell, the upload endpoint
  and the switcher all build URLs.

## Parallelisation and file ownership

Two worktrees from one pinned base, verified with the `comm` / `merge-tree`
check in CLAUDE.md before dispatch and before merge.

- **A owns:** `EntryEdit.jsp`, `EntryEditor.jsp`, `PageEdit.jsp`,
  `app/frontend/**`, `preview.vm` and the three theme `_preview` templates,
  the asset macros' `init(root)` change in `weblog.vm`, `EntryEditController`
  (shell endpoint), `MediaFileAddController` (upload endpoint) and
  `MediaApi` (helper extraction), a new `roller-editor.css`, the EasyMDE and
  Font Awesome lines of `head.jsp` and `pom.xml`, the IT editor support
  helper and the 25 IT classes' selector edits, the new design card.
- **B owns:** every other JSP, `roller.css`, `roller.js`, `RollerViewResolver`,
  `bannerStatus.jsp`, `BaseController` (field errors, `userWeblogs`),
  `EntriesBean`/`EntriesController`, the B11 controllers, `Routes.java`, and
  the jQuery UI lines of `head.jsp` and `pom.xml`.
- Both touch `ApplicationResources.properties` and the six locale bundles
  (append-only, distinct keys) and CLAUDE.md (distinct sections); merge order
  is A then B, and B rebases its bundle hunks.
- Builds are serialised per the CLAUDE.md rule; reviews overlap freely.

## Risks and how the plan bounds them

- **Node in the build.** The plugin downloads Node from nodejs.org; an
  offline build fails at `install-node-and-npm`. The downloaded runtime is
  kept in `app/frontend/node/` (git-ignored) so only the first build needs
  the network; CI's runner has it. Accepted and documented in CLAUDE.md.
- **IT selector migration touches 25 classes.** Done as one task on the A
  worktree with the full `-Pit` run as its gate, before any other A task
  merges.
- **Preview shell and CSP.** The shell carries a theme CSP; the fragment
  is injected by script, which `script-src 'self'` allows since the script
  is the shell's own. Pinned byte-for-byte in rendering tests.
- **Asset re-initialisation.** The macros today initialise once on load;
  exposing `init(root)` is a change to shipped theme JavaScript and is
  covered by `GalleryIT`/`MapIT` on the public page as well as the preview.
- **Velocity leniency.** No Java member reachable from a template is deleted
  in this wave; if one is, the CLAUDE.md grep protocol applies.
- **Pinned selectors.** `Routes.java` markers are updated in the same commit
  as any class rename (B1, B3, B6, B9).

## Execution model

Sized for subagent-driven development: each task is locally reasoned with
its findings inlined as file:line → change. Package A is roughly nine tasks
(build wiring; core + theme; toolbar + modes; preview shell + wiring; upload
endpoint + helper; paste/drop; guide + status line; PageEdit rebuild; IT
migration + docs). Package B is roughly eleven (one per B-section, B11 split
by controller). Bundle-touching tasks append distinct keys and merge A before
B.
