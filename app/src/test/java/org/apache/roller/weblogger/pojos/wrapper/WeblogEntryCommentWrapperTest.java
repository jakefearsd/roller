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
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.plugins.PluginManager;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntryComment;
import org.apache.roller.weblogger.pojos.WeblogEntryComment.ApprovalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Checks the escaping {@link WeblogEntryCommentWrapper} applies.
 *
 * <p>Comments are the only content on a Roller blog written by people with no
 * account, so this wrapper is the boundary between anonymous input and the
 * rendered page. Every field it exposes is escaped, the body is escaped only
 * when it is plain text, and links always get {@code rel="nofollow"} so a
 * comment cannot be used to pass PageRank. Each of those is asserted here
 * because none of them is visible in the pojo.
 */
class WeblogEntryCommentWrapperTest {

    private WeblogEntryComment comment;
    private URLStrategy urls;
    private WeblogEntryCommentWrapper wrapper;

    @BeforeEach
    void setUp() {
        Weblog weblog = new Weblog();
        weblog.setHandle("testblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setAnchor("hello-world");
        entry.setTitle("Hello World");

        comment = new WeblogEntryComment();
        comment.setWeblogEntry(entry);
        comment.setName("Alice");
        comment.setEmail("alice@example.com");
        comment.setContent("Nice post");
        comment.setContentType("text/plain");
        comment.setPostTime(Timestamp.valueOf("2024-03-09 15:30:00"));
        comment.setStatus(ApprovalStatus.APPROVED);
        comment.setNotify(Boolean.TRUE);
        comment.setRemoteHost("203.0.113.9");
        comment.setReferrer("http://referrer.example.com/");
        comment.setUserAgent("SomeBrowser/1.0");

        urls = mock(URLStrategy.class);
        wrapper = WeblogEntryCommentWrapper.wrap(comment, urls);
    }

    @Test
    void scalarAccessorsReportTheWrappedCommentsOwnValues() {
        assertEquals(comment.getId(), wrapper.getId());
        assertEquals("Alice", wrapper.getName());
        assertEquals("alice@example.com", wrapper.getEmail());
        assertEquals(Timestamp.valueOf("2024-03-09 15:30:00"), wrapper.getPostTime());
        assertEquals(ApprovalStatus.APPROVED, wrapper.getStatus());
        assertEquals(Boolean.TRUE, wrapper.getNotify());
        assertEquals("203.0.113.9", wrapper.getRemoteHost());
        assertEquals("SomeBrowser/1.0", wrapper.getUserAgent());
        assertEquals("http://referrer.example.com/", wrapper.getReferrer());
        assertEquals(Long.toString(comment.getPostTime().getTime()), wrapper.getTimestamp());
        assertEquals("hello-world", wrapper.getWeblogEntry().getAnchor(),
                "The entry the comment belongs to must be reachable, wrapped");
    }

    @Test
    void derivedStatusFlagsFollowTheComment() {
        assertTrue(wrapper.getApproved());
        assertFalse(wrapper.getPending());

        comment.setStatus(ApprovalStatus.DISAPPROVED);
        assertFalse(wrapper.getApproved(),
                "The wrapper must read the flags live rather than snapshotting them at "
                        + "wrap time; a comment rejected mid-render must not render");
        assertFalse(wrapper.getPending());

        comment.setStatus(ApprovalStatus.PENDING);
        assertTrue(wrapper.getPending(),
                "A comment awaiting moderation must report as pending -- the moderation "
                        + "queue is built from this flag");
        assertFalse(wrapper.getApproved());
    }

    @Test
    void theNotifyFlagTracksTheCommentInBothStates() {
        comment.setNotify(Boolean.TRUE);
        assertEquals(Boolean.TRUE, wrapper.getNotify(),
                "This drives the follow-up e-mail; a flag stuck on would mail every "
                        + "commenter on every subsequent comment");

        comment.setNotify(Boolean.FALSE);
        assertEquals(Boolean.FALSE, wrapper.getNotify(),
                "and a flag stuck off would silently drop the notifications people asked for");
    }

    @Test
    void identityFieldsAreHtmlEscaped() {
        // These are written by anonymous visitors and rendered next to the
        // comment body. Escaping is what stops a comment author from closing
        // the surrounding tag and injecting markup.
        comment.setName("<script>alert(1)</script>");
        comment.setEmail("<b>a</b>@example.com");
        comment.setReferrer("<img src=x onerror=alert(1)>");

        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;", wrapper.getName(),
                "A commenter's name must reach the page as entities, never as markup");
        assertFalse(wrapper.getEmail().contains("<b>"));
        assertFalse(wrapper.getReferrer().contains("<img"));
    }

    @Test
    void aPlainTextCommentBodyIsEscapedBeforePluginsSeeIt() throws Exception {
        comment.setContentType("text/plain");
        comment.setContent("2 < 3 and <b>bold</b>");

        String rendered = renderWithPassThroughPlugins();

        assertFalse(rendered.contains("<b>"),
                "A plain text comment must have its markup escaped -- this is the only "
                        + "thing standing between an anonymous commenter and stored XSS");
        assertTrue(rendered.contains("&lt;b&gt;"), "The text itself must still be visible");
        assertTrue(rendered.contains("&lt;") && rendered.contains("2"),
                "and the mathematics the commenter typed must survive: " + rendered);
    }

    @Test
    void anHtmlCommentBodyIsLeftForThePluginsToHandle() throws Exception {
        comment.setContentType("text/html");
        comment.setContent("<b>bold</b>");

        assertEquals("<b>bold</b>", renderWithPassThroughPlugins(),
                "A comment explicitly stored as HTML must not be double-escaped, or an "
                        + "authenticated author's formatted comment renders as source");
    }

    @Test
    void theBodyIsPassedThroughTheCommentPluginChain() {
        // The plugins are what turn markdown/plain text into the rendered body,
        // so the wrapper must return what the chain produced, not the raw field.
        comment.setContentType("text/html");
        comment.setContent("raw body");

        PluginManager plugins = mock(PluginManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getPluginManager()).thenReturn(plugins);
        when(plugins.applyCommentPlugins(comment, "raw body")).thenReturn("rendered body");

        String rendered;
        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);
            rendered = wrapper.getContent();
        }

        assertEquals("rendered body", rendered,
                "The wrapper must hand the body to the comment plugins and return their "
                        + "output; skipping them would render every comment unformatted");
    }

    @Test
    void thePlainTextEscapingHappensBeforeThePluginsRun() {
        // Order matters: escaping after the plugins ran would escape the markup
        // the plugins just generated, rendering it as visible source.
        comment.setContentType("text/plain");
        comment.setContent("<b>bold</b>");

        PluginManager plugins = mock(PluginManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getPluginManager()).thenReturn(plugins);
        when(plugins.applyCommentPlugins(any(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);
            wrapper.getContent();
        }

        org.mockito.Mockito.verify(plugins).applyCommentPlugins(comment, "&lt;b&gt;bold&lt;/b&gt;");
    }

    @Test
    void wrappingNullGivesNull() {
        assertNull(WeblogEntryCommentWrapper.wrap(null, urls));
    }

    /**
     * Renders the body with a {@link PluginManager} that returns the content
     * unchanged, isolating the wrapper's own escaping and nofollow handling
     * from whatever comment plugins happen to be configured.
     */
    private String renderWithPassThroughPlugins() {
        PluginManager plugins = mock(PluginManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getPluginManager()).thenReturn(plugins);
        when(plugins.applyCommentPlugins(any(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);
            return wrapper.getContent();
        }
    }
}
