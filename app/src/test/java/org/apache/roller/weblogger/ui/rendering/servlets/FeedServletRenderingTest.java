package org.apache.roller.weblogger.ui.rendering.servlets;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedServletRenderingTest {

    private User user;
    private Weblog weblog;
    private String handle;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        // unique handle per test method: FeedServlet has no cache bypass
        handle = "feedblog" + System.nanoTime();
        user = TestUtils.setupUser("feeduser");
        weblog = TestUtils.setupWeblog(handle, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private MockHttpServletResponse feed(String typeAndFormat) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/feed", "/" + handle + "/" + typeAndFormat);
        return RenderingTestSupport.execute(RenderingTestSupport.feedServlet(), request);
    }

    /**
     * W2: feeds are Atom only. {@code entries/rss} used to be a live format;
     * the template that rendered it ({@code weblog-entries-rss.vm}) is gone,
     * so the same request must now 404 rather than pretend to still work.
     */
    @Test
    void rssEntriesFeedIsGone() throws Exception {
        TestUtils.setupWeblogEntry("rss-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletResponse response = feed("entries/rss");

        assertEquals(404, response.getStatus());
    }

    @Test
    void atomEntriesFeedContainsPublishedEntry() throws Exception {
        TestUtils.setupWeblogEntry("atom-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletResponse response = feed("entries/atom");

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("<feed"), "must be an Atom document:\n" + body);
        assertTrue(body.contains("atom-entry"), "entry must appear in the feed:\n" + body);
        // W2: RSS is gone but styled feeds (site.newsfeeds.styledFeeds,
        // default on) must still work for the surviving Atom feeds -- the
        // stylesheet PI is what turns a bare XML download into a styled page
        // when a browser requests it directly.
        assertTrue(body.contains("<?xml-stylesheet type=\"text/xsl\" "
                        + "href=\"http://localhost:8080/roller/roller-ui/styles/atom.xsl\" media=\"screen\"?>"),
                "a styled feed must carry the atom.xsl stylesheet PI:\n" + body);
    }

    /**
     * {@code UserWrapper#getScreenName} sanitizes tags but does not
     * entity-escape ({@code HTMLSanitizer.conditionallySanitizeText}), so a
     * screen name reaches {@code feeds.vm} carrying live {@code &} and
     * {@code <} characters. {@code <author><name>} is an Atom text
     * construct, not {@code type="html"} like the title beside it: a bare
     * {@code &} there is not "unescaped display text", it is a malformed
     * XML document that a strict feed reader refuses outright. Every other
     * value in the macro already went through {@code $utils.escapeXML};
     * the author name was the one that did not.
     */
    @Test
    void theAtomAuthorNameIsXmlEscaped() throws Exception {
        User managed = WebloggerFactory.getWeblogger().getUserManager()
                .getUserByUserName(user.getUserName());
        managed.setScreenName("Ampersand & Sons");
        WebloggerFactory.getWeblogger().getUserManager().saveUser(managed);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.setupWeblogEntry("author-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletResponse response = feed("entries/atom");

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("<name>Ampersand &amp; Sons</name>"),
                "the author name must be XML-escaped inside the Atom text "
                        + "construct:\n" + body);
    }

    /**
     * A trashed entry must not be syndicated -- same invariant as
     * {@code draftEntryPermalinkIsNotFound}/{@code frontPageExcludesTrashedEntry}
     * in {@code PageServletRenderingTest}, for the Atom feed. A live entry
     * alongside it is what stops "the trashed entry is absent" from being
     * satisfied by an empty feed.
     */
    @Test
    void atomEntriesFeedExcludesTrashedEntry() throws Exception {
        TestUtils.setupWeblogEntry("atom-live-entry", weblog, user);
        TestUtils.setupWeblogEntry("atom-gone-entry", weblog, user, PubStatus.TRASHED);
        TestUtils.endSession(true);

        MockHttpServletResponse response = feed("entries/atom");

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("atom-live-entry"),
                "the published entry must be syndicated:\n" + body);
        assertFalse(body.contains("atom-gone-entry"),
                "a trashed entry must not be syndicated in atom:\n" + body);
    }

    /**
     * W2: search feeds are gone ({@code weblog-search-atom.vm} and
     * {@code SearchResultsFeedModel} are deleted). A {@code q} parameter on
     * a feed request must 404, not silently fall through and serve the
     * unfiltered entries feed under the same URL a search reader bookmarked.
     */
    @Test
    void searchTermOnAFeedRequestIsNotFound() throws Exception {
        TestUtils.setupWeblogEntry("atom-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/feed", "/" + handle + "/entries/atom");
        request.setParameter("q", "atom");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.feedServlet(), request);

        assertEquals(404, response.getStatus());
    }

    /**
     * W2: multi-locale weblogs are gone, but the URL shape
     * {@code /<handle>/<locale>/...} the old per-locale feeds used is still
     * parsed by WeblogFeedRequest -- {@code getLocale()} can come back
     * non-null even though there is no per-locale content behind it anymore.
     * Without this check the request would fall through and quietly serve
     * the ordinary unfiltered feed under a URL that looks locale-scoped,
     * instead of 404ing on a feed shape that no longer exists.
     */
    @Test
    void aFeedRequestNamingALocaleIsNotFound() throws Exception {
        TestUtils.setupWeblogEntry("atom-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/feed", "/" + handle + "/en_US/entries/atom");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.feedServlet(), request);

        assertEquals(404, response.getStatus());
    }

    @Test
    void unknownCategoryFeedIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/feed", "/" + handle + "/entries/rss");
        request.setParameter("cat", "Nope");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.feedServlet(), request);

        assertEquals(404, response.getStatus());
    }

    @Test
    void unknownWeblogFeedIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/feed", "/nosuchblog/entries/rss");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.feedServlet(), request);

        assertEquals(404, response.getStatus());
    }
}
