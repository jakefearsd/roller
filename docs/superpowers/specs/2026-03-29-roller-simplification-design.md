# Roller Simplification Design — Approach B

**Date:** 2026-03-29
**Goal:** Remove unused features and simplify admin config to make Roller maintainable as a small-business blogging platform.
**Philosophy:** "Done when there is nothing left to remove."

## What We're Keeping

- Multi-blog support
- Full-text search (Lucene)
- Comments (simplified, ready to re-enable later)
- Full theme/template editing (Velocity + custom CSS)
- Both APIs (AtomPub + XML-RPC)
- OAuth
- Media file management
- User/permission system

## What We're Removing

### 1. Planet Aggregator (~43 files, ~4,200 LOC, 6 DB tables)

**Java classes to delete:**
- `org.apache.roller.planet.*` — entire package tree (43 files)
- `org.apache.roller.weblogger.planet.*` — weblogger-planet bridge classes
- Admin controllers: `PlanetGroupsController`, `PlanetGroupSubsController`, `PlanetConfigController`
- Scheduled tasks: `SyncWebsitesTask`, `RefreshPlanetTask`
- Planet-specific rendering: `PlanetRequest`, `PlanetGroupFeedRequest`, planet feed model classes

**JSPs to delete:**
- `PlanetGroups.jsp`, `PlanetGroupSubs.jsp`, `PlanetGroupSubsSidebar.jsp`, `PlanetConfig.jsp`

**Config to remove:**
- `planet.properties` file
- Planet-related scheduled tasks from `roller.properties`
- Planet cache configuration

**Database tables dropped:**
- `rag_planet`, `rag_group`, `rag_subscription`, `rag_entry`, `rag_group_subscription`, `rag_properties`

**Dependencies:** Rome library stays (needed for blog RSS/Atom feed generation).

### 2. Bookmarks/Blogroll (~12 files, ~1,500 LOC, 2 DB tables)

**Java classes to delete:**
- Controllers: `BookmarksController`, `BookmarkEditController`, `BookmarksImportController`, `FolderEditController`
- Manager: `BookmarkManager` interface, `JPABookmarkManagerImpl`
- POJOs: `WeblogBookmark`, `WeblogBookmarkFolder` (+ wrapper classes)
- Beans: `BookmarkBean`, `FolderBean`

**JSPs to delete:**
- `Bookmarks.jsp`, `BookmarksSidebar.jsp`, `BookmarksImport.jsp`

**Database tables dropped:**
- `roller_bookmark`, `roller_bookmarkfolder`

**Theme cleanup:** Remove `$model.bookmarks` references from bundled Velocity theme files.

### 3. Pings/Trackbacks (~15 files, ~1,800 LOC, 3 DB tables)

**Java classes to delete:**
- Admin controllers: `PingTargetsController`, `PingTargetEditController`
- Editor controller: `PingsController`
- Managers: `AutoPingManager`, `PingTargetManager`, `PingQueueManager`, `PingQueueProcessor` + JPA impls
- POJOs: `AutoPing`, `PingTarget`, `PingQueueEntry` (+ wrappers)
- Beans: `PingTargetBean`
- Scheduled task: `PingQueueTask`
- Trackback: `Trackback.java`, `TrackbackNotAllowedException.java`
- Comment validator: `TrackbackLinkbackCommentValidator`

**JSPs to delete:**
- `PingTargets.jsp`, `Pings.jsp`, `PingsSidebar.jsp`

**Controller cleanup:** Remove trackback handler methods from `EntryEditController` (`entryAdd!trackback.rol`, `entryEdit!trackback.rol`). Remove commented trackback section from `EntryEdit.jsp`.

**Database tables dropped:**
- `roller_pingtarget`, `roller_autopings`, `roller_pingqueueentry`

### 4. Config & Admin Simplification

**runtimeConfigDefs.xml:**
- Remove "Ping Settings" display group
- Rename "Comment & Trackback" → "Comment Settings", remove trackback properties
- Remove any planet-related properties

**roller.properties:**
- Remove PingQueueTask, SyncWebsitesTask, RefreshPlanetTask from scheduled tasks
- Remove planet cache config (`planet.cache.*`)
- Remove ping config (`pings.*`)
- Remove trackback config

**Admin UI:**
- Delete CacheInfoController + CacheInfo.jsp (developer diagnostic, not needed)
- Update admin menu XML to remove: Planet Config, Planet Groups, Ping Targets, Cache Info
- Update editor menu XML to remove: Bookmarks/Blogroll, Pings

**Weblogger facade:**
- Remove from `Weblogger` interface: `getBookmarkManager()`, `getAutopingManager()`, `getPingTargetManager()`, `getPingQueueManager()`
- Remove corresponding Guice module bindings in `DefaultWeblogger` / module config

## Estimated Impact

| Metric | Before | After | Reduction |
|--------|--------|-------|-----------|
| Java files | ~531 | ~450 | ~80 files |
| LOC (Java) | ~84,000 | ~76,500 | ~7,500 |
| DB tables | ~40 | ~29 | ~11 |
| Admin pages | ~10 | ~6 | 4 |
| Editor pages | ~15 | ~12 | 3 |
| Config properties | ~230 | ~200 | ~30 |
| Scheduled tasks | 5 | 2 | 3 |

## Implementation Order

The safest order for removal (each step is independently committable and testable):

1. **Planet Aggregator** — most isolated, largest win
2. **Bookmarks/Blogroll** — small, clean removal
3. **Pings/Trackbacks** — medium complexity due to references in entry editing
4. **Config & Admin cleanup** — final pass, depends on 1-3 being done
5. **Weblogger facade cleanup** — remove dead manager interfaces
6. **Database migration** — drop unused tables (can be a separate SQL script)

## Verification

After each removal step:
- `mvn compile -pl app` must succeed
- `mvn test -pl app` — all 155 tests must pass
- Manual smoke test: create entry, edit entry, save draft, publish, view blog, manage comments, upload media, edit templates
- Verify admin menu has no broken links
- Verify editor menu has no broken links
