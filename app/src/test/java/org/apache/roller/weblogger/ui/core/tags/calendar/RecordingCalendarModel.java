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

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Function;

/**
 * A {@link CalendarModel} that records the cell dates {@link CalendarTag} asks
 * it about, so the tag's grid arithmetic can be inspected directly instead of
 * being reverse-engineered out of the emitted HTML.
 *
 * <p>The tag calls {@link #computeUrl} exactly once per grid cell, in
 * left-to-right, top-to-bottom order, before deciding how to render that cell.
 * {@link #cellDates()} is therefore the grid itself.
 *
 * <p>The calendar handed back by {@link #getCalendar()} is built the same way
 * {@link WeblogCalendarModel} builds its own: from the weblog's time zone and
 * locale, positioned at the current instant. Both the time zone (which decides
 * what "the same day" means) and the locale (which decides the first day of the
 * week) reach the tag only through this object.
 */
class RecordingCalendarModel implements CalendarModel {

    private final Date day;
    private final TimeZone timeZone;
    private final Locale locale;
    private final List<Date> cellDates = new ArrayList<>();

    private Date prevMonth;
    private Date nextMonth;
    private Function<Date, String> urlForDay = date -> null;
    private Function<Date, String> contentForDay = date -> null;
    private RuntimeException failure;

    /**
     * @param day      the day whose month the tag should render
     * @param timeZone the weblog time zone
     * @param locale   the weblog locale, which fixes the first day of the week
     */
    RecordingCalendarModel(Date day, TimeZone timeZone, Locale locale) {
        this.day = day;
        this.timeZone = timeZone;
        this.locale = locale;
    }

    /**
     * Builds a model positioned at noon on the 15th of the given month, in the
     * given zone. Mid-month and mid-day so that neither the month boundary nor
     * a daylight-saving shift can move the date the tag is asked to render --
     * if the grid comes out wrong, the fault is in the tag, not the fixture.
     */
    static RecordingCalendarModel forMonth(int year, int month, TimeZone timeZone, Locale locale) {
        ZonedDateTime midMonth =
                ZonedDateTime.of(year, month, 15, 12, 0, 0, 0, timeZone.toZoneId());
        return new RecordingCalendarModel(Date.from(midMonth.toInstant()), timeZone, locale);
    }

    RecordingCalendarModel withPrevMonth(Date value) {
        this.prevMonth = value;
        return this;
    }

    RecordingCalendarModel withNextMonth(Date value) {
        this.nextMonth = value;
        return this;
    }

    RecordingCalendarModel withUrls(Function<Date, String> urls) {
        this.urlForDay = urls;
        return this;
    }

    RecordingCalendarModel withContent(Function<Date, String> content) {
        this.contentForDay = content;
        return this;
    }

    /** Makes every cell lookup blow up, to exercise the tag's error handling. */
    RecordingCalendarModel thatFails(RuntimeException toThrow) {
        this.failure = toThrow;
        return this;
    }

    /** The dates of the grid cells, in the order the tag visited them. */
    List<Date> cellDates() {
        return cellDates;
    }

    /** The grid cells as local dates in the model's own time zone. */
    List<LocalDate> cellLocalDates() {
        List<LocalDate> dates = new ArrayList<>(cellDates.size());
        for (Date cell : cellDates) {
            dates.add(cell.toInstant().atZone(timeZone.toZoneId()).toLocalDate());
        }
        return dates;
    }

    @Override
    public Calendar getCalendar() {
        return Calendar.getInstance(timeZone, locale);
    }

    @Override
    public void setDay(String month) {
        throw new UnsupportedOperationException("CalendarTag must not call setDay()");
    }

    @Override
    public Date getDay() {
        return (Date) day.clone();
    }

    @Override
    public Date getNextMonth() {
        return nextMonth;
    }

    @Override
    public Date getPrevMonth() {
        return prevMonth;
    }

    @Override
    public String computePrevMonthUrl() {
        return "/prev-month";
    }

    @Override
    public String computeTodayMonthUrl() {
        return "/this-month";
    }

    @Override
    public String computeNextMonthUrl() {
        return "/next-month";
    }

    @Override
    public String computeUrl(Date cellDay, boolean monthURL, boolean alwaysURL) {
        if (failure != null) {
            throw failure;
        }
        cellDates.add(cellDay);
        return urlForDay.apply(cellDay);
    }

    @Override
    public String getContent(Date cellDay) {
        return contentForDay.apply(cellDay);
    }
}
