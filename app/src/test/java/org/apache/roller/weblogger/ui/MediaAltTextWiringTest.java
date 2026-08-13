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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the couplings between {@code MediaFile.altText} and the two JSPs that
 * let an author see and edit it: {@code MediaFileEdit.jsp} (the field) and
 * {@code MediaFileView.jsp} (the missing-alt marker on the grid).
 *
 * <p>None of these fail at compile time. A renamed form field silently stops
 * binding {@code bean.altText} and every save discards what the author typed;
 * a marker present in only one of {@code MediaFileView.jsp}'s two
 * {@code c:forEach} loops is invisible on exactly the view an author with a
 * lot of photographs is actually looking at (the paged/search results loop),
 * while the unpaged-folder loop keeps the feature looking complete to anyone
 * testing casually against a small folder.
 */
class MediaAltTextWiringTest {

    private static final Path MEDIA_FILE_EDIT =
            Paths.get("src/main/webapp/WEB-INF/jsps/editor/MediaFileEdit.jsp");
    private static final Path MEDIA_FILE_VIEW =
            Paths.get("src/main/webapp/WEB-INF/jsps/editor/MediaFileView.jsp");

    private static String read(Path jsp) throws IOException {
        return Files.readString(jsp, StandardCharsets.UTF_8);
    }

    /**
     * The JSP with its comments removed.
     *
     * <p>An assertion that matches raw JSP source is really an assertion
     * about the file's prose: a comment merely mentioning
     * {@code media-alt-missing} would satisfy (or a rewording of that
     * comment would break) a test that searched the raw file. Strip the
     * commentary; assert on the code.
     */
    private static String codeOnly(String jsp) {
        return jsp.replaceAll("(?s)<%--.*?--%>", "")
                .replaceAll("(?m)^\\s*//.*$", "");
    }

    @Test
    void theEditFormBindsAltTextByName() throws IOException {
        String jsp = codeOnly(read(MEDIA_FILE_EDIT));
        assertTrue(jsp.contains("name=\"bean.altText\""),
                "MediaFileEdit.jsp must carry an input named bean.altText -- Spring "
                        + "binds by control NAME, not id, and without this the field "
                        + "renders but every save silently discards what was typed");
    }

    @Test
    void theEditFormLabelsAltTextWithItsOwnMessageKeys() throws IOException {
        String jsp = codeOnly(read(MEDIA_FILE_EDIT));
        assertTrue(jsp.contains("mediaFileEdit.altText"),
                "the alt text field must be labelled with mediaFileEdit.altText");
        assertTrue(jsp.contains("mediaFileEdit.altText.tip"),
                "the alt text field must carry a hint via mediaFileEdit.altText.tip");
    }

    @Test
    void theMissingAltMarkerAppearsInBothGridLoops() throws IOException {
        String jsp = codeOnly(read(MEDIA_FILE_VIEW));
        Matcher matches = Pattern.compile("media-alt-missing").matcher(jsp);
        int count = 0;
        while (matches.find()) {
            count++;
        }
        assertEquals(2, count,
                "media-alt-missing must appear in BOTH of MediaFileView.jsp's "
                        + "c:forEach loops (childFiles and pager.items) -- the paged/search "
                        + "loop is the view an author with a lot of photographs is actually "
                        + "looking at, so missing it there makes the whole feature invisible "
                        + "exactly when it matters; found " + count + " occurrence(s)");
    }

    @Test
    void theMissingAltMarkerIsGatedOnImageFileAndEmptyAltText() throws IOException {
        String jsp = codeOnly(read(MEDIA_FILE_VIEW));
        String needle = "mediaFile.imageFile and empty mediaFile.altText";
        int first = jsp.indexOf(needle);
        assertTrue(first >= 0,
                "the marker must be gated on ${mediaFile.imageFile and empty "
                        + "mediaFile.altText} -- without the imageFile half, a non-image "
                        + "(e.g. a PDF) gets a meaningless alt-text marker; without the "
                        + "empty-altText half every image is flagged forever");
        int second = jsp.indexOf(needle, first + needle.length());
        assertTrue(second >= 0,
                "the imageFile/empty-altText gate must appear in both grid loops, not just one");
    }

    @Test
    void theMissingAltMarkerCarriesItsOwnMessageKeys() throws IOException {
        String jsp = codeOnly(read(MEDIA_FILE_VIEW));
        assertTrue(jsp.contains("mediaFileView.altMissing\""),
                "the marker's visible text must come from mediaFileView.altMissing");
        assertTrue(jsp.contains("mediaFileView.altMissing.tip"),
                "the marker's title attribute must come from mediaFileView.altMissing.tip");
    }
}
