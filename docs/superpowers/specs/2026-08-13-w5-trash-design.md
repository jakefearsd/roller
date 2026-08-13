# W5 — Soft delete / trash

**Date:** 2026-08-13
**Status:** approved (Jake, 2026-08-12 — "Soft delete / trash: Add", sequenced
last with a recorded trade-off; this spec settles the how)
**Parent:** `2026-08-12-minimalism-program-design.md`
**Migration:** `V025`
**Predecessor:** W1–W4 complete and green.

## The recorded trade-off, and what this spec does about it

The program design says this out loud:

> Soft delete is the one item on this list that makes the system *bigger*. It
> adds a status dimension every entry query path must respect, which is the
> opposite of the program's direction. Jake chose it with that stated. It is
> sequenced last so that it is the cheapest thing to drop if it starts
> spreading.

So the design goal is not "build trash". It is **build trash that does not
spread**. Everything below is chosen to keep the new dimension in as few places
as possible, and the count is the headline result: **two**.

## Why a fifth `PubStatus` rather than a `deleted` flag

The obvious shape is `weblogentry.deleted_at IS NULL` on every query. That is
exactly the spreading the trade-off warns about: seven named queries plus a
dynamic query builder plus twenty-three call sites, every one of which has to
remember a condition that fails *open* — forget it and deleted entries come
back, silently, on a public page.

`WeblogEntry.status` is already a `PubStatus` enum that every query either
filters on or deliberately does not. Adding **`PubStatus.TRASHED`** means every
query that names a status excludes trash *automatically and by construction* —
nobody asks for TRASHED, so nobody gets it. The dimension does not spread
because it is not a new dimension; it is a new value in an existing one.

That leaves only the queries that name **no** status. There are five, and four
of them are correct to leave exactly as they are:

| Query | Used by | Should it see trash? |
|---|---|---|
| `WeblogEntry.getByCategory` | `isWeblogCategoryInUse` | **Yes.** A trashed entry still holds its category; deleting the category out from under it would leave nothing to restore into. |
| `WeblogEntry.getByWebsite&Anchor` | anchor uniqueness on create | **Yes.** A trashed entry still occupies its anchor. Excluding it lets a new entry take the anchor and collide the moment the old one is restored. |
| `WeblogEntry.getByWebsite&AnchorOrderByPubTimeDesc` | permalink lookup | **Yes**, harmlessly — the caller renders only `isPublished()` entries and `TRASHED != PUBLISHED`, so a trashed entry 404s at its permalink without this query knowing anything about trash. |
| `WeblogEntry.getByWebsite` | weblog-deletion cascade | **Yes.** Deleting a weblog must take its trash with it. |
| `getWeblogEntries(WeblogEntrySearchCriteria)` | everything else — 23 call sites, 11 of which set no status | **No.** This is the one place that changes. |

So the entire query-side change is **one default on one criteria object**.

## The two places

### 1. `WeblogEntrySearchCriteria` excludes trash by default

A new `includeTrashed` flag, default **false**, which appends
`AND e.status <> TRASHED` whenever no explicit status is set. Only the trash
screen sets it true.

**The default is the whole safety property.** A new caller that thinks about
nothing gets the safe behaviour; seeing trash requires asking. The inverse
default — remember to exclude — is the design that fails open onto a public
page, and it is why this is not a `deleted_at` column.

### 2. `BaseController.removeEntryWithIndex` becomes "move to trash"

That method is already the single deletion seam the whole authoring UI goes
through: `EntriesController`'s bulk delete and `EntryRemoveController`'s two
paths all call it, and it already exists precisely so the Lucene index cannot
be orphaned. It becomes: set `TRASHED`, stamp `trashedAt`, save, **and remove
from the search index exactly as it does today** — a trashed entry must not be
findable by site search.

`JPAWeblogEntryManagerImpl.removeWeblogEntry` stays a real, permanent delete.
It is what "delete forever" and the weblog-deletion cascade call.

## What the author sees

A **Trash** item on the editor menu, listing trashed entries newest-first with:

- **Restore** — status becomes `DRAFT`, `trashedAt` cleared, re-indexed.
- **Delete forever** — the existing hard delete, with a confirm.
- **Empty trash** — hard-deletes everything currently in it, with a confirm
  naming the count.

**Restore always goes to DRAFT, never back to PUBLISHED**, and that is a
safety property rather than a limitation: an undelete that silently republishes
to the world — to feeds, to the sitemap, to anyone subscribed — is a worse
outcome than making the author click Publish. It also means no column is needed
to remember the pre-trash status, which keeps `V025` to one nullable timestamp.

The Entries screen gains nothing except that "Delete" now says what it does.
The count of trashed entries is **not** shown anywhere except on the Trash
screen itself; a badge on a menu item is a nag about work that does not need
doing.

## Purge

`entry.trash.retention.days`, a **runtime** property (Admin Settings), default
**30**. `-1` keeps trash forever; `0` means the trash screen still works but the
next sweep empties it.

Purging runs from the existing scheduled-task machinery alongside
`ScheduledEntriesTask`, hard-deleting entries whose `trashedAt` is older than
the retention. It goes through the same `removeWeblogEntry` as "delete
forever", so there is one permanent-deletion path, not two.

Promoted-property rule (CLAUDE.md, Configuration scope): this is a **new**
runtime property, not a promotion, so the two-files trap does not apply — but
it must genuinely re-read per sweep rather than latch a value in `init()`.

## Scope: entries only

**Not pages.** `WeblogPage` has its own status enum and its own screen, there
are few of them, and each is a deliberate object an author is unlikely to
delete by accident. Adding trash there doubles this wave for a case that does
not hurt.

**Not media files.** Deleting a photograph is the most destructive thing in
this system and the most tempting candidate — but a soft-deleted media file
still occupies disk, still counts against `uploads.dir.maxsize`, still has a
rendition ladder and a thumbnail, and is still reachable at its media URL
unless every one of those paths learns about trash. That is precisely the
spreading the trade-off exists to prevent. Recorded here as deliberately out,
not overlooked.

## Error handling

- **Trashing is a status change, not a content change.** Entry revisions
  snapshot pre-save title/text/summary on content-changing saves; trashing
  changes none of the three, so it must not create a revision. Verify rather
  than assume.
- **`roller_event`**: trashing a published entry emits nothing.
  `ENTRY_PUBLISHED` is gated on the post-load status snapshot, and
  `PUBLISHED → TRASHED` is not a publish. Restoring to DRAFT emits nothing
  either; publishing afterwards emits once, correctly.
- **A trashed entry opened by id in the editor.** `lookupEntry` is a global
  by-id lookup with an ownership check and no status filter, so a bookmarked
  editor URL still resolves a trashed entry. It must redirect to the trash
  screen with a message rather than presenting an editable form whose Save
  would resurrect the entry into DRAFT by a side door.
- **Purge is best-effort and must not fail a request** — it runs off the
  scheduler, and a failure to purge is logged, not propagated.
- **Emptying trash is not undoable and must say so** in the confirmation, with
  the count.

## Testing

- `SchemaMigrationTest` covers `V025`.
- **`it-selenium/src/test/resources/seed-it-data.sql`** — checked in the
  migration task. `V025` only adds a column, so the usual failure mode does not
  apply, but the check is cheap and the habit is why W4 caught nothing.
- Unit: the criteria default (a trashed entry is absent unless asked for, at
  the manager level, which is the assertion that matters); trashing sets status
  and stamp and de-indexes; restore goes to DRAFT and re-indexes; purge
  respects the retention including `-1`; a trashed entry still blocks its
  category and still holds its anchor.
- **The regression that matters most**: a trashed entry must be absent from
  the home page, its permalink, the Atom feed, the sitemap and site search.
  Prove each; four of the five are free by construction, and a test that says
  so is what stops someone "simplifying" the default later.
- Browser: `TrashIT` — delete an entry from Entries, find it in Trash, confirm
  it is gone from the public page, restore it, confirm it comes back as a
  draft and not as a published post.
- `RouteSweepIT` gains the new route; `Routes.java` needs its entry and its
  content-tile marker in the same commit.

## Definition of done

Both suites green. An entry deleted from the Entries screen is recoverable and
is simultaneously absent from every reader-facing surface. `grep` for the new
status in query paths shows the exclusion living in exactly one place.
CLAUDE.md records why it is a `PubStatus` value rather than a `deleted_at`
column, why restore goes to DRAFT, and which four unfiltered queries
deliberately still see trash.
