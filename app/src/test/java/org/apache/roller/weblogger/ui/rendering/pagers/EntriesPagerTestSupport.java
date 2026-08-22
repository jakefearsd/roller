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

import java.util.Date;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.PropertiesManager;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.Weblog;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Shared fixture for the concrete weblog-entry pagers.
 *
 * <p>Those pagers reach the business tier twice on construction: once through
 * {@code WebloggerRuntimeConfig} for the site page-size ceiling, and once
 * through the {@link WeblogEntryManager} for the entries themselves. The
 * entries come from the {@link Weblogger} the pager is constructed with
 * ({@link #weblogger}, plan Task 11); only the runtime-config read still goes
 * through the {@code WebloggerFactory} static (plan Task 19), which is why
 * {@link #withRuntimeConfig} installs a SEPARATE, properties-only facade there:
 * a pager that regressed to locating its entry manager statically would find
 * none and fail, rather than silently passing.
 */
abstract class EntriesPagerTestSupport {

    /** Site-wide ceiling on entries per page, high enough not to mask the weblog's own setting. */
    static final int SITE_MAX_ENTRIES = 30;

    /** Effective page size for these tests: the weblog asks for fewer than the ceiling. */
    static final int PAGE_SIZE = 10;

    /** Fixed creation date well in the past, so previous-collection links are never suppressed. */
    private static final Date WEBLOG_CREATED = new Date(0L);

    final URLStrategy urlStrategy = mock(URLStrategy.class);
    final WeblogEntryManager entryManager = mock(WeblogEntryManager.class);

    /**
     * The facade a pager is constructed WITH (plan Task 11). Its entry manager
     * is this fixture's {@link #entryManager}; nothing else is stubbed, so a
     * pager that reached for any other manager would get null and fail loudly.
     */
    final Weblogger weblogger = mock(Weblogger.class);

    {
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
    }

    /** A weblog pinned to UTC so date arithmetic in the tests is reproducible. */
    static Weblog weblog() {
        Weblog weblog = new Weblog();
        weblog.setHandle("myblog");
        weblog.setLocale("en_US");
        weblog.setTimeZone("UTC");
        weblog.setEntryDisplayCount(PAGE_SIZE);
        weblog.setDateCreated(WEBLOG_CREATED);
        return weblog;
    }

    /** A weblog created just now, used to check that previous-collection links are suppressed. */
    static Weblog weblogCreatedToday() {
        Weblog weblog = weblog();
        weblog.setDateCreated(new Date());
        return weblog;
    }

    /**
     * Runs the body with WebloggerFactory answering with a properties-only
     * facade that supplies site.pages.maxEntries (the one read that is still
     * static, plan Task 19). The entry manager is NOT reachable through it.
     */
    void withRuntimeConfig(Runnable body) {
        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            Weblogger runtimeConfigOnly = mock(Weblogger.class);
            PropertiesManager propertiesManager = mock(PropertiesManager.class);
            try {
                when(propertiesManager.getProperty("site.pages.maxEntries"))
                        .thenReturn(new RuntimeConfigProperty("site.pages.maxEntries",
                                String.valueOf(SITE_MAX_ENTRIES)));
            } catch (WebloggerException impossible) {
                // getProperty declares this; stubbing a mock never actually throws.
                throw new AssertionError("stubbing a mock must not throw", impossible);
            }
            when(runtimeConfigOnly.getPropertiesManager()).thenReturn(propertiesManager);
            factory.when(WebloggerFactory::getWeblogger).thenReturn(runtimeConfigOnly);
            body.run();
        }
    }

    /**
     * Asserts that a navigation label is real user-facing text.
     *
     * <p>Two failure modes are worth separating. An empty label renders a
     * clickable control with no caption, and {@link org.apache.roller.weblogger.util.I18nMessages}
     * returns the lookup key itself when a bundle entry is missing, which puts
     * "weblogEntriesPager.month.next" on the page. Both look like working code
     * to an assertion that only checks for non-null.
     */
    static void assertIsRenderedLabel(String label, String messageKey, String what) {
        org.junit.jupiter.api.Assertions.assertNotNull(label, what + " must have a label");
        org.junit.jupiter.api.Assertions.assertFalse(label.isBlank(),
                what + " must not render as an empty caption");
        org.junit.jupiter.api.Assertions.assertNotEquals(messageKey, label,
                what + " fell back to the raw message key, so " + messageKey
                        + " is missing from ApplicationResources");
    }

    /**
     * Makes every URLStrategy collection/page call echo the page number, so a
     * test can assert on the produced link instead of on call counts. The
     * name-returning getters call the link getters again internally, which
     * makes strict invocation counting misleading.
     */
    void stubUrlStrategyEchoingPageNumber() {
        when(urlStrategy.getWeblogCollectionURL(any(), any(), any(), any(), any(), anyInt(),
                anyBoolean())).thenAnswer(call -> "collection:date=" + call.getArgument(3)
                        + ":page=" + call.getArgument(5));
        when(urlStrategy.getWeblogPageURL(any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyBoolean())).thenAnswer(call -> "page:" + call.getArgument(2)
                        + ":page=" + call.getArgument(7));
        when(urlStrategy.getWeblogEntryURL(any(), any(), any(), anyBoolean()))
                .thenAnswer(call -> "entry:" + call.getArgument(2));
    }
}
