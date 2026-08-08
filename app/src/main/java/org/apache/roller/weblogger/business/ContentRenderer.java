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
import org.apache.roller.weblogger.business.shortcodes.ShortcodeExpander;
import org.apache.roller.weblogger.util.HTMLSanitizer;

/**
 * The content pipeline every authored surface shares: shortcode expansion,
 * then Markdown, then sanitization.
 *
 * <p>The order is load-bearing and deliberately not the one it looks like it
 * should be. Markdown first would escape the quotes in
 * {@code [gallery dir="Iceland"]} to {@code &quot;}, and the expander's
 * attribute grammar does not match entity-quoted values, so every shortcode
 * carrying an attribute would silently stop working. Expanding first is safe
 * because commonmark passes raw HTML through verbatim in block and inline
 * positions alike; the only cost is that markdown syntax inside a shortcode's
 * own emitted text is interpreted, which is cosmetic.
 *
 * <p>Raw HTML is deliberately not escaped: the shortcodes emit HTML. The
 * sanitizer at the end is the security boundary.
 */
public final class ContentRenderer {

    private ContentRenderer() {
    }

    /**
     * @param content what is being rendered, for weblog and slug context
     * @param text    the source; null returns null
     */
    public static String render(ShortcodeContext content, String text) {
        if (text == null) {
            return null;
        }
        String out = ShortcodeExpander.defaultExpander().expand(content, text);
        out = MarkdownRenderer.render(out);
        return HTMLSanitizer.conditionallySanitize(out);
    }
}
