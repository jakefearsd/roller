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
package org.apache.roller.weblogger.pojos.wrapper;

import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.themes.ThemeManager;
import org.apache.roller.weblogger.pojos.TagStat;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogTemplate;
import org.apache.roller.weblogger.pojos.WeblogTheme;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Covers the {@link WeblogWrapper} accessors that reach through the business
 * tier -- URLs, counts, recent-content lists and theme templates.
 *
 * <p>These are the methods a Velocity theme actually calls to build a page, and
 * every one of them is a one-line delegation, which is exactly the shape of
 * code that gets wired to the wrong underlying method during a refactor and
 * still compiles. Each is given a value only it could have returned.
 */
class WeblogWrapperDelegationTest {

    private Weblog weblog;
    private URLStrategy urls;
    private Weblogger weblogger;
    private WeblogEntryManager entries;
    private ThemeManager themes;
    private WeblogTheme theme;
    private WeblogWrapper wrapper;

    @BeforeEach
    void setUp() throws Exception {
        weblog = new Weblog();
        weblog.setHandle("testblog");
        weblog.setLocale("en_US");
        weblog.setLastModified(new Date(1_700_000_000_000L));
        weblog.setCreatorUserName("alice");

        urls = mock(URLStrategy.class);
        entries = mock(WeblogEntryManager.class);
        themes = mock(ThemeManager.class);
        theme = mock(WeblogTheme.class);
        weblogger = mock(Weblogger.class);
        when(weblogger.getUrlStrategy()).thenReturn(urls);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        when(weblogger.getThemeManager()).thenReturn(themes);
        when(themes.getTheme(weblog)).thenReturn(theme);

        wrapper = WeblogWrapper.wrap(weblog, urls);
    }

    private <T> T withWeblogger(Supplier<T> body) {
        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);
            return body.get();
        }
    }

    @Test
    void theRelativeAndAbsoluteUrlsAreKeptDistinct() {
        when(urls.getWeblogURL(weblog, null, false)).thenReturn("/roller/testblog/");
        when(urls.getWeblogURL(weblog, null, true)).thenReturn("http://example.com/testblog/");

        assertEquals("/roller/testblog/", withWeblogger(wrapper::getURL));
        assertEquals("http://example.com/testblog/", withWeblogger(wrapper::getAbsoluteURL),
                "Feeds and notification e-mails are read away from the site and need the "
                        + "absolute form; swapping the two produces links that 404 in a "
                        + "reader and work in a browser");
    }

    @Test
    void countsComeFromTheEntryManager() throws Exception {
        when(entries.getEntryCount(weblog)).thenReturn(13L);

        assertEquals(13L, withWeblogger(wrapper::getEntryCount));
    }

    @Test
    void recentContentListsComeBackWrapped() throws Exception {
        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setAnchor("hello-world");
        entry.setTitle("Hello World");
        TagStat tag = new TagStat();
        tag.setName("java");

        when(entries.getWeblogEntries(any())).thenReturn(List.of(entry));
        when(entries.getPopularTags(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(tag));

        assertEquals("Hello World",
                withWeblogger(() -> wrapper.getRecentWeblogEntries("General", 5))
                        .get(0).getTitle(),
                "Recent entries must reach the theme wrapped, not as raw pojos");
        assertEquals("Hello World",
                withWeblogger(() -> wrapper.getRecentWeblogEntriesByTag("java", 5))
                        .get(0).getTitle());
        assertEquals(List.of(tag), withWeblogger(() -> wrapper.getPopularTags(30, 5)),
                "Tag statistics carry no mutable state, so they are passed through as-is");
    }

    @Test
    void singleEntryAndCategoryLookupsComeBackWrapped() throws Exception {
        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setAnchor("hello-world");
        entry.setTitle("Hello World");
        WeblogCategory category = new WeblogCategory();
        category.setName("Travel");

        when(entries.getWeblogEntryByAnchor(weblog, "hello-world")).thenReturn(entry);
        when(entries.getWeblogCategoryByName(weblog, "Travel")).thenReturn(category);

        assertEquals("Hello World",
                withWeblogger(() -> wrapper.getWeblogEntry("hello-world")).getTitle());
        assertEquals("Travel",
                withWeblogger(() -> wrapper.getWeblogCategory("Travel")).getName());
        assertNull(withWeblogger(() -> wrapper.getWeblogEntry("no-such-anchor")),
                "An anchor that matches nothing must wrap to null so the permalink page "
                        + "can render a 404");
    }

    @Test
    void theCreatorComesBackWrapped() throws Exception {
        User alice = new User();
        alice.setUserName("alice");
        alice.setScreenName("Alice A");
        UserManager users = mock(UserManager.class);
        when(weblogger.getUserManager()).thenReturn(users);
        when(users.getUserByUserName("alice")).thenReturn(alice);

        assertEquals("Alice A", withWeblogger(() -> wrapper.getCreator()).getScreenName());
    }

    @Test
    void lastModifiedIsTheWeblogsOwn() {
        assertEquals(new Date(1_700_000_000_000L), wrapper.getLastModified(),
                "Theme caching keys on this timestamp, so it has to be the weblog's own "
                        + "rather than a fresh clock reading");
    }

    @Test
    void templateLookupsGoThroughTheWeblogsThemeAndComeBackWrapped() throws Exception {
        WeblogTemplate byAction = template("Weblog", "weblog");
        WeblogTemplate byName = template("About", "about");
        WeblogTemplate byLink = template("Archive", "archive");
        when(theme.getTemplateByAction(ComponentType.WEBLOG)).thenReturn(byAction);
        when(theme.getTemplateByName("About")).thenReturn(byName);
        when(theme.getTemplateByLink("archive")).thenReturn(byLink);
        org.mockito.Mockito.<List<? extends org.apache.roller.weblogger.pojos.ThemeTemplate>>when(
                theme.getTemplates()).thenReturn(List.of(byAction, byName));

        // Three distinct templates, so a lookup that consulted the wrong one
        // would return the wrong name rather than the right one by accident.
        assertEquals("Weblog",
                withWeblogger(() -> lookup(() -> wrapper.getTemplateByAction(ComponentType.WEBLOG)))
                        .getName());
        assertEquals("About",
                withWeblogger(() -> lookup(() -> wrapper.getTemplateByName("About"))).getName());
        assertEquals("Archive",
                withWeblogger(() -> lookup(() -> wrapper.getTemplateByLink("archive"))).getName());
        assertEquals(2, withWeblogger(() -> {
            try {
                return wrapper.getTemplates();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }).size());
    }

    @Test
    void theStylesheetUrlIsBuiltFromTheThemesStylesheetTemplate() throws Exception {
        WeblogTemplate stylesheet = template("Stylesheet", "custom.css");
        when(theme.getStylesheet()).thenReturn(stylesheet);
        when(urls.getWeblogPageURL(weblog, null, "custom.css", null, null, null, null, 0, false))
                .thenReturn("/roller/testblog/page/custom.css");

        assertEquals("/roller/testblog/page/custom.css", withWeblogger(() -> {
            try {
                return wrapper.getStylesheet();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }), "The stylesheet link has to be resolved through the URL strategy; emitting "
                + "the template's own link would produce a URL relative to the page");
    }

    @Test
    void aThemeWithNoStylesheetProducesNoStylesheetLink() throws Exception {
        when(theme.getStylesheet()).thenReturn(null);

        assertNull(withWeblogger(() -> {
            try {
                return wrapper.getStylesheet();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }), "A theme with no stylesheet must produce no <link>, not one pointing at "
                + "nothing");
    }

    private static <T> T lookup(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private WeblogTemplate template(String name, String link) {
        WeblogTemplate template = new WeblogTemplate();
        template.setName(name);
        template.setLink(link);
        template.setWeblog(weblog);
        return template;
    }
}
