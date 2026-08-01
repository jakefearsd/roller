package org.apache.roller.weblogger.ui.rendering.servlets;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders real Velocity themes through the real PageServlet against the
 * Testcontainers database. First tests ever on the anonymous-visitor path.
 */
class PageServletRenderingTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("pagerenderuser");
        weblog = TestUtils.setupWeblog("pagerenderblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void frontPageRendersPublishedEntry() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("smoke-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("text/html"),
                "front page must be html but was: " + response.getContentType());
        String body = response.getContentAsString();
        assertTrue(body.contains("smoke-entry"),
                "entry title must appear on the front page:\n" + body);
        assertTrue(body.contains("blah blah entry"),
                "entry text must appear on the front page:\n" + body);
    }

    @Test
    void permalinkRendersEntryContent() throws Exception {
        TestUtils.setupWeblogEntry("perma-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog/entry/perma-entry");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("perma-entry"), "permalink must show the entry:\n" + body);
        assertTrue(body.contains("blah blah entry"), "permalink must show the text:\n" + body);
    }

    @Test
    void anImageShortcodeExpandsOnTheLivePermalink() throws Exception {
        // End-to-end render-pipeline proof for the unconditional shortcode
        // expander: a real uploaded image (hawk.jpg, 500w, so only the 480
        // rung exists), a real entry naming NO plugins, the real PageServlet.
        org.apache.roller.weblogger.pojos.MediaFile image =
                TestUtils.setupImageMediaFile(weblog, "page-hawk.jpg");
        WeblogEntry entry = TestUtils.setupWeblogEntry("shortcode-entry", weblog, user);
        entry.setText("before [image id=" + image.getId() + " caption=\"A hawk\"] after");
        org.apache.roller.weblogger.business.WebloggerFactory.getWeblogger()
                .getWeblogEntryManager().saveWeblogEntry(entry);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog/entry/shortcode-entry");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("<figure class=\"shortcode-image\">"),
                "the [image] shortcode must expand on the live permalink:\n" + body);
        assertTrue(body.contains(image.getId() + "?w=480 480w"),
                "srcset must point the ladder rung at the media-resource URL:\n" + body);
        assertTrue(body.contains("<figcaption>A hawk</figcaption>"), body);
        assertFalse(body.contains("[image id="),
                "the raw shortcode text must not leak to readers:\n" + body);
    }

    @Test
    void draftEntryPermalinkIsNotFound() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        WeblogCategory category = managed.getWeblogCategories().iterator().next();
        TestUtils.setupWeblogEntry("draft-entry", category, PubStatus.DRAFT, managed, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog/entry/draft-entry");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(404, response.getStatus(), "a draft must never render publicly");
    }

    @Test
    void unknownWeblogIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/nosuchblog");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(404, response.getStatus());
    }

    @Test
    void categoryPageRendersItsEntries() throws Exception {
        TestUtils.setupWeblogEntry("category-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog/category/General");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("category-entry"),
                "category page must list the entry");
    }

    @Test
    void unknownCategoryIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog/category/Nope");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(404, response.getStatus());
    }
}
