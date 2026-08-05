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
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comments, from a reader posting one to a moderator deleting it.
 *
 * <p>Comments had no browser coverage at all, which is a strange gap for the
 * one feature that lets a stranger write to the database. The path is also
 * unusual in this application: a POST handled by a servlet rather than a
 * controller, landing on a page which is otherwise aggressively cached.
 *
 * <p>Two things are worth holding here. Signed-in-only commenting is the
 * default and has to be enforced by the server, not merely by hiding the form
 * -- a hidden form stops nobody who posts directly. And a comment the moderator
 * deletes has to actually leave the public page: that is the whole point of
 * deleting it, and it is where the old spam flag failed silently.
 */
class CommentIT extends RollerIT {

    private static final String ENTRY_ADD = "/roller-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String COMMENTS = "/roller-ui/authoring/comments.rol?weblog=" + WEBLOG_HANDLE;
    private static final String SETTINGS = "/roller-ui/authoring/weblogConfig.rol?weblog=" + WEBLOG_HANDLE;

    private static final String EDITOR_BODY = ".CodeMirror";
    private static final String PERMALINK = "#entry_bean_permalink";
    private static final String REQUIRE_SIGN_IN = "#bean_requireAuthenticatedComments";

    /**
     * The default: an anonymous reader gets an invitation to sign in, and a
     * direct POST is refused anyway.
     */
    @Test
    void anAnonymousReaderIsAskedToSignInAndCannotPostRegardless() {
        String suffix = nonce();

        loginAsAdmin();
        String permalink = publishEntry("IT Comment gate " + suffix, "Body " + suffix);
        logout();

        Selenide.open(permalink);
        BrowserHealth.current().settle();

        assertTrue($$("form[name='commentForm']").isEmpty(),
                "an anonymous reader must not be shown a comment form on a weblog that "
                        + "requires a sign-in");
        // The way in, not a dead end.
        $(".comments-form").shouldHave(text("sign in"));
    }

    /**
     * A signed-in reader can comment, and the comment reaches the public page.
     */
    @Test
    void aSignedInReaderCanPostACommentAndSeeItAppear() {
        String suffix = nonce();
        String commentBody = "A perfectly ordinary remark " + suffix;

        loginAsAdmin();
        String permalink = publishEntry("IT Comment " + suffix, "Body " + suffix);

        Selenide.open(permalink);
        BrowserHealth.current().settle();
        postComment(commentBody);

        // The seeded weblog does not moderate, so it should be public at once.
        assertTrue(getAnonymously(permalink).contains(commentBody),
                "a signed-in reader's comment never appeared on the entry it was posted to");
        logout();
    }

    /**
     * Deleting a comment takes it off the public page.
     *
     * <p>This is the assertion the old spam flag could not pass. The moderation
     * screen pre-ticks "approved" for every approved comment, so ticking a
     * second box submitted both, and the approve branch was tested first --
     * marking a comment as spam re-saved it as APPROVED and it stayed up. The
     * page is also cached per weblog, so this covers the invalidation too.
     */
    @Test
    void aDeletedCommentLeavesThePublicPage() {
        String suffix = nonce();
        String commentBody = "Delete me " + suffix;

        loginAsAdmin();
        String permalink = publishEntry("IT Comment delete " + suffix, "Body " + suffix);

        Selenide.open(permalink);
        BrowserHealth.current().settle();
        postComment(commentBody);
        assertTrue(getAnonymously(permalink).contains(commentBody),
                "the comment must be public before the delete means anything");

        // Moderate it away. Nothing is unticked: the approved box stays as the
        // page rendered it, which is exactly the condition the old bug needed.
        // Filter to this comment: the seeded weblog is shared, and the
        // moderation list pages at 30 rows.
        openPath(COMMENTS + "&bean.searchString=" + suffix);
        $("input[name='bean.deleteComments'][value]").should(exist);
        String commentId = commentIdFor(commentBody);
        $("input[name='bean.deleteComments'][value='" + commentId + "']").click();
        $("input[type='submit'].btn-primary").click();
        BrowserHealth.current().settle();

        assertFalse(getAnonymously(permalink).contains(commentBody),
                "a deleted comment is still being served to readers");
        logout();
    }

    /**
     * The setting governs both halves. With it off the anonymous form comes
     * back and an anonymous post is accepted -- which is also the proof that
     * the refusal above came from the setting rather than from something else
     * being broken about anonymous commenting.
     */
    @Test
    void turningTheRequirementOffLetsAnAnonymousReaderComment() {
        String suffix = nonce();
        String commentBody = "Anonymous but allowed " + suffix;

        loginAsAdmin();
        String permalink = publishEntry("IT Comment open " + suffix, "Body " + suffix);

        try {
            setRequireSignIn(false);
            logout();

            Selenide.open(permalink);
            BrowserHealth.current().settle();
            $("form[name='commentForm']").should(exist);
            $("input[name='name']").setValue("Casual Reader");
            $("input[name='email']").setValue("reader" + suffix + "@example.invalid");
            $("textarea[name='content']").setValue(commentBody);
            submitCommentForm();

            assertTrue(getAnonymously(permalink).contains(commentBody),
                    "with the sign-in requirement off, an anonymous comment must be accepted");
        } finally {
            // Restore the default; the seeded weblog is shared with every other
            // test in this suite.
            loginAsAdmin();
            setRequireSignIn(true);
            logout();
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Reads the requirement checkbox on the settings page and saves it. */
    private void setRequireSignIn(boolean required) {
        openPath(SETTINGS);
        $(REQUIRE_SIGN_IN).should(visible);
        if ($(REQUIRE_SIGN_IN).isSelected() != required) {
            $(REQUIRE_SIGN_IN).click();
        }
        $("button[type='submit'].btn-success").click();
        $("#messages").should(exist);

        openPath(SETTINGS);
        if (required) {
            $(REQUIRE_SIGN_IN).shouldBe(checked);
        } else {
            $(REQUIRE_SIGN_IN).shouldNotBe(checked);
        }
    }

    /** Posts a comment as whoever is currently signed in. */
    private void postComment(String body) {
        $("form[name='commentForm']").should(exist);
        $("textarea[name='content']").setValue(body);
        submitCommentForm();
    }

    private void submitCommentForm() {
        $("form[name='commentForm'] input[type='submit'], form[name='commentForm'] button[type='submit']")
                .should(visible).click();
        BrowserHealth.current().settle();

        // Whatever the server made of the post, it says so here. Asserting on
        // it first means a rejection reports its own reason instead of
        // surfacing later as "the comment is missing".
        $(".comments-form").shouldNotHave(text("error"));
    }

    /**
     * The moderation row carrying the given comment text, as a comment id.
     *
     * <p>Read off the delete checkbox of the row whose body matches, rather
     * than assuming a position: the seeded weblog accumulates comments from
     * whichever tests ran before this one.
     */
    private String commentIdFor(String body) {
        String id = executeJavaScript(
                "const cells = document.querySelectorAll(\"span[id^='comment-']\");"
                        + "for (const cell of cells) {"
                        + "  if (cell.textContent.includes(arguments[0])) {"
                        + "    return cell.id.substring('comment-'.length);"
                        + "  }"
                        + "}"
                        + "return null;",
                body);
        assertNotNull(id, "the comment must be listed on the moderation page before it "
                + "can be deleted from it");
        return id;
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

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
