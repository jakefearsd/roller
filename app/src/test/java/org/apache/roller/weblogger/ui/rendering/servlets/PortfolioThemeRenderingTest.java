/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package org.apache.roller.weblogger.ui.rendering.servlets;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.themes.SharedTheme;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the new portfolio theme through the real PageServlet /
 * SearchServlet: the front page packs featured images into the justified
 * {@code .pf-grid} (with the {@code --ar} aspect ratio driving the packing,
 * same convention as the gallery shortcode's {@code .jgrid}), the permalink
 * leads with a full-bleed hero, and every head carries the same CSP meta,
 * {@code #showSeoHead}, and the gallery grid + lightbox assets as the other
 * four themes. Entries without a featured image degrade to quiet text cards
 * rather than broken markup.
 */
class PortfolioThemeRenderingTest {

    private static final String HANDLE = "portfoliorenderblog";
    private static final String BASE = "http://localhost:8080/roller/" + HANDLE;

    /**
     * The directives this test actually cares about, not the whole policy
     * string -- pinning the whole string made it impossible for a theme to
     * name a directive its own assets need without failing this test (see
     * {@code MapAssetsRenderingTest#CSP_DATA_IMAGES}, which holds the same
     * lesson). {@code data:} joined {@code img-src} when Leaflet arrived: it
     * paints aborted and out-of-range tiles with a base64 GIF placeholder,
     * and per CSP3 the {@code *} wildcard does not match the {@code data:}
     * scheme.
     */
    private static final String CSP_DATA_IMAGES = "img-src * data:;";

    /**
     * The single authorised CSP widening for this wave: frame-src limited to
     * the two providers {@code [video]} can target. Same constant
     * {@code MapAssetsRenderingTest} and {@code TravelThemeRenderingTest} hold.
     */
    private static final String CSP_VIDEO_FRAMES =
            "frame-src https://www.youtube-nocookie.com https://player.vimeo.com;";

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("portfoliorenderuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        switchTheme("portfolio");
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

    private void switchTheme(String themeName) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(themeName);
        WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    private WeblogEntry entryWithFeaturedImage(String anchor, String imageId)
            throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, weblog, user);
        WeblogEntryManager mgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setFeaturedImageId(imageId);
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
        return entry;
    }

    /** The shared head contract plus the no-Velocity-leak assertions. */
    private static void assertPortfolioHead(String body) {
        assertTrue(body.contains(CSP_DATA_IMAGES),
                "the portfolio head must allow data: images, like the other themes:\n" + body);
        assertTrue(body.contains(CSP_VIDEO_FRAMES),
                "the portfolio head must allow the video providers' frames:\n" + body);
        assertTrue(body.contains("<link rel=\"canonical\""),
                "#showSeoHead must contribute the canonical link:\n" + body);
        assertTrue(body.contains(".jgrid { display: flex;"),
                "#showGalleryGridStyles must be in the head:\n" + body);
        assertTrue(body.contains("/webjars/photoswipe/"),
                "#showGalleryAssets must ship the lightbox:\n" + body);
        assertTrue(body.contains("portfolio-custom.css"),
                "the head must link the theme stylesheet override:\n" + body);
        // a Velocity error would leak the raw directive or reference text
        assertFalse(body.contains("#showResponsiveImage"), body);
        assertFalse(body.contains("#showGalleryAssets"), body);
        assertFalse(body.contains("#showGalleryGridStyles"), body);
        assertFalse(body.contains("#showSeoHead"), body);
        assertFalse(body.contains("$entry.featuredImage"), body);
    }

    // ------------------------------------------------------------ discovery

    @Test
    void theThemeManagerListsThePortfolioTheme() throws Exception {
        SharedTheme portfolio = WebloggerFactory.getWeblogger().getThemeManager()
                .getEnabledThemesList().stream()
                .filter(theme -> "portfolio".equals(theme.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "portfolio theme missing from the enabled themes list"));
        assertEquals("Portfolio", portfolio.getName());
        assertTrue(portfolio.getStylesheet() != null,
                "the theme must declare a stylesheet so per-weblog overrides work");
    }

    // ------------------------------------------------------------ front page

    @Test
    void theFrontPagePacksFeaturedImagesIntoTheJustifiedGrid() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "grid-image");
        String imageId = image.getId();
        TestUtils.endSession(true);
        entryWithFeaturedImage("grid-entry", imageId);

        String body = render("/" + HANDLE);

        assertPortfolioHead(body);
        assertTrue(body.contains("<div class=\"pf-grid\">"),
                "the front page is the justified grid:\n" + body);
        // hawk.jpg is 500x373; the card carries width/height as --ar so the
        // grid can pack justified rows exactly like .jgrid does
        assertTrue(body.contains("<figure class=\"pf-card\" style=\"--ar:1.34"),
                "each card must carry its aspect ratio for justified packing:\n" + body);
        assertTrue(body.contains("class=\"pf-card-img\""),
                "the featured image renders through #showResponsiveImage:\n" + body);
        assertTrue(body.contains("<picture>"), body);
        assertTrue(body.contains(BASE + "/mediaresource/" + imageId),
                "the card image must come off the rendition ladder:\n" + body);
        assertTrue(body.contains("<span class=\"pf-card-title\">grid-entry</span>"),
                "the card carries the title overlay:\n" + body);
        assertTrue(body.contains("<a class=\"pf-card-link\" href=\"" + BASE
                        + "/entry/grid-entry\">"),
                "the whole card links to the entry:\n" + body);
    }

    @Test
    void anEntryWithoutAFeaturedImageBecomesAQuietTextCard() throws Exception {
        TestUtils.setupWeblogEntry("bare-entry", weblog, user);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE);

        assertPortfolioHead(body);
        assertTrue(body.contains("<figure class=\"pf-card pf-card-text\">"),
                "imageless entries must still get a card:\n" + body);
        assertTrue(body.contains("<span class=\"pf-card-title\">bare-entry</span>"), body);
        assertFalse(body.contains("<picture>"),
                "no responsive image may be emitted without a featured image:\n" + body);
    }

    // ------------------------------------------------------------- permalink

    @Test
    void thePermalinkLeadsWithAFullBleedHero() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "hero-image");
        String imageId = image.getId();
        TestUtils.endSession(true);
        entryWithFeaturedImage("hero-entry", imageId);

        String body = render("/" + HANDLE + "/entry/hero-entry");

        assertPortfolioHead(body);
        assertTrue(body.contains("<figure class=\"pf-hero\">"),
                "the permalink leads with the hero figure:\n" + body);
        assertTrue(body.contains("class=\"pf-hero-img\""), body);
        assertTrue(body.contains("sizes=\"100vw\""),
                "the hero is full-bleed, so it declares 100vw:\n" + body);
        assertTrue(body.contains("<h1 class=\"pf-entry-title\">hero-entry</h1>"), body);
        assertTrue(body.contains("blah blah entry"),
                "the entry content must render below the hero:\n" + body);
        assertTrue(body.contains("class=\"pf-comments\""),
                "comments stay available, just below the image and text:\n" + body);
    }

    @Test
    void aPermalinkWithoutAFeaturedImageRendersWithoutBreakage() throws Exception {
        TestUtils.setupWeblogEntry("plain-entry", weblog, user);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/entry/plain-entry");

        assertPortfolioHead(body);
        assertFalse(body.contains("pf-hero"),
                "no hero markup may appear without a featured image:\n" + body);
        assertFalse(body.contains("<picture>"), body);
        assertTrue(body.contains("<h1 class=\"pf-entry-title\">plain-entry</h1>"), body);
        assertTrue(body.contains("blah blah entry"), body);
    }

    // ------------------------------------------------------------ stylesheet

    /**
     * The stylesheet ships as a Velocity-rendered theme template (the
     * declared override target), so an id selector or stray directive-looking
     * token that Velocity chokes on would corrupt the whole file at serve
     * time -- render it for real.
     */
    @Test
    void theStylesheetRendersIntactThroughTheTemplateEngine() throws Exception {
        String body = render("/" + HANDLE + "/page/portfolio-custom.css");

        assertTrue(body.contains(".pf-grid {"), body);
        assertTrue(body.contains("flex-basis: calc(var(--ar) * var(--pf-row-h));"),
                "the justified-packing rule must survive Velocity:\n" + body);
        assertTrue(body.contains("#searchAgain input.text"),
                "id selectors must pass through Velocity untouched:\n" + body);
        assertFalse(body.contains("ParseErrorException"), body);
    }

    // ---------------------------------------------------------------- search

    @Test
    void theSearchPageRendersThePortfolioTemplate() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/search", "/" + HANDLE);
        request.setParameter("q", "zzznope");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains(CSP_DATA_IMAGES),
                "the search head must allow data: images:\n" + body);
        assertTrue(body.contains(CSP_VIDEO_FRAMES),
                "the search head must allow the video providers' frames:\n" + body);
        assertTrue(body.contains(".jgrid { display: flex;"), body);
        assertTrue(body.contains("/webjars/photoswipe/"), body);
        assertTrue(body.contains("<div class=\"pf-grid\">"),
                "search results reuse the justified grid:\n" + body);
        assertTrue(body.contains("id=\"searchAgain\""),
                "the search-again form must be present:\n" + body);
    }
}
