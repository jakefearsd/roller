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

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.business.plugins.PluginManager;
import org.apache.roller.weblogger.business.plugins.entry.WeblogEntryPlugin;
import org.apache.roller.weblogger.business.shortcodes.ShortcodeCard;
import org.apache.roller.weblogger.business.shortcodes.ShortcodeContext;
import org.apache.roller.weblogger.business.shortcodes.ShortcodeExpander;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.apache.roller.weblogger.util.HTMLSanitizer;
import org.apache.roller.weblogger.util.I18nMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The content pipeline every authored surface shares -- entry plugins, then
 * shortcode expansion, then Markdown, then sanitization -- as a service that
 * receives its collaborators, rather than behaviour reached from inside the
 * entities through the static service locator.
 *
 * <p>This is what {@code WeblogEntry.render()}/{@code getTransformedText()}/
 * {@code displayContent()} and {@code WeblogPage.getRenderedContent()} used to
 * do, with the static {@code ContentRenderer} folded in. It lives on the
 * {@link Weblogger} facade ({@link Weblogger#getEntryRenderer()}) because the
 * template wrappers, which hold the facade, are its main consumer.
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
 *
 * <p>Entry plugins: per-entry opt-in died with {@code weblogentry.plugins}
 * (V021) -- every plugin the site has registered is applied to every entry
 * unconditionally, the same way shortcodes are. In production the registry is
 * empty ({@code plugins.page} is no longer configured), so that loop is a
 * no-op; it stays as the render seam a future page plugin would use. The
 * plugin set is asked of the {@link PluginManager} per render rather than
 * memoised on the weblog entity, which is where the old cache lived -- a
 * manager result cached on a JPA-managed object outlives the request that
 * built it.
 */
public class EntryRenderer {

    private static final Logger log = LoggerFactory.getLogger(EntryRenderer.class);

    private final ShortcodeExpander expander;
    private final PluginManager pluginManager;

    public EntryRenderer(ShortcodeExpander expander, PluginManager pluginManager) {
        this.expander = expander;
        this.pluginManager = pluginManager;
    }

    /**
     * The shared pipeline for any authored text -- shortcodes, markdown,
     * sanitization -- with no entry-plugin pass (pages have no plugins).
     *
     * @param content what is being rendered, for weblog and slug context
     * @param text    the source; null returns null
     */
    public String render(ShortcodeContext content, String text) {
        if (text == null) {
            return null;
        }
        String out = expander.expand(content, text);
        out = MarkdownRenderer.render(out);
        return HTMLSanitizer.conditionallySanitize(out);
    }

    /** Entry text, transformed by the site's plugins and the shared pipeline. */
    public String transformedText(WeblogEntry entry) {
        return renderEntry(entry, entry.getText());
    }

    /** Entry summary, transformed by the site's plugins and the shared pipeline. */
    public String transformedSummary(WeblogEntry entry) {
        return renderEntry(entry, entry.getSummary());
    }

    /**
     * The right transformed display content depending on the situation.
     *
     * <p>If the readMoreLink is specified then we assume the caller wants to
     * prefer summary over content and we include a "Read More" link at the
     * end of the summary if it exists. Otherwise, if the readMoreLink is
     * empty, null, or the literal {@code "nil"} (Velocity has no null literal,
     * so themes pass that string) then we assume the caller prefers content
     * over summary.
     */
    public String displayContent(WeblogEntry entry, String readMoreLink) {

        String displayContent;

        if (readMoreLink == null || readMoreLink.isBlank() || "nil".equals(readMoreLink)) {

            // no readMore link means permalink, so prefer text over summary
            if (StringUtils.isNotEmpty(entry.getText())) {
                displayContent = transformedText(entry);
            } else {
                displayContent = transformedSummary(entry);
            }
        } else {
            // not a permalink, so prefer summary over text
            // include a "read more" link if needed
            if (StringUtils.isNotEmpty(entry.getSummary())) {
                displayContent = transformedSummary(entry);
                if (StringUtils.isNotEmpty(entry.getText())) {
                    // add read more
                    List<String> args = List.of(readMoreLink);

                    // TODO: we need a more appropriate way to get the view locale here
                    String readMore = I18nMessages.getMessages(entry.getWebsite().getLocaleInstance())
                            .getString("macro.weblog.readMoreLink", args);

                    displayContent += readMore;
                }
            } else {
                displayContent = transformedText(entry);
            }
        }

        return HTMLSanitizer.conditionallySanitize(displayContent);
    }

    /** A static page's content, through the same pipeline entries use. */
    public String pageContent(WeblogPage page) {
        return render(page, page.getContent());
    }

    /**
     * What the entry editor should offer for the registered shortcodes --
     * by construction the ones this renderer will actually expand.
     */
    public List<ShortcodeCard> shortcodeCards() {
        return expander.cards();
    }

    private String renderEntry(WeblogEntry entry, String str) {
        String ret = str;
        log.debug("Applying page plugins to string");
        Map<String, WeblogEntryPlugin> inPlugins = pluginManager.getWeblogEntryPlugins(entry.getWebsite());
        if (str != null && inPlugins != null) {
            for (WeblogEntryPlugin pagePlugin : inPlugins.values()) {
                try {
                    ret = pagePlugin.render(entry, ret);
                } catch (Exception e) {
                    log.error("ERROR from plugin: {}", pagePlugin.getName(), e);
                }
            }
        }
        return render(entry, ret);
    }
}
