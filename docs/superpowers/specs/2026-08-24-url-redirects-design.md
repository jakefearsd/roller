# URL redirects — 301s for URIs that would otherwise 404

**Date:** 2026-08-24
**Status:** implemented (2026-08-24; approved by Jake the same day — feature
agreed with the narrow definition "only URIs that would 404", hit count
required, robust diagnostic logging required. Plan:
`docs/superpowers/plans/2026-08-24-url-redirects.md`)
**Migration:** `V028`

## Why this exists

Everything this fork invested in — per-entry canonical/OG/JSON-LD, sitemaps,
robots, structured travel data, custom domains per weblog, contact-form lead
capture — exists to earn and convert search traffic. But the platform has no
way to keep a URL alive when it changes:

- Rename a page slug and the old URL 404s, taking its accumulated ranking and
  every inbound link with it. `PageBean.copyTo` writes the submitted slug
  straight through, so this is one edit away for any author.
- Migrating an existing site onto Roller — which is exactly what the
  custom-domain feature invites — means every URL from the old site's
  structure (`/2023/05/my-post/`, `/best-cafes.html`) lands on a 404. For a
  site whose value *is* its search equity, that is the difference between a
  migration and a restart.

The vhost wave already established the principle in one place: the path form
301s to the custom domain *specifically* so crawlers transfer ranking. This
spec extends that reasoning to content URLs, and nothing else.

## The narrow definition, and why it is fail-closed by construction

**A redirect rule is consulted only at a point in the code where a 404 has
already been decided.** This is the agreed scope, and it is also the safety
property: a rule can never shadow live content, not because save-time
validation checks for collisions, but because the lookup is structurally
unreachable while anything real is being served. No validator has to know
what "live content" means (page slugs, entry anchors, reserved contexts,
templates served by link); the ordering of the code does.

The consultation seams, named precisely:

| Seam | What reaches it |
|---|---|
| `PageServlet`: `selectTemplate(...) == null` | An unknown or unpublished page slug — the renamed-slug case. |
| `PageServlet`: `rejectionReason(...) != null` | An unknown entry anchor, an unpublished/trashed entry's permalink, an unknown category — the deleted-and-recreated and migrated-permalink cases. |
| `WeblogRequestMapper`: the two `sendError(SC_NOT_FOUND)` sites | Malformed tags shapes and trailing-slash-on-context URLs. |
| `WeblogRequestMapper`: `calculateForwardUrl(...) == null` → `return false` | Multi-segment paths with an unknown context — `/2023/05/foo.html` under a handle or on a vhost. **This is the seam that serves the site-migration case**, and it is the one place where "would 404" is established by argument rather than by construction: the mapper *declines* rather than 404s, and the filter chain continues. The argument: this branch is only reached with a resolved weblog (handle or vhost), and no downstream servlet serves anything under a weblog's namespace — the protected roots (`roller-ui`, `themes`, `webjars`, `api`, …) never reach this branch at all. Because it is an argument and not a construction, it gets its own test at **both context paths** (see Testing). |

All four seams have the weblog already resolved, and all four run behind
`PersistenceSessionFilter`, so the lookup and the hit-count write have a
session without any new plumbing.

**When the resolver itself fails** (store unreachable, unexpected exception):
log and serve the 404 that was already decided. A redirect is a favor granted
on top of a settled outcome, so the safe degradation is the outcome that was
already settled — this is the same "a check that could not run is not a check
that passed" rule as everywhere else, pointed the other way: a favor that
could not be looked up is not a favor owed.

## Data model — `V028__weblog_redirects.sql`

One table, `weblog_redirect`:

| Column | Notes |
|---|---|
| `id` | The usual generated id. |
| `weblogid` | FK to `weblog`. `removeWeblog`'s cascade must delete these rows the same way it takes the weblog's other children. |
| `source_path` | Weblog-relative, normalized (below). Unique per `(weblogid, source_path)`. |
| `target_path` | Weblog-relative, validated (below). |
| `origin` | `'MANUAL'` or `'SLUG_HISTORY'` — so the diagnosis story can distinguish a rule an operator wrote from one a rename minted. |
| `created_at` | |
| `hit_count` | `bigint default 0 not null`. |
| `last_hit_at` | Nullable timestamp. |

`hit_count` alone says *that* something is happening; `last_hit_at` says
*whether it is still happening* — it is what distinguishes a rule the world
depends on from one that went quiet a year ago, and it is the first thing to
look at when a count seems wrong ("10,000 hits — over three years, or since
Tuesday?").

A new small manager, `WeblogRedirectManager`, joins the facade
(`Weblogger.getWeblogRedirectManager()`): `getRedirects(weblog)`,
`saveRedirect`, `removeRedirect`, `resolve(weblog, path)`, `recordHit(id)`.
Folding this into `WeblogManager` was considered and rejected — that class is
already the biggest manager, and the resolve/recordHit pair has callers
(rendering tier) that none of its existing methods have.

## Matching and normalization

- **Weblog-relative and path-only.** The stored `source_path` never contains
  the handle, the context path, or a query string. On the site host the
  request path minus `/<handle>` is matched; on a vhost the full path is. One
  rule therefore behaves identically on both hosts, which is the same
  invariant the render caches keep by keying on the handle.
- **Exact match after normalization**: guaranteed leading `/`, a single
  trailing slash stripped (so `/old-page` and `/old-page/` are one rule —
  migrated sites are inconsistent about this and a 404 over a slash defeats
  the feature's purpose), case preserved. Applied identically at save time
  and at match time, so what is stored is always in matchable form.
- **The query string is never matched and always preserved** on the outbound
  `Location`. UTM tags surviving the redirect is what keeps Umami attribution
  intact for old links; matching on them would multiply rules for no gain.

## The Location is built from the weblog, never the request

`Location` = the weblog's root URL (via `URLStrategy.getWeblogURL`, the same
single method all eleven weblog-content URL builders root through) + the
target path + the request's query string. This inherits custom-domain and
servlet-context-path handling instead of reimplementing either — the vhost
wave found **three separate sites** that hand-built a URL and dropped the
context path, invisible at the root context. This feature does not become the
fourth.

The status is **301**, matching the vhost path-form precedent and for the
same reason: a permanent redirect is what tells crawlers to transfer ranking.

## No chaining

A redirect resolves in exactly one hop, enforced at save time and true at
serve time by construction (the resolver does one lookup and never recurses).

Save-time validation on every rule, within its weblog:

- `target_path` may not equal any existing rule's `source_path`, and
  `source_path` may not equal any existing rule's `target_path` — no rule may
  extend a chain from either end.
- `target_path != source_path`.
- `target_path` must start with `/`, must not start with `//`
  (protocol-relative URLs are how an open redirect sneaks past a
  "starts with slash" check), must not contain `?`, `\`, or control
  characters, and must not carry a scheme. External targets are refused
  outright — a redirect table that can point off-site is a phishing
  primitive, the same class of concern that keeps authored `<form>`s out of
  sanitized content.

## Hit counting

One best-effort `UPDATE weblog_redirect SET hit_count = hit_count + 1,
last_hit_at = now() WHERE id = ?` per served redirect — caught, logged, and
never able to fail the redirect that triggered it, the same convention
`roller_event` writes established. A reader following a stale link gets their
301 even when the bookkeeping write hiccups.

No `roller_event` row per hit: redirect traffic is high-volume and
crawler-heavy, and per-hit rows would bloat the analytics contract's event
table with rows that answer no funnel question. The count answers "how much";
the log (below) answers "what, exactly, and from where".

## Logging — the diagnosis story

**A dedicated named logger, `roller.redirects`** — a deliberate deviation
from this codebase's class-logger convention, for two reasons: its verbosity
is tunable independently of the mapper/servlet DEBUG noise, and every
redirect ever served is one `grep roller.redirects` away in `app.log`.

One INFO line per redirect served, parameterized, carrying:

- the weblog handle, the matched rule id, and its `origin` — so "where did
  this rule come from" is answered in the line itself;
- the requested URI **including the query string** — the path was matched
  without it, but it is evidence (a burst carrying `utm_source=old-newsletter`
  answers "where is this traffic from" instantly);
- the resolved target;
- the **Referer header** — the single most valuable field for the
  "something unexpected" case: it distinguishes Google's index catching up,
  one specific site linking to a renamed page, and this system's own theme
  still emitting an old URL (referer = own domain is the smoking gun);
- the User-Agent — so crawler traffic (Googlebot re-verifies a 301 for
  months) is distinguishable from human traffic at a glance.

Referer and User-Agent are attacker-controlled strings: they go through `{}`
placeholders, never concatenation, and nothing follows them that could eat a
throwable slot.

Rule **mutations** are logged on the same logger at INFO — create and delete,
with rule id, source, target, origin, and the acting user (or `SLUG_HISTORY`
for the automatic hook) — so the audit trail for "who made this rule and
when" does not depend on remembering to check anything.

## Automatic slug history

The highest-value case needs no operator at all. In
`WeblogPageManager.savePage`, when an existing page's slug changes:

1. **Insert** a `SLUG_HISTORY` rule, old slug → new slug.
2. **Collapse** any existing rules whose `target_path` is the old slug to
   point at the new one — otherwise renaming B→C strands an existing A→B rule
   on a 404, and collapsing is also what preserves the one-hop invariant
   under repeated renames.
3. **Delete** any rule whose `source_path` equals the new slug. It could
   never fire (the new slug is live content and the resolver is unreachable
   for live content), but it is dead weight, and deleting it makes an
   A→B→A rename round-trip converge to a clean table instead of accreting
   contradictory rows.

All three run **in the save's own transaction**, deliberately: a rename whose
history row failed to write is a rename that silently killed a URL, which is
the exact failure this feature exists to prevent — so the rename and its
history succeed or fail together.

**There is no entry-side hook, because entry anchors are immutable**: the
editor's `EntryBean` carries no anchor field, and
`JPAWeblogEntryManagerImpl` calls `createAnchor` only when the anchor is
unset. An entry's URL cannot be renamed through any surface, so there is no
rename event to capture. Deleting an entry and recreating it under a
different title produces a *new* anchor, not a rename — covering the old one
is a manual rule. Recorded here so nobody hunts for the missing hook.

## Admin surface: API only, deliberately

A `RedirectsApi` under `ui.restapi.v1`, following every house rule for that
package (`UISecurityEnforced`, weblog-scoped through the shared ownership
path, a resource the caller may not see is 404 never 403, `POST`-scope
required for writes):

- `GET /api/v1/weblogs/{handle}/redirects` — the rules with `hitCount` and
  `lastHitAt`, so the observability half of this spec is readable without a
  database session.
- `POST` to create, `DELETE` to remove. No update verb — a redirect is two
  strings; delete-and-recreate is the update, and it keeps the no-chaining
  validation to one code path.
- `docs/api/README.md` gains the section, since that file is the API's front
  door.

No JSP screen in this wave. This is a single-operator fork whose operator
already works through `bin/roller-api`, the site-migration use case (dozens
of rules) is a script against the API rather than a form filled fifty times,
and the automatic slug-history path has no UI to need. A screen can be added
later without touching the schema or the manager. Recorded as deliberately
out, not overlooked.

No new configuration property either: an empty table is "off", and a kill
switch for a feature that only ever acts on 404s would be a knob without a
failure mode to guard.

## Out of scope

- **Media and resource URLs.** `ResourceServlet` / `MediaResourceServlet`
  404s are untouched. A media redirect would have to reason about rendition
  query params and `Accept` negotiation, and the payoff is small: image URLs
  carry no ranking worth preserving the way content URLs do. This is the
  trash spec's media argument again — the tempting extension is where the
  spreading starts.
- **Patterns and wildcards.** Exact match only. A regex engine is where
  loops, shadowing, and unreviewable rules come back in one feature; a
  migration that needs a thousand mappings needs a script that *emits* a
  thousand exact rules through the API, which is also the only version whose
  hit counts mean anything per-URL.
- **External targets and 410 Gone.** Both refused/absent per the validation
  above; neither serves the ranking-preservation purpose.

## Testing

TDD per house rules — each behavior below gets its failing test first.

- `SchemaMigrationTest` covers `V028`; the `seed-it-data.sql` check happens
  in the migration task (the W4 habit).
- **Unit — resolver and validation**: normalization (trailing slash, leading
  slash) applied identically at save and match; the no-chaining refusals from
  both ends; the open-redirect shapes (`//evil.example`, `https:` scheme,
  embedded `?`) refused; resolver failure degrades to the 404, never to a
  500.
- **Unit — seams**: the mapper's forward-url table is already exercised
  directly (package-private), so the decline-site consultation slots into
  those tests; `PageServlet.selectTemplate`/`rejectionReason` are already
  static and testable. The `Location` assertion must **derive** the expected
  URL from the strategy at both a root and a `/roller` context path, never
  hardcode one shape — `SeoController.robots()`'s unit test once encoded the
  context-path bug it should have caught.
- **Unit — slug history**: rename mints the rule; collapse re-points an
  existing rule; the round-trip rename converges; a failed history write
  fails the save with it.
- **Unit — bookkeeping**: hit count and `last_hit_at` advance; a failing
  count write does not fail the redirect; the log line carries every field
  this spec names (a list-appender test pins the fields, so "robust logging"
  is a tested property rather than an intention).
- **Browser — `RedirectIT`**, owning its own weblog (no `GLOBAL_CONFIG` /
  `SHARED_MEDIA` locks needed): create a page, rename its slug, follow the
  old URL to a 301 that renders the page at its new URL; create a manual
  rule over the API on a multi-segment would-404 path and follow it; read
  the hit count back over the API and see it advanced.
- This changes routing, so the pre-ship rule applies: run the browser suite
  at **both** context paths (`mvn verify -Pit` and
  `mvn verify -Pit -Dit.context.path=roller`).

## Definition of done

Both suites green at both context paths. A renamed page slug's old URL 301s
to the new one with no operator action, and the rule, its origin, its hit
count and its last hit are readable over the API. Every served redirect is
one grep away in `app.log` with rule id, origin, full requested URI, target,
referer and user-agent. A rule can neither shadow live content (by
construction — the resolver is only reachable from decided 404s) nor point
off-site nor chain (by save-time validation). CLAUDE.md records the seams,
the fail-closed-by-construction argument, the immutable-anchor finding, and
the decision to keep the admin surface API-only.
