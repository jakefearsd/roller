# Themes and templates

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## Theme System
- **Shared themes** live under `/themes/`: `portfolio` (dark photo grid),
  `travel` (light travel guide cards) and `journal` (the default: `qj-*`
  vocabulary, light+dark, self-hosted IBM Plex — `ibm__plex-serif` joins
  the Sans/Mono webjars in `app/pom.xml`; the CSS `url()`s carry the webjar
  version, so a bump edits three stylesheets and `WebjarReferenceTest`). A theme
  with its own webfont must add `font-src 'self'` to its CSP on top of
  `CSP_STANDARD`, or the browser refuses every `@font-face`;
  `JournalThemeRenderingTest` pins the string byte-for-byte.
  `ThemeCspCoverageTest` enforces it for any theme CSS referencing
  `@font-face`, checking `/webjars/` fonts via
  `getResource("META-INF/resources/webjars/...")`, not the filesystem (Plex
  is classpath-served, not under `webapp/`). All three ship a `_page`
  template (see Themes); `journal`'s is covered for the `[contact]` slot and
  the draft-404 case.
- **Retired**: `basic`, `fauxcoly`, `gaurav` — directories deleted;
  `V018__retire_legacy_themes.sql` moves any weblog still on one to `journal`
  (idempotent; a custom-theme weblog, `'custom'` in `editortheme`, is
  untouched). The fixture theme (`TestUtils`, seeded IT weblog) is
  `journal`; `ThemeMatrixIT` lists `journal`/`portfolio`/`travel`. Never
  reintroduce a theme directory or a migration writing `editortheme` back
  to one of these ids.
- `frontpage` (the multi-weblog aggregator theme) uses the `fd-*` front-door
  design, same `font-src` addition and self-hosted Plex as `journal`.
  `_blogprofile.vm` and `_blogs.vm` are deleted with their `theme.xml`
  registrations; directory cards link to the weblog.
- **Entry titles are stored HTML-escaped; page titles are stored raw.**
  `EntryBean.copyTo` runs `StringEscapeUtils.escapeHtml4` on the title once
  at save, so `WeblogEntry.getTitle()` is already escaped and every theme
  must emit `$entry.title` bare — `$utils.escapeHTML($entry.title)`
  double-encodes to `&amp;amp;`. `PageBean.copyTo` copies `title` through
  unescaped, so `WeblogPage.getTitle()` is raw and every page template must
  call `$utils.escapeHTML($model.page.title)` (or `#showPageTitle`) itself,
  as all three themes' `page.vm` do — skipping it is stored XSS once a title
  carries `<script>`. `EditorJspEscapingTest` pins the admin-JSP side:
  every author-controlled EL expression, `entry.title` included, goes through
  `fn:escapeXml` (JSP fields get no save-time escaping).
- **The `bean.*` form expressions are the dangerous twins of the display
  expressions**: `value="${bean.name}"`, `<textarea>${bean.text}</...>`,
  inline-JS `'${bean.link}'` are stored/reflected XSS. Two traps:
  `EntryBean.copyFrom` runs `StringEscapeUtils.unescapeHtml4` on the title
  (the author edits what they typed), so `bean.title` is raw though
  `entry.title` is stored escaped; and a wrapper that escapes does not make
  the pojo safe — `Weblog.getName()` is raw, `WeblogWrapper.getName()`
  escapes on read, `WeblogConfig.jsp` reads the bean. Guard a field by adding
  it to `EditorJspEscapingTest`'s `AUTHOR_CONTROLLED` list in
  **both** spellings. `<spring:message arguments="...">` does **not** escape
  (`defaultHtmlEscape` is unset), so an argument is an injection point too.
- Custom themes: per-weblog (see Themes); template types `.vm`, stylesheets,
  resources; hot reload in dev.

## Themes
- A weblog runs either a **shared** theme (id from `themes/<id>/theme.xml`) or
  `WeblogTheme.CUSTOM`. Switching to custom **imports** the shared theme's
  templates as the weblog's own rows and is one-way (it stops tracking the
  shared theme). `ThemeIT` therefore works on weblogs it creates itself;
  never switch the seeded IT weblog.
- `ThemeEdit.jsp` hides its Save buttons until its JS sees a change; pick
  the theme (or the radio) first to reveal the right one.
- A theme switch reaches readers via `saveWeblog` bumping `lastModified`, not
  `CacheManager.invalidate` — see Templates on `WeblogPageCache`.
- **`themes.customtheme.allowed` (default `false`) gates only two of the
  Design tab's three items.** `themeEdit` (shared-theme selection, safe and
  reversible) is ungated; only `stylesheetEdit` and `templates` carry
  `enabledProperty="themes.customtheme.allowed"` in `editor-menu.xml` (the
  whole `tabbedmenu.design` group used to). `MainMenu.jsp` splits its theme
  button the same way: shared-theme link unconditional, custom-theme link
  behind the flag.
- **`themes.customtheme.allowed` is enforced in `ThemeEditController`, not
  just the menu** — a hidden menu entry stops nobody posting to
  `themeEdit!save.rol` directly. Only the `WeblogTheme.CUSTOM` branch of
  `ThemeEditController.save` checks it; the shared-theme branch never has. A
  weblog already on a custom theme is grandfathered (disabling the option
  must not strand it).
- `journal`, `travel` and `portfolio` each ship a `_page` template
  (`themes/<id>/page.vm`); a `WeblogPage` reaches it via the
  `StaticThemeTemplate` fallback, so a static page renders in the theme's
  own chrome (travel's `tg-header`, portfolio's dark frame), not the fallback
  template's bare `<h1>`. `TravelThemeRenderingTest`/
  `PortfolioThemeRenderingTest` pin this: a page carrying `[contact]` must
  render through the theme's header/prose classes and ship the audience
  assets in the head, with an `assertFalse` on the fallback `<h1>`.

## Templates
- Add/edit/remove live in `TemplatesController` and `TemplateEditController`;
  both resolve client ids through `BaseController.lookupTemplate` (pinned by
  their `*ControllerTest`s).
- A CUSTOM template gets `link = name` and is served publicly at
  `/<handle>/page/<link>` — including on a weblog running a *shared* theme,
  via `WeblogSharedTheme.getTemplateByLink`'s fallback to the weblog's own
  templates. `TemplateIT` asserts that end to end.
- **Render-cache expiry.** `saveTemplate`/`removeTemplate` bump
  `weblog.lastModified`, the only thing that expires a rendered page:
  `WeblogPageCache` and `WeblogFeedCache` both pass
  `constructCache(null, ...)`, register no CacheHandler, and are never reached
  by `CacheManager.invalidate(...)` — they expire only lazily against
  `weblog.lastModified`. `SiteWideCache` is the sole eager render cache: it
  registers itself (`constructCache(this, ...)`) and `CacheManager` drops it
  wholesale. `RenderCacheHandlerRegistrationTest` pins the split. The two lazy
  caches share `LazyExpiringRenderCache` (config read, backing `Cache`,
  `get`/`put`/`remove`/`clear`); each subclass keeps its `CACHE_ID`, singleton
  and `generateKey`. `SiteWideCache` is deliberately outside that hierarchy —
  its `get` takes no timestamp, having no per-weblog expiry.
- **A Velocity resource loader that cannot report modification may not be
  cached; the two settings only ever change together.** With `cache=true`
  Velocity reuses a parse tree whenever `isSourceModified` says "not stale",
  so a loader answering a constant `false` pins every template for the life of
  the JVM — edits do nothing until a restart, with no error.
  `ThemeResourceLoader` used to answer that constant (hence
  `resource.loader.theme.cache` false); it now reports the theme's real disk
  timestamp, `0` → "modified" when it cannot tell, and is cached.
  `RollerResourceLoader` serves CUSTOM templates from database rows with no
  timestamp and still answers a constant `false`, so
  `resource.loader.roller.cache` must stay `false` until it reports a real
  timestamp. `LoaderCachingContractTest` probes every *cached* loader with a
  maximally stale resource and fails if one answers "unmodified"; loaders that
  need a collaborator to answer (`webapp`, which asks the `ServletContext` for
  a real file) are named there with a reason, the `QualityGatePomTest`
  convention.
- **Velocity is lenient, so deleting a Java member is a live hazard.**
  `velocity.properties` sets no `runtime.references.strict` and turns off
  `runtime.log.invalid.reference`: a reference to a deleted field or getter
  neither throws nor logs — it prints as literal text (e.g.
  `$entry.commentCount`) into the page (W1 shipped `journal/_day.vm`'s
  `$entry.commentCount` and `feeds.vm`'s
  `<comments>$url.comments(...)</comments>` this way). Any deletion of a Java
  member a `.vm` can reach (pojo, wrapper, model) must `grep`
  `app/src/main/webapp/themes` and `app/src/main/webapp/WEB-INF/velocity` for
  it before being called done.
- **A zero-argument macro name written bare inside a comment is INVOKED, not
  printed.** `#name` with no parentheses is a valid velocimacro call when
  `name` takes no arguments, inside a `//` JavaScript comment or a `/* */` CSS
  one alike (it bit `weblog.vm`'s live-preview script via a
  `#showPreviewShellScript` mention, and three themes' `*-custom.css` via
  `#showAudienceAssets`/`#showWeblogCategoryLinksList`). Write "the showX
  macro" in a comment, never `#showX` — `ThemeStylesheetTest` scans every
  theme stylesheet for the bare form and fails the build.
