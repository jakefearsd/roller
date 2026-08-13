# W3 — Autosave / draft recovery

**Date:** 2026-08-13
**Status:** approved (Jake, 2026-08-12 — "Autosave: Add" in the program design;
this spec settles the how)
**Parent:** `2026-08-12-minimalism-program-design.md`
**Migration:** none
**Predecessor:** W1 (comments, `V022`) and W2 (eight fossils, `V023`), both
complete and green. This is the first *additive* wave.

## The decision: local drafts, not server autosave

Autosave in a CMS usually means "POST a draft to the server every N seconds."
That is not what this wave builds, and the reason is specific rather than
squeamish.

**A server autosave collides with entry revisions.** `weblogentry_revision`
snapshots the pre-save content of *every* content-changing save, and
`entry.revisions.retention` defaults to **-1: keep everything**. Autosaving on a
timer multiplies saves by one to two orders of magnitude, so it multiplies
revision rows by the same factor. There are only two ways out, and both are
worse than the problem: change the default (a behaviour change nobody asked
for, silently discarding history on installs that want it), or teach the
snapshot to distinguish an autosave from a real save (a new dimension on a
table that exists precisely to record content-changing saves).

**A server autosave of a *new* entry has to invent a row.** Anything anyone
starts typing becomes a draft in the Entries list. That is the opposite of this
program's direction.

**The program reserved no migration for W3**, which is the same conclusion
arrived at from the other end.

**And the failure modes that actually lose work are all same-browser.** Browser
or tab crash; closing the wrong tab; a laptop sleeping and waking to a dead
session. That last one is the interesting case: when the session has expired,
the save POST is intercepted by Spring Security and redirected to the login
page, and everything typed since the last save is gone with no warning. A local
draft survives all four. Cross-device recovery — the one thing a server
autosave buys that this does not — is not a failure mode; it is a different
feature (writing on the train, finishing at the desk), and nobody asked for it.

So: the browser keeps a rolling snapshot of the editor in `localStorage`, and
offers it back when the page reloads with the server's copy behind.

## How it behaves

**While writing.** Every change to the form or the editor schedules a snapshot
2 seconds later (debounced, so a burst of typing writes once). The snapshot
holds the form's authored fields plus a timestamp.

**On load.** The module compares the stored snapshot against the values the
server just rendered.

- No snapshot, or every field matches → nothing happens. (Matching means the
  save went through and the page came back with the saved values; the snapshot
  is stale and is deleted, silently.)
- Any field differs → a recovery bar appears above the title: *"Unsaved changes
  from 14:32. **Restore** · Discard"*.

Comparing **content**, not timestamps, is deliberate. A timestamp comparison
against `entry.updateTime` has to reconcile a browser clock, a server clock and
the weblog's timezone, and gets the answer wrong when any of the three drift.
Content comparison cannot: either the browser is holding something the server
is not, or it is not.

**Restore** puts the snapshot back into the form and the editor, and leaves the
bar's place with a quiet confirmation. It does **not** save — restoring is
giving the author their text back, and what they do with it is theirs.

**Discard** deletes the snapshot and removes the bar.

**Never auto-restore.** Silently overwriting what the server has with an
unreviewed local snapshot is a worse failure than losing the snapshot.

## Scope

Both editors, because both lose work the same way and both already expose the
same three-function seam (`insertMediaFile` / `rollerSetEntryText` /
`rollerGetEntryText`):

- `EntryEdit.jsp` + `EntryEditor.jsp` — entries
- `PageEdit.jsp` — static pages (its own copy of the editor bootstrap, mirroring
  `EntryEditor.jsp`)

New entries and new pages are in scope, keyed `new`. That is the highest-value
case: an unsaved new entry has nothing on the server at all.

## Architecture

One new file, `app/src/main/webapp/theme/scripts/roller-draft.js`, alongside
`roller.js`. A plain static script, **not** a `<%@ include %>` like
`ajax-user.js` — nothing in it needs JSP interpolation, because every
translated string reaches it through `data-` attributes on the bar element the
JSP renders (the same pattern the shortcode Insert menu already uses for
`data-snippet`).

It exposes one entry point:

```js
rollerDraft.install({
  form:           HTMLFormElement,   // the form to watch
  key:            String,            // storage key, built by the JSP
  staleKeys:      [String],          // keys to consume if this page IS them, saved
  exclude:        [String],          // page-specific field names to skip
  bar:            HTMLElement,       // the recovery bar, hidden until needed
  csrfName:       String,            // ${_csrf.parameterName}
  getText:        () => String,      // rollerGetEntryText
  setText:        (String) => void,  // rollerSetEntryText
  onEditorChange: (cb) => void       // registers cb with the editor
});
```

Both JSPs call it with their own form, key and seam functions. Nothing else in
either page changes.

### What is snapshotted

Everything in **`form.elements`** — the browser's own authoritative list of the
controls a given form owns — except a denylist, plus the editor's text (which
lives in EasyMDE, not in the DOM textarea, until submit).

`form.elements` rather than a `querySelectorAll` under the form node, because
`#entry` is `display:contents` and the page carries three *other* forms
(revision restore, newsletter send, delete) that a naive DOM walk could pick up
or miss depending on nesting. `form.elements` gets it right by definition.

The denylist is what must never be restored from a stale snapshot:

| Excluded | Why |
|---|---|
| `_csrf` (whatever `_csrf.parameterName` is) | a stale token fails the POST |
| `bean.id` | identity, not content — restoring it into the wrong page is a data-loss bug |
| `weblog` | ditto |
| `type="file"` | cannot be restored; setting `.value` throws |
| buttons, submits, unnamed controls | not state |
| the editor's own textarea | `bean.text` on entries, `bean.content` on pages — already captured as `snapshot.text` through the seam; capturing it again doubles the snapshot and gives `differs()` a second, possibly-stale copy of the same content. Passed per-editor, because the two name it differently. |
| `bean.status` — **entries only** | see below |

> **Amended during implementation.** The denylist is no longer a fixed list in
> the module: the three universal names stay there, and each editor passes its
> own via an `exclude` option. See the two paragraphs below.

**`bean.status` differs between the two editors, and the whole-wave review
caught why.** On `PageEdit.jsp` it is a visible `<select>` and a real author
choice, so excluding it would lose one — it stays in. On `EntryEdit.jsp` it is
a hidden input, and *restoring* it is inert (the rail's submit buttons carry
`formaction="…!publish.rol"` / `"…!saveDraft.rol"`, and `EntryEditController`
overwrites `bean.status` from that action on every save) — but **comparing** it
is not inert. `doEntryEditSave` mutates the bean and *forwards*, so the page
rendered immediately after a successful Post carries `PUBLISHED` while the
snapshot taken at submit holds `DRAFT`. That difference raised a phantom
"unsaved changes" bar over a save that had just succeeded, and kept raising it
on every later visit — eventually offering days-old text over something newer,
which is precisely the "silently overwriting what the server has" failure this
spec forbids. So `bean.status` is excluded **on entries only**.

That is also why the denylist is by **name**, not by `type="hidden"`. Excluding
hidden inputs wholesale would drop `bean.featuredImageId` and `bean.ogImageId`
— hidden fields, but real author choices made through the image pickers, and
exactly the kind of work that is annoying to redo.

An allowlist was the other option and is the wrong one: the editor keeps
growing (the SEO card, the newsletter box, the focal-point pickers all arrived
after the rail was built), and an allowlist silently stops covering whatever
lands next. A three-name denylist fails safe in the other direction.

Checkboxes and radios snapshot `checked`; everything else snapshots `value`.
Spring's field markers (`_showInNav` and friends) are ordinary hidden inputs and
ride along without special handling.

### The key

```
roller.draft.v1:<contextPath>:<weblogHandle>:<actionName>:<entryId|new>
```

`contextPath` because two Roller installs on one origin would otherwise share
storage. `v1` because a future change to the snapshot shape needs to invalidate
old snapshots rather than misread them.

### Housekeeping

On install, snapshots older than 30 days under the `roller.draft.v1:` prefix
are deleted, whatever page they belong to. Without this, every abandoned draft
in the installation's history stays in the browser forever.

`localStorage` writes throw when the quota is exhausted or when storage is
blocked entirely (Safari private browsing, some enterprise policies). Every
read and write is wrapped: a failure disables the module for the page and
leaves the editor exactly as it is today. Autosave that cannot store must not
break writing.

### One pre-existing bug this wave fixes

`EntryEditor.jsp`'s "warn before leaving" block registers its handlers *inside*
the CodeMirror `change` callback:

```js
rollerEditor.codemirror.on('change', function () {
    var confirmLeaving = function (event) { ... };
    $(window).on("beforeunload", confirmLeaving);
    $("#entry").on('submit', function () { ... });
});
```

Every keystroke adds another `beforeunload` handler and another `submit`
handler. The `beforeunload` duplicates are harmless (they all say the same
thing), but the `submit` handlers accumulate without bound on the form that is
about to be posted. Autosave hooks into exactly this block, so it is rewritten
here: bind once, track a dirty flag. `PageEdit.jsp` carries the same code and
gets the same fix.

The leave-warning **stays**. A draft in `localStorage` is a recovery mechanism,
not a reason to stop telling someone they are about to walk away from unsaved
work.

## Design

The recovery bar follows the Quiet Instrument spec: a single line above the
title, `--accent-quiet` ground, `--line` border, `--radius`, one primary text
action and one quiet one. No icon, no modal, no colour alarm — this is an offer,
not an error. It is the same register as `.empty-state` ("invitations, not
shrugs"), and reuses the existing tokens; `DesignTokenTest` allows no hex
literal that does not trace to the palette, so it uses none.

## Error handling

Nothing here can fail the save path, by construction: the module never touches
the network and never intercepts submit except to clear its own storage. The
three ways it can fail and what each does:

1. **Storage unavailable or full** — module disables itself, editor unaffected.
2. **A stored snapshot that will not parse** (hand-edited, or written by a
   future version) — the key is deleted and treated as absent.
3. **`rollerGetEntryText` missing** (an editor swap that forgot the seam) —
   `install()` returns without binding anything, rather than throwing into the
   page's `$(document).ready`.

## Testing

- **Unit:** a JSP-reading test in the shape of the existing `_showInNav` marker
  test — pin that both editors load `roller-draft.js`, render the bar, and pass
  a key containing the weblog handle. These are the couplings that break
  silently, exactly like the field-marker name did.
- **Browser (`EntryAutosaveIT`, `it-selenium`):** the real coverage.
  1. Type into a new entry's editor, do not save, reload → the bar appears,
     Restore brings the text back.
  2. Save the entry properly, reload → no bar (the stale snapshot was detected
     and dropped).
  3. Discard removes the bar and survives a reload.
  4. The same round trip on `PageEdit`.

  **The reload has to defeat the leave-warning.** The page installs a
  `beforeunload` handler as soon as anything is typed, and an unhandled
  Chrome beforeunload dialog blocks the session. The IT clears
  `window.onbeforeunload` (and jQuery's bound handlers) via `executeScript`
  before reloading. That is testing draft recovery rather than the warning,
  which is the correct seam — the warning has no test today and gains none
  here.

- `RouteSweepIT` markers are untouched: no route, no selector, and no content
  tile changes.
- Known flake to expect, not to chase: `ReferenceError: EasyMDE is not defined`
  on `entryEdit!firstSave.rol` (see CLAUDE.md, CI section).

## Definition of done

Both suites green. Typing into an entry, killing the tab, and coming back
offers the text; saving and coming back does not. Nothing new in the database,
no new route, no new runtime property. CLAUDE.md's "Entry editing" section
records the seam and the reason autosave is local.

## Known limits, recorded rather than fixed

These came out of the whole-wave review and are deliberate, not oversights.

- **Two tabs on the same new entry share one slot.** Both `entryAdd.rol` tabs
  key on `…:entryAdd:new`, so the later debounce overwrites the earlier tab's
  snapshot. Inherent to keying an unsaved thing by `new` — the alternative is
  minting a client-side id per tab, which then has to be reconciled on save.
  Not worth it for a recovery mechanism.
- **A stale key is consumed on text AND title matching.** Text alone was the
  first implementation and was wrong: copy an entry's body into a new-entry tab
  and reloading the *entry* tab would delete the new draft. Two independent
  fields is a much stronger "this draft became this entry" signal. It is still
  a heuristic, and a deliberate one — the exact alternative (only consume on
  the `firstSave` landing) needs a signal the page does not currently carry.
- **Drafts outlive logout.** Nothing clears `roller.draft.v1:*` on sign-out; a
  snapshot lives up to 30 days in the browser profile. On a shared machine the
  next person who can open that weblog's editor is offered the previous
  author's unsaved text. No privilege boundary is crossed — only someone
  already permitted on that weblog sees the bar — but "no server storage" was
  partly a privacy argument, and this is its counterpart.
