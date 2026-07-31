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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the data side of the archive calendar: which month is being shown,
 * which months the previous/next arrows lead to, and what a day cell links to.
 *
 * <p>The date parsing here reads straight off the URL a visitor typed, so it
 * has to cope with junk, with dates that do not exist, and with dates in the
 * future. The month-window arithmetic decides which entries the calendar can
 * see at all: a boundary that is off by a millisecond silently hides the first
 * or last post of the month.
 */
class WeblogCalendarModelTest extends CalendarModelTestSupport {

    // ------------------------------------------------------------------
    // URL date parsing -- pure, no business tier needed
    // ------------------------------------------------------------------

    @Test
    void eightCharacterUrlDatesSelectThatDay() {
        Date parsed = WeblogCalendarModel.parseWeblogURLDateString(
                "20240229", TimeZone.getTimeZone("UTC"), Locale.US);

        assertEquals(LocalDate.of(2024, 2, 29), toLocalDate(parsed, "UTC"),
                "/date/20240229 must select February 29th 2024. A leap day that does not "
                        + "parse makes a whole day of the archive unreachable.");
    }

    @Test
    void sixCharacterUrlDatesSelectTheFirstOfThatMonth() {
        Date parsed = WeblogCalendarModel.parseWeblogURLDateString(
                "202402", TimeZone.getTimeZone("UTC"), Locale.US);

        assertEquals(LocalDate.of(2024, 2, 1), toLocalDate(parsed, "UTC"),
                "/date/202402 selects a month, which the calendar represents by its first day.");
    }

    /** The date string is interpreted in the weblog's zone, not the server's. */
    @Test
    void urlDatesAreInterpretedInTheWeblogTimeZone() {
        Date tokyo = WeblogCalendarModel.parseWeblogURLDateString(
                "20240301", TimeZone.getTimeZone("Asia/Tokyo"), Locale.US);
        Date newYork = WeblogCalendarModel.parseWeblogURLDateString(
                "20240301", TimeZone.getTimeZone("America/New_York"), Locale.US);

        assertEquals(LocalDate.of(2024, 3, 1), toLocalDate(tokyo, "Asia/Tokyo"),
                "March 1st in Tokyo must be March 1st for a Tokyo weblog.");
        assertEquals(LocalDate.of(2024, 3, 1), toLocalDate(newYork, "America/New_York"),
                "March 1st in New York must be March 1st for a New York weblog.");
        assertTrue(tokyo.before(newYork),
                "Midnight in Tokyo happens before midnight in New York, so the two instants "
                        + "must differ. Identical instants mean the time zone is being ignored.");
    }

    /**
     * A visitor can type any date into the URL. Anything that is not a
     * plausible date stamp falls back to today rather than throwing or
     * rendering an empty calendar for the year 0001.
     */
    @Test
    void unusableUrlDatesFallBackToToday() {
        List<String> rejected = java.util.Arrays.asList(
                null,           // no date segment in the URL at all
                "",             // empty segment
                "2024",         // year only -- neither 6 nor 8 characters
                "2024022",      // 7 characters, a typo away from a real date
                "202402290",    // 9 characters
                "2024feb",      // not numeric
                "2024-02-29");  // not numeric once the dashes are counted

        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        for (String candidate : rejected) {
            Date parsed = WeblogCalendarModel.parseWeblogURLDateString(
                    candidate, TimeZone.getTimeZone("UTC"), Locale.US);

            assertEquals(today, toLocalDate(parsed, "UTC"),
                    "\"" + candidate + "\" is not a usable 6- or 8-character date stamp, so "
                            + "the calendar should open on today rather than on a date parsed "
                            + "out of garbage.");
        }
    }

    /**
     * Roller never shows a month it has not reached yet, so a hand-typed future
     * date is clamped back to now.
     */
    @Test
    void futureUrlDatesAreClampedToToday() {
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        String nextYear = String.format("%04d0101", today.getYear() + 1);

        Date parsed = WeblogCalendarModel.parseWeblogURLDateString(
                nextYear, TimeZone.getTimeZone("UTC"), Locale.US);

        assertEquals(today, toLocalDate(parsed, "UTC"),
                "/date/" + nextYear + " is in the future; the calendar must clamp it to today "
                        + "instead of showing an empty month nobody has posted in yet.");
    }

    @Test
    void futureMonthStampsAreClampedToToday() {
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        String nextYear = String.format("%04d01", today.getYear() + 1);

        Date parsed = WeblogCalendarModel.parseWeblogURLDateString(
                nextYear, TimeZone.getTimeZone("UTC"), Locale.US);

        assertEquals(today, toLocalDate(parsed, "UTC"),
                "A 6-character future month must be clamped just like an 8-character one.");
    }

    @Test
    void dateStampFormattersUseTheSuppliedCalendar() {
        Calendar tokyo = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"), Locale.US);
        // 2024-03-01T00:30 Tokyo is still 2024-02-29 in UTC.
        Date instant = Date.from(
                ZonedDateTime.of(2024, 3, 1, 0, 30, 0, 0, ZoneId.of("Asia/Tokyo")).toInstant());

        assertEquals("20240301", WeblogCalendarModel.format8chars(instant, tokyo),
                "The 8-character day stamp in a link must be the day in the weblog's zone, "
                        + "otherwise a late-evening post links to the wrong day's archive.");
        assertEquals("202403", WeblogCalendarModel.format6chars(instant, tokyo),
                "The 6-character month stamp must likewise follow the weblog's zone.");
    }

    // ------------------------------------------------------------------
    // Month window and navigation
    // ------------------------------------------------------------------

    /**
     * The calendar asks for the entries of exactly one month. Both ends of that
     * window are boundaries: a window that starts a millisecond late loses the
     * first post of the month.
     */
    @Test
    void theMonthWindowCoversTheWholeMonthAndNothingMore() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryStringMap(any())).thenReturn(Map.of());
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());

            new WeblogCalendarModel(pageRequestFor("20240515", null, "Travel"), null);

            WeblogEntrySearchCriteria window = lastStringMapCriteria();
            assertEquals(instantIn(2024, 5, 1, 0, 0, 0, 0), window.getStartDate(),
                    "The entry query must start at the first millisecond of the 1st, in the "
                            + "weblog's zone. Starting any later loses the first post of the "
                            + "month from the calendar.");
            assertEquals(instantIn(2024, 5, 31, 23, 59, 59, 999), window.getEndDate(),
                    "The entry query must run to the last millisecond of the last day. May has "
                            + "31 days; ending earlier loses the final post of the month.");
            assertEquals(WeblogEntry.PubStatus.PUBLISHED, window.getStatus(),
                    "Drafts must never appear in a public archive calendar.");
            assertSame(weblog, window.getWeblog(),
                    "The query must be scoped to this weblog, or one blog's calendar shows "
                            + "another blog's posts.");
            assertEquals(REQUEST_LOCALE, window.getLocale(),
                    "The query must keep the request's language, so a French reader's calendar "
                            + "does not fill up with posts in other languages.");
            assertEquals("Travel", window.getCatName(),
                    "The query must keep the category the calendar is scoped to.");
        });
    }

    /**
     * The two searches that find the previous and next non-empty months are
     * bounded a single millisecond outside the displayed month. One millisecond
     * the wrong way and a post published at the very start or end of the month
     * makes the arrows point back at the month already on screen.
     */
    @Test
    void theSearchesForNeighbouringMonthsStopOneMillisecondOutsideThisOne() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryStringMap(any())).thenReturn(Map.of());
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());

            new WeblogCalendarModel(pageRequestFor("20240515", null, "Travel"), null);

            List<WeblogEntrySearchCriteria> searches = neighbourMonthSearches();
            WeblogEntrySearchCriteria backwards = searches.get(0);
            WeblogEntrySearchCriteria forwards = searches.get(1);

            assertEquals(new Date(instantIn(2024, 5, 1, 0, 0, 0, 0).getTime() - 1),
                    backwards.getEndDate(),
                    "The backward search must end one millisecond before the displayed month "
                            + "starts. Ending at the start of the month instead would match a "
                            + "post published at midnight on the 1st and point the back arrow "
                            + "at the month already being shown.");
            assertNull(backwards.getStartDate(),
                    "The backward search must be open-ended, or the arrow cannot reach a blog's "
                            + "oldest posts.");
            assertEquals(WeblogEntrySearchCriteria.SortOrder.DESCENDING, backwards.getSortOrder(),
                    "The back arrow wants the newest of the earlier entries.");

            assertEquals(new Date(instantIn(2024, 5, 31, 23, 59, 59, 999).getTime() + 1),
                    forwards.getStartDate(),
                    "The forward search must start one millisecond after the displayed month "
                            + "ends, or a post from the last millisecond of the month makes the "
                            + "forward arrow point back at this month.");
            assertNull(forwards.getEndDate(),
                    "The forward search must be open-ended.");
            assertEquals(WeblogEntrySearchCriteria.SortOrder.ASCENDING, forwards.getSortOrder(),
                    "The forward arrow wants the oldest of the later entries. Searching "
                            + "descending would jump the reader to the newest month on the blog "
                            + "rather than the next one.");

            for (WeblogEntrySearchCriteria search : searches) {
                assertEquals(1, search.getMaxResults(),
                        "Only the nearest entry is needed; fetching the lot would load an "
                                + "entire blog's history to draw two arrows.");
                assertSame(weblog, search.getWeblog(),
                        "A neighbouring-month search must be scoped to this weblog.");
                assertEquals(WeblogEntry.PubStatus.PUBLISHED, search.getStatus(),
                        "An unpublished draft in a neighbouring month must not light up an "
                                + "arrow that leads to an empty page.");
                assertEquals("Travel", search.getCatName(),
                        "A calendar scoped to a category must page through that category only.");
                assertEquals(REQUEST_LOCALE, search.getLocale(),
                        "A neighbouring-month search must keep the request's language.");
            }
        });
    }

    /** February 2024 has 29 days; the window has to stretch to cover the leap day. */
    @Test
    void theMonthWindowStretchesOverTheLeapDay() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryStringMap(any())).thenReturn(Map.of());
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());

            new WeblogCalendarModel(pageRequestFor("20240210", null, null), null);

            assertEquals(LocalDate.of(2024, 2, 29),
                    toLocalDate(lastStringMapCriteria().getEndDate(), ZONE),
                    "February 2024 ends on the 29th. A window ending on the 28th would hide "
                            + "any post made on the leap day.");
        });
    }

    /**
     * The back and forward arrows jump to the nearest month that actually has
     * entries, not simply to the adjacent month -- otherwise readers page
     * through empty months one at a time.
     */
    @Test
    void navigationArrowsPointAtTheNearestNonEmptyMonths() {
        withBusinessTier(() -> {
            WeblogEntry older = entryPublishedOn(2023, 11, 14);
            WeblogEntry newer = entryPublishedOn(2024, 8, 2);
            when(entryManager.getWeblogEntries(any()))
                    .thenReturn(List.of(older))
                    .thenReturn(List.of(newer));
            when(entryManager.getWeblogEntryStringMap(any())).thenReturn(Map.of());

            WeblogCalendarModel model =
                    new WeblogCalendarModel(pageRequestFor("20240515", null, null), null);

            assertEquals(LocalDate.of(2023, 11, 1), toLocalDate(model.getPrevMonth(), ZONE),
                    "The back arrow should lead to the start of November 2023, the month of "
                            + "the newest entry before this one.");
            assertEquals(LocalDate.of(2024, 8, 1), toLocalDate(model.getNextMonth(), ZONE),
                    "The forward arrow should lead to the start of August 2024, the month of "
                            + "the oldest entry after this one.");
        });
    }

    @Test
    void navigationArrowsAreAbsentWhenThereAreNoOtherEntries() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());
            when(entryManager.getWeblogEntryStringMap(any())).thenReturn(Map.of());

            WeblogCalendarModel model =
                    new WeblogCalendarModel(pageRequestFor("20240515", null, null), null);

            assertNull(model.getPrevMonth(),
                    "With no earlier entries there is nothing to page back to, so no back "
                            + "arrow should be offered.");
            assertNull(model.getNextMonth(),
                    "With no later entries there is nothing to page forward to.");
        });
    }

    /**
     * ROL-840: showing the current month must not reveal posts scheduled for
     * later today, and must not offer a forward arrow into the future.
     */
    @Test
    void theCurrentMonthIsTruncatedAtNowSoScheduledPostsStayHidden() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntries(any()))
                    .thenReturn(List.of())
                    .thenReturn(List.of(entryPublishedOn(2999, 1, 1)));
            when(entryManager.getWeblogEntryStringMap(any())).thenReturn(Map.of());

            Date beforeConstruction = new Date();
            WeblogCalendarModel model =
                    new WeblogCalendarModel(pageRequestFor(null, null, null), null);

            Date windowEnd = lastStringMapCriteria().getEndDate();
            assertTrue(!windowEnd.before(beforeConstruction),
                    "The current month's window should end at 'now', but ended at " + windowEnd
                            + " which is before this test started.");
            assertTrue(windowEnd.before(new Date(beforeConstruction.getTime() + 60_000L)),
                    "The current month's window ends at " + windowEnd + ", well after now. "
                            + "Entries scheduled for later today would show up in the calendar.");
            assertNull(model.getNextMonth(),
                    "The month being shown has not finished yet, so there is no next month to "
                            + "page forward into, whatever the entry search turned up.");
        });
    }

    // ------------------------------------------------------------------
    // Cell URLs
    // ------------------------------------------------------------------

    @Test
    void daysWithoutEntriesGetNoLink() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryStringMap(any())).thenReturn(Map.of());
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());

            WeblogCalendarModel model =
                    new WeblogCalendarModel(pageRequestFor("20240515", null, null), null);

            assertNull(model.computeUrl(dateIn(2024, 5, 15), false, false),
                    "A day with no entries must not be linked, or every empty square in the "
                            + "calendar leads to an empty archive page.");
        });
    }

    @Test
    void daysWithEntriesLinkToThatDaysArchive() {
        withBusinessTier(() -> {
            Date theFifteenth = dateIn(2024, 5, 15);
            when(entryManager.getWeblogEntryStringMap(any()))
                    .thenReturn(Map.of(theFifteenth, "20240515"));
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());
            when(urlStrategy.getWeblogCollectionURL(eq(weblog), any(), any(), eq("20240515"),
                    any(), eq(-1), eq(false))).thenReturn("/blog/date/20240515");

            WeblogCalendarModel model =
                    new WeblogCalendarModel(pageRequestFor("20240515", null, null), null);

            assertEquals("/blog/date/20240515", model.computeUrl(theFifteenth, false, false),
                    "A day the entry map knows about must link to that day's archive.");
        });
    }

    /**
     * The next/prev arrows always need a URL even though those months are not
     * in the entry map, and they address a month (6 chars), not a day (8).
     */
    @Test
    void monthNavigationUrlsUseSixCharacterDateStamps() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryStringMap(any())).thenReturn(Map.of());
            when(entryManager.getWeblogEntries(any()))
                    .thenReturn(List.of(entryPublishedOn(2024, 4, 20)))
                    .thenReturn(List.of(entryPublishedOn(2024, 6, 3)));
            when(urlStrategy.getWeblogCollectionURL(eq(weblog), any(), any(), eq("202404"),
                    any(), eq(-1), eq(false))).thenReturn("/blog/date/202404");
            when(urlStrategy.getWeblogCollectionURL(eq(weblog), any(), any(), eq("202406"),
                    any(), eq(-1), eq(false))).thenReturn("/blog/date/202406");

            WeblogCalendarModel model =
                    new WeblogCalendarModel(pageRequestFor("20240515", null, null), null);

            assertEquals("/blog/date/202404", model.computePrevMonthUrl(),
                    "The back arrow must link to April 2024, the previous non-empty month.");
            assertEquals("/blog/date/202406", model.computeNextMonthUrl(),
                    "The forward arrow must link to June 2024, the next non-empty month.");

            ArgumentCaptor<String> dateStamp = ArgumentCaptor.forClass(String.class);
            verify(urlStrategy, times(2)).getWeblogCollectionURL(eq(weblog), any(), any(),
                    dateStamp.capture(), any(), eq(-1), eq(false));
            assertEquals(List.of("202404", "202406"), dateStamp.getAllValues(),
                    "Month navigation must produce YYYYMM stamps for the previous and next "
                            + "non-empty months. An 8-character stamp would drop the reader on "
                            + "a single day instead of the month.");
        });
    }

    @Test
    void dayUrlsAreProducedForDaysWithoutEntriesWhenTheCallerInsists() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryStringMap(any())).thenReturn(Map.of());
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());

            WeblogCalendarModel model =
                    new WeblogCalendarModel(pageRequestFor("20240515", null, null), null);
            model.computeUrl(dateIn(2024, 5, 15), false, true);

            ArgumentCaptor<String> dateStamp = ArgumentCaptor.forClass(String.class);
            verify(urlStrategy).getWeblogCollectionURL(eq(weblog), any(), any(),
                    dateStamp.capture(), any(), eq(-1), eq(false));
            assertEquals("20240515", dateStamp.getValue(),
                    "When a URL is demanded for a day with no entries, it must still be that "
                            + "day's 8-character stamp.");
        });
    }

    /**
     * A calendar embedded in a custom page must keep the reader on that page,
     * so its links go through the page URL rather than the main weblog URL.
     */
    @Test
    void aCalendarOnACustomPageLinksBackToThatPage() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryStringMap(any())).thenReturn(Map.of());
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());
            when(urlStrategy.getWeblogPageURL(eq(weblog), any(), eq("archive"), isNull(),
                    any(), any(), isNull(), eq(-1), eq(false))).thenReturn("/blog/page/archive");

            WeblogCalendarModel model =
                    new WeblogCalendarModel(pageRequestFor("20240515", "archive", null), null);

            assertEquals("/blog/page/archive", model.computeUrl(dateIn(2024, 5, 15), false, true),
                    "A calendar rendered inside a custom page must link through that page, "
                            + "not bounce the reader to the main weblog URL.");
            assertEquals("/blog/page/archive", model.computeTodayMonthUrl(),
                    "The 'Today' link must stay on the custom page too.");
        });
    }

    @Test
    void theTodayLinkCarriesNoDate() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryStringMap(any())).thenReturn(Map.of());
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());

            new WeblogCalendarModel(pageRequestFor("20240515", null, null), null)
                    .computeTodayMonthUrl();

            verify(urlStrategy).getWeblogCollectionURL(eq(weblog), any(), any(), isNull(),
                    isNull(), eq(-1), eq(false));
        });
    }

    // ------------------------------------------------------------------
    // Category filtering and model state
    // ------------------------------------------------------------------

    /**
     * A calendar can be scoped to a category two ways -- from the URL or from
     * the template macro. The macro argument wins, because a template author
     * asking for one category should not be overridden by the reader's URL.
     */
    @Test
    void theCategoryArgumentOverridesTheCategoryInTheUrl() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryStringMap(any())).thenReturn(Map.of());
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());

            new WeblogCalendarModel(pageRequestFor("20240515", null, "FromUrl"), "FromTemplate");

            assertEquals("FromTemplate", lastStringMapCriteria().getCatName(),
                    "The category passed to the calendar macro must win over the one in the "
                            + "request URL.");
        });
    }

    @Test
    void theCategoryFromTheUrlIsUsedWhenTheTemplateDoesNotSpecifyOne() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryStringMap(any())).thenReturn(Map.of());
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());

            new WeblogCalendarModel(pageRequestFor("20240515", null, "FromUrl"), null);

            assertEquals("FromUrl", lastStringMapCriteria().getCatName(),
                    "With no macro argument the calendar should stay scoped to the category "
                            + "the reader is browsing.");
        });
    }

    @Test
    void theDayBeingDisplayedIsHandedOutAsACopy() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryStringMap(any())).thenReturn(Map.of());
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());

            WeblogCalendarModel model =
                    new WeblogCalendarModel(pageRequestFor("20240515", null, null), null);

            Date first = model.getDay();
            first.setTime(0);

            assertNotSame(first, model.getDay(), "getDay() must not hand out its own field");
            assertEquals(LocalDate.of(2024, 5, 15), toLocalDate(model.getDay(), ZONE),
                    "Mutating the Date returned by getDay() moved the month the calendar is "
                            + "showing. The model must return a defensive copy.");
        });
    }

    @Test
    void theCalendarHandedToTheTagIsAFreshInstanceInTheWeblogZone() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryStringMap(any())).thenReturn(Map.of());
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());

            WeblogCalendarModel model =
                    new WeblogCalendarModel(pageRequestFor("20240515", null, null), null);

            Calendar first = model.getCalendar();
            Calendar second = model.getCalendar();

            assertNotSame(first, second,
                    "CalendarTag mutates the calendars it is given, so each call must return "
                            + "a separate instance or the grid corrupts its own state.");
            assertEquals(TimeZone.getTimeZone(ZONE), first.getTimeZone(),
                    "The calendar must carry the weblog's time zone.");
            assertEquals(Calendar.MONDAY, first.getFirstDayOfWeek(),
                    "The weblog locale is fr-FR, so its weeks start on Monday.");
        });
    }

    @Test
    void switchingTheDisplayedMonthReloadsThatMonthsEntries() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryStringMap(any())).thenReturn(Map.of());
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());

            WeblogCalendarModel model =
                    new WeblogCalendarModel(pageRequestFor("20240515", null, null), null);
            model.setDay("20231101");

            WeblogEntrySearchCriteria window = lastStringMapCriteria();
            assertEquals(LocalDate.of(2023, 11, 1), toLocalDate(window.getStartDate(), ZONE),
                    "setDay() must re-run the month query for the newly selected month.");
            assertEquals(LocalDate.of(2023, 11, 30), toLocalDate(window.getEndDate(), ZONE),
                    "November has 30 days.");
        });
    }

    /**
     * setDay() parses an 8-character stamp from the URL, and must read it in
     * the weblog's zone. Reading it in the server's zone puts a blog far enough
     * east or west on the wrong side of midnight and shows the wrong month.
     */
    @Test
    void switchingMonthsParsesTheDateInTheWeblogZone() {
        TimeZone serverZone = TimeZone.getDefault();
        try {
            // Server a day ahead of the weblog, so a date parsed in the
            // server's zone lands in the previous month for the weblog.
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));

            withBusinessTier(() -> {
                weblog.setTimeZone("Pacific/Midway");
                when(entryManager.getWeblogEntryStringMap(any())).thenReturn(Map.of());
                when(entryManager.getWeblogEntries(any())).thenReturn(List.of());

                WeblogCalendarModel model =
                        new WeblogCalendarModel(pageRequestFor("20240515", null, null), null);
                model.setDay("20231101");

                assertEquals(LocalDate.of(2023, 11, 1),
                        toLocalDate(lastStringMapCriteria().getStartDate(), "Pacific/Midway"),
                        "November 1st for a Midway blog starts at midnight in Midway. Parsed "
                                + "in the server's zone instead, the same string is still "
                                + "October 31st for the weblog and the calendar reloads the "
                                + "wrong month.");
            });
        } finally {
            TimeZone.setDefault(serverZone);
        }
    }

    /**
     * The raw date stamp behind a day, exposed for templates that want to build
     * their own link rather than take the one computeUrl() produces.
     */
    @Test
    void theDateStampForADayIsAvailableOnItsOwn() {
        withBusinessTier(() -> {
            Date theFifteenth = dateIn(2024, 5, 15);
            when(entryManager.getWeblogEntryStringMap(any()))
                    .thenReturn(Map.of(theFifteenth, "20240515"));
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());

            WeblogCalendarModel model =
                    new WeblogCalendarModel(pageRequestFor("20240515", null, null), null);

            assertEquals("20240515", model.getParameterValue(theFifteenth),
                    "A day with entries must report the date stamp the entry map holds for it.");
            assertNull(model.getParameterValue(dateIn(2024, 5, 16)),
                    "A day with no entries has no date stamp.");
        });
    }

    /** The small calendar shows numbers only; cell markup is the big calendar's job. */
    @Test
    void theSmallCalendarSuppliesNoCellContent() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntryStringMap(any())).thenReturn(Map.of());
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());

            WeblogCalendarModel model =
                    new WeblogCalendarModel(pageRequestFor("20240515", null, null), null);

            assertNull(model.getContent(dateIn(2024, 5, 15)),
                    "Returning content here would make CalendarTag replace every day number "
                            + "with it.");
        });
    }

    /**
     * A database hiccup while looking for neighbouring months must cost the
     * reader the arrows, not the page. The calendar is drawn on every request
     * to a blog's home page.
     */
    @Test
    void aFailingNeighbourMonthLookupLeavesTheArrowsOffButKeepsTheMonth() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntries(any()))
                    .thenThrow(new org.apache.roller.weblogger.WebloggerException("database down"));
            when(entryManager.getWeblogEntryStringMap(any())).thenReturn(Map.of());

            WeblogCalendarModel model =
                    new WeblogCalendarModel(pageRequestFor("20240515", null, null), null);

            assertNull(model.getPrevMonth(),
                    "The backward search failed, so no back arrow can be offered.");
            assertNull(model.getNextMonth(),
                    "The forward search failed, so no forward arrow can be offered.");
            assertEquals(LocalDate.of(2024, 5, 15), toLocalDate(model.getDay(), ZONE),
                    "The month itself must still load; only the arrows are lost.");
        });
    }

    /**
     * The URL strategy is called once per linked day. If it throws, the day
     * loses its link -- the alternative is a broken blog page.
     */
    @Test
    void aFailingUrlStrategyLeavesTheDayUnlinkedRatherThanBreakingThePage() {
        withBusinessTier(() -> {
            Date theFifteenth = dateIn(2024, 5, 15);
            when(entryManager.getWeblogEntryStringMap(any()))
                    .thenReturn(Map.of(theFifteenth, "20240515"));
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());
            when(urlStrategy.getWeblogCollectionURL(any(), any(), any(), any(), any(), anyInt(),
                    anyBoolean())).thenThrow(new IllegalStateException("no url strategy"));

            WeblogCalendarModel model =
                    new WeblogCalendarModel(pageRequestFor("20240515", null, null), null);

            assertNull(model.computeUrl(theFifteenth, false, false),
                    "A URL strategy failure must leave the day unlinked, not propagate out of "
                            + "the calendar and take the whole blog page down with it.");
        });
    }

    /**
     * The manager throwing must leave an empty month rather than a model with a
     * null map that blows up on the first cell the tag asks about.
     */
    @Test
    void aFailingEntryLookupLeavesAnEmptyMonthRatherThanABrokenModel() {
        withBusinessTier(() -> {
            when(entryManager.getWeblogEntries(any())).thenReturn(List.of());
            when(entryManager.getWeblogEntryStringMap(any()))
                    .thenThrow(new org.apache.roller.weblogger.WebloggerException("database down"));

            WeblogCalendarModel model =
                    new WeblogCalendarModel(pageRequestFor("20240515", null, null), null);

            assertNull(model.computeUrl(dateIn(2024, 5, 15), false, false),
                    "With no entries loaded, no day should be linked -- and asking must not "
                            + "throw, or the whole blog page fails to render.");
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private WeblogEntry entryPublishedOn(int year, int month, int day) {
        WeblogEntry entry = new WeblogEntry();
        entry.setPubTime(new Timestamp(dateIn(year, month, day).getTime()));
        return entry;
    }

    private WeblogEntrySearchCriteria lastStringMapCriteria() {
        ArgumentCaptor<WeblogEntrySearchCriteria> captor =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        try {
            verify(entryManager, org.mockito.Mockito.atLeastOnce())
                    .getWeblogEntryStringMap(captor.capture());
        } catch (Exception e) {
            throw new AssertionError("entry lookup was never performed", e);
        }
        List<WeblogEntrySearchCriteria> all = captor.getAllValues();
        assertTrue(!all.isEmpty(), "The calendar never asked for the month's entries");
        return all.get(all.size() - 1);
    }

    /** The backward search followed by the forward search, in that order. */
    private List<WeblogEntrySearchCriteria> neighbourMonthSearches() {
        ArgumentCaptor<WeblogEntrySearchCriteria> captor =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        try {
            verify(entryManager, times(2)).getWeblogEntries(captor.capture());
        } catch (Exception e) {
            throw new AssertionError("neighbouring-month searches were not performed", e);
        }
        return captor.getAllValues();
    }
}
