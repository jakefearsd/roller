package org.apache.roller.weblogger.ui.rendering.servlets;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.CwebpEncoder;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        CwebpEncoder.setAvailableForTesting(null);
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

    // ------------------------------------------------- ?w= responsive renditions

    @Test
    void widthParameterStreamsTheMatchingRendition() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "wide480.jpg");
        TestUtils.endSession(true);

        MockHttpServletRequest full = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        MockHttpServletRequest w480 = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        w480.setParameter("w", "480");

        int fullSize = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), full).getContentAsByteArray().length;
        MockHttpServletResponse w480Response = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), w480);

        assertEquals(200, w480Response.getStatus());
        assertEquals("image/jpeg", w480Response.getContentType());
        assertTrue(w480Response.getContentAsByteArray().length < fullSize,
                "the 480w rendition must be smaller than the 500w original (hawk.jpg)");
        assertEquals("Accept", w480Response.getHeader("Vary"),
                "a ?w= URL is content-negotiated on Accept, so shared caches must key on it");
    }

    @Test
    void aLadderWidthNeverGeneratedForThisImageFallsBackToTheOriginal() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);
        // hawk.jpg is 500w; 960 is a real ladder width but is never generated
        // for an original this narrow (960 >= 500).
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "notgenerated.jpg");
        TestUtils.endSession(true);

        MockHttpServletRequest full = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        MockHttpServletRequest w960 = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        w960.setParameter("w", "960");

        MockHttpServletResponse fullResponse = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), full);
        MockHttpServletResponse w960Response = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), w960);

        assertEquals(200, w960Response.getStatus());
        assertEquals("image/jpeg", w960Response.getContentType());
        assertEquals(fullResponse.getContentAsByteArray().length, w960Response.getContentAsByteArray().length,
                "a valid ladder width with no generated rendition must 404-safe fall back to the original");
    }

    @Test
    void aWidthOutsideTheLadderIsRejectedAndServesTheOriginal() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "rejectwidth.jpg");
        TestUtils.endSession(true);

        MockHttpServletRequest full = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        MockHttpServletRequest badWidth = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        // 999 is not one of the four ladder widths -- it must be validated
        // against the ladder set only, not treated as an arbitrary resize request.
        badWidth.setParameter("w", "999");

        MockHttpServletResponse fullResponse = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), full);
        MockHttpServletResponse badWidthResponse = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), badWidth);

        assertEquals(200, badWidthResponse.getStatus());
        assertEquals("image/jpeg", badWidthResponse.getContentType());
        assertEquals(fullResponse.getContentAsByteArray().length, badWidthResponse.getContentAsByteArray().length,
                "a width outside the ladder set must be rejected and fall back to the original");
    }

    @Test
    void webpIsNeverServedWhenCwebpWasUnavailableAtGenerationTime() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "nowebp.jpg");
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        request.setParameter("w", "480");
        request.addHeader("Accept", "image/webp,image/*,*/*");

        MockHttpServletResponse response = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), request);

        assertEquals(200, response.getStatus());
        assertNotEquals("image/webp", response.getContentType(),
                "no webp sibling exists when cwebp was unavailable at generation time");
        assertEquals("image/jpeg", response.getContentType());
    }

    @Test
    void webpIsServedWhenTheClientAcceptsItAndCwebpWasAvailable() throws Exception {
        // Real detection (not forced) -- this is the one test that proves the
        // real cwebp integration works end to end; skipped rather than failed
        // when this machine has no cwebp installed.
        CwebpEncoder.setAvailableForTesting(null);
        Assumptions.assumeTrue(CwebpEncoder.isAvailable(),
                "cwebp is not installed on this machine -- skipping the webp negotiation check");

        MediaFile image = TestUtils.setupImageMediaFile(weblog, "webpnegotiation.jpg");
        TestUtils.endSession(true);

        MockHttpServletRequest webpRequest = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        webpRequest.setParameter("w", "480");
        webpRequest.addHeader("Accept", "image/webp,image/*,*/*");

        MockHttpServletRequest plainRequest = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        plainRequest.setParameter("w", "480");
        plainRequest.addHeader("Accept", "image/jpeg,image/*,*/*");

        MockHttpServletResponse webpResponse = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), webpRequest);
        MockHttpServletResponse plainResponse = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), plainRequest);

        assertEquals(200, webpResponse.getStatus());
        assertEquals("image/webp", webpResponse.getContentType(),
                "Accept: image/webp must negotiate the .webp sibling when it exists");
        assertEquals("image/jpeg", plainResponse.getContentType(),
                "no Accept: image/webp must serve the raster rendition");
        // Same URL, two different bodies and content types: without Vary a
        // shared cache would hand one client the other's variant.
        assertEquals("Accept", webpResponse.getHeader("Vary"));
        assertEquals("Accept", plainResponse.getHeader("Vary"));
    }
}
