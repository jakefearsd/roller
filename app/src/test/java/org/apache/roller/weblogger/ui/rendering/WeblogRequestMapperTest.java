package org.apache.roller.weblogger.ui.rendering;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The public-URL front door: /roller/<handle>/... → rendering servlet forwards. */
class WeblogRequestMapperTest {

    private User user;
    private Weblog weblog;
    private WeblogRequestMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("mapperuser");
        weblog = TestUtils.setupWeblog("mapperblog", user);
        TestUtils.endSession(true);
        mapper = new WeblogRequestMapper();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private MockHttpServletRequest publicUrl(String method, String uriAfterContext) {
        MockHttpServletRequest request =
                new MockHttpServletRequest(method, "/roller" + uriAfterContext);
        request.setContextPath("/roller");
        return request;
    }

    @Test
    void weblogHomeForwardsToPageServlet() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean handled = mapper.handleRequest(request, response);

        assertTrue(handled);
        assertEquals("/roller-ui/rendering/page/mapperblog", response.getForwardedUrl());
    }

    @Test
    void permalinkForwardsToPageServlet() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/entry/my-post");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/page/mapperblog/entry/my-post",
                response.getForwardedUrl());
    }

    @Test
    void feedUrlForwardsToFeedServlet() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/feed/entries/rss");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/feed/mapperblog/entries/rss",
                response.getForwardedUrl());
    }

    @Test
    void searchUrlForwardsToSearchServlet() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/search");
        request.setParameter("q", "term");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/search/mapperblog", response.getForwardedUrl());
    }

    @Test
    void commentPostForwardsToCommentServlet() throws Exception {
        MockHttpServletRequest request = publicUrl("POST", "/mapperblog/entry/my-post");
        request.setParameter("content", "a comment body");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/comment/mapperblog/entry/my-post",
                response.getForwardedUrl());
    }

    @Test
    void missingTrailingSlashRedirects() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertNotNull(response.getRedirectedUrl(), "must redirect to the canonical slash form");
        assertTrue(response.getRedirectedUrl().endsWith("/mapperblog/"),
                "redirect target was: " + response.getRedirectedUrl());
    }

    @Test
    void unknownHandleIsNotHandled() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/nosuchblog/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(request, response),
                "an unknown handle must fall through to the next filter");
        assertNull(response.getForwardedUrl());
    }
}
