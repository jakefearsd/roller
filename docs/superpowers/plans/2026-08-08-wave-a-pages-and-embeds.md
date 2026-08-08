# Wave A — Pages & Embeds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Markdown-authored static Pages (About / Services / Contact) served at `/<handle>/<slug>`, plus a `[video]` shortcode for YouTube and Vimeo embeds.

**Architecture:** A new `WeblogPage` entity with its own table and manager — deliberately *not* a `WeblogEntry` discriminator, so a page cannot leak into feeds, archives, the Lucene index or tag aggregates. Both entities share a rendering pipeline through a new `ContentRenderer`, and both satisfy a new `ShortcodeContext` interface so every shortcode works on either. `[video]` parses known URL shapes and emits a placeholder `<div>` that a theme macro turns into a click-to-play facade — the same pattern `[map]` already uses to get past the iframe-stripping sanitizer.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring MVC, EclipseLink JPA, PostgreSQL 16, Velocity (public rendering), JSP/JSTL (admin UI), commonmark-java, OWASP HTML Sanitizer, JUnit 5 + Mockito + Testcontainers, Selenide (browser ITs).

**Spec:** `docs/superpowers/specs/2026-08-08-pages-audience-analytics-design.md`

## Global Constraints

Every task inherits these. They are copied verbatim from the spec.

- **`weblogAdminsUntrusted` stays `true`.** No role-keyed sanitizer bypass, and no raw HTML from a weblog admin reaches `<head>` or entry output.
- **The only authorised theme-CSP change in this wave** is adding `frame-src https://www.youtube-nocookie.com https://player.vimeo.com` for `[video]`. It is additive and host-scoped: `script-src`, `connect-src` and `default-src 'none'` are untouched. The pinned tests are updated in the **same commit** that changes the themes.
- **No server-side fetch of an author-supplied URL** anywhere in this wave. `[video]` parses; it never requests.
- **No GPL-licensed dependencies.**
- **Every schema change adds a numbered idempotent migration** under `bin/db/migrations/`; never edit an applied one.
- **Controllers name every `@RequestParam`/`@PathVariable` explicitly** — the build does not pass `-parameters`, so a bare `@RequestParam String id` throws at runtime while unit tests keep passing. `ControllerMetadataTest` fails the build on any unnamed one.
- **Ownership-check every id** through the `BaseController.lookup*` family. The permission interceptor vouches only for the *action* weblog, so a global by-id lookup lets any editor rewrite any weblog's data. Treat a blank id as absent, not as something to look up.
- **Render order is load-bearing:** shortcodes → markdown → sanitize. Markdown first would escape the quotes in `[gallery dir="x"]` and every attribute-carrying shortcode would silently stop working.
- **Coverage gates:** ~90% diff coverage on changed lines (`bin/check-diff-coverage.sh`); JaCoCo floors only ever rise; a browser IT for every new public surface and every new admin screen.
- **Tests clean up after themselves.** Nothing truncates tables between tests: create fixtures via `TestUtils.setupX(...)` and remove them in `@AfterEach` with `teardownWeblog`/`teardownUser` + `endSession(true)`. Tests touching the rendering path call `CacheManager.clear()` in `@BeforeEach`.
- **Commit on `master`.** Solo-developer repo; do not create a feature branch.

## File Structure

**Shortcode & render layer**

| File | Responsibility |
| --- | --- |
| `business/shortcodes/ShortcodeContext.java` *(new)* | The three things handlers need from whatever is being rendered |
| `business/shortcodes/ShortcodeHandler.java` | Signature changes `WeblogEntry` → `ShortcodeContext` |
| `business/shortcodes/ShortcodeExpander.java` | Same, plus registers `VideoShortcode` |
| `business/shortcodes/{Image,Gallery,Map,Cta,Faq}Shortcode.java` | Signature updates |
| `business/shortcodes/{MapPins,FaqBlocks}.java` | Static helpers take `ShortcodeContext` |
| `business/shortcodes/VideoShortcode.java` *(new)* | Provider allowlist, URL parsing, placeholder emission |
| `business/ContentRenderer.java` *(new)* | shortcodes → markdown → sanitize, shared by entries and pages |
| `pojos/WeblogEntry.java` | Implements `ShortcodeContext`; `render` delegates to `ContentRenderer` |
| `util/HTMLSanitizer.java` | Grants `data-provider` / `data-video-id` on `div` |

**Page layer**

| File | Responsibility |
| --- | --- |
| `bin/db/migrations/V014__weblog_pages.sql` *(new)* | `roller_weblogpage` |
| `pojos/WeblogPage.java` *(new)* | The entity; implements `ShortcodeContext` |
| `pojos/ReservedSlugs.java` *(new)* | Single source of truth for reserved first-path segments |
| `resources/.../pojos/WeblogPage.orm.xml` *(new)* | Mapping + named queries |
| `business/WeblogPageManager.java` + `business/jpa/JPAWeblogPageManagerImpl.java` *(new)* | CRUD and lookup by slug |
| `ui/rendering/util/WeblogPageRequest.java` | Resolves a bare slug to a page |
| `ui/rendering/servlets/PageServlet.java` | `selectTemplate` returns the page template |
| `ui/rendering/model/PageModel.java` | Exposes `$model.page` |
| `WEB-INF/velocity/templates/weblog/page.vm` *(new)* | Shipped default page template |
| `ui/controllers/editor/{PagesController,PageEditController,PageBean}.java` *(new)* | Admin CRUD |
| `WEB-INF/jsps/editor/{Pages,PageEdit}.jsp` *(new)* | Admin screens |
| `WEB-INF/velocity/weblog.vm` | `#showPageLinks`, `#showEmbedAssets`, `#showPageMenu` fold-in |
| `ui/controllers/core/SeoController.java` | Pages join the sitemap |

---

# Task 1: Extract `ShortcodeContext`

Behaviour-preserving refactor. Nothing renders differently; the whole point is that the 192 existing rendering tests keep passing untouched.

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/business/shortcodes/ShortcodeContext.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/shortcodes/ShortcodeHandler.java:61`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/shortcodes/ShortcodeExpander.java:150,154,218`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/shortcodes/ImageShortcode.java`, `GalleryShortcode.java`, `MapShortcode.java`, `CtaShortcode.java`, `FaqShortcode.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/shortcodes/MapPins.java`, `FaqBlocks.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/pojos/WeblogEntry.java:343` area
- Test: `app/src/test/java/org/apache/roller/weblogger/business/shortcodes/ShortcodeContextTest.java`

**Interfaces:**
- Produces: `ShortcodeContext` with `Weblog getWeblog()`, `String getSlug()`, `String getRawText()`. `WeblogEntry implements ShortcodeContext`. `ShortcodeExpander.expand(ShortcodeContext, String)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/apache/roller/weblogger/business/shortcodes/ShortcodeContextTest.java`:

```java
package org.apache.roller.weblogger.business.shortcodes;

import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * WeblogEntry satisfies ShortcodeContext by delegating to the accessors it
 * already had. This is what lets expand(entry, text) keep compiling at every
 * existing call site while handlers stop depending on WeblogEntry.
 */
class ShortcodeContextTest {

    @Test
    void aWeblogEntryIsAShortcodeContext() {
        Weblog weblog = new Weblog();
        weblog.setHandle("travelblog");

        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setAnchor("hiking-in-spain");
        entry.setText("the pyrenees are extraordinary");

        ShortcodeContext context = entry;

        assertEquals(weblog, context.getWeblog(),
                "getWeblog must be the entry's own weblog");
        assertEquals("hiking-in-spain", context.getSlug(),
                "an entry's slug is its anchor");
        assertEquals("the pyrenees are extraordinary", context.getRawText(),
                "raw text is the pre-expansion source MapPins and FaqBlocks re-parse");
    }

    @Test
    void anEntryWithNothingSetReportsNullsRatherThanThrowing() {
        ShortcodeContext context = new WeblogEntry();

        assertNull(context.getWeblog());
        assertNull(context.getSlug());
        assertNull(context.getRawText());
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=ShortcodeContextTest`
Expected: FAIL — compilation error, `ShortcodeContext` does not exist.

- [ ] **Step 3: Create the interface**

Create `app/src/main/java/org/apache/roller/weblogger/business/shortcodes/ShortcodeContext.java` with the standard ASF licence header used by every file in this package, then:

```java
package org.apache.roller.weblogger.business.shortcodes;

import org.apache.roller.weblogger.pojos.Weblog;

/**
 * What a {@link ShortcodeHandler} needs to know about the thing being
 * rendered. Deliberately three methods: a survey of the five built-in
 * handlers found they use their subject for exactly this much -- the weblog
 * (media lookups, UTM source), a slug (UTM campaign), and the unexpanded
 * source text ({@link MapPins} and {@link FaqBlocks} re-parse it to build the
 * JSON-LD twin of what the shortcode renders).
 *
 * <p>Both {@link org.apache.roller.weblogger.pojos.WeblogEntry} and
 * {@link org.apache.roller.weblogger.pojos.WeblogPage} implement this, which
 * is what lets every shortcode work on a page without the handlers knowing
 * pages exist.
 *
 * <p>Implementations may return null from any method; handlers already treat
 * a missing subject as "render what you can" rather than failing.
 */
public interface ShortcodeContext {

    /** The weblog this content belongs to, or null when unavailable. */
    Weblog getWeblog();

    /** An entry's anchor or a page's slug; null when unavailable. */
    String getSlug();

    /** The source text before shortcode expansion; null when unavailable. */
    String getRawText();
}
```

- [ ] **Step 4: Make `WeblogEntry` implement it**

In `app/src/main/java/org/apache/roller/weblogger/pojos/WeblogEntry.java`, add the import and the interface to the class declaration, then add these three delegating getters next to `getWebsite()` (around line 343):

```java
    // ---- ShortcodeContext. Delegates to the accessors this class already
    // had; WeblogEntry.orm.xml is metadata-complete, so an extra getter
    // cannot create a phantom persistent field.

    @Override
    public Weblog getWeblog() {
        return getWebsite();
    }

    @Override
    public String getSlug() {
        return getAnchor();
    }

    @Override
    public String getRawText() {
        return getText();
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl app test -Dtest=ShortcodeContextTest`
Expected: PASS.

- [ ] **Step 6: Widen the handler and expander signatures**

In `ShortcodeHandler.java`, change the import from `WeblogEntry` to `ShortcodeContext`, rename the parameter, and update the javadoc `@param`:

```java
    /**
     * @param content    the entry or page being rendered, for weblog context
     */
    String render(Map<String, String> attributes, String body, ShortcodeContext content);
```

In `ShortcodeExpander.java`, change `expand(WeblogEntry entry, String text)` and the private `expand(WeblogEntry entry, String text, int depth)` to take `ShortcodeContext content`, and update the `handler.render(...)` call at line 218.

In each of `ImageShortcode`, `GalleryShortcode`, `MapShortcode`, `CtaShortcode`, `FaqShortcode`, change the `render` parameter type to `ShortcodeContext` and replace `entry.getWebsite()` → `content.getWeblog()`, `entry.getAnchor()` → `content.getSlug()`, `entry.getText()` → `content.getRawText()`.

In `MapPins` and `FaqBlocks`, change the static helpers (`MapShortcode.pinsInEntry`, `autoPins`, `centerOf`, and the `FaqBlocks` equivalents) to take `ShortcodeContext`. Keep the method names; only the parameter type changes.

- [ ] **Step 7: Fix the two other call sites**

`PluginManagerImpl.java:113` and `WeblogEntry.java:1169` pass a `WeblogEntry`, which is now a `ShortcodeContext`, so **neither line changes**. Verify by compiling.

Run: `mvn -pl app -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Fix tests that call handlers directly**

Only tests invoking `handler.render(attrs, body, entry)` need editing — those going through `expand()` are unaffected. Find them:

```bash
grep -rln "\.render(" app/src/test/java/org/apache/roller/weblogger/business/shortcodes/
```

For each, the variable stays a `WeblogEntry`; no change is needed unless the test declares the parameter type explicitly. Compile the tests to find the real list:

Run: `mvn -pl app -DskipTests test-compile`
Expected: BUILD SUCCESS after fixes.

- [ ] **Step 9: Prove the refactor is behaviour-preserving**

Run: `mvn -pl app test -Dtest='*Shortcode*Test,*Rendering*Test'`
Expected: PASS, with the same test count as before the refactor. **This is the evidence the refactor is safe — not the new test.** If any assertion changed, the refactor changed behaviour and must be corrected rather than the test updated.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/business/shortcodes/ \
        app/src/main/java/org/apache/roller/weblogger/pojos/WeblogEntry.java \
        app/src/test/java/org/apache/roller/weblogger/business/shortcodes/
git commit -m "Extract ShortcodeContext so shortcodes stop depending on WeblogEntry"
```

---

# Task 2: Extract `ContentRenderer`

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/business/ContentRenderer.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/pojos/WeblogEntry.java:1141-1187`
- Test: `app/src/test/java/org/apache/roller/weblogger/business/ContentRendererTest.java`

**Interfaces:**
- Consumes: `ShortcodeContext` (Task 1).
- Produces: `ContentRenderer.render(ShortcodeContext content, String text)` returning fully rendered, sanitized HTML.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/apache/roller/weblogger/business/ContentRendererTest.java`:

```java
package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.business.shortcodes.ShortcodeContext;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared pipeline: shortcodes, then markdown, then sanitize. The order is
 * load-bearing and each test here pins one reason why.
 */
class ContentRendererTest {

    private static ShortcodeContext context(String rawText) {
        Weblog weblog = new Weblog();
        weblog.setHandle("testblog");
        return new ShortcodeContext() {
            @Override public Weblog getWeblog() { return weblog; }
            @Override public String getSlug() { return "a-slug"; }
            @Override public String getRawText() { return rawText; }
        };
    }

    @Test
    void markdownBecomesHtml() {
        String html = ContentRenderer.render(context("**bold**"), "**bold**");

        assertTrue(html.contains("<strong>bold</strong>"), "got: " + html);
    }

    /**
     * Shortcodes expand BEFORE markdown. Markdown first would escape the
     * quotes in an attribute to &quot;, and the expander's attribute grammar
     * does not match entity-quoted values -- so every shortcode carrying an
     * attribute would silently stop working.
     */
    @Test
    void anUnknownShortcodeSurvivesByteForByte() {
        String source = "[nosuchcode attr=\"value\"]";

        String html = ContentRenderer.render(context(source), source);

        assertTrue(html.contains("[nosuchcode attr=\"value\"]"),
                "an unknown shortcode must pass through unchanged: " + html);
    }

    /** The sanitizer is the security boundary, and it runs last. */
    @Test
    void scriptIsStrippedEvenThoughRawHtmlPassesThroughMarkdown() {
        String source = "<p>ok</p><script>alert(1)</script>";

        String html = ContentRenderer.render(context(source), source);

        assertTrue(html.contains("ok"));
        assertFalse(html.contains("<script"), "the sanitizer must remove it: " + html);
    }

    @Test
    void nullTextRendersAsNullRatherThanThrowing() {
        ContentRenderer.render(context(null), null);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=ContentRendererTest`
Expected: FAIL — `ContentRenderer` does not exist.

- [ ] **Step 3: Create `ContentRenderer`**

Create `app/src/main/java/org/apache/roller/weblogger/business/ContentRenderer.java` with the ASF header, then:

```java
package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.business.shortcodes.ShortcodeContext;
import org.apache.roller.weblogger.business.shortcodes.ShortcodeExpander;
import org.apache.roller.weblogger.util.HTMLSanitizer;

/**
 * The content pipeline every authored surface shares: shortcode expansion,
 * then Markdown, then sanitization.
 *
 * <p>The order is load-bearing and deliberately not the one it looks like it
 * should be. Markdown first would escape the quotes in
 * {@code [gallery dir="Iceland"]} to {@code &quot;}, and the expander's
 * attribute grammar does not match entity-quoted values, so every shortcode
 * carrying an attribute would silently stop working. Expanding first is safe
 * because commonmark passes raw HTML through verbatim in block and inline
 * positions alike; the only cost is that markdown syntax inside a shortcode's
 * own emitted text is interpreted, which is cosmetic.
 *
 * <p>Raw HTML is deliberately not escaped: the shortcodes emit HTML. The
 * sanitizer at the end is the security boundary.
 */
public final class ContentRenderer {

    private ContentRenderer() {
    }

    /**
     * @param content what is being rendered, for weblog and slug context
     * @param text    the source; null returns null
     */
    public static String render(ShortcodeContext content, String text) {
        if (text == null) {
            return null;
        }
        String out = ShortcodeExpander.defaultExpander().expand(content, text);
        out = MarkdownRenderer.render(out);
        return HTMLSanitizer.conditionallySanitize(out);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl app test -Dtest=ContentRendererTest`
Expected: PASS.

- [ ] **Step 5: Delegate from `WeblogEntry`**

In `WeblogEntry.java`, replace the tail of the private `render(String str)` method — the three lines that expand, render markdown and sanitize — with a single delegation, leaving the named-plugin loop above it exactly as it is:

```java
        // Named entry plugins are opt-in per entry and stay here. Everything
        // below -- shortcodes, markdown, sanitization -- is universal and
        // lives in ContentRenderer so WeblogPage gets the identical pipeline.
        return ContentRenderer.render(this, ret);
```

Delete the now-unused `ShortcodeExpander`, `MarkdownRenderer` and `HTMLSanitizer` imports if nothing else in the file uses them.

- [ ] **Step 6: Prove entries render identically**

Run: `mvn -pl app test -Dtest='*Rendering*Test,WeblogEntryTest,*Shortcode*Test'`
Expected: PASS with an unchanged test count.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/business/ContentRenderer.java \
        app/src/main/java/org/apache/roller/weblogger/pojos/WeblogEntry.java \
        app/src/test/java/org/apache/roller/weblogger/business/ContentRendererTest.java
git commit -m "Extract the shortcode/markdown/sanitize pipeline into ContentRenderer"
```

---

# Task 3: The `[video]` shortcode

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/business/shortcodes/VideoShortcode.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/shortcodes/ShortcodeExpander.java:117-119`
- Modify: `app/src/main/resources/ApplicationResources.properties`
- Test: `app/src/test/java/org/apache/roller/weblogger/business/shortcodes/VideoShortcodeTest.java`

**Interfaces:**
- Consumes: `ShortcodeHandler`, `ShortcodeCard`, `ShortcodeContext` (Task 1).
- Produces: a handler named `video` emitting `<div class="video-embed" data-provider="…" data-video-id="…">` with a thumbnail `<img>` inside.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/apache/roller/weblogger/business/shortcodes/VideoShortcodeTest.java`:

```java
package org.apache.roller.weblogger.business.shortcodes;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [video] parses URLs; it never fetches them. Every provider is matched by a
 * known URL shape and the id is validated against a strict character class,
 * so an author cannot smuggle markup through the id and there is no outbound
 * request for anyone to point at an internal address.
 */
class VideoShortcodeTest {

    private final VideoShortcode shortcode = new VideoShortcode();

    private String render(String url) {
        return shortcode.render(Map.of("url", url), null, null);
    }

    @Test
    void aWatchUrlYieldsAYouTubePlaceholder() {
        String html = render("https://www.youtube.com/watch?v=dQw4w9WgXcQ");

        assertTrue(html.contains("data-provider=\"youtube\""), html);
        assertTrue(html.contains("data-video-id=\"dQw4w9WgXcQ\""), html);
        assertTrue(html.contains("class=\"video-embed\""), html);
    }

    @Test
    void aShortYoutuBeUrlYieldsTheSameId() {
        assertTrue(render("https://youtu.be/dQw4w9WgXcQ")
                .contains("data-video-id=\"dQw4w9WgXcQ\""));
    }

    @Test
    void aVimeoUrlYieldsAVimeoPlaceholder() {
        String html = render("https://vimeo.com/123456789");

        assertTrue(html.contains("data-provider=\"vimeo\""), html);
        assertTrue(html.contains("data-video-id=\"123456789\""), html);
    }

    /**
     * No iframe is ever emitted. The sanitizer strips iframes, so emitting one
     * would produce an empty div and a mystery; the theme macro injects the
     * frame client-side on click instead.
     */
    @Test
    void noIframeIsEmitted() {
        assertFalse(render("https://youtu.be/dQw4w9WgXcQ").contains("<iframe"));
    }

    @Test
    void aThumbnailIsEmittedForYouTube() {
        String html = render("https://youtu.be/dQw4w9WgXcQ");

        assertTrue(html.contains("<img"), html);
        assertTrue(html.contains("i.ytimg.com/vi/dQw4w9WgXcQ/"), html);
        assertTrue(html.contains("loading=\"lazy\""),
                "the thumbnail must not block first paint: " + html);
    }

    /** Null means "leave the author's text visible" -- the can't-render signal. */
    @Test
    void anUnknownHostIsLeftAsTheAuthorWroteIt() {
        assertNull(render("https://example.com/video/1"));
    }

    @Test
    void aMissingUrlIsLeftAsTheAuthorWroteIt() {
        assertNull(shortcode.render(Map.of(), null, null));
    }

    /**
     * The id goes straight into an HTML attribute and into a thumbnail URL.
     * Anything outside the provider's own id alphabet is refused outright
     * rather than escaped, because a value that shape is not an id.
     */
    @Test
    void anIdCarryingMarkupIsRefused() {
        assertNull(render("https://youtu.be/abc\"><script>alert(1)</script>"));
    }

    @Test
    void aNonHttpSchemeIsRefused() {
        assertNull(render("javascript:alert(1)"));
    }

    @Test
    void aCaptionIsEscapedAndEmitted() {
        String html = shortcode.render(
                Map.of("url", "https://youtu.be/dQw4w9WgXcQ", "caption", "A & B <x>"),
                null, null);

        assertTrue(html.contains("<figcaption>"), html);
        assertTrue(html.contains("A &amp; B &lt;x&gt;"), html);
        assertFalse(html.contains("<x>"), html);
    }

    @Test
    void theCardIsDiscoverableAndInsertsWorkingSyntax() {
        ShortcodeCard card = shortcode.getCard();

        assertEquals("video", card.name());
        assertTrue(card.snippet().startsWith("[video "), card.snippet());
        assertFalse(card.usesMediaChooser());
        assertFalse(card.snippet().contains("<"), "snippet travels in an HTML attribute");
    }

    @Test
    void theHandlerIsRegisteredInTheDefaultExpander() {
        assertTrue(ShortcodeExpander.defaultExpander().cards().stream()
                        .anyMatch(c -> "video".equals(c.name())),
                "an unregistered shortcode is undiscoverable in the editor");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=VideoShortcodeTest`
Expected: FAIL — `VideoShortcode` does not exist.

- [ ] **Step 3: Write the handler**

Create `app/src/main/java/org/apache/roller/weblogger/business/shortcodes/VideoShortcode.java` with the ASF header, then:

```java
package org.apache.roller.weblogger.business.shortcodes;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.text.StringEscapeUtils;

/**
 * The built-in {@code [video url="..." caption="..."]} shortcode: a
 * click-to-play placeholder for an allowlisted video provider.
 *
 * <p><strong>This parses URLs; it never fetches them.</strong> Real oEmbed
 * would discover a provider endpoint by requesting the author's URL
 * server-side -- an SSRF surface pointing at this deployment's own actuator
 * port and any cloud metadata endpoint -- and the HTML it returned would be
 * discarded anyway, because {@code HTMLSanitizer} strips {@code <iframe>}.
 * Matching known URL shapes costs one regex per provider and has neither
 * problem.
 *
 * <p>Like {@code [map]}, the emitted markup is a {@code <div>} carrying data
 * attributes, never a frame: the sanitizer would delete a frame, so the
 * {@code #showEmbedAssets} macro injects one client-side when a reader
 * clicks. Nothing loads from the provider before that click.
 */
public class VideoShortcode implements ShortcodeHandler {

    private static final Log log = LogFactory.getLog(VideoShortcode.class);

    /** YouTube ids are [A-Za-z0-9_-]{11}; Vimeo ids are digits. */
    private record Provider(String name, Pattern urlPattern, Pattern idPattern,
                            String thumbnailFormat) {
    }

    private static final List<Provider> PROVIDERS = List.of(
            new Provider("youtube",
                    Pattern.compile("^https?://(?:www\\.|m\\.)?youtube\\.com/watch\\?"
                            + "(?:[^&]*&)*v=([^&#]+)"),
                    Pattern.compile("^[A-Za-z0-9_-]{11}$"),
                    "https://i.ytimg.com/vi/%s/hqdefault.jpg"),
            new Provider("youtube",
                    Pattern.compile("^https?://youtu\\.be/([^?&#/]+)"),
                    Pattern.compile("^[A-Za-z0-9_-]{11}$"),
                    "https://i.ytimg.com/vi/%s/hqdefault.jpg"),
            new Provider("vimeo",
                    Pattern.compile("^https?://(?:www\\.|player\\.)?vimeo\\.com/"
                            + "(?:video/)?(\\d+)"),
                    Pattern.compile("^\\d+$"),
                    null));

    @Override
    public String getName() {
        return "video";
    }

    @Override
    public ShortcodeCard getCard() {
        return ShortcodeCard.snippet("video", "shortcode.video.label",
                "[video url=\"https://youtu.be/dQw4w9WgXcQ\" caption=\"What this shows\"]");
    }

    @Override
    public String render(Map<String, String> attributes, String body,
            ShortcodeContext content) {

        String url = StringUtils.trimToNull(attributes.get("url"));
        if (url == null) {
            log.debug("[video] shortcode without a url; leaving it as written");
            return null;
        }

        for (Provider provider : PROVIDERS) {
            Matcher matcher = provider.urlPattern().matcher(url);
            if (!matcher.find()) {
                continue;
            }
            String id = matcher.group(1);
            if (!provider.idPattern().matcher(id).matches()) {
                // Refused rather than escaped: a value this shape is not an id,
                // and it would travel into both an attribute and a thumbnail URL.
                log.debug("[video] id is not valid for " + provider.name()
                        + "; leaving it as written");
                return null;
            }
            return markup(provider, id, attributes.get("caption"));
        }

        log.debug("[video] url is not an allowlisted provider; leaving it as written");
        return null;
    }

    private static String markup(Provider provider, String id, String caption) {
        StringBuilder html = new StringBuilder(320);
        html.append("<figure class=\"video-figure\">");
        html.append("<div class=\"video-embed\" data-provider=\"")
                .append(provider.name())
                .append("\" data-video-id=\"").append(id).append("\">");

        if (provider.thumbnailFormat() != null) {
            html.append("<img src=\"")
                    .append(escape(String.format(provider.thumbnailFormat(), id)))
                    .append("\" alt=\"\" loading=\"lazy\" decoding=\"async\">");
        }

        html.append("</div>");

        String trimmedCaption = StringUtils.trimToNull(caption);
        if (trimmedCaption != null) {
            html.append("<figcaption>").append(escape(trimmedCaption)).append("</figcaption>");
        }
        html.append("</figure>");
        return html.toString();
    }

    private static String escape(String value) {
        return StringEscapeUtils.escapeHtml4(value);
    }
}
```

- [ ] **Step 4: Register it and add the message key**

In `ShortcodeExpander.java`, add `new VideoShortcode()` to the `DEFAULT` list:

```java
    private static final ShortcodeExpander DEFAULT =
            new ShortcodeExpander(List.of(new ImageShortcode(), new GalleryShortcode(),
                    new MapShortcode(), new CtaShortcode(), new FaqShortcode(),
                    new VideoShortcode()));
```

In `app/src/main/resources/ApplicationResources.properties`, add beside the other `shortcode.*.label` keys:

```properties
shortcode.video.label=Video
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl app test -Dtest=VideoShortcodeTest`
Expected: PASS, all 12 tests.

- [ ] **Step 6: Check the message-key ratchet**

Run: `mvn -pl app test -Dtest=MessageKeyTest`
Expected: PASS — the key is referenced as a literal in `getCard()`, which is what the scanner looks for.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/business/shortcodes/VideoShortcode.java \
        app/src/main/java/org/apache/roller/weblogger/business/shortcodes/ShortcodeExpander.java \
        app/src/main/resources/ApplicationResources.properties \
        app/src/test/java/org/apache/roller/weblogger/business/shortcodes/VideoShortcodeTest.java
git commit -m "Add the [video] shortcode: allowlisted providers, parsed not fetched"
```

---

# Task 4: Sanitizer grant, `#showEmbedAssets`, and the CSP change

The one authorised CSP widening. Themes and pinned tests change in this single commit so they cannot drift.

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/util/HTMLSanitizer.java:104-106`
- Modify: `app/src/main/webapp/WEB-INF/velocity/weblog.vm`
- Modify: `app/src/main/webapp/themes/{basic,fauxcoly,gaurav,portfolio,travel}/*.vm` (every file carrying a CSP meta tag)
- Modify: `app/src/test/java/.../MapAssetsRenderingTest.java`, `PortfolioThemeRenderingTest.java`, `TravelThemeRenderingTest.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/util/VideoSanitizationTest.java`

**Interfaces:**
- Consumes: the `<div class="video-embed" data-provider data-video-id>` markup from Task 3.
- Produces: `#showEmbedAssets` macro; theme CSPs carrying `frame-src`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/apache/roller/weblogger/util/VideoSanitizationTest.java`:

```java
package org.apache.roller.weblogger.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sanitizer must keep [video]'s data attributes and must still refuse
 * iframes. Both halves matter: without the grant the placeholder arrives
 * stripped of the only information the macro needs, and if iframes were ever
 * allowed the click-to-play facade would stop being a boundary at all.
 */
class VideoSanitizationTest {

    @Test
    void theVideoPlaceholdersDataAttributesSurvive() {
        String clean = HTMLSanitizer.conditionallySanitize(
                "<div class=\"video-embed\" data-provider=\"youtube\" "
                        + "data-video-id=\"dQw4w9WgXcQ\"></div>");

        assertTrue(clean.contains("data-provider=\"youtube\""), clean);
        assertTrue(clean.contains("data-video-id=\"dQw4w9WgXcQ\""), clean);
    }

    @Test
    void anIframeIsStillStripped() {
        String clean = HTMLSanitizer.conditionallySanitize(
                "<iframe src=\"https://evil.example/x\"></iframe>");

        assertFalse(clean.contains("<iframe"),
                "the facade is only a boundary while this holds: " + clean);
    }

    @Test
    void theseAttributesAreNotGrantedGlobally() {
        String clean = HTMLSanitizer.conditionallySanitize(
                "<a data-video-id=\"x\">link</a>");

        assertFalse(clean.contains("data-video-id"),
                "the grant is scoped to div, like data-pins: " + clean);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=VideoSanitizationTest`
Expected: FAIL — the data attributes are stripped.

- [ ] **Step 3: Grant the attributes**

In `HTMLSanitizer.java`, extend the existing `div` grant (line ~104) — keep it one `.onElements("div")` call so the scoping stays obvious:

```java
            // the map shortcode's payload: pins as JSON in a data attribute;
            // and the video shortcode's provider + id, which #showEmbedAssets
            // reads to build the frame on click
            .allowAttributes("data-pins", "data-center", "data-zoom", "data-route",
                    "data-provider", "data-video-id")
            .onElements("div")
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl app test -Dtest=VideoSanitizationTest`
Expected: PASS.

- [ ] **Step 5: Add the `#showEmbedAssets` macro**

In `app/src/main/webapp/WEB-INF/velocity/weblog.vm`, immediately after the `#showMapAssets` macro, add:

```velocity
#**
Click-to-play video embeds for the [video] shortcode -- the twin of
#showMapAssets and #showGalleryAssets.

Nothing loads from the provider until a reader clicks: the shortcode emits a
thumbnail and this macro swaps in the frame on demand. That is a privacy
property, not only a performance one, and it is why the theme CSP needs
frame-src but not connect-src for these hosts.
*#
#macro(showEmbedAssets)
<style>
.video-figure { margin: 1em 0; }
.video-embed { position: relative; aspect-ratio: 16 / 9; background: #000; cursor: pointer; }
.video-embed img, .video-embed iframe { width: 100%; height: 100%; border: 0; display: block; object-fit: cover; }
.video-embed::after { content: "\25B6"; position: absolute; inset: 0; display: flex;
  align-items: center; justify-content: center; font-size: 3rem; color: #fff;
  text-shadow: 0 0 12px rgba(0,0,0,.7); pointer-events: none; }
.video-embed.is-playing { cursor: default; }
.video-embed.is-playing::after { display: none; }
</style>
<script>
document.addEventListener('DOMContentLoaded', function () {
  var embeds = document.querySelectorAll('.video-embed');
  if (!embeds.length) { return; }
  Array.prototype.forEach.call(embeds, function (embed) {
    embed.addEventListener('click', function () {
      if (embed.classList.contains('is-playing')) { return; }
      var provider = embed.getAttribute('data-provider');
      var id = embed.getAttribute('data-video-id');
      if (!provider || !id) { return; }
      var src = provider === 'vimeo'
        ? 'https://player.vimeo.com/video/' + encodeURIComponent(id) + '?autoplay=1'
        : 'https://www.youtube-nocookie.com/embed/' + encodeURIComponent(id) + '?autoplay=1';
      var frame = document.createElement('iframe');
      frame.setAttribute('src', src);
      frame.setAttribute('allow', 'autoplay; fullscreen; picture-in-picture');
      frame.setAttribute('allowfullscreen', 'allowfullscreen');
      frame.setAttribute('title', 'Embedded video');
      embed.innerHTML = '';
      embed.appendChild(frame);
      embed.classList.add('is-playing');
    });
  });
});
</script>
#end
```

- [ ] **Step 6: Update all five themes**

For **every** `.vm` file in `app/src/main/webapp/themes/{basic,fauxcoly,gaurav,portfolio,travel}/` that contains a `Content-Security-Policy` meta tag, insert `frame-src https://www.youtube-nocookie.com https://player.vimeo.com;` immediately after the `img-src * data:;` directive. Find them:

```bash
grep -rln "Content-Security-Policy" app/src/main/webapp/themes/
```

The travel theme's `weblog.vm` line 6 becomes:

```html
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src * data:; frame-src https://www.youtube-nocookie.com https://player.vimeo.com; base-uri 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'">
```

In the same files, add `#showEmbedAssets()` beside the existing `#showMapAssets()` call (or in `<head>` next to `#showGalleryAssets()` where the theme places asset macros).

- [ ] **Step 7: Update the three pinned CSP tests**

`MapAssetsRenderingTest` pins `CSP_DATA_IMAGES = "img-src * data:;"` — leave that assertion alone and **add** one for the new directive rather than pinning the whole string again:

```java
    private static final String CSP_VIDEO_FRAMES =
            "frame-src https://www.youtube-nocookie.com https://player.vimeo.com;";
```

Assert `CSP_VIDEO_FRAMES` is present wherever `CSP_DATA_IMAGES` is asserted. Do the same in `PortfolioThemeRenderingTest` and `TravelThemeRenderingTest`. If either of those pins a whole CSP string verbatim, narrow it to the directives it actually cares about — a whole-string pin is what made this file painful to change last time.

- [ ] **Step 8: Run the rendering suite**

Run: `mvn -pl app test -Dtest='*Rendering*Test,ThemeCspCoverageTest,VideoSanitizationTest'`
Expected: PASS.

- [ ] **Step 9: Commit — themes and tests together**

```bash
git add app/src/main/java/org/apache/roller/weblogger/util/HTMLSanitizer.java \
        app/src/main/webapp/WEB-INF/velocity/weblog.vm \
        app/src/main/webapp/themes/ \
        app/src/test/java/org/apache/roller/weblogger/ui/rendering/ \
        app/src/test/java/org/apache/roller/weblogger/util/VideoSanitizationTest.java
git commit -m "Allow [video] through the sanitizer and the theme CSPs

The single authorised CSP widening for this wave: frame-src limited to
youtube-nocookie and player.vimeo. script-src, connect-src and
default-src 'none' are untouched. Themes and the tests that pin them change
together so the two cannot drift."
```

---

# Task 5: `V014` migration, `WeblogPage` entity, ORM

**Files:**
- Create: `bin/db/migrations/V014__weblog_pages.sql`
- Create: `app/src/main/java/org/apache/roller/weblogger/pojos/WeblogPage.java`
- Create: `app/src/main/resources/org/apache/roller/weblogger/pojos/WeblogPage.orm.xml`
- Modify: `app/src/main/resources/META-INF/persistence.xml:34` (append)
- Test: `app/src/test/java/org/apache/roller/weblogger/pojos/WeblogPageTest.java`

**Interfaces:**
- Consumes: `ShortcodeContext` (Task 1), `ContentRenderer` (Task 2).
- Produces: `WeblogPage` with `getId/setId`, `getWeblog/setWeblog`, `getSlug/setSlug`, `getTitle/setTitle`, `getContent/setContent`, `getStatus/setStatus` (`PubStatus` enum `DRAFT|PUBLISHED`), `getShowInNav/setShowInNav`, `getNavOrder/setNavOrder`, `getCreated/setCreated`, `getUpdated/setUpdated`, the SEO fields, and `getRenderedContent()`.

- [ ] **Step 1: Write the migration**

Create `bin/db/migrations/V014__weblog_pages.sql` with the ASF header used by `V013`, then:

```sql
-- Migration: static pages, authored in Markdown
--
-- A page is deliberately NOT a weblog entry with a flag. Entries are read by
-- 25 query paths in JPAWeblogEntryManagerImpl -- feeds, archives, the Lucene
-- index, sitemaps, tag aggregates, pagers, next/prev navigation -- and a
-- discriminator would mean auditing every one of them, where missing a single
-- path silently puts an About page in the RSS feed. A separate table cannot
-- leak into any of them.
--
-- Absent on purpose: category, tags, pubtime, comment settings, locale. A page
-- has no position in a chronology and nothing to file it under; columns that
-- mean nothing are how a model starts lying.
--
-- Prerequisites: V002__baseline_schema.

CREATE TABLE IF NOT EXISTS roller_weblogpage (
    id                 varchar(48)  NOT NULL PRIMARY KEY,
    weblogid           varchar(48)  NOT NULL,
    slug               varchar(255) NOT NULL,
    title              varchar(255) NOT NULL,
    content            text,
    status             varchar(20)  NOT NULL DEFAULT 'DRAFT',
    show_in_nav        boolean      NOT NULL DEFAULT true,
    nav_order          integer      NOT NULL DEFAULT 0,
    created            timestamp(3) with time zone NOT NULL,
    updated            timestamp(3) with time zone NOT NULL,
    meta_title         varchar(255),
    search_description varchar(255),
    canonical_url      varchar(255),
    noindex            boolean      NOT NULL DEFAULT false,
    og_image_id        varchar(48),
    CONSTRAINT rwp_weblog_fk FOREIGN KEY (weblogid) REFERENCES weblog(id)
);

-- One slug per weblog: the routing lookup is (weblog, slug) and a duplicate
-- would make which page a URL resolves to a matter of chance.
CREATE UNIQUE INDEX IF NOT EXISTS rwp_weblog_slug_uq
    ON roller_weblogpage(weblogid, slug);

-- Nav rendering and the sitemap both read published pages for one weblog in
-- nav order.
CREATE INDEX IF NOT EXISTS rwp_weblog_status_idx
    ON roller_weblogpage(weblogid, status, nav_order);
```

- [ ] **Step 2: Verify the migration applies and is idempotent**

Run: `mvn -pl app test -Dtest=SchemaMigrationTest`
Expected: PASS — it applies the chain, asserts discoverability and re-applies to prove idempotency.

- [ ] **Step 3: Write the failing entity test**

Create `app/src/test/java/org/apache/roller/weblogger/pojos/WeblogPageTest.java`:

```java
package org.apache.roller.weblogger.pojos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A page renders through the same pipeline an entry does, and satisfies the
 * same ShortcodeContext, so every shortcode works on it.
 */
class WeblogPageTest {

    private static WeblogPage page(String content) {
        Weblog weblog = new Weblog();
        weblog.setHandle("maiia");

        WeblogPage page = new WeblogPage();
        page.setWeblog(weblog);
        page.setSlug("about");
        page.setTitle("About");
        page.setContent(content);
        return page;
    }

    @Test
    void aPageIsAShortcodeContext() {
        WeblogPage page = page("hello");

        assertEquals("maiia", page.getWeblog().getHandle());
        assertEquals("about", page.getSlug(), "a page's slug is its slug");
        assertEquals("hello", page.getRawText());
    }

    @Test
    void contentIsRenderedAsMarkdown() {
        assertTrue(page("**bold**").getRenderedContent().contains("<strong>bold</strong>"));
    }

    @Test
    void scriptInContentIsSanitizedAway() {
        String html = page("ok<script>alert(1)</script>").getRenderedContent();

        assertTrue(html.contains("ok"));
        assertFalse(html.contains("<script"));
    }

    @Test
    void aNewPageStartsAsADraft() {
        assertEquals(WeblogPage.PubStatus.DRAFT, new WeblogPage().getStatus(),
                "publishing must be a deliberate act");
    }

    @Test
    void aNewPageShowsInNavByDefault() {
        assertTrue(new WeblogPage().getShowInNav());
    }

    @Test
    void nullContentRendersAsNullRatherThanThrowing() {
        page(null).getRenderedContent();
    }
}
```

- [ ] **Step 4: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=WeblogPageTest`
Expected: FAIL — `WeblogPage` does not exist.

- [ ] **Step 5: Write the entity**

Create `app/src/main/java/org/apache/roller/weblogger/pojos/WeblogPage.java` with the ASF header. It needs: the fields from the migration, a `PubStatus` enum with `DRAFT, PUBLISHED`, standard getters and setters for every field, `implements ShortcodeContext` with `getWeblog()` returning the weblog field directly, `getSlug()` returning the slug, `getRawText()` returning the content, plus:

```java
    /**
     * The page's content, rendered through the same pipeline entries use:
     * shortcodes, then markdown, then sanitization.
     */
    public String getRenderedContent() {
        return ContentRenderer.render(this, getContent());
    }
```

Give `id` the same default the other pojos use (`UUIDGenerator.generateUUID()`), default `status` to `PubStatus.DRAFT`, `showInNav` to `Boolean.TRUE`, `navOrder` to `0`, and `noindex` to `Boolean.FALSE`. Implement `equals`/`hashCode` on `id` and a `toString` naming the weblog handle and slug, matching `ShareLink`'s style.

- [ ] **Step 6: Write the ORM mapping**

Create `app/src/main/resources/org/apache/roller/weblogger/pojos/WeblogPage.orm.xml`, modelled on `ShareLink.orm.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<entity-mappings version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence/orm"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="https://jakarta.ee/xml/ns/persistence/orm https://jakarta.ee/xml/ns/persistence/orm_3_0.xsd">
    <package>org.apache.roller.weblogger.pojos</package>
    <entity metadata-complete="true" name="WeblogPage"
            class="org.apache.roller.weblogger.pojos.WeblogPage" access="PROPERTY">
        <table name="roller_weblogpage"/>
        <named-query name="WeblogPage.getByWeblogAndSlug">
            <query>SELECT p FROM WeblogPage p WHERE p.weblog = ?1 AND p.slug = ?2</query>
        </named-query>
        <!-- Nav order first, then title, so two pages sharing an order do not
             swap places between reads. -->
        <named-query name="WeblogPage.getByWeblog">
            <query>SELECT p FROM WeblogPage p WHERE p.weblog = ?1 ORDER BY p.navOrder ASC, p.title ASC</query>
        </named-query>
        <named-query name="WeblogPage.getByWeblogAndStatus">
            <query>SELECT p FROM WeblogPage p WHERE p.weblog = ?1 AND p.status = ?2 ORDER BY p.navOrder ASC, p.title ASC</query>
        </named-query>
        <named-query name="WeblogPage.removeByWeblog">
            <query>DELETE FROM WeblogPage p WHERE p.weblog = ?1</query>
        </named-query>
        <attributes>
            <id name="id">
                <column name="id"/>
            </id>
            <basic name="slug">
                <column name="slug" insertable="true" updatable="true" nullable="false"/>
            </basic>
            <basic name="title">
                <column name="title" insertable="true" updatable="true" nullable="false"/>
            </basic>
            <basic name="content">
                <column name="content" insertable="true" updatable="true" nullable="true"/>
            </basic>
            <basic name="status">
                <column name="status" insertable="true" updatable="true" nullable="false"/>
                <enumerated>STRING</enumerated>
            </basic>
            <basic name="showInNav">
                <column name="show_in_nav" insertable="true" updatable="true" nullable="false"/>
            </basic>
            <basic name="navOrder">
                <column name="nav_order" insertable="true" updatable="true" nullable="false"/>
            </basic>
            <basic name="created">
                <column name="created" insertable="true" updatable="true" nullable="false"/>
            </basic>
            <basic name="updated">
                <column name="updated" insertable="true" updatable="true" nullable="false"/>
            </basic>
            <basic name="metaTitle">
                <column name="meta_title" insertable="true" updatable="true" nullable="true"/>
            </basic>
            <basic name="searchDescription">
                <column name="search_description" insertable="true" updatable="true" nullable="true"/>
            </basic>
            <basic name="canonicalUrl">
                <column name="canonical_url" insertable="true" updatable="true" nullable="true"/>
            </basic>
            <basic name="noindex">
                <column name="noindex" insertable="true" updatable="true" nullable="false"/>
            </basic>
            <basic name="ogImageId">
                <column name="og_image_id" insertable="true" updatable="true" nullable="true"/>
            </basic>
            <many-to-one name="weblog" target-entity="org.apache.roller.weblogger.pojos.Weblog">
                <join-column name="weblogid" insertable="true" updatable="true" nullable="false"/>
            </many-to-one>
        </attributes>
    </entity>
</entity-mappings>
```

**Do not put an XML comment containing `--` anywhere in this file** — a double hyphen inside an XML comment is illegal and fails the whole persistence unit at bootstrap with an error that does not name the file.

- [ ] **Step 7: Register the mapping**

In `app/src/main/resources/META-INF/persistence.xml`, after the `CustomTemplateRendition.orm.xml` line:

```xml
    <mapping-file>org/apache/roller/weblogger/pojos/WeblogPage.orm.xml</mapping-file>
```

- [ ] **Step 8: Run the tests**

Run: `mvn -pl app test -Dtest='WeblogPageTest,SchemaMigrationTest'`
Expected: PASS.

- [ ] **Step 9: Verify the persistence unit still bootstraps**

Run: `mvn -pl app test -Dtest=WeblogEntryManagerQueryTest`
Expected: PASS. A malformed `.orm.xml` breaks every database test, so a passing unrelated JPA test is the check that the mapping parsed.

- [ ] **Step 10: Commit**

```bash
git add bin/db/migrations/V014__weblog_pages.sql \
        app/src/main/java/org/apache/roller/weblogger/pojos/WeblogPage.java \
        app/src/main/resources/org/apache/roller/weblogger/pojos/WeblogPage.orm.xml \
        app/src/main/resources/META-INF/persistence.xml \
        app/src/test/java/org/apache/roller/weblogger/pojos/WeblogPageTest.java
git commit -m "Add the WeblogPage entity and its table"
```

---

# Task 6: `WeblogPageManager`, reserved slugs, and wiring

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/pojos/ReservedSlugs.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/business/WeblogPageManager.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAWeblogPageManagerImpl.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/Weblogger.java:97` (after `getShareLinkManager`)
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/WebloggerImpl.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/jpa/WebloggerBeanConfig.java:120-123`
- Test: `app/src/test/java/org/apache/roller/weblogger/business/WeblogPageManagerTest.java`

**Interfaces:**
- Consumes: `WeblogPage` (Task 5).
- Produces:
  - `ReservedSlugs.RESERVED` (a `Set<String>`) and `ReservedSlugs.isReserved(String)`.
  - `WeblogPageManager` with `void savePage(WeblogPage) throws WebloggerException`, `void removePage(WeblogPage)`, `WeblogPage getPage(String id)`, `WeblogPage getPageBySlug(Weblog, String slug)`, `List<WeblogPage> getPages(Weblog)`, `List<WeblogPage> getPublishedPages(Weblog)`, `void removePages(Weblog)`.
  - `Weblogger.getWeblogPageManager()`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/apache/roller/weblogger/business/WeblogPageManagerTest.java`:

```java
package org.apache.roller.weblogger.business;

import java.util.List;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeblogPageManagerTest {

    private User user;
    private Weblog weblog;
    private Weblog otherWeblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("pageuser");
        weblog = TestUtils.setupWeblog("pageblog", user);
        otherWeblog = TestUtils.setupWeblog("otherpageblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownWeblog(otherWeblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static WeblogPageManager manager() {
        return WebloggerFactory.getWeblogger().getWeblogPageManager();
    }

    private WeblogPage save(Weblog target, String slug, WeblogPage.PubStatus status)
            throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(target));
        page.setSlug(slug);
        page.setTitle("Title for " + slug);
        page.setContent("Body of " + slug);
        page.setStatus(status);
        manager().savePage(page);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);
        return page;
    }

    @Test
    void aSavedPageIsFoundBySlug() throws Exception {
        save(weblog, "about", WeblogPage.PubStatus.PUBLISHED);

        WeblogPage found = manager().getPageBySlug(TestUtils.getManagedWebsite(weblog), "about");

        assertNotNull(found);
        assertEquals("Title for about", found.getTitle());
    }

    /**
     * The isolation that matters. Two weblogs may both have an /about, and a
     * lookup scoped to one must never answer with the other's.
     */
    @Test
    void aSlugLookupIsScopedToItsWeblog() throws Exception {
        save(weblog, "about", WeblogPage.PubStatus.PUBLISHED);

        assertNull(manager().getPageBySlug(TestUtils.getManagedWebsite(otherWeblog), "about"),
                "another weblog's page must not answer this weblog's lookup");
    }

    @Test
    void twoWeblogsMayEachHaveTheSameSlug() throws Exception {
        save(weblog, "about", WeblogPage.PubStatus.PUBLISHED);
        save(otherWeblog, "about", WeblogPage.PubStatus.PUBLISHED);

        assertNotNull(manager().getPageBySlug(TestUtils.getManagedWebsite(weblog), "about"));
        assertNotNull(manager().getPageBySlug(TestUtils.getManagedWebsite(otherWeblog), "about"));
    }

    @Test
    void publishedPagesExcludeDrafts() throws Exception {
        save(weblog, "about", WeblogPage.PubStatus.PUBLISHED);
        save(weblog, "secret", WeblogPage.PubStatus.DRAFT);

        List<WeblogPage> published =
                manager().getPublishedPages(TestUtils.getManagedWebsite(weblog));

        assertEquals(1, published.size(), "got: " + published);
        assertEquals("about", published.get(0).getSlug());
    }

    @Test
    void pagesComeBackInNavOrder() throws Exception {
        WeblogPage second = save(weblog, "services", WeblogPage.PubStatus.PUBLISHED);
        WeblogPage first = save(weblog, "about", WeblogPage.PubStatus.PUBLISHED);

        WeblogPage managedFirst = manager().getPage(first.getId());
        managedFirst.setNavOrder(1);
        manager().savePage(managedFirst);
        WeblogPage managedSecond = manager().getPage(second.getId());
        managedSecond.setNavOrder(2);
        manager().savePage(managedSecond);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        List<WeblogPage> pages = manager().getPages(TestUtils.getManagedWebsite(weblog));

        assertEquals(List.of("about", "services"),
                pages.stream().map(WeblogPage::getSlug).toList());
    }

    @Test
    void aRemovedPageIsGone() throws Exception {
        WeblogPage page = save(weblog, "about", WeblogPage.PubStatus.PUBLISHED);

        manager().removePage(manager().getPage(page.getId()));
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        assertNull(manager().getPageBySlug(TestUtils.getManagedWebsite(weblog), "about"));
    }

    /**
     * A slug that collides with a routing context would make the page
     * unreachable and shadow a real weblog view. Refused at save, which is the
     * only place it can be refused usefully.
     */
    @Test
    void aReservedSlugIsRefused() {
        assertThrows(WebloggerException.class,
                () -> save(weblog, "entry", WeblogPage.PubStatus.PUBLISHED));
        assertThrows(WebloggerException.class,
                () -> save(weblog, "tags", WeblogPage.PubStatus.PUBLISHED));
        assertThrows(WebloggerException.class,
                () -> save(weblog, "feed", WeblogPage.PubStatus.PUBLISHED));
    }

    @Test
    void aReservedSlugIsRefusedRegardlessOfCase() {
        assertThrows(WebloggerException.class,
                () -> save(weblog, "Entry", WeblogPage.PubStatus.PUBLISHED));
    }

    @Test
    void aBlankSlugIsRefused() {
        assertThrows(WebloggerException.class,
                () -> save(weblog, "   ", WeblogPage.PubStatus.PUBLISHED));
    }

    @Test
    void aSlugWithASlashIsRefused() {
        assertThrows(WebloggerException.class,
                () -> save(weblog, "about/us", WeblogPage.PubStatus.PUBLISHED));
    }

    @Test
    void removingAWeblogsPagesLeavesAnothersAlone() throws Exception {
        save(weblog, "about", WeblogPage.PubStatus.PUBLISHED);
        save(otherWeblog, "about", WeblogPage.PubStatus.PUBLISHED);

        manager().removePages(TestUtils.getManagedWebsite(weblog));
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        assertTrue(manager().getPages(TestUtils.getManagedWebsite(weblog)).isEmpty());
        assertEquals(1, manager().getPages(TestUtils.getManagedWebsite(otherWeblog)).size(),
                "the other weblog's pages must survive");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=WeblogPageManagerTest`
Expected: FAIL — `getWeblogPageManager` does not exist.

- [ ] **Step 3: Write `ReservedSlugs`**

Create `app/src/main/java/org/apache/roller/weblogger/pojos/ReservedSlugs.java` with the ASF header:

```java
package org.apache.roller.weblogger.pojos;

import java.util.Locale;
import java.util.Set;

/**
 * First path segments a weblog page's slug may not use.
 *
 * <p>Single source of truth, shared by the request parser and the save-time
 * validator. {@code /<handle>/<slug>} now resolves an unrecognised segment to
 * a page, so a page slugged {@code entry} would both be unreachable itself and
 * shadow every permalink on the weblog. Two lists would drift; this one
 * cannot.
 */
public final class ReservedSlugs {

    /**
     * The contexts {@code WeblogPageRequest} parses, plus the servlet paths
     * that never reach it ({@code feed}, {@code search}, {@code resource},
     * {@code media}, {@code rsd}).
     */
    public static final Set<String> RESERVED = Set.of(
            "entry", "date", "category", "page", "tags",
            "feed", "search", "resource", "media", "rsd");

    private ReservedSlugs() {
    }

    /** Case-insensitive; null and blank count as reserved (nothing to route). */
    public static boolean isReserved(String slug) {
        return slug == null || slug.isBlank()
                || RESERVED.contains(slug.trim().toLowerCase(Locale.ROOT));
    }
}
```

- [ ] **Step 4: Write the manager interface**

Create `app/src/main/java/org/apache/roller/weblogger/business/WeblogPageManager.java` with the ASF header, declaring the seven methods listed under **Interfaces** above. Every method that touches the database declares `throws WebloggerException`. Javadoc `savePage` with: *"Refuses a blank slug, a slug containing '/', and any slug in ReservedSlugs — a page that cannot be routed to is not a page."*

- [ ] **Step 5: Write the JPA implementation**

Create `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAWeblogPageManagerImpl.java`, modelled on the existing `JPAShareLinkManagerImpl`. It takes a `JPAPersistenceStrategy` in its constructor and uses the named queries from the ORM file. `savePage` validates first:

```java
    @Override
    public void savePage(WeblogPage page) throws WebloggerException {
        String slug = page.getSlug() == null ? null : page.getSlug().trim();
        if (slug == null || slug.isBlank()) {
            throw new WebloggerException("page slug is required");
        }
        if (slug.indexOf('/') >= 0) {
            throw new WebloggerException("page slug may not contain '/': " + slug);
        }
        if (ReservedSlugs.isReserved(slug)) {
            throw new WebloggerException("page slug is reserved: " + slug);
        }
        page.setSlug(slug);

        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (page.getCreated() == null) {
            page.setCreated(now);
        }
        page.setUpdated(now);

        strategy.store(page);
    }
```

- [ ] **Step 6: Wire it in**

In `Weblogger.java`, after `getShareLinkManager()`:

```java
    /**
     * Get the WeblogPageManager, which handles static pages.
     */
    WeblogPageManager getWeblogPageManager();
```

In `WebloggerImpl.java`, add the field, the constructor parameter and the getter, following exactly how `shareLinkManager` is handled.

In `WebloggerBeanConfig.java`, after the `shareLinkManager` bean:

```java
    @Bean
    public WeblogPageManager weblogPageManager(JPAPersistenceStrategy strategy) {
        return new JPAWeblogPageManagerImpl(strategy);
    }
```

and add the parameter to the `weblogger(...)` bean method, passing it through to the `WebloggerImpl` constructor.

- [ ] **Step 7: Run the test to verify it passes**

Run: `mvn -pl app test -Dtest=WeblogPageManagerTest`
Expected: PASS, all 11 tests.

- [ ] **Step 8: Verify nothing else broke**

Run: `mvn -pl app test -Dtest='*ManagerTest,SmallWrapperDelegationTest'`
Expected: PASS. Adding a facade method can break delegation tests that enumerate the interface.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/pojos/ReservedSlugs.java \
        app/src/main/java/org/apache/roller/weblogger/business/WeblogPageManager.java \
        app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAWeblogPageManagerImpl.java \
        app/src/main/java/org/apache/roller/weblogger/business/Weblogger.java \
        app/src/main/java/org/apache/roller/weblogger/business/WebloggerImpl.java \
        app/src/main/java/org/apache/roller/weblogger/business/jpa/WebloggerBeanConfig.java \
        app/src/test/java/org/apache/roller/weblogger/business/WeblogPageManagerTest.java
git commit -m "Add WeblogPageManager with reserved-slug validation"
```

---

# Task 7: Route `/<handle>/<slug>` to a page

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/rendering/util/WeblogPageRequest.java:165-176`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/rendering/servlets/PageServlet.java:359-385`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/rendering/model/PageModel.java`
- Create: `app/src/main/webapp/WEB-INF/velocity/templates/weblog/page.vm`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/PageRoutingTest.java`

**Interfaces:**
- Consumes: `WeblogPageManager.getPageBySlug` (Task 6), `ReservedSlugs` (Task 6).
- Produces: `WeblogPageRequest.getWeblogPageContent()` returning the resolved `WeblogPage` or null; `PageModel.getPage()` exposing it to templates as `$model.page`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/apache/roller/weblogger/ui/rendering/PageRoutingTest.java`:

```java
package org.apache.roller.weblogger.ui.rendering;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.apache.roller.weblogger.ui.rendering.servlets.RenderingTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bare path segment resolves to a page. Before this, /handle/about threw
 * InvalidRequestException("invalid index page") -- a single element was only
 * ever legal for /tags.
 */
class PageRoutingTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();

        user = TestUtils.setupUser("routeuser");
        weblog = TestUtils.setupWeblog("routeblog", user);
        TestUtils.endSession(true);

        savePage("about", "About Us", WeblogPage.PubStatus.PUBLISHED);
        savePage("draft-page", "Not Yet", WeblogPage.PubStatus.DRAFT);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private void savePage(String slug, String title, WeblogPage.PubStatus status)
            throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug(slug);
        page.setTitle(title);
        page.setContent("Body of **" + slug + "**");
        page.setStatus(status);
        WebloggerFactory.getWeblogger().getWeblogPageManager().savePage(page);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);
    }

    private MockHttpServletResponse get(String path) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/routeblog" + path);
        return RenderingTestSupport.execute(RenderingTestSupport.pageServlet(), request);
    }

    @Test
    void aPublishedPageRendersAtItsBareSlug() throws Exception {
        MockHttpServletResponse response = get("/about");

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("About Us"),
                response.getContentAsString());
    }

    @Test
    void thePageContentIsRenderedAsMarkdown() throws Exception {
        assertTrue(get("/about").getContentAsString().contains("<strong>about</strong>"));
    }

    @Test
    void aDraftPageIs404ToAnAnonymousReader() throws Exception {
        assertEquals(404, get("/draft-page").getStatus(),
                "an unpublished page must not be readable");
    }

    @Test
    void anUnknownSlugIs404() throws Exception {
        assertEquals(404, get("/no-such-page").getStatus());
    }

    @Test
    void theWeblogHomePageStillWorks() throws Exception {
        assertEquals(200, get("").getStatus());
    }

    @Test
    void aReservedContextStillRoutesToItsOwnView() throws Exception {
        assertFalse(get("/tags").getStatus() == 404,
                "/tags must keep resolving to the tags view, not a page lookup");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=PageRoutingTest`
Expected: FAIL — `/routeblog/about` produces a 404 or an `InvalidRequestException`.

- [ ] **Step 3: Resolve the slug in `WeblogPageRequest`**

Add a `private WeblogPage weblogPageContent;` field with a getter `getWeblogPageContent()`. Replace the `else` branch at lines ~170-176:

```java
            } else {
                // A single path element. It is either /tags (the one context
                // that takes no argument) or a page slug: /<handle>/about.
                // Anything else 404s at the servlet, not here.
                if (!"tags".equals(this.context)) {
                    if (ReservedSlugs.isReserved(this.context)) {
                        throw new InvalidRequestException("invalid index page, "
                                + request.getRequestURL());
                    }
                    this.weblogPageContent = lookUpPage(this.context);
                    if (this.weblogPageContent == null) {
                        throw new InvalidRequestException("no such page, "
                                + request.getRequestURL());
                    }
                    otherPageHit = true;
                }
            }
```

Add the lookup, which resolves against the already-known weblog and refuses anything not published:

```java
    /**
     * The page for a bare slug on this weblog, or null. Drafts are invisible
     * here rather than at the servlet: an unpublished page must be
     * indistinguishable from one that does not exist.
     */
    private WeblogPage lookUpPage(String slug) {
        try {
            Weblog weblog = getWeblog();
            if (weblog == null) {
                return null;
            }
            WeblogPage page = WebloggerFactory.getWeblogger()
                    .getWeblogPageManager().getPageBySlug(weblog, slug);
            return page != null && page.getStatus() == WeblogPage.PubStatus.PUBLISHED
                    ? page : null;
        } catch (WebloggerException ex) {
            log.error("Error looking up page " + slug, ex);
            return null;
        }
    }
```

- [ ] **Step 4: Select the template in `PageServlet`**

In `selectTemplate`, immediately after the `popup` block and **before** the `"page".equals(...)` block, add:

```java
        // A static page: the theme may override with a custom template named
        // _page, exactly as it may override _popupcomments; otherwise the
        // shipped default renders it. Falling back rather than 404ing means a
        // theme does not have to know pages exist.
        if (pageRequest.getWeblogPageContent() != null) {
            ThemeTemplate template = null;
            try {
                template = weblog.getTheme().getTemplateByName("_page");
            } catch (Exception e) {
                // ignored ... considered page not found
            }
            return template != null ? template
                    : new StaticThemeTemplate("templates/weblog/page.vm",
                            TemplateLanguage.VELOCITY);
        }
```

- [ ] **Step 5: Expose the page to templates**

In `PageModel.java`, add:

```java
    /**
     * The static page being rendered, or null on every other weblog view.
     */
    public WeblogPage getPage() {
        return pageRequest.getWeblogPageContent();
    }
```

- [ ] **Step 6: Write the shipped default template**

Create `app/src/main/webapp/WEB-INF/velocity/templates/weblog/page.vm`:

```velocity
#**
The default rendering for a static page, used when the weblog's theme does
not supply a template named _page. Kept close to the themes' own permalink
markup so a page does not look foreign inside any of them.
*#
<!DOCTYPE html>
<html lang="$utils.escapeHTML($model.weblog.locale)">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src * data:; frame-src https://www.youtube-nocookie.com https://player.vimeo.com; base-uri 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'">
    <title>$utils.escapeHTML($model.page.title) : $utils.escapeHTML($model.weblog.name)</title>
    #showSeoHead()
    #showAnalyticsTrackingCode($model.weblog)
    <link rel="stylesheet" href="$model.weblog.stylesheet">
    #showGalleryGridStyles()
</head>
<body>
<header>
    <p><a href="$url.home">$utils.escapeHTML($model.weblog.name)</a></p>
    <nav>#showPageLinks($model.weblog)</nav>
</header>
<main>
    <article>
        <h1>$utils.escapeHTML($model.page.title)</h1>
        $model.page.renderedContent
    </article>
</main>
#showGalleryAssets()
#showMapAssets()
#showEmbedAssets()
</body>
</html>
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `mvn -pl app test -Dtest=PageRoutingTest`
Expected: PASS, all 6 tests.

- [ ] **Step 8: Verify existing routing is untouched**

Run: `mvn -pl app test -Dtest='WeblogRequestMapperTest,PageServletDecisionTest,PageServletCachingTest,*Rendering*Test'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/rendering/ \
        app/src/main/webapp/WEB-INF/velocity/templates/weblog/page.vm \
        app/src/test/java/org/apache/roller/weblogger/ui/rendering/PageRoutingTest.java
git commit -m "Route /<handle>/<slug> to a published page"
```

---

# Task 8: The page editor

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/PagesController.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/PageEditController.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/PageBean.java`
- Create: `app/src/main/webapp/WEB-INF/jsps/editor/Pages.jsp`, `PageEdit.jsp`
- Modify: `app/src/main/resources/org/apache/roller/weblogger/ui/menu/editor-menu.xml`
- Modify: `app/src/main/resources/ApplicationResources.properties`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/BaseController.java` (add `lookupPage`)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/editor/PagesControllerTest.java`, `PageEditControllerTest.java`

**Interfaces:**
- Consumes: `WeblogPageManager` (Task 6).
- Produces: `BaseController.lookupPage(String id, HttpServletRequest request)` returning the page **only if it belongs to the action weblog**, else null; routes `/roller-ui/authoring/pages.rol`, `pageEdit.rol`, `pageEdit!save.rol`, `pageRemove.rol`.

- [ ] **Step 1: Write the failing ownership test**

Create `app/src/test/java/org/apache/roller/weblogger/ui/controllers/editor/PageEditControllerTest.java`. The critical test — this is the `lookupCategory`/`lookupEntry` hazard again:

```java
    @Test
    void aPageBelongingToAnotherWeblogIsNotFound() throws Exception {
        // actionWeblog is weblogA; the id names a page on weblogB
        WeblogPage foreign = pageOn(weblogB, "their-about");

        String view = controller.edit(foreign.getId(), requestFor(weblogA), model);

        assertEquals(DENIED_VIEW, view,
                "the permission interceptor vouches only for the action weblog, "
                        + "so a global by-id lookup would let any editor edit any "
                        + "weblog's pages");
    }

    @Test
    void aBlankIdIsTreatedAsAbsentRatherThanLookedUp() throws Exception {
        assertNull(controller.lookupPage("   ", requestFor(weblogA)));
    }
```

Write the rest of the class covering: listing shows only this weblog's pages; save creates with `DRAFT` by default; save updates an existing page; a reserved slug is rejected with a field error rather than a 500; remove deletes only the named page.

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=PageEditControllerTest`
Expected: FAIL — the controller does not exist.

- [ ] **Step 3: Add `lookupPage` to `BaseController`**

Modelled exactly on the existing `lookupCategory`, including the blank-id guard:

```java
    /**
     * The page named by {@code id}, but only when it belongs to the weblog
     * this action is scoped to. The permission interceptor vouches for the
     * action weblog and nothing else, so a global by-id lookup would let any
     * editor reach any weblog's pages. A blank id is absent, not something to
     * look up.
     */
    public WeblogPage lookupPage(String id, HttpServletRequest request) {
        if (StringUtils.isBlank(id)) {
            return null;
        }
        try {
            WeblogPage page = WebloggerFactory.getWeblogger()
                    .getWeblogPageManager().getPage(id);
            if (page == null || !page.getWeblog().equals(getActionWeblog(request))) {
                return null;
            }
            return page;
        } catch (WebloggerException ex) {
            log.error("Error looking up page " + id, ex);
            return null;
        }
    }
```

- [ ] **Step 4: Write `PageBean`, `PagesController`, `PageEditController`**

`PageBean` carries `id, slug, title, content, status, showInNav, navOrder` plus the five SEO fields, with `copyTo`/`copyFrom` methods matching `EntryBean`'s shape.

Both controllers extend `BaseController`, are annotated `@Controller @RequestMapping("/roller-ui/authoring")`, override `requiredWeblogPermissionActions()` to return `List.of(WeblogPermission.POST)`, `getDesiredMenu()` → `"editor"`, `getActionName()` → `"pages"` / `"pageEdit"`, and `getPageTitle()` → the message keys added below.

**Every `@RequestParam` and `@PathVariable` is named explicitly**, e.g. `@RequestParam(name = "id", required = false) String id` — the build does not pass `-parameters`, so an unnamed one throws at runtime while these unit tests still pass. `ControllerMetadataTest` fails the build on any unnamed one.

`save` catches the `WebloggerException` from a reserved or malformed slug and adds a field error rather than letting it become a 500.

- [ ] **Step 5: Write the JSPs**

`Pages.jsp`: one form around the table, listing this weblog's pages with slug, title, status, nav order, an edit link and a remove button — following `Entries.jsp`'s single-form pattern, where per-row actions are submit buttons carrying a name rather than nested forms.

`PageEdit.jsp`: reuses the EasyMDE editor include the entry editor uses, driving it through the same three functions (`insertMediaFile`, `rollerSetEntryText`, `rollerGetEntryText`) rather than the editor's own API. Fields for slug, title, status, show-in-nav, nav order and a collapsible "SEO & Social Sharing" card matching `EntryEdit.jsp`'s.

- [ ] **Step 6: Register the menu item and messages**

In `editor-menu.xml`, after the `categories` item:

```xml
        <menu-item action="pages"
                   name="tabbedmenu.pages"
                   weblogPerms="author"/>
```

In `ApplicationResources.properties`:

```properties
tabbedmenu.pages=Pages
pagesForm.title=Pages
pagesForm.subtitle=Static pages for {0}
pagesForm.slug=Slug
pagesForm.navOrder=Nav order
pagesForm.showInNav=Show in navigation
pagesForm.add=New page
pageEdit.title=Edit page
pageEdit.error.slugReserved=That address is reserved. Choose another slug.
pageEdit.error.slugInvalid=A slug may not be blank or contain a slash.
```

- [ ] **Step 7: Run the tests**

Run: `mvn -pl app test -Dtest='PagesControllerTest,PageEditControllerTest,ControllerMetadataTest,MessageKeyTest'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/ \
        app/src/main/webapp/WEB-INF/jsps/editor/Pages.jsp \
        app/src/main/webapp/WEB-INF/jsps/editor/PageEdit.jsp \
        app/src/main/resources/ApplicationResources.properties \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/editor/
git commit -m "Add the page editor, ownership-checking every id"
```

---

# Task 9: Navigation in all five themes

**Files:**
- Modify: `app/src/main/webapp/WEB-INF/velocity/weblog.vm` (add `#showPageLinks`, extend `#showPageMenu` at line 1125)
- Modify: `app/src/main/webapp/themes/{basic,fauxcoly,gaurav,portfolio,travel}/*.vm`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/PageNavRenderingTest.java`

**Interfaces:**
- Consumes: `WeblogPageManager.getPublishedPages` (Task 6).
- Produces: `#showPageLinks($weblog)` emitting `<li><a href="…">Title</a></li>` items only — no wrapper — so each theme keeps its own nav markup and CSS.

- [ ] **Step 1: Write the failing test**

Create `PageNavRenderingTest` asserting: a published page with `showInNav=true` appears in the rendered home page of each of the five themes; a page with `showInNav=false` does not; a `DRAFT` page does not; ordering follows `navOrder`; and the page title is HTML-escaped.

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=PageNavRenderingTest`
Expected: FAIL — no nav links are emitted.

- [ ] **Step 3: Add the macro**

In `weblog.vm`, beside `#showWeblogCategoryLinksList`:

```velocity
#**
Navigation links for a weblog's published pages, in nav order.

Emits <li> items and nothing else -- no <ul>, no wrapper -- so each theme
supplies its own container and keeps its own CSS. That is why this is a
separate macro from #showPageMenu rather than an extension of it.
*#
#macro(showPageLinks $weblog)
#foreach($navPage in $model.getNavPages($weblog))
    <li class="page-nav-item"><a href="$url.staticPage($navPage.slug)">$utils.escapeHTML($navPage.title)</a></li>
#end
#end
```

Add `getNavPages(Weblog)` to `PageModel`, returning `getPublishedPages(weblog)` filtered to `getShowInNav()`.

Add **`staticPage(String slug)`** to `URLModel`, building `getWeblogURL(weblog, null, false) + slug`. It must **not** be called `page` — `URLModel.page(String)` already exists at line 248 and builds the `/page/<link>` template-page URL, which is a different address for a different thing. Two overloads distinguished only by intent would be a trap for whoever reads a theme next.

- [ ] **Step 4: Fold pages into `#showPageMenu`**

`basic/sidebar.vm:20` and `fauxcoly/std_header.vm:9` already call `#showPageMenu`, which iterates template pages. Add page links inside its existing `<ul class="rNavigationBar">`, immediately after the "Weblog" item:

```velocity
        #showPageLinks($weblog)
```

- [ ] **Step 5: Add nav to the three themes that have none**

`gaurav`, `portfolio` and `travel` have no page nav. In each theme's `weblog.vm`, `permalink.vm` and `searchresults.vm`, add inside the existing `<nav>` element, after `#showWeblogCategoryLinksList()`:

```velocity
        #showPageLinks($model.weblog)
```

- [ ] **Step 6: Run the tests**

Run: `mvn -pl app test -Dtest='PageNavRenderingTest,*Rendering*Test'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/webapp/WEB-INF/velocity/weblog.vm \
        app/src/main/webapp/themes/ \
        app/src/main/java/org/apache/roller/weblogger/ui/rendering/model/ \
        app/src/test/java/org/apache/roller/weblogger/ui/rendering/PageNavRenderingTest.java
git commit -m "Show published pages in every bundled theme's navigation"
```

---

# Task 10: Pages in the sitemap

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/SeoController.java:185-195`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/core/SeoControllerPagesTest.java`

**Interfaces:**
- Consumes: `WeblogPageManager.getPublishedPages` (Task 6).

- [ ] **Step 1: Write the failing test**

Assert that `sitemap-<handle>.xml` contains a published page's absolute URL; that a `DRAFT` page is absent; that a page with `noindex=true` is absent; and that `<lastmod>` carries the page's `updated` timestamp.

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=SeoControllerPagesTest`
Expected: FAIL — no page URLs in the sitemap.

- [ ] **Step 3: Emit the pages**

In `SeoController`, after the entry loop:

```java
        // Pages sit alongside entries in the sitemap: they are the addresses a
        // business site most wants indexed. noindex is honoured here for the
        // same reason it is for entries -- a page excluded from search must be
        // excluded from what we hand the crawler.
        for (WeblogPage page : weblogger.getWeblogPageManager().getPublishedPages(weblog)) {
            if (Boolean.TRUE.equals(page.getNoindex())) {
                continue;
            }
            xml.append("  <url>\n");
            xml.append("    <loc>").append(escapeXml(
                    urlStrategy.getWeblogURL(weblog, null, true) + page.getSlug()))
                    .append("</loc>\n");
            appendLastmod(xml, "    ", page.getUpdated());
            xml.append("  </url>\n");
        }
```

- [ ] **Step 4: Run the tests**

Run: `mvn -pl app test -Dtest='SeoController*Test'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/SeoController.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/core/SeoControllerPagesTest.java
git commit -m "Put published pages in the sitemap"
```

---

# Task 11: Browser integration tests

**Files:**
- Create: `it-selenium/src/test/java/.../PageIT.java`
- Create: `it-selenium/src/test/java/.../VideoEmbedIT.java`

**Interfaces:**
- Consumes: everything above.

- [ ] **Step 1: Write `PageIT`**

Following the existing IT conventions (own weblog per test class; never switch the seeded IT weblog's theme; `BrowserHealth` assertions on every page visited):

1. Sign in, open **Pages**, create a page with slug `about`, title `About Us`, body containing `**bold**` and a `[cta]`.
2. Save as draft. Visit `/<handle>/about` anonymously → 404.
3. Publish. Visit anonymously → 200, title present, `<strong>bold</strong>` present, the CTA rendered.
4. Assert the page appears in the theme's nav, and that clicking it from the home page arrives at the page.
5. Untick "Show in navigation", save, reload the home page → the link is gone but the URL still resolves.
6. `assertNoBrokenResources` and `assertNoFailedRequests` on every page.

- [ ] **Step 2: Write `VideoEmbedIT`**

1. Create an entry whose body is `[video url="https://youtu.be/dQw4w9WgXcQ"]`.
2. Publish and visit the permalink anonymously.
3. Assert a `.video-embed` element exists carrying `data-provider="youtube"`.
4. Assert **no `<iframe>` is present before a click** — the privacy property.
5. Assert `assertNoFailedRequests` is clean, which is what would catch the CSP refusing the thumbnail.

Do **not** click to play in CI: that would load a third-party frame and make the suite depend on YouTube being reachable. Assert the facade's wiring, not YouTube's behaviour.

- [ ] **Step 3: Run the browser suite**

Run: `mvn verify -Pit`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add it-selenium/
git commit -m "Add browser ITs for pages and video embeds"
```

---

# Task 12: Ratchet the gates and update the docs

**Files:**
- Modify: `pom.xml` (`jacoco.line.minimum`, `jacoco.branch.minimum`)
- Modify: `CLAUDE.md`

- [ ] **Step 1: Measure**

```bash
mvn clean test && mvn jacoco:report -pl app
```

Read `app/target/site/jacoco/index.html` for the new line and branch percentages.

- [ ] **Step 2: Check diff coverage**

Run: `bin/check-diff-coverage.sh ca93f25f5`

`ca93f25f5` is the commit Wave A started from — the spec commit. **Do not pass `master`**: this wave is committed directly onto master, so `master` is the current HEAD and the diff would be empty, reporting a vacuous pass.

Expected: ~90% on changed lines. Add tests for anything below before continuing.

- [ ] **Step 3: Raise the floors**

In the parent `pom.xml`, set `jacoco.line.minimum` and `jacoco.branch.minimum` to the measured values rounded **down** to two decimals. Floors only ever rise.

- [ ] **Step 4: Document the new subsystems in `CLAUDE.md`**

Add a `## Pages` section recording: pages are a separate entity *on purpose* (the 25 entry query paths); `/<handle>/<slug>` with `ReservedSlugs` as the single source of truth shared by the parser and the validator; `_page` template override with a shipped fallback, mirroring `_popupcomments`; and that `lookupPage` joins the `lookupEntry`/`lookupTemplate`/`lookupCategory` family.

Extend the `## Shortcodes` section with `[video]`: parses, never fetches; emits a placeholder because the sanitizer strips iframes; `#showEmbedAssets` injects the frame on click; and that the theme CSPs now carry a `frame-src` pinned by the three rendering tests.

- [ ] **Step 5: Full verification**

Run: `mvn clean verify -Pit`
Expected: BUILD SUCCESS, zero failures, JaCoCo gate passing.

- [ ] **Step 6: Commit**

```bash
git add pom.xml CLAUDE.md
git commit -m "Ratchet coverage floors and document pages and [video]"
```

---

# Self-review

**Spec coverage.** Every Wave A requirement maps to a task: `WeblogPage` entity → 5; `ShortcodeContext` → 1; shared render pipeline → 2; schema → 5; routing → 7; reserved slugs → 6; nav + five themes → 9; sitemap → 10; editor reuse → 8; `[video]` → 3; sanitizer + macro + CSP → 4; ITs → 11; floors and docs → 12. "No revisions in Wave A" is honoured — no task adds them.

**Deferred from Wave A, deliberately.** Page revisions (spec says so explicitly). The `[subscribe]` shortcode and `#showSubscribeForm` wiring are Wave B. `analyticsSiteId` is Wave C — Task 7's `page.vm` calls the *existing* `#showAnalyticsTrackingCode`, which still reads `weblog.analyticsCode` until Wave C changes it.

**Type consistency.** `ShortcodeContext` is `getWeblog()/getSlug()/getRawText()` in Tasks 1, 2, 3 and 5. `WeblogPage.PubStatus` is `DRAFT|PUBLISHED` in 5, 6, 7 and 8. `getPageBySlug(Weblog, String)` is consistent in 6 and 7. `getPublishedPages(Weblog)` is consistent in 6, 9 and 10. `getWeblogPageContent()` is consistent in 7. `showInNav`/`navOrder` are consistent in 5, 6, 8 and 9.

**Known risk.** Task 7 makes `WeblogPageRequest` hit the database during request parsing, which it did not do before. It is a single indexed lookup on `(weblogid, slug)` and only on a path shape that previously threw — but if `PageServlet`'s caching path turns out to construct the request before consulting `WeblogPageCache`, the lookup happens on cache hits too. Task 7 Step 8 runs `PageServletCachingTest` for exactly this reason; if it regresses, move the lookup behind the cache check rather than widening the cache key.
