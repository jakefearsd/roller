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
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task B11's small-bug sweep, routed into its own class rather than
 * {@code JspConsistencyTest} because that file is owned by the concurrent
 * B10 agent working in this same worktree (weblog switcher).
 *
 * <p>Each scan strips JSP comments first ({@link #withoutJspComments}) so a
 * comment merely mentioning a banned pattern cannot defeat an
 * absence-assertion, the same convention {@code JspConsistencyTest} follows.
 */
class AdminJspBugSweepTest {

    static final Path JSPS = Path.of("src/main/webapp/WEB-INF/jsps");

    static Stream<Path> jsps() throws IOException {
        return Files.walk(JSPS).filter(p -> p.toString().endsWith(".jsp"));
    }

    private static String withoutJspComments(String src) {
        return src.replaceAll("(?s)<%--.*?--%>", "");
    }

    // --- B11 bullet 3: every <div opened in a JSP must be closed ---

    /**
     * {@code TemplateEdit.jsp} opened {@code <div id="accordion">} to wrap the
     * advanced-settings panel and never closed it, so the accordion's own
     * closing {@code </div>} (for {@code #panel-advanced}) was mistaken for
     * the outer one and everything after the advanced panel -- the closing
     * {@code </c:if>}, the CSRF input, {@code </form>} and the page's own
     * script -- rendered nested one level deeper than the surrounding markup
     * expects. JSP {@code <c:choose>}/{@code <c:when>}/{@code <c:otherwise>}
     * branches need no special handling here: each branch balances its own
     * {@code <div>}s independently (this file's own {@code #panel-advanced}
     * is proof -- its branches are not conditional at all), so a plain count
     * over the whole file, comments stripped, is the right measure.
     */
    @Test
    void everyDivOpenedInAJspIsClosed() throws IOException {
        List<Path> allJsps = jsps().toList();
        assertTrue(allJsps.size() > 20,
                "Found too few JSPs -- the scan is not looking where it thinks it is.");

        List<String> violations = new ArrayList<>();
        for (Path jsp : allJsps) {
            String src = withoutJspComments(Files.readString(jsp, StandardCharsets.UTF_8));
            int opens = countMatches(src, Pattern.compile("<div\\b"));
            int closes = countMatches(src, Pattern.compile("</div>"));
            if (opens != closes) {
                violations.add(jsp + ": " + opens + " <div> vs " + closes + " </div>");
            }
        }

        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    // --- B11 bullet 4: no heading-level skips (h3 straight to h4/h5) ---

    /**
     * Package A (the editor rebuild) owns these files in a parallel worktree
     * -- see task-B10 brief / {@code JspConsistencyTest.A_OWNED}. Skipped for
     * the same reason that scan skips them: a post-merge task applies this
     * heading-level fix to the editor screens once A lands.
     */
    private static final Set<String> A_OWNED = Set.of("EntryEdit.jsp", "EntryEditor.jsp", "PageEdit.jsp");

    /**
     * Every card/section heading in the admin UI used {@code h4}, or in one
     * place {@code h5}, immediately inside a page whose own title is an
     * {@code h2} (the tiles layout's {@code roller-page-title}) with no
     * {@code h3} anywhere between -- a level skip. {@code MediaFileAdd.jsp}
     * and {@code TemplateEdit.jsp} render their card heading as
     * {@code h4.card-title}; {@code MediaFileAddSuccess.jsp} (twice) and
     * {@code MediaFileEdit.jsp} already carried {@code .section-head} on an
     * {@code h4}/{@code h5}. All four become plain {@code h3} elements
     * (carrying {@code .section-head} instead of {@code .card-title}, and
     * keeping {@code #cropSectionTitle} -- {@code MediaCropIT} identifies the
     * crop section by that id), matching the single heading level every
     * other admin card/section already uses.
     */
    @Test
    void noHeadingLevelIsSkippedToH4OrH5() throws IOException {
        List<Path> nonAOwned = jsps()
                .filter(p -> !A_OWNED.contains(p.getFileName().toString()))
                .toList();
        assertTrue(nonAOwned.size() > 20,
                "Found too few JSPs -- the scan is not looking where it thinks it is.");

        List<String> violations = new ArrayList<>();
        for (Path jsp : nonAOwned) {
            String src = withoutJspComments(Files.readString(jsp, StandardCharsets.UTF_8));
            if (src.contains("<h4") || src.contains("<h5")) {
                violations.add(jsp.toString());
            }
        }

        assertTrue(violations.isEmpty(),
                "non-editor-rebuild JSPs must not skip a heading level to h4/h5: " + violations);
    }

    // --- B11 bullet 5: dead <str:truncateNicely> markup (no str taglib) ---

    /**
     * {@code MediaFileImageChooser.jsp} and {@code MediaFileView.jsp} (twice)
     * wrapped a filename in {@code <str:truncateNicely>}, a Struts tag from
     * before the Struts-to-Spring-MVC migration. No {@code str} taglib is
     * declared anywhere under {@code webapp/} (confirmed by
     * {@link #noStrTaglibIsDeclaredAnywhere} below), so the JSP container
     * treats the unrecognised prefix as literal markup: the opening and
     * closing tags rendered verbatim around the filename instead of doing
     * any truncation at all, on every media tile.
     */
    @Test
    void noStrTagRendersInAnyJsp() throws IOException {
        List<Path> allJsps = jsps().toList();
        assertTrue(allJsps.size() > 20,
                "Found too few JSPs -- the scan is not looking where it thinks it is.");

        List<String> violations = new ArrayList<>();
        for (Path jsp : allJsps) {
            String src = withoutJspComments(Files.readString(jsp, StandardCharsets.UTF_8));
            if (src.contains("<str:")) {
                violations.add(jsp.toString());
            }
        }

        assertTrue(violations.isEmpty(), "dead <str:...> markup (no str taglib exists): " + violations);
    }

    /**
     * Confirms the premise {@link #noStrTagRendersInAnyJsp} depends on: no
     * {@code str} taglib is declared anywhere, so {@code <str:...>} could
     * never have been a real custom tag -- it was always going to render as
     * literal text.
     */
    @Test
    void noStrTaglibIsDeclaredAnywhere() throws IOException {
        try (Stream<Path> walk = Files.walk(Path.of("src/main/webapp"))) {
            List<Path> withStrPrefix = walk
                    .filter(p -> p.toString().endsWith(".jsp") || p.toString().endsWith(".tld"))
                    .filter(p -> {
                        try {
                            return Files.readString(p, StandardCharsets.UTF_8).contains("prefix=\"str\"");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .toList();
            assertTrue(withStrPrefix.isEmpty(), "expected no str taglib declaration, found: " + withStrPrefix);
        }
    }

    // --- B11 bullet 6: user-controlled URL text unescaped in an href ---

    /**
     * A starting-point sweep -- {@code grep -rn 'href="${' WEB-INF/jsps |
     * grep -v escapeXml | grep -v 'c:url\|urls\.'} -- turns out to be almost
     * entirely false positives: every {@code href="${var}"} in the admin UI
     * is fed by a {@code <c:url>}-built variable (which URL-encodes its
     * params and entity-encodes the joining {@code &}) or a {@code urls.*}
     * helper call (which builds a server-side URL from ids/handles, not raw
     * text), so grepping by variable name misses the one real gap: a
     * {@code urls.*} call with a raw field appended directly after it.
     * {@code PageEdit.jsp} (A-owned, unrelated to this fix) already gets this
     * right -- {@code href="${urls.weblogAbsolute(actionWeblog)}page/${fn:
     * escapeXml(bean.slug)}"} -- because a page slug is author-controlled and
     * restricted only against containing {@code '/'} or a reserved name
     * (see {@code JPAWeblogPageManagerImpl.savePage}), not against quote or
     * angle-bracket characters. {@code Pages.jsp}'s list-page equivalent
     * left the same {@code ${p.slug}} unescaped in the href -- a slug like
     * {@code x" onmouseover="alert(1)} breaks out of the attribute -- while
     * the adjacent link TEXT on the same line already correctly went through
     * {@code <c:out value="${p.slug}"/>}.
     */
    @Test
    void pagesListEscapesTheSlugInThePermalinkHref() throws IOException {
        String src = Files.readString(JSPS.resolve("editor/Pages.jsp"), StandardCharsets.UTF_8);

        assertTrue(src.contains(
                        "href=\"${urls.weblogAbsolute(actionWeblog)}page/${fn:escapeXml(p.slug)}\""),
                "Pages.jsp's permalink href must escape the author-controlled page slug, "
                        + "the same way PageEdit.jsp's own permalink already does:\n" + src);
    }

    private static int countMatches(String haystack, Pattern pattern) {
        Matcher m = pattern.matcher(haystack);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }
}
