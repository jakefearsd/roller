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
 *       expanded recursively first, so shortcodes may nest. Same-name tags
 *       pair inside-out like HTML elements; when opens and closers do not
 *       balance, the outermost tag takes the last closer and the leftover
 *       inner opens fall back to self-closing (a closer that had a matching
 *       open never leaks into the output as raw text).</li>
 *   <li>{@code [name attr="v"]} or {@code [name attr="v" /]} -- self-closing
 *       (no matching {@code [/name]} means self-closing too).</li>
 *   <li>Attribute values may be double-quoted, single-quoted, or bare
 *       (no whitespace); a quoted value may contain {@code ]} and {@code [}.
 *       Names are matched case-insensitively. Quote anything URL-shaped or
 *       bracket-containing -- a bare value can contain neither.</li>
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
     * (group 1 captures the doubled bracket). The attribute section is
     * quote-aware: a {@code ]} inside a complete double- or single-quoted
     * value stays part of the value ({@code caption="Paris [2023]"}) rather
     * than ending the tag, while an unquoted (or unterminated-quote)
     * {@code ]} still ends it -- which is what keeps an unterminated
     * {@code [name} from swallowing the rest of the text. Group 4 catches
     * the no-attribute self-close ({@code [name/]}); a trailing {@code /}
     * after attributes lands inside group 3 and is handled by
     * {@link #isSelfClosing}.
     */
    private static final Pattern OPEN_TAG = Pattern.compile(
            "\\[(\\[?)([a-zA-Z][\\w-]*)((?:\\s(?:[^\\]\"']|\"[^\"]*\"|'[^']*')*)?)(/?)\\]");

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

            boolean selfClosing = isSelfClosing(matcher.group(3), matcher.group(4));
            String attributeText = matcher.group(3);
            if (selfClosing) {
                // strip the explicit self-close slash so it never reads as
                // part of a bare attribute value
                String stripped = attributeText.stripTrailing();
                if (stripped.endsWith("/")) {
                    attributeText = stripped.substring(0, stripped.length() - 1);
                }
            }

            // Body: text up to the MATCHING [/name] (nested same-name opens
            // are balanced), unless written self-closing.
            String body = null;
            int consumedEnd = matcher.end();
            if (!selfClosing) {
                int closerAt = findMatchingCloser(text, name, matcher.end());
                if (closerAt >= 0) {
                    body = expand(entry, text.substring(matcher.end(), closerAt), depth + 1);
                    consumedEnd = closerAt + name.length() + 3; // "[/" + name + "]"
                }
            }

            String replacement = null;
            try {
                replacement = handler.render(parseAttributes(attributeText), body, entry);
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

    /**
     * A tag is self-closing when it ends {@code /]}: with no attributes the
     * slash is captured by OPEN_TAG's group 4, after attributes it is the
     * last non-blank character of group 3.
     */
    private static boolean isSelfClosing(String attributeText, String slashGroup) {
        return !slashGroup.isEmpty() || attributeText.stripTrailing().endsWith("/");
    }

    /**
     * Finds the {@code [/name]} that closes an open tag whose body starts at
     * {@code from}, balancing nested same-name opens so
     * {@code [a]..[a]..[/a]..[/a]} pairs inside-out like HTML. Self-closing
     * and escaped ({@code [[name ...]]}) inner opens do not consume a closer.
     * Matching is case-insensitive, like tag names everywhere else.
     *
     * <p>When opens outnumber closers a strict pairing would leave THIS tag
     * unclosed even though the author wrote a closer -- so as a fallback the
     * tag takes the LAST closer, and the leftover inner opens (re-scanned
     * when the body is recursively expanded, where their closers are absent)
     * degrade to self-closing. Returns -1 only when no closer exists at all.
     */
    private static int findMatchingCloser(String text, String name, int from) {
        String quoted = Pattern.quote(name);
        Pattern sameName = Pattern.compile(
                "\\[/" + quoted + "\\]|\\[" + quoted + "(?=[\\s/\\]])",
                Pattern.CASE_INSENSITIVE);
        Matcher token = sameName.matcher(text);

        int depth = 1;
        int lastCloser = -1;
        int pos = from;
        while (pos < text.length() && token.find(pos)) {
            if (text.charAt(token.start() + 1) == '/') {
                lastCloser = token.start();
                depth--;
                if (depth == 0) {
                    return token.start();
                }
                pos = token.end();
                continue;
            }
            // A same-name open inside the body: only a real, non-escaped,
            // non-self-closing open tag consumes a closer of its own.
            Matcher open = OPEN_TAG.matcher(text).region(token.start(), text.length());
            if (!open.lookingAt()) {
                pos = token.end();
                continue;
            }
            boolean escapedOpen = token.start() > 0
                    && text.charAt(token.start() - 1) == '['
                    && open.end() < text.length()
                    && text.charAt(open.end()) == ']';
            if (!escapedOpen && !isSelfClosing(open.group(3), open.group(4))) {
                depth++;
            }
            pos = open.end();
        }
        return lastCloser;
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
