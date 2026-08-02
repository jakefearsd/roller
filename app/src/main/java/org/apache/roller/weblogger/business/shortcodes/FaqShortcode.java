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

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.pojos.WeblogEntry;

/**
 * The built-in {@code [faq]} shortcode: a body of
 * {@code [q]question[/q][a]answer[/a]} pairs rendered as a
 * {@code <dl class="faq">} of {@code <dt>}/{@code <dd>} rows -- dl/dt/dd
 * are sanitizer-allow-listed where {@code <details>}/{@code <summary>} are
 * not.
 *
 * <p>Parsing is delegated to {@link FaqBlocks}, the shared parser the
 * FAQPage JSON-LD emission (Wave 3 T4) also uses, so the FAQ on the page
 * and the structured data in the head cannot drift. Questions and answers
 * are emitted verbatim: they are entry HTML like any other body text and
 * flow through the downstream entry-content sanitizer (the same convention
 * as the {@code [image]} shortcode's body caption).
 *
 * <p>Returns null -- the expander's "leave the shortcode text exactly as
 * written" failure signal -- when the body is missing or does not match
 * the strict pair grammar, so the author sees their text instead of half
 * an FAQ.
 */
public class FaqShortcode implements ShortcodeHandler {

    private static final Log log = LogFactory.getLog(FaqShortcode.class);

    @Override
    public String getName() {
        return "faq";
    }

    @Override
    public String render(Map<String, String> attributes, String body, WeblogEntry entry) {
        if (StringUtils.isBlank(body)) {
            log.debug("[faq] shortcode without a body; leaving it as written");
            return null;
        }
        List<FaqBlocks.Qa> pairs = FaqBlocks.parsePairs(body);
        if (pairs == null) {
            log.debug("[faq] shortcode body is not well-formed [q]..[/q][a]..[/a]"
                    + " pairs; leaving it as written");
            return null;
        }

        StringBuilder html = new StringBuilder(128 * pairs.size());
        html.append("<dl class=\"faq\">\n");
        for (FaqBlocks.Qa pair : pairs) {
            html.append("<dt>").append(pair.question()).append("</dt>\n");
            html.append("<dd>").append(pair.answer()).append("</dd>\n");
        }
        html.append("</dl>");
        return html.toString();
    }
}
