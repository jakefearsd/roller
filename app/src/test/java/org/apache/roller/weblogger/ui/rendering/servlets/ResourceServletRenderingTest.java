package org.apache.roller.weblogger.ui.rendering.servlets;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ResourceServlet serves weblog uploads at a fixed path, looked up by
 * {@link MediaFile#getOriginalPath()}. Ordinary uploads created through
 * {@link TestUtils#setupImageMediaFile(Weblog, String)} don't have an
 * original path set -- that field is only populated by the legacy
 * fixed-path-upload storage upgrade (see JPAMediaFileManagerImpl). We set it
 * directly on the fixture and persist it via updateMediaFile so the 200 path
 * through the servlet is exercised too.
 */
class ResourceServletRenderingTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("resourceuser");
        weblog = TestUtils.setupWeblog("resourceblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void resourceWithOriginalPathStreams() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "photo.jpg");
        image.setOriginalPath("/photo.jpg");
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        mfMgr.updateMediaFile(TestUtils.getManagedWebsite(weblog), image);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/resources", "/resourceblog/photo.jpg");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.resourceServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsByteArray().length > 1000,
                "the real image bytes must be streamed");
    }

    @Test
    void unknownWeblogIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/resources", "/nosuchblog/whatever.jpg");
        assertEquals(404, RenderingTestSupport.execute(
                RenderingTestSupport.resourceServlet(), request).getStatus());
    }

    @Test
    void unknownPathIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/resources", "/resourceblog/no-such-file.jpg");
        assertEquals(404, RenderingTestSupport.execute(
                RenderingTestSupport.resourceServlet(), request).getStatus());
    }

    @Test
    void aConditionalRequestForAnUnchangedResourceIsNotModified() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "cond.jpg");
        image.setOriginalPath("/cond.jpg");
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        mfMgr.updateMediaFile(TestUtils.getManagedWebsite(weblog), image);
        TestUtils.endSession(true);

        MockHttpServletRequest first = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/resources", "/resourceblog/cond.jpg");
        MockHttpServletResponse firstResponse = RenderingTestSupport
                .execute(RenderingTestSupport.resourceServlet(), first);
        assertEquals(200, firstResponse.getStatus());

        MockHttpServletRequest conditional = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/resources", "/resourceblog/cond.jpg");
        conditional.addHeader("If-Modified-Since", firstResponse.getDateHeader("Last-Modified"));

        assertEquals(304, RenderingTestSupport.execute(
                        RenderingTestSupport.resourceServlet(), conditional).getStatus(),
                "a reader who already has this file must not be sent it again -- every "
                        + "image on every page depends on this");
    }
}
