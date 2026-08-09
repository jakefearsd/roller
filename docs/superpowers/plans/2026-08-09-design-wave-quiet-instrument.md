# Design Wave — "Quiet Instrument" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Roll the approved "quiet instrument" design system (claude.ai/design → Roller Design System, 12 cards, user-approved 2026-08-09) onto the admin UI and close the two public-theme gaps (`_page` templates for travel and portfolio, portfolio text-card polish).

**Architecture:** The admin skin is one hand-written CSS file (`roller-ui/styles/roller.css`, 322 lines) plus seven hand-rolled layout JSPs (`WEB-INF/jsps/tiles/`) and one nav fragment (`bannerStatus.jsp`) — a very small surface. We add a token stylesheet (light+dark via `prefers-color-scheme`), rewrite `roller.css` against it, restructure the two-column layouts around a context rail (replacing the "Powered by Apache Roller" card), and restyle — never rename — the Bootstrap-classed controls the browser ITs pin. Public side: a shared theme ships a page template by declaring `<template action="custom"><name>_page</name>…` in `theme.xml` beside a `.vm` file — no code changes; form styling lands in each theme's already-registered stylesheet under its `body.tg`/`body.pf` scope (which beats the audience macro's unscoped rules by specificity).

**Tech Stack:** JSP/JSTL layouts, one CSS file + new token file, IBM Plex webjars (self-hosted), Velocity theme templates, JUnit + Selenide.

**Reference:** the token values and component specs in `docs/design/design-system.md` (committed by Task 1 from `.superpowers/design/SPEC.md`). The rendered cards live in the claude.ai/design project "Roller Design System".

## Global Constraints

- **Restyle, never rename.** These selectors are pinned by the browser ITs and MUST survive verbatim: ids (`#loginForm #j_username #j_password #login #messages #errors #saveButton #save_button #pageRemoveForm #pageEditForm #entry #category-table #entriesBulkActions #bulkDeleteButton #bulk-delete-modal #bulkDeleteConfirm #delete-entry-modal #entryRevisionsCard #entryRevisionsTable #revision-diff-modal #revisionDiffBody #shortcodeInsertButton #shortcodeInsertMenu #mediafile_edit_lightbox #cropSectionTitle #cropCanvas #cropButton #themeSelector #sharedRadio #customRadio #user-submit #fileControl0 #uploadButton #directoryShareCard #directoryPrivateToggle #createShareLinkButton #createEntryShareLinkButton #shareLinkUrl #entryShareLinkUrl #sharePasswordForm #templateAdd #submissionsDeleteForm` and the `#seo_*` family), classes on controls (`.btn`, `.btn-primary`, `.btn-success` on submit buttons, `.CodeMirror`, `table.rollertable`, `.jgrid`, `p.subtitle`, `.submission-select`, `.shortcode-card`, `.revision-diff-button`, `.revision-restore-button`, `li.page-nav-item`), structural markers (`h1.roller-page-title`, `h2.roller-page-title`, `h3.setup-section-title`, `h3.mm_weblog_name`, `a[href$='/roller-ui/logout.rol']`), and every `form[action$=…]`/`button[formaction$=…]` action attribute. RouteSweepIT + the feature ITs enforce all of this.
- **Bootstrap 5.3.8 stays.** Its grid/modals/dropdowns are load-bearing (132 `btn`, 368 `col-*`, modals on six screens). We restyle over it with tokens; we do not remove the webjar or its classes.
- **ZERO theme-CSP changes.** The pinned strings in `AnalyticsInjectionRenderingTest` (CSP_STANDARD/CSP_GAURAV byte-for-byte), `TravelThemeRenderingTest`, `PortfolioThemeRenderingTest` must pass untouched.
- **Pinned theme markup survives:** travel `tg-cards/tg-card/tg-hero/tg-entry-title/tg-main tg-main-entry` assertions; portfolio `pf-grid`, the literal `--ar:1.34` card prefix, `pf-card pf-card-text` + `span.pf-card-title` (no `<picture>`) for imageless entries, `pf-hero`, `pf-entry-title`, `pf-comments`, and the stylesheet-through-Velocity assertions (`body.tg .travel-map`/`.faq`/`.cta-card`; `.pf-grid {`, `flex-basis: calc(var(--ar) * var(--pf-row-h));`, `#searchAgain input.text`; travel css contains NO `@font-face` and NO `http://`).
- **`SubscribeFormRenderingTest` / `PageNavRenderingTest` strings survive**: `subscribe-form-slot`, `data-list-uuid`, `data-endpoint`, `audience-hp`, nav links inside an open `<ul>`, no leaked directive text. Any new `.vm` must pass the `$utils.`-leak style checks.
- **Webjar discipline:** new webjars follow the font-awesome precedent — pom dependency with a why-comment near `pom.xml:283` ("note: update head.jsp on webjar version change"), `head.jsp` link, `WebjarReferenceTest` verifies resolution automatically. Verify an artifact EXISTS on Maven Central before wiring it (`mvn -q dependency:get -Dartifact=…`); never guess coordinates into the pom.
- **Fonts are self-hosted; theme stylesheets keep zero `@font-face` and zero `http://`** (pinned). Plex is an ADMIN-ONLY face this wave: it is loaded via `head.jsp` (admin layouts), never via theme CSS.
- **Light + dark for the admin** via tokens on `:root` + `@media (prefers-color-scheme: dark)` — no toggle UI this wave. Every color in the new CSS comes from the token set in the spec; no stray hex.
- **Copy rules:** empty states use the approved invitation pattern (one 600-weight line, one soft sentence, at most one action). All new message keys referenced (`MessageKeyTest` ratchet; bump `KNOWN_DYNAMIC_KEY_COUNT` only per its documented convention).
- **Coverage gates:** diff coverage vs `0d33adaa7` (~90%) — note most of this wave is JSP/CSS which JaCoCo does not count; the Java that does change (none expected beyond tests) still meets it. Full `mvn -pl app test` green per task; ONE full `mvn verify -Pit` in the final IT task (foreground discipline: timeout 600000, no shell timeouts, no detached `&`, wait for harness notifications).
- **Commit on `master`.** Never commit red. No pushing.

## File Structure

| File | Responsibility |
| --- | --- |
| `docs/design/design-system.md` *(new)* | The committed spec (tokens + component rules) — copied from `.superpowers/design/SPEC.md` |
| `app/src/main/webapp/roller-ui/styles/roller-tokens.css` *(new)* | Token custom properties, light + dark |
| `app/src/main/webapp/roller-ui/styles/roller.css` | Rewritten against tokens: components, rail, tables, forms, empty states, EasyMDE skin |
| `app/pom.xml` + `WEB-INF/jsps/tiles/head.jsp` | IBM Plex webjars + token stylesheet link (before roller.css) |
| `WEB-INF/jsps/tiles/tiles-tabbedpage.jsp`, `tiles-mainmenupage.jsp` | The rail (branding card removed, menu groups moved in), content column |
| `WEB-INF/jsps/tiles/bannerStatus.jsp` | Slimmed top bar (brand, front page, main menu, user, sign out) |
| `WEB-INF/jsps/tiles/userStatus.jsp`, `footer.jsp` | Rail context block; quiet footer |
| ~8 list/form JSPs | Empty-state blocks; form-section markup where CSS alone cannot (labels above fields) |
| `themes/travel/theme.xml` + `themes/travel/page.vm` *(new)* + `travel-custom.css` | Travel `_page` + `body.tg` form styling |
| `themes/portfolio/theme.xml` + `themes/portfolio/page.vm` *(new)* + `portfolio-custom.css` | Portfolio `_page` + text-card polish |
| `app/src/test/java/.../ui/rendering/servlets/{Travel,Portfolio}ThemeRenderingTest.java` | New `_page` assertions (mirroring their own leak-check pattern) |
| `it-selenium/` | Any selector fallout (expected: none) + a rail smoke assertion |

---

# Task 1: Foundations — spec committed, Plex webjars, token stylesheet

**Files:**
- Create: `docs/design/design-system.md` (copy `.superpowers/design/SPEC.md` verbatim, retitled as the committed reference; add a line naming the claude.ai/design project as the visual source of truth)
- Create: `app/src/main/webapp/roller-ui/styles/roller-tokens.css`
- Modify: `app/pom.xml` (Plex webjars), `app/src/main/webapp/WEB-INF/jsps/tiles/head.jsp`
- Test: `WebjarReferenceTest` (auto), `app/src/test/java/org/apache/roller/weblogger/ui/DesignTokenTest.java` *(new)*

**Interfaces:**
- Produces: CSS custom properties consumed by every later task — exactly the spec's names: `--paper --surface --ink --ink-soft --line --accent --accent-quiet --good --warn --bad --focus`, plus `--radius: 6px`, `--font-ui`, `--font-data`.

- [ ] **Step 1: Verify Plex webjar coordinates.** Run `mvn -q dependency:get -Dartifact=org.webjars.npm:ibm-plex-sans:RELEASE` and plausible alternates (`org.webjars.npm:ibm__plex-sans`, `org.webjars.npm:ibm-plex`, and the mono siblings) until the real artifacts are found (scoped npm packages usually become `ibm__plex-sans` — verify, do not assume). If NO webjar exists for Plex: fall back to vendoring the woff2 files (Latin subsets, 450/600 for Sans; 400 for Mono) under `roller-ui/styles/fonts/` with an `@font-face` block at the top of `roller-tokens.css` and the OFL/Apache license text beside them — document the choice either way in the pom or a fonts/README.
- [ ] **Step 2: Write `roller-tokens.css`** — the spec's two palettes: full light set on `:root`, dark overrides under `@media (prefers-color-scheme: dark)`; `--font-ui: "IBM Plex Sans", system-ui, sans-serif; --font-data: "IBM Plex Mono", ui-monospace, monospace;` plus the `@font-face` blocks (webjar CSS import or vendored files per Step 1). Base element layer: `body { background: var(--paper); color: var(--ink); font-family: var(--font-ui); }` and `:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }`.
- [ ] **Step 3: Wire `head.jsp`** — the tokens link goes AFTER the Bootstrap link and BEFORE `roller.css` (cascade: bootstrap → tokens → roller.css overrides).
- [ ] **Step 4: `DesignTokenTest`** — reads `roller-tokens.css` from the webapp source and asserts: every hex literal in the file is one of the spec's 21 values; both `prefers-color-scheme` sets define the SAME property names (no token defined in only one theme — the classic unreadable-page bug); `head.jsp` references the file. Model file-reading conventions on `MessageKeyTest`'s webapp scanning.
- [ ] **Step 5:** `mvn -pl app test -Dtest='WebjarReferenceTest,DesignTokenTest'` → PASS. Commit: `"Design foundations: committed spec, Plex, token stylesheet"`.

---

# Task 2: The shell — rail, top bar, footer

**Files:**
- Modify: `WEB-INF/jsps/tiles/tiles-tabbedpage.jsp`, `tiles-mainmenupage.jsp`, `bannerStatus.jsp`, `userStatus.jsp`, `footer.jsp`, `roller.css` (+ `roller-tokens.css` only if a token is missing)
- Modify: `ApplicationResources.properties` (rail group labels if the Menu model's tab keys don't already serve; check first — `tabbedmenu.*` keys exist)
- Test: extend `it-selenium` ONLY if a new stable hook is needed (add `id="adminRail"` to the rail nav for future ITs); unit: none beyond MessageKeyTest staying green

**What it becomes (per the approved Shell card):**
- `bannerStatus.jsp`: keep `nav.navbar` + the collapse plumbing and ALL pinned links (logout link selector must survive), but drop the per-tab dropdown menus from the top bar — the top bar keeps brand (site name), Front Page, Main Menu, user/logout. Style to tokens (surface bar, line border-bottom — kill `bg-dark` by replacing the class styling, or swap `navbar-dark bg-dark` to a tokens class; `navbar-*` classes not IT-pinned — verified inventory).
- `tiles-tabbedpage.jsp` left column: DELETE the feather/poweredBy card. First block = context: `userStatus.jsp` reworked into the rail context header (weblog name 600 + handle in `--font-data` + a `--good` status dot when `actionWeblog.visible`; falls back to the user block on non-weblog screens). Second block = THE MENU: iterate the same `navMenu` model `bannerStatus` used, rendering each tab as a caps-label group and its items as rail links; the ACTIVE item (match `tabItem.action` against the current action — the Menu model exposes selected state; check `MenuTabItem`/`Menu` for the `selected` flag Roller's menu model traditionally has and use it) gets the spine treatment (class `rail-active`). Third block = the page's `tile_sidebar` include (filters/actions), card-styled. Keep `generic.poweredBy` + feather OUT of the rail; the footer keeps the attribution.
- `tiles-mainmenupage.jsp`: same rail structure (no weblog context — user context + menu groups).
- `footer.jsp`: one quiet line — tiny feather + `footer.productName` — restyled to `--ink-soft` 12px.
- `roller.css`: the rail component (`.rail`, `.rail-group-label`, `.rail-link`, `.rail-active` with the 2px inset `--accent` spine + `--accent-quiet` ground per the card), page-title row (`.roller-page-title` restyled: 20px/600, with the primary action floated right when present — pure CSS, JSPs unchanged), content card = `--surface` + `--line` border, radius 6.
- **Do not touch** `${tile_*}` attribute names, the `h2.roller-page-title` element+class, or the sidebar include conditional.

- [ ] Steps: restyle → `mvn -pl app test -Dtest='MessageKeyTest,MenuDefinitionTest,MenuHelperTest'` green → live check on the dev server (controller does browser pass later; you verify by rendering at least one tabbedpage JSP compiles via jspc if the repo has the precedent from Wave B Task 8 — it does, reuse it) → commit `"The shell: context rail replaces the branding card"`.

---

# Task 3: Controls — buttons, pills, forms, focus

**Files:** `roller.css` only (+ JSP edits limited to: `WeblogConfig.jsp`, `GlobalConfig.jsp` label-layout change if CSS `display:block` on their `col-sm-3` labels is insufficient — prefer CSS: the settings forms use `row mb-3 > label.col-sm-* + div.col-sm-*`; convert visually to labels-above by making those rows block-flow under a `.form-stacked` class added to the two form elements — JSP change is two attributes, not per-field surgery)

Per the Buttons/Pills/Forms cards:
- `.btn` base → token typography/radius/padding; `.btn-primary` → `--accent` (hover: `color-mix` darken); `.btn-success` → keep the CLASS, restyle to the same accent family (the design has one primary treatment; success-class buttons render as primary); `.btn-danger`/destructive → `--bad` outline until hover; `.btn` default/secondary → `--line` border + `--ink`; disabled → 50% + no pointer. Focus ring everywhere via `:focus-visible`.
- Status pills: restyle `.scheduledEntryBox`/`.pendingEntryBox`/`.draftEntryBox` and the `td.*entry` row tints to the pill/tint treatment from the card (keep class names).
- Forms: labels 12px caps or 600 14 per card; inputs `--surface`/`--line`/radius, focus border `--focus`; help text `--ink-soft`; error text/border `--bad` (`.text-danger`/`.is-invalid` Bootstrap hooks restyled).
- EasyMDE (open hooks, zero existing overrides): `.editor-toolbar` → `--surface`, `--line` border, token buttons; `.CodeMirror` → `--surface`, `--ink`, `--font-ui` at 15px, `--line` border, min-height kept; `.editor-preview` → token prose. Keep `.CodeMirror` class untouched in markup (IT-pinned).

- [ ] Steps: implement → jspc compile check on the two touched JSPs → `mvn -pl app test -Dtest='MessageKeyTest'` → commit `"Controls: one button hierarchy, token forms, EasyMDE skin"`.

---

# Task 4: Tables and empty states

**Files:** `roller.css`; JSPs: `Entries.jsp`, `Pages.jsp`, `Submissions.jsp`, `MediaFileView.jsp`, `Comments.jsp` (empty-state blocks); `ApplicationResources.properties` (invitation copy keys)

- `table.rollertable` (class survives): caps-label header row, `--line` row borders, hover `--accent-quiet` tint, `--font-data` for date/count/slug columns (add `td.data`/utility class where the JSP marks them — additive class only).
- Empty states per the card: where each list currently prints a bare "No X" string, emit `<div class="empty-state">` with the three-part invitation (Entries: "No entries yet." / "Your first post starts the archive." / button to entryAdd; Pages: parallel; Inquiries: no button per the card; Media: "Add a photo"). Keys: `empty.entries.title/body/action` family — all referenced (ratchet).
- Keep every pinned id/selector; `Submissions.jsp` empty-state replaces the plain strip BUT `submissions.none` key either reused inside the block or removed everywhere including the IT that asserts it (`ContactFormIT` asserts the "No inquiries yet." TEXT — keep that literal string as the title line so the IT survives).

- [ ] Steps: implement → `mvn -pl app test -Dtest='MessageKeyTest'` → commit `"Tables and empty states: invitations, not shrugs"`.

---

# Task 5: Travel `_page`

**Files:**
- Modify: `themes/travel/theme.xml` (add the `_page` template block — copy `_day`'s shape: `action="custom"`, `<name>_page</name>`, `hidden true`, rendition → `page.vm`)
- Create: `themes/travel/page.vm`
- Modify: `themes/travel/travel-custom.css` (`body.tg` form styling + page prose)
- Test: extend `TravelThemeRenderingTest` (a `_page` section mirroring its own head/leak-check pattern)

`page.vm`: travel's own head chain copied from `permalink.vm` (SAME CSP byte-for-byte, `#showSeoHead`, analytics macro, stylesheet link, gallery/embed/audience/map assets) + `body.tg` chrome (`.tg-header` with name/tagline/nav incl. `#showPageLinks` in the same `<ul>` structure weblog.vm uses), then `<main class="tg-main"><article class="tg-entry"><h1 class="tg-entry-title">$utils.escapeHTML($model.page.title)</h1><div class="tg-entry-content">$model.page.renderedContent</div></article></main>`, travel footer (search + subscribe form). Title tag via `#showPageTitle($model.page)`.
`travel-custom.css` additions (inside the existing token language, `body.tg` scoped — beats the audience macro's unscoped rules by specificity): `.contact-form` fields as `--tg` cards (line borders, radius, accent button), `.newsletter-subscribe` matching; page prose falls under existing `.tg-entry-content` rules. NO `@font-face`, NO `http://` (pinned).
New test block: render `/handle/about` on a travel-themed fixture weblog (savePage pattern from `PageRoutingTest`): assert `class="tg-entry-title"`, `tg-header`, the CSP substrings via the existing `assertTravelHead`, the audience marker `audience-hp`, and the leak checks (`$utils.` absent etc.) — mirroring the class's own conventions.

- [ ] Steps: TDD (test first — it fails because the fallback renders, no `tg-` classes) → implement → `mvn -pl app test -Dtest='TravelThemeRenderingTest,*Rendering*Test'` → commit `"Travel wears its own pages"`.

---

# Task 6: Portfolio `_page` + text-card polish

**Files:** `themes/portfolio/theme.xml`, create `themes/portfolio/page.vm`, modify `portfolio-custom.css`; extend `PortfolioThemeRenderingTest`

Same shape as Task 5 on the `pf` identity (dark page: `.pf-main-entry` prose column, `pf-entry-title` h1, contact form styled dark per the approved card). Text-card polish per the card's "after": enhance `.pf-card-text` CSS ONLY — the teal-wash treatment (`linear-gradient` using the theme's own palette… portfolio has no teal token; the approved card used a subtle accent wash: add `--pf-accent: #4FB3AA` to portfolio's `:root` block and use it at low alpha) + title sizing; DO NOT change the pinned markup (`figure.pf-card.pf-card-text`, `span.pf-card-title`, no `<picture>`, `--ar:` prefix behavior).
Portfolio test additions mirror Task 5's (assert `pf-entry-title` + head pins + leaks on a page render).

- [ ] Steps: TDD → implement → `mvn -pl app test -Dtest='PortfolioThemeRenderingTest,*Rendering*Test'` → commit `"Portfolio wears its own pages; the text card gets its wash"`.

---

# Task 7: Remaining-screens sweep + browser suite

**Files:** any JSP the sweep flags (login family already tokened via shell/controls; UserAdmin's legacy select-box gets CSS-only cleanup this wave — no structural rebuild); `it-selenium/` — one new smoke: extend `RouteSweepIT`-adjacent coverage with `#adminRail` presence on a tabbed page; fix any selector fallout (expected none — Global Constraints protected them).

- [ ] Step 1: jspc-compile all touched JSPs; `mvn -pl app test` full → green.
- [ ] Step 2: FULL `mvn verify -Pit` — foreground discipline (timeout 600000; if harness-backgrounded, wait for that ONE notification; scoped single-class reruns for any deterministic failure; never commit red).
- [ ] Step 3: commit `"Design wave: sweep and browser verification"`.

---

# Task 8: Docs + closeout

**Files:** `CLAUDE.md`, `docs/design/design-system.md` (any drift from implementation), `pom.xml` floors only if measured movement (JSP/CSS waves rarely move Java coverage).

- [ ] CLAUDE.md: a short `## Admin UI` section — the tiles system + where tokens live + the restyle-never-rename selector contract (point at RouteSweepIT/Routes.java as the enforcement) + rail structure; note themes now ship `_page` templates and what the pinned rendering tests cover.
- [ ] `bin/check-diff-coverage.sh 0d33adaa7` (Java-only lines; expect trivially green), floors per convention.
- [ ] Final: `mvn -pl app test` green → commit `"Document the quiet instrument"`.

---

# Self-review

**Card coverage:** tokens/type/spacing → 1; shell/rail/top bar → 2; buttons/pills/forms/EasyMDE → 3; tables/empty states → 4; travel page+contact → 5; portfolio page+card → 6; sweep+ITs → 7; docs → 8. Nav-rail card = Task 2's menu-into-rail move. All 12 cards land.
**Do-not-break integrity:** every constraint selector appears in the Global Constraints list verbatim from the IT inventory; theme pins enumerated; CSP untouchable; `ContactFormIT`'s "No inquiries yet." literal preserved in Task 4.
**Known risks:** (1) moving menus from top-bar dropdowns into the rail changes navigation structure ITs *don't* pin (verified — no dropdown-item selectors in the inventory) but humans use; the browser pass in Task 7 is the net. (2) The `Menu` model's active/selected flag needs confirming in Task 2 (`MenuHelper`); if absent, match on the current action name from the request. (3) Plex webjar coordinates unverified — Task 1 Step 1 resolves or falls back to vendored fonts, both documented. (4) Dark-mode admin ships via `prefers-color-scheme` only; JSP inline `style=` attributes (e.g. `min-height:30em`, `text-align:center` in tiles) must migrate into CSS classes in Task 2 or they'll fight the tokens — Task 2 removes inline styles from the layouts it touches.
