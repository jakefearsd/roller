# Design Consistency Pass — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the whole project onto the "Quiet Instrument" spec now that
`docs/design/` is the committed source of record — admin CSS, admin JSPs, the
four public themes, the feed stylesheets — and close the two gaps that need a
design card drawn before code (Comments, Weblog Settings).

**Origin:** the two audits run 2026-08-10 against `docs/design/design-system.md`
after the card set landed (commit `f731a7af3`). Four scope decisions were taken
by Jake in the same session and are recorded as Global Constraints below; they
are settled, not open questions.

## Global Constraints

- **Decision 1 — the rail collapses onto the scale.** The editor rail's
  11 / 11.5 / 12.5 / 13px tier is removed. Labels go to **12px** (caps-label
  role), values and controls to **14.5px**. The scale stays
  12 / 14.5 / 16 / 20 / 26 / 28 and gains no support tier.
  **`docs/design/editor/editor-writing-surface.html` carries the same micro-scale
  and must be updated in the same task** — it is the approved card, and the repo
  is now the source of record, so leaving it would just move the contradiction.
- **Decision 2 — all four public themes normalize to the spec type scale.**
  `journal`, `travel`, `portfolio`, `frontpage`. Their *palettes* keep their own
  identities (see Decision 3); only type conforms. Their in-repo reference cards
  under `docs/design/journal/` and `docs/design/public/` must move with them.
- **Decision 3 — travel: fix the drift, keep the warmth.** Repoint
  `--tg-accent` (`#0f6f63` → `#0F6E68`), `--tg-muted` (`#5d6f6f` → `#5A6E72`) and
  `--tg-line` (`#dfe6e4` → `#DCE4E4`) to exact tokens. **Keep `--tg-paper`
  `#fcfbf7`** and document the warm paper as travel's deliberate identity.
  `--tg-radius: 10px` likewise stays, documented.
- **Decision 4 — full scope, cards included.** Comments and Weblog Settings get
  new design cards drawn and approved *before* their rebuilds, per the repo's own
  cards-travel-with-the-spec rule.
- **Emphasis is weight, never size** (signature move 4). "Quiet" is expressed by
  color and weight, never by shrinking below the scale — that rule is why
  Decision 1 went the way it did.
- **Never restyle by renaming a selector** — every admin route's content-tile
  marker in `it-selenium/.../support/Routes.java` must keep matching, or
  `RouteSweepIT` fails. A class rename and its `Routes` update belong in the same
  commit (CLAUDE.md, Admin UI).
- **Contract-frozen ids/names** pinned by the browser ITs must survive every JSP
  edit — see the Entry-editor list in
  `docs/superpowers/plans/2026-08-09-editor-rebuild.md`, plus
  `#collapseAdvanced` (CommentIT) and `#entries-list-marker`.
- All builds foreground; never commit red; nothing under `.superpowers/`.

---

### Task A: The scale is the scale — collapse the rail

**Files:** `roller.css` (the ten `.editor-*` rules at ~761–914),
`docs/design/editor/editor-writing-surface.html` (same values),
`docs/design/design-system.md` (state the outcome so the next screen inherits it).

Also fold in the other off-scale admin values the audit named: `.form-label`
14px → 14.5px, `.form-text` 13px, `.btn-sm` 13px, `.mediaObjectInfo` 11px,
`p.pagetip` 0.9em (the file's only relative unit), `.CodeMirror` 15px.

- [ ] Step 1: collapse the rail rules; update the card to match byte-for-byte in intent.
- [ ] Step 2: `mvn -pl app test` green (`DesignCardsTest`, `DesignTokenTest`).
- [ ] Step 3: visual check of the entry editor before/after.

### Task B: Two rules, twenty headings

`roller.css` has no global heading reset — only `.card-header`. Every bare
`<h3>`/`<h4>` outside one renders at Bootstrap's 24–28px default against a 20px
page title. This is the "stupid big" complaint, still live.

- [ ] Step 1: one rule normalising sidebar-tile headings (`.roller-column-left`
      `h3`/`h4`) to the caps-label role — fixes Entries, Comments, Media, Main
      menu, Categories, Templates, Members at once. Kill the
      `<hr size noshade>`s while there.
- [ ] Step 2: one rule for `.modal-header h3, .modal-title` — ten more headings
      across six screens.
- [ ] Step 3: the in-page strays — `UserEdit.jsp:171` `<h2>` (32px, worst single
      offender), `ThemeEdit.jsp:34/53`, `MainMenu.jsp:64`,
      `MediaFileAddSuccess`, `MediaFileEdit:165`, `UserAdmin:56`, `Login:25`.

### Task C: Empty states as invitations

- [ ] `Categories.jsp` — header row currently sits outside the `c:choose`, so an
      empty weblog gets a full table header over a `colspan="6"` strip on a
      4-column table. Move it in; add the invitation.
- [ ] `MainMenu.jsp:21–27` — the first screen a new user ever sees.
- [ ] `Templates.jsp:88`, `MediaFileImageChooser.jsp:66` (the picker opened from
      inside the editor), `MediaFileView.jsp:57` (search-empty, inconsistent with
      its own folder-empty path), `UserEdit.jsp:211`, `Members.jsp` (no empty
      branch at all).
- [ ] Add the missing `rollertable` class on `Templates.jsp:35` / `UserEdit.jsp:175`.

### Task D: The last hardcodes

- [ ] `CreateWeblog.jsp:53` `style="color:red"`; `MediaFileEdit.jsp:51–52`
      `#fff`/`#000`; `roller.css:677` `solid grey` chevron.
- [ ] `EntryEdit.jsp:223–225` — three inline `font-size` bumps on the SEO
      snippet, size-as-emphasis on the very page the rule protects. Move to
      classes; keep a comment if the intent is to mimic a SERP's proportions.
- [ ] Fold the 4× `background: var(--paper)` readonly hack into
      `.form-control[readonly]`; the 5× `margin/padding` em literals on alerts.
- [ ] Delete the three spacer-GIF layout hacks.

### Task E: Forms

- [ ] `.form-stacked` on the two delete-entry modal forms (`Entries.jsp:275`,
      `EntryEdit.jsp:599`) — the last label-column gutters on high-traffic screens.
- [ ] `MembersInvite.jsp` — never migrated; `formrow` has no CSS at all, inputs
      lack `.form-control`, two labels are malformed.

### Task F: Dark mode

- [ ] jQuery UI datepicker — the single most visible parity break: a white
      calendar over a near-black page. Token override block in `roller.css`.
- [ ] Button hover: `color-mix(… , black)` dims the accent in dark mode instead of
      lifting it. Mix toward `var(--ink)` so polarity flips per theme.
- [ ] `color-scheme: dark` on portfolio, `light` on travel — without it a reader's
      OS preference paints native inputs and scrollbars against a fixed ground.

### Task G: Themes — type to spec, travel's drift fixed

- [ ] Normalize `journal`, `travel`, `portfolio`, `frontpage` onto
      12 / 14.5 / 16 / 20 / 26 / 28 (Decision 2). Frontpage's quarter-pixel rem
      fractions are conversion artifacts and go first.
- [ ] Weights to the 450/600 pair — travel's three `700`s, portfolio's two `500`s.
- [ ] Travel palette per Decision 3; document the warm paper and 10px radius as
      identity in `design-system.md`, so the next audit does not re-flag them.
- [ ] `journal-custom.css:331` `#b3261e` and `portfolio-custom.css:403` `#e08a8a`
      → per-theme danger tokens defined in both modes.
- [ ] The in-repo cards for these themes move with them.

### Task H: Feeds, and widening the net

- [ ] `atom.xsl` / `rss.xsl` — 22 hex literals in an off-spec red/tan palette, no
      dark mode, on a public surface, in the very directory the token test polices.
- [ ] Widen `DesignTokenTest` from one hardcoded filename to a sweep of
      `roller-ui/styles/*.css` + `*.xsl`. **This is what would have caught both the
      XSL palette and travel's drift without an audit**, so it matters more than
      either individual fix.
- [ ] Sibling assertion: each theme CSS declares its palette only in a
      `:root`/dark pair, no literals in rule bodies.

### Task I: Dead weight

- [ ] `resources/themes/css.vm` — calls a macro that does not exist to load a file
      that does not exist. Delete the directory (`day.vm`, `new_page.vm` likewise
      unreferenced).
- [ ] `themes/base.css` — 22 dead selectors (blogroll, newsfeed, trackback,
      category chooser), `verdana`, `x-small`. Shrink now; full deletion needs a
      decision on the popup-comments fallback in `PageServlet:367`.
- [ ] `roller.css` dead selectors: `.approvedCommentBox`, `.date-form`,
      `.mm_table`, `.mm_table_actions`, `.mm_subtable_label`. Fix the `:623`
      comment still documenting the deleted Plugins card.
- [ ] Font Awesome 4.7 loads site-wide for an editor-only toolbar. Remap EasyMDE
      to bootstrap-icons or make the include conditional.
- [ ] Dead frontpage raster assets, two still declared in `theme.xml:140–141`.
- [ ] Delete `tiles/search.jsp` — unreferenced.
- [ ] `http://roller.apache.org` credit links → `https://`.

### Task J: Comments — card first, then rebuild

The largest structural mismatch on a high-traffic screen: each comment is a
nested `<table class="innertable">` inside a `<td>`, and `details`,
`viewdetails`, `actionrow`, `tablenav` have **no CSS rules at all**.

- [ ] Step 1: draw `docs/design/tables/comments-moderation.html` to the card
      format (both palettes, realistic content, `@dsCard` marker) — selection bar
      per `tables-list.html`, approve/delete actions, pending state.
- [ ] Step 2: approval before code.
- [ ] Step 3: rebuild `Comments.jsp` against it; `Routes` marker preserved.

### Task K: Weblog Settings — card first, then rebuild

23 field rows across 9 `.section-head` groups with a single Save buried at the
bottom. Wants a variant of the editor's shape: sticky section index + Save rail.

- [ ] Step 1: draw `docs/design/forms/settings-with-rail.html`.
- [ ] Step 2: approval before code.
- [ ] Step 3: rebuild `WeblogConfig.jsp`; `GlobalConfig.jsp` follows the same
      shape if it lands cleanly.

### Task L: Verify

- [ ] FULL `mvn verify -Pit` green.
- [ ] `bin/check-diff-coverage.sh <wave-base>`.
- [ ] Re-run both audits against the result; the top-10 lists should come back empty.
- [ ] Republish the design-system artifact so the browsable copy matches.
