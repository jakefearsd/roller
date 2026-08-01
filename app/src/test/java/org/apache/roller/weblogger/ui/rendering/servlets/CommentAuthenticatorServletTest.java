package org.apache.roller.weblogger.ui.rendering.servlets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CommentAuthenticatorServlet.init() reads comment.authenticator.classname,
 * which roller.properties defaults to DefaultCommentAuthenticator -- its
 * getHtml() emits a "custom authenticator would go here" placeholder comment.
 */
class CommentAuthenticatorServletTest {

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
    }

    @Test
    void getRendersDefaultAuthenticatorHtmlWithNoCacheHeaders() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/commentAuthenticator", "");
        MockHttpServletResponse response = RenderingTestSupport.execute(
                RenderingTestSupport.commentAuthenticatorServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("custom authenticator"));
        assertEquals("no-cache", response.getHeader("Cache-Control"));
    }
}
