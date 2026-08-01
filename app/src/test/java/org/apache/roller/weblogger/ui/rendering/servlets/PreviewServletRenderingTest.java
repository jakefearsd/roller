package org.apache.roller.weblogger.ui.rendering.servlets;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PreviewServlet is the authoring-side render of a weblog. It requires no
 * request principal (access control is URL-layer, in SecurityConfig's
 * authorization rules) — so these tests drive it exactly like the public
 * servlets.
 */
class PreviewServletRenderingTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("previewuser");
        weblog = TestUtils.setupWeblog("previewblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void frontPagePreviewRendersPublishedEntry() throws Exception {
        TestUtils.setupWeblogEntry("preview-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/authoring/preview", "/previewblog");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.previewServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("text/html"));
        assertTrue(response.getContentAsString().contains("preview-entry"),
                "preview must render the entry:\n" + response.getContentAsString());
    }

    @Test
    void themeParameterPreviewsAnotherTheme() throws Exception {
        TestUtils.setupWeblogEntry("themed-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/authoring/preview", "/previewblog");
        request.setParameter("theme", "gaurav");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.previewServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("themed-entry"),
                "theme-preview must still render the weblog's entries");
    }

    @Test
    void permalinkPreviewRendersTheEntry() throws Exception {
        TestUtils.setupWeblogEntry("preview-permalink", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/authoring/preview", "/previewblog/entry/preview-permalink");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.previewServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("preview-permalink"));
    }

    @Test
    void unknownWeblogPreviewIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/authoring/preview", "/nosuchblog");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.previewServlet(), request);

        assertEquals(404, response.getStatus());
    }

    @Test
    void draftEntryPreviewRendersViaPreviewEntryParam() throws Exception {
        // WeblogPreviewRequest reads ?previewEntry=<anchor> and
        // PreviewPageModel routes it through WeblogEntriesPreviewPager, which
        // (unlike the public pager PageModel uses) does not filter by
        // PubStatus -- so a DRAFT entry renders here. This is the inverse of
        // PageServletRenderingTest#draftEntryPermalinkIsNotFound.
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        WeblogCategory category = managed.getWeblogCategories().iterator().next();
        TestUtils.setupWeblogEntry("draft-preview-entry", category, PubStatus.DRAFT, managed, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/authoring/preview", "/previewblog");
        request.setParameter("previewEntry", "draft-preview-entry");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.previewServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("draft-preview-entry"),
                "draft preview must render the DRAFT entry:\n" + response.getContentAsString());
    }
}
