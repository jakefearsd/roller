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
package org.apache.roller.weblogger.business.themes;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

import org.apache.roller.weblogger.pojos.TemplateRendition.RenditionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ThemeMetadataParser}, focused on the five places a
 * malformed theme.xml throws {@link ThemeParsingException}. Each of these
 * used to discard the parse failure's cause (see CLAUDE.md's
 * PreserveStackTrace note) -- every assertion here checks the cause survived
 * alongside the message, so the fix stays pinned.
 */
class ThemeMetadataParserTest {

    private static final String HEADER =
            "<weblogtheme><id>t</id><name>t</name><preview-image path=\"p.png\" />";
    private static final String FOOTER = "</weblogtheme>";

    private ThemeMetadata parse(String xml) throws Exception {
        InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        return new ThemeMetadataParser().unmarshall(in);
    }

    @Test
    void unknownTemplateActionValueIsWrappedWithItsCause() {
        String xml = HEADER
                + "<template action=\"bogus\"><name>t</name><link>t</link></template>"
                + FOOTER;

        ThemeParsingException ex = assertThrows(ThemeParsingException.class, () -> parse(xml));

        assertEquals("Unknown template action value 'bogus'", ex.getMessage());
        assertNotNull(ex.getCause(),
                "the IllegalArgumentException that caused this must survive as the cause");
    }

    @Test
    void invalidTemplateRenditionTypeIsWrappedWithItsCause() {
        String xml = HEADER
                + "<template action=\"weblog\"><name>t</name><link>t</link>"
                + "<rendition type=\"bogus\"><templateLanguage>velocity</templateLanguage>"
                + "<contentsFile>t.vm</contentsFile></rendition></template>"
                + FOOTER;

        ThemeParsingException ex = assertThrows(ThemeParsingException.class, () -> parse(xml));

        assertEquals("Invalid rendition type bogus found.", ex.getMessage());
        assertNotNull(ex.getCause());
    }

    @Test
    void unknownTemplateLanguageIsWrappedWithItsCause() {
        String xml = HEADER
                + "<template action=\"weblog\"><name>t</name><link>t</link>"
                + "<rendition><templateLanguage>bogus</templateLanguage>"
                + "<contentsFile>t.vm</contentsFile></rendition></template>"
                + FOOTER;

        ThemeParsingException ex = assertThrows(ThemeParsingException.class, () -> parse(xml));

        assertEquals("Unknown templateLanguage value 'bogus'", ex.getMessage());
        assertNotNull(ex.getCause());
    }

    @Test
    void invalidStylesheetRenditionTypeIsWrappedWithItsCause() {
        String xml = HEADER
                + "<stylesheet><name>s</name><link>s</link>"
                + "<rendition type=\"bogus\"><templateLanguage>velocity</templateLanguage>"
                + "<contentsFile>s.css</contentsFile></rendition></stylesheet>"
                + FOOTER;

        ThemeParsingException ex = assertThrows(ThemeParsingException.class, () -> parse(xml));

        assertEquals("Invalid rendition type bogus found.", ex.getMessage());
        assertNotNull(ex.getCause());
    }

    @Test
    void unknownStylesheetTemplateLanguageIsWrappedWithItsCause() {
        String xml = HEADER
                + "<stylesheet><name>s</name><link>s</link>"
                + "<rendition><templateLanguage>bogus</templateLanguage>"
                + "<contentsFile>s.css</contentsFile></rendition></stylesheet>"
                + FOOTER;

        ThemeParsingException ex = assertThrows(ThemeParsingException.class, () -> parse(xml));

        assertEquals("Unknown templateLanguage value 'bogus'", ex.getMessage());
        assertNotNull(ex.getCause());
    }

    // RenditionType has exactly one value (STANDARD), so an explicit
    // type="standard" attribute is the only value that can ever parse
    // successfully -- the two tests below drive that success path, distinct
    // from the default-when-absent branch every other fixture above exercises.

    @Test
    void templateRenditionWithExplicitStandardTypeIsAccepted() throws Exception {
        String xml = HEADER
                + "<template action=\"weblog\"><name>t</name><link>t</link>"
                + "<rendition type=\"standard\"><templateLanguage>velocity</templateLanguage>"
                + "<contentsFile>t.vm</contentsFile></rendition></template>"
                + FOOTER;

        ThemeMetadata parsed = parse(xml);

        Iterator<ThemeMetadataTemplate> templates = parsed.getTemplates().iterator();
        assertTrue(templates.hasNext(), "the template must have parsed");
        ThemeMetadataTemplateRendition rendition =
                templates.next().getTemplateRendition(RenditionType.STANDARD);
        assertNotNull(rendition, "the explicit standard rendition must be reachable by type");
        assertEquals(RenditionType.STANDARD, rendition.getType());
    }

    @Test
    void stylesheetRenditionWithExplicitStandardTypeIsAccepted() throws Exception {
        String xml = HEADER
                + "<template action=\"weblog\"><name>t</name><link>t</link>"
                + "<rendition><templateLanguage>velocity</templateLanguage>"
                + "<contentsFile>t.vm</contentsFile></rendition></template>"
                + "<stylesheet><name>s</name><link>s</link>"
                + "<rendition type=\"standard\"><templateLanguage>velocity</templateLanguage>"
                + "<contentsFile>s.css</contentsFile></rendition></stylesheet>"
                + FOOTER;

        ThemeMetadata parsed = parse(xml);

        ThemeMetadataTemplateRendition rendition =
                parsed.getStylesheet().getTemplateRendition(RenditionType.STANDARD);
        assertNotNull(rendition, "the explicit standard rendition must be reachable by type");
        assertEquals(RenditionType.STANDARD, rendition.getType());
    }
}
