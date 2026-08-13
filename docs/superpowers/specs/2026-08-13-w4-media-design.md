# W4 — Media: bulk upload and alt text

**Date:** 2026-08-13
**Status:** approved (Jake, 2026-08-12 — "Bulk media upload: Add" and "Media alt
text: Add" in the program design; this spec settles the how)
**Parent:** `2026-08-12-minimalism-program-design.md`
**Migration:** `V024`
**Predecessor:** W1–W3 complete, green, and pushed.

## What this wave is

Two additions to the media subsystem plus one deletion carried over from W2.
For a photography blog — which is half of what this fork exists for — these are
the two things missing that an author notices every single session.

| # | Change | Direction |
|---|---|---|
| 1 | Alt text on media files | add (`V024` adds `mediafile.alt_text`) |
| 2 | Bulk upload | add (UI only; the controller already loops) |
| 3 | `MediaFile.sharedForGallery` / `mediafile.is_public` | delete (`V024`) |

## 1. Alt text

### The problem, stated precisely

There is no alt-text field. Every alt attribute the system emits today falls
back to `MediaFile.getName()`, which **is the uploaded filename**:

- `ImageShortcode:114` — `attributes.get("alt")` if the author typed one into
  the shortcode, else `media.getName()`.
- `GalleryMarkup:174` — `image.getName()`, unconditionally. A gallery image has
  no way to carry alt text at all.

So a screen reader on a portfolio page announces "IMG_4821.jpg", forty times.
That is worse than no alt attribute: it is noise presented as content.

### The change

`MediaFile.altText` (new column, `MediaFileBean`, `MediaFileWrapper`), edited on
`MediaFileEdit.jsp` beside Description, and consumed as the middle link of an
explicit chain:

```
explicit shortcode alt=  →  mediaFile.altText  →  mediaFile.name
```

**The filename stays as the last resort rather than becoming `alt=""`.** An
empty alt means "decorative, skip me", which is the wrong claim about a
photograph on a photography blog — the honest states are "described" and "not
described yet", and silently converting the second into "decorative" hides work
instead of surfacing it. Which is what the next part is for.

### Surfacing what is missing

`MediaFileView.jsp`'s grid gets a quiet marker on any **image** with no alt
text. Without it the feature is a field nobody remembers to fill: an author has
no way to find the forty images that need attention, so they fix none of them.

Marker only — not a nag, not a blocking validation, no count in the page title.
The rule this follows is the design system's "invitations, not shrugs": it says
where the work is, and does not scold.

Non-images are excluded. Alt text on a PDF is meaningless and marking one would
train the eye to ignore the marker.

### Where it is NOT

**Not on `MediaFileAdd.jsp`.** Alt text is per-image by definition, and the
whole point of part 2 is uploading thirty files at once; a single shared alt box
would write the same sentence onto all thirty, which is worse than leaving them
blank — it is thirty *wrong* descriptions that the missing-alt marker would then
report as done.

**Not a change to `#showResponsiveImage`'s signature.** Its `$alt` parameter
stays caller-supplied; the four theme callers pass `$entry.title` for a featured
image, which is correct in a card context and is not the media file's own
description of itself.

## 2. Bulk upload

The server already does this. `MediaFileAddController.save` takes
`MultipartFile[]` and loops, and `spring.servlet.multipart.max-request-size` is
1GB. The gap is entirely in the form: `MediaFileAdd.jsp` renders **five fixed
`<input type="file">` controls**, none carrying `multiple`, so the ceiling is
five files per trip and each needs its own click through a file dialog.

Replace them with one `multiple` input plus a drop zone, and list what is
selected — count, names, total size — before the author commits to the upload.

### Removing the Name field

`MediaFileAdd.jsp` has a Name field. It does nothing: `save()` calls
`bean.copyTo(mediaFile)` (which sets the name from the bean) and then
immediately overwrites it with the uploaded filename at line 123. The field has
a block of JavaScript behind it that fills it from the chosen file and writes
the literal string "multiple names" when there is more than one — elaborate
maintenance of a value the server discards.

It goes, with its JavaScript. Description, Copyright and Tags stay: those
genuinely do apply to every file in the batch through `copyTo`, and "tag all
thirty of these `iceland`" is the main reason to batch in the first place.

### What is deliberately not built

No per-file progress bars, no chunked/resumable upload, no client-side
resizing, no AJAX. One form post of N files, the same request shape the server
already handles. A progress UI means an upload endpoint that reports progress,
which means state per in-flight upload; that is a different feature and nobody
asked for it.

## 3. Deleting `sharedForGallery`

`MediaFile.isSharedForGallery` + its accessors + the `is_public` column in
`MediaFile.orm.xml`. W2 deleted its last reader (the checkbox and the endpoint
behind it) and deferred the column here. Nothing in `app/src`, `it-selenium` or
`bin` references it now except the mapping itself.

`V024` drops the column. Any `true` values are discarded deliberately — the
flag has meant nothing since W2.

## Error handling

- **A batch is not a transaction, and must not pretend to be one.** The
  existing loop already continues past a failed file and collects errors; that
  behaviour is right and stays. What changes is that a partial failure is now
  *likely* rather than exotic (thirty files, one over quota), so the outcome has
  to name which files landed and which did not, rather than reporting a single
  "error uploading" and leaving the author to compare directories by hand.
- **Quota is enforced per file inside `createMediaFile`.** A batch that crosses
  `uploads.dir.maxsize` partway through uploads the files before the crossing
  and refuses the rest. That is the existing behaviour and stays; the point is
  that the message must make it legible.
- Alt text is free text with no format to get wrong. It is stored raw and
  escaped at every emission point, the same as `description` — `MediaFileWrapper`
  runs `HTMLSanitizer.conditionallySanitizeText`, `ImageShortcode`/`GalleryMarkup`
  escape into the attribute.

## Testing

- `SchemaMigrationTest` covers `V024` (discoverability, shape, idempotency).
- **`it-selenium/src/test/resources/seed-it-data.sql` must be checked in the
  migration task.** This is the third wave running: it is raw SQL with no
  compile-time or JPA check, and a migration that drops a column it writes fails
  the entire browser suite at fixture load, not at any test.
- Unit: the alt chain at both emission points (explicit → altText → name), the
  bean round trip, the wrapper's sanitisation, and a JSP-reading test pinning
  that the add form carries `multiple` and no longer carries the Name field.
- Browser: `MediaBulkUploadIT` — upload three files in one post, assert all
  three land; set alt text on one and assert it reaches the rendered page
  through both `[image]` and `[gallery]`; assert the missing-alt marker appears
  for an image without it and not for one with it.
- Existing `GalleryIT` and `MediaCropIT` must stay green — `GalleryIT` has a
  known upload race (CLAUDE.md, CI section).

## Definition of done

Both suites green. One file input, `multiple`, with a drop zone; no Name field
on the add form. An image carries its own alt text through `[image]`,
`[gallery]` and the media grid's marker. `grep -rn sharedForGallery app/src`
returns nothing. CLAUDE.md's Media Pipeline section records the alt chain and
why the filename is still the last fallback.
