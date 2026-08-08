# Pages, Audience & Analytics — Design

**Date:** 2026-08-08
**Status:** Approved
**Builds on:** 2026-08-01 modernization roadmap (this is the detailed design for
its "Wave 5 — Services", plus Pages, forms and account access)

## Context

The fork's publishing engine is now competitive — media pipeline, shortcodes,
share links, SEO head, five bundled themes. A gap assessment on 2026-08-08,
benchmarked against Ghost, WordPress, Squarespace, Substack, Pixieset and
Lodgify, found the shortfalls are almost entirely in the ring *around*
publishing: the parts that turn a blog into a business asset for two
family-owned businesses and ~4 people running personal blogs.

Ten gaps were identified, plus two ranked just below the cut. Five gaps are in
scope here, together with video embeds — pulled forward from below the cut
because YouTube matters to both businesses. The rest are logged as future work
at the end of this document.

### The reframing that shaped this design

The initial assessment scoped to `app/src` and reported "no newsletter" and "no
analytics." At the **app tier** that was accurate. At the **deploy tier** it was
wrong: `docker-compose.prod.yml` already ships Listmonk and Umami, Caddy already
routes `/analytics/*` and `/newsletter/subscribe` same-origin (deliberately, so
the theme CSPs do not block them), `deploy/backup/backup.sh` already covers both
databases, and `docker_deployment.md` already documents their setup.

Gaps 2 and 4 are therefore **wiring problems, not build problems**. The app-side
work is far smaller than a naive reading of the gap list implies.

### Confirmed defect this design fixes

`docker_deployment.md:288-296` instructs the operator to enable the analytics
override and then "paste this into *Settings → Weblog Settings → Analytics
tracking code*". `WeblogConfig.jsp:232` gates that textarea on
`analytics.code.override.allowed && !weblogAdminsUntrusted`, and
`roller.properties:276` ships `weblogAdminsUntrusted=true`. **The documented
procedure cannot be performed** — the field never renders. Umami is provisioned
end to end, backed up nightly, CSP-compatible by design, and unreachable because
of one JSP condition.

The gate is not the bug. It is doing its job: that field holds raw `<script>`
bound for `<head>`, and `weblogAdminsUntrusted` is a deliberate security
invariant of this fork. The fix is to stop sending raw HTML across that boundary
at all (see Wave C).

## Decisions (settled during design; do not re-litigate)

| Decision            | Choice                                                                                           |
| ------------------- | ------------------------------------------------------------------------------------------------ |
| Decomposition       | Three waves — A Pages & Embeds, B Audience, C Analytics — each its own spec → plan → implement cycle |
| Page model          | New `WeblogPage` entity + extracted `ShortcodeContext`; **not** a `WeblogEntry` discriminator    |
| Page URLs           | `/<handle>/<slug>` with a reserved-slug list                                                     |
| Page discovery      | `show_in_nav` + `nav_order` flags, new macro, all five bundled themes; pages join the sitemap    |
| Inquiry handling    | Persist, **then** notify by email                                                                |
| Spam defence        | Layered and dependency-free — honeypot, timing, per-IP throttle, length caps. No CAPTCHA         |
| Broadcast           | Explicit "Send as newsletter" action on the entry; never automatic on publish                    |
| Subscribe endpoint  | **Roller owns `/newsletter/subscribe`**, forwarding to Listmonk; stores no subscriber data       |
| Analytics injection | Structured `analyticsSiteId`; the macro builds the `<script>`. `weblogAdminsUntrusted` untouched |
| Analytics contract  | Versioned SQL views we own + first-party `roller_event` table; Grafana joins them                |
| Grafana access      | Dedicated read-only Postgres role, private network only; 5432 never published                    |
| Account access      | Shared token table serving forgot-password **and** admin "send set-password link"                |
| Self-registration   | Out of scope — consistent with the roadmap's public-multi-tenancy non-goal                       |
| Video embeds        | Curated `[video]` shortcode over a provider allowlist; **no oEmbed, no outbound HTTP**           |
| Embed placement     | Wave A — shares the sanitizer, theme CSPs and shortcode registry that Wave A already opens       |
| Maps                | Already shipped via `[map]` (Leaflet + OSM); no iframe embed needed                              |

## Global constraints

Every task in every wave inherits these.

- **`weblogAdminsUntrusted` stays `true`.** No role-keyed sanitizer bypass, and
  no raw HTML from a weblog admin reaches `<head>` or entry output.
- **Theme CSPs are pinned byte-for-byte** by `MapAssetsRenderingTest`,
  `PortfolioThemeRenderingTest` and `TravelThemeRenderingTest`. This is why
  there is no CAPTCHA and why Umami's tracker is served same-origin.
  **Exactly one deliberate widening is authorised in these waves:** adding
  `frame-src` limited to the allowlisted video hosts, for `[video]` (Wave A).
  It is additive and host-scoped — `script-src`, `connect-src` and
  `default-src 'none'` are untouched, and the pinned tests are updated in the
  same commit that changes the themes so the two cannot drift. Any other CSP
  change needs its own decision.
- **No GPL-licensed dependencies.**
- **Every schema change adds a numbered idempotent migration** under
  `bin/db/migrations/`; never edit an applied one.
- **Controllers name every `@RequestParam`/`@PathVariable` explicitly** — the
  build does not pass `-parameters`, and `ControllerMetadataTest` enforces it.
- **Ownership-check every id** through the `BaseController.lookup*` family. The
  permission interceptor vouches only for the *action* weblog.
- **Coverage gates:** ~90% diff coverage on changed lines; JaCoCo floors ratchet
  upward after each wave; a browser IT for every new public surface and every
  new admin screen.
- **Render order is load-bearing:** shortcodes → markdown → sanitize. Markdown
  first would escape the quotes in `[gallery dir="x"]`.

---

# Wave A — Pages & Embeds

**Goal:** About / Services / Contact pages authored in Markdown in the existing
editor, not hand-written Velocity templates — plus a `[video]` shortcode, which
belongs here because it touches the same sanitizer, theme templates and
shortcode registry this wave already opens.

## Why a new entity rather than a flag on `WeblogEntry`

Reusing `WeblogEntry` with a `PAGE` discriminator would give the editor,
revisions, SEO fields, shortcodes and preview for free. It would also require
auditing all 25 query paths in `JPAWeblogEntryManagerImpl` to exclude pages —
feeds, archives, the Lucene index, sitemaps, tag aggregates, pagers, next/prev
navigation. Missing one puts a page in an RSS feed silently. Pages would also
carry `pubtime`, `category` and tag columns that mean nothing for them.

A separate entity cannot leak into any of those paths by construction.

## `ShortcodeContext` — the load-bearing extraction

`ShortcodeHandler.render(Map, String, WeblogEntry)` takes an entry, but handlers
use it for exactly three things: `getWebsite()`, `getAnchor()` and `getText()`.
`CtaShortcode:122` already null-guards it.

```java
public interface ShortcodeContext {
    Weblog getWeblog();
    String getSlug();      // entry anchor, or page slug
    String getRawText();   // pre-expansion source, for MapPins/FaqBlocks re-parse
}
```

`WeblogEntry` **implements** `ShortcodeContext`. Consequences:

- `ShortcodeExpander.expand(ShortcodeContext, String)` still accepts a
  `WeblogEntry` at all three existing call sites — `WeblogEntry:1169`,
  `PluginManagerImpl:113`, `MapShortcode:202` — unchanged.
- The ~145 test references that go through `expand()` are untouched.
- Only the five handler signatures, `MapPins`/`FaqBlocks`/`EntryJsonLd` helpers,
  and tests calling `handler.render()` directly need editing.

This is what makes the refactor bounded rather than sprawling.

## Shared render pipeline

Extract shortcodes → markdown → sanitize from `WeblogEntry.render()` into a
`ContentRenderer` that both `WeblogEntry` and `WeblogPage` call. Named entry
plugins remain entry-only; shortcodes and markdown are universal. The ordering
constraint is preserved verbatim.

## Schema (`V014__weblog_pages.sql`)

```
roller_weblogpage
  id                 varchar(48) PK
  weblogid           varchar(48) NOT NULL  -> roller_weblog(id)
  slug               varchar(255) NOT NULL
  title              varchar(255) NOT NULL
  content            text
  status             varchar(20)  NOT NULL   -- DRAFT | PUBLISHED
  show_in_nav        boolean      NOT NULL DEFAULT true
  nav_order          integer      NOT NULL DEFAULT 0
  created            timestamptz  NOT NULL
  updated            timestamptz  NOT NULL
  -- SEO, mirroring WeblogEntry
  meta_title         varchar(255)
  search_description varchar(255)
  canonical_url      varchar(255)
  noindex            boolean NOT NULL DEFAULT false
  og_image_id        varchar(48)
  UNIQUE (weblogid, slug)
```

Deliberately absent: category, tags, pubtime, comment settings, locale.

## Routing

`WeblogPageRequest` currently throws `InvalidRequestException("invalid index
page")` for any single-element path other than `tags` (line ~168). That branch
changes: a single unmatched segment becomes a page-slug candidate, resolved
against `roller_weblogpage`; no match → 404.

Slugs are validated on save against a reserved list —
`entry, date, category, page, tags, feed, search, resource, media, rsd` — sourced
from the same constant the request parser uses, so the two cannot drift.

`/<handle>/page/<link>` keeps working for CUSTOM templates; nothing about the
existing template-page mechanism changes.

## Discovery

- New `#showPageLinks($weblog)` in `WEB-INF/velocity/weblog.vm`, emitting only
  `<li><a>` items so each theme keeps its own nav markup and CSS.
- Wired into all five bundled themes. `gaurav`, `portfolio` and `travel` have no
  page nav today and gain one; `basic` and `fauxcoly` already call
  `#showPageMenu`, which folds pages in beside its template pages.
- `SeoController` emits published, non-`noindex` pages into `sitemap-<handle>.xml`.

## Video embeds — the `[video]` shortcode

Brought forward from the deferred list: YouTube embeds matter for both
businesses. Maps do **not** need this — `[map]` already renders Leaflet over OSM
tiles with no iframe, no API key and no third party, and every theme CSP already
carries `img-src * data:` for its tiles.

### Why not oEmbed

Two independent findings rule it out.

1. **`HTMLSanitizer:48` strips `<iframe>`**, and the allowlist at line 88 does
   not include it. oEmbed responses are overwhelmingly iframes and scripts, so
   the payload would be discarded on arrival — the round trip would yield little
   more than a title and a thumbnail.
2. **Discovery is an SSRF surface.** True oEmbed fetches an author-supplied URL
   server-side to find its endpoint. On this deployment that points straight at
   the actuator on port 8090 and at any cloud metadata endpoint.

So no outbound HTTP at all. URLs are parsed, not fetched.

### Design — the `[map]` pattern, exactly

`[video url="https://youtu.be/abc123" caption="..."]` matches a provider
allowlist by URL shape (`youtube.com/watch?v=`, `youtu.be/`, `vimeo.com/<id>`),
extracts the ID, and validates it against a strict character class. An
unrecognised host passes through byte-for-byte, like any unknown shortcode.

It emits a placeholder, never a frame:

```html
<div class="video-embed" data-provider="youtube" data-video-id="abc123">
  <img src="https://i.ytimg.com/vi/abc123/hqdefault.jpg" alt="..." loading="lazy">
</div>
```

The thumbnail needs no CSP change — `img-src *` already allows it. `HTMLSanitizer`
gains `data-provider` and `data-video-id` on `div`, mirroring the `data-pins`
grant at line 104.

A new `#showEmbedAssets` macro — the twin of `#showMapAssets` and
`#showGalleryAssets` — renders a **click-to-play facade**: nothing loads from the
provider until a reader clicks, at which point it injects a
`youtube-nocookie.com` / `player.vimeo.com` iframe. Privacy-preserving by
default and faster, since no third-party frame loads on page view.

### The CSP change

`frame-src https://www.youtube-nocookie.com https://player.vimeo.com` is added
to all five bundled themes, and `MapAssetsRenderingTest`,
`PortfolioThemeRenderingTest` and `TravelThemeRenderingTest` are updated in the
same commit. This is the single authorised widening (see Global constraints):
additive, host-scoped, and it leaves `script-src`, `connect-src` and
`default-src 'none'` untouched.

### Testing

Unit: URL-shape parsing per provider, ID validation, unknown-host pass-through,
escaped `[[video]]` literal, sanitizer retention of the new data attributes.
Rendering: the CSP string pinned with `frame-src` present. Browser IT: an entry
carrying `[video]` renders a facade, loads no third-party frame before a click,
and `assertNoFailedRequests` stays clean.

## Editor

Reuses the EasyMDE surface and its three-function seam (`insertMediaFile`,
`rollerSetEntryText`, `rollerGetEntryText`). Server-rendered preview, same as
entries, because only the server expands shortcodes. `[video]` joins the Insert
menu via its required `ShortcodeCard`, so it cannot ship undiscoverable.

**No revisions in Wave A** — pages change rarely and the entry revision table is
keyed to entries. Logged as future work.

## Testing

Unit: slug validation and reserved-list rejection, routing resolution, nav
ordering, sitemap inclusion/exclusion, `ShortcodeContext` behaviour for both
implementations. Browser IT: author a page, publish it, see it in nav, reach it
at `/<handle>/<slug>`, confirm a draft 404s anonymously.

---

# Wave B — Audience

## Contact forms

`[contact]` shortcode — usable on a Page via `ShortcodeContext`, which is the
Contact-page story. Fixed field set: name, email, message, optional subject.
Configurable field builders are explicitly out of scope (YAGNI).

**Endpoint.** A narrow public POST, CSRF-exempt on the reasoning
`SecurityConfig:253` already documents for comments: the request carries no
ambient authority, so a token defends nothing an attacker could not bypass by
posting from their own server.

**Handling.** Persist to `roller_form_submission`, **then** notify
`weblog.emailAddress` with `Reply-To` set to the submitter. Persist-first is the
point: if SMTP is misconfigured the inquiry survives, which for a business
running on leads is the failure that matters. Admin list view per weblog.

**Defences, layered and dependency-free:**

| Layer                                                 | Stops                   |
| ----------------------------------------------------- | ----------------------- |
| Honeypot field that must stay empty                   | Naive form-filling bots |
| Minimum render-to-submit interval                     | Instant automated posts |
| Per-IP `GenericThrottle` (already proven on comments) | Volume                  |
| Hard length caps on every field                       | Payload abuse           |

No CAPTCHA: it would require widening `script-src`/`frame-src`/`connect-src` in
all five themes — the exact strings three rendering tests pin byte-for-byte —
and add a vendor to the inquiry path.

## Newsletter

Listmonk owns subscribers, double opt-in and sending. **Roller stores no
subscriber data**, which keeps consent and retention obligations out of the blog.

- Per-weblog `newsletter_list_uuid`.
- `#showSubscribeForm` already exists in `weblog.vm:1577` and is currently called
  by no theme. Wire it into theme footers; add a `[subscribe]` shortcode for
  placement on pages.
- **Roller owns `/newsletter/subscribe`** — a thin controller forwarding to
  Listmonk's public API. **The Caddy rewrite must be deleted, not merely left in
  place.** In `deploy/caddy/Caddyfile` the `handle /newsletter/subscribe` block
  is more specific than the catch-all `handle { reverse_proxy app:8080 }`, and
  Caddy evaluates mutually-exclusive `handle` blocks by path specificity — so in
  production Caddy wins and the Roller controller never runs —
  no throttle, no `roller_event`, no conversion capture. Leaving it would
  silently defeat this decision in the only environment that matters.
  Rationale for owning it: the Caddy route exists only in production, so today
  the form cannot be exercised in dev or by a browser IT, which the quality
  gates require.
  Owning it also lets the same honeypot/throttle defences apply, and lets the
  pass-through emit a `roller_event` row so a subscribe is a first-class
  conversion in the Grafana join rather than something only Listmonk knows.
  The no-subscriber-data property is preserved — forwarding is not storing.
- **"Send as newsletter"** button on the entry editor: creates and sends a
  Listmonk campaign from the rendered entry via the background-task framework
  with retry, stamping `newsletter_sent_at` so it cannot double-send. Manual, not
  automatic on publish: a published typo is a 30-second fix, a mailed one is
  permanent.

## Account access

`roller_user_token` — hashed token (a database read must not yield working reset
links), purpose, expiry, used-at.

- **Forgot password:** identical confirmation whether or not the address exists,
  so the form cannot enumerate accounts. Throttled per IP and per address. One
  hour, single-use.
- **Admin "send set-password link"** on user creation, replacing inventing a
  password and passing it out of band. Same table, same expiry logic.

Both branch on `MailUtil.isMailConfigured()` and say so plainly when it is not.

---

# Wave C — Analytics

## Per-weblog injection

`Weblog.analyticsSiteId` (validated as a UUID on save) and
`analytics_share_url`. `#showAnalyticsTrackingCode` builds the `<script>` itself
from trusted site-wide config — the Umami host and `UMAMI_SCRIPT_NAME`. No raw
HTML crosses the trust boundary, so `weblogAdminsUntrusted` stays on and every
weblog still gets its own segment. Side benefit: nobody can paste a malformed
tag into `<head>`.

`docker_deployment.md:288-296` is rewritten, since the procedure it currently
documents is impossible.

## The Grafana contract

Two pieces, both ours, both in the migration chain.

1. **Versioned SQL views** mapping Umami's internal tables to a stable shape —
   `weblog_handle, path, entry_anchor, day, sessions, views`. An
   anti-corruption layer: replacing Umami later rewrites views, not dashboards.
   Querying another product's internal tables directly is precisely what makes a
   setup non-portable.
2. **`roller_event`** — first-party outcomes Umami cannot see: form submitted,
   newsletter subscribed, entry published. Columns: `id, weblogid, event_type,
   entry_anchor, page_slug, occurred_at, metadata jsonb`. **Created in Wave B**,
   because Wave B is what writes to it; Wave C only adds the views over it.

Grafana joins the two. **That join is the SEO feedback loop** — traffic to a post
beside what it converted.

## Access

Dedicated Postgres role with `SELECT` on the analytics views and nothing else —
not `roller_user`, not Umami's raw tables. Reachable only over
Tailscale/WireGuard or an SSH tunnel; 5432 is never published. The view layer
doubles as the permission boundary.

## Deletion

`HitCountQueue`, `WeblogHitCount`, `ResetHitCountsTask`, `getHotWeblogs`,
`WeblogEntryManager.resetAllHitCounts` and `roller_hitcounts` all go.

Justification: `roller_hitcounts` holds only `(id, websiteid, dailyhits)`,
`ResetHitCountsTask` zeroes it daily so there is no history, `PageServlet:208`
counts per-weblog only so per-entry was never possible, and the sole consumer —
`SiteModel.getHotWeblogs` — is called by no bundled theme. The only remaining
trace is an orphaned `.hotBlogs` CSS class in `frontpage/_css.vm`. Umami now
owns this job properly.

---

# Cross-cutting

**Migrations**, wave-aligned so each wave's schema lands with the code that uses
it. Each idempotent; `SchemaMigrationTest` enforces discoverability and
idempotency.

| Migration                               | Wave | Contents                                                                                          |
| --------------------------------------- | ---- | ------------------------------------------------------------------------------------------------- |
| `V014__weblog_pages.sql`                | A    | `roller_weblogpage`                                                                               |
| `V015__form_submissions_and_tokens.sql` | B    | `roller_form_submission`, `roller_user_token`, **`roller_event`**                                 |
| `V016__newsletter_wiring.sql`           | B    | `weblog.newsletter_list_uuid`, `weblogentry.newsletter_sent_at`                                   |
| `V017__analytics_contract.sql`          | C    | `weblog.analytics_site_id`, `weblog.analytics_share_url`, the versioned views, the read-only role |

`roller_event` lands in **Wave B, not C** — Wave B is what writes to it (form
submitted, newsletter subscribed), so the table must exist before those code
paths ship. Wave C adds only the views over it and the role that reads them.

**Testing:** unit coverage on every new manager and controller; a browser IT per
new public surface (page rendering, contact submission, subscribe) and per new
admin screen (page editor, submissions list, forgot-password). Floors ratchet
after each wave.

**Security invariants restated:** `weblogAdminsUntrusted` on; no raw HTML into
`<head>`; every by-id lookup ownership-checked; tokens stored hashed; the
analytics role read-only and network-isolated; no server-side fetch of an
author-supplied URL anywhere in these waves. The **only** theme-CSP change is
the additive, host-scoped `frame-src` for `[video]` in Wave A.

---

# Deferred — logged, not built

| Gap                           | Why deferred                                                                                                                                                                                                          |
| ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **3. Custom domain per blog** | `weblog.absoluteurl.<handle>` exists but is startup-scoped and only *rejects* mismatched Hosts; the handle still lives in the path. Real apex-per-blog needs host→weblog routing. High value, own wave.               |
| **7. Content export**         | No WXR/JSON/Markdown archive; the deploy backup is infrastructure-level and all-or-nothing. Portability and per-blog migration.                                                                                       |
| **8. Public comments**        | Requires self-registration (a roadmap non-goal) or anonymous comments plus real spam defence. Needs a product decision first.                                                                                         |
| **9. Commerce / booking**     | Client galleries with proofing, print sales, session booking; availability calendars and rate tables. The line between "a blog about the business" and "the business runs on it" — needs an explicit in/out decision. |
| **10. Asset-level alt text**  | `MediaFile` has description/copyright/EXIF/focal point but no `alt`; alt lives only on each `[image]` insertion, so a reused photo needs it retyped. Plus no image sitemap.                                           |
| **Redirect management**       | Changing an entry anchor 404s every existing link — a slow SEO leak on exactly the evergreen guide content these businesses depend on.                                                                                |
| **Embeds beyond video**       | `[video]` ships in Wave A for YouTube and Vimeo. Social embeds (Instagram, X, Bluesky) need `script-src` widened to third-party hosts, not just `frame-src` — a materially larger concession than the one authorised here, and worth its own decision. |
| **Page revisions**            | Entry revisions exist (`weblogentry_revision`, V010); pages get none in Wave A.                                                                                                                                       |
