# Virtual-host support (per-weblog custom domains) — Design

**Date:** 2026-08-18
**Status:** approved, not yet implemented
**Scope:** request resolution, URL generation, the public SEO surface, and the
proxy's certificate model. **No change to authoring, themes, or the automation
API's content endpoints.**

## Goal

Let a weblog own a hostname. `https://berlin.thelocalwiki.com/entry/tiergarten`
instead of `https://blog.example.com/berlin/entry/tiergarten`, with one
canonical address per page and no per-blog deploy step.

This is the prerequisite for joining external search data to internal blog
data. Search Console, Bing and Yandex key everything on **hostname + page
URL**; Roller keys everything on **weblog handle + entry id**. Until a weblog
*has* a hostname, there is no join key, and every downstream piece of the SEO
feedback loop (`docs/…/2026-07-11-seo-feedback-loop-design.md` in the jakemon
repo) is blocked behind that.

It is also work with a deadline. URL shape is the one category of change that
gets strictly more expensive every day a site is indexed: today it is a config
edit, after ranking it is a redirect map and a recovery period. The same
argument moved the servlet context path to the root in 0.1.4; this is the
second and larger instance of it.

## Non-goals

- **No apex-domain support in this wave.** See Decision 3 — the app is written
  so that enabling one later is a proxy change only, with no schema, routing or
  URL-generation change.
- **No `www.` handling.** `www.x` and `x` are two hostnames; if both are wanted,
  one redirects to the other at the proxy. Roller has one domain per weblog.
- **No self-service domain claiming.** Setting a domain stays an authoring-side
  setting on Weblog Settings, as it is today for every other weblog property.
- **No change to how entries, media, categories or pages are edited or stored.**
- **No new permission vocabulary.** Editing a weblog's domain requires exactly
  the permission editing its other settings already requires.

## Decisions

Settled before design; recorded so a later reader does not relitigate them.

1. **The custom domain is canonical; the path form 301s to it.** A weblog with a
   domain is reachable at exactly one address per page. Requests arriving on the
   old `/<handle>/…` form permanently redirect to the domain form, so existing
   inbound links and already-indexed URLs survive the move. Serving both with a
   `rel=canonical` hint was rejected: it splits link equity, relies on crawlers
   honouring a hint, and reports both properties in Search Console — which is
   precisely the ambiguity this feature exists to remove from the join.

2. **The control plane lives on the site host only.** Admin (`/roller-ui/**`)
   and the automation API (`/api/**`) 301 from a custom domain to the same path
   on the site host. One session cookie, one login, and — the reason that
   matters here — **one API base URL regardless of how many weblogs exist**, so
   an agent doing SEO work never needs a host-per-weblog map to find the API.
   Serving admin on every hostname would mean N logins, N cookie jars and N
   times the CSRF surface for a convenience nobody asked for.

3. **Certificates come from a wildcard; apex domains are deferred.** One
   DNS-01 `*.<zone>` certificate covers the unbounded subdomain case with no
   per-blog work at all. Apex domains (`maiiavorobiova.com`) cannot be covered
   by a wildcard and are out of scope for this wave — but nothing in the app
   knows that. `custom_domain` holds any hostname and resolution is identical
   either way, so adding apex support later is a Caddyfile change and nothing
   else.

4. **Syntax and uniqueness are hard errors; zone membership is a warning.** A
   malformed hostname (400) or one already claimed by another weblog (409) is
   refused outright — both are unambiguously wrong. A hostname outside the
   configured certificate zones saves, with a non-blocking warning on Weblog
   Settings. Refusing it would couple the app to the certificate model and make
   Decision 3's "proxy change only" false.

5. **Host resolution happens inside `WeblogRequestMapper`, not in a new mapper
   or an upstream rewriting filter.** See Architecture — this is what keeps the
   rest of the rendering stack unaware that vhosts exist, and it puts the
   redirect in the same class that already owns weblog-URL redirects.

6. **Generated URLs derive from the weblog, never from the request.** Forced by
   the cache design; see "URL generation" below. It is also simply correct: an
   entry has one canonical address, whichever hostname a reader arrived on.

## Architecture

### The property that makes this cheap

`WeblogRequestMapper` forwards weblog requests to
`/roller-ui/rendering/page/<handle>/…`. If host resolution happens *there*, the
forward URL still carries the handle, and `PageServlet`, `WeblogPageRequest`,
the pagers, every rendering model, `WeblogPageCache` and `SiteWideCache` stay
completely unaware that virtual hosts exist. The feature is a front-door
translation and nothing more.

Every alternative gives that up. A separate `VirtualHostRequestMapper` would
have to reimplement the mapper's ~150 lines of locale detection, context/data
splitting and trailing-slash rules, and two copies will drift. An upstream
filter rewriting `/x` to `/<handle>/x` keeps the mapper untouched but makes the
mapper build handle-form redirect targets, which then need re-redirecting —
leaking the handle into user-visible URLs, which is the thing being removed.

### Data model

`weblog.custom_domain VARCHAR(255) NULL`, with a **unique index**
(`V027__weblog_custom_domain.sql`, idempotent per `bin/db/migrations/README.md`).

- `Weblog.customDomain` + the mapping in `Weblog.orm.xml`.
- A `Weblog.getByCustomDomain` named query and
  `WeblogManager.getWeblogByCustomDomain(String)`, following
  `getWeblogByNewsletterListUuid`'s precedent — **except** that this column is
  genuinely unique, so it carries a database constraint instead of that
  method's order-by-handle tiebreak for non-unique values.
- Stored lowercased. Lookups lowercase the `Host` header and strip any `:port`.
- The unique index is the guarantee; the save-time 409 exists to produce a good
  error message rather than a constraint-violation 500.

### Resolution

One branch at the top of `WeblogRequestMapper.handleRequest`:

```
host   = lowercase(Host header), port stripped
weblog = getWeblogByCustomDomain(host)

if weblog != null:
    handle      = weblog.getHandle()      # from the HOST
    pathToParse = the entire request path # there is no handle segment
else:
    handle      = first path segment      # exactly as today
    pathToParse = the remainder
```

Everything downstream of that branch is unchanged: the same locale detection,
the same context/data split, the same trailing-slash and tags rules, the same
forward URL.

`getWeblogByHandle` is already called on every public request from this same
method, so a host lookup on the same path costs no additional round trip in the
common case and is subject to the same caching considerations as the existing
lookup.

**On a custom domain the first path segment is not a handle.**
`maiiavorobiova.com/jakefear/` is a page-slug candidate on Maiia's weblog, not a
route to the `jakefear` weblog. This is the point of the feature and it is an
acceptance criterion, not an incidental consequence.

### URL generation

**Rule: a weblog with a custom domain generates every URL against that domain,
derived from the weblog, never from the request.**

**A custom domain replaces the HANDLE segment and nothing else.** The servlet
context path, the locale segment, and every reserved path root are unchanged.
State it that precisely: an earlier draft of this section said the weblog's root
"IS the site root", and that one simplification generated three separate
defects — the protected-path guard swallowing `/page/<theme>.css` on every vhost
page, the context path dropped from custom-domain URLs at three sites, and a
locale-shaped first segment redirecting back to handle form.

At the root context:

- absolute → `https://maiiavorobiova.com/entry/x`
- relative → `/entry/x`

Under a `/roller` context path:

- absolute → `https://maiiavorobiova.com/roller/entry/x`
- relative → `/roller/entry/x`

This is forced, not stylistic. `WeblogPageCache.generateKey` keys on the weblog
handle and not the host, so one weblog reachable at two hostnames shares one
cached rendering — and `#showSeoHead` bakes absolute `canonical`/`og:url`
values *into* that cached HTML. Request-derived URLs would let whichever host
rendered first stamp its own canonical onto the other's response. Deriving from
the weblog makes those bytes identical by construction, which is why the cache
needs no host in its key and why nothing about cache keying changes in this
wave.

Mechanically this is smaller than it looks. All eleven weblog-content URL
methods on `MultiWeblogURLStrategy` — entry, media file, thumbnail, collection,
page, feed, search, resource, search-page template — already delegate their
root to `getWeblogURL(weblog, locale, absolute)`. **The change is confined to
that one method**: when the weblog has a custom domain, use it as the absolute
prefix and omit the `/<handle>/` segment.

`AbstractURLStrategy`'s six methods (`getLoginURL`, `getLogoutURL`,
`getActionURL`, `getEntryAddURL`, `getEntryEditURL`, `getWeblogConfigURL`) do
repeat the context-URL prefix, but they build **control-plane** URLs under
`/roller-ui/`, which by Decision 2 always live on the site host. They must
**not** be changed to consult a weblog's domain — doing so would send an author
to an admin screen on a hostname that redirects straight back.

**`weblog.absoluteurl.<handle>` is removed** along with its `Host`-header check
in the mapper. It never worked as a vhost mechanism:
`MultiWeblogURLStrategy.getWeblogURL` appends `/<handle>/` even when the
property is set, so the best it ever produced was
`https://maiiavorobiova.com/maiia/`. It is also startup-scoped, meaning a new
weblog needs a restart, and the `ROLLER_*` environment overlay lowercases names
and turns `_` into `.`, so a handle containing a hyphen or a capital cannot be
expressed as an environment variable at all. The column replaces it; keeping a
second, restart-scoped way to say the same thing is how the two drift.

### Redirects

**1. Path form → domain.** In the mapper: a resolved weblog that has a custom
domain, reached on any other host, gets a 301 to `https://<domain>/<rest>` with
the query string preserved.

Note the mapper's existing trailing-slash redirect was fixed in 0.1.4 to carry
the context path (a leading-slash `Location` resolves against the *server* root,
not the application root). The new redirect is absolute and so is unaffected,
but the two live side by side and the distinction must survive review.

**2. Control plane → site host.** A small filter ahead of the mapper.
**This must not be a blanket `/roller-ui/**` rule.**

`ContactController` is mapped at `/roller-ui/rendering/contact.rol` and
`NewsletterController` at `/newsletter/subscribe`. Both are posted by `fetch`
from the rendered blog page, and every bundled theme's CSP is
`connect-src 'self'`. Redirecting either to the site host makes it cross-origin:
blocked by CSP, and a 301 on a POST does not carry the body regardless. Every
`[contact]` and `[subscribe]` shortcode on every vhost weblog would silently
stop working, visible only in a browser console.

| Path on a custom domain | Behaviour |
|---|---|
| `/roller-ui/rendering/**` | **stays** — the public rendering namespace: page, feed, search, resources, media-resources, contact |
| `/newsletter/**` | **stays** — same-origin subscribe |
| `/themes/**`, `/webjars/**`, other static | **stays** — theme assets |
| `/robots.txt`, `/sitemap.xml` | **stays**, per-host (below) |
| all other `/roller-ui/**` | 301 → site host, same path |
| `/api/**` | 301 → site host, same path |

`/roller-ui/rendering/` already *is* the public namespace in this codebase; the
filter makes that load-bearing rather than incidental.

### The public SEO surface, per host

On a custom domain:

- `/robots.txt` advertises `https://<domain>/sitemap.xml`.
- `/sitemap.xml` **is that weblog's sitemap** — the content today served at
  `/sitemap-<handle>.xml` — with every `<loc>` in domain form.

On the site host, `/sitemap.xml` remains the index of all weblogs but **omits
custom-domain weblogs**. The sitemap protocol only permits an index to reference
sitemaps on its own host, so leaving them in produces an index that is invalid
for exactly the entries most wanted in the crawl. Each such weblog is discovered
through its own host's `robots.txt` instead.

`SeoController` gains host awareness; that is the only place it is needed.

### Cache invalidation

Setting or clearing a custom domain must bump `weblog.lastModified`.
`WeblogPageCache` has no CacheHandler, so `CacheManager.invalidate(...)` never
reaches it and `lastModified` is the only thing that expires a rendered page.
Without the bump, every already-cached page keeps serving handle-form URLs until
some unrelated edit happens to touch the weblog.

`JPAWeblogManagerImpl.saveWeblog` already sets `lastModified` unconditionally on
every save, so this requirement is **already satisfied** and needs no new code —
but it is load-bearing enough to pin with a characterisation test rather than
leave as an accident someone could optimise away later.

### Configuration and operations

- **`vhost.cert.zones`** — startup property, comma-separated (e.g.
  `thelocalwiki.com`). Drives the save-time warning **only**; no code path ever
  gates on it. Empty means warn about nothing.
- **`SITE_DOMAIN`** keeps meaning the control-plane host — where admin, the API
  and the site-wide front page live.

### Naming the site host, and the redirect loop it prevents

The control-plane redirect needs a base URL to send readers *to*, and getting
this wrong produces an infinite loop rather than a visible error.

`WebloggerRuntimeConfig.getAbsoluteContextURL()` returns `site.absoluteurl` when
that runtime property is set, and otherwise the value `InitFilter` latched from
whichever request happened to arrive first after boot. Under virtual hosts that
first request can perfectly well be for a custom domain — at which point the
"site host" the filter redirects to *is* a custom domain, that request is
redirected again, and the browser gives up after N hops.

Therefore:

- **`site.absoluteurl` becomes required once any weblog has a custom domain.**
  It is the only host-independent statement of where the control plane lives.
- The control-plane filter reads `site.absoluteurl` **directly**, never
  `getAbsoluteContextURL()`, so it cannot fall back to a latched request value.
- If `site.absoluteurl` is unset, the filter **does not redirect** — it serves
  the request as it does today. A missing configuration degrades to the
  pre-vhost behaviour rather than to a loop.
- Startup logs a warning when a weblog has a custom domain and
  `site.absoluteurl` is blank, since that combination silently disables the
  control-plane boundary Decision 2 describes.

`InitFilter`'s latched absolute URL is left alone in this wave. Every URL that
matters for SEO now comes from the weblog (see "URL generation"), which is what
made the latch harmless here; auditing its remaining consumers is follow-up
work, not a prerequisite.
- **Caddy needs rebuilding with a DNS-provider module.** Wildcard certificates
  require DNS-01, which stock Caddy cannot perform; `deploy/caddy/Dockerfile`
  must build via `xcaddy` with the relevant provider plugin, and the provider's
  API credentials join `.env`. This is a real change to a published image and is
  its own implementation task, not a footnote to the Caddyfile edit.

### The join

- `customDomain` on `AdminDtos.WeblogView`, settable through
  `PATCH /api/v1/weblogs/{handle}` (ADMIN scope, as that endpoint already
  requires).
- A `custom_domain` column on the `analytics_weblog_sites` view (V027), which
  then carries **weblog handle ↔ Umami website id ↔ hostname** in one place.

That view becomes the map an SEO agent reads to turn a Search Console property
into a weblog it can edit. It is the deliverable this whole wave exists to
produce.

## Testing

Unit tests carry the resolution and URL-generation rules; the browser suite
carries the parts only a real container can prove (the servlet mappings, the
security chain, the redirects as a browser actually follows them).

`WeblogRequestMapperTest` already runs its cases at more than one context path
after 0.1.4; custom-domain cases join it there. A new `VirtualHostIT` covers the
end-to-end criteria. Because the IT harness reaches the app over `127.0.0.1`, a
custom domain is simulated by sending an explicit `Host` header rather than by
resolving a real name.

## Acceptance criteria

Each is a test. "b.example.com" is a weblog whose `custom_domain` is set.

1. `GET https://b.example.com/` renders that weblog's home page;
   `GET https://b.example.com/entry/x` renders its permalink.
2. `GET https://site/handle/entry/x?p=2` → 301
   `https://b.example.com/entry/x?p=2` — query string preserved.
3. `GET https://b.example.com/roller-ui/menu.rol` → 301 to the same path on the
   site host. `GET https://b.example.com/api/v1/ping` likewise.
4. `POST https://b.example.com/roller-ui/rendering/contact.rol` is **not**
   redirected. `POST https://b.example.com/newsletter/subscribe` is **not**
   redirected.
5. A rendered permalink's `rel=canonical` and `og:url` are the domain form,
   whichever host served the request.
6. `https://b.example.com/robots.txt` names `https://b.example.com/sitemap.xml`;
   that sitemap lists only that weblog's URLs, every one in domain form.
7. The site host's `/sitemap.xml` index omits custom-domain weblogs.
8. Saving a domain another weblog already holds → 409. Saving a malformed
   hostname → 400. Saving one outside `vhost.cert.zones` → saved, with a warning
   rendered on Weblog Settings.
9. Setting or clearing a custom domain bumps `weblog.lastModified`.
10. `https://maiiavorobiova.com/jakefear/` resolves as a page-slug candidate on
    Maiia's weblog and **not** as the `jakefear` weblog.
11. With `site.absoluteurl` unset and at least one weblog holding a custom
    domain, `GET https://b.example.com/roller-ui/menu.rol` is **served, not
    redirected** — the degraded path is pre-vhost behaviour, never a loop.
12. **Characterisation:** a weblog with no custom domain behaves exactly as it
    does today — same routing, same generated URLs, same sitemap membership.
    Expected to pass on arrival; it exists to prove the wave changed nothing for
    weblogs that do not opt in.

## Out of scope / follow-ups

- **Apex domains** — Decision 3. A Caddyfile and certificate change; no app
  change.
- **`www.` canonicalisation** — proxy-level, if wanted at all.
- **Per-weblog search-engine verification tokens.** Every new hostname needs
  Search Console / Bing / Yandex verification and an IndexNow key file at its
  root, which is manual work that scales with the number of weblogs. A
  per-weblog verification field that Roller serves would remove it permanently.
  Now newly possible: serving a file from the site root only became an option
  when the context path moved to `/`.
- **Site-wide front page behaviour on a custom domain.** `/` on the site host
  continues to forward to `site.frontpage.weblog.handle`; `/` on a custom domain
  is that weblog's home. No aggregator is reachable from a custom domain, which
  is correct for now but worth revisiting if the front door ever becomes a
  product surface of its own.
