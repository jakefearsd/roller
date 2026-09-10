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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source scans that pin the admin UI's shared vocabulary: one status-pill
 * component, three button buckets, one selection bar, one confirm idiom,
 * no jQuery UI, no sidebar h3+hr. Each method names the task that made it
 * true; a red method means the vocabulary drifted, not that a page broke.
 */
class JspConsistencyTest {

    static final Path JSPS = Path.of("src/main/webapp/WEB-INF/jsps");

    static final Path ROLLER_CSS = Path.of("src/main/webapp/roller-ui/styles/roller.css");

    /**
     * Package A (the editor rebuild) owns these files in a parallel worktree
     * and this task must not touch them -- see task-B1's brief. Post-merge
     * task M1 applies the status-pill vocabulary to the editor screens; until
     * then they are exempt from this scan.
     */
    private static final Set<String> A_OWNED = Set.of("EntryEdit.jsp", "EntryEditor.jsp", "PageEdit.jsp");

    static Stream<Path> jsps() throws IOException {
        return Files.walk(JSPS).filter(p -> p.toString().endsWith(".jsp"));
    }

    @Test
    void statusIsAlwaysAStatusPill() throws IOException {
        List<Path> editorJsps = jsps()
                .filter(p -> p.toString().contains("/editor/"))
                .filter(p -> !A_OWNED.contains(p.getFileName().toString()))
                .toList();
        assertTrue(editorJsps.size() > 5,
                "Found too few editor JSPs -- the scan is not looking where it thinks it is.");

        for (Path jsp : editorJsps) {
            String src = Files.readString(jsp, StandardCharsets.UTF_8);
            assertFalse(src.contains("badge bg-success") || src.contains("badge bg-info")
                    || src.contains("badge bg-warning") || src.contains("badge bg-danger")
                    || src.contains("badge bg-primary"), jsp + " uses a Bootstrap badge for status; use .status-pill");
            assertFalse(src.contains("EntryBox"), jsp + " renders the old status legend");
            assertFalse(src.contains("class=\"draftentry\"") || src.contains("class=\"pendingentry\""),
                    jsp + " tints rows by status");
        }

        String css = Files.readString(ROLLER_CSS, StandardCharsets.UTF_8);
        for (String s : List.of("published", "draft", "pending", "scheduled", "trashed")) {
            assertTrue(css.contains(".status-pill.status-" + s), "roller.css lacks .status-pill.status-" + s);
        }
    }
}
