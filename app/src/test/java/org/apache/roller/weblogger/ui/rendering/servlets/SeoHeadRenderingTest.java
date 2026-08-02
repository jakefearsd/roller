package org.apache.roller.weblogger.ui.rendering.servlets;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.ui.controllers.editor.EntryBean;
import org.apache.roller.weblogger.util.RollerMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders real themes through the real PageServlet and asserts on the
 * automatic SEO baseline that {@code #showSeoHead} emits into every theme
 * head: meta description, robots, rel=canonical, Open Graph, Twitter card
 * and JSON-LD. The JSON-LD blocks are extracted and actually JSON-parsed --
 * a stray comma or an unescaped quote makes crawlers drop the whole block,
 * and string-contains assertions would never notice.
 */
class SeoHeadRenderingTest {

    private static final String HANDLE = "seorenderblog";
    private static final String BASE = "http://localhost:8080/roller/" + HANDLE;

    private static final Pattern LD_JSON = Pattern.compile(
            "<script type=\"application/ld\\+json\">(.*?)</script>", Pattern.DOTALL);

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("seorenderuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ---------------------------------------------------------------- helpers

    private String render(String pathInfo) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", pathInfo);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(),
                "page must render for " + pathInfo);
        return response.getContentAsString();
    }

    private void updateEntry(WeblogEntry entry, Consumer<WeblogEntry> mutation)
            throws Exception {
        WeblogEntryManager mgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        mutation.accept(managed);
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
    }

    private void switchTheme(String themeName) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(themeName);
        WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    /** Extract every ld+json block and JSON-parse it; parse failure fails the test. */
    private static List<JsonNode> ldJsonBlocks(String body) {
        List<JsonNode> nodes = new ArrayList<>();
        Matcher matcher = LD_JSON.matcher(body);
        ObjectMapper mapper = new ObjectMapper();
        while (matcher.find()) {
            nodes.add(mapper.readTree(matcher.group(1)));
        }
        return nodes;
    }

    private static JsonNode singleLdJson(String body, String expectedType) {
        List<JsonNode> nodes = ldJsonBlocks(body);
        assertEquals(1, nodes.size(),
                "expected exactly one JSON-LD block; body:\n" + body);
        JsonNode node = nodes.get(0);
        assertEquals(expectedType, node.path("@type").asString(),
                "wrong JSON-LD @type in: " + node);
        assertEquals("https://schema.org", node.path("@context").asString());
        return node;
    }

    // ----------------------------------------------------------------- basic

    @Test
    void permalinkEmitsTheFullSeoBaseline() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("seo-entry", weblog, user);
        updateEntry(entry, e -> e.setSearchDescription("A concise entry description."));

        String body = render("/" + HANDLE + "/entry/seo-entry");
        String canonical = BASE + "/entry/seo-entry";

        assertTrue(body.contains(
                "<meta name=\"description\" content=\"A concise entry description.\">"),
                "permalink meta description must be the entry's search description:\n" + body);
        assertTrue(body.contains("<link rel=\"canonical\" href=\"" + canonical + "\">"),
                "canonical must be the exact absolute entry URL:\n" + body);
        assertTrue(body.contains("<meta property=\"og:type\" content=\"article\">"),
                "a permalink is an og article:\n" + body);
        assertTrue(body.contains("<meta property=\"og:title\" content=\"seo-entry\">"),
                "og:title must be the entry title:\n" + body);
        assertTrue(body.contains(
                "<meta property=\"og:description\" content=\"A concise entry description.\">"));
        assertTrue(body.contains("<meta property=\"og:url\" content=\"" + canonical + "\">"));
        assertTrue(body.contains("<meta property=\"og:site_name\" content="),
                "og:site_name must be present:\n" + body);
        assertTrue(body.contains("<meta name=\"twitter:card\" content=\"summary\">"),
                "with no image the twitter card is a plain summary:\n" + body);
        assertFalse(body.contains("<meta name=\"robots\""),
                "an ordinary entry must not carry a robots directive:\n" + body);

        JsonNode posting = singleLdJson(body, "BlogPosting");
        assertEquals("seo-entry", posting.path("headline").asString());
        assertEquals(canonical, posting.path("mainEntityOfPage").asString());
        assertEquals("A concise entry description.",
                posting.path("description").asString());
        assertFalse(posting.path("datePublished").asString().isEmpty(),
                "datePublished must be present: " + posting);
        assertFalse(posting.path("dateModified").asString().isEmpty(),
                "dateModified must be present: " + posting);
        assertEquals("Person", posting.path("author").path("@type").asString());
        assertEquals("Test User Screen Name",
                posting.path("author").path("name").asString(),
                "author must be the entry creator's screen name: " + posting);
    }

    @Test
    void frontPageEmitsWebsiteSeo() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setAbout("All about rendering tests");
        WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        TestUtils.setupWeblogEntry("front-entry", weblog, user);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE);

        assertTrue(body.contains("<link rel=\"canonical\" href=\"" + BASE + "/\">"),
                "the front page canonicalises to the weblog home URL:\n" + body);
        assertTrue(body.contains("<meta property=\"og:type\" content=\"website\">"),
                "a collection page is an og website:\n" + body);
        assertTrue(body.contains(
                "<meta name=\"description\" content=\"All about rendering tests\">"),
                "the front page description is the weblog's about text:\n" + body);

        JsonNode blog = singleLdJson(body, "Blog");
        assertEquals("Test Weblog", blog.path("name").asString());
        assertEquals(BASE + "/", blog.path("url").asString());
        assertEquals("All about rendering tests", blog.path("description").asString());
    }

    @Test
    void noindexEntryGetsARobotsMeta() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("hidden-entry", weblog, user);
        updateEntry(entry, e -> e.setNoindex(true));

        String body = render("/" + HANDLE + "/entry/hidden-entry");

        assertTrue(body.contains("<meta name=\"robots\" content=\"noindex\">"),
                "a noindex entry must carry the robots directive:\n" + body);
    }

    @Test
    void perEntryCanonicalOverrideWinsOnThePermalink() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("syndicated-entry", weblog, user);
        updateEntry(entry, e -> e.setCanonicalUrl("https://original.example.com/first-home"));

        String body = render("/" + HANDLE + "/entry/syndicated-entry");

        assertTrue(body.contains(
                "<link rel=\"canonical\" href=\"https://original.example.com/first-home\">"),
                "the per-entry canonical override must win:\n" + body);
        assertFalse(body.contains(
                "<link rel=\"canonical\" href=\"" + BASE + "/entry/syndicated-entry\">"),
                "the natural permalink must not also be claimed as canonical:\n" + body);
        JsonNode posting = singleLdJson(body, "BlogPosting");
        assertEquals("https://original.example.com/first-home",
                posting.path("mainEntityOfPage").asString());
    }

    // ------------------------------------------------------ escaping (carry-forward)

    @Test
    void aCanonicalUrlContainingAQuoteCannotBreakOutOfItsAttribute() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("escape-entry", weblog, user);
        updateEntry(entry, e -> e.setCanonicalUrl("https://example.com/pa\"th"));

        String body = render("/" + HANDLE + "/entry/escape-entry");

        assertTrue(body.contains(
                "<link rel=\"canonical\" href=\"https://example.com/pa&quot;th\">"),
                "the quote must be attribute-escaped:\n" + body);
        assertFalse(body.contains("https://example.com/pa\"th"),
                "the raw quote must never reach the page, in any context:\n" + body);
        // and the JSON-LD block that carries the same URL must still parse
        singleLdJson(body, "BlogPosting");
    }

    @Test
    void aMetaTitleContainingAQuoteIsEscapedEverywhereItAppears() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("titled-entry", weblog, user);
        updateEntry(entry, e -> e.setMetaTitle("Sneaky \"quoted\" title"));

        String body = render("/" + HANDLE + "/entry/titled-entry");

        assertTrue(body.contains(
                "<meta property=\"og:title\" content=\"Sneaky &quot;quoted&quot; title\">"),
                "og:title must carry the attribute-escaped meta title:\n" + body);
        assertTrue(body.contains(
                "<title>Sneaky &quot;quoted&quot; title : Test Weblog</title>"),
                "the meta title must replace the entry title in <title>:\n" + body);
        assertFalse(body.contains("Sneaky \"quoted\" title"),
                "the raw quotes must never reach the page:\n" + body);
        assertEquals("Sneaky \"quoted\" title",
                singleLdJson(body, "BlogPosting").path("headline").asString(),
                "the JSON-LD headline must decode back to the original title");
    }

    /**
     * Roller stores UI-authored entry titles HTML-escaped ({@code EntryBean.copyTo}
     * runs them through escapeHtml4, with a symmetric unescape in copyFrom),
     * which is why themes emit {@code $entry.title} raw. #showSeoHead must
     * therefore unescape before its own emit-time escape, or "Fish & Chips"
     * ships to share cards as "Fish &amp;amp; Chips" and lands in the JSON-LD
     * headline as a literal entity.
     *
     * <p>The fixture deliberately goes through the bean rather than setting the
     * title directly: the escape-on-save step is exactly what the other tests
     * here (raw TestUtils titles) bypass, which is why they never caught this.
     */
    @Test
    void aUiAuthoredTitleIsEscapedExactlyOnceInTheSeoHead() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("ampersand-entry", weblog, user);
        saveTitleThroughTheEditorBean(entry, "Fish & \"Chips\"");

        String body = render("/" + HANDLE + "/entry/ampersand-entry");

        assertTrue(body.contains(
                "<meta property=\"og:title\" content=\"Fish &amp; &quot;Chips&quot;\">"),
                "og:title must be escaped exactly once -- and still attribute-safe:\n" + body);
        assertFalse(body.contains("&amp;amp;"),
                "a stored-escaped title must not be escaped a second time:\n" + body);
        assertFalse(body.contains("Fish & \"Chips\""),
                "the raw quotes must never reach the page:\n" + body);
        assertEquals("Fish & \"Chips\"",
                singleLdJson(body, "BlogPosting").path("headline").asString(),
                "the JSON-LD headline must decode back to the author's title");
    }

    /**
     * Saves {@code title} the way the editor does: through {@link EntryBean},
     * whose copyTo HTML-escapes the title on the way into the database.
     */
    private void saveTitleThroughTheEditorBean(WeblogEntry entry, String title) throws Exception {
        WeblogEntryManager mgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        EntryBean bean = new EntryBean();
        bean.copyFrom(managed, Locale.getDefault());
        bean.setTitle(title);
        bean.copyTo(managed);
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
    }

    // ----------------------------------------------------------------- og:image

    @Test
    void theFeaturedImageBecomesTheOgImage() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "seo-og-image");
        String imageId = image.getId();
        TestUtils.endSession(true);
        WeblogEntry entry = TestUtils.setupWeblogEntry("pictured-entry", weblog, user);
        updateEntry(entry, e -> e.setFeaturedImageId(imageId));

        String body = render("/" + HANDLE + "/entry/pictured-entry");

        // hawk.jpg is 500x373: narrower than 1600, so og:image is the
        // original's URL untouched -- no ?w= rendition parameter.
        String imageUrl = BASE + "/mediaresource/" + imageId;
        assertTrue(body.contains("<meta property=\"og:image\" content=\"" + imageUrl + "\">"),
                "og:image must point at the media file:\n" + body);
        assertTrue(body.contains("<meta property=\"og:image:width\" content=\"500\">"),
                "og:image:width must be the stored pixel width:\n" + body);
        assertTrue(body.contains("<meta property=\"og:image:height\" content=\"373\">"),
                "og:image:height must be the stored pixel height:\n" + body);
        assertTrue(body.contains(
                "<meta name=\"twitter:card\" content=\"summary_large_image\">"),
                "with an image the twitter card upgrades to summary_large_image:\n" + body);

        JsonNode posting = singleLdJson(body, "BlogPosting");
        assertEquals(imageUrl, posting.path("image").asString(),
                "the JSON-LD image must match og:image: " + posting);
    }

    /**
     * Persists a content-backed media file from a generated blank image of
     * the given pixel size and format. setupImageMediaFile always uploads
     * the 500px hawk.jpg, which can never exercise the >1600px og:image
     * branch in either direction.
     */
    private MediaFile setupGeneratedImage(String name, String contentType,
            String format, int width, int height) throws Exception {
        java.util.Map<String, RuntimeConfigProperty> config = WebloggerFactory
                .getWeblogger().getPropertiesManager().getProperties();
        config.get("uploads.enabled").setValue("true");

        MediaFileManager mgr = WebloggerFactory.getWeblogger().getMediaFileManager();
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        MediaFileDirectory root = mgr.getDefaultMediaFileDirectory(managed);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, bytes),
                "no ImageIO writer for format " + format);

        MediaFile mediaFile = new MediaFile();
        mediaFile.setName(name);
        mediaFile.setDirectory(root);
        mediaFile.setWeblog(managed);
        mediaFile.setContentType(contentType);
        mediaFile.setInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        mgr.createMediaFile(managed, mediaFile, new RollerMessages());
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);
        return mediaFile;
    }

    @Test
    void aWideJpegOgImageUsesThe1600RenditionAndScaledDimensions() throws Exception {
        MediaFile image = setupGeneratedImage("wide.jpg", "image/jpeg", "jpg", 2000, 1000);
        String imageId = image.getId();
        WeblogEntry entry = TestUtils.setupWeblogEntry("wide-jpg-entry", weblog, user);
        updateEntry(entry, e -> e.setFeaturedImageId(imageId));

        String body = render("/" + HANDLE + "/entry/wide-jpg-entry");

        // jpeg is rendition-eligible, so the _1600 file exists and ?w=1600
        // really serves a 1600px image: the metadata may claim it.
        String imageUrl = BASE + "/mediaresource/" + imageId + "?w=1600";
        assertTrue(body.contains("<meta property=\"og:image\" content=\"" + imageUrl + "\">"),
                "a wide jpeg must use the 1600px rendition URL:\n" + body);
        assertTrue(body.contains("<meta property=\"og:image:width\" content=\"1600\">"),
                "the declared width must match the served rendition:\n" + body);
        assertTrue(body.contains("<meta property=\"og:image:height\" content=\"800\">"),
                "the declared height must be scaled to the rendition (2000x1000 -> 1600x800):\n" + body);
        assertEquals(imageUrl,
                singleLdJson(body, "BlogPosting").path("image").asString());
    }

    @Test
    void aWideGifOgImageKeepsTheOriginalUrlAndDimensions() throws Exception {
        // RenditionSupport only generates renditions for jpeg/png. For a
        // wide GIF the ?w=1600 URL would silently serve the full-resolution
        // original, so the macro must not claim 1600px dimensions for it.
        MediaFile image = setupGeneratedImage("wide.gif", "image/gif", "gif", 2000, 1000);
        String imageId = image.getId();
        WeblogEntry entry = TestUtils.setupWeblogEntry("wide-gif-entry", weblog, user);
        updateEntry(entry, e -> e.setFeaturedImageId(imageId));

        String body = render("/" + HANDLE + "/entry/wide-gif-entry");

        String imageUrl = BASE + "/mediaresource/" + imageId;
        assertTrue(body.contains("<meta property=\"og:image\" content=\"" + imageUrl + "\">"),
                "a wide gif must use the plain original URL:\n" + body);
        assertFalse(body.contains("?w=1600"),
                "no rendition URL may be emitted for a format with no renditions:\n" + body);
        assertTrue(body.contains("<meta property=\"og:image:width\" content=\"2000\">"),
                "the declared width must be the original's real width:\n" + body);
        assertTrue(body.contains("<meta property=\"og:image:height\" content=\"1000\">"),
                "the declared height must be the original's real height:\n" + body);
        assertEquals(imageUrl,
                singleLdJson(body, "BlogPosting").path("image").asString());
    }

    // ---------------------------------------------------------- other themes

    @Test
    void gauravThemeEmitsTheSeoBaseline() throws Exception {
        switchTheme("gaurav");
        WeblogEntry entry = TestUtils.setupWeblogEntry("gaurav-entry", weblog, user);
        updateEntry(entry, e -> e.setMetaTitle("Gaurav meta title"));

        String body = render("/" + HANDLE + "/entry/gaurav-entry");

        assertTrue(body.contains("<link rel=\"canonical\" href=\""
                        + BASE + "/entry/gaurav-entry\">"),
                "gaurav permalinks must carry the canonical link:\n" + body);
        assertTrue(body.contains("<meta property=\"og:type\" content=\"article\">"));
        assertTrue(body.contains("<title>Test Weblog: Gaurav meta title</title>"),
                "gaurav's entry <title> must honour the meta title override:\n" + body);
        singleLdJson(body, "BlogPosting");
        assertFalse(body.contains("<meta name=\"Description\""),
                "the theme's old hand-rolled Description meta must be gone:\n" + body);
    }

    @Test
    void fauxcolyThemeEmitsTheSeoBaselineForTheFirstTime() throws Exception {
        switchTheme("fauxcoly");
        WeblogEntry entry = TestUtils.setupWeblogEntry("fauxcoly-entry", weblog, user);
        updateEntry(entry, e -> e.setSearchDescription("Fauxcoly gets metas now"));

        String body = render("/" + HANDLE + "/entry/fauxcoly-entry");

        assertTrue(body.contains(
                "<meta name=\"description\" content=\"Fauxcoly gets metas now\">"),
                "fauxcoly historically had no meta description at all:\n" + body);
        assertTrue(body.contains("<link rel=\"canonical\" href=\""
                        + BASE + "/entry/fauxcoly-entry\">"));
        singleLdJson(body, "BlogPosting");
    }

    @Test
    void frontpageThemeEmitsTheSeoBaseline() throws Exception {
        switchTheme("frontpage");

        String body = render("/" + HANDLE);

        assertTrue(body.contains("<link rel=\"canonical\" href=\"" + BASE + "/\">"),
                "the frontpage theme head must carry the canonical link:\n" + body);
        assertTrue(body.contains("<meta property=\"og:type\" content=\"website\">"));
        assertTrue(body.contains("<meta property=\"og:site_name\" content="));
        singleLdJson(body, "Blog");
    }

    @Test
    void basicSearchResultsPageClaimsNoCanonicalUrl() throws Exception {
        TestUtils.setupWeblogEntry("searchable-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/search", "/" + HANDLE);
        request.setParameter("q", "searchable");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertFalse(body.contains("rel=\"canonical\""),
                "search pages have no canonical form:\n" + body);
        assertFalse(body.contains("$model"),
                "no unresolved Velocity references may leak into the page:\n" + body);
        assertNotNull(body);
        assertTrue(ldJsonBlocks(body).isEmpty(),
                "search pages emit no JSON-LD:\n" + body);
    }
}
