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
}
