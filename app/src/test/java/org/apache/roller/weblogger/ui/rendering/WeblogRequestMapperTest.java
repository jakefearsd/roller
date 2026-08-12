package org.apache.roller.weblogger.ui.rendering;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    // ------------------------------------------- what is NOT a weblog handle

    /**
     * The protected-URL list is what stops an application path being mistaken
     * for a weblog. Every name in {@code rendering.weblogMapper.rollerProtectedUrls}
     * is a path this mapper must decline so the real servlet behind it runs --
     * and a weblog registered under one of those handles must not be able to
     * shadow it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"roller-ui", "images", "theme", "themes", "index.jsp",
            "favicon.svg", "robots.txt", "sitemap.xml", "share", "webjars",
            "page", "flavor", "rss", "atom", "language", "search", "comments", "resource"})
    void protectedPathsAreDeclinedSoTheirOwnServletsRun(String reserved) throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/" + reserved + "/anything");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(request, response),
                reserved + " is a reserved application path, not a weblog handle");
        assertNull(response.getForwardedUrl());
    }

    @Test
    void theBareContextRootIsNotAWeblog() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(request, response),
                "the site root belongs to the front page, not to a weblog lookup");
    }

    // --------------------------------------------------------------- locales

    /**
     * A locale segment is optional and sits before the context. Misreading it
     * as the context would route /blog/en/entry/x to a page named "en".
     */
    @Test
    void aLocaleSegmentIsRecognisedAndPassedThrough() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/en/entry/my-post");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/page/mapperblog/en/entry/my-post",
                response.getForwardedUrl());
    }

    /**
     * With no context after it, a locale still needs the trailing-slash
     * redirect -- /blog/en and /blog/en/ are different urls.
     */
    @Test
    void aLocaleWithNoContextRedirectsForTheTrailingSlash() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/en");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/mapperblog/", response.getRedirectedUrl());
    }

    /**
     * A path segment that merely looks like a locale must not be swallowed as
     * one, or a page named "entry" would become unreachable.
     */
    @Test
    void aNonLocaleFirstSegmentIsTreatedAsTheContext() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/category/travel");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/page/mapperblog/category/travel",
                response.getForwardedUrl());
    }

    /**
     * Extra path segments past the context are reassembled rather than
     * dropped: a category can be nested.
     */
    @Test
    void extraPathSegmentsAreReassembledOntoTheForward() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/category/travel/spain");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/page/mapperblog/category/travel/spain",
                response.getForwardedUrl());
    }

    // ------------------------------------------------- the trailing-slash rules

    /**
     * The query string has to survive the trailing-slash redirect, or a link
     * to /blog?q=x loses its parameters on the way to /blog/.
     */
    @Test
    void theTrailingSlashRedirectKeepsTheQueryString() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog");
        request.setQueryString("page=2");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/mapperblog/?page=2", response.getRedirectedUrl());
    }

    /**
     * Past the weblog root the rule inverts: a trailing slash on a permalink is
     * a different url from the permalink, and only one of them may exist.
     */
    @Test
    void aTrailingSlashOnAPermalinkIsNotFound() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/entry/my-post/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response),
                "the mapper answers this itself rather than declining it");
        assertEquals(404, response.getStatus());
    }

    /**
     * Tags invert it again, and in both directions: the index wants the slash,
     * a tag query must not have one.
     */
    @Test
    void theTagsIndexRequiresItsTrailingSlash() throws Exception {
        MockHttpServletResponse withSlash = new MockHttpServletResponse();
        assertTrue(mapper.handleRequest(publicUrl("GET", "/mapperblog/tags/"), withSlash));
        assertEquals("/roller-ui/rendering/page/mapperblog/tags", withSlash.getForwardedUrl());

        MockHttpServletResponse without = new MockHttpServletResponse();
        assertTrue(mapper.handleRequest(publicUrl("GET", "/mapperblog/tags"), without));
        assertEquals(404, without.getStatus(),
                "the tags index without its slash is not a url this site serves");
    }

    @Test
    void aTagQueryMustNotCarryATrailingSlash() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(mapper.handleRequest(publicUrl("GET", "/mapperblog/tags/spain+food/"), response));
        assertEquals(404, response.getStatus());
    }

    // --------------------------------------------------- static-page slugs

    /**
     * A single unrecognised segment is a page-slug candidate
     * (/<handle>/<slug>): every reserved word the mapper actually knows
     * about (page/entry/date/category/tags/feed/resource/mediaresource/
     * search) is handled by name above, so anything else that reaches the
     * default branch with no further path data must be forwarded to
     * PageServlet, which resolves the slug and 404s itself when it does not
     * name a published page (see WeblogPageRequest/WeblogPageManager). Before
     * this, the mapper declined the request outright and a published static
     * page never became reachable.
     */
    @Test
    void anUnknownSingleSegmentForwardsToPageServletAsAPageSlugCandidate() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/about");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/page/mapperblog/about", response.getForwardedUrl());
    }

    /**
     * The locale segment, when present, must survive onto the forwarded
     * page-slug url exactly as it does for every other context -- the
     * page-slug branch builds its own forward url from scratch rather than
     * reusing the locale-aware builder above it, so it has to repeat the
     * locale handling, not just the handle.
     */
    @Test
    void anUnknownSingleSegmentWithALocaleCarriesTheLocaleOntoTheForward() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/en/about");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/page/mapperblog/en/about",
                response.getForwardedUrl());
    }

    /**
     * A second path segment is never a page slug -- WeblogPageRequest only
     * treats a single remaining path element as one -- so this must stay
     * declined exactly as it was before the page-slug forward existed.
     */
    @Test
    void anUnknownTwoSegmentPathStaysDeclined() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/about/extra");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(request, response),
                "a second path segment past an unknown context is not a page slug");
        assertNull(response.getForwardedUrl());
    }

    /**
     * A known context (page/entry/date/category/tags/feed/resource/
     * mediaresource/search) must keep forwarding exactly as before -- the
     * page-slug fallback only ever fires from the switch's default branch.
     */
    @Test
    void aKnownContextStillForwardsAsBefore() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/date/20260101");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/page/mapperblog/date/20260101",
                response.getForwardedUrl());
    }

    // ------------------------------------------------------------ POST rules

    /**
     * POST used to be routed here only for comment submission: a permalink
     * carrying a "content" param forwarded to the comment servlet. That
     * servlet is gone with the comment subsystem, so a POST is declined
     * unconditionally now, whether or not it carries a content parameter and
     * whether or not it targets a permalink -- there is nothing left in the
     * public url space for this mapper to route a POST to.
     */
    @Test
    void aPostToAPermalinkIsDeclinedEvenWithAContentParameter() throws Exception {
        MockHttpServletRequest request = publicUrl("POST", "/mapperblog/entry/my-post");
        request.setParameter("content", "a comment");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(request, response),
                "comment-post forwarding is gone with the comment servlet");
        assertNull(response.getForwardedUrl());
    }

    @Test
    void aPostToAPermalinkWithoutContentIsDeclined() throws Exception {
        MockHttpServletRequest request = publicUrl("POST", "/mapperblog/entry/my-post");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(request, response));
        assertNull(response.getForwardedUrl());
    }

    @Test
    void aPostSomewhereOtherThanAPermalinkIsDeclined() throws Exception {
        MockHttpServletRequest request = publicUrl("POST", "/mapperblog/category/travel");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(request, response),
                "posting into the public url space is not something this mapper routes");
        assertNull(response.getForwardedUrl());
    }
}
