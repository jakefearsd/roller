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

import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.rendering.util.WeblogPageRequest;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Fixture for the two calendar models, which reach the business tier through
 * the static {@link WebloggerFactory}.
 *
 * <p>{@link #withBusinessTier} stands a mock Weblogger up for the duration of a
 * scenario, so these tests exercise the models' own arithmetic without a
 * database, a bootstrap, or any dependence on what other tests in the JVM have
 * already booted.
 *
 * <p>The fixture weblog is deliberately <em>not</em> in the default locale or
 * zone: it is a Paris blog in French, so anything that silently falls back to
 * the JVM defaults shows up as a failure rather than passing by coincidence on
 * the developer's machine.
 */
abstract class CalendarModelTestSupport {

    /** The fixture weblog's time zone. Offset from UTC, and observes DST. */
    protected static final String ZONE = "Europe/Paris";

    /** French, so the fixture's weeks start on Monday rather than Sunday. */
    protected static final String LOCALE = "fr_FR";

    /**
     * The locale of the request, which scopes a multi-language blog's entries.
     * Non-null so that failing to pass it on shows up as a difference.
     */
    protected static final String REQUEST_LOCALE = "fr";

    protected Weblog weblog;
    protected WeblogEntryManager entryManager;
    protected URLStrategy urlStrategy;

    /** A scenario body, which may throw the checked exceptions the managers declare. */
    @FunctionalInterface
    protected interface Scenario {
        void run() throws Exception;
    }

    /**
     * Runs {@code scenario} with a mock business tier installed behind
     * {@link WebloggerFactory}, and with {@link #weblog}, {@link #entryManager}
     * and {@link #urlStrategy} freshly wired.
     */
    protected void withBusinessTier(Scenario scenario) {
        weblog = new Weblog();
        weblog.setHandle("testblog");
        weblog.setLocale(LOCALE);
        weblog.setTimeZone(ZONE);

        entryManager = mock(WeblogEntryManager.class);
        urlStrategy = mock(URLStrategy.class);

        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        when(weblogger.getUrlStrategy()).thenReturn(urlStrategy);

        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);
            scenario.run();
        } catch (Exception e) {
            throw new AssertionError("Scenario threw: " + e, e);
        }
    }

    /**
     * A page request for the fixture weblog.
     *
     * @param weblogDate   the date segment of the URL, or null for none
     * @param pageName     the custom page the calendar sits on, or null for the
     *                     main weblog page
     * @param categoryName the category the reader is browsing, or null
     */
    protected WeblogPageRequest pageRequestFor(String weblogDate, String pageName,
                                               String categoryName) {
        WeblogPageRequest request = mock(WeblogPageRequest.class);
        when(request.getWeblog()).thenReturn(weblog);
        when(request.getWeblogHandle()).thenReturn(weblog.getHandle());
        when(request.getWeblogPageName()).thenReturn(pageName);
        when(request.getWeblogDate()).thenReturn(weblogDate);
        when(request.getWeblogCategoryName()).thenReturn(categoryName);
        when(request.getLocale()).thenReturn(REQUEST_LOCALE);
        return request;
    }

    /** The instant of a wall-clock time in the fixture weblog's zone. */
    protected static Date instantIn(int year, int month, int day, int hour, int minute,
                                    int second, int millis) {
        return Date.from(ZonedDateTime
                .of(year, month, day, hour, minute, second, millis * 1_000_000, ZoneId.of(ZONE))
                .toInstant());
    }

    /** Noon on the given day in the fixture weblog's zone. */
    protected static Date dateIn(int year, int month, int day) {
        return Date.from(ZonedDateTime.of(year, month, day, 12, 0, 0, 0, ZoneId.of(ZONE))
                .toInstant());
    }

    protected static LocalDate toLocalDate(Date date, String zoneId) {
        return date.toInstant().atZone(ZoneId.of(zoneId)).toLocalDate();
    }
}
