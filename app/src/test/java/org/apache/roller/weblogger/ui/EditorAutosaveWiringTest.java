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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the couplings between the editors and {@code roller-draft.js}.
 *
 * <p>None of these can fail at compile time and none of them fail loudly in a
 * browser either: a missing {@code <script>} tag, a bar whose class the module
 * cannot find, or a storage key that forgot the weblog handle all produce a
 * page that looks completely normal and silently never recovers anything. That
 * is the same class of defect as the {@code _showInNav} field-marker name (see
 * {@code PageEditControllerTest}), and it gets the same treatment: read the
 * JSP and assert on it.
 */
class EditorAutosaveWiringTest {

    private static final Path ENTRY_EDIT =
            Paths.get("src/main/webapp/WEB-INF/jsps/editor/EntryEdit.jsp");
    private static final Path ENTRY_EDITOR =
            Paths.get("src/main/webapp/WEB-INF/jsps/editor/EntryEditor.jsp");
    private static final Path PAGE_EDIT =
            Paths.get("src/main/webapp/WEB-INF/jsps/editor/PageEdit.jsp");

    private static String read(Path jsp) throws IOException {
        return Files.readString(jsp, StandardCharsets.UTF_8);
    }

    @Test
    void theEntryEditorLoadsTheDraftScript() throws IOException {
        assertTrue(read(ENTRY_EDIT).contains("/theme/scripts/roller-draft.js"),
                "EntryEdit.jsp must load roller-draft.js; without it the editor "
                        + "looks entirely normal and never recovers anything");
    }

    @Test
    void theEntryEditorRendersTheRecoveryBarTheModuleLooksFor() throws IOException {
        String jsp = read(ENTRY_EDIT);
        assertTrue(jsp.contains("id=\"draftRecoveryBar\""),
                "EntryEdit.jsp must render the recovery bar");
        for (String required : new String[] {
                "draft-bar-text", "draft-bar-restore", "draft-bar-discard",
                "data-template=", "data-restored=" }) {
            assertTrue(jsp.contains(required),
                    "roller-draft.js querySelector()s '" + required
                            + "' and silently declines to offer anything without it");
        }
    }

    @Test
    void theRecoveryBarsButtonsCannotSubmitTheForm() throws IOException {
        // The bar sits inside #entry, where a <button> with no type defaults
        // to submit -- clicking Discard would post the entry.
        String bar = read(ENTRY_EDIT);
        int start = bar.indexOf("id=\"draftRecoveryBar\"");
        assertTrue(start > 0, "EntryEdit.jsp must render the recovery bar");
        String markup = bar.substring(start, bar.indexOf("</div>", start));
        assertTrue(markup.contains("type=\"button\" class=\"draft-bar-restore\""),
                "Restore must be type=\"button\"; inside #entry it would submit otherwise");
        assertTrue(markup.contains("type=\"button\" class=\"draft-bar-discard\""),
                "Discard must be type=\"button\"; inside #entry it would submit otherwise");
    }

    @Test
    void theEntryEditorsDraftKeyIsScopedToTheWeblogTheActionAndTheEntry() throws IOException {
        String jsp = read(ENTRY_EDIT);
        assertTrue(jsp.contains("roller.draft.v1:"),
                "the storage key must carry the versioned prefix roller-draft.js sweeps on");
        assertTrue(jsp.contains("${pageContext.request.contextPath}")
                        && jsp.contains("${actionWeblog.handle}")
                        && jsp.contains("${actionName}"),
                "a key missing the context path, handle or action lets two editors "
                        + "share one snapshot and hand an author someone else's text");
    }

    @Test
    void theDraftKeysAreSetInRequestScopeSoTheIncludedEditorCanSeeThem() throws IOException {
        // EntryEditor.jsp arrives via jsp:include and reads ${draftKey}. A
        // page-scoped <c:set> resolves to the empty string there, which
        // produces a valid-looking key that every editor in the install shares.
        String jsp = read(ENTRY_EDIT);
        assertTrue(jsp.contains("var=\"draftKey\" scope=\"request\""),
                "draftKey must be set in request scope");
        assertTrue(jsp.contains("var=\"draftNewKey\" scope=\"request\""),
                "draftNewKey must be set in request scope");
    }

    @Test
    void theEntryEditorInstallsTheModuleWithBothEditorSeams() throws IOException {
        String jsp = read(ENTRY_EDITOR);
        assertTrue(jsp.contains("rollerDraft.install("),
                "EntryEditor.jsp must install the module");
        assertTrue(jsp.contains("getText: rollerGetEntryText"),
                "the module reads the editor through rollerGetEntryText");
        assertTrue(jsp.contains("setText: rollerSetEntryText"),
                "Restore writes back through rollerSetEntryText");
    }

    @Test
    void theLeaveWarningIsBoundOnceRatherThanPerKeystroke() throws IOException {
        // The original registered $(window).on("beforeunload", ...) and
        // $("#entry").on('submit', ...) INSIDE the codemirror change callback,
        // so a thousand-word entry left a thousand submit handlers on the form
        // it was about to post.
        String jsp = read(ENTRY_EDITOR);
        assertTrue(jsp.contains("beforeunload"), "the leave-warning must still exist");

        String body = firstEditorChangeCallbackBody(jsp);
        assertFalse(body.contains("beforeunload"),
                "beforeunload must be bound once, outside the change callback -- "
                        + "the callback body is:\n" + body);
        assertFalse(body.contains("'submit'"),
                "a submit handler bound inside the change callback accumulates one "
                        + "per keystroke on the form about to be posted -- "
                        + "the callback body is:\n" + body);
    }

    @Test
    void thePageEditorIsWiredTheSameWay() throws IOException {
        String jsp = read(PAGE_EDIT);
        assertTrue(jsp.contains("/theme/scripts/roller-draft.js"),
                "PageEdit.jsp must load roller-draft.js");
        assertTrue(jsp.contains("id=\"draftRecoveryBar\""),
                "PageEdit.jsp must render the recovery bar");
        assertTrue(jsp.contains("rollerDraft.install("),
                "PageEdit.jsp must install the module");
        assertTrue(jsp.contains("getText: rollerGetEntryText")
                        && jsp.contains("setText: rollerSetEntryText"),
                "PageEdit.jsp has its own copy of the editor seam and must pass both halves");
    }

    @Test
    void thePageEditorsDraftKeyIsScopedToTheWeblogAndThePage() throws IOException {
        String jsp = read(PAGE_EDIT);
        assertTrue(jsp.contains("roller.draft.v1:"),
                "the storage key must carry the versioned prefix roller-draft.js sweeps on");
        assertTrue(jsp.contains("${actionWeblog.handle}"),
                "a key without the handle lets two weblogs' page editors share a snapshot");
        assertTrue(jsp.contains(":pageEdit:"),
                "the page editor's key must not collide with the entry editor's");
    }

    @Test
    void thePageEditorInstallsAgainstItsOwnFormNotTheEntryForm() throws IOException {
        // PageEdit.jsp's form is #pageEditForm. Copying EntryEditor.jsp's
        // install() verbatim would pass document.getElementById('entry'),
        // which is null here -- install() returns silently and the page has
        // autosave that never fires.
        String jsp = read(PAGE_EDIT);
        assertTrue(jsp.contains("form: document.getElementById('pageEditForm')"),
                "PageEdit.jsp must install against #pageEditForm");
    }

    /**
     * The source between {@code codemirror.on('change', function () {} and its
     * matching close.
     *
     * <p>Brace-matched rather than "the text up to the next {@code });}", and
     * asserted on for what the body <em>contains</em> rather than where a
     * keyword first appears in the file. The first version of this test did the
     * latter, and the result was a test that a JSP <em>comment</em> mentioning
     * beforeunload could break — it made prose fail the build while a genuinely
     * misplaced binding a few lines further down would have passed.
     */
    private static String firstEditorChangeCallbackBody(String jsp) {
        int start = jsp.indexOf("rollerEditor.codemirror.on('change'");
        assertTrue(start > 0, "the editor must still have a change handler");
        int open = jsp.indexOf('{', jsp.indexOf("function", start));
        assertTrue(open > 0, "the change handler must have a function body");

        int depth = 0;
        for (int i = open; i < jsp.length(); i++) {
            char c = jsp.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return jsp.substring(open + 1, i);
                }
            }
        }
        throw new AssertionError("unbalanced braces in the change handler");
    }
}
