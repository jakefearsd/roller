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
package org.apache.roller.weblogger.pojos;

import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.plugins.PluginManager;
import org.apache.roller.weblogger.business.plugins.entry.WeblogEntryPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Covers how a {@link WeblogEntry} turns itself into the text a theme renders.
 *
 * <p>Two rules live here. Plugins named in the entry's {@code plugins} column
 * are applied, and ones that are not named are left alone -- an entry must not
 * silently pick up a plugin its author did not choose. And
 * {@code displayContent} decides between the summary and the full text
 * depending on whether it was given a "read more" link, which is what makes the
 * same entry render short on the front page and in full on its permalink page.
 */
class WeblogEntryRenderingTest {

    private Weblog weblog;
    private WeblogEntry entry;
    private Weblogger weblogger;
    private PluginManager pluginManager;

    @BeforeEach
    void setUp() {
        weblog = new Weblog();
        weblog.setHandle("testblog");
        weblog.setLocale("en_US");

        entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setAnchor("hello-world");

        pluginManager = mock(PluginManager.class);
        weblogger = mock(Weblogger.class);
        when(weblogger.getPluginManager()).thenReturn(pluginManager);
    }

    private void installPlugins(Map<String, WeblogEntryPlugin> plugins) {
        when(pluginManager.getWeblogEntryPlugins(weblog)).thenReturn(plugins);
    }

    private <T> T withWeblogger(java.util.function.Supplier<T> body) {
        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);
            return body.get();
        }
    }

    private static WeblogEntryPlugin shouting() throws Exception {
        WeblogEntryPlugin plugin = mock(WeblogEntryPlugin.class);
        when(plugin.getName()).thenReturn("Shout");
        when(plugin.render(any(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1).toString().toUpperCase());
        return plugin;
    }

    @Test
    void onlyThePluginsTheEntryNamesAreApplied() throws Exception {
        WeblogEntryPlugin shout = shouting();
        WeblogEntryPlugin unused = mock(WeblogEntryPlugin.class);
        when(unused.getName()).thenReturn("Unused");
        when(unused.render(any(), anyString())).thenReturn("SHOULD NOT APPEAR");
        installPlugins(Map.of("Shout", shout, "Unused", unused));

        entry.setText("hello");
        entry.setSummary("summary");
        entry.setPlugins("Shout");

        assertEquals("HELLO", withWeblogger(entry::getTransformedText),
                "A plugin the entry names must be applied to its body");
        assertEquals("SUMMARY", withWeblogger(entry::getTransformedSummary),
                "and to its summary");
    }

    @Test
    void anEntryThatNamesNoPluginsIsRenderedUntouched() throws Exception {
        installPlugins(Map.of("Shout", shouting()));
        entry.setText("hello");
        entry.setPlugins(null);

        assertEquals("hello", withWeblogger(entry::getTransformedText),
                "An entry that opted into no plugins must not have one applied anyway; "
                        + "the plugin list is per-entry precisely so authors can choose");
    }

    @Test
    void aPluginThatBlowsUpLeavesTheEntryRenderable() throws Exception {
        WeblogEntryPlugin broken = mock(WeblogEntryPlugin.class);
        when(broken.getName()).thenReturn("Broken");
        when(broken.render(any(), anyString())).thenThrow(new RuntimeException("boom"));
        installPlugins(Map.of("Broken", broken));

        entry.setText("hello");
        entry.setPlugins("Broken");

        assertEquals("hello", withWeblogger(entry::getTransformedText),
                "A failing plugin must leave the text as it found it rather than taking "
                        + "out the whole page; plugins are third-party code");
    }

    @Test
    void anEntryWithNoTextRendersAsNothingRatherThanThrowing() throws Exception {
        installPlugins(Map.of("Shout", shouting()));
        entry.setText(null);
        entry.setPlugins("Shout");

        assertNull(withWeblogger(entry::getTransformedText),
                "An entry with no body must render as nothing; passing null into the "
                        + "plugin chain would NPE inside third-party code");
    }

    // -------------------------------------------------------- display content

    @Test
    void withoutAReadMoreLinkTheFullTextIsPreferred() throws Exception {
        installPlugins(Map.of());
        entry.setText("The whole post");
        entry.setSummary("Just a taste");

        assertEquals("The whole post", withWeblogger(entry::getDisplayContent),
                "No read-more link means this is the permalink page, which shows the "
                        + "whole post rather than the teaser");
        assertEquals("The whole post", withWeblogger(() -> entry.displayContent(null)));
        assertEquals("The whole post", withWeblogger(() -> entry.displayContent("  ")),
                "A blank link is not a link");
        assertEquals("The whole post", withWeblogger(() -> entry.displayContent("nil")),
                "Velocity has no null literal, so themes pass the string 'nil' -- it must "
                        + "be understood as 'no link'");
    }

    @Test
    void withoutTextTheSummaryStandsIn() throws Exception {
        installPlugins(Map.of());
        entry.setText("");
        entry.setSummary("Just a taste");

        assertEquals("Just a taste", withWeblogger(entry::getDisplayContent),
                "An entry with only a summary must still render something on its own page");
    }

    @Test
    void withAReadMoreLinkTheSummaryIsPreferredAndTheLinkAppended() throws Exception {
        installPlugins(Map.of());
        entry.setText("The whole post");
        entry.setSummary("Just a taste");

        String rendered = withWeblogger(() -> entry.displayContent("http://example.com/entry"));

        assertTrue(rendered.startsWith("Just a taste"),
                "A read-more link means this is a list page, which shows the teaser: "
                        + rendered);
        assertTrue(rendered.contains("http://example.com/entry"),
                "and the link the caller supplied must appear in the appended markup: "
                        + rendered);
    }

    @Test
    void withAReadMoreLinkButNoSummaryTheFullTextIsShown() throws Exception {
        installPlugins(Map.of());
        entry.setText("The whole post");
        entry.setSummary(null);

        assertEquals("The whole post",
                withWeblogger(() -> entry.displayContent("http://example.com/entry")),
                "An entry with no teaser has to show its body on the list page; showing "
                        + "nothing but a read-more link would give the reader no reason to "
                        + "click it");
    }

    @Test
    void anEntryWithASummaryButNoTextGetsNoReadMoreLink() throws Exception {
        installPlugins(Map.of());
        entry.setText("");
        entry.setSummary("Just a taste");

        String rendered = withWeblogger(() -> entry.displayContent("http://example.com/entry"));

        assertEquals("Just a taste", rendered,
                "There is nothing more to read, so offering a 'read more' link would take "
                        + "the reader to the same words again");
    }

    // ------------------------------------------------------------- creators

    @Test
    void theCreatorIsResolvedFromTheStoredUsername() throws Exception {
        User alice = new User();
        alice.setUserName("alice");
        UserManager users = mock(UserManager.class);
        when(weblogger.getUserManager()).thenReturn(users);
        when(users.getUserByUserName("alice")).thenReturn(alice);

        entry.setCreatorUserName("alice");
        weblog.setCreatorUserName("alice");

        assertSame(alice, withWeblogger(entry::getCreator));
        assertSame(alice, withWeblogger(weblog::getCreator));
    }

    @Test
    void aDeletedCreatorResolvesToNullRatherThanBreakingTheRender() throws Exception {
        UserManager users = mock(UserManager.class);
        when(weblogger.getUserManager()).thenReturn(users);
        when(users.getUserByUserName("ghost"))
                .thenThrow(new org.apache.roller.weblogger.WebloggerException("gone"));

        entry.setCreatorUserName("ghost");
        weblog.setCreatorUserName("ghost");

        assertNull(withWeblogger(entry::getCreator),
                "An entry whose author was deleted must still render, without a byline");
        assertNull(withWeblogger(weblog::getCreator));
    }

    // ---------------------------------------------------------------- urls

    @Test
    void thePermalinkComesFromTheUrlStrategy() {
        URLStrategy urls = mock(URLStrategy.class);
        when(weblogger.getUrlStrategy()).thenReturn(urls);
        when(urls.getWeblogEntryURL(weblog, null, "hello-world", true))
                .thenReturn("http://example.com/roller/testblog/entry/hello-world");

        assertEquals("http://example.com/roller/testblog/entry/hello-world",
                withWeblogger(entry::getPermalink),
                "Permalinks are built centrally so that changing the URL scheme changes "
                        + "them everywhere at once; the entry must not build its own");
    }

    @Test
    void mediaFileUrlsComeFromTheUrlStrategyToo() {
        URLStrategy urls = mock(URLStrategy.class);
        when(weblogger.getUrlStrategy()).thenReturn(urls);

        MediaFile file = new MediaFile();
        file.setId("file-1");
        file.setWeblog(weblog);
        when(urls.getMediaFileURL(weblog, "file-1", true)).thenReturn("http://example.com/f");
        when(urls.getMediaFileThumbnailURL(weblog, "file-1", true))
                .thenReturn("http://example.com/f/thumb");

        assertEquals("http://example.com/f", withWeblogger(file::getPermalink));
        assertEquals("http://example.com/f/thumb", withWeblogger(file::getThumbnailURL),
                "The thumbnail URL must be distinct from the full-size one, or the "
                        + "gallery serves full-resolution images as thumbnails");
    }

    @Test
    void aMediaFileResolvesItsCreatorAndFailsSoftly() throws Exception {
        User alice = new User();
        alice.setUserName("alice");
        UserManager users = mock(UserManager.class);
        when(weblogger.getUserManager()).thenReturn(users);
        when(users.getUserByUserName("alice")).thenReturn(alice);
        when(users.getUserByUserName("ghost"))
                .thenThrow(new org.apache.roller.weblogger.WebloggerException("gone"));

        MediaFile file = new MediaFile();
        file.setCreatorUserName("alice");
        assertSame(alice, withWeblogger(file::getCreator));

        file.setCreatorUserName("ghost");
        assertNull(withWeblogger(file::getCreator),
                "A file uploaded by a since-deleted user must still be listable");
    }

    @Test
    void initializedPluginsAreFetchedOnceAndCached() {
        installPlugins(Map.of());

        Map<String, WeblogEntryPlugin> first = withWeblogger(weblog::getInitializedPlugins);
        Map<String, WeblogEntryPlugin> second = weblog.getInitializedPlugins();

        assertSame(first, second,
                "The plugin set is fetched once per weblog and reused; re-initialising it "
                        + "for every entry on a page would repeat the work for each post. "
                        + "The second call here runs without a Weblogger at all, which only "
                        + "works if the first result was cached.");
    }
}
