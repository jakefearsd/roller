# Fit & Finish — Pre-Release UI/Usability Pass (Design)

**Date:** 2026-08-20
**Status:** Approved for planning
**Plan:** `docs/superpowers/plans/2026-08-20-fit-and-finish.md`

## What this is

A single low-risk quality pass over every user-facing surface — admin JSPs,
editor JSPs, the four bundled themes, the shared Velocity macro library, the
message bundles — to get the system ready for release. The inventory below
comes from six parallel read-only sweeps run on 2026-08-20 (admin/core JSPs,
editor JSPs, themes/velocity, i18n bundles, usability micro-features,
accessibility/HTML hygiene), every finding verified against the code before
inclusion. Curated finding summaries are preserved per-sweep in the session
scratchpad; the plan carries everything an executor needs inline.

The headline discovery: this is not only polish. The sweeps found **a
double-digit count of outright broken controls** that no test covers — the
entry-list filter 405s on every use, the stylesheet Revert/Delete buttons
throw and then silently *save*, delete controls die on any title containing an
apostrophe, the journal theme's pagination labels are inverted, and the
frontpage theme's pinned-entries feature has never rendered on a site with
more than one page of entries. These are P1 and lead the plan.

## Goals

1. **Nothing user-reachable is broken.** Every control does what its label
   says, in both the admin UI and the bundled themes.
2. **Every message a user reads is intentional.** No raw keys, no literal
   `{0}`, no hardcoded English beside translated labels, no copy describing
   removed features, no typos on admin screens.
3. **The quiet-instrument design holds everywhere.** Weight-not-size,
   caps-labels, one primary action per screen, quiet destructive links —
   applied to the screens the design wave missed (install/error pages, six
   forms with no primary button, four sidebars with 28px headings).
4. **Mechanical accessibility floor.** Labels bound, landmarks present,
   `lang` set, headings ordered, focus visible, destructive icon controls
   named. Attribute-level only; no redesigns.
5. **A handful of small usability features** authors will feel daily:
   working entry filters, Ctrl+S, double-submit guards, copy affordances,
   status badges, purge countdown, session-expiry warning.

## Non-goals / deliberately excluded (do not creep these in)

- **POST-redirect-GET conversion across all save handlers.** Real, but it
  touches the editor save path and its ITs — medium risk, its own wave.
  (Two one-line redirect-target fixes ARE in scope: `SetupController`'s
  flash lands on a page with no messages tile, and `EntryRemoveController`'s
  orphaned landing.)
- **The deep-link-while-logged-out `menu.rol?continue` 404** — Spring
  Security saved-request work, not fit-and-finish.
- **Frontpage `frame-src` CSP widening** for `[video]` on frontpage pages.
  Audience assets (contact/subscribe, CSP-neutral) are in scope; the CSP pin
  change is a decision, not a cleanup.
- **Locale translation gaps** (212–514 missing keys per locale). Vendor
  work. In scope only: locale values that *break* (literal `{0}`, swallowed
  apostrophes) and untranslated-English values that hide gaps from tooling —
  mechanical corrections, no new translations.
- **`error-message-names-no-field` link-to-field UX** — a design, not a fix.
- **Bundle encoding normalization to raw UTF-8** — safe but churny; noted.
- **The themed reader 404 page** — deliberately plain per its own comment.
- **`messages.jsp` unescaped `${msg}`/`${error}` audit** — flagged for a
  separate targeted security look; several bundle values legitimately carry
  HTML, so this needs care, not a sweep. (One in-scope consequence: any new
  message *argument* must be server-built, never user text.)
- **`[gallery]` over an empty directory leaving literal shortcode text** in
  a published page — behavior of the render seam; needs a product call.
- **Per-weblog default category** (schema change). The localStorage
  last-used variant is in scope.

## Acceptance criteria

Each is concrete enough to test; the plan derives its tests from these.

**W1 — Broken behavior (P1):**
- Submitting the Entries filter form returns 200 and a filtered list; the
  date pickers open; paging a category-filtered list keeps the filter.
- StylesheetEdit Revert and Delete perform their named action (pinned by a
  source-scan that the form is addressed by id, plus manual verification).
- Every delete/edit control operates on an entry/page/template/category/media
  file whose title contains `'` (pinned by a source scan: no `fn:escapeXml`
  interpolation inside `onclick` attributes in the editor JSPs).
- Journal pager: the link labeled Older carries `rel="next"` (older) and
  Earlier/Newer labels match pager semantics; frontpage pinned entries render
  on page one of a >1-page site.
- A search against a nonexistent weblog returns HTTP 404.
- Velocity error templates show a reader a neutral message — no exception
  class, message, or source — verified by reading the templates in a test.

**W2 — Messages & copy:**
- A new `MessageKeyTest` arm scans Java `getText/addError/addMessage/
  addFlash*` and `getPageTitle` call sites: every key argument resolves in
  the base bundle. Zero violations.
- A new placeholder-contract test: for every key, the max `{n}` in the base
  value matches the argument count at every call site (allowlist for the
  documented client-side-substitution key). Zero violations.
- `MessageFormatRegressionTest` covers all locale bundles and both
  apostrophe directions. Zero violations.
- No `placeholder=`, `aria-label=`, visible text, or `alert()` string in a
  JSP carries hardcoded English where a bundle key exists or is added
  (extended JSP scan). Login placeholders localized.
- Grep-level: no "Perfom", "the the", "not found system", "blogroll" in the
  base bundle; both Entries pagers agree on Newer/Older.

**W3 — Feedback:**
- `#messages` carries `role="status" aria-live="polite"`; `#errors` carries
  `role="alert"` and does NOT auto-dismiss; success messages are a single
  alert (no nested alert-in-alert). Source-scan pinned.
- Template add/delete, and member-save-with-no-change, each produce exactly
  one flash message; folder-name collision renders as an error.

**W4 — A11y & markup:**
- Source-scan tests: every `<label for=>` in the JSP tree targets an id
  present in the same file; no positive `tabindex`; every `<th>` in the
  listed data tables has `scope`; the two tabbed layouts render
  `<h1 class="roller-page-title">`; every tiles layout and theme template
  sets `<html lang>`; listed modals carry `aria-modal` + `aria-labelledby`
  + `tabindex="-1"`.
- RouteSweepIT stays green (marker classes untouched or updated in the same
  commit).

**W5 — Visual & protections:**
- `p.subtitle` has a CSS rule; the six primary-less forms have exactly one
  `btn-primary`; Maintenance/set-password-link/Submissions destructive or
  consequential actions confirm; slow forms disable their submit on submit
  (with the `formaction` caveat handled); password/email/maxlength attributes
  match the schema.
- ITs that pin `#saveButton`/`#save_button`/`#login` still pass.

**W6 — Features:** Ctrl/Cmd+S saves a draft; Trash states the purge horizon;
Entries rows show a status badge; media add-success URLs have copy buttons;
`favicon.ico` exists (and the BrowserHealth exemption for it is retired).

**W7 — Themes:** theme rendering tests extended to pin `lang`, `<h1>`,
skip link, `color-scheme`, distinct titles per context, empty-state and
zero-hit copy, `noindex` on search results, escaped category/tag names, and
the corrected pager labels. Pinned CSPs byte-unchanged (no CSP edits in
scope).

**W8 — Dead code:** deleted files/attributes stay deleted (Velocity-grep
protocol run for every deleted Java/template member); `MessageKeyTest`'s
orphan ratchet uses word-boundary matching and reports zero orphans.

## Risks and how the plan bounds them

- **Velocity leniency** — every deletion of a Java member or macro is
  preceded by the CLAUDE.md grep over both template trees; the plan repeats
  the command in each affected task.
- **Pinned selectors** — `Routes.java` markers and IT selectors are checked
  per task; the two known couplings (`a[onclick*='showCategoryAddModal']`,
  the `roller-page-title` comments) are called out in their tasks.
- **Pinned CSPs** — no task changes a CSP string.
- **Escaped/raw storage split** — entry titles stored escaped, page titles
  raw, `WeblogWrapper.getName()` pre-escaped: the three affected fixes each
  restate the rule; nothing is batched mechanically across it.
- **Diff-coverage gate** — most changes are JSP/VM/properties/CSS (not
  JaCoCo-measured). Java changes are small and carry real tests. If a batch
  still dings the gate on error-path lines, the CLAUDE.md policy applies:
  accept the red run and say so; do not write assertion-free coverage.
- **One build at a time** — standard `pgrep` guard; tasks that only edit
  JSP/properties still end with a targeted `mvn -pl app test -Dtest=...`.

## Execution model

Sized for Sonnet/Opus subagents: every task is mechanical or locally
reasoned, with its findings inlined as file:line → exact change. Lanes that
touch disjoint files (themes vs admin JSPs vs bundle) can run in parallel
worktrees under the CLAUDE.md base-pinning protocol; the bundle-touching
tasks (W2, plus any task adding keys) are serialized because
`ApplicationResources.properties` is the one file nearly everything shares.
