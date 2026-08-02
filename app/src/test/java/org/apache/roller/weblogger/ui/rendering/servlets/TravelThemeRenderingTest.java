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
 * Renders the travel-guide theme through the real PageServlet: the front page
 * lays entries out as guide cards (hero image, title, excerpt), the permalink
 * gives them a wide reading column, and every head carries the same CSP meta
 * and the same four macros ({@code #showSeoHead}, the gallery grid styles, the
 * lightbox, and the Leaflet map assets) as the other five themes. Entries
 * without a featured image degrade to text-only cards rather than broken
 * markup, which is the case a guide blog hits constantly.
 */
class TravelThemeRenderingTest {

    private static final String HANDLE = "travelrenderblog";
    private static final String BASE = "http://localhost:8080/roller/" + HANDLE;

    /**
     * The CSP line every theme head ships, verbatim -- the same constant
     * {@code PortfolioThemeRenderingTest} and {@code MapAssetsRenderingTest}
     * hold. A new theme that drifts here would ship a weaker (or broken)
     * policy than its siblings.
     */
    private static final String CSP_META =
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
            + "script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
            + "img-src * data:; base-uri 'self'; connect-src 'self'; form-action 'self'; "
            + "frame-ancestors 'none'\">";

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("travelrenderuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        switchTheme("travel");
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
        assertEquals(200, response.getStatus(), "page must render for " + pathInfo);
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
        managed.setSearchDescription("Two days of hot springs and black sand.");
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
        return entry;
    }

    /** The shared head contract plus the no-Velocity-leak assertions. */
    private static void assertTravelHead(String body) {
        assertTrue(body.contains(CSP_META),
                "the travel head must carry the exact CSP meta the other themes ship:\n" + body);
        assertTrue(body.contains("<link rel=\"canonical\""),
                "#showSeoHead must contribute the canonical link:\n" + body);
        assertTrue(body.contains(".jgrid { display: flex;"),
                "#showGalleryGridStyles must be in the head:\n" + body);
        assertTrue(body.contains("/webjars/photoswipe/"),
                "#showGalleryAssets must ship the lightbox:\n" + body);
        assertTrue(body.contains("/webjars/leaflet/"),
                "#showMapAssets must ship Leaflet -- this is the travel theme:\n" + body);
        assertTrue(body.contains("travel-custom.css"),
                "the head must link the theme stylesheet override:\n" + body);
        // a Velocity error would leak the raw directive or reference text
        assertFalse(body.contains("#showResponsiveImage"), body);
        assertFalse(body.contains("#showGalleryAssets"), body);
        assertFalse(body.contains("#showMapAssets"), body);
        assertFalse(body.contains("#showSeoHead"), body);
        assertFalse(body.contains("$entry.featuredImage"), body);
        assertFalse(body.contains("$utils."), body);
    }

    // ------------------------------------------------------------ discovery

    @Test
    void theThemeManagerListsTheTravelTheme() throws Exception {
        SharedTheme travel = WebloggerFactory.getWeblogger().getThemeManager()
                .getEnabledThemesList().stream()
                .filter(theme -> "travel".equals(theme.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "travel theme missing from the enabled themes list"));
        assertEquals("Travel Guide", travel.getName());
        assertTrue(travel.getStylesheet() != null,
                "the theme must declare a stylesheet so per-weblog overrides work");
    }

    // ------------------------------------------------------------ front page

    @Test
    void theFrontPageLaysEntriesOutAsGuideCards() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "card-image");
        entryWithFeaturedImage("iceland-ring-road", image.getId());

        String body = render("/" + HANDLE + "/");

        assertTravelHead(body);
        assertTrue(body.contains("<div class=\"tg-cards\">"),
                "the front page must open the card grid:\n" + body);
        assertTrue(body.contains("class=\"tg-card\""),
                "each entry must render as a guide card:\n" + body);
        assertTrue(body.contains("class=\"tg-card-img\""),
                "a featured image must fill the card's media slot:\n" + body);
        assertTrue(body.contains("<picture>"),
                "the card image must go through #showResponsiveImage:\n" + body);
        assertTrue(body.contains(BASE + "/mediaresource/" + image.getId()),
                "and point at the entry's own featured image:\n" + body);
        assertTrue(body.contains("Two days of hot springs and black sand."),
                "the card excerpt must come from the entry's search description:\n" + body);
    }

    @Test
    void anEntryWithoutAFeaturedImageStillRendersACard() throws Exception {
        TestUtils.setupWeblogEntry("no-photos-yet", weblog, user);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/");

        assertTravelHead(body);
        assertTrue(body.contains("class=\"tg-card\""),
                "a text-only entry must still get a card:\n" + body);
        assertFalse(body.contains("class=\"tg-card-media\""),
                "but no media slot, since there is no image to put in it:\n" + body);
        assertFalse(body.contains("<picture>"),
                "and no responsive-image markup at all:\n" + body);
    }

    // ------------------------------------------------------------- permalink

    @Test
    void thePermalinkLeadsWithTheHeroAndOpensTheReadingColumn() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "hero-image");
        entryWithFeaturedImage("faroe-islands", image.getId());

        String body = render("/" + HANDLE + "/entry/faroe-islands");

        assertTravelHead(body);
        assertTrue(body.contains("<main class=\"tg-main tg-main-entry\">"),
                "the permalink must use the narrower reading column:\n" + body);
        assertTrue(body.contains("<figure class=\"tg-hero\">"),
                "and lead with the hero figure:\n" + body);
        assertTrue(body.contains("class=\"tg-hero-img\""),
                "whose image is the featured image:\n" + body);
        assertTrue(body.contains("<h1 class=\"tg-entry-title\">"),
                "the entry title must be the page's h1:\n" + body);
        assertTrue(body.contains("<div class=\"tg-entry-content\">"),
                "and the content must open the measured column:\n" + body);
    }

    @Test
    void aPermalinkWithoutAFeaturedImageSkipsTheHero() throws Exception {
        TestUtils.setupWeblogEntry("packing-list", weblog, user);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/entry/packing-list");

        assertTravelHead(body);
        assertTrue(body.contains("<h1 class=\"tg-entry-title\">"), body);
        assertFalse(body.contains("<figure class=\"tg-hero\">"),
                "no featured image means no empty hero figure:\n" + body);
    }

    // ------------------------------------------------------- shortcode blocks

    @Test
    void theStylesheetStylesTheTravelShortcodeBlocks() throws Exception {
        // The theme's whole reason to exist: [map], [faq] and [cta] must land
        // in a guide-book frame without the author writing any theme markup.
        String css = render("/" + HANDLE + "/page/travel-custom.css");

        assertTrue(css.contains("body.tg .travel-map"),
                "the map container must be sized by the theme:\n" + css);
        assertTrue(css.contains("body.tg .faq"),
                "the FAQ definition list must be styled:\n" + css);
        assertTrue(css.contains("body.tg .cta-card"),
                "the CTA card must be styled:\n" + css);
        assertFalse(css.contains("@font-face"),
                "the theme CSP declares no font-src, so web fonts must not appear:\n" + css);
        assertFalse(css.contains("http://"),
                "no remote CSS references -- script/style-src are 'self':\n" + css);
    }
}
