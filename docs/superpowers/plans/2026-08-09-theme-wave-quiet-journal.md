# Theme Wave — Quiet Journal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the three legacy themes (basic, fauxcoly, gaurav) with one new general-purpose theme, `journal` ("Quiet Journal"), and restyle the `frontpage` aggregator to the design system.

**Architecture:** `journal` is a shared theme built the way `travel` and `portfolio` are — same directory shape, same head-chain macros, same CSP contract, its own `_page` template from day one. Retirement is a data migration (V018) plus directory/test deletion. The approved design cards (design project groups "Quiet Journal" and "Frontpage") are the visual source of truth; their HTML is committed under `docs/design/journal/` for permanence.

**Tech Stack:** Velocity templates, IBM Plex Serif via webjar (`org.webjars.npm:ibm__plex-serif` — sibling of the two Plex webjars already in `app/pom.xml:329-335`), JUnit rendering tests, Selenide ITs.

## Global Constraints

- **Design authority:** Jake approved the four cards (Quiet Journal ×3, Frontpage ×1) as-is. Deviations from card layout/tokens need coordinator sign-off. Token values come from `docs/design/design-system.md` — no new colors.
- **CSP:** every new/changed theme template's CSP meta is byte-for-byte `CSP_STANDARD` as pinned by `AnalyticsInjectionRenderingTest` (plus the same `img-src * data:` and provider `frame-src` additions the other themes carry — copy travel's meta exactly unless a journal page genuinely embeds nothing; verify which variant travel/portfolio actually ship and match).
- **Macros:** journal templates call the standard head chain in the same order travel does: `#showSeoHead`, `#showAnalyticsTrackingCode`, stylesheet link, `#showGalleryGridStyles/#showGalleryAssets/#showEmbedAssets/#showAudienceAssets/#showMapAssets`; `#showPageLinks` inside an open `<ul>` (PageNavRenderingTest).
- Theme custom CSS: no `@font-face` pointing outside webjars, no `http://` literals. Plex Serif loads via webjar paths (WebjarReferenceTest scans them).
- Never touch travel/portfolio templates or their pinned tests.
- All builds foreground (timeout 600000); never commit red; nothing under `.superpowers/` in any commit.
- Entry content is Markdown rendered server-side; templates emit `$entry.renderedContent`-family properties exactly as travel does — never re-render client-side.

---

### Task 1: Journal theme skeleton — home page renders

**Files:**
- Create: `app/src/main/webapp/themes/journal/theme.xml`, `weblog.vm`, `_day.vm`, `journal-custom.css`
- Create: `docs/design/journal/` — copy the four approved card HTML files in verbatim (design source of record)
- Modify: `app/pom.xml` — add `org.webjars.npm:ibm__plex-serif` dependency (same version-and-exclusion shape as ibm__plex-sans at lines 318-340; verify the artifact's actual available version on Maven Central first — if no webjar exists, STOP and report BLOCKED)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/servlets/JournalThemeRenderingTest.java` (new — model on TravelThemeRenderingTest's structure)

**Interfaces:**
- Produces: theme id `journal`, CSS class vocabulary `qj-*` (matching the card classes: `qj-entry`, `qj-date`, `qj-title`, `qj-site`, `qj-nav` etc.), stylesheet at `journal-custom.css`, used by Tasks 2-3.

- [ ] Step 1 (TDD): write JournalThemeRenderingTest first — CSP pin, head-chain substrings, `qj-date` marginalia + `qj-title` on the home page for a published entry, `#showPageLinks` nav present, `$utils.`-leak check — run it, watch it fail (theme does not exist).
- [ ] Step 2: build theme.xml (copy travel's structure: weblog + permalink actions declared; permalink can point at weblog.vm until Task 2), weblog.vm (per the Journal — Home card: header block, date-marginalia entry grid via `_day.vm`, footer search + `#showSubscribeForm`), `_day.vm` (the `qj-entry` grid row: mono date cell, serif title link, summary, category/comment meta), journal-custom.css (tokens from the card, light + dark via `prefers-color-scheme`, Plex Serif/Sans/Mono `@font-face` from webjar paths, the 2px `qj-entry:hover` spine).
- [ ] Step 3: test green; full `mvn -pl app test` green.
- [ ] Step 4: Commit `"Journal theme: skeleton and home page"`.

### Task 2: Journal reading view (permalink + comments + search)

**Files:**
- Create: `themes/journal/permalink.vm`, `searchresults.vm`
- Modify: `theme.xml` (point permalink action at permalink.vm), `journal-custom.css`
- Test: extend `JournalThemeRenderingTest`

- [ ] Step 1 (TDD): extend the test — permalink renders `qj-h1` serif title, byline with mono date, prose at reading measure, the comment area (`#showWeblogEntryCommentForm` — signed-out shows the sign-in prompt), search results page renders through the theme. Red first.
- [ ] Step 2: build permalink.vm per the Reading-view card (crumb, title, byline, prose column, pull-quote styling for blockquotes, comments per card), searchresults.vm (imitate travel's).
- [ ] Step 3: green; full `mvn -pl app test` green.
- [ ] Step 4: Commit `"Journal theme: reading view"`.

### Task 3: Journal `_page` + audience forms

**Files:**
- Create: `themes/journal/page.vm`; Modify: `theme.xml` (add the `_page` custom-action block exactly as travel/theme.xml does — hidden, navbar false), `journal-custom.css`
- Test: extend `JournalThemeRenderingTest` (the travel `_page` test section is the model, including the `contact-form-slot` assertion)

- [ ] Step 1 (TDD): page fixture with `[contact]` → `qj`-dressed page title + prose + `contact-form-slot` present + audience assets in head; a draft page still 404s. Red first.
- [ ] Step 2: page.vm per the Page & Contact card; `body.qj`-scoped contact/subscribe form CSS (specificity must beat the audience macro's unscoped rules — travel's precedent).
- [ ] Step 3: green; full `mvn -pl app test` green.
- [ ] Step 4: Commit `"Journal theme: pages and contact"`.

### Task 4: Retire basic, fauxcoly, gaurav

**Files:**
- Create: `bin/db/migrations/V018__retire_legacy_themes.sql` — idempotent `UPDATE weblog SET editortheme='journal' WHERE editortheme IN ('basic','fauxcoly','gaurav');` (custom-theme weblogs store `WeblogTheme.CUSTOM` and are untouched by definition)
- Delete: `app/src/main/webapp/themes/basic/`, `themes/fauxcoly/`, `themes/gaurav/` (whole directories)
- Modify: `app/src/test/java/org/apache/roller/weblogger/TestUtils.java:162` — fixture theme `basic` → `journal`; then chase every test that referenced the three retired ids (the grep list is long — `AnalyticsInjectionRenderingTest`, `SiteModelTest`, `WeblogTest`, `WeblogLogicTest`, etc.): switch fixture ids to `journal` and update any output-shape assertions that pinned basic's markup to pin journal's instead. A test that exists ONLY to pin a retired theme's output is deleted, not ported — name each deletion in the report.
- Modify: `it-selenium` — `ThemeMatrixIT` theme list (three out, journal in), `ThemeIT`/any IT naming a retired id.

- [ ] Step 1: migration + SchemaMigrationTest idempotency run green.
- [ ] Step 2: delete directories; fix the full test sweep; `mvn -pl app test` green.
- [ ] Step 3: `mvn verify -Pit -Dit.test='ThemeMatrixIT,ThemeIT'` green (foreground).
- [ ] Step 4: Commit `"Retire basic, fauxcoly and gaurav; journal is the default fixture"`.

### Task 5: Frontpage front door

**Files:**
- Modify: `themes/frontpage/` templates + stylesheet to the Front-door card (hero, latest-across-the-site via `$site` model, weblog directory cards with the teal-wash treatment + live dot)
- Test: extend/create the frontpage rendering test (find what pins frontpage today — `SiteModelTest` and any FrontpageRenderingTest; keep `$site`-model usage intact — frontpage renders only for the `site.frontpage.weblog.handle` weblog, which is why ThemeMatrixIT excludes it)

- [ ] Step 1 (TDD): rendering test for the restyled front door (fd-* classes, CSP pin, directory block) — red first.
- [ ] Step 2: restyle per card, keeping every `$site` call the current templates make (aggregation logic is not this wave's to change).
- [ ] Step 3: green; full `mvn -pl app test` green.
- [ ] Step 4: Commit `"Frontpage: the front door"`.

### Task 6: Docs, full verification, evidence

**Files:**
- Modify: `CLAUDE.md` — Themes section: journal described (id, qj-* vocabulary, Plex Serif webjar, _page), the retirement + V018 note, frontpage restyle note. Match the file's dense voice.
- Modify: `docs/design/design-system.md` — add the journal/front-door card references.

- [ ] Step 1: docs.
- [ ] Step 2: FULL `mvn verify -Pit` green (foreground; GalleryIT font-abort = known flake, rerun once before treating as real).
- [ ] Step 3: `bin/check-diff-coverage.sh <wave-base-ref>` on the wave range.
- [ ] Step 4: Commit `"Theme wave: document the journal"`.
