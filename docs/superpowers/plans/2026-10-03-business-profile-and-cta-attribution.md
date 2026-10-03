# Business Profile and CTA Attribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Inline `[cta]` and footer booking clicks are attributed per entry
in Umami. Every blog can declare the business behind it, and optionally a
locality-only place, as schema.org JSON-LD plus a visible footer card, with
the booking link defined once.

**Architecture:** Part 1 is three `data-umami-*` attributes on the anchor
`CtaShortcode` already builds, plus an exact sanitizer allowance. Part 2 is
one table (`roller_business`, V029) behind a new `BusinessManager` on the
facade, place columns plus an eager `business` reference on `weblog`, a
Java-side `BusinessJsonLd` exposed through `UtilitiesModel`, one shared
`BookingLink` resolver used by both `[cta]` and `#showBusinessCard`, a
global-admin Businesses screen, and a Business section in blog Settings.

**Tech Stack:** Java 25, Spring MVC + JSP/tiles, Velocity, JPA/EclipseLink
(no weaving), PostgreSQL 18, JUnit 5 + Testcontainers, Selenium ITs.

**Spec:** `docs/superpowers/specs/2026-10-03-business-profile-and-cta-attribution-design.md`.
Its acceptance criteria are numbered AC1 to AC18, and every task names the
ACs it owns. Read the spec's "Cache expiry" and "Location privacy" rows
before Task 2.

## Global Constraints

- **Never push. Commit only your own task's files**, one commit per task,
  ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Work directly on `master`. No feature branch. If you work in a worktree,
  pin and verify its base per CLAUDE.md before starting.
- **This wave owns migration `V029` and no other number.** Idempotent DDL
  (`CREATE TABLE IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`). Never edit
  V001 to V028.
- **TDD:** write each test first, run it, and watch it fail for the
  expected reason before writing the code. Manager tests are DB-backed
  through `TestUtils` (pattern: `WeblogRedirectManagerTest`).
- **The build is serialised.** Check
  `pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || echo CLEAR`
  before any `mvn` run, and never run two builds in one tree.
- Per task: `mvn -q -pl app test -Dtest=<YourTests>` while iterating, then the
  full `mvn -q -pl app test` before committing. Tasks 5 to 7 also run
  `mvn -q -pl app verify -DskipTests=false`, because PMD, CPD and SpotBugs
  have zero tolerance and new code must arrive clean, not suppressed.
- **Name every `@RequestParam`/`@PathVariable`** (`ControllerMetadataTest`).
- New manager beans in `WebloggerBeanConfig` take `@Lazy Weblogger` like their
  siblings (`ContextRefreshDoesNotBootstrapTest`). No static service locator
  (`StaticServiceLocatorTest`). `pojos/` import no business-tier type.
- **Escaping:** JSON goes through `StringEscapeUtils.escapeJson` in Java only.
  HTML in Velocity goes through `$utils.escapeHTML`. Author-controlled EL in
  JSPs goes through `fn:escapeXml`. Confirmations use `data-confirm`.
- **Velocity is lenient**: assert on parsed output (JSON parsed, Jsoup
  selectors), never on a substring that a literal `$utils.foo` would also
  satisfy. Never write a zero-arg macro name bare in a comment.
- i18n: new keys go in `ApplicationResources.properties`. The three
  ratchet tests must stay green with no offender set grown (AC18).
- SLF4J: a `Throwable` is the last argument, and no `{}` consumes it.

## Review Focus

Inputs the spec implies but its ACs do not pin. Each one has its test
added to the owning task.

1. **A sparse business** (name only, every optional field blank) emits no
   empty-string keys (`"telephone": ""`), no empty `sameAs` array, and a
   card with just the name. Owned by Task 3 (JSON) and Task 4 (card).
2. **A phone typed with spaces or dots** (`+351 912 345.678`) shows as
   typed but links as `tel:+351912345678`. Owned by Task 4.
3. **A booking URL that already has a query and a fragment**
   (`https://b.example/x?ref=a#dates`) gets its UTM parameters before the
   `#`, and an existing `utm_source` is not duplicated. This holds in both
   `[cta]` and the card, because both go through `BookingLink`. Owned by
   Task 4.
4. **Non-ASCII business and place names** (`São Miguel, Açores`) produce
   JSON-LD that parses and round-trips the exact string. Owned by Task 3.
5. **A blog on a custom domain**: the place node's `url` is the blog's
   public URL on that domain (`URLStrategy.getWeblogURL(weblog, true)`),
   not the site URL. Owned by Task 3.

## File Structure

| File | Responsibility |
|---|---|
| `bin/db/migrations/V029__business_profile.sql` | **new**: `roller_business` table plus the weblog columns |
| `.../pojos/Business.java` + `resources/.../pojos/Business.orm.xml` | **new**: entity, `BusinessType` enum, named queries |
| `.../pojos/Weblog.java` + `Weblog.orm.xml` | `business` (eager many-to-one), place fields, `bookingUrl`; lat/lng rounding in setters |
| `resources/META-INF/persistence.xml` | mapping-file row |
| `.../business/BusinessManager.java` + `jpa/JPABusinessManagerImpl.java` | **new**: CRUD, `countWeblogsUsing`, lastModified touch |
| `.../business/Weblogger.java`, `WebloggerImpl.java`, `jpa/JPAWebloggerImpl.java`, `jpa/WebloggerBeanConfig.java`, test `MockWeblogger.java` | facade wiring |
| `.../business/jsonld/BusinessJsonLd.java` | **new**: business, place and publisher nodes |
| `.../business/BookingLink.java` | **new**: booking-URL resolution, UTM tagging, dest host (moved out of `CtaShortcode`) |
| `.../business/shortcodes/CtaShortcode.java` | Umami attributes; `href` fallback through `BookingLink` |
| `.../util/HTMLSanitizer.java` | exactly three `data-umami-*` names on `<a>` |
| `.../ui/rendering/model/UtilitiesModel.java` | `businessJsonLd`, `businessPublisherJson`, `businessCard` |
| `webapp/WEB-INF/velocity/weblog.vm` | `#showSeoHead` additions; new `#showBusinessCard` |
| `webapp/themes/{journal,travel,portfolio}/*.vm` + theme CSS | card call in every `<footer>`; one style block each |
| `.../ui/controllers/admin/BusinessesController.java` + `BusinessBean.java` + `BusinessRules.java` | **new**: global-admin list/edit/delete, shared validation |
| `webapp/WEB-INF/jsps/admin/Businesses.jsp`, `BusinessEdit.jsp`; `RollerViewResolver`; `ui/menu/admin-menu.xml` | **new** screens and menu item |
| `.../ui/controllers/editor/WeblogConfigController.java`, `WeblogConfigBean.java`, `WEB-INF/jsps/editor/WeblogConfig.jsp` | Business section |
| `it-selenium/.../support/Routes.java`, `it-selenium/.../BusinessProfileIT.java` | routes plus IT |
| `docs/dev/content-features.md`, `audience-and-analytics.md`, `admin-ui.md` | docs |

**Order and parallelism.** Task 1 is independent of everything else and can
run alongside Task 2. Tasks 3, 4, 5 and 6 need Task 2. Tasks 3 and 4 both
edit `weblog.vm`, so run them **sequentially** (3, then 4). Task 5 can
overlap with Task 3 or 4 in a pinned worktree. Task 6 needs `BusinessRules`
from Task 5. Task 7 comes last.

---

### Task 1: CTA click attributes and sanitizer allowance (AC1–AC4)

**Files:**
- Modify: `main/java/.../business/shortcodes/CtaShortcode.java`, `main/java/.../util/HTMLSanitizer.java`
- Test: `test/java/.../business/shortcodes/CtaShortcodeTest.java`, `test/java/.../util/HTMLSanitizerTest.java`, plus one render-seam test in the class that already tests `WeblogEntry.render()` with shortcodes (`grep -rln "render()" test/java/.../pojos` to find it)

**Interfaces:**
- Produces: `static String destHost(String absoluteUrl)` (package-private in
  `CtaShortcode` for now; Task 4 moves it to `BookingLink`). It returns the
  lower-cased host with no userinfo, port or path, or null if the URL
  cannot be parsed.

- [ ] **Step 1: Failing tests**

```java
// CtaShortcodeTest
@Test
void anchorCarriesUmamiClickAttributes() {
    String html = render(Map.of("href", "https://book.example.com/x", "label", "Book"),
            contextWithSlug("porto-guide"));
    Element a = Jsoup.parse(html).selectFirst("a.cta-card");
    assertEquals("cta-click", a.attr("data-umami-event"));
    assertEquals("porto-guide", a.attr("data-umami-event-entry"));
    assertEquals("book.example.com", a.attr("data-umami-event-dest"));
}

@Test
void noSlugOmitsEntryButKeepsEventAndDest() { /* contextWithSlug(null): !a.hasAttr("data-umami-event-entry") */ }

@Test
void destHostDropsUserinfoPortPathAndCase() {
    assertEquals("book.example.com",
            CtaShortcode.destHost("https://User@Book.Example.com:8443/p?q=1"));
}

// HTMLSanitizerTest
@Test
void keepsExactlyTheThreeUmamiAttributesOnAnchors() {
    String out = HTMLSanitizer.conditionallySanitize(
        "<a href=\"https://x.example\" data-umami-event=\"e\" data-umami-event-entry=\"s\""
        + " data-umami-event-dest=\"d\" data-umami-event-foo=\"z\">x</a>"
        + "<div data-umami-event=\"e\">y</div>");
    Element a = Jsoup.parse(out).selectFirst("a");
    assertEquals("e", a.attr("data-umami-event"));
    assertEquals("s", a.attr("data-umami-event-entry"));
    assertEquals("d", a.attr("data-umami-event-dest"));
    assertFalse(a.hasAttr("data-umami-event-foo"));
    assertFalse(Jsoup.parse(out).selectFirst("div").hasAttr("data-umami-event"));
}
```

Use whatever sanitize entry point `HTMLSanitizerTest` already calls. The
names above are illustrative, so match the file. Add the AC1 render-seam
test: an entry whose text is `[cta href="https://book.example.com/x"
label="Book"]` with anchor `porto-guide`, asserting the three attributes on
`entry.render()` output. That is the post-sanitizer seam, and the test
fails today because the sanitizer strips the attributes.

- [ ] **Step 2: Run and watch them fail.** The attributes are missing, the
  sanitizer strips them, and `destHost` does not compile.
- [ ] **Step 3: Implement.** In `render`, after building the final href:
  append `data-umami-event="cta-click"`, then `data-umami-event-entry` only
  when `content.getSlug()` is non-blank, then `data-umami-event-dest` only
  when `destHost` is non-null. Escape every value with `escape(...)`.
  `destHost` uses `java.net.URI` and returns
  `uri.getHost().toLowerCase(Locale.ROOT)`. In `HTMLSanitizer`, add one
  policy line beside the existing `<a>` allowances:

```java
// Umami's declarative click tracking (CtaShortcode). Exactly these three names:
// a data-umami-* wildcard would let authors attach arbitrary event properties.
.allowAttributes("data-umami-event", "data-umami-event-entry",
        "data-umami-event-dest").onElements("a")
```

- [ ] **Step 4: Run the focused tests, then the full suite.**
- [ ] **Step 5: Commit** `feat(cta): clicks on [cta] are recorded as Umami events per entry and destination`

---

### Task 2: Schema, `Business` entity, `BusinessManager`, weblog fields (AC5, AC9, AC11, AC12 manager half)

**Files:**
- Create: `bin/db/migrations/V029__business_profile.sql`, `pojos/Business.java`, `resources/.../pojos/Business.orm.xml`, `business/BusinessManager.java`, `business/jpa/JPABusinessManagerImpl.java`
- Modify: `pojos/Weblog.java`, `Weblog.orm.xml`, `META-INF/persistence.xml`, `Weblogger.java`, `WebloggerImpl.java`, `jpa/JPAWebloggerImpl.java`, `jpa/WebloggerBeanConfig.java`, test `MockWeblogger.java`, test `EqualsContractTest.java`, `jpa/JPAWeblogManagerImpl.java` (only if `removeWeblog` needs nothing: verify and leave it alone; the FK is on `weblog`, so deleting a weblog never touches a business)
- Test: `test/java/.../business/BusinessManagerTest.java` (new), `test/java/.../pojos/WeblogPlaceTest.java` (new)

**Interfaces (produced, used by Tasks 3–6):**

```java
public class Business implements Serializable {
    public enum BusinessType { Organization, LocalBusiness, ProfessionalService }
    // id (UUID), name, businessType, websiteUrl, bookingUrl, telephone, email,
    // logoUrl, sameAs (String, newline-separated), areaServed, description,
    // created, lastModified (Timestamp)
    public List<String> getSameAsList();  // split on \R, trimmed, blanks dropped, order kept
}

// Weblog additions
Business getBusiness(); void setBusiness(Business b);
String getPlaceType();       // null or "LodgingBusiness"
String getPlaceLocality(); String getPlaceRegion(); String getPlaceCountry();
BigDecimal getPlaceLat(); void setPlaceLat(BigDecimal v);  // setScale(2, HALF_UP); null stays null
BigDecimal getPlaceLng(); void setPlaceLng(BigDecimal v);  // same
String getBookingUrl();

public interface BusinessManager {
    List<Business> getBusinesses() throws WebloggerException;          // ordered by name
    Business getBusiness(String id) throws WebloggerException;         // null if absent
    void saveBusiness(Business b) throws WebloggerException;           // also touches users' weblogs
    void removeBusiness(Business b) throws WebloggerException;         // throws if in use
    long countWeblogsUsing(Business b) throws WebloggerException;
}
// Weblogger: BusinessManager getBusinessManager();
```

**Migration** (header comment in the V015 style, saying what and why):

```sql
CREATE TABLE IF NOT EXISTS roller_business (
    id            varchar(48)  NOT NULL PRIMARY KEY,
    name          varchar(255) NOT NULL,
    business_type varchar(32)  NOT NULL,
    website_url   varchar(255),
    booking_url   varchar(255),
    telephone     varchar(32),
    email         varchar(255),
    logo_url      varchar(255),
    same_as       text,
    area_served   varchar(255),
    description   text,
    created       timestamp    NOT NULL,
    last_modified timestamp    NOT NULL
);
ALTER TABLE weblog ADD COLUMN IF NOT EXISTS business_id varchar(48)
    CONSTRAINT weblog_business_fk REFERENCES roller_business(id);
ALTER TABLE weblog ADD COLUMN IF NOT EXISTS place_type     varchar(32);
ALTER TABLE weblog ADD COLUMN IF NOT EXISTS place_locality varchar(128);
ALTER TABLE weblog ADD COLUMN IF NOT EXISTS place_region   varchar(128);
ALTER TABLE weblog ADD COLUMN IF NOT EXISTS place_country  char(2);
ALTER TABLE weblog ADD COLUMN IF NOT EXISTS place_lat      numeric(5,2);
ALTER TABLE weblog ADD COLUMN IF NOT EXISTS place_lng      numeric(6,2);
ALTER TABLE weblog ADD COLUMN IF NOT EXISTS booking_url    varchar(255);
CREATE INDEX IF NOT EXISTS weblog_business_idx ON weblog(business_id);
```

**Mapping:** in `Weblog.orm.xml`, declare `business` as a `<many-to-one>` with
`<join-column name="business_id"/>` and **no `fetch="LAZY"`**. The unit runs
without weaving, so lazy would silently load eagerly anyway. Eager is the
honest declaration, and the row is tiny. `Business.orm.xml` declares
`Business.getAll` (`ORDER BY b.name`) and
`Weblog.countByBusiness` (`SELECT COUNT(w) FROM Weblog w WHERE w.business = ?1`).

- [ ] **Step 1: Failing tests.**
  - `WeblogPlaceTest` (plain unit test, AC9):
    `setPlaceLat(new BigDecimal("38.71234"))` gives `38.71`, `-9.145` gives
    `-9.15` (HALF_UP), and null stays null.
  - `BusinessManagerTest` (DB-backed, pattern `WeblogRedirectManagerTest`):
    - `saveAndReadBack`: every field round-trips; `getSameAsList()` keeps order and drops blanks.
    - `savingBusinessTouchesOnlyWeblogsUsingIt` (AC11): weblogs A and B use business X, and C does not. Set all three `lastModified` to a fixed old date and flush, then `saveBusiness(X)`. A and B are after the fixed date; C is unchanged.
    - `removeRefusedWhileInUse` (AC12): `assertThrows(WebloggerException.class, () -> mgr.removeBusiness(X))` while A uses it. After A's business is set to null, removal succeeds and `getBusiness(id)` is null.
    - `countWeblogsUsing` returns 2 for X.
    - `placeColumnsRoundTrip`: `placeLat` `38.71234` reads back `38.71` from the DB (AC9, persistence half).
- [ ] **Step 2: Run them and watch them fail** (compilation first, then the touch and refusal behaviour).
- [ ] **Step 3: Implement.** In `saveBusiness`, set `lastModified = now`,
  call `strategy.store(b)`, then run a JPQL bulk update
  `UPDATE Weblog w SET w.lastModified = ?1 WHERE w.business = ?2`. **A
  bulk update bypasses the EclipseLink shared cache**, so after it, evict
  those weblogs, or simpler: load them with a query and call
  `setLastModified` on each managed instance (10–50 blogs; prefer this,
  since it stays coherent with the cache). Comment the reason, citing the
  spec's "Cache expiry". `removeBusiness` checks `countWeblogsUsing > 0`
  and throws `WebloggerException("business in use by N weblogs")`; the FK
  is the backstop. Wire the facade exactly as `WeblogRedirectManager` was
  wired (`git show cc4120c17 --stat`).
- [ ] **Step 4: Run focused, then the full suite.** `SchemaMigrationTest`,
  `DatabaseInstallerMigrationTest`, `SqlScriptRunnerMigrationTest` and
  `MigrationCatalogTest` cover AC5 now that the file exists. Run them by
  name and confirm they ran V029. Check
  `it-selenium/src/test/resources/seed-it-data.sql` for column-list
  `INSERT INTO weblog` statements; the new columns are nullable, so none
  should break. Report what you found.
- [ ] **Step 5: Commit** `feat(business): a shared business record and per-blog place fields (V029)`

---

### Task 3: `BusinessJsonLd` and `#showSeoHead` (AC6, AC7, AC8, AC14; Review Focus 1, 4, 5)

**Files:**
- Create: `main/java/.../business/jsonld/BusinessJsonLd.java`
- Modify: `UtilitiesModel.java`, `webapp/WEB-INF/velocity/weblog.vm` (`#showSeoHead` only)
- Test: `test/java/.../business/jsonld/BusinessJsonLdTest.java` (new); add rendering cases to `JournalThemeRenderingTest`

**Interfaces:**
- Consumes: `Weblog.getBusiness()`, place getters, `Business.getSameAsList()` (Task 2).
- Produces:

```java
public final class BusinessJsonLd {
    /** Business node, or null when weblog has no business. siteUrl = absolute site URL. */
    static Map<String, Object> businessNode(Business b, String siteUrl);
    /** Place node with parentOrganization, or null when placeType is null. */
    static Map<String, Object> placeNode(Weblog w, String weblogAbsoluteUrl, String siteUrl);
    /** Complete JSON for the non-entry block: place if present, else business, else null. */
    public static String forWeblog(Weblog w, String weblogAbsoluteUrl, String siteUrl);
    /** JSON object (no @context) for BlogPosting.publisher, or null. */
    public static String publisherFor(Weblog w, String siteUrl);
}
// UtilitiesModel: String businessJsonLd(Weblog w), String businessPublisherJson(Weblog w)
//   (Velocity passes $model.weblog.pojo, the same unwrapping #showBusinessCard uses)
//   resolve weblogAbsoluteUrl via URLStrategy.getWeblogURL(weblog, true) and siteUrl via
//   WebloggerRuntimeConfig.getAbsoluteContextURL(), the same sources travelJsonLd's callers use.
```

Serialise with the same hand-rolled `escapeJson` writer `EntryJsonLd` uses.
Extract its map-to-JSON writer into a package-private
`JsonLdWriter.write(Map)` if it is private, rather than duplicating it,
because CPD runs at 110 tokens. `@id` for a business is
`siteUrl + "#business-" + b.getId()`.

- [ ] **Step 1: Failing tests** (`BusinessJsonLdTest`, parse every output with Jackson's `ObjectMapper` and assert on the tree):
  - AC6 `fullProfessionalService`: exact key set
    `{@context,@type,@id,name,url,telephone,email,logo,sameAs,areaServed,description}`;
    `sameAs` is an array in input order.
  - AC7 `scriptBreakoutIsEscaped`: name `Ana </script><b>"x"</b>` gives JSON that parses, a raw output containing no `</script>`, and a parsed name equal to the input.
  - AC8 `placeIsLocalityOnly`: `address` keys are exactly
    `{@type,addressLocality,addressRegion,addressCountry}`; `geo` is present when both coordinates are set;
    `parentOrganization.@id` equals the business `@id`. `placeWithoutBusiness` has no `parentOrganization`.
  - RF1 `sparseBusiness`: name only gives exactly `{@context,@type,@id,name}`.
  - RF4 `nonAsciiRoundTrips`: `São Miguel, Açores` parses back equal.
  - RF5 `placeUrlIsTheWeblogUrl`: the `url` equals the `weblogAbsoluteUrl` argument.
    The custom-domain case is a rendering test (below), which proves
    `UtilitiesModel` passes `getWeblogURL(weblog, true)`.
  - Rendering (`JournalThemeRenderingTest`, AC14): a blog with a business has
    two `ld+json` blocks on its home page, and the second parses to
    `@type` = the business type. A permalink's `BlogPosting` parses with
    `publisher.name`. **A blog with neither has a `BlogPosting` block that
    is byte-identical to a fixture captured before this change.** Capture
    that fixture in Step 1, before touching `weblog.vm`, and say so in its
    javadoc, since it is a characterisation test. Add a custom-domain
    case: set `customDomain`, render, and assert the place `url` host
    equals that domain.
- [ ] **Step 2: Watch them fail.**
- [ ] **Step 3: Implement.** In `#showSeoHead`: in the `BlogPosting`
  block, add `#if($seoPublisher)"publisher": $seoPublisher,#end` with
  `#set($seoPublisher = false)` before it. Velocity's `#set` to null
  keeps the old value, so the reset is required. In the `#elseif
  (!$model.searchResults)` branch, after the `Blog` block, emit a second
  `<script type="application/ld+json">` when
  `$utils.businessJsonLd($model.weblog.pojo)` is non-null, using the same
  reset-then-set pattern.
- [ ] **Step 4: Run focused, then the full suite**, including
  `TravelThemeRenderingTest` and `PortfolioThemeRenderingTest`, which pin
  heads byte-for-byte.
- [ ] **Step 5: Commit** `feat(seo): blogs declare their business and place as schema.org JSON-LD`

---

### Task 4: `BookingLink`, `[cta]` fallback and `#showBusinessCard` (AC15, AC16; Review Focus 1, 2, 3)

**Files:**
- Create: `main/java/.../business/BookingLink.java`
- Modify: `CtaShortcode.java` (move `withUtmParams`, `existingParamNames`, `appendParam` and `destHost` into `BookingLink`; add the fallback), `UtilitiesModel.java`, `weblog.vm` (new macro), `themes/journal/{weblog,permalink}.vm`, `themes/travel/{weblog,permalink}.vm`, `themes/portfolio/{page,permalink,weblog}.vm` and any other template with a `<footer`, each theme's CSS
- Test: `test/java/.../business/BookingLinkTest.java` (new), `CtaShortcodeTest`, `test/java/.../ui/rendering/BusinessCardRenderingTest.java` (new)

**Interfaces:**

```java
public final class BookingLink {
    /** weblog.bookingUrl, else weblog.business.bookingUrl, else null. */
    public static String resolve(Weblog w);
    /** href with utm_source=handle, utm_medium=blog, utm_campaign=slug added before any #,
        skipping params already present (moved verbatim from CtaShortcode). */
    public static String withUtmParams(String href, Weblog w, String slug);
    public static String destHost(String absoluteUrl);
}
// UtilitiesModel: Map<String,String> businessCard(Weblog w, String slug)
//   keys: name, locality (joined "Locality, Region"), telephone (as typed),
//   telHref ("tel:" + digits and leading +), bookingHref (UTM-tagged), dest; null when nothing to show
```

`#showBusinessCard($weblog)` reads `$utils.businessCard($weblog.pojo, $slug)`,
where `$slug` is `$model.weblogEntry.anchor` on a permalink and empty
otherwise. It renders nothing when the map is null. Otherwise:

```html
<aside class="business-card">
  <p class="business-card-name">$utils.escapeHTML($card.name)</p>
  #if($card.locality)<p class="business-card-locality">$utils.escapeHTML($card.locality)</p>#end
  #if($card.telHref)<a class="business-card-tel" href="$utils.escapeHTML($card.telHref)">$utils.escapeHTML($card.telephone)</a>#end
  #if($card.bookingHref)<a class="business-card-book" href="$utils.escapeHTML($card.bookingHref)"
     rel="noopener" target="_blank" data-umami-event="business-card-click"
     data-umami-event-dest="$utils.escapeHTML($card.dest)"#if($slug) data-umami-event-entry="$utils.escapeHTML($slug)"#end>Book</a>#end
</aside>
```

The button label "Book" is theme copy, like the subscribe prompts the
themes already pass. Put it in a macro parameter so each theme words it:
`#showBusinessCard($model.weblog "Book a stay")`.

- [ ] **Step 1: Failing tests.**
  - `BookingLinkTest`: `resolve` prefers the weblog's URL, then the business's, then null. RF3: `https://b.example/x?ref=a&utm_source=keep#dates` becomes `...?ref=a&utm_source=keep&utm_medium=blog&utm_campaign=s#dates`.
  - `CtaShortcodeTest` AC16: with no `href`, it uses the weblog URL, then the business URL, then returns `null` (text left as written). An explicit `href` beats both. The existing UTM tests keep passing, unchanged, because the code moved and its behaviour did not.
  - `BusinessCardRenderingTest`:
    - AC15 enumeration: walk `webapp/themes/{journal,travel,portfolio}`, and for every `.vm` containing `<footer`, assert it contains `#showBusinessCard(`. Use a file walk, not a hard-coded list, so a new footer cannot miss it.
    - A blog with neither business nor place renders no `.business-card` (Jsoup) in each theme.
    - RF1: a name-only business gives a card with the name and no tel or book links.
    - RF2: telephone `+351 912 345.678` shows exactly that text with `href="tel:+351912345678"`.
    - On a permalink, the book link carries `data-umami-event-entry` equal to the anchor.
- [ ] **Step 2: Watch them fail.**
- [ ] **Step 3: Implement**, moving code before changing it: move the UTM
  helpers to `BookingLink`, run `CtaShortcodeTest` green, then add the
  fallback. Add one small token-based style block per theme. Never rename
  an existing selector, because `Routes` pins CSS markers.
- [ ] **Step 4: Run focused, then the full suite.**
- [ ] **Step 5: Commit** `feat(themes): footer business card; [cta] without href uses the blog's booking link`

---

### Task 5: Businesses admin screen and `BusinessRules` (AC10 business half, AC12 UI, AC13, AC18)

**Files:**
- Create: `ui/controllers/admin/BusinessesController.java`, `BusinessBean.java`, `ui/controllers/BusinessRules.java` (shared with Task 6), `WEB-INF/jsps/admin/Businesses.jsp`, `BusinessEdit.jsp`
- Modify: `RollerViewResolver.java` (`.Businesses`, `.BusinessEdit`), `resources/.../ui/menu/admin-menu.xml` (a `businesses` menu item, `subactions="editBusiness"`), `ApplicationResources.properties`
- Test: `test/java/.../ui/controllers/admin/BusinessesControllerTest.java`, `test/java/.../ui/controllers/BusinessRulesTest.java`

**Interfaces:**

```java
public final class BusinessRules {
    static final Pattern TELEPHONE = Pattern.compile("^[0-9+()\\-. ]{3,32}$");
    public static boolean isHttpUrl(String s);          // same UrlValidator(http, https) as CtaShortcode
    public static boolean isEmail(String s);            // commons EmailValidator
    public static boolean isTelephone(String s);
    public static List<String> sameAsLines(String raw); // trimmed, blanks dropped
    public static final int MAX_SAME_AS = 10;
}
```

Routes: `GET /roller-ui/admin/businesses.rol` (list), `GET
/roller-ui/admin/businesses!edit.rol?id=` (blank id means new), `POST
businesses!save.rol`, `POST businesses!delete.rol` (`data-confirm`).
Controller pattern: `GlobalConfigController`, with `isWeblogRequired()
false` and `requiredGlobalPermissionActions()` = admin. The list shows name,
type and `countWeblogsUsing`.

- [ ] **Step 1: Failing tests.**
  - `BusinessRulesTest`: table-driven valid and invalid cases for each rule.
  - `BusinessesControllerTest` (pattern `UserAdminControllerTest`):
    - AC10: each invalid field gets its field error and `saveBusiness` is never called. This covers a forged `businessType` (`"Hotel"`), more than 10 `sameAs` lines, `javascript:` in `websiteUrl`, and a blank name.
    - A valid save calls `saveBusiness` and redirects to the list.
    - AC12: deleting a business in use shows the error key and does not remove it; an unused business is removed.
    - AC13: the controller declares the admin global permission and is weblog-agnostic. Follow how `UserAdminControllerTest` pins this.
- [ ] **Step 2: Watch them fail.** **Step 3: Implement** the controller,
  bean and JSPs. Every `bean.*` value in the JSPs goes through
  `fn:escapeXml`. User-typed message arguments are escaped in the
  controller (`MessageArgumentEscapingTest`). Add the message keys.
- [ ] **Step 4: Run focused, then the full suite, then `mvn -q -pl app verify -DskipTests=false`.**
- [ ] **Step 5: Commit** `feat(admin): site admins manage shared businesses`

---

### Task 6: Blog Settings → Business section (AC10 place half, AC18)

**Files:**
- Modify: `WeblogConfigBean.java` (businessId, placeType, placeLocality, placeRegion, placeCountry, placeLat, placeLng, bookingUrl; `copyFrom`/`copyTo`), `WeblogConfigController.java` (`myValidate`, model attribute `businesses`), `WEB-INF/jsps/editor/WeblogConfig.jsp` (new `<h3 class="section-head" id="settings-business">` plus section-index entry), `ApplicationResources.properties`
- Test: `WeblogConfigControllerTest`

- [ ] **Step 1: Failing tests (AC10).** Each of the following adds its field error and does not save:
  - an unknown `businessId`;
  - `getBusiness` throwing (the fail-closed rule from CLAUDE.md: `addFieldError`, never fall through);
  - a `placeType` other than blank or `LodgingBusiness`;
  - `placeType` set with a blank locality;
  - country `prt` or `P1` (valid input is exactly two letters; lower-case `pt` is accepted and stored as `PT`, which a separate positive test pins);
  - latitude 91; longitude without latitude;
  - a non-http booking URL.

  A valid save copies every field, `copyTo` sets `weblog.setBusiness(manager.getBusiness(id))`, and a blank `businessId` clears the business.
- [ ] **Step 2: Watch them fail.** **Step 3: Implement**, reusing `BusinessRules`. The business dropdown lists `getBusinesses()` plus "None". Country is a text input with `maxlength="2"`.
- [ ] **Step 4: Run focused, then the full suite, then `verify`.**
- [ ] **Step 5: Commit** `feat(settings): a blog picks its business and describes its place`

---

### Task 7: Browser IT, Routes and docs (AC17)

**Files:**
- Modify: `it-selenium/src/test/java/org/apache/roller/it/support/Routes.java`: add `businesses.rol` (Role.ADMIN, marker = the list table's app-owned class) and `businesses!edit.rol` (Role.ADMIN, marker = the form's `action$=` selector, following the `userAdmin` entry).
- Create: `it-selenium/src/test/java/org/apache/roller/it/BusinessProfileIT.java`
- Modify: `docs/dev/content-features.md` (SEO: the JSON-LD nodes, `#showBusinessCard`, the `[cta]` fallback, the cache-expiry rule), `docs/dev/audience-and-analytics.md` (Analytics: `cta-click` and `business-card-click`, their properties, where to read them in Umami), `docs/dev/admin-ui.md` (Businesses screen)

- [ ] **Step 1: Write the IT** (pattern `RedirectIT`; it cleans up by identity per `docs/dev/browser-its.md`). The admin creates business "IT Rentals" with a booking URL. A blog owner selects it and sets place `LodgingBusiness`, locality `Porto`, country `PT`. The public home page shows `.business-card` containing "Porto", and its second `ld+json` parses with `@type` `LodgingBusiness` and `parentOrganization.name` "IT Rentals". Clean up afterwards: unset the blog's business, then delete the business.
- [ ] **Step 2: Run it**, watch it fail if run before the routes and seed are right, then pass:
  `mvn verify -Pit -Dit.test=BusinessProfileIT` and again with `-Dit.context.path=roller`.
  Then run the whole browser suite once at each context path, since this
  wave adds admin routes.
- [ ] **Step 3: Docs.** **Step 4: Commit** `test(it): business profile end to end; docs`

---

## Spec coverage

| AC | Task |
|---|---|
| 1–4 | 1 |
| 5, 9, 11 | 2 |
| 6, 7, 8, 14 | 3 |
| 15, 16 | 4 |
| 10 | 5 (business), 6 (place) |
| 12 | 2 (manager), 5 (UI) |
| 13 | 5 |
| 17 | 7 |
| 18 | 5, 6 |

**Spec amended while planning:** the spec said "lazy `@ManyToOne`". The
persistence unit has no weaving, so the spec now says eager (Task 2).
