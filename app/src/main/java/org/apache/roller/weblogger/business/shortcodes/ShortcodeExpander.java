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
package org.apache.roller.weblogger.business.shortcodes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.pojos.WeblogEntry;

/**
 * Expands {@code [name attr="value"]body[/name]} shortcodes in entry text
 * into HTML, using a registry of {@link ShortcodeHandler}s.
 *
 * <p>Syntax rules:
 * <ul>
 *   <li>{@code [name attr="v"]body[/name]} -- body form; the body is
 *       expanded recursively first, so different shortcodes may nest.</li>
 *   <li>{@code [name attr="v"]} or {@code [name attr="v" /]} -- self-closing
 *       (no matching {@code [/name]} means self-closing too).</li>
 *   <li>Attribute values may be double-quoted, single-quoted, or bare
 *       (no whitespace); names are matched case-insensitively.</li>
 *   <li>{@code [[name ...]]} escapes a REGISTERED shortcode: it renders as
 *       the literal text {@code [name ...]}.</li>
 *   <li>Anything that is not a registered shortcode -- unknown names,
 *       unmatched brackets, malformed tags -- passes through completely
 *       unchanged. That keeps this expander forward-compatible: text written
 *       today with a Wave 2/3 shortcode renders as-is until the handler
 *       ships.</li>
 * </ul>
 *
 * <p>Expansion runs UNCONDITIONALLY in both entry render paths
 * ({@code WeblogEntry#render} and
 * {@code PluginManagerImpl#applyWeblogEntryPlugins}), before
 * {@code HTMLSanitizer.conditionallySanitize} -- shortcodes are not
 * opt-in-per-entry the way named {@code WeblogEntryPlugin}s are (deliberate
 * contract change; see docs/superpowers/plans/2026-08-01-stage2-wave1-media-seo.md).
 *
 * <p>Caching: expansion is deterministic for a given entry text and media
 * metadata, so it is safe inside the whole-page render caches
 * (WeblogPageCache and friends) -- those are already invalidated via the
 * weblog's lastModified whenever content or media change.
 */
public final class ShortcodeExpander {

    private static final Log log = LogFactory.getLog(ShortcodeExpander.class);

    /**
     * How deep body expansion may recurse. Nesting beyond this leaves the
     * inner shortcodes unexpanded rather than risking a runaway.
     */
    private static final int MAX_NESTING_DEPTH = 10;

    /**
     * An opening (or self-closing) shortcode tag: {@code [name attrs]},
     * {@code [name attrs /]}, or the escaped {@code [[name attrs]}] prefix
     * (group 1 captures the doubled bracket). Attributes cannot contain
     * {@code ]}, which is what keeps an unterminated {@code [name} from
     * swallowing the rest of the text.
     */
    private static final Pattern OPEN_TAG =
            Pattern.compile("\\[(\\[?)([a-zA-Z][\\w-]*)((?:\\s[^\\]]*?)?)(/?)\\]");

    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\\]]+))");

    /** The default registry: every built-in shortcode, applied everywhere. */
    private static final ShortcodeExpander DEFAULT =
            new ShortcodeExpander(List.of(new ImageShortcode()));

    private final Map<String, ShortcodeHandler> handlers;

    public ShortcodeExpander(List<ShortcodeHandler> handlerList) {
        Map<String, ShortcodeHandler> byName = new LinkedHashMap<>();
        for (ShortcodeHandler handler : handlerList) {
            byName.put(handler.getName().toLowerCase(Locale.ROOT), handler);
        }
        this.handlers = Collections.unmodifiableMap(byName);
    }

    /** The expander both render call-sites use, with all built-in handlers registered. */
    public static ShortcodeExpander defaultExpander() {
        return DEFAULT;
    }

    /**
     * Expands every registered shortcode in {@code text}. Null-safe; returns
     * {@code text} itself when there is nothing to do. A handler that throws
     * or returns null leaves its shortcode in the output exactly as written.
     */
    public String expand(WeblogEntry entry, String text) {
        return expand(entry, text, 0);
    }

    private String expand(WeblogEntry entry, String text, int depth) {
        if (text == null || handlers.isEmpty() || depth > MAX_NESTING_DEPTH
                || text.indexOf('[') < 0) {
            return text;
        }

        Matcher matcher = OPEN_TAG.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        boolean expandedAnything = false;

        while (last < text.length() && matcher.find(last)) {
            String name = matcher.group(2).toLowerCase(Locale.ROOT);
            ShortcodeHandler handler = handlers.get(name);
            if (handler == null) {
                // Unknown shortcode: pass through unchanged (forward-compat).
                out.append(text, last, matcher.end());
                last = matcher.end();
                continue;
            }

            out.append(text, last, matcher.start());

            boolean doubledBracket = !matcher.group(1).isEmpty();
            if (doubledBracket && matcher.end() < text.length()
                    && text.charAt(matcher.end()) == ']') {
                // [[name ...]] is the escape form: emit the literal [name ...]
                out.append(text, matcher.start() + 1, matcher.end());
                last = matcher.end() + 1;
                expandedAnything = true;
                continue;
            }
            if (doubledBracket) {
                // "[[name]" with no closing pair: the first bracket is just text.
                out.append('[');
            }
            int tagStart = matcher.start() + (doubledBracket ? 1 : 0);

            // Body: text up to the first [/name], unless written self-closing.
            String body = null;
            int consumedEnd = matcher.end();
            if (matcher.group(4).isEmpty()) {
                String closer = "[/" + name + "]";
                int closerAt = text.indexOf(closer, matcher.end());
                if (closerAt >= 0) {
                    body = expand(entry, text.substring(matcher.end(), closerAt), depth + 1);
                    consumedEnd = closerAt + closer.length();
                }
            }

            String replacement = null;
            try {
                replacement = handler.render(parseAttributes(matcher.group(3)), body, entry);
            } catch (Exception e) {
                log.warn("Shortcode [" + name + "] handler failed; leaving the "
                        + "shortcode text as the author wrote it", e);
            }
            if (replacement != null) {
                out.append(replacement);
                expandedAnything = true;
            } else {
                out.append(text, tagStart, consumedEnd);
            }
            last = consumedEnd;
        }

        if (!expandedAnything) {
            return text;
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    private static Map<String, String> parseAttributes(String attributeText) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (attributeText == null || attributeText.isBlank()) {
            return attributes;
        }
        Matcher matcher = ATTRIBUTE.matcher(attributeText);
        while (matcher.find()) {
            String value = matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3)
                    : matcher.group(4);
            attributes.put(matcher.group(1).toLowerCase(Locale.ROOT), value);
        }
        return attributes;
    }
}
