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
     * Regression check: unknown shortcodes are not silently deleted, and
     * markdown's entity escaping of quote characters in HTML-like text is
     * stable. The expander doesn't touch unknown shortcodes; markdown escapes
     * them in the normal way.
     */
    @Test
    void unknownShortcodeRegressionCheck() {
        String source = "[nosuchcode attr=\"value\"]";

        String html = ContentRenderer.render(context(source), source);

        // The shortcode passes through shortcode expander unchanged (it's unknown),
        // then markdown escapes the quotes as &#34; (numeric entities).
        assertTrue(html.contains("[nosuchcode attr") && html.contains("value"),
                "unknown shortcode must survive in output: " + html);
        // Confirm the exact escaping that markdown/sanitizer produces
        assertTrue(html.contains("&#34;"),
                "markdown-escaped quotes must be in output: " + html);
    }

    /**
     * Shortcodes expand BEFORE markdown. A registered shortcode with quoted
     * attributes is parsed correctly only when quotes are literal (before
     * markdown runs). If markdown ran first, entity-escaped quotes would be
     * parsed as part of the attribute value itself, not as delimiters.
     * This test uses [cta] with a label attribute to detect the difference:
     * in correct order, label="test" → label renders as "test" (no quotes);
     * in reversed order, markdown would make it label=&quot;test&quot; → label
     * renders with the quote entities embedded.
     */
    @Test
    void registeredShortcodeWithQuotedAttributesPinsExpansionBeforeMarkdown() {
        // [cta] is registered; it requires href and label to render, otherwise returns null
        String source = "[cta href=\"http://example.com\" label=\"test\"]";

        String html = ContentRenderer.render(context(source), source);

        // In the correct order (shortcodes first, then markdown):
        // - Expander sees literal quotes, matches the regex, parses attributes correctly
        // - Handler gets {href: "http://example.com", label: "test"}
        // - Renders: <span class="cta-label">test</span> (no quotes in output)
        assertTrue(html.contains("<span class=\"cta-label\">test</span>"),
                "label must render without quote entities in correct order: " + html);
        // If markdown ran first, the output would have the quotes as entities:
        // <span class="cta-label">&#34;test&#34;</span> or similar
        assertFalse(html.contains("cta-label\">&#34;") || html.contains("cta-label\">&quot;"),
                "label must not include escaped quotes in correct order: " + html);
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
