package org.apache.roller.weblogger.ui.rendering;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.apache.roller.weblogger.ui.rendering.servlets.RenderingTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bare path segment resolves to a page. Before this, /handle/about threw
 * InvalidRequestException("invalid index page") -- a single element was only
 * ever legal for /tags.
 */
class PageRoutingTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();

        user = TestUtils.setupUser("routeuser");
        weblog = TestUtils.setupWeblog("routeblog", user);
        TestUtils.endSession(true);

        savePage("about", "About Us", WeblogPage.PubStatus.PUBLISHED);
        savePage("draft-page", "Not Yet", WeblogPage.PubStatus.DRAFT);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private void savePage(String slug, String title, WeblogPage.PubStatus status)
            throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug(slug);
        page.setTitle(title);
        page.setContent("Body of **" + slug + "**");
        page.setStatus(status);
        WebloggerFactory.getWeblogger().getWeblogPageManager().savePage(page);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);
    }

    private MockHttpServletResponse get(String path) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/routeblog" + path);
        return RenderingTestSupport.execute(RenderingTestSupport.pageServlet(), request);
    }

    @Test
    void aPublishedPageRendersAtItsBareSlug() throws Exception {
        MockHttpServletResponse response = get("/about");

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("About Us"),
                response.getContentAsString());
    }

    @Test
    void thePageContentIsRenderedAsMarkdown() throws Exception {
        assertTrue(get("/about").getContentAsString().contains("<strong>about</strong>"));
    }

    @Test
    void aDraftPageIs404ToAnAnonymousReader() throws Exception {
        assertEquals(404, get("/draft-page").getStatus(),
                "an unpublished page must not be readable");
    }

    @Test
    void anUnknownSlugIs404() throws Exception {
        assertEquals(404, get("/no-such-page").getStatus());
    }

    @Test
    void theWeblogHomePageStillWorks() throws Exception {
        assertEquals(200, get("").getStatus());
    }

    @Test
    void aReservedContextStillRoutesToItsOwnView() throws Exception {
        assertFalse(get("/tags").getStatus() == 404,
                "/tags must keep resolving to the tags view, not a page lookup");
    }
}
