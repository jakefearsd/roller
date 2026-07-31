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

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.CommentSearchCriteria;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntryComment;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CommentsPager}, which backs the site-wide recent
 * comments feed and page.
 *
 * <p>Beyond the usual paging arithmetic this pager publishes a
 * {@code lastUpdated} timestamp straight into the feed's {@code <updated>} /
 * {@code <lastBuildDate>} element. Reporting a timestamp older than the newest
 * comment makes conditional GETs answer 304 and subscribers never see the new
 * comments, so the "newest of all items" scan has to be right.
 */
class CommentsPagerTest {

    private static final String BASE_URL = "http://localhost/roller/comments";
    private static final int LENGTH = 3;

    private final URLStrategy urlStrategy = mock(URLStrategy.class);
    private final Weblog weblog = new Weblog();

    /** Comments whose post times ascend, so item 0 is never the newest. */
    private static List<WeblogEntryComment> commentsWithPostTimes(long... epochMillis) {
        List<WeblogEntryComment> list = new ArrayList<>();
        for (long millis : epochMillis) {
            WeblogEntryComment comment = new WeblogEntryComment();
            comment.setPostTime(new Timestamp(millis));
            list.add(comment);
        }
        return list;
    }

    private static void withEntryManager(WeblogEntryManager manager, Runnable body) {
        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            Weblogger weblogger = mock(Weblogger.class);
            when(weblogger.getWeblogEntryManager()).thenReturn(manager);
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);
            body.run();
        }
    }

    @Test
    void exactlyOnePageOfResultsReportsNoFurtherPages() throws Exception {
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getComments(any())).thenReturn(commentsWithPostTimes(1L, 2L, 3L));

        withEntryManager(manager, () -> {
            CommentsPager pager =
                    new CommentsPager(urlStrategy, BASE_URL, weblog, -1, 0, LENGTH);

            assertEquals(LENGTH, pager.getItems().size());
            assertFalse(pager.hasMoreItems(),
                    "A page filled exactly to the limit is the last page");
            assertNull(pager.getNextLink());
        });
    }

    @Test
    void oneExtraRowReportsAFurtherPageButIsNotItselfDisplayed() throws Exception {
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getComments(any())).thenReturn(commentsWithPostTimes(1L, 2L, 3L, 4L));

        withEntryManager(manager, () -> {
            CommentsPager pager =
                    new CommentsPager(urlStrategy, BASE_URL, weblog, -1, 0, LENGTH);

            assertEquals(LENGTH, pager.getItems().size(),
                    "The probe row must not be rendered as a fourth comment");
            assertTrue(pager.hasMoreItems());
            assertEquals(BASE_URL + "?page=1", pager.getNextLink());
        });
    }

    @Test
    void searchCriteriaCarryTheOffsetPageSizeAndApprovedFilter() throws Exception {
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getComments(any())).thenReturn(List.of());

        withEntryManager(manager, () ->
                new CommentsPager(urlStrategy, BASE_URL, weblog, -1, 2, LENGTH));

        ArgumentCaptor<CommentSearchCriteria> criteria =
                ArgumentCaptor.forClass(CommentSearchCriteria.class);
        verify(manager).getComments(criteria.capture());

        assertEquals(2 * LENGTH, criteria.getValue().getOffset(),
                "Page 2 of 3-item pages starts at row 6");
        assertEquals(LENGTH + 1, criteria.getValue().getMaxResults(),
                "One extra row is fetched purely to detect a further page");
        assertEquals(WeblogEntryComment.ApprovalStatus.APPROVED,
                criteria.getValue().getStatus(),
                "A public feed must only ever show approved comments");
        assertTrue(criteria.getValue().isReverseChrono(),
                "Recent comments are newest first");
        assertEquals(weblog, criteria.getValue().getWeblog(),
                "The query must be scoped to this weblog, or the feed shows the "
                        + "whole site's comments");
    }

    @Test
    void sinceDaysOfMinusOneMeansNoDateFloor() throws Exception {
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getComments(any())).thenReturn(List.of());

        withEntryManager(manager, () ->
                new CommentsPager(urlStrategy, BASE_URL, weblog, -1, 0, LENGTH));

        ArgumentCaptor<CommentSearchCriteria> criteria =
                ArgumentCaptor.forClass(CommentSearchCriteria.class);
        verify(manager).getComments(criteria.capture());

        assertNull(criteria.getValue().getStartDate(),
                "sinceDays=-1 means no time limit at all");
    }

    @Test
    void sinceDaysOfZeroAlsoMeansNoDateFloor() throws Exception {
        // The guard is `sinceDays > 0`. Zero sits on the boundary: "the last
        // zero days" is not a window, so it must behave like no filter rather
        // than like "since this instant", which would return nothing.
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getComments(any())).thenReturn(List.of());

        withEntryManager(manager, () ->
                new CommentsPager(urlStrategy, BASE_URL, weblog, 0, 0, LENGTH));

        ArgumentCaptor<CommentSearchCriteria> criteria =
                ArgumentCaptor.forClass(CommentSearchCriteria.class);
        verify(manager).getComments(criteria.capture());

        assertNull(criteria.getValue().getStartDate(),
                "sinceDays=0 must not become a start date of 'now', which would "
                        + "empty the feed");
    }

    @Test
    void positiveSinceDaysAppliesADateFloorInThePast() throws Exception {
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getComments(any())).thenReturn(List.of());

        withEntryManager(manager, () ->
                new CommentsPager(urlStrategy, BASE_URL, weblog, 7, 0, LENGTH));

        ArgumentCaptor<CommentSearchCriteria> criteria =
                ArgumentCaptor.forClass(CommentSearchCriteria.class);
        verify(manager).getComments(criteria.capture());

        assertNotNull(criteria.getValue().getStartDate(), "sinceDays=7 must set a floor");
        assertDaysAgo(criteria.getValue().getStartDate(), 7);
    }

    /**
     * Asserts a date is roughly the given number of days in the past.
     *
     * <p>Checking only "before now" would pass for a floor of one second ago or
     * one century ago -- either of which silently changes what the feed
     * contains. The tolerance covers clock movement during the test, nothing more.
     */
    private static void assertDaysAgo(Date actual, int expectedDays) {
        long millisAgo = System.currentTimeMillis() - actual.getTime();
        long expectedMillis = expectedDays * 86_400_000L;
        long toleranceMillis = 60_000L;

        assertTrue(Math.abs(millisAgo - expectedMillis) < toleranceMillis,
                "Expected a floor about " + expectedDays + " days ago, but it was "
                        + (millisAgo / 86_400_000.0) + " days ago");
    }

    @Test
    void lastUpdatedIsTheNewestPostTimeEvenWhenItIsNotTheFirstItem() throws Exception {
        // The feed is ordered by post time, but the pager deliberately scans
        // every item rather than trusting position 0. This fixture puts the
        // newest comment last so a "just take the first" shortcut fails.
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getComments(any()))
                .thenReturn(commentsWithPostTimes(1_000L, 5_000L, 3_000L));

        withEntryManager(manager, () -> {
            CommentsPager pager =
                    new CommentsPager(urlStrategy, BASE_URL, weblog, -1, 0, LENGTH);

            assertEquals(new Date(5_000L), pager.getLastUpdated(),
                    "lastUpdated must be the newest comment in the page; a stale "
                            + "value makes conditional GETs hide new comments");
        });
    }

    @Test
    void lastUpdatedIsComputedOnceAndReused() throws Exception {
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getComments(any())).thenReturn(commentsWithPostTimes(1_000L));

        withEntryManager(manager, () -> {
            CommentsPager pager =
                    new CommentsPager(urlStrategy, BASE_URL, weblog, -1, 0, LENGTH);

            assertEquals(pager.getLastUpdated(), pager.getLastUpdated(),
                    "A feed must not report a different timestamp each time it is read");
        });
    }

    @Test
    void anEmptyPageReportsNowSoTheFeedStillValidates() throws Exception {
        // A feed element cannot be empty, so with no comments the pager falls
        // back to the current time.
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getComments(any())).thenReturn(List.of());

        withEntryManager(manager, () -> {
            Date before = new Date();
            CommentsPager pager =
                    new CommentsPager(urlStrategy, BASE_URL, weblog, -1, 0, LENGTH);
            Date lastUpdated = pager.getLastUpdated();

            assertNotNull(lastUpdated, "An empty feed still needs a timestamp");
            assertFalse(lastUpdated.before(before),
                    "With no comments the timestamp must be 'now', not the epoch");
        });
    }

    @Test
    void aFailingQueryLeavesAnEmptyPagerRatherThanBreakingTheFeed() throws Exception {
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getComments(any())).thenThrow(new WebloggerException("database is down"));

        withEntryManager(manager, () -> {
            CommentsPager pager =
                    new CommentsPager(urlStrategy, BASE_URL, weblog, -1, 0, LENGTH);

            assertTrue(pager.getItems().isEmpty(),
                    "A failed lookup must yield an empty list, never null");
            assertFalse(pager.hasMoreItems());
        });
    }
}
