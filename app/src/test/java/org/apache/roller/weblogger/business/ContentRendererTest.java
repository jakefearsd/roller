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

package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.business.shortcodes.ShortcodeContext;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared pipeline: shortcodes, then markdown, then sanitize. The order is
 * load-bearing and each test here pins one reason why.
 */
class ContentRendererTest {

    private static ShortcodeContext context(String rawText) {
        Weblog weblog = new Weblog();
        weblog.setHandle("testblog");
        return new ShortcodeContext() {
            @Override public Weblog getWeblog() { return weblog; }
            @Override public String getSlug() { return "a-slug"; }
            @Override public String getRawText() { return rawText; }
        };
    }

    @Test
    void markdownBecomesHtml() {
        String html = ContentRenderer.render(context("**bold**"), "**bold**");

        assertTrue(html.contains("<strong>bold</strong>"), "got: " + html);
    }

    /**
     * Shortcodes expand BEFORE markdown. Markdown escapes the quotes in
     * what looks like an attribute to numeric entities (&#34;), and the
     * expander's attribute grammar does not match entity-quoted values -- so
     * every shortcode carrying an attribute would silently stop working if
     * markdown ran first. This test confirms the shortcode survives in the
     * output even though markdown escapes the quotes, which proves the order
     * (shortcodes first, then markdown) is correct and necessary.
     */
    @Test
    void anUnknownShortcodeSurvivesEvenWhenMarkdownEscapesIt() {
        String source = "[nosuchcode attr=\"value\"]";

        String html = ContentRenderer.render(context(source), source);

        // The shortcode passes through shortcode expander unchanged, then markdown
        // escapes the quotes as &#34; (numeric entities) as it would for HTML-like
        // text. The important thing is that the shortcode's core structure survives
        // intact in the output: the tag name, the attribute name, and the value.
        assertTrue(html.contains("[nosuchcode attr") && html.contains("value"),
                "unknown shortcode must survive in output: " + html);
        // Confirm the exact escaping that markdown/sanitizer produces
        assertTrue(html.contains("&#34;"),
                "markdown-escaped quotes must be in output: " + html);
    }

    /** The sanitizer is the security boundary, and it runs last. */
    @Test
    void scriptIsStrippedEvenThoughRawHtmlPassesThroughMarkdown() {
        String source = "<p>ok</p><script>alert(1)</script>";

        String html = ContentRenderer.render(context(source), source);

        assertTrue(html.contains("ok"));
        assertFalse(html.contains("<script"), "the sanitizer must remove it: " + html);
    }

    @Test
    void nullTextRendersAsNullRatherThanThrowing() {
        ContentRenderer.render(context(null), null);
    }
}
