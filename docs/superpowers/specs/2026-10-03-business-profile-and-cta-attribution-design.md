# Business profile and CTA attribution — Design

**Date:** 2026-10-03
**Status:** Draft for review

## Context

The blogs exist to bring people to the business: rental guides send
travellers to a booking page, and Maiia's photography blogs send clients to
an enquiry or booking page. Two things stop a blog from being a measurable,
findable part of that business:

1. **Nobody can tell which guide sends people to book.** `[cta]`
   (`CtaShortcode`) UTM-tags its outbound link, but Umami records no click,
   so the question "which guide drives bookings?" has no answer. Contact
   form enquiries are already attributed (`FormSubmission.entryAnchor` /
   `pageSlug`, shown in the Submissions inbox and recorded as
   `FORM_SUBMITTED` in `roller_event`), so CTA clicks are the only gap.
2. **No blog says it is a business.** Entries emit `BlogPosting` plus the
   optional travel types, and other pages emit `Blog`, but nothing emits
   `Organization` / `LocalBusiness` / `LodgingBusiness`. Local search ranks
   on consistent name/area/contact data. The booking link is retyped into
   every `[cta]`.

### What the user said

- Blogs map to businesses in a **mixed** way: a few real businesses (the
  rental company, Maiia's photography) are shared by several blogs, and some
  blogs (a rental property) add their own place details on top.
- Rental properties publish **locality only**: no street address. A map
  point, if any, is approximate.
- Approach chosen for both parts: A (below).

### Assumptions (correct these in review)

- Shared businesses are created and edited only by a **site admin**.
  Blog owners choose a business for their blog and fill in the place
  fields; they cannot edit a business. Business data is public by nature,
  so every blog owner may see the list of businesses.
- The results of part 1 are read in **Umami's Events view**. Grafana gets
  no new panel in this work.

## Decisions

| Decision | Choice |
|---|---|
| Click tracking | Umami's declarative `data-umami-event*` attributes on the anchor. No new endpoint, no new JavaScript, no `roller_event` row. |
| Business model | A site-level `roller_business` row shared by many blogs, plus an optional per-blog place layer stored on `weblog`. |
| Location privacy | Locality, region and country only. Coordinates are rounded to 2 decimal places (about 1 km) **on save**, so a precise point can never be stored. |
| JSON-LD | Built in Java (`BusinessJsonLd`), like `EntryJsonLd`: one escape path, unit-testable. |
| Visible card | A theme macro renders the same facts in every bundled theme's footer, because Google expects structured data to describe visible content. |
| Default booking link | A `[cta]` with no `href` uses the blog's booking URL, then the business's. |

## Non-goals

- Booking, availability, rates or payments. The blog links out to them.
- A street address or exact coordinates for any place.
- A Grafana panel for CTA clicks; a first-party `CTA_CLICKED` event.
- Opening hours, price range, reviews or aggregate ratings.
- Exposing businesses through `/api/v1` (a follow-up if wanted).
- More than one place per blog.

## Part 1 — CTA click attribution

### Behaviour

`CtaShortcode.render` adds three attributes to the anchor it already builds:

```html
<a class="cta-card" href="...utm..." rel="nofollow sponsored noopener" target="_blank"
   data-umami-event="cta-click"
   data-umami-event-entry="<entry anchor or page slug>"
   data-umami-event-dest="<destination host>">
```

- `data-umami-event-entry` is `ShortcodeContext.getSlug()`, the value
  `utm_campaign` already uses. It is omitted when there is no slug.
- `data-umami-event-dest` is the lower-cased host of the **final** href
  (after the default-booking-URL fallback from part 2), with no port and no
  path. Hosts let Umami group clicks by booking site without recording
  full URLs.
- Values pass through the same `escape` as the label.

`HTMLSanitizer`'s policy allows exactly `data-umami-event`,
`data-umami-event-entry` and `data-umami-event-dest` on `<a>`, and no
other `data-umami-*` name. Authors can hand-write these attributes on
ordinary links. The worst outcome is a fabricated event in their own blog's
Umami site, which is accepted.

When a blog has no Umami site ID, `#showAnalyticsTrackingCode` emits no
tracker and the attributes do nothing. CSP needs no change, because the
tracker already sends pageviews to the same endpoint.

Pages already in the render cache pick up the attributes when they next
expire. The caches are in memory, so a deploy restart clears them.

## Part 2 — Business profile

### Data model (migration `V029__business_profile.sql`, idempotent)

**`roller_business`** (new table)

| Column | Type | Notes |
|---|---|---|
| `id` | varchar(48) PK | UUID |
| `name` | varchar(255) NOT NULL | |
| `business_type` | varchar(32) NOT NULL | `Organization` \| `LocalBusiness` \| `ProfessionalService` |
| `website_url` | varchar(255) | absolute http(s) |
| `booking_url` | varchar(255) | absolute http(s); the default for every blog using it |
| `telephone` | varchar(32) | |
| `email` | varchar(255) | |
| `logo_url` | varchar(255) | absolute http(s); emitted, never fetched |
| `same_as` | text | social/profile URLs, one per line, at most 10 |
| `area_served` | varchar(255) | free text, e.g. "Lisbon and the Algarve" |
| `description` | text | |
| `created`, `last_modified` | timestamp NOT NULL | |

**`weblog`** (new columns, all nullable)

| Column | Type | Notes |
|---|---|---|
| `business_id` | varchar(48) REFERENCES `roller_business(id)` | no cascade: a referenced business cannot be deleted |
| `place_type` | varchar(32) | null (no place) \| `LodgingBusiness` |
| `place_locality` | varchar(128) | required when `place_type` is set |
| `place_region` | varchar(128) | |
| `place_country` | char(2) | ISO 3166-1 alpha-2, upper case |
| `place_lat`, `place_lng` | numeric(5,2) / numeric(6,2) | both or neither; rounded to 2 dp on save |
| `booking_url` | varchar(255) | absolute http(s); overrides the business's |

New pojo `Business` (in `pojos/`, no business-tier imports) with its
`.orm.xml`; `Weblog` gains an eager `@ManyToOne business` (the persistence unit runs without weaving, so lazy would be eager anyway) plus the place
fields. New `BusinessManager` (JPA implementation) with `getBusinesses()`,
`getBusiness(id)`, `saveBusiness(b)`, `removeBusiness(b)`, and
`countWeblogsUsing(b)`.

### Cache expiry (silent-failure hazard)

Rendered pages expire only through `weblog.lastModified`. **`saveBusiness`
sets `lastModified = now` on every weblog whose `business_id` is that
business, in the same transaction.** Without this, editing the shared
phone number leaves every blog serving the old one.

### Admin screens

- **Server admin, Businesses** (`/roller-ui/admin/businesses.rol`, list;
  `/roller-ui/admin/businesses!edit.rol`, create and edit; `!delete.rol`,
  POST with `data-confirm`). Global-admin role only. The list shows each
  business's name, type and how many blogs use it. Delete is refused, with an error, while
  `countWeblogsUsing > 0`.
- **Blog Settings → Business** (a new `section-head` in `WeblogConfig.jsp`,
  added to the section index). It has a business dropdown (including
  "None"), place type (None / Lodging), locality, region, country,
  latitude, longitude and booking URL. Validation lives in
  `WeblogConfigController.myValidate`, following the existing pattern
  (`addFieldError`).

### Validation (both screens)

- URLs (`website_url`, `booking_url`, `logo_url`, each `same_as` line):
  absolute http(s) per the `UrlValidator` that `CtaShortcode` uses.
- `telephone`: `^[0-9+()\-. ]{3,32}$`. `email`: commons `EmailValidator`.
- `same_as`: blank lines dropped, at most 10 remaining.
- `business_type` / `place_type`: only the listed values (a forged value
  is a field error, never a silently saved string).
- `place_country`: two letters, stored upper-case.
- Latitude in [-90, 90], longitude in [-180, 180], both or neither.
- `place_locality` is required when `place_type` is set.
- A `business_id` that does not exist is a field error. A lookup that
  throws is also a field error: **a check that could not run is not a
  check that passed** (CLAUDE.md).

### Rendering

**`BusinessJsonLd`** (`business/jsonld/`) builds complete JSON objects,
escaping every string through `StringEscapeUtils.escapeJson`. It returns
null when there is nothing to emit. `UtilitiesModel` exposes it to Velocity
as `$utils.businessJsonLd($weblog)` and
`$utils.businessPublisherJson($weblog)`.

- **Business node:** `@type` = `business_type`; `@id` = absolute site
  URL + `#business-<id>`; `name`, and when set: `url` (website), `telephone`,
  `email`, `logo`, `sameAs` (array), `areaServed`, `description`.
- **Place node** (when `place_type` is set): `@type: LodgingBusiness`;
  `name` = weblog name; `url` = the blog's absolute URL;
  `address` = `PostalAddress` with only `addressLocality`/`addressRegion`/
  `addressCountry`; `geo` = `GeoCoordinates` when both coordinates are set;
  `parentOrganization` = the business node (when one is set).

`#showSeoHead`:
- **Non-entry, non-search pages:** after the existing `Blog` block, a
  second `ld+json` block holds the place node if the blog has one, otherwise
  the business node. The block is omitted when the blog has neither.
- **Entry permalinks:** the `BlogPosting` block gains
  `"publisher": <business node>` when the blog has a business. Nothing
  else in that block changes.

**`#showBusinessCard($weblog)`** (in `weblog.vm`) renders the visible card.
It renders nothing when the blog has neither a business nor a place.
Otherwise it shows the place name and locality line (or the business name
and area served), phone as a `tel:` link, and, when a booking URL resolves, a
booking button. The button carries
`data-umami-event="business-card-click"`, `data-umami-event-dest` and,
on a permalink, `data-umami-event-entry`. It is UTM-tagged like `[cta]`,
with `utm_campaign` set to the slug or omitted. Every value is
`$utils.escapeHTML`'d. Every `<footer>` in the journal, travel and portfolio
themes calls it. Each theme gets one small, token-based style block.

### `[cta]` default booking URL

When `href` is absent, `CtaShortcode` uses `weblog.bookingUrl`, then
`weblog.business.bookingUrl`. If neither is set, the shortcode is left as
written, as it is today without `href`. An explicit `href` always wins. The
editor's Insert card keeps its explicit-`href` snippet.

## Acceptance criteria

Each criterion is checkable by a test. AC numbers are referenced from
the plan.

**Part 1**

1. A rendered `[cta href="https://book.example.com/x" label="Book"]` in an
   entry with anchor `porto-guide` carries
   `data-umami-event="cta-click"`, `data-umami-event-entry="porto-guide"` and
   `data-umami-event-dest="book.example.com"` **after** sanitization
   (asserted on `WeblogEntry.render()` output, not the shortcode alone).
2. With no slug, `data-umami-event-entry` is absent. The other two
   attributes are present.
3. The sanitizer keeps exactly those three `data-umami-*` names on `<a>`
   and strips any other (e.g. `data-umami-event-foo`), and strips all three
   from non-`<a>` elements.
4. A `dest` host is lower-cased and carries no port, path or credentials
   (`https://User@Book.Example.com:8443/p` → `book.example.com`).

**Part 2**

5. `V029` applies cleanly through all three appliers and re-applies as a
   no-op (the existing migration-chain tests cover it once the file
   exists).
6. A business with `ProfessionalService` and every field set produces a
   JSON-LD node with exactly the documented keys. `sameAs` is an array in
   input order.
7. A business whose name contains `</script>` and `"` produces JSON-LD
   that parses and contains no literal `</script>`.
8. A blog with a place produces a `LodgingBusiness` node whose `address`
   has only locality/region/country keys and whose `parentOrganization`
   is the business node. With no business, `parentOrganization` is absent.
9. Saving a place with latitude `38.71234` stores and emits `38.71`.
10. Each validation rule above produces a field error and saves nothing.
    This includes a forged `business_type`, a non-existent `business_id`,
    and a `BusinessManager` lookup that throws.
11. Saving a business sets `lastModified` on every weblog that uses it and on
    no other.
12. Deleting a business used by one or more blogs is refused with an error
    and deletes nothing. Deleting an unused one succeeds.
13. The Businesses pages answer only to a global admin. Every other role
    is refused by the existing interceptor path.
14. A blog's home page with a business emits a business `ld+json`
    block. A permalink's `BlogPosting` has `publisher`. A blog with
    neither emits no extra block and no `publisher` (byte-identical
    `BlogPosting` to today, pinned).
15. Every `<footer>` in the journal, travel and portfolio theme templates
    calls `#showBusinessCard` (a test enumerates the templates, so a new
    footer cannot miss it). The card renders nothing for a blog with no
    business or place.
16. `[cta label="Book"]` with no `href` links to the blog's booking URL,
    else the business's. With neither set, the text is left as written. An
    explicit `href` beats both.
17. Browser IT: a site admin creates a business, a blog owner selects it
    and sets a place, and the public home page shows the card and the
    JSON-LD. The test runs at `/` and at `-Dit.context.path=roller`. Both
    admin routes are added to `Routes` with a CSS marker.
18. All new message keys pass `MessageKeyTest`,
    `MessagePlaceholderContractTest` and `MessageFormatRegressionTest`.
    No offender set grows.

## Documentation

- `docs/dev/content-features.md`, SEO: business/place JSON-LD,
  `#showBusinessCard`, the `[cta]` fallback, and the cache-expiry rule.
- `docs/dev/audience-and-analytics.md`, Analytics: the two Umami event
  names and their properties, and where to read them.
- `docs/dev/admin-ui.md`: the Businesses screen.

## Risks

- **Velocity leniency.** A misspelt `$utils.businessJsonLd` prints as
  literal text. Rendering tests assert on the parsed JSON, not on the
  presence of a substring.
- **Sanitizer allowlist drift.** AC3 pins the exact attribute set.
- **Shared-business cache staleness.** AC11.
- **Ad blockers.** Clicks and pageviews are blocked together, so ratios
  stay meaningful. Absolute counts undercount. This is accepted.
