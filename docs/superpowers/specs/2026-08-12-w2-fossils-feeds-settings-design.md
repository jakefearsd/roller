# W2 — Fossils, feeds, settings

**Date:** 2026-08-12
**Status:** approved (Jake, 2026-08-12 — decisions taken in the program design)
**Parent:** `2026-08-12-minimalism-program-design.md`
**Migration:** `V023`
**Predecessor:** W1 (comment removal), 14 commits, complete and green.

## What this wave is

W1 removed one entangled subsystem. W2 removes **eight largely independent
things**, which makes it wider but shallower. After it, the program hits its
checkpoint: the minimal working system, before any new code.

| # | Removal | Why |
|---|---|---|
| 1 | Calendar subsystem | 6 files, 2 macros, **zero** bundled-theme callers |
| 2 | Multi-locale weblogs | `enableMultiLang`/`showAllLangs` + per-entry locale; zero theme readers |
| 3 | Blogger API category | configures an API that is not in this codebase |
| 4 | RSS, search feeds, media-file feed | Atom only; nothing subscribes to a search query |
| 5 | Legacy free-text analytics code | unreachable — `weblogAdminsUntrusted=true` on this fork |
| 6 | Group-blogging ceremony | invite/accept/pending/resign; the bare member list survives |
| 7 | Maintenance → Global Admin | operator tools, not authoring tools |
| 8 | Design-tab gating **fix** (not a removal) | theme selection stops depending on the custom-theme flag |

Plus the items W1 handed forward (see Inherited below).

## Four traps, verified during scoping

These are the ones that would cause real damage. Each was checked against the
working tree, not assumed.

1. **Most "calendar" hits in this codebase are `java.util.Calendar`.**
   `AbstractWeblogEntriesPager:23,198`, `WeblogEntriesMonthPager:78-88`,
   `WeblogEntriesDayPager:23,84-87`, `Weblog.java:641`, `SiteModel.java:22,424`
   and `DateUtil` all import and use the JDK class. **Date archives are not
   being removed.** Only these are the feature: `ui/core/tags/calendar/`
   (`CalendarTag`, `CalendarModel`, `WeblogCalendarModel`,
   `BigWeblogCalendarModel`, `package-info`), `ui/rendering/model/CalendarModel`,
   and `weblog.vm`'s two macros at 1145 and 1152.

2. **`localesList` is not multi-locale.** It populates the *user* locale picker
   (`Profile.jsp:86`, `UserEdit.jsp:126-130`) and the *weblog* locale picker
   (`CreateWeblog.jsp:72`, `WeblogConfig.jsp`). All of those stay, and
   `weblog.locale` / `user.locale` stay. Only `enableMultiLang`, `showAllLangs`,
   the per-**entry** locale select in `EntryEdit.jsp:178-190`, and the locale
   branches in `FeedServlet:183` / `PageServlet:457` go.

3. **`frontpage/weblog.vm:87` carries a live RSS link** —
   `<a href='$url.feed.entries.rss'>Subscribe to the combined RSS feed</a>`.
   Dropping RSS without editing it reproduces W1's exact defect: Velocity is
   lenient, so it would render the literal string `$url.feed.entries.rss` into
   the front door's markup. `weblog.vm`'s head autodiscovery block also carries
   RSS `<link>`s at 85, 89 and 92, and a search-feed link at 96.

4. **`it-selenium/src/test/resources/seed-it-data.sql` is raw SQL with no
   compile-time or JPA check against the schema.** W1 discovered the hard way
   that a migration dropping columns this fixture writes fails the entire
   browser suite at fixture load, not at any test. `V023` drops columns; the
   seed must be checked in the same task.

## Scope, item by item

### 1. Calendar
Delete the six files and the two macros above, plus `$calendarModel` from
`ModelLoader`. Nothing else references them.

### 2. Multi-locale
`Weblog.enableMultiLang` / `showAllLangs` + accessors + wrappers + their
`Weblog.orm.xml` mappings + `WeblogConfigBean` fields + the two `WeblogConfig.jsp`
checkboxes + the `EntryEdit.jsp` per-entry locale block (keep the hidden input's
job: an entry still stores a locale, defaulted from the weblog) + the
`FeedServlet`/`PageServlet` branches + `WeblogConfigController:128-129`.
`V023` drops `enablemultilang` and `showalllangs`.

### 3. Blogger API category
`WeblogConfigBean.bloggerCategoryId`, `Weblog.bloggerCategory` + accessors,
`WeblogWrapper.getBloggerCategory`, the `WeblogConfig.jsp` section and its rail
link, `WeblogConfigController:109-120`, `JPAWeblogManagerImpl:292`, and the three
maintenance sites in `JPAWeblogEntryManagerImpl` (119-120, 148-150, 725).
`saveWeblogEntry:186`'s fallback becomes "first category found", which is
already the null path. `V023` drops `bloggercatid`.

### 4. Feeds — Atom only
Delete `weblog-entries-rss.vm`, `site-entries-rss.vm`, `weblog-search-atom.vm`,
`site-search-atom.vm`, `weblog-files-atom.vm`, and `roller-ui/styles/rss.xsl`.
Delete `SearchResultsFeedModel`, `SearchResultsFeedPager`,
`URLStrategy.getWeblogSearchFeedURLTemplate`, `FeedServlet`'s search branch, and
every `rss`/`files` accessor on `URLModel`'s feed classes.
**Then fix the four live template sites in trap 3.**
`atom.xsl` survives; its two body links (`ietf.org/rfc/rfc4287.txt`) go to
`https`. Styled feeds stay on.

### 5. Legacy analytics code
`Weblog.analyticsCode` + accessors + `Weblog.orm.xml:49-50`,
`WeblogConfigBean.analyticsCode`, the `WeblogConfig.jsp` textarea and its
`showAnalyticsCodeOverride` computation, `ConfigModel.getDefaultAnalyticsTrackingCode`
and `getAnalyticsOverrideAllowed`, the `analytics.default.tracking.code` and
`analytics.code.override.allowed` property-defs, and `weblog.vm:393-394`'s
injection branch. The structured Umami UUID field is the only path left.
`V023` drops `analyticscode`.

### 6. Group-blogging ceremony
Delete `MembersInviteController`, `MemberResignController`, `MembersInvite.jsp`,
`MemberResign.jsp`, `MembersSidebar.jsp`, `MailUtil.sendWeblogInvitation`, and
`ObjectPermission.pending` + its `roller_permission.pending` column (V002:101).
`MembersController` and `Members.jsp` survive as a grant/revoke list.
**Take `commentManagement.pending`, `Members.jsp:52`'s
`<span class="pendingCommentBox">` and `roller.css`'s `.pendingCommentBox` with
it** — W1 retained that key only because Members still rendered it.
`V023` drops `pending`.

### 7. Maintenance → Global Admin
`MaintenanceController` moves to `ui/controllers/admin/`, mapped under
`/roller-ui/admin/`, with a weblog selector (the three actions are per-weblog:
flush cache, rebuild index, regenerate renditions). Remove the
`tabbedmenu.website.maintenance` item; add it to `admin-menu.xml`. Update
`Routes.java` and `AnalyticsInjectionIT` (which references the route).

### 8. Design-tab gating — a FIX
`editor-menu.xml:71` gates the whole `tabbedmenu.design` group on
`themes.customtheme.allowed`, which defaults **false** — so a blog owner cannot
change theme at all on a default install. Split it: the group and its
`themeEdit` item become ungated; `stylesheetEdit` and `templates` keep the flag,
as does `ThemeEditController:203`'s custom-conversion check (that check is the
real enforcement and must not be weakened). `MainMenu.jsp:131` gates a link on
the same property — re-check what it points at and gate it correctly.

## Inherited from W1

- `PageServlet.selectTemplate`'s unused `request` parameter — remove it.
- `docs/roller-template-guide.adoc`'s `getHotWeblogs` / "hot blogs" references
  (retired hitcount subsystem, same defect shape as W1's doc debt).
- `README.md`'s "published to GHCR on every push to master" (contradicts the CI
  section — publishing is tag-gated) and "~2,200 JUnit tests" (actual: 3117).
- The pre-existing `$url.openSearchSite` / `$url.openSearchWeblog` literal-render
  bug in the search feeds — resolved by deleting those feeds (item 4).

## Error handling

Nothing gains a failure mode; the wave only subtracts, except item 8 which
*widens* access to an existing screen. Two invariants to preserve and verify:

1. **`ThemeEditController`'s custom-conversion enforcement must not weaken.**
   Ungating the menu must not ungate the conversion. A weblog already on a
   custom theme stays grandfathered.
2. **`weblog.lastModified` bumping** — unchanged from W1, but item 7 moves a
   controller that calls `CacheManager`; confirm the flush action still works
   from its new home.

## Testing

- Every task runs the full unit suite, not just its named classes.
- Every task greps `app/src/main/webapp/themes` and `WEB-INF/velocity` for what
  it deleted. This is W1's most expensive lesson: Velocity is lenient, so a dead
  reference prints as literal text with no error, no test failure and no log.
- `SchemaMigrationTest` covers `V023`.
- `seed-it-data.sql` is checked in the migration task (trap 4).
- Full `mvn verify -Pit` at the end.

## Definition of done

Both suites green. Editor menu at 10 items with Design reachable on a default
install. Weblog Settings at 4 sections / 12 fields. No RSS anywhere. `grep -rni
"calendar\|multilang\|bloggercat\|analyticscode"` over `app/src/main` returns
only `java.util.Calendar` usage and prose. CLAUDE.md updated — including the
"Permutation coverage" section again, which item 2 and item 6 both shrink.
