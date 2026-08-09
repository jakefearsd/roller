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
 * Renders the journal theme's home page through the real PageServlet: a
 * reading-first entry list with date marginalia (the qj-date cell), serif
 * titles, and the same head-chain contract every other bundled theme carries.
 *
 * <p>Task 1 skeleton only -- the permalink action still points at weblog.vm
 * (see {@code theme.xml}), so this class does not yet assert anything about a
 * dedicated reading view; that lands in Task 2.
 */
class JournalThemeRenderingTest {

    private static final String HANDLE = "journalrenderblog";
    private static final String BASE = "/roller/" + HANDLE;

    /**
     * Journal self-hosts Plex Serif/Sans/Mono via webjar, unlike travel and
     * portfolio (system fonts only, no font-src). CSP_STANDARD's
     * default-src 'none' blocks any directive it does not name, so a webfont
     * theme needs its own font-src -- the same shape gaurav's CSP already
     * carries (CSP_GAURAV in AnalyticsInjectionRenderingTest), added at the
     * same position. ThemeCspCoverageTest#everyFontAThemeAsksForIsShippedAndAllowedByItsPolicy
     * enforces this for every theme's CSS, not just this one.
     */
    private static final String CSP_JOURNAL =
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
                    + "script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                    + "img-src * data:; frame-src https://www.youtube-nocookie.com "
                    + "https://player.vimeo.com; font-src 'self'; base-uri 'self'; "
                    + "connect-src 'self'; form-action 'self'; frame-ancestors 'none'\">";

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("journalrenderuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        switchTheme("journal");
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

    private WeblogEntry entryWithSummary(String anchor, String summary) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, weblog, user);
        WeblogEntryManager mgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setSearchDescription(summary);
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
        return entry;
    }

    /** The shared head contract plus the no-Velocity-leak assertions. */
    private static void assertJournalHead(String body) {
        assertTrue(body.contains(CSP_JOURNAL),
                "the journal head must carry the CSP_STANDARD directives plus font-src "
                        + "'self' for its self-hosted webfonts:\n" + body);
        assertTrue(body.contains("<link rel=\"canonical\""),
                "#showSeoHead must contribute the canonical link:\n" + body);
        assertTrue(body.contains(".jgrid { display: flex;"),
                "#showGalleryGridStyles must be in the head:\n" + body);
        assertTrue(body.contains("/webjars/photoswipe/"),
                "#showGalleryAssets must ship the lightbox:\n" + body);
        assertTrue(body.contains(".video-embed"),
                "#showEmbedAssets must be in the head:\n" + body);
        assertTrue(body.contains("audience-hp"),
                "#showAudienceAssets must be in the head:\n" + body);
        assertTrue(body.contains("/webjars/leaflet/"),
                "#showMapAssets must ship Leaflet:\n" + body);
        assertTrue(body.contains("journal-custom.css"),
                "the head must link the theme stylesheet:\n" + body);
        // a Velocity error would leak the raw directive or reference text
        assertFalse(body.contains("#showResponsiveImage"), body);
        assertFalse(body.contains("#showGalleryAssets"), body);
        assertFalse(body.contains("#showMapAssets"), body);
        assertFalse(body.contains("#showSeoHead"), body);
        assertFalse(body.contains("#showEmbedAssets"), body);
        assertFalse(body.contains("#showAudienceAssets"), body);
        assertFalse(body.contains("$entry.featuredImage"), body);
        assertFalse(body.contains("$utils."), body);
    }

    // ------------------------------------------------------------ discovery

    @Test
    void theThemeManagerListsTheJournalTheme() throws Exception {
        SharedTheme journal = WebloggerFactory.getWeblogger().getThemeManager()
                .getEnabledThemesList().stream()
                .filter(theme -> "journal".equals(theme.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "journal theme missing from the enabled themes list"));
        assertTrue(journal.getStylesheet() != null,
                "the theme must declare a stylesheet so per-weblog overrides work");
    }

    // ------------------------------------------------------------ front page

    @Test
    void theFrontPageListsEntriesWithDateMarginalia() throws Exception {
        entryWithSummary("field-notes-from-the-coast",
                "Three mornings of fog, one of clear light.");

        String body = render("/" + HANDLE + "/");

        assertJournalHead(body);
        assertTrue(body.contains("class=\"qj-entry\""),
                "each entry must render as a qj-entry row:\n" + body);
        assertTrue(body.contains("class=\"qj-date\""),
                "the date marginalia cell must be present:\n" + body);
        assertTrue(body.contains("class=\"qj-title\""),
                "the serif entry title must be present:\n" + body);
        assertTrue(body.contains("Three mornings of fog, one of clear light."),
                "the entry's search description must render as the summary:\n" + body);
        assertTrue(body.contains(BASE + "/entry/field-notes-from-the-coast"),
                "the title must link to the entry's permalink:\n" + body);
    }

    @Test
    void theNavPageLinksRenderInsideAnOpenList() throws Exception {
        String body = render("/" + HANDLE + "/");

        assertJournalHead(body);
        // #showPageLinks emits bare <li> items -- the theme must supply the
        // enclosing <ul> itself (PageNavRenderingTest pins this shape for the
        // other five bundled themes).
        assertTrue(body.contains("<nav class=\"qj-nav\">"),
                "the nav block must be present:\n" + body);
        assertFalse(body.contains("#showPageLinks"),
                "a Velocity error resolving the macro would leak the raw directive:\n" + body);
    }
}
