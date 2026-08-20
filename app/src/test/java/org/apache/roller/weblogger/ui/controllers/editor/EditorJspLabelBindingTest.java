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
package org.apache.roller.weblogger.ui.controllers.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-scan tests pinning label/control binding in the editor JSP tree.
 *
 * <p>A {@code <label>} that neither carries a {@code for=} pointing at a real
 * id nor wraps its own control is not a label to a screen reader or to a
 * pointer -- it is styled text. The sweep of 2026-08-20 found ~80 such labels
 * across the admin JSPs, of which the editor tree owns the bulk; these scans
 * pin the repair for the editor tree specifically (a sibling package extends
 * the coverage to {@code jsps/admin}, {@code jsps/core} and {@code jsps/tiles}).
 *
 * <p>Both scans collect every violation before asserting once, following
 * {@link EditorJspScriptBindingTest}: an {@code assertTrue} inside the loop
 * stops at the first offender, so a single run can never show the whole set.
 */
class EditorJspLabelBindingTest {

    private static final Path EDITOR_JSP_DIR =
            Path.of("src/main/webapp/WEB-INF/jsps/editor");

    /** {@code <label ...>body</label>}, body spanning lines. */
    private static final Pattern LABEL =
            Pattern.compile("<label\\b([^>]*)>(.*?)</label>", Pattern.DOTALL);

    private static final Pattern FOR_ATTR = Pattern.compile("\\bfor=\"([^\"]*)\"");

    private static List<Path> editorJsps() throws IOException {
        try (Stream<Path> files = Files.list(EDITOR_JSP_DIR)) {
            return files.filter(p -> p.toString().endsWith(".jsp")).sorted().toList();
        }
    }

    /** Every literal {@code <label for="X">} must have a matching {@code id="X"}
     *  in the same JSP. A dangling {@code for} is worse than none: it silently
     *  claims a binding that does not exist. */
    @Test
    void everyLabelForTargetsAnIdInTheSameFile() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Path jsp : editorJsps()) {
            String src = Files.readString(jsp);
            Matcher m = Pattern.compile("<label[^>]*\\bfor=\"([^\"$]+)\"").matcher(src);
            while (m.find()) {
                if (!src.contains("id=\"" + m.group(1) + "\"")) {
                    violations.add(jsp.getFileName() + ": label for=\"" + m.group(1)
                            + "\" has no target");
                }
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    /** A label must bind to something: either {@code for=} or a control nested
     *  inside it. Anything else is a caption pretending to be a label, and the
     *  fix is either the missing {@code for}/{@code id} pair or a
     *  {@code <span class="col-form-label">} when the row is read-only content
     *  rather than a form field. */
    @Test
    void everyLabelBindsToAControl() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Path jsp : editorJsps()) {
            String src = Files.readString(jsp);
            Matcher m = LABEL.matcher(src);
            while (m.find()) {
                String attrs = m.group(1);
                String body = m.group(2);
                Matcher f = FOR_ATTR.matcher(attrs);
                if (f.find() && !f.group(1).isBlank()) {
                    continue;
                }
                if (body.contains("<input") || body.contains("<select")
                        || body.contains("<textarea") || body.contains("<form:")) {
                    continue;
                }
                violations.add(jsp.getFileName() + ": label \""
                        + body.replaceAll("\\s+", " ").trim()
                        + "\" binds to no control (no for=, no nested control)");
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }
}
