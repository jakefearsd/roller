package org.apache.roller.weblogger.ui.rendering.servlets;

import org.apache.roller.weblogger.TestUtils;
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

class MediaResourceServletRenderingTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("mediauser");
        weblog = TestUtils.setupWeblog("mediablog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void mediaFileStreamsWithContentType() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "photo.jpg");
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.mediaResourceServlet(), request);

        assertEquals(200, response.getStatus());
        assertEquals("image/jpeg", response.getContentType());
        assertTrue(response.getContentAsByteArray().length > 1000,
                "the real image bytes must be streamed");
    }

    @Test
    void thumbnailParameterStreamsSmallerImage() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "thumb.jpg");
        TestUtils.endSession(true);

        MockHttpServletRequest full = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        MockHttpServletRequest thumb = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        thumb.setParameter("t", "true");

        int fullSize = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), full).getContentAsByteArray().length;
        MockHttpServletResponse thumbResponse = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), thumb);

        assertEquals(200, thumbResponse.getStatus());
        assertTrue(thumbResponse.getContentAsByteArray().length < fullSize,
                "thumbnail must be smaller than the original");
    }

    @Test
    void unknownMediaFileIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/no-such-id");
        assertEquals(404, RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), request).getStatus());
    }

    @Test
    void unknownWeblogIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/nosuchblog/whatever");
        assertEquals(404, RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), request).getStatus());
    }
}
