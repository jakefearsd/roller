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
package org.apache.roller.it;

import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.CollectionCondition.exactTexts;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's insert menu, in a real browser.
 *
 * <p>{@code ShortcodeCardTest} proves the cards are well-formed and that the
 * JSP generates the menu from the registry rather than hard-coding it. What it
 * cannot prove is the half that only exists once a browser runs the page: that
 * the Bootstrap dropdown opens, that the click handler is bound, and that the
 * snippet survives the trip through the HTML data attribute into the editor
 * with its quotes and newlines intact -- the failure mode that would leave an
 * author with {@code [gallery dir=&quot;default&quot;]} in their post.
 */
class ShortcodeCardIT extends RollerIT {

    private static final String ENTRY_ADD = "/roller-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;

    /** The editor's editable surface; text goes in and out through the page's own seam. */
    private static final String EDITOR_BODY = ".CodeMirror";

    @BeforeEach
    void logIn() {
        loginAsAdmin();
    }

    @Test
    void theInsertMenuOffersEveryShippedShortcode() {
        openEditor();
        $("#shortcodeInsertButton").shouldBe(visible).click();

        // The order is the registry's registration order, which is what an
        // author sees; asserting it exactly means a reordering is a decision
        // rather than an accident.
        $$("#shortcodeInsertMenu .shortcode-card").shouldHave(exactTexts(
                "Image…", "Photo gallery", "Map with pins",
                "Call to action", "Questions and answers", "Video", "Contact form",
                "Subscribe form"));
    }

    @Test
    void insertingAGalleryPutsItsSnippetInTheEditor() {
        openEditor();
        $("#shortcodeInsertButton").shouldBe(visible).click();
        $("#shortcodeInsertMenu .shortcode-card[data-shortcode='gallery']")
                .shouldBe(visible).click();

        String text = executeJavaScript("return rollerGetEntryText();");
        assertTrue(text != null && text.contains("[gallery dir=\"default\" row=\"320\"]"),
                "The gallery snippet did not reach the editor intact; it holds: " + text);
    }

    /**
     * The [faq] snippet is the multi-line one: if newlines were lost crossing
     * the data attribute, its [q]/[a] pairs would collapse onto one line and
     * the block would still parse -- so this asserts the shape, not just the
     * presence.
     */
    @Test
    void aMultiLineSnippetKeepsItsLineBreaks() {
        openEditor();
        $("#shortcodeInsertButton").shouldBe(visible).click();
        $("#shortcodeInsertMenu .shortcode-card[data-shortcode='faq']")
                .shouldBe(visible).click();

        String text = executeJavaScript("return rollerGetEntryText();");
        assertTrue(text != null && text.contains("[faq]\n[q]"),
                "The FAQ snippet lost its line breaks; it holds: " + text);
    }

    private void openEditor() {
        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $(EDITOR_BODY).should(visible);
    }
}
