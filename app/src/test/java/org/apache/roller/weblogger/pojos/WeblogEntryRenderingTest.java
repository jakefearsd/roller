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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The lookups {@link WeblogEntry}, {@link Weblog} and {@link MediaFile} still
 * perform from inside getters -- creators resolved by name, urls built from
 * the url strategy -- which reach the business tier through the static
 * locator until plan Tasks 15 and 16 move them out. (The render pipeline that
 * used to be tested here -- plugins, shortcodes, {@code displayContent} --
 * is {@code EntryRenderer}'s now; see {@code EntryRendererTest}, which holds
 * the same assertions against the service.)
 */
class WeblogEntryRenderingTest {

    private Weblog weblog;
    private WeblogEntry entry;
    private Weblogger weblogger;

    @BeforeEach
    void setUp() {
        weblog = new Weblog();
        weblog.setHandle("testblog");
        weblog.setLocale("en_US");

        entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setAnchor("hello-world");

        weblogger = mock(Weblogger.class);
    }

    private <T> T withWeblogger(java.util.function.Supplier<T> body) {
        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);
            return body.get();
        }
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
}
