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

import java.util.Map;

import org.apache.commons.text.StringEscapeUtils;

/**
 * The built-in {@code [contact]} shortcode: a placeholder for a contact form.
 *
 * <p>Like {@code [map]} and {@code [video]}, the emitted markup is a
 * {@code <div>} carrying data attributes, never a {@code <form>}: the
 * sanitizer strips {@code <form>} from authored content by design (an
 * authored form is a phishing kit), so {@code #showAudienceAssets} builds the
 * real form client-side and posts it to the weblog named by
 * {@code data-weblog}.
 */
public class ContactShortcode implements ShortcodeHandler {

    @Override
    public String getName() {
        return "contact";
    }

    @Override
    public ShortcodeCard getCard() {
        return ShortcodeCard.snippet("contact", "shortcode.contact.label", "[contact]");
    }

    @Override
    public String render(Map<String, String> attributes, String body,
            ShortcodeContext content) {
        if (content == null || content.getWeblog() == null
                || content.getWeblog().getHandle() == null) {
            return null;
        }
        return "<div class=\"contact-form-slot\" data-weblog=\""
                + StringEscapeUtils.escapeHtml4(content.getWeblog().getHandle())
                + "\"></div>";
    }
}
