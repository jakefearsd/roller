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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-scan tests for the Struts-id fossils in the editor JSPs: a handful of
 * scripts still address {@code document.<formName>} or a jQuery {@code #id}
 * selector that the Struts-to-Spring-MVC migration never reproduced on the
 * actual element. The failure mode is silent (a no-op click) or, in
 * StylesheetEdit's case, actively wrong (the exception aborts before
 * {@code return false}, so the submit button's default action -- saving --
 * fires anyway).
 *
 * <p>Both scans below collect every violation before failing, rather than
 * asserting inside the loop: an {@code assertTrue} inside a loop stops at the
 * FIRST offender, so a single run can never demonstrate two independent
 * defects in the same file list at once, and a regression run only ever names
 * one offender per fix-rerun cycle instead of the whole set.</p>
 */
class EditorJspScriptBindingTest {

    private static final Path EDITOR_JSP_DIR =
            Path.of("src/main/webapp/WEB-INF/jsps/editor");

    private static List<Path> editorJsps() throws IOException {
        try (Stream<Path> files = Files.list(EDITOR_JSP_DIR)) {
            return files.filter(p -> p.toString().endsWith(".jsp")).toList();
        }
    }

    /** Every document.<name> reference in an editor JSP must match a form that
     *  declares that name or id -- the Struts migration left several that don't,
     *  and the failure is a silent no-op (or worse: StylesheetEdit fell through
     *  to submit). */
    @Test
    void everyDocumentFormReferenceResolves() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Path jsp : editorJsps()) {
            String src = Files.readString(jsp);
            Matcher m = Pattern.compile("document\\.([A-Za-z][A-Za-z0-9_]*)\\b(?!\\.getElementById)")
                .matcher(src);
            while (m.find()) {
                String name = m.group(1);
                if (Set.of("getElementById", "location", "body", "title", "createElement",
                           "addEventListener", "querySelector", "querySelectorAll", "forms")
                        .contains(name)) continue;
                if (!(src.contains("name=\"" + name + "\"") || src.contains("id=\"" + name + "\""))) {
                    violations.add(jsp.getFileName() + " references document." + name
                            + " but no form declares it");
                }
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    /**
     * Every {@code $("#id")} selector in TemplateEdit's script block must
     * reference an {@code id=} present in the file -- same mechanism Task 1
     * pinned for EntriesSidebar's datepicker binding, extended here because
     * {@code #template-code-tabs} targets nothing and throws, which kills the
     * whole ready-block (the manual-content-type reveal, the URL preview, and
     * launchPage() never run).
     */
    @Test
    void theTemplateEditIdBindingMatchesRealIds() throws Exception {
        String jsp = Files.readString(EDITOR_JSP_DIR.resolve("TemplateEdit.jsp"));
        List<String> violations = new ArrayList<>();
        Matcher sel = Pattern.compile("\\$\\(\"#([A-Za-z0-9_-]+)\"\\)").matcher(jsp);
        while (sel.find()) {
            String id = sel.group(1);
            if (!jsp.contains("id=\"" + id + "\"")) {
                violations.add("script binds #" + id + " but no element has that id");
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    /** onclick attributes must not interpolate escaped author text -- the entity
     *  decodes before the JS compiles and an apostrophe in a title becomes a
     *  SyntaxError. Fix pattern: data-* + delegated handler (MediaFileView:493). */
    @Test
    void noOnclickInterpolatesAuthorText() throws Exception {
        List<String> violations = new ArrayList<>();
        Pattern bad = Pattern.compile("onclick=\"[^\"]*\\$\\{fn:escapeXml");
        for (Path jsp : editorJsps()) {
            String src = Files.readString(jsp);
            if (bad.matcher(src).find()) {
                violations.add(jsp.getFileName() + " interpolates escaped author text into onclick");
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }
}
