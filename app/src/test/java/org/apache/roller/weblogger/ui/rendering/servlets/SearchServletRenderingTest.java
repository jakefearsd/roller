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

class SearchServletRenderingTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("searchuser");
        weblog = TestUtils.setupWeblog("searchblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void searchPageRendersWithNoResults() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/search", "/searchblog");
        request.setParameter("q", "zzznope");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("text/html"));
        String body = response.getContentAsString();
        assertTrue(body.contains("Entries found for"),
                "search results template must render the search-again form's "
                        + "hit count (macro.weblog.searchhits):\n" + body);
        assertTrue(body.contains("Test Weblog"),
                "weblog name must appear on the search page:\n" + body);
    }

    @Test
    void unknownWeblogSearchIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/search", "/nosuchblog");
        request.setParameter("q", "anything");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);

        // A missing weblog is a 404, matching every sibling rendering
        // servlet (WebContainerConfig renders the 404 page body for this
        // status; a 400 here used to disagree with that body).
        assertEquals(404, response.getStatus());
    }
}
