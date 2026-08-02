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

package org.apache.roller.weblogger.pojos;

import java.util.Locale;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * The schema.org type a weblog entry's head emits as JSON-LD structured
 * data, chosen per entry in the editor's SEO card.
 *
 * <p>{@link #BLOG_POSTING} is the default: it means "just the BlogPosting
 * block every permalink already gets, nothing extra". A null
 * {@code weblogentry.jsonld_type} column means the same thing, so entries
 * that predate this field render exactly as before. A travel type REPLACES
 * the BlogPosting object with the typed one -- one
 * {@code application/ld+json} block per permalink, as before, describing
 * what the page actually is; the enum name is what is stored in the column.
 * {@code EntryJsonLd} is the emitter.
 */
public enum JsonLdType {

    BLOG_POSTING("BlogPosting"),
    TOURIST_ATTRACTION("TouristAttraction"),
    TOURIST_TRIP("TouristTrip"),
    EVENT("Event"),
    FAQ_PAGE("FAQPage");

    private static final Log log = LogFactory.getLog(JsonLdType.class);

    private final String schemaType;

    JsonLdType(String schemaType) {
        this.schemaType = schemaType;
    }

    /**
     * The schema.org type name emitted as {@code "@type"} -- CamelCase, and
     * not derivable from the enum name ({@code FAQPage}, not {@code FaqPage}).
     */
    public String getSchemaType() {
        return schemaType;
    }

    /**
     * Parses a stored or submitted type name, case-insensitively.
     *
     * <p>Deliberately lenient: null or blank means "never chosen" and an
     * unrecognized value (a hand-edited row, or a value written by a newer
     * version of Roller) must not break rendering or editing, so both fall
     * back to {@link #BLOG_POSTING} -- the unknown value with a warning
     * rather than silently.
     */
    public static JsonLdType fromString(String value) {
        if (value == null || value.isBlank()) {
            return BLOG_POSTING;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown JSON-LD type '" + value + "'; falling back to BLOG_POSTING");
            return BLOG_POSTING;
        }
    }
}
