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

package org.apache.roller.weblogger.ui.rendering.model;

import jakarta.servlet.jsp.PageContext;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MultiWeblogURLStrategy;
import org.apache.roller.weblogger.business.PropertiesManager;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.wrapper.WeblogWrapper;
import org.apache.roller.weblogger.ui.core.tags.calendar.BigWeblogCalendarModel;
import org.apache.roller.weblogger.ui.core.tags.calendar.WeblogCalendarModel;
import org.apache.roller.weblogger.ui.rendering.util.WeblogFeedRequest;
import org.apache.roller.weblogger.ui.rendering.util.WeblogPageRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CalendarModel}, the {@code $calendarModel} object that
 * renders the little month calendar in a blog's sidebar.
 *
 * <p>The class itself does two things: it decides which calendar flavour to
 * publish into the JSP page context, and it translates the theme-level "no
 * category" sentinel. Both are asserted here. Emitting the HTML is
 * {@code CalendarTag}'s job and is not re-tested.
 */
class CalendarModelTest {

    private MockedStatic<WebloggerFactory> factory;
    private Weblog weblog;
    private PageContext pageContext;
    private final Map<String, Object> pageScope = new HashMap<>();
    private String previousRelativeContextURL;

    @BeforeEach
    void setUp() throws Exception {
        // The calendar asks the entry manager which days have posts; an empty
        // answer is enough to exercise everything this class owns.
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        when(entryManager.getWeblogEntries(any())).thenReturn(List.of());
        when(entryManager.getWeblogEntryStringMap(any())).thenReturn(Map.of());

        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        when(weblogger.getPropertiesManager()).thenReturn(mock(PropertiesManager.class));
        when(weblogger.getUrlStrategy()).thenReturn(new MultiWeblogURLStrategy());

        factory = mockStatic(WebloggerFactory.class);
        factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);

        previousRelativeContextURL = WebloggerRuntimeConfig.getRelativeContextURL();
        WebloggerRuntimeConfig.setRelativeContextURL("/roller");

        weblog = new Weblog("testblog", "testuser", "Test Blog", "a test blog",
                "blog@example.com", "journal", "en_US", "UTC");

        // CalendarTag reads its model back out of the page context, so the mock
        // has to behave like real page scope rather than swallowing writes —
        // otherwise the tag finds nothing and silently emits an empty string.
        pageContext = mock(PageContext.class);
        doAnswer(invocation -> {
            pageScope.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(pageContext).setAttribute(anyString(), any());
        when(pageContext.findAttribute(anyString()))
                .thenAnswer(invocation -> pageScope.get(invocation.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        WebloggerRuntimeConfig.setRelativeContextURL(previousRelativeContextURL);
        factory.close();
    }

    private WeblogPageRequest pageRequest() {
        WeblogPageRequest request = new WeblogPageRequest();
        request.setWeblog(weblog);
        request.setWeblogHandle(weblog.getHandle());
        return request;
    }

    private CalendarModel modelFor(WeblogPageRequest request) throws WebloggerException {
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", request);
        initData.put("pageContext", pageContext);
        CalendarModel model = new CalendarModel();
        model.init(initData);
        return model;
    }

    /** The calendar flavour the model published for CalendarTag to pick up. */
    private org.apache.roller.weblogger.ui.core.tags.calendar.CalendarModel publishedModel() {
        return (org.apache.roller.weblogger.ui.core.tags.calendar.CalendarModel)
                pageScope.get("calendarModel");
    }

    private WeblogWrapper wrappedWeblog() {
        return WeblogWrapper.wrap(weblog, new MultiWeblogURLStrategy());
    }

    // ------------------------------------------------------------------ init

    @Test
    void modelIsRegisteredUnderTheNameThemesUse() {
        assertEquals("calendarModel", new CalendarModel().getModelName(),
                "Themes reference this model as $calendarModel.");
    }

    @Test
    void initWithoutAParsedRequestFails() {
        CalendarModel model = new CalendarModel();
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(new HashMap<>()),
                "init() must reject init data with no request.");
        assertTrue(thrown.getMessage().contains("weblogRequest"),
                "The failure should name what was missing; was: " + thrown.getMessage());
    }

    @Test
    void initRejectsRequestsThatAreNotPageRequests() {
        // A calendar only makes sense on an HTML page; there is nothing to
        // render it into in a feed.
        WeblogFeedRequest feedRequest = new WeblogFeedRequest();
        feedRequest.setWeblog(weblog);
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", feedRequest);

        CalendarModel model = new CalendarModel();
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(initData),
                "CalendarModel must refuse a request it cannot serve.");
        assertTrue(thrown.getMessage().contains("WeblogPageRequest"),
                "The failure should say which request type is required; was: "
                        + thrown.getMessage());
    }

    // -------------------------------------------------------------- flavours

    @Test
    void theSmallCalendarIsPublishedAndRendered() throws Exception {
        String html = modelFor(pageRequest()).showWeblogEntryCalendar(wrappedWeblog(), "nil");

        assertInstanceOf(WeblogCalendarModel.class, publishedModel(),
                "showWeblogEntryCalendar must publish the compact calendar.");
        assertTrue(html.contains("class=\"hCalendarTable\""),
                "The tag must actually emit the calendar table; got: " + html);
        assertFalse(html.contains("Big"),
                "The compact calendar must not carry the big variant's CSS classes; "
                        + "got: " + html);
    }

    @Test
    void theBigCalendarIsPublishedAndRenderedWithItsOwnStyling() throws Exception {
        String html = modelFor(pageRequest()).showWeblogEntryCalendarBig(wrappedWeblog(), "nil");

        assertInstanceOf(BigWeblogCalendarModel.class, publishedModel(),
                "showWeblogEntryCalendarBig must publish the day-by-day calendar, "
                        + "which is a different class with different day cells.");
        assertTrue(html.contains("class=\"hCalendarTableBig\""),
                "The big calendar's CSS classes carry a 'Big' suffix, which is what "
                        + "the archive-page stylesheet targets; got: " + html);
    }

    @Test
    void theCalendarIsRenderedInTheWeblogsLanguageNotTheServers() throws Exception {
        // The tag's locale defaults to the JVM's, so if the weblog's locale is
        // not passed through, a French blog running on an English server shows
        // English day and month names — and nothing fails to give it away.
        String english = modelFor(pageRequest())
                .showWeblogEntryCalendar(wrappedWeblog(), "nil");

        weblog = new Weblog("frblog", "testuser", "Blog FR", "d", "e@example.com",
                "journal", "fr", "UTC");
        pageScope.clear();
        String french = modelFor(pageRequest())
                .showWeblogEntryCalendar(wrappedWeblog(), "nil");

        // ">Sun<" is the day-name header cell, not a CSS class, so this cannot
        // be satisfied accidentally by markup that happens to contain the word.
        assertTrue(english.contains(">Sun<"),
                "The en_US calendar must carry English day names; got: " + english);
        assertFalse(french.contains(">Sun<"),
                "The French calendar must not carry English day names; got: " + french);
    }

    @Test
    void theRenderedCalendarNamesTheMonthItIsShowing() throws Exception {
        // Proves the tag ran far enough to read the model, rather than bailing
        // out and returning an empty string.
        String html = modelFor(pageRequest()).showWeblogEntryCalendar(wrappedWeblog(), "nil");

        String month = new java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.US)
                .format(new Date());
        assertTrue(html.contains(month),
                "The calendar heading must name the current month (" + month
                        + "); got: " + html);
    }

    // ------------------------------------------------------- the nil sentinel

    @Test
    void theNilCategoryArgumentMeansUseWhateverTheUrlSaid() throws Exception {
        // Velocity has no null literal, so themes pass the string "nil" to mean
        // "no override". Taking it literally would filter the calendar to a
        // category named "nil" and blank out every day.
        WeblogPageRequest request = pageRequest();
        request.setWeblogCategoryName("Java");

        modelFor(request).showWeblogEntryCalendar(wrappedWeblog(), "nil");

        String url = publishedModel().computeUrl(new Date(), false, true);
        assertTrue(url.contains("cat=Java"),
                "'nil' must fall through to the request's category; URL was: " + url);
        assertFalse(url.contains("nil"),
                "'nil' must never reach a generated URL; URL was: " + url);
    }

    @Test
    void aCalendarThatCannotBeBuiltRendersAsNothing() throws Exception {
        // No page context means there is nowhere to publish the model, which is
        // fatal for the calendar. Losing the sidebar widget is acceptable;
        // losing the whole page is not.
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", pageRequest());
        CalendarModel model = new CalendarModel();
        model.init(initData);

        assertNull(model.showWeblogEntryCalendar(wrappedWeblog(), "nil"),
                "A calendar that cannot be built must render as nothing rather than "
                        + "aborting the page.");
    }

    @Test
    void anExplicitCategoryArgumentOverridesTheOneInTheUrl() throws Exception {
        WeblogPageRequest request = pageRequest();
        request.setWeblogCategoryName("Java");

        modelFor(request).showWeblogEntryCalendar(wrappedWeblog(), "Sports");

        String url = publishedModel().computeUrl(new Date(), false, true);
        assertTrue(url.contains("cat=Sports"),
                "The argument must win over the request's category; URL was: " + url);
    }
}
