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

import com.codeborne.selenide.Selenide;
import org.apache.roller.it.support.BrowserHealth;
import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.Condition.checked;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comments, from a reader posting one to a moderator hiding it.
 *
 * <p>Comments had no browser coverage at all, which is a strange gap for the
 * one feature that lets a stranger write to the database. The path is also
 * unusual in this application: an anonymous POST, handled by a servlet rather
 * than a controller, that then has to appear on a page which is otherwise
 * aggressively cached.
 *
 * <p>Both halves matter. A comment that never appears is a broken feature; a
 * comment marked as spam that keeps appearing is the moderator's only tool not
 * working, and with no spam filtering in this build (see the user guide)
 * moderation is the whole defence.
 */
@org.junit.jupiter.api.Disabled("Blocked on a decision, not on this test: posting a "
        + "comment returns 403. The comment form is rendered by a Velocity theme macro "
        + "and carries no CSRF token, and Spring Security protects every POST -- so "
        + "anonymous commenting cannot work at all. A token cannot simply be embedded, "
        + "because weblog pages are cached and the token is per-session. The options are "
        + "to exempt the public comment POST from CSRF (standard for anonymous comment "
        + "forms, which carry no ambient authority) or to fetch a token separately. "
        + "Enable this the moment that is settled -- it passes up to the submit.")
class CommentIT extends RollerIT {

    private static final String ENTRY_ADD = "/roller-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String COMMENTS = "/roller-ui/authoring/comments.rol?weblog=" + WEBLOG_HANDLE;

    private static final String EDITOR_BODY = ".CodeMirror";
    private static final String PERMALINK = "#entry_bean_permalink";

    @Test
    void aReaderCanCommentAndAModeratorCanHideIt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String commentBody = "A perfectly ordinary remark " + suffix;

        loginAsAdmin();
        String permalink = publishEntry("IT Comment " + suffix, "Body " + suffix);
        logout();

        // --- an anonymous reader comments -----------------------------------
        Selenide.open(permalink);
        BrowserHealth.current().settle();
        $("form[name='commentForm']").should(exist);
        $("input[name='name']").setValue("Casual Reader");
        $("input[name='email']").setValue("reader" + suffix + "@example.invalid");
        $("textarea[name='content']").setValue(commentBody);
        $("form[name='commentForm'] input[type='submit'], form[name='commentForm'] button[type='submit']")
                .should(visible).click();
        BrowserHealth.current().settle();

        // Whatever the server made of the post, it says so here. Asserting on
        // it first means a rejection reports its own reason instead of
        // surfacing later as "the comment is missing".
        $(".comments-form").shouldNotHave(text("error"));

        // The seeded weblog does not moderate, so it should be public at once.
        assertTrue(pageContains(permalink, commentBody),
                "a reader's comment never appeared on the entry it was posted to");

        // --- the moderator marks it as spam ---------------------------------
        loginAsAdmin();
        openPath(COMMENTS);
        $("input[name='bean.spamComments']").should(exist).click();
        $("input[type='submit'].btn-primary").click();
        $("#messages").should(exist);
        logout();

        // --- and it is gone from the public page -----------------------------
        assertFalse(pageContains(permalink, commentBody),
                "a comment marked as spam is still shown to readers; moderation is the "
                        + "only defence this build has");
    }

    // ---------------------------------------------------------------- helpers

    /** Fetches with no session at all, i.e. what a reader actually sees. */
    private boolean pageContains(String url, String text) {
        return getAnonymously(url).contains(text);
    }

    /** Publishes an entry and returns its public permalink. */
    private String publishEntry(String title, String body) {
        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("input[name='bean.title']").setValue(title);
        $(EDITOR_BODY).should(visible);
        executeJavaScript("rollerSetEntryText(arguments[0]);", body);

        // The allow-comments checkbox is the whole reason this test exists: it
        // was missing from the editor, so the field defaulted to false on every
        // save and no entry written here could be commented on. It should
        // arrive already ticked, inherited from the weblog's default -- assert
        // that rather than clicking it, since clicking would turn it off.
        $("a[href='#collapseAdvanced']").should(visible).click();
        $("#entry_bean_allowComments").shouldBe(visible).shouldBe(checked);

        $("button[formaction$='entryAdd!publish.rol']").click();

        $(PERMALINK).should(exist);
        String permalink = $(PERMALINK).getAttribute("href");
        assertNotNull(permalink, "a published entry must expose its permalink");
        return permalink;
    }
}
