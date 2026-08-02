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
package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The markdown step where it actually lives: inside {@code WeblogEntry}'s
 * render funnel, ahead of shortcode expansion and the sanitizer.
 *
 * <p>Two properties matter more than the conversion itself. An entry that did
 * not opt in must render byte for byte as it did before markdown existed --
 * every entry in every existing blog is such an entry. And the ordering must
 * be shortcodes-then-markdown: running markdown first would escape the quotes
 * in a shortcode's attributes to &amp;quot;, which the expander's grammar does
 * not match, silently breaking every shortcode in every markdown entry.
 */
class WeblogEntryMarkdownRenderingTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("mdrenderuser");
        weblog = TestUtils.setupWeblog("mdrenderblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private WeblogEntry entry(String contentType, String text) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(
                "md-" + Long.toString(System.nanoTime(), 36), weblog, user);
        WeblogEntry managed = WebloggerFactory.getWeblogger()
                .getWeblogEntryManager().getWeblogEntry(entry.getId());
        managed.setContentType(contentType);
        managed.setText(text);
        return managed;
    }

    // ------------------------------------------------------- the opt-in guard

    @Test
    void anEntryThatDidNotOptInIsUntouched() throws Exception {
        // The overwhelmingly common case: content_type null. Markdown syntax
        // in an HTML entry must stay literal -- an existing post full of
        // asterisks and underscores cannot start sprouting <em> tags.
        String source = "<p>A *starred* line and a # hash.</p>";
        assertEquals(entry(null, source).getTransformedText(),
                entry("text/html", source).getTransformedText(),
                "null and text/html must take the same path");
        String rendered = entry(null, source).getTransformedText();
        assertTrue(rendered.contains("*starred*"),
                "markdown emphasis must stay literal in an HTML entry: " + rendered);
        assertFalse(rendered.contains("<em>"), rendered);
        assertFalse(rendered.contains("<h1"), rendered);
    }

    @Test
    void aStrayLegacyContentTypeAlsoMeansHtml() throws Exception {
        // A value left by a long-removed feature must fail safe, not reflow
        // somebody's decade-old post.
        String source = "<p>Legacy *content* here.</p>";
        assertEquals(entry(null, source).getTransformedText(),
                entry("application/atom+xml", source).getTransformedText());
    }

    // ---------------------------------------------------------- the ordering

    @Test
    void shortcodesExpandBeforeMarkdownRuns() throws Exception {
        // Top risk 1 of the wave, and the order is the reverse of the
        // intuitive one. Markdown-first would escape the quotes in
        // [image id="..."] to &quot;, which the expander's attribute grammar
        // does not match -- every attribute-carrying shortcode would silently
        // stop working in markdown entries, which is the whole feature.
        // Expanding first is safe because commonmark passes the emitted raw
        // HTML through verbatim, in block and inline positions alike.
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "md-order-probe.jpg");
        String imageId = image.getId();
        TestUtils.endSession(true);
        WeblogEntry entry = entry("text/markdown",
                "> Notes:\n>\n> - first\n> - second\n\n[image id=\""
                        + imageId + "\"]\n");
        String rendered = entry.getTransformedText();

        assertTrue(rendered.contains("<blockquote>"), "markdown ran: " + rendered);
        assertTrue(rendered.contains("<li>first</li>"), "and produced the list: " + rendered);
        assertTrue(rendered.contains("<figure class=\"shortcode-image\""),
                "the shortcode must have expanded, which only happens if the expander "
                        + "saw real quotes rather than &quot;: " + rendered);
        assertFalse(rendered.contains("[image id="),
                "and no shortcode source may survive: " + rendered);
    }

    @Test
    void markdownEntriesAreStillSanitized() throws Exception {
        // commonmark passes raw HTML through by design, so the sanitizer is
        // the boundary -- and it runs last, after markdown and shortcodes.
        WeblogEntry entry = entry("text/markdown",
                "Careful now\n\n<script>alert(1)</script>\n");
        String rendered = entry.getTransformedText();
        assertFalse(rendered.contains("<script"),
                "the sanitizer must strip script from markdown output too: " + rendered);
    }

    @Test
    void theSummaryTakesTheSamePath() throws Exception {
        WeblogEntry entry = entry("text/markdown", "unused");
        entry.setSummary("A **bold** summary.");
        assertTrue(entry.getTransformedSummary().contains("<strong>bold</strong>"),
                "getTransformedSummary must honour the flag too: "
                        + entry.getTransformedSummary());
    }

    @Test
    void theFormatSurvivesASaveAndReload() throws Exception {
        // The flag is worthless if it does not persist: the editor sets it,
        // the entry is saved, and the next page load must still know the entry
        // is markdown.
        WeblogEntry entry = TestUtils.setupWeblogEntry(
                "md-persist-" + Long.toString(System.nanoTime(), 36), weblog, user);
        String id = entry.getId();
        WeblogEntryManager mgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(id);
        managed.setContentType("text/markdown");
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);

        assertEquals("text/markdown",
                WebloggerFactory.getWeblogger().getWeblogEntryManager()
                        .getWeblogEntry(id).getContentType(),
                "content_type must round-trip through the database");
    }
}
