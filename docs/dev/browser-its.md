# Browser integration tests

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## The IT harness cleans up by identity, not by pidfile

Everything an IT run creates carries its id (`${it.run.id}` = build
timestamp + reserved HTTP port); the app JVM's command line adds
`-Droller.it.run=<id>` and `-Droller.it.owner=<pid>@<start time>` (owning
build). The pidfile scheme it replaced silently leaked an app JVM, a
chromedriver and a Docker volume per abnormal run
(`build-helper:reserve-network-port`'s free port meant leaks never collided).

Five mechanisms in `it-selenium/src/test/script/`:

- **`start-app.sh` kills what it started on every failure path** (`trap
  cleanup EXIT`; INT/TERM become exits). Its readiness timeout used to
  `exit 1` with the JVM running, skipping `post-integration-test` and so
  `app-stop`.
- **`sweep-stale.sh` runs first in `pre-integration-test`**, reaps what
  earlier runs left and **reports every pid and container it takes** —
  silence was the defect.
- **`supervise-run.sh` starts before `pg-start`, detaches, and outlives the
  build.** Failsafe protects cleanup from *test* failures only; an
  *infrastructure* failure in `pre-integration-test` (`pg-wait-ready`,
  `migrate.sh`, the seed, `app-start`) or a Ctrl-C skips
  `post-integration-test`. It kills the run's app, its chromedrivers
  (recorded while their JVM lived; unattributable after), and the
  container (`docker rm -f -v`, always `-v`).
- **Staleness is decided by the owning build, never by "an IT process
  exists"**: a concurrent `mvn verify -Pit` is legitimate and must survive
  another run's sweep.
- **The supervisor marks itself `-Droller.it.supervisor=`, not
  `-Droller.it.run=`.** A forked subshell (command substitution, pipeline
  halves) shows its *parent's* command line in `ps`; a supervisor in its
  own run would kill its own subshells (`ItHarnessLeakTest` pins it).

Per run: container `roller-it-postgres-<run id>` (no fixed-name 409 needing
`docker rm`); `pg-stop` passes `<removeVolumes>true</removeVolumes>` (the
default leaked an anonymous volume even on success: `postgres:18` declares
`VOLUME /var/lib/postgresql`); pidfiles, logs and work dirs, so a leaked
run's `app.log`/`app.pid` survive; `it-work/app-latest.log` symlinks the
newest.

`RollerPostgresContainer` (the unit suite's shared, never-stopped container)
adds a JVM shutdown hook behind Ryuk, one process that can be missing.

Tests (fast suite, not `-Pit`): `ItHarnessLeakTest` (real scripts, fake
processes), `ItHarnessPomTest` (`removeVolumes`, per-run names, plugin order
putting `app-stop` before `pg-stop`), `RollerPostgresContainerTest`.

## Permuting global runtime flags

Browser tests permute global runtime properties via `RollerIT.setGlobalFlag`
(`setGlobalFlags` for several in one save), which drives the real Admin
Settings page, returning the old value. One shared instance: every caller
must restore in a `finally`.

## Permutation coverage in the browser suite
Four classes carry the configuration matrix:

- `ThemeMatrixIT` — every bundled theme rendering one entry carrying
  `[image]`/`[gallery]`/`[map]`/`[faq]`, on the home page and the permalink
  (different templates). One looping test, since the fixture costs ~9s.
  `frontpage` is excluded — it renders through `$site`, which exists only
  for the weblog named by `site.frontpage.weblog.handle`.
- `WeblogConfigMatrixIT` — per-weblog settings: locale, `entryDisplayCount`,
  and `active` (which also withdraws the weblog from the sitemap index). Each
  test owns its weblog and touches no global state.
- `GlobalConfigMatrixIT` — three tests that mutate site-wide state: the
  feature-refusal switch (uploads/weblog-creation off, batched),
  `groupblogging.enabled`'s own refusal (a user who already owns a weblog is
  refused a second one — its own test, so the assertion cannot pass for the
  wrong reason), and the entry-URL word-separator. One of five classes that
  call `setGlobalFlag`; "Browser ITs run class-parallel" lists them.
- `ScheduledEntryIT` — a future-dated entry is withheld from pages, its Atom
  feed, and the sitemap.

Comment-related tests and their `postCommentDirectly`/`approveComment`
helpers were deleted with the comment subsystem in W1; the invite/accept
ceremony (`invite.rol`) went in W2 — `MembersController.grant()` adds a
collaborator directly and has no `groupblogging.enabled` check of its own
(only the menu entry is gated).

No reachable browser coverage, documented rather than silently skipped:
- `user.hideUserNames` — every bundled theme and feed uses
  `$entry.creator.screenName`, never `.userName`, so the flag changes nothing
  in shipped output. (Per-weblog `analyticsCode` was uncoverable too, gated
  on `weblogAdminsUntrusted` being off; W2 deleted it — see Analytics.)
- `ScheduledEntriesTask` promoting a scheduled entry: an entry is only
  `SCHEDULED` when its pubtime is >1 minute out and the task cadence is whole
  minutes, so observing it costs 1-3 minutes with real variance.

## Browser ITs run class-parallel

`it-selenium/src/test/resources/junit-platform.properties` runs test
**classes** concurrently (fixed parallelism 4) and **methods** on one thread.
Re-measured 2026-09-09 across 35 classes: 412s of test time in 3m18s of
module wall clock. The profile is flat — `ThemeIT` at 27.2s is the slowest,
`VirtualHostIT` 20.4s, nothing else over 25s. Performance numbers in this
file go stale; re-measure before quoting one.

Methods stay serial on purpose: an IT class is a narrative (create a weblog,
edit it, publish, assert the rendered page) whose methods share fixtures
built in `@BeforeAll`; the parallelism worth having is across classes.

Two resource locks, whose failure modes both look like something other than
a race:

- **`RollerIT.GLOBAL_CONFIG`** — held **write** by the five classes that
  call `setGlobalFlag`: `GlobalConfigMatrixIT`, `ThemeIT`
  (`CUSTOM_THEMES_ALLOWED`), `ThemeMatrixIT` (`uploads.enabled`), `ApiIT`,
  `VirtualHostIT`. One shared app instance holds one set of runtime
  properties, so two overlapping would each see the other's flag. **A
  sixth `setGlobalFlag` class must take this lock** — nothing enforces it,
  and the symptom is a wrong-looking assertion, not a concurrency error.
  Held **read** by `RouteSweepIT`, which visits every admin route and so is
  exposed to every site-wide flag (on a `/roller`-prefix run with
  `groupblogging.enabled` off, `createWeblog.rol` returned a healthy 200
  with no form — the `categoryEdit.rol` failure mode the sweep exists to
  catch — because `CreateWeblogController` answers `.GenericError` to an
  admin who already owns a weblog), and by the media classes, which need
  `uploads.enabled` true (`GlobalConfigMatrixIT` sets
  `uploads.enabled=false`; with uploads off the media page renders no
  buttons, and the failure reads
  `Element not found {button[formaction$='entryAddWithMediaFile.rol']}`).
- **`RollerIT.SHARED_MEDIA`** — held **write** by the four classes that
  upload, crop or delete media on the shared `WEBLOG_HANDLE` (`GalleryIT`,
  `MediaCropIT`, `MediaBulkUploadIT`, `EditorSeoIT`); **read** by
  `ErrorCasesIT`, which only browses the media page.

The `GLOBAL_CONFIG` read modes preserve the speed: readers exclude the
mutators, not each other.

## BrowserHealth: three checks, and the third watches the watcher

Every check reports by finding something wrong in what it recorded, so
**every one passes vacuously when nothing is recorded**: a recorder that has
stopped seeing traffic turns all 126 tests green at once. The listeners ride
a CDP binding pinned to one Chrome major (`selenium-devtools-v153`); Chrome
ships a new major every few weeks, and Selenium answers a mismatch by
silently falling back to its nearest binding and logging a line nobody reads.

- `blindnessReport` closes it: an http(s) page with zero recorded responses
  is a broken monitor, not a clean page, and fails saying so. The
  discriminator must come from outside CDP or it is circular — the `page`
  field is itself set by a CDP event, so a blind recorder also believes it
  is on `about:blank`; `getCurrentUrl()` goes over the WebDriver protocol,
  and the two disagreeing is the signal. A test that never navigates stays
  on `about:blank` and is correctly exempt. Proof: with the
  `Network.responseReceived` listener disabled, 29 of `RouteSweepIT`'s 31
  tests fail; before the check, all 31 passed.
- **Keep `selenium-devtools-vNNN` at the newest version the pinned Selenium
  ships.** It is pinned explicitly in `it-selenium/pom.xml`, not taken
  transitively, so a Selenium bump that drops that version breaks the build
  rather than degrading at runtime (4.49.0 drops v150, which 4.47.0
  shipped).
- `assertNoBrokenResources` catches any sub-resource that came back
  4xx/5xx.
- `assertNoFailedRequests` catches requests that produced no response at
  all, closing the first's blind spot: a stylesheet whose URL 404s gets an
  HTML error page, and Chrome, refusing the wrong content type, *aborts* the
  load — no `Network.responseReceived` is emitted, so a theme whose CSS had
  gone missing rendered unstyled and passed. Webfonts refused by the page's
  own CSP arrive the same way. The discriminator is what may legitimately be
  cancelled: page script starts `Image`/`XHR`/`Fetch` and may abandon them
  (Leaflet cancels ~48 tiles per map render; the live preview coalesces
  in-flight requests as the author types), and a `Document` navigation is
  cancelled by navigating again. A `Stylesheet`, `Script` or `Font` is
  declared by the document and nothing cancels it, so an abort there means
  the browser refused it. A **blocked** request is never excused, whatever
  its type.

## Run the browser suite at BOTH context paths before shipping routing changes

`mvn verify -Pit` covers the root context; `mvn verify -Pit -Dit.context.path=roller`
alone exercises a servlet prefix end-to-end. The prefix pass found a defect
the root pass could not; `SeoController.robots()`'s test, already under
`/roller`, had hardcoded the buggy url. A fixture pinning a context path must
*derive* its expected url, never hardcode a shape.
