package org.apache.roller.weblogger.ui.rendering.servlets;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.PropertiesManager;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.pojos.CommentSearchCriteria;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntryComment;
import org.apache.roller.weblogger.pojos.WeblogEntryComment.ApprovalStatus;
import org.apache.roller.weblogger.ui.rendering.util.WeblogEntryCommentForm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommentServletRenderingTest {

    private User user;
    private Weblog weblog;
    private WeblogEntry entry;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("commentuser");
        weblog = TestUtils.setupWeblog("commentblog", user);
        entry = TestUtils.setupWeblogEntry("comment-entry", weblog, user);
        TestUtils.endSession(true);

        // open the comment window explicitly instead of relying on defaults.
        // requireAuthenticatedComments ships ON, so most of these tests -- which
        // drive the anonymous path -- have to turn it off deliberately; the two
        // that exercise it turn it back on.
        Weblog managedWeblog = TestUtils.getManagedWebsite(weblog);
        managedWeblog.setAllowComments(Boolean.TRUE);
        managedWeblog.setRequireAuthenticatedComments(Boolean.FALSE);
        WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(managedWeblog);
        WeblogEntry managedEntry = TestUtils.getManagedWeblogEntry(entry);
        managedEntry.setAllowComments(Boolean.TRUE);
        managedEntry.setCommentDays(Integer.valueOf(7));
        WebloggerFactory.getWeblogger().getWeblogEntryManager().saveWeblogEntry(managedEntry);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private MockHttpServletRequest commentPost(String content, String email) {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousPost("/roller-ui/rendering/comment", "/commentblog/entry/comment-entry");
        request.setParameter("method", "post");
        request.setParameter("name", "Anonymous Reader");
        request.setParameter("email", email);
        request.setParameter("content", content);
        return request;
    }

    private List<WeblogEntryComment> commentsInDb() throws Exception {
        WeblogEntryManager manager = WebloggerFactory.getWeblogger().getWeblogEntryManager();
        CommentSearchCriteria criteria = new CommentSearchCriteria();
        criteria.setEntry(TestUtils.getManagedWeblogEntry(entry));
        return manager.getComments(criteria);
    }

    /**
     * Comment throttling can be switched on and off while the server runs.
     *
     * <p>It used to be latched at servlet init: with {@code
     * comment.throttle.enabled} false at startup no throttle object existed at
     * all, so an administrator meeting a flood of spam could only turn
     * throttling on by restarting. The flag is now a runtime property read per
     * request, and the throttle itself is sized once — so this drives ONE
     * servlet instance across both settings. A fresh servlet per phase would
     * pass even with the old latching behaviour and prove nothing.
     *
     * <p>The sizing knobs stay startup-scoped, hence the threshold override
     * before the servlet is built: it makes the limit reachable in three posts
     * instead of twenty-six.
     */
    @Test
    void commentThrottlingCanBeSwitchedOnAndOffWithoutARestart() throws Exception {
        String previousThreshold = overrideStartupProperty("comment.throttle.threshold", "2");
        String previousEnabled = setRuntimeProperty("comment.throttle.enabled", "true");
        try {
            // One servlet for the whole test: the throttle is sized at init,
            // and the switch has to be read after that or nothing is proved.
            CommentServlet servlet = RenderingTestSupport.commentServlet();

            assertEquals(200, throttledPost(servlet, "within budget 1").getStatus());
            assertEquals(200, throttledPost(servlet, "within budget 2").getStatus());

            // Past the threshold the servlet refuses the post outright rather
            // than saving it, so the comment count is the real assertion.
            assertEquals(404, throttledPost(servlet, "over budget").getStatus(),
                    "a client past the throttle threshold must be refused");
            assertEquals(2, commentsInDb().size(),
                    "the refused post must not have been saved");

            setRuntimeProperty("comment.throttle.enabled", "false");

            assertEquals(200, throttledPost(servlet, "throttle now off").getStatus(),
                    "turning throttling off must take effect on the running servlet; a "
                            + "flag latched at init would still be refusing this client");
            assertEquals(3, commentsInDb().size());
        } finally {
            setRuntimeProperty("comment.throttle.enabled", previousEnabled);
            overrideStartupProperty("comment.throttle.threshold", previousThreshold);
        }
    }

    /**
     * A comment post from one fixed client address, so the throttle counts
     * these together.
     *
     * <p>The address is unique to this test because tripping the throttle also
     * adds the caller to {@code IPBanList}, a JVM-wide singleton that outlives
     * the test.
     */
    private MockHttpServletResponse throttledPost(CommentServlet servlet, String content)
            throws Exception {
        MockHttpServletRequest request = commentPost(content, "reader@example.com");
        request.setRemoteAddr("203.0.113.42");
        return RenderingTestSupport.execute(servlet, request);
    }

    /** Sets a runtime property, returning what it was. */
    private static String setRuntimeProperty(String name, String value) throws Exception {
        PropertiesManager pmgr = WebloggerFactory.getWeblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        String previous = config.get(name).getValue();
        config.get(name).setValue(value);
        pmgr.saveProperties(config);
        WebloggerFactory.getWeblogger().flush();
        return previous;
    }

    /** Sets a startup-config property, returning what it was. Process-global. */
    private static String overrideStartupProperty(String name, String value) throws Exception {
        Field field = WebloggerConfig.class.getDeclaredField("config");
        field.setAccessible(true);
        Properties config = (Properties) field.get(null);
        String previous = config.getProperty(name);
        if (value == null) {
            config.remove(name);
        } else {
            config.setProperty(name, value);
        }
        return previous;
    }

    @Test
    void validCommentIsApprovedSavedAndForwarded() throws Exception {
        MockHttpServletResponse response = RenderingTestSupport.execute(
                RenderingTestSupport.commentServlet(),
                commentPost("first comment", "reader@example.com"));

        assertEquals("/roller-ui/rendering/page/commentblog/entry/comment-entry",
                response.getForwardedUrl());
        List<WeblogEntryComment> saved = commentsInDb();
        assertEquals(1, saved.size(), "comment must be persisted");
        assertEquals(ApprovalStatus.APPROVED, saved.get(0).getStatus());
        assertEquals("first comment", saved.get(0).getContent());
    }

    @Test
    void moderatedWeblogHoldsCommentAsPending() throws Exception {
        Weblog managedWeblog = TestUtils.getManagedWebsite(weblog);
        managedWeblog.setModerateComments(Boolean.TRUE);
        WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(managedWeblog);
        TestUtils.endSession(true);

        MockHttpServletResponse response = RenderingTestSupport.execute(
                RenderingTestSupport.commentServlet(),
                commentPost("hold me", "reader@example.com"));

        assertEquals("/roller-ui/rendering/page/commentblog/entry/comment-entry",
                response.getForwardedUrl());
        List<WeblogEntryComment> saved = commentsInDb();
        assertEquals(1, saved.size(), "moderated comment must still be persisted");
        assertEquals(ApprovalStatus.PENDING, saved.get(0).getStatus());
    }

    @Test
    void invalidEmailIsRejectedWithoutSaving() throws Exception {
        MockHttpServletRequest request = commentPost("bad email", "not-an-email");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.commentServlet(), request);

        assertEquals("/roller-ui/rendering/page/commentblog/entry/comment-entry",
                response.getForwardedUrl());
        WeblogEntryCommentForm form =
                (WeblogEntryCommentForm) request.getAttribute("commentForm");
        assertNotNull(form, "the error form must ride back on the request");
        assertTrue(form.isError(), "an invalid email must flag an error");
        assertEquals(0, commentsInDb().size(), "nothing may be saved");
    }

    @Test
    void unknownEntryIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousPost("/roller-ui/rendering/comment", "/commentblog/entry/nope");
        request.setParameter("method", "post");
        request.setParameter("name", "x");
        request.setParameter("email", "x@example.com");
        request.setParameter("content", "x");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.commentServlet(), request);

        assertEquals(404, response.getStatus());
    }

    // --- signed-in commenting ------------------------------------------------

    /** Flips the weblog into signed-in-only commenting. */
    private void requireSignIn() throws Exception {
        Weblog managedWeblog = TestUtils.getManagedWebsite(weblog);
        managedWeblog.setRequireAuthenticatedComments(Boolean.TRUE);
        WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(managedWeblog);
        TestUtils.endSession(true);
    }

    /**
     * The whole point of the flag: an anonymous POST is refused even though
     * every other thing about the comment is valid. Without the servlet-side
     * check the flag would only hide the form, and hiding a form stops nobody
     * who posts directly.
     */
    @Test
    void anAnonymousCommentIsRefusedWhenTheWeblogRequiresASignIn() throws Exception {
        requireSignIn();

        MockHttpServletRequest request = commentPost("drive-by", "spammer@example.com");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.commentServlet(), request);

        assertEquals("/roller-ui/rendering/page/commentblog/entry/comment-entry",
                response.getForwardedUrl());
        WeblogEntryCommentForm form =
                (WeblogEntryCommentForm) request.getAttribute("commentForm");
        assertNotNull(form, "the error form must ride back on the request");
        assertTrue(form.isError(), "an anonymous post must be flagged as an error");
        assertEquals(0, commentsInDb().size(), "nothing may be saved");
    }

    @Test
    void aSignedInCommentIsAcceptedWhenTheWeblogRequiresASignIn() throws Exception {
        requireSignIn();

        MockHttpServletRequest request = RenderingTestSupport.signedInPost(
                "/roller-ui/rendering/comment", "/commentblog/entry/comment-entry",
                user.getUserName());
        request.setParameter("method", "post");
        request.setParameter("name", "Anonymous Reader");
        request.setParameter("email", "reader@example.com");
        request.setParameter("content", "a comment with a name behind it");

        RenderingTestSupport.execute(RenderingTestSupport.commentServlet(), request);

        List<WeblogEntryComment> saved = commentsInDb();
        assertEquals(1, saved.size(), "a signed-in comment must be persisted");
        assertEquals(ApprovalStatus.APPROVED, saved.get(0).getStatus());
    }

    /**
     * The posted name and email are ignored in favour of the account's. A
     * sign-in requirement that still let the commenter type any name would
     * leave impersonation exactly as easy as it was before.
     */
    @Test
    void aSignedInCommentIsAttributedToTheAccountNotThePostedFields() throws Exception {
        requireSignIn();

        MockHttpServletRequest request = RenderingTestSupport.signedInPost(
                "/roller-ui/rendering/comment", "/commentblog/entry/comment-entry",
                user.getUserName());
        request.setParameter("method", "post");
        request.setParameter("name", "Somebody Else Entirely");
        request.setParameter("email", "victim@example.com");
        request.setParameter("content", "signed in somebody else's name");

        RenderingTestSupport.execute(RenderingTestSupport.commentServlet(), request);

        List<WeblogEntryComment> saved = commentsInDb();
        assertEquals(1, saved.size());
        assertEquals(user.getScreenName(), saved.get(0).getName(),
                "the account's screen name must win over the posted one");
        assertEquals(user.getEmailAddress(), saved.get(0).getEmail(),
                "the account's email must win over the posted one");
    }

    @Test
    void getIsAlwaysNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/comment", "/commentblog/entry/comment-entry");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.commentServlet(), request);

        assertEquals(404, response.getStatus());
    }
}
