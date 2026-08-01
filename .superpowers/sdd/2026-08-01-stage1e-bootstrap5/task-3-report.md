# Task 3 report — Content batch A (Bootstrap 5)

(Written inside the worktree — the sandbox refused writes to the shared-checkout
path `/home/jakefear/source/roller/.superpowers/sdd/...`, so this lives at the same
relative path under the worktree instead.)

Worktree: `worktree-agent-ae5da1de0c976fc9b`, branched from `3f0f9717e` (pre-Task-1/2).
Per coordinator instruction mid-task, ran `git merge master --no-edit` (fast-forward,
`3f0f9717e..318aeedc7`) before making any further edits, since the worktree had been
created before Tasks 1-2 landed. One in-progress edit to EntryEdit.jsp made before the
merge notice was stashed and dropped (not reapplied) so all batch-A work here was done
fresh against the merged baseline. Verified post-merge: `EntryEditor.jsp` has no
`c:choose`/editor-id branch, and `head.jsp` references `bootstrap/5.3.8` +
`bootstrap-icons/1.13.1`.

Files touched (all under `app/src/main/webapp/WEB-INF/jsps/editor/`):

- **EntryEdit.jsp** — form-horizontal removed; all `form-group` → `row mb-3` +
  `col-form-label`/`offset-sm-3`; status `label label-*` → `badge bg-*`;
  `form-control-static` → `form-control-plaintext`; `panel-group`/`panel*` → `card`/
  `card-header`/`card-title`/`card-body` for the plugins and advanced-settings
  accordion panels (`#panel-plugins`, `#panel-settings` ids kept); `data-toggle`/
  `data-target`/`data-parent` → `data-bs-*` (incl. the `:23` jQuery selector for the
  collapse-anchor click-guard); `input-group-addon btn` → `input-group-text`;
  `glyphicon-calendar` → `bi bi-calendar`; entryPlugins/rightToLeft/pinnedToMain
  checkboxes wrapped in `form-check`/`form-check-input`/`form-check-label`; delete
  modal converted (`form-horizontal` dropped, `form-group`→`row mb-3`,
  `form-control-static`→`form-control-plaintext`, `data-dismiss`→`data-bs-dismiss`,
  nonsense `btn btn-default btn-primary`→`btn btn-secondary`); `fullPreview` button
  `btn-default`→`btn-secondary`; `.modal({show:true})`→
  `bootstrap.Modal.getOrCreateInstance(...).show()`. All ids (`#entry`,
  `#delete-entry-modal`, `#entry_bean_permalink`, `#accordion`) and action URLs
  preserved.
- **EntryEditor.jsp** (post-Task-1 Summernote-only file) — summary `panel*` → `card`;
  collapse `data-toggle/target` → `data-bs-*`; media-insert modal close button
  `class="close"` → `btn-close` (dropped the now-redundant inner `&times;` span);
  footer `btn btn-default` → `btn btn-secondary` with `data-bs-dismiss`; both
  `.modal({show:true})`/`.modal("hide")` calls → `bootstrap.Modal.getOrCreateInstance(...)`.
  `#edit_content` id untouched.
- **EntrySidebar.jsp** — glyphicon→bi swaps only: `lock`→`bi-lock`,
  `edit`→`bi-pencil-square`, `book`→`bi-book`, `time`→`bi-clock`.
- **Entries.jsp** — both `<ul class="pager">` blocks (top "Newer/Older", bottom
  "Older/Newer", labels preserved as-is including the existing app quirk of reversed
  wording) → `d-flex justify-content-between` with `btn btn-outline-secondary`
  prev/next links; `glyphicon-edit`/`glyphicon-trash` in the table → `bi
  bi-pencil-square`/`bi bi-trash` with `data-toggle="tooltip"` → `data-bs-toggle`
  (left inert per plan — Task 6 owns the tooltip decision); delete modal converted
  same as EntryEdit's (form-horizontal dropped, row/col-form-label,
  form-control-plaintext, btn-default+btn-primary nonsense combo→btn-secondary,
  data-bs-dismiss); `.modal({show:true})`→`bootstrap.Modal.getOrCreateInstance`.
- **EntriesSidebar.jsp** — category `<select class="form-control">`→`form-select`;
  start/end-date `control-group`/`controls` (BS2 fossil, no BS3/5 meaning, dropped)
  → `mb-3`; `input-group-addon btn`→`input-group-text`;
  `glyphicon-calendar`→`bi bi-calendar` (×2); status/sortBy radio loops wrapped in
  `form-check`.
- **Categories.jsp** — `glyphicon-edit`/`glyphicon-trash`→`bi` equivalents;
  edit-modal form `form-horizontal` dropped, `form-group`→`row mb-3`; cancel button
  `data-dismiss`→`data-bs-dismiss`; delete-modal form same treatment plus
  `targetCategoryId` select `form-control`→`form-select` and
  `btn-default`→`btn-secondary`; three `.modal(...)` JS calls (show/hide/show) →
  `bootstrap.Modal.getOrCreateInstance(...)`.
- **CategoriesSidebar.jsp** — `glyphicon-plus`→`bi bi-plus-lg`;
  `.modal({show:true})`→`bootstrap.Modal.getOrCreateInstance(...).show()`.
- **Comments.jsp** — both pager `<ul class="pager">` blocks → `d-flex
  justify-content-between` prev/next buttons (labels preserved). The legacy
  `float:left/right` `.tablenav` layout inside the form was left alone — out of the
  recipe's listed scope for this file, no census line ref for it.
- **CommentsSidebar.jsp** — `form-group`→`mb-3`; `input-group-addon
  btn`→`input-group-text`; `glyphicon-calendar`→`bi bi-calendar` (×2); `div
  class="radio"`→`form-check`/`form-check-input`/`form-check-label` (this was the
  one census-noted `div.radio` instance); submit `btn-default`→`btn-secondary`.
- **WeblogConfig.jsp** — largest file in the batch: `form-horizontal` dropped on
  both `<form>`s; all 12 `form-group`→`row mb-3`; every `col-sm-offset-3
  col-sm-9`→`offset-sm-3 col-sm-9`; all 5 `<select class="form-control">`→
  `form-select`; all 9 bare `<label><input type="checkbox">...</label>` patterns
  (active, enableMultiLang, showAllLangs, allowComments, emailComments,
  moderateComments, defaultAllowComments, applyCommentDefaults, enableBloggerApi,
  plus the pluginsList forEach checkboxes) wrapped in `form-check`. No editor-page
  `<select>` present — Task 1's refactor already removed it upstream of this merge.
- **MediaFileAdd.jsp** — `form-horizontal` dropped; 5 `form-group`→`row mb-3`;
  directory `<select>`→`form-select`; `sharedForGallery` checkbox→`form-check`;
  file-location `panel panel-default`→`card`/`card-header`/`card-title`/`card-body`;
  upload button `btn-default`→`btn-secondary`.
- **MediaFileView.jsp** — 3 `<select class="form-control">`→`form-select`
  (view-folder, sort-by, move-target); `#imageGrid` `panel
  panel-default`/`panel-body`→`card`/`card-body`; both `.modal(...)` calls
  (view-image lightbox show/hide) → `bootstrap.Modal.getOrCreateInstance(...)`.
- **MediaFileAddSuccess.jsp** — `form-horizontal` dropped (no form-groups present,
  form only had hidden inputs); all 3 `panel panel-default`/`panel-body` pairs
  (image-select rows, file-select rows, no-enclosure row) → `card`/`card-body`;
  "upload more" button `btn-default`→`btn-secondary`.
- **MediaFileSidebar.jsp** — `glyphicon-picture`→`bi bi-image`;
  `glyphicon-folder-open`→`bi bi-folder2-open`; search-form 3 `<select>`s→
  `form-select` (kept the 3 text `<input>`s on `form-control`, corrected a
  first-pass `replace_all` mistake that had wrongly turned those inputs into
  `form-select` too — caught and fixed before running tests).
- **TemplateEdit.jsp** — `form-horizontal` dropped; 4 `form-group`→`row mb-3`;
  bare `alert-danger`/`alert-success` divs (`#no_link`/`#good_link`) → `alert
  alert-danger`/`alert alert-success` (the census-flagged bare-alert bug); save
  button `btn-default`→`btn-secondary`; advanced-settings `panel-group`/`panel*`→
  `card`/`card-header`/`card-title`/`card-body` (`#panel-plugins` id kept); 3
  offset checkboxes (hidden, navbar, autoContentType) wrapped in `form-check`;
  template-language `<select>`→`form-select`; `data-toggle/target`→`data-bs-*`.
- **Templates.jsp** — `form-horizontal` dropped (no form-groups, table-only form);
  `glyphicon-trash`/`glyphicon-lock`→`bi bi-trash`/`bi bi-lock`.

## Notes / judgment calls
- Removed the vestigial Bootstrap-2 `controls`/`control-group` classes wherever they
  co-occurred with the converted grid (`col-sm-9 controls`, etc.) — they have no
  BS3/BS5 styling effect (confirmed against `roller.css`), not app-owned, and the
  plan's own recipe already treats the `form-group`/`control-label` fossils as
  in-scope cleanup.
- `panel-group` divs (`#accordion` in EntryEdit.jsp and TemplateEdit.jsp) had their
  `panel-group` class dropped entirely (no BS5 equivalent needed since the chevron
  CSS fix already landed in Task 2 keyed off `.collapsed`) — the `id="accordion"`
  was kept for stability.
- `form-check` markup nests the input inside `form-check-label` (rather than the
  textbook BS5 sibling pattern with `for=`) because none of these checkboxes/radios
  had ids to associate via `for` — nesting preserves click-to-toggle semantics with
  zero markup/id churn.
- Icon name choices where the census didn't specify a target (only "per the §1 glyph
  list"): `glyphicon-edit`→`bi-pencil-square`, `glyphicon-folder-open`→
  `bi-folder2-open`, `glyphicon-picture`→`bi-image`, `glyphicon-plus`→`bi-plus-lg`,
  `glyphicon-time`→`bi-clock`. Batch B may pick different but analogous names for
  its own files (e.g. MainMenu's `glyphicon-edit`-adjacent icons); not reconciled
  here since batches are file-disjoint per the dispatch.
- Left `Comments.jsp`'s `.tablenav` `float:left`/`float:right` inline styles alone —
  not listed in the census/recipe for this file.
- Inert `data-toggle="tooltip"` attributes in Entries.jsp (now `data-bs-toggle`) were
  renamed, not removed/wired — per the plan, that decision belongs to Task 6.

## Verification
`mvn -ntp -pl app test` (worktree, no `-Pit` per dispatch): **BUILD SUCCESS**, 2197
tests, 0 failures/errors/skipped. This run compiles+precompiles the JSPs
(jspc-maven-plugin, `process-classes`) and includes `WebjarReferenceTest` and
`MessageKeyTest`, both green. A grep sweep across all 16 files for `glyphicon`,
bare `panel-`/`panel-heading`/`panel-body`/`panel-title`/`panel-collapse`/
`panel-group`, `btn-default`, non-bs `data-toggle=`/`data-dismiss=`/`data-target=`/
`data-parent=`, `form-group`, `control-label`, `col-sm-offset`, `col-xs-`,
`form-control-static`, `form-horizontal`, `class="close"`, `class="label label-`,
`input-group-addon`, `<ul class="pager">`, jQuery `.modal(`, and `<select
class="form-control">` came back empty for all target files.

IT suite intentionally not run here (worktree convention per dispatch — coordinator
runs it post-merge).
