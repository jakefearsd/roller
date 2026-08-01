package org.apache.roller.weblogger.ui.rendering.servlets;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void rssEntriesFeedContainsPublishedEntry() throws Exception {
        TestUtils.setupWeblogEntry("rss-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletResponse response = feed("entries/rss");

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().contains("xml"),
                "feed content type must be xml but was: " + response.getContentType());
        String body = response.getContentAsString();
        assertTrue(body.contains("<rss"), "must be an RSS document:\n" + body);
        assertTrue(body.contains("rss-entry"), "entry must appear in the feed:\n" + body);
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
    }

    @Test
    void commentsFeedRenders() throws Exception {
        TestUtils.setupWeblogEntry("commented-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletResponse response = feed("comments/rss");

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("<rss"));
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
