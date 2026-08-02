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

import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders every one of the six theme heads through the real PageServlet /
 * SearchServlet and asserts that {@code #showGalleryAssets} ships the
 * PhotoSwipe lightbox assets <em>exactly once</em> per page: the webjar
 * stylesheet link, the two ESM module references (the lightbox controller,
 * and the lazily-imported core), and the {@code .jgrid} presence guard that
 * keeps gallery-less pages from downloading any of the JS.
 *
 * <p>gaurav and fauxcoly share one head fragment across their five page
 * types, so a single render each covers them; basic repeats its head in
 * three top-level templates, so all three are rendered; frontpage has its
 * own shared {@code _header}. A theme head that called the macro twice (or
 * a shared fragment included twice) would double-initialize the lightbox,
 * which is why these are exact-count assertions rather than "contains".
 */
class GalleryAssetsRenderingTest {

    private static final String HANDLE = "galleryassetblog";

    private static final String PSWP_CSS =
            "/webjars/photoswipe/5.4.3/dist/photoswipe.css";
    private static final String PSWP_LIGHTBOX_MODULE =
            "/webjars/photoswipe/5.4.3/dist/photoswipe-lightbox.esm.min.js";
    private static final String PSWP_CORE_MODULE =
            "/webjars/photoswipe/5.4.3/dist/photoswipe.esm.min.js";
    /** The cheap lazy-init guard: no .jgrid on the page, no module downloads. */
    private static final String JGRID_GUARD = "document.querySelector('.jgrid')";

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("galleryassetuser");
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
        assertEquals(200, response.getStatus(), "page must render for " + pathInfo);
        return response.getContentAsString();
    }

    private void switchTheme(String themeName) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(themeName);
        WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    private static void assertAssetsExactlyOnce(String body, String head) {
        assertEquals(1, StringUtils.countMatches(body, PSWP_CSS),
                head + " must link the PhotoSwipe stylesheet exactly once:\n" + body);
        assertEquals(1, StringUtils.countMatches(body, PSWP_LIGHTBOX_MODULE),
                head + " must import the lightbox module exactly once:\n" + body);
        assertEquals(1, StringUtils.countMatches(body, PSWP_CORE_MODULE),
                head + " must reference the core module exactly once:\n" + body);
        assertEquals(1, StringUtils.countMatches(body, JGRID_GUARD),
                head + " must carry the .jgrid lazy-init guard exactly once:\n" + body);
        assertEquals(1, StringUtils.countMatches(body, "<script type=\"module\">"),
                head + " must emit exactly one module script:\n" + body);
        // a Velocity error would leak the raw directive text into the page
        assertFalse(body.contains("#showGalleryAssets"),
                head + " leaked the macro directive:\n" + body);
    }

    // ------------------------------------------------------------- six heads

    @Test
    void basicFrontPageShipsTheLightboxAssetsOnce() throws Exception {
        assertAssetsExactlyOnce(render("/" + HANDLE), "basic weblog.vm");
    }

    @Test
    void basicPermalinkShipsTheLightboxAssetsOnce() throws Exception {
        TestUtils.setupWeblogEntry("asset-entry", weblog, user);
        TestUtils.endSession(true);
        assertAssetsExactlyOnce(render("/" + HANDLE + "/entry/asset-entry"),
                "basic permalink.vm");
    }

    @Test
    void basicSearchResultsShipTheLightboxAssetsOnce() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/search", "/" + HANDLE);
        request.setParameter("q", "anything");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);
        assertEquals(200, response.getStatus());
        assertAssetsExactlyOnce(response.getContentAsString(),
                "basic searchresults.vm");
    }

    @Test
    void frontpageThemeShipsTheLightboxAssetsOnce() throws Exception {
        switchTheme("frontpage");
        assertAssetsExactlyOnce(render("/" + HANDLE), "frontpage _header.vm");
    }

    @Test
    void gauravSharedHeadShipsTheLightboxAssetsOnce() throws Exception {
        switchTheme("gaurav");
        assertAssetsExactlyOnce(render("/" + HANDLE), "gaurav std_head.vm");
    }

    @Test
    void fauxcolySharedHeadShipsTheLightboxAssetsOnce() throws Exception {
        switchTheme("fauxcoly");
        assertAssetsExactlyOnce(render("/" + HANDLE), "fauxcoly std_head.vm");
    }

    // ------------------------------------------------- gaurav/fauxcoly permalinks

    /**
     * The shared-head themes' permalink templates pull the same fragment in
     * through {@code #includeTemplate}; prove the include path does not emit
     * the assets a second time on the page type T4 actually targets.
     */
    @Test
    void gauravPermalinkDoesNotDoubleEmitTheAssets() throws Exception {
        switchTheme("gaurav");
        TestUtils.setupWeblogEntry("gaurav-asset-entry", weblog, user);
        TestUtils.endSession(true);
        assertAssetsExactlyOnce(render("/" + HANDLE + "/entry/gaurav-asset-entry"),
                "gaurav permalink");
    }

    @Test
    void fauxcolyPermalinkDoesNotDoubleEmitTheAssets() throws Exception {
        switchTheme("fauxcoly");
        TestUtils.setupWeblogEntry("fauxcoly-asset-entry", weblog, user);
        TestUtils.endSession(true);
        assertAssetsExactlyOnce(render("/" + HANDLE + "/entry/fauxcoly-asset-entry"),
                "fauxcoly permalink");
    }

    /**
     * The tradeoff the macro documents: assets are emitted from the head even
     * on gallery-less pages (one cacheable stylesheet request), but the module
     * script must stop at the guard -- the page carries no {@code .jgrid}, so
     * the guard is what keeps both ESM downloads from ever happening.
     */
    @Test
    void aGalleryLessPageStillCarriesTheGuardButNoGrid() throws Exception {
        String body = render("/" + HANDLE);
        assertTrue(body.contains(JGRID_GUARD), body);
        assertFalse(body.contains("<div class=\"jgrid\""),
                "no gallery markup may appear without a [gallery] entry:\n" + body);
    }
}
