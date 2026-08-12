# Complete but minimalist: program design

**Date:** 2026-08-12
**Status:** approved (Jake, 2026-08-12)
**Scope:** the whole authoring product, evaluated from a blog *user's* seat rather
than an administrator's.

## Why this exists

The goal is not "delete things." It is to get the system to the smallest shape
that is still complete for its two real jobs — travel/rental guides and Maiia's
photography — so that large reworks can happen cheaply against a surface small
enough to hold in your head.

## Verdict

This fork is already lean. Pings, trackbacks, referrers, blogroll/bookmarks,
hitcounts, XML-RPC, public self-registration, entry plugins and share links are
all gone from earlier waves. Very little of what remains is dead weight in the
usual sense.

What the audit actually found is a different and more expensive failure, hit
four independent times:

> **A capability exists, is maintained, appears in the UI — and has no reachable
> path to it.**

These do not read as bloat in a file listing. They read as features. They cost
the reader attention, cost us maintenance, and each one is a small lie about what
the product does.

| # | Capability | Why it is unreachable | Evidence |
|---|---|---|---|
| 1 | Comments | Require a signed-in account; public self-registration was removed | `Weblog.requireAuthenticatedComments` defaults `TRUE`; `V013` sets the column `DEFAULT true NOT NULL`; enforced `CommentServlet:231`; `ProfileController:137` records the registration removal |
| 2 | Theme switching | The whole Design menu group is gated on the custom-theme flag, which defaults off | `runtimeConfigDefs.xml` `themes.customtheme.allowed` = `false`; `editor-menu.xml` gates `tabbedmenu.design` (including `themeEdit`) on it |
| 3 | Blogger API category | Configures an API that is not in this codebase | zero matches for `xmlrpc`/`MetaWeblog`/`BloggerAPI`; the settings section still renders |
| 4 | Free-text analytics code | Renders only when `weblogAdminsUntrusted` is off, and this fork always sets it on | `roller.properties:298` `weblogAdminsUntrusted=true` |

Two of these are cut, one is cut, one is fixed — see the decision table.

## Decisions

Every row below was decided by Jake on 2026-08-12 and is binding on the waves.

| Area | Decision |
|---|---|
| Comments | **Delete the subsystem entirely.** The contact form and newsletter are the real reader channels and already exist. |
| Design tab | **Keep custom themes.** Fix the gating bug only: theme *selection* stops depending on `themes.customtheme.allowed`; custom-theme conversion stays behind it. |
| Calendar | Delete. Zero bundled-theme callers. |
| Multi-locale | Delete. Zero bundled-theme callers. A single `weblog.locale` survives for formatting. |
| Blogger API category | Delete, with a migration. |
| Group blogging | **Keep a bare member list; delete the ceremony** — no invite, no accept, no pending, no resign. |
| User weblog creation | **Stays.** Not selected for removal. |
| Feeds | Cut search feeds, the media-file feed, and **all RSS**. Atom only. |
| Styled feeds | **Keep.** `atom.xsl` survives and its two body links go to https. |
| Legacy analytics code | Delete. The structured Umami UUID becomes the only path. |
| Maintenance | Move to Global Admin. Off the blog-user tabs. |
| Autosave | **Add.** |
| Bulk media upload | **Add.** |
| Media alt text | **Add.** |
| Soft delete / trash | **Add**, sequenced last — see the recorded trade-off below. |

### Recorded trade-off: trash

Soft delete is the one item on this list that makes the system *bigger*. It adds
a status dimension every entry query path must respect, which is the opposite of
the program's direction. Jake chose it with that stated. It is sequenced last so
that it is the cheapest thing to drop if it starts spreading; dropping it must
not block W1–W4.

## What a blog user is left with

**Editor menu: 13 items → 10.** New Entry, Entries, Submissions, Categories,
Pages, Media | Theme, Stylesheet, Templates | Settings, Members.

**Weblog Settings: 8 sections → 4, 21 fields → 12.**

- General — name, tagline, icon, about, email, entries-per-page, active
- Language — locale, timezone
- Analytics — Umami site id, share url
- Newsletter — list uuid

**Readers keep:** home, permalink, category/tag/date archives, pages, search,
Atom feeds, galleries, maps, FAQs, contact, subscribe, sitemaps, SEO/social
metadata, responsive images.

**Readers lose:** commenting (unreachable today), RSS, feed-of-search-results,
feed-of-media-files.

## Wave decomposition

Each wave is its own spec, its own plan, and its own commits. Each ends green,
with a migration where schema moves. Migration numbers are reserved here so the
waves cannot collide.

| Wave | Content | Direction | Migration |
|---|---|---|---|
| **W1** | Comments removal | subtract | `V022` |
| **W2** | Fossils, feeds, settings, members ceremony, maintenance move, design-tab ungate | subtract | `V023` |
| — | **Checkpoint: minimal working system, before any new code** | | |
| **W3** | Autosave / draft recovery | add | none |
| **W4** | Media: bulk upload + alt text | add | `V024` |
| **W5** | Soft delete / trash | add | `V025` |

W1 is separated from W2 because it is the single largest subtraction and touches
schema, all three themes, the browser suite and eight i18n bundles at once.
Landing it alone keeps its blast radius reviewable.

The checkpoint after W2 is the point of the whole ordering: the reworks this
program exists to enable should start from the small system, not from the large
one with new features bolted on.

## Constraints binding on every wave

1. **Schema changes need a numbered migration** under `bin/db/migrations/`,
   idempotent, never editing an applied one.
2. **Never restyle by renaming a selector** — a content-tile marker change and
   its `it-selenium/.../support/Routes.java` update land in the same commit.
3. **Deleting a route means deleting its `Routes` entry**, or `RouteSweepIT`
   fails on a route that no longer exists.
4. **Deleted message keys come out of all eight bundles**, and
   `MessageKeyTest`'s `KNOWN_DYNAMIC_KEY_COUNT` ratchet is adjusted in the same
   commit.
5. **Changed lines need ~90% coverage** (`bin/check-diff-coverage.sh`). Deletion
   waves reduce the denominator; do not let a floor in the parent `pom.xml` drift
   downward as a side effect.
6. **No commits or pushes without an explicit request.**

## Related

- `docs/design/design-system.md` — the Quiet Instrument spec; the surviving
  screens stay on it.
- `docs/superpowers/plans/2026-08-10-design-consistency-pass.md` — the pass that
  preceded this one.
