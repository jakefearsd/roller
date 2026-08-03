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

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.executeJavaScript;

/**
 * The editor's preview pane, driven the way an author drives it.
 *
 * <p>This exists because the preview shipped broken and nothing noticed: the
 * endpoint's {@code @RequestParam}s were unnamed, the build does not pass
 * {@code -parameters}, and so every real request threw while the unit tests --
 * which call the controller method directly, with arguments already bound --
 * carried on passing. {@code ControllerMetadataTest} now fails on an unnamed
 * parameter; this fails if the preview stops rendering for any other reason.
 *
 * <p>It also pins the thing that makes a server-rendered preview worth having:
 * shortcodes expand here exactly as they will on the published page, which no
 * in-browser Markdown library could do.
 */
class MarkdownPreviewIT extends RollerIT {

    private static final String ENTRY_ADD = "/roller-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;

    private static final String EDITOR_BODY = ".CodeMirror";

    /** EasyMDE's own preview toggle, and the pane it reveals. */
    private static final String PREVIEW_BUTTON = "button.preview";
    private static final String PREVIEW_PANE = ".editor-preview";

    @BeforeEach
    void logIn() {
        loginAsAdmin();
    }

    @Test
    void thePreviewRendersMarkdownThroughTheServer() {
        String suffix = Long.toString(System.nanoTime(), 36);

        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("input[name='bean.title']").setValue("IT Preview " + suffix);
        $(EDITOR_BODY).should(visible);
        executeJavaScript("rollerSetEntryText(arguments[0]);",
                "## Heading " + suffix + "\n\nA **bold** word.\n");

        $(PREVIEW_BUTTON).should(visible).click();

        // Markdown became HTML, which only the server does.
        $(PREVIEW_PANE).shouldBe(visible).shouldHave(text("Heading " + suffix));
        $(PREVIEW_PANE + " h2").should(exist);
        $(PREVIEW_PANE + " strong").shouldHave(text("bold"));
    }
}
