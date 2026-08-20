package org.apache.roller.weblogger.ui.rendering.servlets;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * A search that matched nothing used to render the search-again form and
     * then an empty entries div -- no sentence anywhere saying so. The
     * default fixture theme is journal, so this covers qj-*; the other two
     * themes' copies are pinned by the source scan below (their zero-hit path
     * is the same three lines of template with a different class prefix).
     */
    @Test
    void aSearchThatMatchesNothingSaysSo() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/search", "/searchblog");
        request.setParameter("q", "zzznope");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("qj-search-empty"),
                "a zero-hit search must carry the theme's no-results block:\n" + body);
        assertTrue(body.contains(NO_RESULTS_SENTENCE),
                "a zero-hit search must say so in words:\n" + body);
    }

    /**
     * A search page is crawlable at every {@code ?q=} permutation, which is
     * an unbounded set of thin near-duplicate pages. {@code #showSeoHead}
     * now emits robots noindex whenever the model is a search result.
     */
    @Test
    void theSearchPageIsNoindex() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/search", "/searchblog");
        request.setParameter("q", "zzznope");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);

        assertTrue(response.getContentAsString()
                        .contains("<meta name=\"robots\" content=\"noindex\">"),
                "search result permutations must not be indexed:\n"
                        + response.getContentAsString());
    }

    // ------------------------------------------------- searchresults.vm scan

    private static final String NO_RESULTS_SENTENCE =
            "No entries matched that search.";
    private static final String SEARCH_ERROR_SENTENCE =
            "Search is temporarily unavailable.";

    /**
     * {@code SearchResultsModel.getErrorMessage()} carries a
     * {@code WebloggerException}'s message (Lucene down, a malformed query)
     * and was rendered by <em>nothing</em>: a broken search index looked
     * exactly like a search that found nothing. Every theme must now say so
     * -- with a fixed sentence, never the exception text, which is internal
     * detail on a public page for the same reason the Velocity error
     * templates may not print it (see {@code VelocityErrorTemplateTest}).
     */
    @Test
    void everyThemeReportsBothTheEmptyAndTheBrokenSearch() throws Exception {
        for (String theme : new String[]{"journal", "portfolio", "travel"}) {
            Path template = Path.of("src/main/webapp/themes/" + theme + "/searchresults.vm");
            assertTrue(Files.exists(template), template + " must exist");
            String source = Files.readString(template, StandardCharsets.UTF_8);

            assertTrue(source.contains(NO_RESULTS_SENTENCE),
                    theme + " must tell a reader when nothing matched:\n" + source);
            assertTrue(source.contains("$utils.isNotEmpty($model.errorMessage)"),
                    theme + " must branch on the search error:\n" + source);
            assertTrue(source.contains(SEARCH_ERROR_SENTENCE),
                    theme + " must report a broken search in fixed words:\n" + source);
            assertFalse(source.contains(">$model.errorMessage")
                            || source.contains(" $model.errorMessage<")
                            || source.contains("escapeHTML($model.errorMessage)"),
                    theme + " must never render the raw error message:\n" + source);
        }
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
