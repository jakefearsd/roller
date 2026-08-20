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
package org.apache.roller.weblogger.ui.rendering.servlets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source scan for the accessibility baseline every bundled theme must carry,
 * written in the same spirit as {@link ThemeCspCoverageTest}: it covers pages
 * no rendering fixture visits and themes no rendering test exercises, and it
 * catches a *missing* attribute -- which a render assertion on one page can
 * never do for the other eleven.
 *
 * <p>Each rule below was a real defect across all four themes before the
 * 2026-08-20 fit-and-finish sweep: no {@code <html lang>} anywhere, no way for
 * a keyboard reader to skip the header, no {@code color-scheme} (so an
 * always-dark theme got light form controls and scrollbars), and two
 * stylesheets that removed the focus ring outright.
 */
class ThemeAccessibilityTest {

    private static final Path THEMES = Paths.get("src/main/webapp/themes");

    // ------------------------------------------------------------- helpers

    private static List<Path> templates() throws IOException {
        return filesUnderThemes(".vm");
    }

    private static List<Path> stylesheets() throws IOException {
        return filesUnderThemes(".css");
    }

    private static List<Path> filesUnderThemes(String suffix) throws IOException {
        assertTrue(Files.isDirectory(THEMES), "Expected " + THEMES.toAbsolutePath());
        List<Path> found = new ArrayList<>();
        try (Stream<Path> themeDirs = Files.list(THEMES)) {
            for (Path theme : themeDirs.filter(Files::isDirectory).toList()) {
                try (Stream<Path> files = Files.list(theme)) {
                    files.filter(f -> f.toString().endsWith(suffix)).forEach(found::add);
                }
            }
        }
        assertFalse(found.isEmpty(), "no " + suffix + " files found under " + THEMES);
        return found;
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String name(Path p) {
        return p.getParent().getFileName() + "/" + p.getFileName();
    }

    // --------------------------------------------------------------- lang

    /**
     * {@code <html>} with no {@code lang} leaves a screen reader guessing the
     * pronunciation of every word on the page. The value must be the BCP-47
     * tag ({@code WeblogWrapper#getLanguageTag}), never the stored Java locale
     * -- {@code lang="en_US"} is not a tag any user agent recognises, so it is
     * exactly as good as having none.
     */
    @Test
    void everyThemePageDeclaresItsLanguageAsABcp47Tag() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path template : templates()) {
            String src = read(template);
            int html = src.indexOf("<html");
            if (html < 0) {
                continue;
            }
            String tag = src.substring(html, src.indexOf('>', html) + 1);
            if (!tag.contains("lang=")) {
                offenders.add(name(template) + ": " + tag + " has no lang attribute");
            } else if (!tag.contains("getLanguageTag()")) {
                offenders.add(name(template) + ": " + tag
                        + " must derive lang from WeblogWrapper#getLanguageTag()");
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }

    // ---------------------------------------------------------- skip link

    /**
     * A keyboard reader landing on any theme page tabs through the whole
     * header -- site name, category list, every nav page -- before reaching
     * the first entry, on every single page. The skip link is the standard
     * escape, and it only works if it is the first focusable thing in the
     * document and points at an id that exists.
     */
    @Test
    void everyThemePageThatOpensABodyOffersASkipLinkToItsMain() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path template : templates()) {
            String src = read(template);
            if (!src.contains("<body")) {
                continue;
            }
            if (!src.contains("href=\"#main\"")) {
                offenders.add(name(template) + ": no skip link targeting #main");
                continue;
            }
            if (!src.contains("id=\"main\"")) {
                offenders.add(name(template) + ": skip link has no id=\"main\" target");
                continue;
            }
            int body = src.indexOf("<body");
            if (src.indexOf("href=\"#main\"") < body) {
                offenders.add(name(template) + ": the skip link must sit inside <body>");
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }

    // ------------------------------------------------------- color-scheme

    /**
     * Without {@code color-scheme} the browser paints form controls,
     * scrollbars and the canvas from the *user's* preference, not the
     * theme's. Portfolio is the visible case: an always-dark page
     * (#0e0e10) with light scrollbars and light native form chrome.
     */
    @Test
    void everyThemeStylesheetDeclaresItsColorScheme() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path sheet : stylesheets()) {
            if (!read(sheet).contains("color-scheme:")) {
                offenders.add(name(sheet) + ": no color-scheme declaration");
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }

    // -------------------------------------------------------------- focus

    /**
     * {@code outline: none} without a replacement ring is the single most
     * common way a stylesheet makes itself unusable by keyboard. Both sites
     * this catches were real: journal's audience-form fields and portfolio's
     * search input.
     */
    @Test
    void noThemeStylesheetSuppressesTheFocusRing() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path sheet : stylesheets()) {
            String css = read(sheet);
            if (css.contains("outline: none") || css.contains("outline:none")
                    || css.contains("outline: 0") || css.contains("outline:0")) {
                offenders.add(name(sheet) + ": suppresses the focus outline");
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }

    /**
     * The positive half of the rule above: every theme needs one global
     * {@code :focus-visible} ring, so a control the theme never styled
     * individually still shows focus.
     */
    @Test
    void everyThemeStylesheetGivesKeyboardFocusAVisibleRing() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path sheet : stylesheets()) {
            if (!read(sheet).contains(":focus-visible")) {
                offenders.add(name(sheet) + ": no :focus-visible rule");
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }
}
