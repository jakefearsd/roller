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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.validator.routines.UrlValidator;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.util.HTMLSanitizer;

/**
 * The built-in {@code [cta href=".." label=".." note=".."]} shortcode: a
 * styled call-to-action card for rental/booking/print links, rendered as an
 * anchor ({@code <button>} is not sanitizer-allow-listed):
 *
 * <pre>{@code
 * <a class="cta-card" href="https://..." rel="nofollow sponsored noopener"
 *    target="_blank"><span class="cta-label">Book this cottage</span>
 *    <span class="cta-note">From EUR 120/night</span></a>
 * }</pre>
 *
 * <p>{@code href} MUST be an absolute http(s) URL: the entry-content
 * sanitizer validates anchor hrefs with commons-validator's
 * {@code UrlValidator} and silently deletes the whole anchor on failure, so
 * this handler runs the same validation itself and returns null -- the
 * expander's "leave the shortcode text exactly as written" signal -- to
 * keep the mistake visible to the author instead of shipping an unlinked
 * label. (That validator also rejects the TLD-less {@code localhost}.)
 *
 * <p>UTM tagging: {@code utm_source=<weblog handle>}, {@code utm_medium=blog}
 * and {@code utm_campaign=<entry anchor>} are appended to the URL's query
 * string, preserving existing query parameters and the fragment. A utm
 * parameter the author already put in the URL wins -- it is never
 * overwritten or duplicated.
 *
 * <p>{@code label} is required, {@code note} is optional; both are
 * attribute values and therefore HTML-escaped on emission.
 */
public class CtaShortcode implements ShortcodeHandler {

    private static final Logger log = LoggerFactory.getLogger(CtaShortcode.class);

    /** The same schemes the sanitizer accepts for anchor hrefs. */
    private static final UrlValidator URL_VALIDATOR =
            new UrlValidator(new String[] {"http", "https"});

    @Override
    public String getName() {
        return "cta";
    }

    @Override
    public ShortcodeCard getCard() {
        return ShortcodeCard.snippet("cta", "shortcode.cta.label",
                "[cta href=\"https://example.com/book\" label=\"Book this stay\" "
                        + "note=\"Free cancellation for 48 hours\"]");
    }

    @Override
    public String render(Map<String, String> attributes, String body, ShortcodeContext content) {
        String href = StringUtils.trimToNull(attributes.get("href"));
        String label = StringUtils.trimToNull(attributes.get("label"));
        if (href == null || label == null) {
            log.debug("[cta] shortcode without href or label; leaving it as written");
            return null;
        }
        if (!URL_VALIDATOR.isValid(href)) {
            // the sanitizer would silently delete the whole anchor; failing
            // here keeps the author's [cta ...] text visible instead
            log.debug("[cta] shortcode href is not an absolute http(s) URL;"
                    + " leaving it as written: {}", href);
            return null;
        }

        StringBuilder html = new StringBuilder(160);
        html.append("<a class=\"cta-card\" href=\"").append(escape(withUtmParams(href, content)))
                .append("\" rel=\"nofollow sponsored noopener\" target=\"_blank\">");
        html.append("<span class=\"cta-label\">").append(escape(label)).append("</span>");
        String note = StringUtils.trimToNull(attributes.get("note"));
        if (note != null) {
            html.append("<span class=\"cta-note\">").append(escape(note)).append("</span>");
        }
        html.append("</a>");
        return html.toString();
    }

    /**
     * {@code href} with the missing utm parameters appended before the
     * fragment: source = the weblog handle, medium = blog, campaign = the
     * entry anchor (each skipped when unavailable or already present).
     */
    private static String withUtmParams(String href, ShortcodeContext content) {
        int hash = href.indexOf('#');
        String base = hash >= 0 ? href.substring(0, hash) : href;
        String fragment = hash >= 0 ? href.substring(hash) : "";

        Set<String> existing = existingParamNames(base);
        Weblog weblog = content == null ? null : content.getWeblog();
        String handle = weblog == null ? null : StringUtils.trimToNull(weblog.getHandle());
        String anchor = content == null ? null : StringUtils.trimToNull(content.getSlug());

        StringBuilder url = new StringBuilder(base);
        appendParam(url, existing, "utm_source", handle);
        appendParam(url, existing, "utm_medium", "blog");
        appendParam(url, existing, "utm_campaign", anchor);
        return url.append(fragment).toString();
    }

    private static void appendParam(StringBuilder url, Set<String> existing,
            String name, String value) {
        if (value == null || existing.contains(name)) {
            return;
        }
        char last = url.charAt(url.length() - 1);
        if (last != '?' && last != '&') {
            url.append(url.indexOf("?") >= 0 ? '&' : '?');
        }
        url.append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    /** The (lower-cased) names of the query parameters already on {@code base}. */
    private static Set<String> existingParamNames(String base) {
        Set<String> names = new HashSet<>();
        int query = base.indexOf('?');
        if (query < 0) {
            return names;
        }
        for (String param : base.substring(query + 1).split("&")) {
            int eq = param.indexOf('=');
            String name = eq >= 0 ? param.substring(0, eq) : param;
            if (!name.isBlank()) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static String escape(String value) {
        return HTMLSanitizer.htmlEncodeApexesAndTags(value);
    }
}
