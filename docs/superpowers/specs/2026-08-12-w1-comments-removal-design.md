# W1 — Comments removal

**Date:** 2026-08-12
**Status:** approved (Jake, 2026-08-12)
**Parent:** `2026-08-12-minimalism-program-design.md`
**Migration:** `V022`

## The decision, and the reason behind it

Delete the comment subsystem whole. Not disable, not hide — delete.

The reason it is safe to delete rather than repair is that **it is already
unreachable by any real reader**:

- `Weblog.requireAuthenticatedComments` defaults to `Boolean.TRUE`
  (`Weblog.java:75`), and `V013` created the column `DEFAULT true NOT NULL`.
- `CommentServlet:231` enforces it server-side; `weblog.vm:1092` swaps the form
  for a sign-in prompt.
- Public self-registration was removed in an earlier wave —
  `ProfileController:137` still carries the comment recording it.

So a reader of a rental guide or a photography portfolio cannot comment unless
an administrator hand-creates them an account. Nobody will ever do that. The
subsystem serves no one, and the reader channels that *do* work — the contact
form and the newsletter — already exist and are already wired to
`roller_event`.

There is no spam filter to lose: `ApprovalStatus` is APPROVED/DISAPPROVED/
PENDING, and marking a comment as spam already means deleting it.

## Scope

### Deleted outright

**Business / persistence**

- `pojos/WeblogEntryComment.java`, `pojos/CommentSearchCriteria.java`,
  `pojos/wrapper/WeblogEntryCommentWrapper.java`,
  `resources/.../pojos/WeblogEntryComment.orm.xml`
- `business/plugins/comment/` (the whole package, incl.
  `WeblogEntryCommentPlugin`)
- `ui/rendering/plugins/comments/` (`CommentAuthenticator`,
  `DefaultCommentAuthenticator`, `CommentAuthenticatorUtils`)

**Rendering**

- `ui/rendering/servlets/CommentServlet.java`,
  `ui/rendering/servlets/CommentAuthenticatorServlet.java`
- `ui/rendering/util/WeblogCommentRequest.java`,
  `ui/rendering/util/WeblogEntryCommentForm.java`
- `ui/rendering/pagers/CommentsPager.java`
- `templates/feeds/{weblog,site}-comments-{atom,rss}.vm` (4 files)
- `templates/weblog/popupcomments.vm` **and** `themes/base.css` — `base.css` is
  linked from nowhere else (`popupcomments.vm:10` is its only reference), so it
  dies with it. *This closes the "base.css has non-token colors" item by
  deletion rather than by fixing them.*

**Admin / authoring UI**

- `ui/controllers/editor/CommentsController.java`, `CommentsBean.java`
- `ui/controllers/admin/GlobalCommentManagementController.java`,
  `GlobalCommentManagementBean.java`
- `ui/controllers/ajax/CommentDataServlet.java`
- `ui/controllers/pagers/CommentsPager.java`
- `jsps/editor/Comments.jsp`, `jsps/editor/CommentsSidebar.jsp`
- `webapp/images/comment.png`

**Tests**

- `WeblogEntryCommentFormTest`, `WeblogCommentRequestTest`, `CommentIT`

### Edited

**Entity fields → `V022` drops the columns**

| Table | Columns dropped |
|---|---|
| `roller_comment` | table dropped whole, with its two indexes |
| `weblog` | `allowcomments`, `emailcomments`, `defaultallowcomments`, `defaultcommentdays`, `commentmod`, `comment_auth_required` |
| `weblogentry` | `allowcomments`, `commentdays` |

`roller_audit_log.comment_text` is **not** a comment column — it is the audit
log's change note. It must not be touched. Any migration or sweep that matches
on the string `comment` has to exclude it explicitly.

Corresponding fields come off `Weblog` (6), `WeblogEntry` (2), both wrappers,
`WeblogConfigBean`, and `EntryBean.commentCount`.

**Manager surface** — `WeblogEntryManager` loses its comment methods
(`getComments`, `getComment`, `saveComment`, `removeComment`,
`getCommentCount()`, `getCommentCount(Weblog)`, `applyCommentDefaults`), plus
the JPA impl and named queries. `SiteModel.getCommentCount()` and
`Weblog.getCommentCount()` go with them.

**`GenericThrottle` survives — do not delete it.** It looks like comment
infrastructure (`CommentServlet:77,130` is its most visible user) but it is
shared by three endpoints that all stay:

| Caller | Its own properties |
|---|---|
| `ContactController:335-337` | `contact.throttle.*` |
| `NewsletterController:282-284` | `newsletter.subscribe.throttle.*` |
| `PasswordResetController:340-342` | `passwordreset.throttle.*` |

Each namespace is independent, so dropping `comment.throttle.*` cannot affect
them. `util/GenericThrottle.java` and `GenericThrottleTest` both stay; only the
`commentThrottle` field and its construction go, with `CommentServlet`.

**Velocity** — `weblog.vm` loses `#showWeblogEntryComments`,
`#showMobileWeblogEntryComments`, `#showWeblogEntryCommentForm` and the
`$entry.commentsStillAllowed` branches. All three themes lose their comments
section from `permalink.vm` and the matching CSS block (`qj-comments`,
`pf-comments`, `tg-comments` — 20/17/1 lines respectively).

**Entry editor** — the rail's Comments drawer goes. This retires the
`#collapseAdvanced` id whose only remaining consumer was `CommentIT`; since that
test is deleted in this wave, **the pinned-contract note in CLAUDE.md must be
removed in the same commit**, not left describing a selector that no longer
exists.

**Main menu** — `MainMenu.jsp:129-136` loses the "Manage comments" button and
its `${perms.weblog.commentCount}` badge.

**Menu** — `editor-menu.xml` loses the `comments` item; `admin-menu.xml` loses
global comment management.

**Runtime properties** — the whole `commentSettings` display group:
`users.comments.enabled`, `users.comments.htmlenabled`,
`users.comments.plugins`, `users.comments.emailnotify`,
`users.moderation.required`, `comment.throttle.enabled`. `comment.throttle.*`
sizing properties come out of `roller.properties` too. `PromotedRuntimeProperty
Test`'s list of promoted properties drops `comment.throttle.enabled`.

**i18n** — 47 keys in the base bundle plus their translations across all seven
translated bundles. `MessageKeyTest`'s `KNOWN_DYNAMIC_KEY_COUNT` ratchet is
adjusted in the same commit.

**Browser suite** — `Routes.java` loses three entries
(`globalCommentManagement.rol`, `globalCommentManagement!query.rol`,
`comments.rol`). `GlobalConfigMatrixIT.switchingCommentsOffSiteWideRefusesThem
AtTheServlet` is deleted along with its `postCommentDirectly` helper.
`WeblogConfigMatrixIT`'s comment-approval test is deleted; its remaining
per-weblog tests stay.

## Architecture note: what deliberately survives

`roller_event` and the contact/newsletter endpoints are untouched. They are the
reader-interaction channel now, and they already work for anonymous readers —
which is precisely what comments do not.

## Error handling

Nothing gains a new failure mode; the wave only removes paths. Two existing
behaviours must be preserved and verified:

1. `weblog.lastModified` bumping. `saveComment`/`removeComment` were two of the
   callers that bumped it, which is how `WeblogPageCache` (no CacheHandler,
   lazy expiry) noticed a change. The remaining callers — entry save, template
   save, page save, theme switch — must still bump it. This is easy to break by
   deleting a shared helper along with its comment-only callers.
2. Requests to the old comment endpoints should 404 through the normal unknown-
   path handling, not 500. `WeblogRequestMapper` forwards unknown single-segment
   paths to the page servlet, so `/<handle>/comments` style URLs must be
   confirmed to land on a page 404 rather than an error page.

## Testing

- Unit: the two deleted test classes go; `SchemaMigrationTest` covers `V022`'s
  discoverability, shape and idempotency; `ReflectionTest`/`EqualsContractTest`/
  `PojoComparatorTest` need their comment entries removed.
- `RouteSweepIT` must stay green with three fewer routes.
- Full browser suite green — `ThemeMatrixIT` in particular, since all three
  themes' `permalink.vm` changed.
- Manual: publish an entry, load its permalink under each theme, confirm no
  empty comments section and no console error.

## Definition of done

`mvn clean install` green; `mvn verify -Pit` green; `grep -ri comment` over
`app/src/main` returns only `roller_audit_log.comment_text`, ordinary code
comments, and the `commonmark`/`Comparator` style false positives; CLAUDE.md's
Comments section replaced with a one-paragraph record that the subsystem was
removed in W1 and is not coming back.
