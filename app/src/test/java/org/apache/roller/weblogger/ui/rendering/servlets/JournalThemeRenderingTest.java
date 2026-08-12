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

import org.apache.commons.text.StringEscapeUtils;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.themes.SharedTheme;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the journal theme's home page and reading view through the real
 * PageServlet/SearchServlet: a reading-first entry list with date marginalia
 * (the qj-date cell), serif titles, and the same head-chain contract every
 * other bundled theme carries.
 *
 * <p>Task 2 adds the dedicated permalink reading view (permalink.vm, the
 * qj-h1/qj-byline/qj-prose treatment per docs/design/journal/journal-permalink.html)
 * and the search results page (searchresults.vm) -- both now declared in
 * theme.xml instead of falling back to weblog.vm / the default template.
 */
class JournalThemeRenderingTest {

    private static final String HANDLE = "journalrenderblog";
    private static final String BASE = "/roller/" + HANDLE;

    /**
     * Journal self-hosts Plex Serif/Sans/Mono via webjar, unlike travel and
     * portfolio (system fonts only, no font-src). CSP_STANDARD's
     * default-src 'none' blocks any directive it does not name, so a webfont
     * theme needs its own font-src (CSP_JOURNAL in
     * AnalyticsInjectionRenderingTest pins the same string), added at the
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
        // A broken $entry.xyz reference (e.g. a method the manager no longer
        // has) prints as this literal text in Velocity's lenient mode rather
        // than failing the render -- see _day.vm's now-fixed
        // "$entry.commentCount comments". This subsumes the old
        // $entry.featuredImage-only check.
        assertFalse(body.contains("$entry."), body);
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

    // ------------------------------------------------------------- permalink

    @Test
    void thePermalinkRendersTheReadingView() throws Exception {
        entryWithSummary("field-notes-from-the-coast",
                "Three mornings of fog, one of clear light.");

        String body = render("/" + HANDLE + "/entry/field-notes-from-the-coast");

        assertJournalHead(body);
        assertTrue(body.contains(CSP_JOURNAL),
                "the permalink template must carry the same CSP as every other "
                        + "journal template:\n" + body);
        // qj-crumb/qj-h1/qj-byline/qj-prose all come from _day.vm's
        // $model.permalink branch, which fires the same way regardless of
        // which theme.xml template action renders it -- they do NOT pin the
        // permalink.vm repoint itself. <main class="qj-main qj-main-entry">
        // does: only permalink.vm emits that combined class (weblog.vm's
        // <main> carries just "qj-main"), so if theme.xml's permalink action
        // ever regresses back to weblog.vm this assertion catches it even
        // though the _day.vm markup below would still render identically.
        assertTrue(body.contains("<main class=\"qj-main qj-main-entry\">"),
                "the permalink must render through permalink.vm's own main "
                        + "shell, not weblog.vm's:\n" + body);
        assertTrue(body.contains("class=\"qj-crumb\""),
                "the weblog/category crumb must be present:\n" + body);
        assertTrue(body.contains("<h1 class=\"qj-h1\">"),
                "the entry title must render as the serif qj-h1:\n" + body);
        assertTrue(body.contains("class=\"qj-byline\""),
                "the byline (author + mono date) must be present:\n" + body);
        assertTrue(body.contains("class=\"qj-prose\""),
                "the entry content must render inside the qj-prose reading column:\n" + body);
        assertTrue(body.contains("field-notes-from-the-coast"),
                "the entry's own anchor/title must render:\n" + body);
        // TestUtils.setupWeblogEntry (called by entryWithSummary) always sets
        // the entry body text to "blah blah entry" -- assert that literal
        // text, not just the title/anchor/canonical, so this proves the
        // rendered body actually made it into the page (PortfolioThemeRenderingTest
        // pins the same literal for the same reason).
        assertTrue(body.contains("blah blah entry"),
                "the entry's rendered body content must appear inside qj-prose:\n" + body);
        assertFalse(body.contains("class=\"qj-entry\""),
                "the permalink must not fall back to the entry-list row markup:\n" + body);
        assertFalse(body.contains("qj-comments"),
                "the comments section must be gone from the permalink");
        assertFalse(body.contains("commentForm"),
                "no comment form may survive in rendered output");
    }

    // ---------------------------------------------------------------- search

    @Test
    void theSearchPageRendersThroughTheJournalTheme() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/search", "/" + HANDLE);
        request.setParameter("q", "zzznope");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();

        // Not assertJournalHead here: search pages get no rel=canonical (see
        // #showSeoHead's own comment -- there is no one canonical URL for a
        // query), so the shared head assertion (which requires it) does not
        // apply to this template.
        assertTrue(body.contains(CSP_JOURNAL),
                "the search results head must carry the same CSP as every "
                        + "other journal template:\n" + body);
        assertTrue(body.contains(".jgrid { display: flex;"),
                "#showGalleryGridStyles must be in the head:\n" + body);
        assertTrue(body.contains("journal-custom.css"),
                "the head must link the theme stylesheet:\n" + body);
        assertTrue(body.contains("id=\"searchAgain\""),
                "the search-again form must render:\n" + body);
        assertTrue(body.contains("class=\"qj-search-head\""),
                "the search head wrapper must be present:\n" + body);
        assertFalse(body.contains("$utils."), body);
    }

    // -------------------------------------------------------------- _page

    private void savePage(String slug, String title, String content, WeblogPage.PubStatus status)
            throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug(slug);
        page.setTitle(title);
        page.setContent(content);
        page.setStatus(status);
        WebloggerFactory.getWeblogger().getWeblogPageManager().savePage(page);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);
    }

    @Test
    void aPageRendersThroughTheJournalThemeInsteadOfTheFallback() throws Exception {
        savePage("about", "About This Journal", "Some prose about the journal. [contact]",
                WeblogPage.PubStatus.PUBLISHED);

        String body = render("/" + HANDLE + "/about");

        assertJournalHead(body);
        assertTrue(body.contains("class=\"qj-head\""),
                "the page must wear the journal header chrome:\n" + body);
        assertTrue(body.contains("<h1 class=\"qj-h1\">About This Journal</h1>"),
                "the page title must render as the serif qj-h1:\n" + body);
        assertTrue(body.contains("class=\"qj-prose\""),
                "the page content must render inside the qj-prose reading column:\n" + body);
        assertTrue(body.contains("audience-hp"),
                "showAudienceAssets must run in the head, whatever the page contains -- "
                        + "this alone does not prove the [contact] shortcode rendered:\n" + body);
        assertTrue(body.contains("contact-form-slot"),
                "the [contact] shortcode must have expanded to its placeholder div in the "
                        + "rendered body -- this is what actually proves the form renders:\n" + body);
        assertFalse(body.contains("<h1>About This Journal</h1>"),
                "the naked fallback template's unstyled h1 must not be what renders:\n" + body);
    }

    // ----------------------------------------------------- escaping (once)

    /**
     * Pins the fix end-to-end for the two independent single-escape
     * contracts a journal page's head/permalink carries: {@code
     * $model.weblog.name} comes back from {@code WeblogWrapper#getName}
     * already {@code escapeHtml4}'d, and an entry title is stored
     * pre-escaped by {@code EntryBean.copyTo} (mirrored here rather than
     * driving the controller, per {@code EntryBeanTest
     * #copyToEscapesTheTitleButNotTheBody}). Templates must emit both bare;
     * calling {@code $utils.escapeHTML} on either double-encodes. An '&'
     * in both fixtures makes a double-escape ({@code &amp;amp;}) impossible
     * to miss.
     */
    @Test
    void theWeblogNameAndEntryTitleEscapeExactlyOnce() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setName("Fog & Light Journal");
        WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);

        WeblogEntry entry = TestUtils.setupWeblogEntry("tides-and-time", weblog, user);
        WeblogEntryManager mgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
        WeblogEntry managedEntry = mgr.getWeblogEntry(entry.getId());
        // Stored form of "Tides & Time" -- the transformation EntryBean.copyTo
        // applies at save time (see EntryBeanTest:212), not raw author input.
        managedEntry.setTitle(StringEscapeUtils.escapeHtml4("Tides & Time"));
        mgr.saveWeblogEntry(managedEntry);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/entry/tides-and-time");

        assertTrue(body.contains("Fog &amp; Light Journal"),
                "the weblog name must render escaped exactly once:\n" + body);
        assertTrue(body.contains("Tides &amp; Time"),
                "the entry title must render escaped exactly once:\n" + body);
        assertFalse(body.contains("&amp;amp;"),
                "no reference may double-encode a value that is already "
                        + "stored/wrapped escaped:\n" + body);
    }

    @Test
    void aDraftPageStill404sUnderTheJournalTheme() throws Exception {
        savePage("still-drafting", "Not Yet", "Nothing to see.", WeblogPage.PubStatus.DRAFT);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/" + HANDLE + "/still-drafting");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(404, response.getStatus(),
                "a draft page must not be reachable through the journal theme's _page "
                        + "template either -- draft status is checked before any template "
                        + "renders:\n" + response.getContentAsString());
    }
}
