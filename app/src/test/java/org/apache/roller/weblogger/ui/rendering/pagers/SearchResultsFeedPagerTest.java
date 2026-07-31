/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package org.apache.roller.weblogger.ui.rendering.pagers;

import java.util.List;

import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.wrapper.WeblogEntryWrapper;
import org.apache.roller.weblogger.ui.rendering.util.WeblogFeedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SearchResultsFeedPager}.
 *
 * <p>This pager overrides {@code createURL} to re-attach the search criteria to
 * every link, then delegates to {@link AbstractPager}. That collaboration is
 * the fragile part: the base class supplies the page parameter in a map the
 * override has to add to without owning.
 */
class SearchResultsFeedPagerTest {

    private static final String BASE_URL = "http://localhost/roller/myblog/feed/entries/atom";

    private URLStrategy urlStrategy;
    private Weblog weblog;

    @BeforeEach
    void setUp() {
        urlStrategy = mock(URLStrategy.class);
        weblog = new Weblog();
        weblog.setHandle("myblog");
        weblog.setLocale("en_US");
        when(urlStrategy.getWeblogURL(any(), any(), anyBoolean()))
                .thenReturn("http://localhost/roller/myblog/");
    }

    private WeblogFeedRequest feedRequest(String term, String category, List<String> tags,
            boolean excerpts) {
        WeblogFeedRequest request = new WeblogFeedRequest();
        request.setWeblog(weblog);
        request.setLocale("en_US");
        request.setTerm(term);
        request.setWeblogCategoryName(category);
        request.setTags(tags);
        request.setExcerpts(excerpts);
        return request;
    }

    private SearchResultsFeedPager pager(int pageNum, WeblogFeedRequest request, boolean more) {
        return new SearchResultsFeedPager(urlStrategy, BASE_URL, pageNum, request,
                List.<WeblogEntryWrapper>of(), more);
    }

    // ------------------------------------------------------- link boundaries

    @Test
    void firstPageOffersNoPreviousLink() {
        SearchResultsFeedPager pager = pager(0, feedRequest("roller", null, null, false), true);

        assertNull(pager.getPrevLink(), "Page 0 of a search feed has nothing before it");
        assertNull(pager.getPrevName());
    }

    @Test
    void lastPageOffersNoNextLink() {
        SearchResultsFeedPager pager = pager(2, feedRequest("roller", null, null, false), false);

        assertNull(pager.getNextLink(),
                "With no further results the feed must not advertise another page");
        assertNull(pager.getNextName());
    }

    @Test
    void emptyResultSetOffersNeitherDirection() {
        SearchResultsFeedPager pager = pager(0, feedRequest("roller", null, null, false), false);

        assertNull(pager.getPrevLink());
        assertNull(pager.getNextLink());
        assertTrue(pager.getItems().isEmpty());
    }

    // ------------------------------------------ criteria carried on the links

    @Test
    void nextLinkKeepsTheSearchTermAlongsideThePageNumber() {
        // Regression guard. AbstractPager builds its page parameter with
        // Map.of(), which is immutable; this pager adds the search criteria to
        // that same map. Writing through to it threw
        // UnsupportedOperationException, and because a search feed always has a
        // term, that took out the next link on every search feed that had one.
        SearchResultsFeedPager pager = pager(0, feedRequest("roller", null, null, false), true);

        String next = pager.getNextLink();

        assertNotNull(next, "There are more results, so a next link must exist");
        assertTrue(next.contains("page=1"), "Next must advance the page; got: " + next);
        assertTrue(next.contains("q=roller"),
                "Next must keep the search term, or page 2 searches for nothing; got: " + next);
    }

    @Test
    void previousLinkKeepsTheSearchTermAlongsideThePageNumber() {
        SearchResultsFeedPager pager = pager(2, feedRequest("roller", null, null, false), false);

        String prev = pager.getPrevLink();

        assertNotNull(prev, "Page 2 can go back to page 1");
        assertTrue(prev.contains("page=1"), "Previous must step back one page; got: " + prev);
        assertTrue(prev.contains("q=roller"), "Previous must keep the term; got: " + prev);
    }

    @Test
    void allSearchCriteriaAreCarriedOnPagingLinks() {
        SearchResultsFeedPager pager = pager(0,
                feedRequest("roller", "Java", List.of("testing"), true), true);

        String next = pager.getNextLink();

        assertNotNull(next);
        assertTrue(next.contains("q=roller"), "term missing from: " + next);
        assertTrue(next.contains("cat=Java"), "category missing from: " + next);
        assertTrue(next.contains("tags=testing"), "tags missing from: " + next);
        assertTrue(next.contains("excerpts=true"), "excerpt flag missing from: " + next);
    }

    @Test
    void criteriaAreUrlEncodedOnTheWayIntoTheQueryString() {
        // An unencoded space or ampersand would truncate the query string and
        // silently drop every parameter after it.
        SearchResultsFeedPager pager = pager(0,
                feedRequest("hello world", "R&D", null, false), true);

        String next = pager.getNextLink();

        assertTrue(next.contains("q=hello+world") || next.contains("q=hello%20world"),
                "The term must be encoded; got: " + next);
        assertTrue(next.contains("cat=R%26D"),
                "An ampersand in a category must be encoded or it splits the query "
                        + "string; got: " + next);
    }

    @Test
    void blankCriteriaAreOmittedRatherThanSentAsEmptyParameters() {
        SearchResultsFeedPager pager = pager(0,
                feedRequest("   ", "  ", List.of(), false), true);

        String next = pager.getNextLink();

        assertNotNull(next);
        assertTrue(next.contains("page=1"), "The page number is still needed; got: " + next);
        assertTrue(!next.contains("q=") && !next.contains("cat=") && !next.contains("tags="),
                "Blank criteria must be left off entirely, not sent as empty "
                        + "parameters that match nothing; got: " + next);
    }

    @Test
    void baseUrlCarriesTheCriteriaButNotAPageNumber() {
        SearchResultsFeedPager pager = pager(3, feedRequest("roller", null, null, false), true);

        String url = pager.getUrl();

        assertTrue(url.contains("q=roller"), "The feed's own url keeps the search; got: " + url);
        assertTrue(!url.contains("page="),
                "The unadorned feed url must not pin a page; got: " + url);
    }

    // ------------------------------------------------------------- passthrough

    @Test
    void entriesAreHandedBackUntouched() {
        List<WeblogEntryWrapper> entries = List.of();
        SearchResultsFeedPager pager = new SearchResultsFeedPager(urlStrategy, BASE_URL, 0,
                feedRequest("roller", null, null, false), entries, false);

        assertSame(entries, pager.getItems());
    }

    @Test
    void homeLinkReturnsToTheWeblogItself() {
        SearchResultsFeedPager pager = pager(2, feedRequest("roller", null, null, false), true);

        assertEquals("http://localhost/roller/myblog/", pager.getHomeLink());
        assertRenderedLabel(pager.getHomeName());
    }

    @Test
    void localeIsTakenFromTheWeblogWhenTheFeedUrlHadNone() {
        WeblogFeedRequest request = feedRequest("roller", null, null, false);
        request.setLocale(null);

        SearchResultsFeedPager pager = pager(0, request, true);

        assertRenderedLabel(pager.getHomeName());
    }

    /**
     * A label must be real user-facing text. I18nMessages returns the lookup
     * key itself when the bundle entry is missing, so a non-null check alone
     * would pass while the feed carries "searchPager.home" as its home title.
     */
    private static void assertRenderedLabel(String label) {
        assertNotNull(label, "The home control must have a label");
        assertTrue(!label.isBlank(), "The home control must not render an empty caption");
        assertNotEquals("searchPager.home", label,
                "The label fell back to the raw message key, so searchPager.home is "
                        + "missing from ApplicationResources");
    }
}
