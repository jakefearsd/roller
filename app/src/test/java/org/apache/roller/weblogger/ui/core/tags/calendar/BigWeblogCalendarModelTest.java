/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.ui.core.tags.calendar;

import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the "big" archive calendar, which fills each day cell with the titles
 * of that day's posts instead of a bare day number.
 *
 * <p>Its cell markup is assembled by hand from entry data, so it is the part of
 * the calendar most exposed to bad input: a post with no title, a title long
 * enough to blow the cell apart, or a day the reader has no entries for.
 */
class BigWeblogCalendarModelTest extends CalendarModelTestSupport {

    private static final Date THE_FIFTEENTH = dateIn(2024, 5, 15);

    @Test
    void aDayWithPostsListsEachOneAsALinkUnderTheDayNumber() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(Map.of(
                    THE_FIFTEENTH, List.of(entry("Morning walk", "morning-walk"),
                            entry("Evening ferry", "evening-ferry"))));
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());
            when(urlStrategy.getWeblogCollectionURL(eq(weblog), any(), any(), eq("20240515"),
                    any(), eq(-1), eq(false))).thenReturn("/blog/date/20240515");
            when(urlStrategy.getWeblogEntryURL(any(), any(), eq("morning-walk"), eq(true)))
                    .thenReturn("/blog/entry/morning-walk");
            when(urlStrategy.getWeblogEntryURL(any(), any(), eq("evening-ferry"), eq(true)))
                    .thenReturn("/blog/entry/evening-ferry");

            String content = bigModel().getContent(THE_FIFTEENTH);

            assertTrue(content.contains(
                            "<div class=\"hCalendarDayTitleBig\"><a href=\"/blog/date/20240515\">15</a></div>"),
                    "The day number should head the cell and link to that day's archive. "
                            + "Rendered:\n" + content);
            assertTrue(content.contains("<a href=\"/blog/entry/morning-walk\">Morning walk</a>"),
                    "Each post should be listed by title, linked to its permalink. "
                            + "Rendered:\n" + content);
            assertTrue(content.contains("<a href=\"/blog/entry/evening-ferry\">Evening ferry</a>"),
                    "Every post of the day must be listed, not just the first. "
                            + "Rendered:\n" + content);
        });
    }

    @Test
    void aDayWithoutPostsStillShowsItsDayNumber() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(Map.of());
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());

            String content = bigModel().getContent(THE_FIFTEENTH);

            assertTrue(content.contains("<div class=\"hCalendarDayTitleBig\">15</div>"),
                    "An empty day still needs its number, or the grid reads as blank. "
                            + "Rendered:\n" + content);
            assertFalse(content.contains("<a "),
                    "There is nothing to link to on an empty day. Rendered:\n" + content);
        });
    }

    /**
     * Titles are truncated so one long headline cannot stretch the cell and
     * break the grid. Twenty characters fit; twenty-one do not.
     */
    @Test
    void longTitlesAreTruncatedAtTwentyCharacters() {
        record Case(String title, String expected) { }
        List<Case> cases = List.of(
                new Case("1234567890123456789", "1234567890123456789"),      // 19: untouched
                new Case("12345678901234567890", "12345678901234567890"),    // 20: still fits
                new Case("123456789012345678901", "12345678901234567890..."), // 21: cut
                new Case("A very long title indeed", "A very long title in..."));

        for (Case testCase : cases) {
            withBusinessTier(() -> {
                when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(
                        Map.of(THE_FIFTEENTH, List.of(entry(testCase.title(), "anchor"))));
                when(entryManager.getWeblogEntries(any())).thenReturn(List.of());
                when(urlStrategy.getWeblogEntryURL(any(), any(), any(), eq(true)))
                        .thenReturn("/blog/entry/anchor");

                String content = bigModel().getContent(THE_FIFTEENTH);

                assertTrue(content.contains(">" + testCase.expected() + "</a>"),
                        "A " + testCase.title().length() + "-character title should be shown as \""
                                + testCase.expected() + "\". Titles of 20 characters or fewer "
                                + "are shown whole; longer ones are cut to 20 and elided. "
                                + "Rendered:\n" + content);
            });
        }
    }

    /**
     * Roller lets a post be saved without a title. Falling back to the anchor
     * keeps the cell clickable instead of rendering an invisible empty link.
     */
    @Test
    void anUntitledPostFallsBackToItsAnchor() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(
                    Map.of(THE_FIFTEENTH, List.of(entry("   ", "the-anchor"))));
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());
            when(urlStrategy.getWeblogEntryURL(any(), any(), eq("the-anchor"), eq(true)))
                    .thenReturn("/blog/entry/the-anchor");

            String content = bigModel().getContent(THE_FIFTEENTH);

            assertTrue(content.contains(">the-anchor</a>"),
                    "A post whose title is blank should be listed under its anchor, not as an "
                            + "empty link nobody can click. Rendered:\n" + content);
        });
    }

    /**
     * The big calendar's cell content is built from the entry's own publication
     * time, so the day link points at the day the post was published in the
     * weblog's zone.
     */
    @Test
    void theDayLinkUsesThePublicationDateOfTheFirstPost() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(
                    Map.of(THE_FIFTEENTH, List.of(entry("Post", "post"))));
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());
            when(urlStrategy.getWeblogEntryURL(any(), any(), any(), eq(true))).thenReturn("/e");

            bigModel().getContent(THE_FIFTEENTH);

            ArgumentCaptor<String> dateStamp = ArgumentCaptor.forClass(String.class);
            verify(urlStrategy).getWeblogCollectionURL(eq(weblog), any(), any(),
                    dateStamp.capture(), any(), eq(-1), eq(false));
            assertEquals("20240515", dateStamp.getValue(),
                    "The day heading must link to the 8-character stamp of the day the entry "
                            + "was published, read in the weblog's own time zone.");
        });
    }

    @Test
    void aDayWithPostsIsLinkedAndAnEmptyDayIsNot() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(
                    Map.of(THE_FIFTEENTH, List.of(entry("Post", "post"))));
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());
            when(urlStrategy.getWeblogCollectionURL(eq(weblog), any(), any(), eq("20240515"),
                    any(), eq(-1), eq(false))).thenReturn("/blog/date/20240515");

            BigWeblogCalendarModel model = bigModel();

            assertEquals("/blog/date/20240515", model.computeUrl(THE_FIFTEENTH, false, false),
                    "A day with posts must be linked.");
            assertNull(model.computeUrl(dateIn(2024, 5, 16), false, false),
                    "A day with no posts must not be linked unless a URL is demanded.");
        });
    }

    /**
     * When the big calendar sits on a custom page, only its month arrows keep
     * the reader on that page -- day links deliberately go back to the main
     * weblog, where the day's posts are actually rendered.
     */
    @Test
    void monthArrowsStayOnTheCustomPageWhileDayLinksReturnToTheWeblog() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(Map.of());
            when(entryManager.getWeblogEntries(any()))
                    .thenReturn(List.of(entryPublishedOn(2024, 4, 20)))
                    .thenReturn(List.of());
            when(urlStrategy.getWeblogPageURL(eq(weblog), any(), eq("archive"), isNull(),
                    any(), eq("202404"), isNull(), eq(-1), eq(false)))
                    .thenReturn("/blog/page/archive/202404");
            when(urlStrategy.getWeblogCollectionURL(eq(weblog), any(), any(), eq("20240515"),
                    any(), eq(-1), eq(false))).thenReturn("/blog/date/20240515");

            BigWeblogCalendarModel model = bigModel("archive");

            assertEquals("/blog/page/archive/202404", model.computePrevMonthUrl(),
                    "The back arrow of a calendar embedded in a custom page must keep the "
                            + "reader on that page.");
            assertEquals("/blog/date/20240515", model.computeUrl(THE_FIFTEENTH, false, true),
                    "A day link must go to the main weblog, which is where that day's entries "
                            + "are rendered.");
        });
    }

    /**
     * The big calendar loads whole entries rather than date stamps, but the
     * query is otherwise the archive calendar's: this month, this weblog, this
     * category, this language, published only.
     */
    @Test
    void theMonthQueryIsScopedTheSameWayAsTheSmallCalendars() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(Map.of());
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());

            new BigWeblogCalendarModel(pageRequestFor("20240515", null, "Travel"), null);

            ArgumentCaptor<WeblogEntrySearchCriteria> captor =
                    ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
            verify(entryManager).getWeblogEntryObjectMap(captor.capture());
            WeblogEntrySearchCriteria window = captor.getValue();

            assertEquals(instantIn(2024, 5, 1, 0, 0, 0, 0), window.getStartDate(),
                    "The query must start at the first millisecond of the month.");
            assertEquals(instantIn(2024, 5, 31, 23, 59, 59, 999), window.getEndDate(),
                    "The query must run to the last millisecond of the month.");
            assertSame(weblog, window.getWeblog(),
                    "The query must be scoped to this weblog.");
            assertEquals("Travel", window.getCatName(),
                    "The query must keep the category the calendar is scoped to.");
            assertEquals(REQUEST_LOCALE, window.getLocale(),
                    "The query must keep the request's language.");
            assertEquals(WeblogEntry.PubStatus.PUBLISHED, window.getStatus(),
                    "Draft titles must never be printed into a public calendar cell.");
        });
    }

    /**
     * Both of the big calendar's formatters are pinned to the weblog's zone at
     * construction. Left on the server's zone, a blog on the other side of the
     * date line shows the wrong day number in the cell and links it to the
     * wrong day's archive.
     */
    @Test
    void cellDatesAreFormattedInTheWeblogTimeZone() {
        TimeZone serverZone = TimeZone.getDefault();
        try {
            // Server twelve hours behind the weblog, so anything formatted in
            // the server's zone instead lands on the day before.
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Midway"));

            withBusinessTier(() -> {
                weblog.setTimeZone("Pacific/Kiritimati");
                // 2024-05-16T03:00 in Kiritimati; still the 15th on the server.
                Date afterMidnight = Date.from(Instant.parse("2024-05-15T13:00:00Z"));
                when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(Map.of(
                        afterMidnight,
                        List.of(entryPublishedAt("Post", "post", afterMidnight))));
                when(entryManager.getWeblogEntries(any())).thenReturn(List.of());
                when(urlStrategy.getWeblogEntryURL(any(), any(), any(), eq(true)))
                        .thenReturn("/e");
                when(urlStrategy.getWeblogCollectionURL(eq(weblog), any(), any(), eq("20240516"),
                        any(), eq(-1), eq(false))).thenReturn("/blog/date/20240516");

                String content = bigModel().getContent(afterMidnight);

                assertTrue(content.contains(">16</a>"),
                        "For a Kiritimati blog 2024-05-15T13:00Z is already the 16th, so the "
                                + "cell must be numbered 16. Numbering it 15 means the day "
                                + "formatter is still on the server's zone. Rendered:\n" + content);
                assertTrue(content.contains("href=\"/blog/date/20240516\""),
                        "The day link must use the same date the cell is numbered with, read "
                                + "in the weblog's zone. Rendered:\n" + content);
            });
        } finally {
            TimeZone.setDefault(serverZone);
        }
    }

    /**
     * Cell markup and day links are both built through the URL strategy. If it
     * throws, the calendar must degrade rather than take the blog page with it.
     */
    @Test
    void aFailingUrlStrategyDegradesTheCellRatherThanBreakingThePage() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(
                    Map.of(THE_FIFTEENTH, List.of(entry("Post", "post"))));
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());
            when(urlStrategy.getWeblogCollectionURL(any(), any(), any(), any(), any(), anyInt(),
                    anyBoolean())).thenThrow(new IllegalStateException("no url strategy"));

            BigWeblogCalendarModel model = bigModel();

            assertNull(model.getContent(THE_FIFTEENTH),
                    "With no URL to build the cell around there is no cell markup, but the "
                            + "failure must not escape getContent().");
            assertNull(model.computeUrl(THE_FIFTEENTH, false, false),
                    "A URL strategy failure must leave the day unlinked rather than propagate "
                            + "out of the calendar.");
        });
    }

    @Test
    void aFailingEntryLookupLeavesAnEmptyMonth() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());
            when(entryManager.getWeblogEntryObjectMap(any()))
                    .thenThrow(new org.apache.roller.weblogger.WebloggerException("database down"));

            BigWeblogCalendarModel model = bigModel();

            assertNull(model.computeUrl(THE_FIFTEENTH, false, false),
                    "With no entries loaded no day is linked, and asking must not throw or the "
                            + "whole blog page fails to render.");
            assertTrue(model.getContent(THE_FIFTEENTH).contains("15"),
                    "An empty month should still render day numbers.");
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private BigWeblogCalendarModel bigModel() {
        return bigModel(null);
    }

    private BigWeblogCalendarModel bigModel(String pageName) {
        return new BigWeblogCalendarModel(pageRequestFor("20240515", pageName, null), null);
    }

    private WeblogEntry entry(String title, String anchor) {
        return entryPublishedAt(title, anchor, THE_FIFTEENTH);
    }

    private WeblogEntry entryPublishedAt(String title, String anchor, Date pubTime) {
        WeblogEntry entry = new WeblogEntry();
        entry.setTitle(title);
        entry.setAnchor(anchor);
        entry.setWebsite(weblog);
        entry.setPubTime(new Timestamp(pubTime.getTime()));
        return entry;
    }

    private WeblogEntry entryPublishedOn(int year, int month, int day) {
        WeblogEntry entry = new WeblogEntry();
        entry.setPubTime(new Timestamp(dateIn(year, month, day).getTime()));
        return entry;
    }
}
