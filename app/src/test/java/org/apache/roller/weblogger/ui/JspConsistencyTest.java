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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * Task B2: every button in the admin UI is one of exactly three buckets --
     * primary (one per screen, the one recommended action), destructive
     * (btn-danger), or secondary (everything else, including a bare .btn with
     * no variant class riding along). Bootstrap's stock .btn-success is not a
     * fourth bucket; every former use is either the screen's one primary
     * action (-> btn-primary) or not (-> btn-secondary). A_OWNED is skipped
     * for the same reason statusIsAlwaysAStatusPill skips it -- see that
     * method's comment.
     */
    @Test
    void buttonsUseThreeBucketsOnly() throws IOException {
        List<Path> nonAOwned = jsps()
                .filter(p -> !A_OWNED.contains(p.getFileName().toString()))
                .toList();
        assertTrue(nonAOwned.size() > 20,
                "Found too few JSPs -- the scan is not looking where it thinks it is.");

        Pattern bareBtn = Pattern.compile("class=\"btn\"[^>]*>");
        for (Path jsp : nonAOwned) {
            String src = Files.readString(jsp, StandardCharsets.UTF_8);
            assertFalse(src.contains("btn-success"), jsp + ": btn-success -> btn-primary");
            assertFalse(bareBtn.matcher(src).find(),
                    jsp + ": bare .btn -> btn-secondary (or btn-primary if it is the screen's one primary action)");
            assertFalse(src.contains("<input type=\"button\" class=\"btn"),
                    jsp + ": <input type=button> as a button -> <button>");
            assertFalse(src.contains("<input id=\"toggleButton\""), jsp + ": media action bar still inputs");
        }

        String pages = Files.readString(JSPS.resolve("editor/Pages.jsp"), StandardCharsets.UTF_8);
        assertTrue(pages.contains("<c:if test=\"${not empty pages}\">\n    <a href=\"${addUrl}\" class=\"btn btn-primary btn-sm\">")
                        || pages.contains("not empty pages"),
                "Pages hides the top primary when empty");

        String members = Files.readString(JSPS.resolve("editor/Members.jsp"), StandardCharsets.UTF_8);
        assertEquals(1, countOccurrences(members, "btn btn-primary"), "Members has one primary");
    }

    /**
     * Task B3: every list screen (Entries, Submissions, MediaFileView) shows
     * exactly one selection bar -- a {@code .selection-bar} that names, via
     * {@code data-selection-bar}, the id of the {@code <form>} whose
     * checkboxes it counts. The bar and its form must live in the same JSP
     * (the delegated handler in roller.js looks the bar up by the checked
     * checkbox's own {@code .form.id}), and the old per-file bulk-action
     * markup ({@code #entriesBulkActions}) must be gone. Trash is
     * deliberately excluded -- see task-B3's brief: TrashController has no
     * bulk restore/delete handlers, so Trash.jsp keeps its per-row buttons
     * and whole-trash "Empty trash" only, with no selection bar at all.
     */
    @Test
    void everySelectionBarNamesItsForm() throws IOException {
        Pattern barPattern = Pattern.compile("class=\"selection-bar\"[^>]*data-selection-bar=\"([^\"]+)\"");
        Pattern formIdPattern = Pattern.compile("<form\\b[^>]*\\bid=\"([^\"]+)\"");

        int barsFound = 0;
        for (Path jsp : jsps().toList()) {
            String src = Files.readString(jsp, StandardCharsets.UTF_8);

            Set<String> formIds = new HashSet<>();
            Matcher formMatcher = formIdPattern.matcher(src);
            while (formMatcher.find()) {
                formIds.add(formMatcher.group(1));
            }

            Matcher barMatcher = barPattern.matcher(src);
            while (barMatcher.find()) {
                barsFound++;
                String formId = barMatcher.group(1);
                assertTrue(formIds.contains(formId), jsp + ": .selection-bar names form \""
                        + formId + "\", which is not declared in this JSP");
            }
        }
        assertTrue(barsFound >= 3,
                "Found too few .selection-bar elements -- expected at least one each on "
                        + "Entries.jsp, Submissions.jsp and MediaFileView.jsp.");

        String entries = Files.readString(JSPS.resolve("editor/Entries.jsp"), StandardCharsets.UTF_8);
        assertFalse(entries.contains("entriesBulkActions"),
                "Entries.jsp still has the old #entriesBulkActions div -- its buttons "
                        + "must move into the .selection-bar");

        String submissions = Files.readString(JSPS.resolve("editor/Submissions.jsp"), StandardCharsets.UTF_8);
        assertEquals(1, countOccurrences(submissions, "class=\"selection-bar\""),
                "Submissions.jsp must have exactly one .selection-bar");

        String mediaFileView = Files.readString(JSPS.resolve("editor/MediaFileView.jsp"), StandardCharsets.UTF_8);
        assertEquals(1, countOccurrences(mediaFileView, "class=\"selection-bar\""),
                "MediaFileView.jsp must have exactly one .selection-bar");
    }

    /**
     * Task B4: the Entries sidebar's date filters became native
     * {@code <input type="date">} elements, and jQuery UI -- the datepicker
     * widget that used to back them, and its only consumer anywhere in this
     * admin UI -- is gone entirely. No JSP may call its API or reference its
     * webjar, and head.jsp, which every admin page includes, must not load it.
     */
    @Test
    void noJqueryUiAnywhere() throws IOException {
        List<Path> allJsps = jsps().toList();
        assertTrue(allJsps.size() > 20,
                "Found too few JSPs -- the scan is not looking where it thinks it is.");

        for (Path jsp : allJsps) {
            String src = Files.readString(jsp, StandardCharsets.UTF_8);
            assertFalse(src.contains("datepicker("), jsp + " still calls jQuery UI's datepicker()");
            assertFalse(src.contains("jquery-ui"), jsp + " still references the jquery-ui webjar");
        }

        String head = Files.readString(JSPS.resolve("tiles/head.jsp"), StandardCharsets.UTF_8);
        assertFalse(head.contains("jquery-ui"), "head.jsp still loads jquery-ui");
    }

    /**
     * Task B5: one date vocabulary across the admin UI. {@code fmt:formatDate}
     * formats in the JVM's default timezone -- the server's, which on this
     * application is nobody's clock in particular -- and emits no
     * machine-readable {@code datetime} attribute. {@code <rc:date>} formats
     * in the action weblog's zone, which is the clock every pubtime on this
     * application is already expressed in, and wraps the result in a
     * {@code <time>}. A_OWNED is skipped for the same reason the scans above
     * skip it: post-merge task M1 converts EntryEdit's {@code .editor-when}
     * spans.
     *
     * <p>The bundle half is the same rule stated where it can actually be
     * enforced. While {@code weblogEntryQuery.date.toStringFormat} still
     * <em>exists</em> as a key, a date format is a translatable string that
     * eight locale files may disagree about -- {@code ja} declared
     * {@code yy/MM/dd HH:mm} against the base bundle's
     * {@code MM/dd/yy hh:mm a} -- and any new call site is one
     * {@code <spring:message>} away from reintroducing request-locale
     * timezones to a data cell. Deleting the key is what makes the JSP-side
     * ban above unbypassable rather than merely observed.
     */
    @Test
    void noAdminTimestampIsFormattedWithFmtFormatDate() throws IOException {
        List<Path> nonAOwned = jsps()
                .filter(p -> !A_OWNED.contains(p.getFileName().toString()))
                .toList();
        assertTrue(nonAOwned.size() > 20,
                "Found too few JSPs -- the scan is not looking where it thinks it is.");

        for (Path jsp : nonAOwned) {
            String src = Files.readString(jsp, StandardCharsets.UTF_8);
            assertFalse(src.contains("fmt:formatDate"),
                    jsp + ": format timestamps with <rc:date>, not fmt:formatDate");
            assertFalse(src.contains("weblogEntryQuery.date.toStringFormat"),
                    jsp + ": format timestamps with <rc:date>, not a message-bundle date pattern");
        }

        try (Stream<Path> bundles = Files.list(Path.of("src/main/resources"))) {
            List<Path> declaringTheDatePattern = bundles
                    .filter(p -> p.getFileName().toString().startsWith("ApplicationResources"))
                    .filter(p -> {
                        try {
                            return Files.readString(p, StandardCharsets.UTF_8)
                                    .contains("weblogEntryQuery.date.toStringFormat");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .toList();
            assertTrue(declaringTheDatePattern.isEmpty(),
                    "a date format is not a translatable string -- <rc:date> owns the "
                            + "display pattern now, so no bundle may still declare "
                            + "weblogEntryQuery.date.toStringFormat: " + declaringTheDatePattern);
        }
    }

    /**
     * Task B6: the five sidebar tiles (EntriesSidebar, MediaFileSidebar,
     * CategoriesSidebar, TemplatesSidebar, MainMenuSidebar) speak the rail's
     * own grammar instead of a Bootstrap card with h3/hr section markup: a
     * caps-label ({@code p.sidebar-label}) per logical block, each wrapped in
     * a {@code div.sidebar-group}, no {@code <h3>} and no {@code <hr>}
     * anywhere in a sidebar tile. The two layouts that render a sidebar
     * (tiles-tabbedpage.jsp, tiles-mainmenupage.jsp) wrap the include in
     * {@code <aside id="adminSidebar">} rather than a {@code .card}/
     * {@code .card-body} pair -- checked only across the LEFT column, since
     * the main content column keeps its own {@code .card-body} untouched.
     *
     * <p>{@code buttonsUseThreeBucketsOnly} above only forbids btn-success
     * and a bare {@code class="btn"}; it does not count primaries per file,
     * so MediaFileSidebar.jsp's Search button (a second {@code btn-primary}
     * alongside MediaFileView.jsp's "Add a photo") needs no exemption there.
     */
    @Test
    void sidebarsUseTheRailGrammar() throws IOException {
        List<Path> sidebarJsps = jsps()
                .filter(p -> p.getFileName().toString().endsWith("Sidebar.jsp"))
                .toList();
        assertEquals(5, sidebarJsps.size(),
                "Expected exactly the five sidebar tiles -- the scan is not looking where it thinks it is.");

        for (Path jsp : sidebarJsps) {
            String src = Files.readString(jsp, StandardCharsets.UTF_8);
            assertFalse(src.contains("<h3"), jsp + " still has an <h3> -- use <p class=\"sidebar-label\">");
            assertFalse(src.contains("<hr"), jsp + " still has an <hr> -- .sidebar-group's border-top replaces it");
            assertTrue(src.contains("sidebar-label"), jsp + " has no sidebar-label caps-label");
        }

        for (String layout : List.of("tiles/tiles-tabbedpage.jsp", "tiles/tiles-mainmenupage.jsp")) {
            String src = Files.readString(JSPS.resolve(layout), StandardCharsets.UTF_8);
            assertTrue(src.contains("id=\"adminSidebar\""), layout + " lacks the <aside id=\"adminSidebar\"> wrapper");

            int leftStart = src.indexOf("roller-column-left");
            int leftEnd = src.indexOf("roller-column-right");
            assertTrue(leftStart >= 0 && leftEnd > leftStart,
                    layout + ": could not isolate the left column -- the scan is not looking where it thinks it is.");
            String leftColumn = src.substring(leftStart, leftEnd);
            assertFalse(leftColumn.contains("card-body"),
                    layout + ": the sidebar is still wrapped in .card-body");
        }
    }

    /**
     * Task B7: one modal shape, one confirm idiom.
     *
     * <ul>
     *   <li>no {@code confirm(} in any JSP outside {@code A_OWNED} -- every
     *       destructive action is confirmed through {@code data-confirm}
     *       (see roller.js), never a native {@code window.confirm()} called
     *       from a JSP's own inline script;</li>
     *   <li>no {@code onsubmit=} attribute anywhere outside {@code A_OWNED}
     *       -- a form's behavior on submit is wired with
     *       {@code addEventListener}, not an inline handler, for the same
     *       reason confirm() moved out: an inline handler is a second place
     *       author-controlled or translated text can reach raw JavaScript;</li>
     *   <li>every {@code .modal-title} is a {@code <p>}, or -- if some other
     *       element carries the class -- that element is not a heading
     *       ({@code h1}-{@code h6}). A modal's title is one caps-label role,
     *       not a document heading competing with the page's own outline;</li>
     *   <li>every {@code .modal-footer} orders its buttons cancel/dismiss
     *       FIRST, primary-or-destructive action LAST. Bootstrap packs
     *       {@code .modal-footer} left-to-right in DOM order, so this is a
     *       markup convention, not something CSS enforces.</li>
     * </ul>
     *
     * <p>JSP comments are stripped before every scan below: a comment
     * documenting the banned pattern (e.g. UserEdit.jsp's explanation of why
     * its own button is NOT built with an inline {@code onsubmit}) is prose
     * about the rule, not a violation of it.
     */
    @Test
    void oneConfirmIdiomAndOneModalShape() throws IOException {
        List<Path> nonAOwned = jsps()
                .filter(p -> !A_OWNED.contains(p.getFileName().toString()))
                .toList();
        assertTrue(nonAOwned.size() > 20,
                "Found too few JSPs -- the scan is not looking where it thinks it is.");

        List<String> confirmViolations = new ArrayList<>();
        List<String> onsubmitViolations = new ArrayList<>();
        List<String> modalTitleViolations = new ArrayList<>();
        List<String> modalFooterViolations = new ArrayList<>();

        Pattern modalTitleOwner = Pattern.compile(
                "<([A-Za-z][A-Za-z0-9]*)\\b[^>]*\\bclass=\"modal-title\"");
        Pattern modalFooter = Pattern.compile(
                "class=\"modal-footer\"[^>]*>(.*?)</div>", Pattern.DOTALL);
        Set<String> headingTags = Set.of("h1", "h2", "h3", "h4", "h5", "h6");

        for (Path jsp : nonAOwned) {
            String name = jsp.getFileName().toString();
            String src = withoutJspComments(Files.readString(jsp, StandardCharsets.UTF_8));

            if (src.contains("confirm(")) {
                confirmViolations.add(name);
            }
            if (src.contains("onsubmit=")) {
                onsubmitViolations.add(name);
            }

            Matcher titleMatcher = modalTitleOwner.matcher(src);
            while (titleMatcher.find()) {
                String tag = titleMatcher.group(1).toLowerCase(Locale.ROOT);
                if (!"p".equals(tag) && headingTags.contains(tag)) {
                    modalTitleViolations.add(name + ": <" + tag + " class=\"modal-title\">");
                }
            }

            Matcher footerMatcher = modalFooter.matcher(src);
            while (footerMatcher.find()) {
                String footer = footerMatcher.group(1);
                int dismissIndex = footer.indexOf("data-bs-dismiss");
                int dangerIndex = footer.indexOf("btn-danger");
                int primaryIndex = footer.indexOf("btn-primary");
                int actionIndex = dangerIndex >= 0 && (primaryIndex < 0 || dangerIndex < primaryIndex)
                        ? dangerIndex : primaryIndex;
                if (dismissIndex >= 0 && actionIndex >= 0 && actionIndex < dismissIndex) {
                    modalFooterViolations.add(name + ": primary/destructive button comes "
                            + "before the dismiss button");
                }
            }
        }

        assertTrue(confirmViolations.isEmpty(),
                "confirm() called from a JSP's own script -- use data-confirm instead: "
                        + confirmViolations);
        assertTrue(onsubmitViolations.isEmpty(),
                "inline onsubmit= attribute -- wire the form with addEventListener instead: "
                        + onsubmitViolations);
        assertTrue(modalTitleViolations.isEmpty(),
                ".modal-title on a heading element -- use <p class=\"modal-title\"> instead: "
                        + modalTitleViolations);
        assertTrue(modalFooterViolations.isEmpty(),
                ".modal-footer buttons out of order -- dismiss/cancel first, "
                        + "primary/destructive last: " + modalFooterViolations);
    }

    // --- B8: errors point at the field they name ---

    /**
     * Every controller that calls {@code addFieldError} and the JSP(s) whose
     * form it renders. An explicit table rather than a derivation from
     * {@code RollerViewResolver}: the field a validation names is often on a
     * SIDEBAR tile ({@code TemplatesSidebar.jsp}) or in a modal on the list
     * screen ({@code Categories.jsp}), neither of which the view name spells
     * out. Adding a controller to {@code addFieldError} without adding it here
     * fails {@link #everyFieldErrorNamesAnIdThatExistsInItsJsp} -- an unmapped
     * caller is not silently skipped.
     */
    private static final Map<String, List<String>> FIELD_ERROR_JSPS = Map.of(
            "editor/WeblogConfigController.java", List.of("editor/WeblogConfig.jsp"),
            "editor/CategoryEditController.java", List.of("editor/Categories.jsp"),
            "editor/PageEditController.java", List.of("editor/PageEdit.jsp"),
            "editor/EntryEditController.java", List.of("editor/EntryEdit.jsp"),
            "editor/TemplatesController.java", List.of("editor/TemplatesSidebar.jsp"),
            "editor/MembersController.java", List.of("editor/Members.jsp"),
            "core/CreateWeblogController.java", List.of("core/CreateWeblog.jsp"),
            "core/ProfileController.java", List.of("core/Profile.jsp"),
            "admin/UserEditController.java", List.of("admin/UserEdit.jsp"));

    private static final Path CONTROLLERS =
            Path.of("src/main/java/org/apache/roller/weblogger/ui/controllers");

    private static final Pattern FIELD_ERROR_CALL =
            Pattern.compile("addFieldError\\(\\s*model\\s*,\\s*\"([^\"]+)\"");

    /**
     * A field id a controller names must be an id the page actually renders.
     *
     * <p>The marker is invisible when it misses: {@code getElementById} of a
     * renamed control simply returns null and {@code roller.js} moves on, so
     * the form comes back with a banner and nothing highlighted -- which looks
     * exactly like the behaviour before this feature existed. Nothing else
     * catches that, which is why it is a source scan rather than a convention.
     */
    @Test
    void everyFieldErrorNamesAnIdThatExistsInItsJsp() throws IOException {
        List<Path> controllers;
        try (Stream<Path> walk = Files.walk(CONTROLLERS)) {
            controllers = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }

        List<String> violations = new ArrayList<>();
        int idsChecked = 0;

        for (Path controller : controllers) {
            String src = Files.readString(controller, StandardCharsets.UTF_8);
            Matcher m = FIELD_ERROR_CALL.matcher(src);
            Set<String> fieldIds = new java.util.LinkedHashSet<>();
            while (m.find()) {
                fieldIds.add(m.group(1));
            }
            if (fieldIds.isEmpty()) {
                continue;
            }

            String key = controller.getParent().getFileName() + "/" + controller.getFileName();
            List<String> jspNames = FIELD_ERROR_JSPS.get(key);
            if (jspNames == null) {
                violations.add(key + " calls addFieldError but names no JSP in FIELD_ERROR_JSPS");
                continue;
            }

            StringBuilder markup = new StringBuilder();
            for (String jspName : jspNames) {
                markup.append(Files.readString(JSPS.resolve(jspName), StandardCharsets.UTF_8));
            }
            for (String fieldId : fieldIds) {
                idsChecked++;
                if (!markup.toString().contains("id=\"" + fieldId + "\"")) {
                    violations.add(key + " names field id \"" + fieldId
                            + "\", which no longer exists in " + jspNames);
                }
            }
        }

        assertTrue(idsChecked >= 15,
                "Found only " + idsChecked + " addFieldError ids -- the scan is not looking "
                        + "where it thinks it is");
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    /**
     * The three layouts an admin form can render through must all put the
     * joined ids on {@code <body>}; {@code roller.js} reads them from nowhere
     * else. Miss one and every form on it silently loses the marker --
     * {@code .Profile} and {@code .CreateWeblog} are simplepage, the editor
     * screens are tabbedpage, and {@code .MainMenu} is mainmenupage.
     */
    @Test
    void layoutsRenderInvalidFieldIds() throws IOException {
        for (String layout : List.of("tiles-tabbedpage.jsp", "tiles-simplepage.jsp",
                "tiles-mainmenupage.jsp")) {
            String src = Files.readString(JSPS.resolve("tiles").resolve(layout),
                    StandardCharsets.UTF_8);
            assertTrue(src.contains("data-invalid-fields=\"${fn:escapeXml(invalidFieldIds)}\""),
                    layout + " does not render data-invalid-fields on <body>");
            assertTrue(src.contains("${not empty invalidFieldIds}"),
                    layout + " emits the attribute unconditionally; a form with nothing "
                            + "wrong must carry no attribute at all");
            assertFalse(src.contains("<body>"),
                    layout + " still has a bare <body> tag -- the conditional attribute "
                            + "replaced it, so two body tags means the edit went to the "
                            + "wrong place");
        }

        String js = Files.readString(
                Path.of("src/main/webapp/theme/scripts/roller.js"), StandardCharsets.UTF_8);
        assertTrue(js.contains("invalidFields"),
                "roller.js does not read document.body.dataset.invalidFields, so nothing "
                        + "consumes what the layouts render");
        assertTrue(js.contains("is-invalid") && js.contains("aria-invalid"),
                "roller.js must set both the visual class and the accessible state");
    }

    // --- B9a: Global Config gets the settings rail ---

    /**
     * Task B9a: Global Config is the site's other long settings form, and it
     * now wears the same shape Weblog Settings does -- a
     * {@code .settings-grid} form with a {@code .settings-rail} holding a
     * section index and the Save button, so Save is reachable without
     * scrolling past nine display groups to find it.
     *
     * <p>Two things this pins beyond the markers. The section index is built
     * from {@code globalConfigDef.displayGroups}, so every group heading must
     * carry the {@code id} the index links to -- a hand-maintained list would
     * silently stop matching the moment {@code runtimeConfigDefs.xml} grew a
     * group. And the page's validation is attached with
     * {@code addEventListener}, not with an inline {@code onchange}/
     * {@code onkeyup} on every numeric field: the same reason
     * {@link #oneConfirmIdiomAndOneModalShape} bans inline {@code onsubmit}.
     *
     * <p>{@code id="saveButton"} is load-bearing beyond this page:
     * {@code RollerIT.setGlobalFlags} -- which every browser test that
     * permutes a runtime property goes through -- clicks it by that id.
     */
    @Test
    void globalConfigHasTheSettingsRail() throws IOException {
        String src = Files.readString(JSPS.resolve("admin/GlobalConfig.jsp"), StandardCharsets.UTF_8);
        String markup = withoutJspComments(src);

        assertTrue(markup.contains("class=\"settings-grid form-stacked\""),
                "GlobalConfig.jsp's <form> must be the .settings-grid container, "
                        + "the same shape WeblogConfig.jsp uses");
        assertEquals(1, countOccurrences(markup, "<aside class=\"settings-rail\">"),
                "GlobalConfig.jsp must have exactly one .settings-rail");
        assertTrue(markup.contains("class=\"section-index\""),
                "the rail must carry a .section-index built from the display groups");
        assertTrue(markup.contains("href=\"#cfg-${"),
                "the section index must link to the group headings by id");
        assertTrue(markup.contains("id=\"cfg-${"),
                "every display-group heading must carry the id its index entry links to");
        assertTrue(markup.contains("id=\"saveButton\"") && markup.contains("btn btn-primary w-100"),
                "Save must be the rail's full-width primary and keep id=saveButton, "
                        + "which RollerIT.setGlobalFlags clicks");

        assertFalse(markup.contains("onchange="),
                "GlobalConfig.jsp still has an inline onchange= -- attach the validation "
                        + "with one delegated addEventListener instead");
        assertFalse(markup.contains("onkeyup="),
                "GlobalConfig.jsp still has an inline onkeyup= -- attach the validation "
                        + "with one delegated addEventListener instead");

        assertTrue(markup.contains("class=\"form-check\""),
                "boolean properties render as a .form-check with the label beside the box");
    }

    // --- B9b: media add leads with the drop zone; the theme chooser is cards ---

    /**
     * Task B9b: the upload form asks for the files first. Description,
     * copyright and tags used to sit above the drop zone -- three optional
     * fields between an author and the only thing they came to the page to do,
     * applied identically to every file in a batch. They are in a collapsed
     * {@code <details>} below it now.
     *
     * <p>The folder {@code <select>} stays ABOVE the drop zone: it is not
     * optional the way the three text fields are (it decides where the batch
     * lands) and there is no undo for dropping thirty files into the wrong
     * directory.
     *
     * <p>The three ids the drop-zone script and every media IT drive
     * ({@code mediaDropZone}, {@code uploadedFiles}, {@code uploadButton}) are
     * asserted here too, because reordering markup is exactly the edit that
     * loses one by accident.
     */
    @Test
    void mediaAddLeadsWithTheDropZone() throws IOException {
        String markup = withoutJspComments(
                Files.readString(JSPS.resolve("editor/MediaFileAdd.jsp"), StandardCharsets.UTF_8));

        int folder = markup.indexOf("id=\"mfadd_bean_directoryId\"");
        int dropZone = markup.indexOf("id=\"mediaDropZone\"");
        int details = markup.indexOf("<details class=\"editor-details\"");
        int description = markup.indexOf("id=\"mfadd_bean_description\"");

        assertTrue(folder >= 0 && dropZone >= 0 && details >= 0 && description >= 0,
                "MediaFileAdd.jsp is missing one of folder/drop zone/details/description "
                        + "-- the scan is not looking where it thinks it is");
        assertTrue(folder < dropZone,
                "the folder select must stay above the drop zone: it decides where the "
                        + "batch lands and there is no undo");
        assertTrue(dropZone < details,
                "the drop zone must come before the optional Details drawer");
        assertTrue(details < description,
                "description/copyright/tags belong inside the Details drawer");

        for (String id : List.of("mediaDropZone", "uploadedFiles", "uploadButton", "mediaChosenFiles")) {
            assertTrue(markup.contains("id=\"" + id + "\""),
                    "MediaFileAdd.jsp lost id=\"" + id + "\", which its own script "
                            + "and every media IT drive");
        }
    }

    /**
     * Task B9b: picking a theme is a grid of cards, each showing the theme's
     * own preview image, not a one-line {@code <select>} beside a thumbnail
     * fetched over ajax. Every card is a {@code <label>} wrapping a radio, so
     * the whole card is the hit target and the browser does the grouping.
     *
     * <p>The chooser's {@code change} handler is delegated rather than an
     * inline {@code onchange}, for the reason
     * {@link #globalConfigHasTheSettingsRail} gives: an inline handler is a
     * second place generated text reaches raw JavaScript. The old
     * {@code #themeSelector} select and the {@code #themeThumbnail} it drove
     * must both be gone -- leaving either means two chooser mechanisms on one
     * page, only one of which the state machine listens to.
     */
    @Test
    void themeChooserIsACardGrid() throws IOException {
        String markup = withoutJspComments(
                Files.readString(JSPS.resolve("editor/ThemeEdit.jsp"), StandardCharsets.UTF_8));

        assertTrue(markup.contains("class=\"theme-cards\""),
                "ThemeEdit.jsp must render the .theme-cards grid");
        assertTrue(markup.contains("<label class=\"theme-card\">"),
                "every theme is a <label class=\"theme-card\"> wrapping its radio");
        assertTrue(markup.contains("type=\"radio\" name=\"selectedThemeId\""),
                "the cards must post the same selectedThemeId the controller reads");
        assertFalse(markup.contains("id=\"themeSelector\""),
                "the old <select id=\"themeSelector\"> must be gone, not merely hidden");
        assertFalse(markup.contains("id=\"themeThumbnail\""),
                "the ajax-driven thumbnail is replaced by each card's own preview image");
        assertFalse(markup.contains("onchange="),
                "wire the chooser with a delegated change listener, not an inline onchange");

        String css = Files.readString(ROLLER_CSS, StandardCharsets.UTF_8);
        for (String rule : List.of(".theme-cards", ".theme-card", ".theme-card-thumb",
                ".theme-card-name", ".theme-card-desc")) {
            assertTrue(css.contains(rule), "roller.css lacks " + rule);
        }
    }

    // --- B9c: the main menu lists weblogs as quiet rows ---

    /**
     * Task B9c: the first screen an author lands on listed each weblog as a
     * Bootstrap card carrying a raw absolute URL on its own line and a
     * {@code .btn-group} of four equally-loud buttons -- a toolbar, on a
     * screen whose job is to get you into one weblog. It is a quiet row now:
     * the name links to the weblog (so the URL line has nothing left to say),
     * the handle and entry count wear the mono data face, and the actions are
     * one primary "New entry" beside plain {@code .quiet-link}s.
     *
     * <p>{@code h3.mm_weblog_name} survives the restyle deliberately:
     * {@code Routes.java} identifies {@code menu.rol} by that selector, so
     * renaming it here without moving the marker in the same commit fails
     * {@code RouteSweepIT}.
     *
     * <p>Two {@code btn btn-primary} occurrences, not one: the empty state
     * above the loop ("you have no blog yet") has its own, and the two branches
     * are mutually exclusive at render time even though both are in the source.
     */
    @Test
    void mainMenuListsWeblogsAsQuietRows() throws IOException {
        String markup = withoutJspComments(
                Files.readString(JSPS.resolve("core/MainMenu.jsp"), StandardCharsets.UTF_8));

        assertTrue(markup.contains("class=\"weblog-row\""),
                "each weblog must be a .weblog-row");
        assertTrue(markup.contains("<h3 class=\"mm_weblog_name section-head\">"),
                "h3.mm_weblog_name is Routes.java's marker for menu.rol -- keep it");
        assertTrue(markup.contains("class=\"weblog-actions\""),
                "the row's actions belong in a .weblog-actions strip");
        assertTrue(markup.contains("class=\"quiet-link\""),
                "everything but New entry is a quiet link, not a button");

        assertFalse(markup.contains("btn-group"),
                "the four-button toolbar is gone -- one primary plus quiet links");
        assertFalse(markup.contains("yourWeblogBox"),
                "the row is not a Bootstrap card any more");
        assertFalse(markup.contains("btn btn-secondary"),
                "a secondary button in the row is the toolbar coming back");

        assertEquals(2, countOccurrences(markup, "btn btn-primary"),
                "exactly two primaries in the source: the empty state's and the "
                        + "row's New entry, which never render together");
        assertEquals(1, countOccurrences(markup, "urls.weblogAbsolute(perms.weblog)"),
                "the raw URL line is gone; the weblog name is the only link to it");

        String css = Files.readString(ROLLER_CSS, StandardCharsets.UTF_8);
        for (String rule : List.of(".weblog-row", ".weblog-actions")) {
            assertTrue(css.contains(rule), "roller.css lacks " + rule);
        }
    }

    // --- B10: the weblog switcher in the top bar ---

    /**
     * Task B10: the top bar's brand now carries a {@code .weblog-switcher}
     * dropdown when the signed-in user holds more than one weblog
     * ({@code BaseController.populateCommonModel} adds {@code userWeblogs}
     * only in that case, so the {@code <c:if>} around this markup is what
     * keeps a one-weblog session's top bar unchanged). The confirm-idiom
     * rule applies here too: this is a navigation control, not a destructive
     * one, so it needs no {@code data-confirm} -- but it must not regress to
     * the inline {@code onclick} idiom {@code oneConfirmIdiomAndOneModalShape}
     * already bans on every other admin screen.
     */
    @Test
    void theTopBarCarriesTheSwitcher() throws IOException {
        String markup = withoutJspComments(
                Files.readString(JSPS.resolve("tiles/bannerStatus.jsp"), StandardCharsets.UTF_8));

        assertTrue(markup.contains("weblog-switcher"),
                "bannerStatus.jsp must render the .weblog-switcher dropdown");
        assertFalse(markup.contains("onclick="),
                "the switcher is a nav control -- no inline onclick, ever");

        String css = Files.readString(ROLLER_CSS, StandardCharsets.UTF_8);
        assertTrue(css.contains(".weblog-switcher"), "roller.css lacks .weblog-switcher");
    }

    /**
     * B9's `<details>` disclosure on MediaFileAdd.jsp had two loose ends,
     * folded in here per B12's deferred-minors decision. First, its summary
     * used a bare {@code cursor:pointer} rule rather than the same chevron
     * treatment {@code .editor-drawer} already gives its own disclosure
     * toggles ({@code EntryEdit.jsp}'s SEO drawer) -- two disclosure widgets
     * with two different looks reads as two components, not one idiom.
     * Second, the details started collapsed on every render, including a
     * re-render after a validation refusal that carries typed description,
     * copyright or tags -- collapsing typed-but-unsubmitted text out of view
     * is the same mistake the field-error work exists to prevent for
     * top-level fields, just one level down.
     */
    @Test
    void mediaFileAddDetailsMatchesTheDrawerChevronAndReopensWithContent() throws IOException {
        String markup = withoutJspComments(
                Files.readString(JSPS.resolve("editor/MediaFileAdd.jsp"), StandardCharsets.UTF_8));

        assertTrue(markup.contains(
                "<details class=\"editor-details\" "
                        + "${not empty bean.description or not empty bean.tagsAsString "
                        + "or not empty bean.copyrightText ? 'open' : ''}>"),
                "the details must reopen when a re-render carries description, tags or copyright");

        String css = Files.readString(ROLLER_CSS, StandardCharsets.UTF_8);
        int drawerBlockStart = css.indexOf(".editor-drawer {");
        int drawerBlockEnd = css.indexOf("}", drawerBlockStart);
        String drawerBlock = css.substring(drawerBlockStart, drawerBlockEnd);
        int detailsBlockStart = css.indexOf(".editor-details > summary {");
        int detailsBlockEnd = css.indexOf("}", detailsBlockStart);
        String detailsBlock = css.substring(detailsBlockStart, detailsBlockEnd);

        assertTrue(detailsBlock.contains("var(--accent)"),
                ".editor-details > summary must wear .editor-drawer's accent color");
        assertTrue(detailsBlock.contains("font-weight: 600"),
                ".editor-details > summary must wear .editor-drawer's weight");
        assertTrue(drawerBlock.contains("var(--accent)") && drawerBlock.contains("font-weight: 600"),
                "sanity: .editor-drawer itself must still carry the properties being reused");

        int chevronStart = css.indexOf(".editor-details > summary::");
        assertTrue(chevronStart >= 0, ".editor-details > summary must have its own rotating chevron, "
                + "the same shape as .editor-drawer::after / .editor-drawer:not(.collapsed)::after");
    }

    /**
     * B9's "first card checked" fallback only covered a blank
     * {@code selectedThemeId} (a weblog on a custom theme). A weblog whose
     * {@code editorTheme} names a shared theme that no longer exists (a
     * retired id, or a directory removed by hand) sends a NON-blank
     * {@code selectedThemeId} that matches no card, so the old condition
     * {@code opt.id == selectedThemeId or (empty selectedThemeId and
     * themeStatus.first)} checks nothing at all -- the browser posts no
     * {@code selectedThemeId}, and save answers "theme not found". The
     * fallback must trigger whenever the weblog's current theme is not
     * found among the rendered cards, not only when it is blank.
     */
    @Test
    void themeCardFallsBackToTheFirstCardWhenTheCurrentThemeIsNotInTheList() throws IOException {
        String markup = withoutJspComments(
                Files.readString(JSPS.resolve("editor/ThemeEdit.jsp"), StandardCharsets.UTF_8));

        assertFalse(markup.contains("empty selectedThemeId and themeStatus.first"),
                "the old fallback only handled a blank selectedThemeId, not one naming a missing theme");
        assertTrue(markup.contains("selectedThemeFound"),
                "the checked condition must fall back to the first card whenever the weblog's "
                        + "current theme id is not found among the rendered cards");
    }

    /**
     * JSP comments are not part of the rendered page, so a scan asserting
     * that some pattern is ABSENT must not be defeated by prose that merely
     * mentions it (or, worse, pass for the wrong reason on prose that
     * mentions the FIXED pattern). See AdminJspHygieneTest, which established
     * this helper first.
     */
    private static String withoutJspComments(String src) {
        return src.replaceAll("(?s)<%--.*?--%>", "");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
