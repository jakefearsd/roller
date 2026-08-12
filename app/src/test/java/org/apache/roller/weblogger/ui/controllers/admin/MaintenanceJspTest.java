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
package org.apache.roller.weblogger.ui.controllers.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins a hazard no controller-level unit test can see. {@code
 * MaintenanceControllerTest} asserts {@code selectedWeblog == null} on the
 * MODEL, but a browser does not read the model -- it reads the rendered
 * {@code <select>}. Per the HTML spec, a {@code <select>} with no {@code
 * <option selected>} defaults its FIRST option to selected, full stop; that
 * is not a quirk to work around, it is what "no option marked selected"
 * means to a browser. {@code Maintenance.jsp}'s weblog list comes from
 * {@code getWeblogs(true, null, null, null, 0, -1)} (ordered by {@code
 * dateCreated DESC}), so without a placeholder, an admin who opens the page
 * and clicks an action button without ever touching the dropdown would
 * silently submit the most-recently-created weblog on the install -- no
 * error, no visual cue a choice was ever made. That is exactly the failure
 * this test exists to keep out.
 *
 * <p>This is a source-text scan in the same style as {@link
 * org.apache.roller.weblogger.ui.EditorJspEscapingTest}: it reads the raw
 * JSP rather than rendering it (there is no lightweight JSP-rendering
 * harness in this codebase), so it proves the placeholder markup is present,
 * first, and correctly guarded -- not that a real browser submits an empty
 * value for it. That half (a disabled, initially-selected {@code <option
 * value="">} posts an empty value when left untouched) is well-established
 * HTML spec behaviour, not something this test re-derives.
 */
class MaintenanceJspTest {

    private static final Path JSP = Path.of("src/main/webapp/WEB-INF/jsps/admin/Maintenance.jsp");

    @Test
    void theWeblogSelectHasACorrectlyGuardedDisabledPlaceholderFirst() throws IOException {
        assertTrue(Files.isRegularFile(JSP), "Expected " + JSP.toAbsolutePath());
        String source = Files.readString(JSP, StandardCharsets.UTF_8);

        int selectStart = source.indexOf("<select name=\"weblogId\"");
        assertTrue(selectStart >= 0, "Expected <select name=\"weblogId\"> in " + JSP);
        int selectEnd = source.indexOf("</select>", selectStart);
        assertTrue(selectEnd > selectStart, "Unterminated <select> in " + JSP);
        String selectBody = source.substring(selectStart, selectEnd);

        int firstOptionStart = selectBody.indexOf("<option");
        assertTrue(firstOptionStart >= 0, "The weblog select has no <option> at all");
        int firstOptionEnd = selectBody.indexOf("</option>", firstOptionStart);
        assertTrue(firstOptionEnd > firstOptionStart, "Unterminated first <option> in the weblog select");
        String firstOption = selectBody.substring(firstOptionStart, firstOptionEnd);

        assertTrue(firstOption.contains("value=\"\""),
                "The FIRST <option> in the weblog select must be the empty-value placeholder, "
                        + "not a real weblog -- a <select> with no explicitly selected option "
                        + "defaults to its first one. Found: " + firstOption);
        assertTrue(firstOption.contains("disabled=\"disabled\""),
                "The placeholder must be disabled so it can never be re-selected once a real "
                        + "weblog is chosen. Found: " + firstOption);
        assertTrue(
                firstOption.contains(
                        "<c:if test=\"${selectedWeblog == null}\">selected=\"selected\"</c:if>"),
                "The placeholder's selected=\"selected\" must be conditional on no weblog being "
                        + "chosen yet, not unconditional -- an unconditional one here would fight "
                        + "a real option's own conditional selected and could mark two options "
                        + "selected in the same rendered <select>. Found: " + firstOption);
        assertTrue(firstOption.contains("code=\"maintenance.select.placeholder\""),
                "The placeholder's label must come from a message key, not a literal string, "
                        + "like every other label on this page. Found: " + firstOption);

        // Confirm this really is the FIRST option -- a getWeblogs() entry
        // rendered before the placeholder would defeat the entire point.
        int secondOptionStart = selectBody.indexOf("<option", firstOptionEnd);
        if (secondOptionStart >= 0) {
            assertTrue(secondOptionStart > firstOptionEnd,
                    "A second <option> appears to start before the first one ends -- "
                            + "the scan itself is confused, not the markup");
        }
    }
}
