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

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.PropertiesManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
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
 * Renders the restyled frontpage ("front door") aggregator theme through the
 * real PageServlet. This theme renders only for the one weblog named by the
 * runtime property {@code site.frontpage.weblog.handle}, with {@code
 * site.frontpage.weblog.aggregated} also on (see
 * {@code WebloggerRuntimeConfig#isSiteWideWeblog}) -- that pair is what adds
 * {@code $site} (SiteModel) to the Velocity context (PageServlet, {@code
 * rendering.siteModels}), which is why ThemeMatrixIT excludes this theme:
 * every other bundled theme renders for whichever weblog the fixture hands
 * it, and this one only renders correctly for the configured site-wide
 * weblog.
 *
 * <p>The restyle keeps every {@code $site}/{@code $model} call the previous
 * markup made (site-wide entries pager, pinned entries, new-weblogs list,
 * weblog-by-handle lookup) -- only the chrome/classes/CSS changed, to the
 * fd-* vocabulary in docs/design/journal/frontpage-front-door.html.
 */
class FrontpageRenderingTest {

    private static final String SITE_HANDLE = "frontpagesiteblog";
    private static final String CONTRIBUTOR_HANDLE = "frontpagecontributorblog";
    private static final String BASE = "/roller/" + SITE_HANDLE;

    private static final String FRONTPAGE_HANDLE_PROP = "site.frontpage.weblog.handle";
    private static final String FRONTPAGE_AGGREGATED_PROP = "site.frontpage.weblog.aggregated";
    private static final String SITE_NAME_PROP = "site.name";
    private static final String SITE_DESCRIPTION_PROP = "site.description";

    /**
     * The fd-hero renders $config.siteName/$config.siteDescription -- the
     * whole-installation Admin Settings fields, not the front weblog's own
     * name/tagline -- exactly as the pre-restyle template did (ConfigModel
     * reads the "site.name"/"site.description" runtime properties). Unlike
     * Weblog.setName/setTagline (which strip markup with Utilities.removeHTML
     * at the setter, before any template ever sees the value), these are
     * plain strings with no sanitizer upstream, so they are the one field in
     * this theme where the template's own $utils.escapeHTML call is the ONLY
     * thing standing between an admin-typed "<script>" and the rendered
     * page -- the deliberately dangerous value in the escaping test below.
     */
    private static final String DANGEROUS_SITE_NAME = "Coastal Guides <script>alert(1)</script>";
    private static final String DANGEROUS_SITE_DESCRIPTION = "Notes & guides for the coast";

    private static final String ANALYTICS_SITE_ID = "11111111-2222-3333-4444-555555555555";

    /**
     * What {@code #showAnalyticsTrackingCode} builds when analyticsSiteId is
     * set -- same shape {@code AnalyticsInjectionRenderingTest} pins for the
     * other three bundled themes' home pages. Frontpage's head never called
     * the macro at all (pre-existing gap: front-door traffic was invisible to
     * Umami), so this class is the only coverage for it.
     */
    private static final String EXPECTED_ANALYTICS_SCRIPT_TAG =
            "<script defer src=\"/analytics/script.js\" "
                    + "data-website-id=\"" + ANALYTICS_SITE_ID + "\" "
                    + "data-host-url=\"/analytics\"></script>";

    /**
     * Frontpage self-hosts Plex Serif/Sans/Mono via webjar (fd-site/fd-post
     * .t/fd-blog .n need the serif, fd-post .d/fd-blog .h need the mono),
     * unlike travel/portfolio (system fonts only, no font-src). The original
     * policy named no font-src at all -- default-src 'none' refuses
     * anything a policy does not name, so the restyle inserts font-src
     * 'self' at the same relative position journal's CSP does among its own
     * directives (ThemeCspCoverageTest
     * #everyFontAThemeAsksForIsShippedAndAllowedByItsPolicy enforces the
     * pairing for every theme, not just this one). Frontpage never called
     * #showEmbedAssets, so unlike journal there is no frame-src to carry.
     */
    private static final String CSP_FRONTPAGE =
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
                    + "script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                    + "img-src * data:; font-src 'self'; base-uri 'self'; connect-src 'self'; "
                    + "form-action 'self'; frame-ancestors 'none'\">";

    private User siteUser;
    private Weblog siteWeblog;
    private User contributorUser;
    private Weblog contributorWeblog;

    private final Map<String, String> originalProperties = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();

        siteUser = TestUtils.setupUser("frontpagesiteuser");
        siteWeblog = TestUtils.setupWeblog(SITE_HANDLE, siteUser);
        Weblog managedSite = TestUtils.getManagedWebsite(siteWeblog);
        managedSite.setEditorTheme("frontpage");
        WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(managedSite);
        TestUtils.endSession(true);

        contributorUser = TestUtils.setupUser("frontpagecontribuser");
        contributorWeblog = TestUtils.setupWeblog(CONTRIBUTOR_HANDLE, contributorUser);
        Weblog managedContributor = TestUtils.getManagedWebsite(contributorWeblog);
        // Weblog.setName/setTagline already strip markup (Utilities.removeHTML)
        // before this ever reaches a template, so a bare "&" -- untouched by
        // that stripper -- is what proves THIS template's own
        // $utils.escapeHTML($blog.name)/$utils.escapeHTML($blog.tagline)
        // calls are doing real work, in the "from <weblog>" credit and the
        // fd-blog directory card.
        managedContributor.setName("Harbor & Cove Notes");
        managedContributor.setTagline("Guides & notes for the coast");
        WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(managedContributor);
        TestUtils.endSession(true);

        setProperty(FRONTPAGE_HANDLE_PROP, SITE_HANDLE);
        setProperty(FRONTPAGE_AGGREGATED_PROP, "true");
    }

    @AfterEach
    void tearDown() throws Exception {
        PropertiesManager pmgr = WebloggerFactory.getWeblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        originalProperties.forEach((name, value) -> config.get(name).setValue(value));
        pmgr.saveProperties(config);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        TestUtils.teardownWeblog(contributorWeblog.getId());
        TestUtils.teardownUser(contributorUser.getUserName());
        TestUtils.teardownWeblog(siteWeblog.getId());
        TestUtils.teardownUser(siteUser.getUserName());
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

    private WeblogEntry contributorEntry(String anchor) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, contributorWeblog, contributorUser);
        TestUtils.endSession(true);
        return entry;
    }

    /**
     * Mirrors AnalyticsInjectionRenderingTest's pattern for driving a
     * runtime property from a real (non-mocked) rendering test: read the
     * live map through PropertiesManager, mutate, save, flush, end the
     * session so the next request reads it fresh. The property's original
     * value is captured the first time it is touched, so tearDown can put
     * every property this test changed back exactly as it found it.
     */
    private void setProperty(String name, String value) throws Exception {
        PropertiesManager pmgr = WebloggerFactory.getWeblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        originalProperties.computeIfAbsent(name, key -> config.get(key).getValue());
        config.get(name).setValue(value);
        pmgr.saveProperties(config);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);
    }

    private static void assertFrontpageHead(String body) {
        assertTrue(body.contains(CSP_FRONTPAGE),
                "the frontpage head must carry the CSP_STANDARD directives plus font-src "
                        + "'self' for its self-hosted webfonts:\n" + body);
        assertTrue(body.contains("<link rel=\"canonical\""),
                "#showSeoHead must contribute the canonical link:\n" + body);
        assertTrue(body.contains("frontpage-custom.css"),
                "the head must link the theme stylesheet:\n" + body);
        // a Velocity error would leak the raw directive or reference text
        assertFalse(body.contains("#showSeoHead"), body);
        assertFalse(body.contains("#showGalleryAssets"), body);
        assertFalse(body.contains("#showMapAssets"), body);
        assertFalse(body.contains("$config.siteName"), body);
        assertFalse(body.contains("$utils."), body);
    }

    // ------------------------------------------------------------ front door

    @Test
    void theFrontDoorShowsTheHeroLatestPostsAndDirectory() throws Exception {
        contributorEntry("field-notes-from-the-coast");

        String body = render("/" + SITE_HANDLE + "/");

        assertFrontpageHead(body);

        assertTrue(body.contains("class=\"fd-hero\""),
                "the site hero (name + tagline) must be present:\n" + body);
        assertTrue(body.contains("class=\"fd-site\""), body);
        assertTrue(body.contains("class=\"fd-tag\""), body);

        assertTrue(body.contains("class=\"fd-post\""),
                "each site-wide entry must render as an fd-post row:\n" + body);
        assertTrue(body.contains("field-notes-from-the-coast"),
                "the contributed entry's anchor/title must render:\n" + body);
        assertTrue(body.contains("from <a"),
                "the fd-post row must carry a \"from <weblog>\" credit:\n" + body);

        assertTrue(body.contains("class=\"fd-dir\""),
                "the weblog directory grid must be present:\n" + body);
        assertTrue(body.contains("class=\"fd-blog\""),
                "each directory entry must render as an fd-blog card:\n" + body);
        assertTrue(body.contains("class=\"live\""),
                "each fd-blog card must carry the live-status dot:\n" + body);
        assertTrue(body.contains(CONTRIBUTOR_HANDLE),
                "the contributing weblog's handle must appear in the directory:\n" + body);
    }

    @Test
    void theHomePageLinksToTheWeblogDirectoryPage() throws Exception {
        String body = render("/" + SITE_HANDLE + "/");

        assertFrontpageHead(body);
        assertTrue(body.contains("class=\"fd-tabs\""),
                "the tab nav must be present:\n" + body);
        assertTrue(body.contains(BASE + "/page/directory"),
                "the directory tab must link to the directory page:\n" + body);
    }

    @Test
    void theWeblogDirectoryPageRendersTheFullDirectory() throws Exception {
        String body = render("/" + SITE_HANDLE + "/page/directory");

        assertFrontpageHead(body);
        assertTrue(body.contains("class=\"fd-dir\""),
                "the full weblog directory grid must be present:\n" + body);
        assertTrue(body.contains("class=\"fd-blog\""), body);
        assertTrue(body.contains(CONTRIBUTOR_HANDLE),
                "the contributing weblog must be listed in the full directory:\n" + body);
        assertTrue(body.contains("class=\"fd-letters\""),
                "the A-Z letter index must be present:\n" + body);
    }

    // -------------------------------------------------------------- escaping

    @Test
    void theSiteNameAndDescriptionAreEscapedNotLiveHtml() throws Exception {
        // $config.siteName/$config.siteDescription have no upstream
        // sanitizer (unlike Weblog.name/tagline -- see the class comment),
        // so this is the field where the restyled template's own
        // $utils.escapeHTML($config.siteName) call is the only thing
        // standing between an admin-typed <script> and the rendered page.
        setProperty(SITE_NAME_PROP, DANGEROUS_SITE_NAME);
        setProperty(SITE_DESCRIPTION_PROP, DANGEROUS_SITE_DESCRIPTION);

        String body = render("/" + SITE_HANDLE + "/");

        assertFalse(body.contains("<script>alert(1)</script>"),
                "a site name carrying a live <script> tag must never reach the page "
                        + "unescaped:\n" + body);
        assertTrue(body.contains("Coastal Guides &lt;script&gt;alert(1)&lt;/script&gt;"),
                "the site name must render HTML-escaped inside the fd-hero:\n" + body);
        assertTrue(body.contains("Notes &amp; guides for the coast"),
                "the site description must render HTML-escaped inside the fd-hero:\n" + body);
    }

    @Test
    void contributingWeblogNamesAndTaglinesAreEscaped() throws Exception {
        contributorEntry("harbor-cottage-guide");

        String body = render("/" + SITE_HANDLE + "/");

        // Weblog.setName/setTagline already strip markup via
        // Utilities.removeHTML before a template ever sees the value, so a
        // bare "&" is the one character that survives to be observable here.
        // The name assertion below exercises WeblogWrapper#getName's own
        // built-in StringEscapeUtils.escapeHtml4 -- the template renders
        // $blog.name/$entry.website.name bare, deliberately NOT wrapped in
        // another $utils.escapeHTML (that would double-encode, see weblog.vm's
        // fd-dir comment). $blog.tagline is NOT pre-escaped by its wrapper
        // (WeblogWrapper#getTagline only sanitizes/strips markup), so the
        // tagline assertion is the one that actually proves THIS template's
        // own $utils.escapeHTML($blog.tagline) call is doing real work.
        assertFalse(body.contains("Harbor & Cove Notes"),
                "the fd-post credit / fd-blog card must not carry a raw ampersand:\n" + body);
        assertTrue(body.contains("Harbor &amp; Cove Notes"),
                "the contributing weblog's name must render HTML-escaped "
                        + "(WeblogWrapper#getName's own escaping):\n" + body);
        assertTrue(body.contains("Guides &amp; notes for the coast"),
                "the contributing weblog's tagline must render HTML-escaped by this "
                        + "template's own $utils.escapeHTML call:\n" + body);
    }

    // ------------------------------------------------------------------- CSP

    @Test
    void theCspIsSentOnEveryFrontpagePage() throws Exception {
        assertTrue(render("/" + SITE_HANDLE + "/").contains(CSP_FRONTPAGE),
                "the home page must carry the CSP with font-src for self-hosted webfonts");
        assertTrue(render("/" + SITE_HANDLE + "/page/directory").contains(CSP_FRONTPAGE),
                "the directory page must carry the same CSP as the home page");
    }

    // ------------------------------------------------------------ analytics

    @Test
    void theFrontDoorInjectsTheUmamiScriptTagWhenASiteIdIsSet() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(siteWeblog);
        managed.setAnalyticsSiteId(ANALYTICS_SITE_ID);
        WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);

        String body = render("/" + SITE_HANDLE + "/");

        assertTrue(body.contains(EXPECTED_ANALYTICS_SCRIPT_TAG),
                "the front page must inject the Umami script tag for the front "
                        + "weblog's analyticsSiteId:\n" + body);
        assertTrue(body.contains(CSP_FRONTPAGE),
                "wiring analytics into the head must not touch the CSP:\n" + body);
    }

    @Test
    void theFrontDoorInjectsNoAnalyticsScriptWithoutASiteId() throws Exception {
        String body = render("/" + SITE_HANDLE + "/");

        assertFalse(body.contains("/analytics/"),
                "no site id means no Umami script must be injected:\n" + body);
    }
}
