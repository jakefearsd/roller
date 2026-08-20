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
package org.apache.roller.weblogger.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source scans pinning the 2026-08-20 fit-and-finish repairs to the admin
 * JSP tree. Each one exists because the property it asserts is invisible in
 * a rendered page and therefore cannot regress loudly: a flash region that
 * stopped announcing itself, a layout that lost its {@code lang}, a table
 * whose headers stopped being scoped, or a screen whose one primary action
 * quietly became three.
 *
 * <p>Every test collects ALL violations before asserting, so one run names
 * the whole repair list rather than the first file that happens to fail.
 */
class AdminJspHygieneTest {

    private static final Path JSPS = Path.of("src/main/webapp/WEB-INF/jsps");
    private static final Path STYLES = Path.of("src/main/webapp/roller-ui/styles/roller.css");

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String jsp(String relative) {
        Path p = JSPS.resolve(relative);
        assertTrue(Files.isRegularFile(p), "JSP moved or deleted? " + p.toAbsolutePath());
        return read(p);
    }

    // ---------------------------------------------------------------- Task 11

    /**
     * A flash message that is not in a live region is invisible to a screen
     * reader: the page has already finished loading when it arrives, so
     * nothing re-reads it. #messages is polite (a success confirmation may
     * wait for a pause), #errors is assertive.
     */
    @Test
    void flashRegionsAnnounceThemselves() {
        String src = jsp("tiles/messages.jsp");
        List<String> missing = new ArrayList<>();
        if (!src.contains("role=\"status\"") || !src.contains("aria-live=\"polite\"")) {
            missing.add("#messages needs role=\"status\" aria-live=\"polite\"");
        }
        if (!src.contains("role=\"alert\"")) {
            missing.add("#errors needs role=\"alert\"");
        }
        assertTrue(missing.isEmpty(), "tiles/messages.jsp: " + String.join("; ", missing));
    }

    /**
     * The success block used to render each message as a nested
     * {@code .alert-info} inside the {@code .alert-success} wrapper -- a box
     * in a box, in the wrong colour. It is a flat list now, like the errors
     * block beside it.
     */
    @Test
    void successMessagesAreNotBoxedInsideTheSuccessBox() {
        String src = jsp("tiles/messages.jsp");
        assertTrue(!src.contains("alert alert-info"),
                "tiles/messages.jsp still nests an .alert-info inside the .alert-success wrapper");
    }

    /**
     * The 10-second auto-dismiss must never reach an error banner: an error
     * the reader is still reading should not evaporate, and on GenericError
     * -- whose whole body used to be the flash region -- it emptied the page.
     */
    @Test
    void autoDismissIsScopedToSuccessMessages() {
        String src = jsp("tiles/messages.jsp");
        assertTrue(src.contains("#messages .alert"),
                "tiles/messages.jsp: auto-dismiss must select #messages .alert, not every .alert");
    }

    /** The inline {@code <style>} block belongs with the other alert rules. */
    @Test
    void messagesJspCarriesNoInlineStyleBlock() {
        assertTrue(!jsp("tiles/messages.jsp").contains("<style>"),
                "tiles/messages.jsp: move the alert list styling into roller.css");
        assertTrue(read(STYLES).contains(".alert ul"),
                "roller.css: the .alert ul rule from messages.jsp should live here");
    }

    /**
     * GenericError had no body at all: the "weblog creation is disabled" and
     * "one weblog per user" answers rendered a single auto-dismissing alert
     * on an otherwise blank page, which then emptied.
     */
    @Test
    void genericErrorHasABody() {
        String src = jsp("core/GenericError.jsp");
        assertTrue(src.contains("empty-state"),
                "core/GenericError.jsp needs an .empty-state body explaining what happened");
        assertTrue(src.contains("menu.rol"),
                "core/GenericError.jsp needs a way back to the weblog list");
    }

    /**
     * Both chrome layouts must put the page title above the flash region:
     * tabbedpage used to render messages first, so the two screens disagreed
     * about where a confirmation appears.
     */
    @Test
    void bothChromeLayoutsPutTheTitleBeforeTheMessages() {
        List<String> wrong = new ArrayList<>();
        for (String layout : List.of("tiles/tiles-tabbedpage.jsp", "tiles/tiles-mainmenupage.jsp")) {
            String src = jsp(layout);
            int title = src.indexOf("roller-page-title");
            int messages = src.indexOf("${tile_messages}");
            if (title < 0 || messages < 0 || title > messages) {
                wrong.add(layout);
            }
        }
        assertTrue(wrong.isEmpty(), "title must precede the flash region in: " + wrong);
    }

    // ---------------------------------------------------------------- Task 14

    private static final List<String> TILES_LAYOUTS = List.of(
            "tiles/tiles-barepage.jsp",
            "tiles/tiles-errorpage.jsp",
            "tiles/tiles-installpage.jsp",
            "tiles/tiles-loginpage.jsp",
            "tiles/tiles-mainmenupage.jsp",
            "tiles/tiles-popuppage.jsp",
            "tiles/tiles-simplepage.jsp",
            "tiles/tiles-tabbedpage.jsp");

    /**
     * Without a document language a screen reader picks its own -- usually the
     * user's OS locale -- and reads English admin copy with, say, German
     * phonemes. Eight layouts, so one missing declaration hides in seven
     * correct ones.
     */
    @Test
    void everyLayoutDeclaresADocumentLanguage() {
        List<String> missing = new ArrayList<>();
        for (String layout : TILES_LAYOUTS) {
            if (!jsp(layout).contains("<html lang=")) {
                missing.add(layout);
            }
        }
        assertTrue(missing.isEmpty(), "layouts with no <html lang>: " + missing);
    }

    /** Every layout must name the tab; popuppage used to ship none at all. */
    @Test
    void everyChromeLayoutSetsATitle() {
        List<String> missing = new ArrayList<>();
        for (String layout : TILES_LAYOUTS) {
            // barepage is deliberately chrome-free -- see its own comment.
            if (layout.endsWith("barepage.jsp")) {
                continue;
            }
            if (!jsp(layout).contains("<title>")) {
                missing.add(layout);
            }
        }
        assertTrue(missing.isEmpty(), "layouts with no <title>: " + missing);
    }

    /**
     * The page title is the document's h1. It was an h2 on both chrome
     * layouts, so every admin screen started its heading outline at level 2
     * and any real h2 inside the content tile read as a sibling of the title.
     */
    @Test
    void thePageTitleIsTheDocumentHeading() {
        List<String> wrong = new ArrayList<>();
        for (String layout : List.of("tiles/tiles-tabbedpage.jsp", "tiles/tiles-mainmenupage.jsp")) {
            if (!jsp(layout).contains("<h1 class=\"roller-page-title\"")) {
                wrong.add(layout);
            }
        }
        assertTrue(wrong.isEmpty(), "page title is not an <h1> in: " + wrong);
    }

    /** "Skip to content" has nowhere to go without a main landmark. */
    @Test
    void contentColumnsAreMainLandmarks() {
        List<String> missing = new ArrayList<>();
        for (String layout : List.of("tiles/tiles-tabbedpage.jsp", "tiles/tiles-mainmenupage.jsp",
                "tiles/tiles-simplepage.jsp", "tiles/tiles-loginpage.jsp")) {
            String src = jsp(layout);
            if (!src.contains("<main ") || !src.contains("</main>")) {
                missing.add(layout);
            }
        }
        assertTrue(missing.isEmpty(), "layouts with no <main> landmark: " + missing);
    }

    /**
     * The sidebar's link headings were h4s under an h1 page title, skipping
     * two levels.
     */
    @Test
    void mainMenuSidebarDoesNotSkipHeadingLevels() {
        String src = jsp("core/MainMenuSidebar.jsp");
        assertTrue(!src.contains("<h4"),
                "core/MainMenuSidebar.jsp still has <h4> headings under an <h1> page title");
    }

    /** A header cell with no scope is ambiguous to a screen reader's table mode. */
    @Test
    void userEditPermissionTableScopesItsHeaders() {
        String src = jsp("admin/UserEdit.jsp");
        int headers = src.split("<th", -1).length - 1;
        int scoped = src.split("<th scope=\"col\"", -1).length - 1;
        assertTrue(headers == scoped,
                "admin/UserEdit.jsp: " + (headers - scoped) + " of " + headers
                        + " <th> cells lack scope=\"col\"");
    }

    // ---------------------------------------------------------------- Task 16

    /**
     * .subtitle is the one-line orientation sentence under a page title, used
     * on ~15 admin screens -- and styled nowhere, so every one of them
     * rendered it as full-ink body prose competing with the title above.
     */
    @Test
    void theSubtitleRoleIsActuallyStyled() {
        assertTrue(read(STYLES).contains("p.subtitle"),
                "roller.css has no p.subtitle rule, so the class is inert on ~15 screens");
    }

    /**
     * One screen, one primary action -- the design system's button hierarchy.
     * These six forms each had their save sitting at .btn-secondary, level
     * with Cancel, so nothing on the page said which control finished the job.
     */
    @Test
    void eachFormScreenHasExactlyOnePrimaryAction() {
        List<String> wrong = new ArrayList<>();
        for (String screen : List.of("admin/GlobalConfig.jsp", "admin/UserEdit.jsp",
                "core/Profile.jsp", "core/CreateWeblog.jsp", "core/Setup.jsp",
                "core/CreateDatabase.jsp")) {
            int primaries = jsp(screen).split("btn-primary", -1).length - 1;
            if (primaries != 1) {
                wrong.add(screen + " has " + primaries);
            }
        }
        assertTrue(wrong.isEmpty(), "expected exactly one btn-primary per screen: " + wrong);
    }

    /**
     * Rebuild-index and regenerate-renditions run for minutes against the
     * weblog named in a &lt;select&gt; the operator may well have scrolled
     * past. Both confirm, and the confirmation names the weblog.
     */
    @Test
    void theLongMaintenanceOperationsConfirmAndNameTheirWeblog() {
        String src = jsp("admin/Maintenance.jsp");
        List<String> missing = new ArrayList<>();
        for (String key : List.of("maintenance.confirm.index",
                "maintenance.confirm.regenerateRenditions")) {
            if (!src.contains(key)) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), "admin/Maintenance.jsp: unconfirmed long operations: " + missing);
    }

    /**
     * Both layouts declared a footer tile and neither rendered it, so the
     * login and simple pages were the only admin screens with no footer.
     */
    @Test
    void theLoginAndSimpleLayoutsRenderTheirFooter() {
        List<String> missing = new ArrayList<>();
        for (String layout : List.of("tiles/tiles-loginpage.jsp", "tiles/tiles-simplepage.jsp")) {
            if (!jsp(layout).contains("${tile_footer}")) {
                missing.add(layout);
            }
        }
        assertTrue(missing.isEmpty(), "layouts declaring but never including tile_footer: " + missing);
    }

    /**
     * Six ids from a pre-Bootstrap centring scheme, none with a CSS rule for
     * years, still wrapping the content of three layouts.
     */
    @Test
    void noLayoutStillCarriesTheDeadCentringScaffolding() {
        List<String> found = new ArrayList<>();
        for (String layout : TILES_LAYOUTS) {
            String src = jsp(layout);
            if (src.contains("leftcontent") || src.contains("centercontent")
                    || src.contains("rightcontent") || src.contains("id=\"footer\"")) {
                found.add(layout);
            }
        }
        assertTrue(found.isEmpty(), "dead #leftcontent-era scaffolding still in: " + found);
    }

    /** Inline style attributes on admin screens belong in roller.css. */
    @Test
    void theSweptScreensCarryNoInlineStyleAttributes() {
        List<String> found = new ArrayList<>();
        for (String screen : List.of("tiles/tiles-installpage.jsp", "core/Setup.jsp",
                "core/CreateWeblog.jsp")) {
            if (jsp(screen).contains("style=\"")) {
                found.add(screen);
            }
        }
        assertTrue(found.isEmpty(), "inline style= attributes remain in: " + found);
    }

    /**
     * form-vertical is a Bootstrap 3 class that does nothing in Bootstrap 5.
     * .form-stacked is this repo's real labels-above conversion.
     */
    @Test
    void noAdminFormRidesTheDeadBootstrap3FormClass() {
        List<String> found = new ArrayList<>();
        for (String screen : List.of("admin/Maintenance.jsp", "admin/UserAdmin.jsp")) {
            if (jsp(screen).contains("form-vertical")) {
                found.add(screen);
            }
        }
        assertTrue(found.isEmpty(), "dead form-vertical still in: " + found);
    }
}
