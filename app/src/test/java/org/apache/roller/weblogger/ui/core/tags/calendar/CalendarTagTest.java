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

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspWriter;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.tagext.Tag;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the month grid {@link CalendarTag} draws on every blog page that shows
 * the archive calendar.
 *
 * <p>The tag walks back from the first of the month to the start of that week,
 * then steps forward one day at a time for six weeks. Every one of those steps
 * is a place an off-by-one can hide: the wrong week start silently rotates the
 * whole grid, a month-boundary slip drops or duplicates a day, and leap years
 * and daylight-saving shifts only bite on a handful of days a year -- the sort
 * of bug that survives for years because nobody looks at February 29th.
 *
 * <p>Rather than scrape the HTML for day numbers, these tests hand the tag a
 * {@link RecordingCalendarModel}, which records the cell date the tag asks
 * about for each of the 42 cells. That list <em>is</em> the grid.
 */
class CalendarTagTest {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    /** Six week rows of seven days. Anything else is a broken grid. */
    private static final int CELLS_IN_GRID = 42;

    /**
     * Day cells, and only day cells: the month/year title and the "Today"
     * footer are also {@code <td>}s but lead with {@code colspan}, not
     * {@code class}.
     */
    private static final Pattern DAY_CELL =
            Pattern.compile("<td class=\"([^\"]+)\">(.*?)</td>", Pattern.DOTALL);

    private static final Pattern DAY_HEADING =
            Pattern.compile("<th class=\"[^\"]+\" align=\"center\">(.*?)</th>", Pattern.DOTALL);

    // ------------------------------------------------------------------
    // Grid arithmetic
    // ------------------------------------------------------------------

    /**
     * A month whose first day lands on each of the seven weekdays in turn. The
     * grid must always begin on the locale's first day of the week, on or
     * before the first of the month -- never after it, which would push day 1
     * off the top of the calendar, and never a whole week before it, which
     * would waste a row and drop the last days of the month.
     */
    @Test
    void gridStartsOnTheWeekStartOnOrBeforeTheFirstOfTheMonth() {
        record Case(int year, int month, DayOfWeek firstOfMonthFallsOn) { }
        List<Case> cases = List.of(
                new Case(2024, 9, DayOfWeek.SUNDAY),
                new Case(2024, 1, DayOfWeek.MONDAY),
                new Case(2024, 10, DayOfWeek.TUESDAY),
                new Case(2024, 5, DayOfWeek.WEDNESDAY),
                new Case(2024, 2, DayOfWeek.THURSDAY),
                new Case(2024, 3, DayOfWeek.FRIDAY),
                new Case(2024, 6, DayOfWeek.SATURDAY));

        for (Case testCase : cases) {
            LocalDate firstOfMonth = LocalDate.of(testCase.year(), testCase.month(), 1);
            // Guard the fixture: if these months ever stop starting on the
            // weekday the case claims, the case stops testing what it says.
            assertEquals(testCase.firstOfMonthFallsOn(), firstOfMonth.getDayOfWeek(),
                    "Fixture is wrong: " + firstOfMonth + " does not start on a "
                            + testCase.firstOfMonthFallsOn() + ". Pick a month that does.");

            List<LocalDate> grid = renderGrid(testCase.year(), testCase.month(), UTC, Locale.US);
            LocalDate firstCell = grid.get(0);

            assertEquals(DayOfWeek.SUNDAY, firstCell.getDayOfWeek(),
                    "US calendars start their weeks on Sunday, so the top-left cell of the "
                            + firstOfMonth + " grid must be a Sunday. It was " + firstCell
                            + " (a " + firstCell.getDayOfWeek() + "). Check the back-up loop "
                            + "in CalendarTag.doStartTag.");
            assertFalse(firstCell.isAfter(firstOfMonth),
                    "The grid for " + firstOfMonth + " starts at " + firstCell + ", after the "
                            + "1st, so the first days of the month are missing from the "
                            + "calendar.");
            assertTrue(firstCell.isAfter(firstOfMonth.minusDays(7)),
                    "The grid for " + firstOfMonth + " starts at " + firstCell + ", a full week "
                            + "or more before the 1st. The back-up loop overshot.");
        }
    }

    /**
     * The locale, not the time zone, decides which weekday heads the grid.
     * Getting this wrong rotates every date in the calendar by a day or more,
     * so readers click a column and get the wrong day's entries.
     */
    @Test
    void weekStartFollowsTheWeblogLocale() {
        record Case(String languageTag, DayOfWeek expectedWeekStart) { }
        List<Case> cases = List.of(
                new Case("en-US", DayOfWeek.SUNDAY),
                new Case("fr-FR", DayOfWeek.MONDAY),
                new Case("ar-EG", DayOfWeek.SATURDAY));

        for (Case testCase : cases) {
            Locale locale = Locale.forLanguageTag(testCase.languageTag());

            List<LocalDate> grid = renderGrid(2024, 1, UTC, locale);

            assertEquals(testCase.expectedWeekStart(), grid.get(0).getDayOfWeek(),
                    "With locale " + testCase.languageTag() + " the calendar's first column "
                            + "should be " + testCase.expectedWeekStart() + " but the grid "
                            + "began on a " + grid.get(0).getDayOfWeek() + ". CalendarTag must "
                            + "take the week start from the model's calendar, which carries "
                            + "the weblog locale.");
            assertEquals(testCase.expectedWeekStart(), grid.get(7).getDayOfWeek(),
                    "For locale " + testCase.languageTag() + " the second row does not start "
                            + "on the same weekday as the first, so the rows are not aligned "
                            + "into columns.");
        }
    }

    /**
     * Exact corners for a month that happens to start on the week start, so
     * there are no leading blanks to reason about. These dates are hand
     * computed, not derived from the Calendar code under test.
     */
    @Test
    void firstAndLastCellsArePinnedForSeptember2024() {
        List<LocalDate> grid = renderGrid(2024, 9, UTC, Locale.US);

        assertEquals(LocalDate.of(2024, 9, 1), grid.get(0),
                "September 2024 begins on a Sunday, so the top-left cell is September 1st.");
        assertEquals(LocalDate.of(2024, 10, 12), grid.get(CELLS_IN_GRID - 1),
                "Six weeks from September 1st 2024 ends on October 12th. A different "
                        + "bottom-right cell means the grid is not 42 consecutive days.");
    }

    /**
     * The grid is 42 consecutive days with no repeats and no gaps. This single
     * invariant catches a stray extra {@code add(DATE, 1)}, a skipped
     * increment, and a day duplicated across a daylight-saving shift.
     */
    @Test
    void gridIsFortyTwoConsecutiveDaysInEveryMonthOfTheYear() {
        for (int month = 1; month <= 12; month++) {
            assertConsecutiveDays(renderGrid(2024, month, UTC, Locale.US), "2024-" + month + " (UTC)");
        }
    }

    /**
     * February is where date arithmetic goes wrong: 29 days in 2024, 28 in
     * 2023, 28 in 1900 (divisible by 100 but not 400) and 29 in 2000
     * (divisible by 400). If the 29th is not in the grid it is unreachable
     * from the archive calendar.
     */
    @Test
    void februaryShowsTheRightNumberOfDays() {
        record Case(int year, int expectedDays) { }
        List<Case> cases = List.of(
                new Case(2024, 29),
                new Case(2023, 28),
                new Case(1900, 28),
                new Case(2000, 29));

        for (Case testCase : cases) {
            List<LocalDate> grid = renderGrid(testCase.year(), 2, UTC, Locale.US);

            assertEquals(testCase.expectedDays(),
                    daysBelongingTo(grid, YearMonth.of(testCase.year(), 2)),
                    "February " + testCase.year() + " should contribute "
                            + testCase.expectedDays() + " cells to the grid. A wrong count "
                            + "means the leap-year rule is misapplied and the last day of "
                            + "February is missing from the archive calendar.");
            assertTrue(grid.contains(LocalDate.of(testCase.year(), 2, testCase.expectedDays())),
                    "February " + testCase.expectedDays() + ", " + testCase.year()
                            + " is missing from the grid entirely.");
        }
    }

    /** 30- and 31-day months, so month-length arithmetic is pinned all round. */
    @Test
    void monthLengthsMatchTheCalendar() {
        record Case(int year, int month, int expectedDays) { }
        List<Case> cases = List.of(
                new Case(2024, 1, 31),
                new Case(2024, 4, 30),
                new Case(2024, 6, 30),
                new Case(2024, 7, 31),
                new Case(2024, 12, 31));

        for (Case testCase : cases) {
            List<LocalDate> grid = renderGrid(testCase.year(), testCase.month(), UTC, Locale.US);

            assertEquals(testCase.expectedDays(),
                    daysBelongingTo(grid, YearMonth.of(testCase.year(), testCase.month())),
                    testCase.year() + "-" + testCase.month() + " has "
                            + testCase.expectedDays() + " days, but that many cells were not "
                            + "drawn for it. Check the in-month test in CalendarTag, which "
                            + "compares MONTH and YEAR against the day being displayed.");
        }
    }

    /**
     * December's grid runs into the next January, and January's runs back into
     * the previous December. Comparing only the month -- forgetting the year --
     * would make December 2024 and December 2025 look like the same month and
     * wrongly mark the trailing cells as in-month.
     */
    @Test
    void decemberGridSpillsIntoTheFollowingJanuary() {
        List<LocalDate> grid = renderGrid(2024, 12, UTC, Locale.US);

        assertEquals(LocalDate.of(2024, 12, 1), grid.get(0),
                "December 1st 2024 is a Sunday, so it is the first cell.");
        assertEquals(LocalDate.of(2025, 1, 11), grid.get(CELLS_IN_GRID - 1),
                "The last cell should be January 11th 2025 -- the grid has to cross both the "
                        + "month and the year boundary.");
        assertEquals(31, daysBelongingTo(grid, YearMonth.of(2024, 12)),
                "All 31 days of December 2024 must be in the grid.");
        assertEquals(0, daysBelongingTo(grid, YearMonth.of(2025, 12)),
                "Cells in January 2025 must not be counted as December just because the month "
                        + "number matches a different year.");
    }

    @Test
    void januaryGridReachesBackIntoThePreviousDecember() {
        List<LocalDate> grid = renderGrid(2025, 1, UTC, Locale.US);

        assertEquals(LocalDate.of(2024, 12, 29), grid.get(0),
                "January 1st 2025 is a Wednesday, so the grid starts on Sunday December 29th "
                        + "2024 -- the year has to roll backwards, not just the month.");
        assertEquals(31, daysBelongingTo(grid, YearMonth.of(2025, 1)),
                "All 31 days of January 2025 must be in the grid.");
    }

    /**
     * The tag steps a day at a time through zones that shift their clocks, some
     * of them at midnight, and one that shifts by 30 minutes. Stepping at
     * midnight rather than noon would duplicate or skip a day in exactly these
     * cases and no others -- a bug that shows up one weekend a year.
     */
    @Test
    void daylightSavingShiftsNeitherDuplicateNorSkipADay() {
        record Case(String zoneId, int month) { }
        List<Case> cases = List.of(
                new Case("America/New_York", 3),
                new Case("America/New_York", 11),
                new Case("America/Santiago", 9),
                new Case("America/Santiago", 4),
                new Case("America/Havana", 3),
                new Case("Australia/Lord_Howe", 4),
                new Case("Pacific/Chatham", 4),
                new Case("Asia/Beirut", 10));

        for (Case testCase : cases) {
            TimeZone zone = TimeZone.getTimeZone(testCase.zoneId());

            List<LocalDate> grid = renderGrid(2024, testCase.month(), zone, Locale.US);

            assertConsecutiveDays(grid, testCase.zoneId() + " 2024-" + testCase.month());
        }
    }

    /**
     * The grid shows the weblog's month in the weblog's own zone. Two blogs a
     * day apart on the clock must still each see their own complete month.
     */
    @Test
    void everyTimeZoneSeesTheSameCalendarMonth() {
        for (String zoneId : List.of("Pacific/Kiritimati", "Pacific/Midway", "UTC", "Asia/Kolkata")) {
            List<LocalDate> grid = renderGrid(2024, 2, TimeZone.getTimeZone(zoneId), Locale.US);

            assertEquals(LocalDate.of(2024, 1, 28), grid.get(0),
                    "In " + zoneId + " the February 2024 grid should still start on Sunday "
                            + "January 28th. A different start means the render is picking up "
                            + "a day in some other zone.");
            assertEquals(29, daysBelongingTo(grid, YearMonth.of(2024, 2)),
                    "In " + zoneId + " February 2024 should still contribute all 29 of its days.");
        }
    }

    // ------------------------------------------------------------------
    // Column headings
    // ------------------------------------------------------------------

    /**
     * The weekday headings label the columns beneath them. If the headings are
     * built from a different week start than the grid, every column is
     * mislabelled -- readers click "Tue" and get Monday's entries.
     */
    @Test
    void weekdayHeadingsLabelTheColumnsBeneathThem() {
        for (String languageTag : List.of("en-US", "fr-FR", "ar-EG")) {
            Locale locale = Locale.forLanguageTag(languageTag);
            RecordingCalendarModel model = RecordingCalendarModel.forMonth(2024, 5, UTC, locale);

            String html = render(model, locale, "");

            assertHeadingsMatchColumns(html, model, locale, languageTag);
        }
    }

    /**
     * CalendarTag takes its locale from a setter but its week start from the
     * model's calendar. When a caller passes a locale that disagrees with the
     * weblog's -- a themed blog rendered for a visitor, say -- the headings
     * must still describe the columns actually drawn.
     */
    @Test
    void weekdayHeadingsFollowTheGridWhenTagAndWeblogLocalesDisagree() {
        // Weblog is French (weeks start Monday); the tag was handed US English.
        RecordingCalendarModel model = RecordingCalendarModel.forMonth(2024, 5, UTC, Locale.FRANCE);

        String html = render(model, Locale.US, "");

        assertHeadingsMatchColumns(html, model, Locale.US, "tag=en-US, weblog=fr-FR");
    }

    // ------------------------------------------------------------------
    // Cell rendering
    // ------------------------------------------------------------------

    @Test
    void daysOutsideTheMonthAreBlankAndDaysInsideItShowTheirNumber() {
        RecordingCalendarModel model = RecordingCalendarModel.forMonth(2024, 5, UTC, Locale.US);

        String html = render(model, Locale.US, "");

        List<String[]> cells = dayCells(html);
        assertEquals(CELLS_IN_GRID, cells.size(),
                "A calendar month is six rows of seven cells; " + cells.size() + " were drawn.");

        List<LocalDate> grid = model.cellLocalDates();
        for (int i = 0; i < cells.size(); i++) {
            String cssClass = cells.get(i)[0];
            String body = cells.get(i)[1];
            LocalDate date = grid.get(i);
            if (date.getMonthValue() == 5) {
                assertEquals("hCalendarDay", cssClass,
                        date + " is in the rendered month, so it should use the in-month style.");
                assertTrue(body.contains(">" + date.getDayOfMonth() + "<"),
                        "Cell for " + date + " should show the day number "
                                + date.getDayOfMonth() + " but was: " + body);
            } else {
                assertEquals("hCalendarDayNotInMonth", cssClass,
                        date + " is outside the rendered month, so it should use the "
                                + "out-of-month style.");
                assertEquals("&nbsp;", body,
                        "Cells outside the month are deliberately blank, so the same date is "
                                + "not shown twice in consecutive months. Cell was: " + body);
            }
        }
    }

    @Test
    void aDayWithEntriesIsRenderedAsALink() {
        LocalDate linked = LocalDate.of(2024, 5, 17);
        RecordingCalendarModel model = RecordingCalendarModel.forMonth(2024, 5, UTC, Locale.US)
                .withUrls(date -> isSameDay(date, linked) ? "/blog/date/20240517" : null);

        String html = render(model, Locale.US, "");

        assertTrue(html.contains("<td class=\"hCalendarDayLinked\">"),
                "A day the model produced a URL for should use the linked-day style. "
                        + "Rendered HTML:\n" + html);
        assertTrue(html.contains("<a href=\"/blog/date/20240517\">17</a>"),
                "The 17th should link to the URL the model returned for it.");
        assertEquals(1, countOccurrences(html, "hCalendarDayLinked"),
                "Only the one day the model gave a URL for should be linked.");
    }

    @Test
    void cellContentFromTheModelReplacesTheDayNumber() {
        LocalDate withContent = LocalDate.of(2024, 5, 17);
        RecordingCalendarModel model = RecordingCalendarModel.forMonth(2024, 5, UTC, Locale.US)
                .withContent(date -> isSameDay(date, withContent) ? "<b>two posts</b>" : null);

        String html = render(model, Locale.US, "");

        assertTrue(html.contains("<td class=\"hCalendarDayCurrent\"><b>two posts</b></td>"),
                "The big calendar supplies its own cell markup through getContent(); it must "
                        + "be emitted verbatim in place of the plain day number. Rendered:\n" + html);
    }

    /**
     * Today gets its own style so readers can see where they are. The tag reads
     * the clock itself, so the expectation is bracketed by readings taken
     * either side of the render -- exact on every day of the year, and still
     * correct if the render happens to straddle midnight.
     */
    @Test
    void todayIsMarkedWithTheCurrentDayStyle() {
        ZonedDateTime beforeRender = ZonedDateTime.now(UTC.toZoneId());
        RecordingCalendarModel model = new RecordingCalendarModel(
                Date.from(beforeRender.toInstant()), UTC, Locale.US);

        String html = render(model, Locale.US, "");
        ZonedDateTime afterRender = ZonedDateTime.now(UTC.toZoneId());

        assertEquals(1, countOccurrences(html, "hCalendarDayCurrent"),
                "Exactly one cell -- today -- should carry the current-day style. Rendered:\n" + html);
        String marked = markedTodayNumber(html);
        Set<String> acceptable = new LinkedHashSet<>();
        acceptable.add(String.valueOf(beforeRender.getDayOfMonth()));
        acceptable.add(String.valueOf(afterRender.getDayOfMonth()));
        assertTrue(acceptable.contains(marked),
                "The cell styled as today shows day " + marked + ", but today in UTC is "
                        + beforeRender.toLocalDate() + ". CalendarTag compares the grid day "
                        + "against the current date in the weblog's zone.");
    }

    /**
     * Today's cell has its own three renderings, like any other day: plain,
     * linked, or filled with the model's own markup. The linked one is the
     * common case on a live blog -- the reader posted today.
     */
    @Test
    void todayIsRenderedAsALinkWhenThereAreEntriesOnIt() {
        ZonedDateTime today = ZonedDateTime.now(UTC.toZoneId());
        RecordingCalendarModel model =
                new RecordingCalendarModel(Date.from(today.toInstant()), UTC, Locale.US)
                        .withUrls(date -> "/blog/date/today");

        String html = render(model, Locale.US, "");
        ZonedDateTime afterRender = ZonedDateTime.now(UTC.toZoneId());

        Set<String> acceptable = new LinkedHashSet<>();
        acceptable.add(cellForLinkedToday(today.getDayOfMonth()));
        acceptable.add(cellForLinkedToday(afterRender.getDayOfMonth()));
        boolean found = acceptable.stream().anyMatch(html::contains);
        assertTrue(found,
                "Today with entries must keep the current-day cell style, carry the link on "
                        + "the day number itself, and close both the link and the cell. "
                        + "Expected one of " + acceptable + " in:\n" + html);
        assertEquals(1, countOccurrences(html, "hCalendarDayCurrent"),
                "Only today may use the current-day style, even when every day is linked.");
    }

    private static String cellForLinkedToday(int dayOfMonth) {
        return "<td class=\"hCalendarDayCurrent\"><a href=\"/blog/date/today\" "
                + "class=\"hCalendarDayTitle\">" + dayOfMonth + "</a></td>";
    }

    @Test
    void todayIsRenderedWithModelContentWhenTheModelSuppliesIt() {
        ZonedDateTime today = ZonedDateTime.now(UTC.toZoneId());
        RecordingCalendarModel model =
                new RecordingCalendarModel(Date.from(today.toInstant()), UTC, Locale.US)
                        .withContent(date -> isSameDay(date, today.toLocalDate())
                                ? "<b>posted today</b>" : null);

        String html = render(model, Locale.US, "");

        assertTrue(html.contains("<td class=\"hCalendarDayCurrent\"><b>posted today</b></td>"),
                "Content supplied for today must replace the day number, exactly as it does "
                        + "for any other day. Rendered:\n" + html);
    }

    @Test
    void previousAndNextMonthLinksAppearOnlyWhenThoseMonthsHaveEntries() {
        RecordingCalendarModel neither = RecordingCalendarModel.forMonth(2024, 5, UTC, Locale.US);
        String withoutNav = render(neither, Locale.US, "");

        assertFalse(withoutNav.contains("/prev-month"),
                "No previous month was offered by the model, so no back link should be drawn.");
        assertFalse(withoutNav.contains("/next-month"),
                "No next month was offered by the model, so no forward link should be drawn.");

        RecordingCalendarModel both = RecordingCalendarModel.forMonth(2024, 5, UTC, Locale.US)
                .withPrevMonth(new Date())
                .withNextMonth(new Date());
        String withNav = render(both, Locale.US, "");

        assertTrue(withNav.contains("href=\"/prev-month\""),
                "getPrevMonth() returned a month, so the back link must point at "
                        + "computePrevMonthUrl(). Rendered:\n" + withNav);
        assertTrue(withNav.contains("href=\"/next-month\""),
                "getNextMonth() returned a month, so the forward link must point at "
                        + "computeNextMonthUrl(). Rendered:\n" + withNav);
    }

    @Test
    void todayLinkIsAlwaysDrawn() {
        String html = render(RecordingCalendarModel.forMonth(2024, 5, UTC, Locale.US), Locale.US, "");

        assertTrue(html.contains("<a href=\"/this-month\" class=\"hCalendarNavBar\">Today</a>"),
                "The footer row always links back to the current month. Rendered:\n" + html);
    }

    /**
     * The big calendar is the same tag with a "Big" suffix appended to every
     * style class. A suffix that reaches some classes but not others silently
     * un-styles part of the widget.
     */
    @Test
    void classSuffixIsAppendedToEveryStyleClass() {
        RecordingCalendarModel model = RecordingCalendarModel.forMonth(2024, 5, UTC, Locale.US)
                .withPrevMonth(new Date())
                .withNextMonth(new Date())
                .withUrls(date -> "/some/day");

        String html = render(model, Locale.US, "Big");

        for (String styleClass : List.of("hCalendarTable", "hCalendarMonthYearRow",
                "hCalendarDayNameRow", "hCalendarDayNotInMonth", "hCalendarDayLinked",
                "hCalendarDayTitle", "hCalendarNextPrev")) {
            assertTrue(html.contains("\"" + styleClass + "Big"),
                    styleClass + " did not pick up the \"Big\" class suffix, so the big "
                            + "calendar falls back to the small calendar's styling for it.");
        }
        assertFalse(html.contains("hCalendarTable\""),
                "An unsuffixed style class survived; the suffix must be applied everywhere.");
    }

    @Test
    void titleShowsTheMonthAndYearBeingRendered() {
        String html = render(RecordingCalendarModel.forMonth(2024, 5, UTC, Locale.US), Locale.US, "");

        assertTrue(html.contains("May 2024"),
                "The heading should name the month being rendered. Rendered:\n" + html);
    }

    /**
     * The calendar is a table, and a browser given an unbalanced one lays the
     * month out as a single ragged column. Nothing else in the suite would
     * notice a dropped closing tag, because the day cells would all still be
     * there and still say the right thing.
     */
    @Test
    void theRenderedCalendarIsABalancedTable() {
        // Each cell has three renderings -- plain, linked, and filled with the
        // model's own markup -- and today has its own three on top of that. All
        // six have to close their tags.
        Date thisMonth = new Date();
        record Variant(String description, RecordingCalendarModel model) { }
        List<Variant> variants = List.of(
                new Variant("a past month, no entries",
                        RecordingCalendarModel.forMonth(2024, 5, UTC, Locale.US)
                                .withPrevMonth(thisMonth).withNextMonth(thisMonth)),
                new Variant("a past month, every day linked",
                        RecordingCalendarModel.forMonth(2024, 5, UTC, Locale.US)
                                .withUrls(date -> "/blog/date/x")),
                new Variant("a past month, every day filled by the model",
                        RecordingCalendarModel.forMonth(2024, 5, UTC, Locale.US)
                                .withContent(date -> "<div>stuff</div>")),
                new Variant("the current month, which contains today",
                        new RecordingCalendarModel(thisMonth, UTC, Locale.US)),
                new Variant("the current month with today linked",
                        new RecordingCalendarModel(thisMonth, UTC, Locale.US)
                                .withUrls(date -> "/blog/date/x")),
                new Variant("the current month with today filled by the model",
                        new RecordingCalendarModel(thisMonth, UTC, Locale.US)
                                .withContent(date -> "<div>stuff</div>")));

        for (Variant variant : variants) {
            String html = render(variant.model(), Locale.US, "");
            String where = variant.description() + ". Rendered:\n" + html;

            assertEquals(1, countOccurrences(html, "<table"), "one table opened: " + where);
            assertEquals(1, countOccurrences(html, "</table>"), "one table closed: " + where);
            // Title row, weekday heading row, six week rows, and the footer row.
            assertEquals(9, countOccurrences(html, "<tr"),
                    "The calendar is a title row, a heading row, six week rows and a footer "
                            + "row: " + where);
            assertEquals(countOccurrences(html, "<tr"), countOccurrences(html, "</tr>"),
                    "Every row must be closed, or the browser runs the rest of the month into "
                            + "the row above: " + where);
            // 42 day cells, plus the title cell and the footer cell.
            assertEquals(44, countOccurrences(html, "<td"),
                    "42 day cells plus the title and footer cells: " + where);
            assertEquals(countOccurrences(html, "<td"), countOccurrences(html, "</td>"),
                    "Every cell must be closed: " + where);
            assertEquals(7, countOccurrences(html, "<th"), "seven weekday headings: " + where);
            assertEquals(countOccurrences(html, "<th"), countOccurrences(html, "</th>"),
                    "Every heading cell must be closed: " + where);
            assertEquals(countOccurrences(html, "<a href"), countOccurrences(html, "</a>"),
                    "Every link must be closed: " + where);
            assertEquals(countOccurrences(html, "<div"), countOccurrences(html, "</div>"),
                    "Every div must be closed: " + where);
        }
    }

    /**
     * A weblog on the far side of the date line is a different day from the
     * server for part of every day. Both the month heading and the weekday
     * headings must be written in the weblog's zone, not the server's, or a
     * blog in Kiribati sees the wrong month name on the last day of the month.
     */
    @Test
    void headingsAreWrittenInTheWeblogZoneNotTheServerZone() {
        TimeZone serverZone = TimeZone.getDefault();
        try {
            // Fourteen hours ahead of the weblog below, so any date read in the
            // server's zone instead lands on the following day.
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));

            // Noon on the last day of May, in UTC: the 1st of June in Kiritimati.
            RecordingCalendarModel model = new RecordingCalendarModel(
                    Date.from(ZonedDateTime.of(2024, 5, 31, 12, 0, 0, 0, UTC.toZoneId())
                            .toInstant()),
                    UTC, Locale.US);

            String html = render(model, Locale.US, "");

            assertTrue(html.contains("May 2024"),
                    "The weblog is in UTC and is showing May, so the heading must say May "
                            + "2024. Reading the date in the server's zone makes the last day "
                            + "of the month spill into the next one. Rendered:\n" + html);
            assertHeadingsMatchColumns(html, model, Locale.US, "server 14h ahead of the weblog");
        } finally {
            TimeZone.setDefault(serverZone);
        }
    }

    // ------------------------------------------------------------------
    // Tag plumbing
    // ------------------------------------------------------------------

    @Test
    void missingModelRendersNothingAtAll() throws JspException {
        PageContext pageContext = mock(PageContext.class);
        when(pageContext.findAttribute("calendarModel")).thenReturn(null);

        CalendarTag tag = new CalendarTag();
        tag.setPageContext(pageContext);
        tag.setModel("calendarModel");
        StringWriter out = new StringWriter();

        int result = tag.doStartTag(new PrintWriter(out, true));

        assertEquals(Tag.SKIP_BODY, result,
                "With no model in scope the tag has nothing to draw and must skip its body.");
        assertEquals("", out.toString(),
                "A missing model must produce no markup at all, not a half-drawn table.");
    }

    /**
     * Velocity can pass the model as a property of another bean, e.g.
     * {@code model="pageModel.calendar"}. That dotted form is resolved through
     * commons-beanutils and is easy to break without noticing.
     */
    @Test
    void modelCanBeReachedThroughABeanProperty() throws JspException {
        RecordingCalendarModel model = RecordingCalendarModel.forMonth(2024, 5, UTC, Locale.US);
        PageContext pageContext = mock(PageContext.class);
        when(pageContext.findAttribute("pageModel")).thenReturn(new ModelHolder(model));

        CalendarTag tag = new CalendarTag();
        tag.setPageContext(pageContext);
        tag.setModel("pageModel.calendar");
        tag.setLocale(Locale.US);
        StringWriter out = new StringWriter();

        tag.doStartTag(new PrintWriter(out, true));

        assertTrue(out.toString().contains("May 2024"),
                "A dotted model name must be resolved as a bean property of the named "
                        + "attribute. Rendered:\n" + out);
        assertEquals(CELLS_IN_GRID, model.cellDates().size(),
                "The bean-property model should have been asked about all 42 cells.");
    }

    @Test
    void aFailingModelReportsAnErrorInsteadOfHalfACalendar() throws JspException {
        RecordingCalendarModel model = RecordingCalendarModel.forMonth(2024, 5, UTC, Locale.US)
                .thatFails(new IllegalStateException("weblog lookup failed"));
        PageContext pageContext = mock(PageContext.class);
        when(pageContext.findAttribute("calendarModel")).thenReturn(model);

        CalendarTag tag = new CalendarTag();
        tag.setPageContext(pageContext);
        tag.setModel("calendarModel");
        StringWriter out = new StringWriter();

        int result = tag.doStartTag(new PrintWriter(out, true));

        assertEquals(Tag.SKIP_BODY, result,
                "A model failure must not propagate out of the tag and break the whole page.");
        // The failure happens part-way through the table, so the partial markup
        // stays; what matters is that the error marker is complete and last.
        assertTrue(out.toString().endsWith("<span class=\"error\">"
                        + "<p><b>An ERROR has occured CalendarTag</b></p>"
                        + "</span>"),
                "A model failure should end the output with a complete, closed error marker "
                        + "-- not an unclosed span that swallows the rest of the page. "
                        + "Rendered:\n" + out);
    }

    @Test
    void emitReturnsTheWholeCalendar() {
        CalendarTag tag = tagFor(RecordingCalendarModel.forMonth(2024, 5, UTC, Locale.US),
                Locale.US, "");

        String emitted = tag.emit();

        assertTrue(emitted.contains("May 2024"),
                "emit() is how the Velocity calendar macro renders the widget; it must return "
                        + "the calendar markup. Got:\n" + emitted);
        assertEquals(CELLS_IN_GRID, countOccurrences(emitted, "<td class=\"hCalendar"),
                "emit() should return a full 42-cell grid.");
        assertEquals(emitted, tag.toString(),
                "emit() is documented as an alias for toString(); the two have diverged.");
    }

    @Test
    void jspEntryPointsWriteToThePageWriter() throws Exception {
        JspWriter pageWriter = mock(JspWriter.class);
        PageContext pageContext = mock(PageContext.class);
        when(pageContext.getOut()).thenReturn(pageWriter);
        when(pageContext.findAttribute("calendarModel"))
                .thenReturn(RecordingCalendarModel.forMonth(2024, 5, UTC, Locale.US));

        CalendarTag tag = new CalendarTag();
        tag.setPageContext(pageContext);
        tag.setModel("calendarModel");
        tag.setLocale(Locale.US);

        assertEquals(Tag.SKIP_BODY, tag.doStartTag(),
                "CalendarTag has no body to evaluate.");
        verify(pageWriter, atLeastOnce()).write(startsWith("<table"), eq(0), anyInt());
        assertEquals(Tag.EVAL_PAGE, tag.doEndTag(),
                "The rest of the page must still be evaluated after the calendar.");
    }

    /** A null locale must be ignored rather than replacing the current one. */
    @Test
    void nullLocaleIsIgnored() {
        CalendarTag tag = tagFor(RecordingCalendarModel.forMonth(2024, 5, UTC, Locale.US),
                Locale.US, "");
        tag.setLocale(null);

        assertTrue(tag.emit().contains("May 2024"),
                "setLocale(null) should leave the previously set locale in place, not clear it.");
    }

    @Test
    void tagAttributesRoundTrip() {
        CalendarTag tag = new CalendarTag();
        tag.setName("calendar");
        tag.setModel("calendarModel");
        tag.setClassSuffix("Big");

        assertEquals("calendar", tag.getName(), "name attribute did not round-trip");
        assertEquals("calendarModel", tag.getModel(), "model attribute did not round-trip");
        assertEquals("Big", tag.getClassSuffix(), "classSuffix attribute did not round-trip");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Renders the given month and returns the 42 grid cells as local dates. */
    private List<LocalDate> renderGrid(int year, int month, TimeZone zone, Locale locale) {
        RecordingCalendarModel model = RecordingCalendarModel.forMonth(year, month, zone, locale);
        render(model, locale, "");
        assertEquals(CELLS_IN_GRID, model.cellDates().size(),
                "The tag drew " + model.cellDates().size() + " cells for " + year + "-" + month
                        + " instead of " + CELLS_IN_GRID + ". The grid is six rows of seven days.");
        return model.cellLocalDates();
    }

    private String render(CalendarModel model, Locale tagLocale, String classSuffix) {
        StringWriter out = new StringWriter();
        try {
            tagFor(model, tagLocale, classSuffix).doStartTag(new PrintWriter(out, true));
        } catch (JspException e) {
            throw new AssertionError("CalendarTag threw instead of rendering", e);
        }
        return out.toString();
    }

    private CalendarTag tagFor(CalendarModel model, Locale tagLocale, String classSuffix) {
        PageContext pageContext = mock(PageContext.class);
        when(pageContext.findAttribute("calendarModel")).thenReturn(model);

        CalendarTag tag = new CalendarTag();
        tag.setPageContext(pageContext);
        tag.setName("calendar");
        tag.setModel("calendarModel");
        tag.setLocale(tagLocale);
        tag.setClassSuffix(classSuffix);
        return tag;
    }

    private static void assertConsecutiveDays(List<LocalDate> grid, String label) {
        assertEquals(CELLS_IN_GRID, grid.size(), label + ": wrong number of cells");
        for (int i = 1; i < grid.size(); i++) {
            assertEquals(grid.get(i - 1).plusDays(1), grid.get(i),
                    label + ": cell " + i + " is " + grid.get(i) + " but the cell before it was "
                            + grid.get(i - 1) + ". The grid must advance exactly one day per "
                            + "cell -- a repeat or a gap means the day-stepping loop lost a day "
                            + "to a clock change or a month boundary.");
        }
        assertEquals(CELLS_IN_GRID, Set.copyOf(grid).size(),
                label + ": the grid contains a repeated date.");
    }

    private static int daysBelongingTo(List<LocalDate> grid, YearMonth month) {
        return (int) grid.stream().filter(date -> YearMonth.from(date).equals(month)).count();
    }

    /**
     * Asserts each weekday heading names the weekday of the column below it.
     * The headings are rendered in the tag's locale; the columns come from the
     * model's calendar. This is the assertion that catches the two drifting
     * apart.
     */
    private static void assertHeadingsMatchColumns(String html, RecordingCalendarModel model,
                                                   Locale headingLocale, String label) {
        List<String> headings = new ArrayList<>();
        Matcher matcher = DAY_HEADING.matcher(html);
        while (matcher.find()) {
            headings.add(matcher.group(1));
        }
        assertEquals(7, headings.size(),
                label + ": expected seven weekday headings but found " + headings.size());

        SimpleDateFormat expectedName = new SimpleDateFormat("EEE", headingLocale);
        expectedName.setTimeZone(UTC);
        for (int column = 0; column < 7; column++) {
            Date columnDate = model.cellDates().get(column);
            assertEquals(expectedName.format(columnDate), headings.get(column),
                    label + ": column " + column + " holds " + model.cellLocalDates().get(column)
                            + " (a " + model.cellLocalDates().get(column).getDayOfWeek()
                            + ") but is headed \"" + headings.get(column) + "\". The weekday "
                            + "headings and the grid must be built from the same first day of "
                            + "the week, or every column is mislabelled.");
        }
    }

    private static List<String[]> dayCells(String html) {
        List<String[]> cells = new ArrayList<>();
        Matcher matcher = DAY_CELL.matcher(html);
        while (matcher.find()) {
            cells.add(new String[]{matcher.group(1), matcher.group(2)});
        }
        return cells;
    }

    /** The day number inside the single cell styled as today. */
    private static String markedTodayNumber(String html) {
        Matcher matcher = Pattern
                .compile("<td class=\"hCalendarDayCurrent\">.*?>(\\d+)<", Pattern.DOTALL)
                .matcher(html);
        assertTrue(matcher.find(), "No cell was styled as today. Rendered:\n" + html);
        return matcher.group(1);
    }

    private static boolean isSameDay(Date date, LocalDate day) {
        return date.toInstant().atZone(UTC.toZoneId()).toLocalDate().equals(day);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = haystack.indexOf(needle);
        while (from >= 0) {
            count++;
            from = haystack.indexOf(needle, from + needle.length());
        }
        return count;
    }

    /** Stands in for the Velocity page model a dotted {@code model} name walks. */
    public static class ModelHolder {
        private final CalendarModel calendar;

        ModelHolder(CalendarModel calendar) {
            this.calendar = calendar;
        }

        public CalendarModel getCalendar() {
            return calendar;
        }
    }
}
