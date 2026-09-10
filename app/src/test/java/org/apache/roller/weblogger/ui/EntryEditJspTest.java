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

package org.apache.roller.weblogger.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source scan for Task A10's rail cleanup: the newsletter and entry-revisions
 * boxes stop being Bootstrap {@code .card}s (a different shape from every
 * other rail box) and become {@code .editor-box}/{@code .rail-group-label},
 * the same shape Publish/Organize/the SEO drawer already use, and the delete
 * link moves to sit after them so the rail reads as one continuous column
 * rather than "boxes, then two cards, then a link squeezed back inside the
 * boxes".
 */
class EntryEditJspTest {

    private static final Path ENTRY_EDIT = Paths.get("src/main/webapp/WEB-INF/jsps/editor/EntryEdit.jsp");

    private static final String RAIL_START = "<div class=\"editor-rail\">";
    private static final String RAIL_END = "<%-- /editor-grid --%>";

    private static String read() throws IOException {
        return Files.readString(ENTRY_EDIT, StandardCharsets.UTF_8);
    }

    /**
     * The rail's visual column spans from the rail div through the newsletter
     * and revisions boxes (placed after the {@code #entry} form closes, so
     * their own {@code <form>}s do not nest inside it -- see the comment
     * above {@code .editor-grid} in EntryEdit.jsp) up to the grid's own
     * closing marker. That whole span is what a reader sees as "the rail",
     * and none of it may fall back to a Bootstrap card.
     */
    private static String railRegion(String jsp) {
        int start = jsp.indexOf(RAIL_START);
        assertTrue(start >= 0, "EntryEdit.jsp must have a <div class=\"editor-rail\">");
        int end = jsp.indexOf(RAIL_END, start);
        assertTrue(end >= 0, "EntryEdit.jsp must close the editor-grid after the rail");
        return jsp.substring(start, end);
    }

    @Test
    void theRailNeverFallsBackToABootstrapCard() throws IOException {
        String rail = railRegion(read());
        assertTrue(!rail.contains("class=\"card"),
                "the rail (Publish/Organize/SEO/newsletter/revisions/delete) must be one "
                        + "shape -- .editor-box/.rail-group-label -- never a Bootstrap .card:\n" + rail);
    }

    @Test
    void theDeleteLinkIsTheLastThingInTheRail() throws IOException {
        String rail = railRegion(read());
        int deleteAt = rail.indexOf("id=\"deleteEntryButton\"");
        assertTrue(deleteAt >= 0, "deleteEntryButton must still exist in the rail region");

        int newsletterAt = rail.indexOf("id=\"newsletterCard\"");
        int revisionsAt = rail.indexOf("id=\"entryRevisionsCard\"");
        if (newsletterAt >= 0) {
            assertTrue(deleteAt > newsletterAt, "delete must come after the newsletter box");
        }
        if (revisionsAt >= 0) {
            assertTrue(deleteAt > revisionsAt, "delete must come after the revisions box");
        }
    }

    @Test
    void theNewsletterAndRevisionsBoxesKeepTheirPinnedIds() throws IOException {
        // These ids are driven directly by ITs/unit tests and must never be
        // renamed -- see the brief. This test exists so a future restyle
        // that reaches for these boxes trips over an assertion here rather
        // than a browser test three files away.
        String jsp = read();
        for (String id : new String[] {
                "id=\"newsletterCard\"", "id=\"newsletterSentAt\"", "id=\"newsletterNoList\"",
                "id=\"sendNewsletterButton\"", "id=\"entryRevisionsTable\"", "id=\"deleteEntryButton\""
        }) {
            assertTrue(jsp.contains(id), "expected " + id + " to still be present in EntryEdit.jsp");
        }
    }

    @Test
    void theNewsletterNoListMessageIsFollowedByItsOwnLinkText() throws IOException {
        String jsp = read();
        int noListAt = jsp.indexOf("code=\"newsletter.noList\"");
        assertTrue(noListAt >= 0, "expected a <spring:message code=\"newsletter.noList\"/>");

        // The next few hundred characters (well inside the same <p>) must
        // carry a SEPARATE <a> whose own text is the new link key, not a
        // reuse of a generic key like tabbedmenu.website.settings -- that is
        // the whole point of Task A10's decision: the sentence and the link
        // are two distinct pieces of copy.
        int searchLimit = Math.min(jsp.length(), noListAt + 500);
        String window = jsp.substring(noListAt, searchLimit);
        int linkAt = window.indexOf("code=\"newsletter.noList.link\"");
        assertTrue(linkAt >= 0,
                "expected a <spring:message code=\"newsletter.noList.link\"/> shortly after "
                        + "newsletter.noList, inside its own <a>:\n" + window);

        int anchorAt = window.lastIndexOf("<a ", linkAt);
        assertTrue(anchorAt >= 0 && anchorAt < linkAt,
                "the newsletter.noList.link message must render inside an <a> element");
    }
}
