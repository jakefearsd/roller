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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The markdown conversion itself: the extension set that is on, the raw-HTML
 * passthrough the shortcodes depend on, and the explicit note that commonmark
 * is not the security boundary.
 */
class MarkdownRendererTest {


    @Test
    void blankInputComesBackUnchanged() {
        assertNull(MarkdownRenderer.render(null));
        assertEquals("", MarkdownRenderer.render(""));
        assertEquals("   ", MarkdownRenderer.render("   "));
    }

    @Test
    void rendersTheBasics() {
        assertEquals("<h1>Iceland</h1>\n", MarkdownRenderer.render("# Iceland"));
        assertEquals("<p>A <strong>long</strong> drive.</p>\n",
                MarkdownRenderer.render("A **long** drive."));
        assertTrue(MarkdownRenderer.render("- one\n- two").contains("<li>one</li>"));
    }

    @Test
    void theThreeExtensionsAreOn() {
        assertTrue(MarkdownRenderer.render("| a | b |\n| - | - |\n| 1 | 2 |").contains("<table>"),
                "tables extension");
        assertTrue(MarkdownRenderer.render("~~cancelled~~").contains("<del>"),
                "strikethrough extension");
        assertTrue(MarkdownRenderer.render("See https://example.com for details")
                        .contains("<a href=\"https://example.com\">"),
                "autolink extension");
    }

    @Test
    void rawHtmlPassesThroughUntouched() {
        // Authors paste embed snippets, and shortcodes expand into HTML right
        // after this step. Escaping here would show both as source text.
        String html = MarkdownRenderer.render("Before\n\n<div class=\"embed\">kept</div>\n\nAfter");
        assertTrue(html.contains("<div class=\"embed\">kept</div>"),
                "raw block HTML must survive: " + html);
        assertFalse(html.contains("&lt;div"), "and must not be escaped: " + html);
    }

    @Test
    void markdownIsNotTheSecurityBoundary() {
        // Documented expectation, pinned so nobody "fixes" it by turning on
        // escaping and assuming that made things safe: commonmark emits the
        // script tag, and HTMLSanitizer -- which runs after -- removes it.
        // WeblogEntryMarkdownRenderingTest proves the removal end to end.
        assertTrue(MarkdownRenderer.render("<script>alert(1)</script>").contains("<script>"),
                "commonmark passes raw HTML through by design; the sanitizer is the boundary");
    }
}
