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
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code roller-tokens.css} -- the "Quiet Instrument" design
 * tokens (see {@code docs/design/design-system.md}) -- stays in sync with
 * its spec and stays wired into the page.
 *
 * <p>Three failure modes this guards, modeled on {@link MessageKeyTest}'s
 * webapp-source scanning:
 * <ul>
 *   <li>a color slips in that is not one of the spec's 21 hex values --
 *       the whole point of a token file is that every color traces back to
 *       the spec, not to whatever a later edit typed in;</li>
 *   <li>the light and dark palettes drift out of parity -- a token defined
 *       under {@code :root} but never overridden under
 *       {@code prefers-color-scheme: dark} (or vice versa) is the classic
 *       unreadable-page bug: half the UI keeps its light-mode color on a
 *       dark background;</li>
 *   <li>{@code head.jsp} stops referencing the stylesheet, silently
 *       reverting every admin page to unstyled tokens.</li>
 * </ul>
 */
public class DesignTokenTest {

    private static final Path TOKENS_CSS =
            Paths.get("src/main/webapp/roller-ui/styles/roller-tokens.css");

    private static final Path HEAD_JSP =
            Paths.get("src/main/webapp/WEB-INF/jsps/tiles/head.jsp");

    /**
     * The spec's 21 hex values (docs/design/design-system.md): 11 light
     * tokens + 11 dark tokens, minus the 1 value (--focus) shared by both.
     */
    private static final Set<String> SPEC_HEX_VALUES = Set.of(
            // light
            "#F7F9F9", "#FFFFFF", "#17262A", "#5A6E72", "#DCE4E4",
            "#0F6E68", "#E3F0EE", "#2F7D4F", "#9A6A1F", "#A33B2E", "#2AA198",
            // dark (excluding #2AA198, already listed as --focus above)
            "#131C1C", "#1A2626", "#DCE7E5", "#8FA5A2", "#2A3838",
            "#4FB3AA", "#1E3230", "#6FBF8F", "#D0A45C", "#D0705F"
    );

    private static final Pattern HEX_LITERAL = Pattern.compile("#[0-9A-Fa-f]{6}\\b");

    private static final Pattern COLOR_TOKEN_DECL =
            Pattern.compile("(--[a-z-]+)\\s*:\\s*(#[0-9A-Fa-f]{6})\\s*;");

    /**
     * Matches specifically {@code @media (prefers-color-scheme: dark) { :root {
     * ... } }} and captures only the {@code :root} body. Anchored to
     * {@code :root} right after the media query (not just "the next {"), and
     * the body capture is reluctant ({@code .*?}) so it stops at the first
     * {@code }} -- i.e. :root's own close -- rather than greedily running to
     * the last {@code }\s*}} in the file. A second, unrelated @media block
     * added later (e.g. a print query) cannot widen or shift this match.
     */
    private static final Pattern DARK_MEDIA_BLOCK = Pattern.compile(
            "@media\\s*\\(prefers-color-scheme:\\s*dark\\)\\s*\\{\\s*:root\\s*\\{(.*?)\\}\\s*\\}",
            Pattern.DOTALL);

    @Test
    public void everyHexLiteralIsFromTheSpec() throws IOException {
        String css = readTokensCss();
        Matcher matcher = HEX_LITERAL.matcher(css);
        Set<String> found = new TreeSet<>();
        Set<String> unexpected = new TreeSet<>();
        while (matcher.find()) {
            String hex = matcher.group().toUpperCase(java.util.Locale.ROOT);
            found.add(hex);
            if (!SPEC_HEX_VALUES.contains(hex)) {
                unexpected.add(hex);
            }
        }

        assertTrue(found.size() > 0,
                "Found no hex color literals in " + TOKENS_CSS.toAbsolutePath()
                        + " -- the test is not looking where it thinks it is.");

        assertTrue(unexpected.isEmpty(),
                TOKENS_CSS + " uses hex color(s) that are not in the \"Quiet Instrument\" "
                        + "spec's 21-value set (docs/design/design-system.md):\n  "
                        + String.join("\n  ", unexpected));
    }

    @Test
    public void lightAndDarkPalettesDefineTheSameTokenNames() throws IOException {
        String css = readTokensCss();

        Matcher darkBlockMatcher = DARK_MEDIA_BLOCK.matcher(css);
        assertTrue(darkBlockMatcher.find(),
                TOKENS_CSS + " has no @media (prefers-color-scheme: dark) block");
        String darkBlock = darkBlockMatcher.group(1);
        String lightBlock = css.substring(0, darkBlockMatcher.start());

        Set<String> lightTokens = colorTokenNames(lightBlock);
        Set<String> darkTokens = colorTokenNames(darkBlock);

        assertFalse(lightTokens.isEmpty(), "Found no color custom properties before the dark media block");
        assertFalse(darkTokens.isEmpty(), "Found no color custom properties inside the dark media block");

        assertEquals(lightTokens, darkTokens,
                "Light and dark palettes in " + TOKENS_CSS + " define different token names -- "
                        + "every color token must be overridden in both, or a page renders half "
                        + "light-mode colors on a dark background. Light-only: "
                        + diff(lightTokens, darkTokens) + "; dark-only: " + diff(darkTokens, lightTokens));
    }

    @Test
    public void headJspReferencesTokensStylesheet() throws IOException {
        assertTrue(Files.exists(HEAD_JSP), "Expected to find " + HEAD_JSP.toAbsolutePath());
        String content = Files.readString(HEAD_JSP, StandardCharsets.UTF_8);

        assertTrue(content.contains("roller-tokens.css"),
                HEAD_JSP + " no longer references roller-tokens.css");

        int bootstrapIndex = content.indexOf("bootstrap.min.css");
        int tokensIndex = content.indexOf("roller-tokens.css");
        int rollerCssIndex = content.indexOf("roller-ui/styles/roller.css");

        assertTrue(bootstrapIndex >= 0, HEAD_JSP + " no longer links bootstrap.min.css");
        assertTrue(rollerCssIndex >= 0, HEAD_JSP + " no longer links roller.css");

        assertTrue(bootstrapIndex < tokensIndex,
                "roller-tokens.css must be linked after bootstrap.min.css so its custom "
                        + "properties are available to override, but it appears first in " + HEAD_JSP);
        assertTrue(tokensIndex < rollerCssIndex,
                "roller-tokens.css must be linked before roller.css so roller.css can override "
                        + "the tokens, but it appears after in " + HEAD_JSP);
    }

    private static Set<String> colorTokenNames(String block) {
        Matcher matcher = COLOR_TOKEN_DECL.matcher(block);
        Set<String> names = new LinkedHashSet<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> result = new TreeSet<>(a);
        result.removeAll(b);
        return result;
    }

    private String readTokensCss() throws IOException {
        assertTrue(Files.exists(TOKENS_CSS), "Expected to find " + TOKENS_CSS.toAbsolutePath());
        return Files.readString(TOKENS_CSS, StandardCharsets.UTF_8);
    }
}
