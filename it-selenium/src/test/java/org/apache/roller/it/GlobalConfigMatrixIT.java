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

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;

import com.codeborne.selenide.CollectionCondition;
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
 * Site-wide switches on the Admin Settings page, each proved by the behaviour
 * it is supposed to change.
 *
 * <p>Thirteen runtime properties have real branches in the code and exactly one
 * ({@code uploads.enabled}) had ever been moved from its default by a browser
 * test — and only ever in the direction that turns a feature on. Two of the
 * switches covered here could not be reached at all until they were promoted
 * from startup-only to runtime-settable.
 *
 * <p><b>This is the class that mutates global state</b>, deliberately kept to
 * one. Every flag is restored in a {@code finally}, because the suite shares a
 * single running instance and whatever is left switched stays switched for
 * every test that runs afterwards. Keeping the global permutations here is also
 * what would let the rest of the suite run in parallel one day: everything in
 * {@code WeblogConfigMatrixIT} and {@code ThemeMatrixIT} is per-weblog and safe.
 */
class GlobalConfigMatrixIT extends RollerIT {

    private static final String EDITOR_BODY = ".CodeMirror";
    private static final String PERMALINK = "#entry_bean_permalink";

    /**
     * Three features switched off site-wide, each refused on its own page.
     *
     * <p>Batched into one settings save rather than three tests: the flags do
     * not interact, they are asserted on three different pages, and a test that
     * spends three settings round trips to prove three independent refusals is
     * mostly measuring the settings page.
     *
     * <p>The uploads check has to attempt an actual upload — the refusal lives
     * in the save handler, so the media page renders perfectly well with
     * uploads off.
     */
    @Test
    void switchingFeaturesOffSiteWideRefusesThemWhereTheyAreUsed() {
        loginAsAdmin();
        Map<String, Boolean> before = setGlobalFlags(Map.of(
                "uploads.enabled", false,
                "site.allowUserWeblogCreation", false,
                "groupblogging.enabled", false));
        try {
            openPath("/roller-ui/authoring/mediaFileAdd.rol?weblog=" + WEBLOG_HANDLE);
            $("#fileControl0").should(exist).uploadFile(testImage());
            $("#uploadButton").click();
            $("#errors").should(visible);

            openPath("/roller-ui/createWeblog.rol");
            $("#errors").should(visible);
            $$("#handle").shouldHave(CollectionCondition.size(0));

            openPath("/roller-ui/authoring/invite.rol?weblog=" + WEBLOG_HANDLE);
            $("#errors").should(visible);
            $$("input[name='userName']").shouldHave(CollectionCondition.size(0));
        } finally {
            setGlobalFlags(before);
            logout();
        }
    }

    /**
     * Comments switched off for the whole site are refused at the servlet, not
     * only hidden by the theme.
     *
     * <p>Same shape as the per-weblog switch in {@code WeblogConfigMatrixIT},
     * and deliberately so: this is a different lever reaching the same gate, and
     * an operator turning comments off everywhere has to be able to rely on it.
     * The baseline post goes through the same synthetic form as the refused one,
     * so a refusal cannot be the posting mechanism quietly failing.
     */
    @Test
    void switchingCommentsOffSiteWideRefusesThemAtTheServlet() {
        String suffix = nonce();
        String welcome = "Site comments open " + suffix;
        String refused = "Site comments closed " + suffix;

        loginAsAdmin();
        String handle = createWeblog();
        String permalink = publishEntry(handle, "Site comments " + suffix);

        postCommentDirectly(permalink, welcome);
        assertTrue(getAnonymously(permalink).contains(welcome),
                "a comment must land while the site allows them -- otherwise the "
                        + "refusal below proves nothing");

        boolean before = setGlobalFlag("users.comments.enabled", false);
        try {
            Selenide.open(permalink);
            BrowserHealth.current().settle();
            $$("form[name='commentForm']").shouldHave(CollectionCondition.size(0));

            postCommentDirectly(permalink, refused);
            assertFalse(getAnonymously(permalink).contains(refused),
                    "a comment posted straight to the servlet must be refused when the "
                            + "site has comments switched off");
        } finally {
            setGlobalFlag("users.comments.enabled", before);
            logout();
        }
    }

    /**
     * Site-wide moderation holds comments on a weblog that does not moderate.
     *
     * <p>{@code Weblog.getCommentModerationRequired()} is the OR of the
     * per-weblog setting and this one, so the site switch has to be able to
     * impose moderation on a weblog whose own box is unticked. That is the whole
     * point of having it: an operator dealing with a spam wave cannot go round
     * every weblog.
     */
    @Test
    void siteWideModerationHoldsCommentsOnAWeblogThatDoesNotModerate() {
        String suffix = nonce();
        String comment = "Held by the site switch " + suffix;

        loginAsAdmin();
        String handle = createWeblog();
        String permalink = publishEntry(handle, "Site moderation " + suffix);

        // Established before the site switch goes on, because the settings page
        // withdraws this control once it is -- see below.
        String settings = "/roller-ui/authoring/weblogConfig.rol?weblog=" + handle;
        openPath(settings);
        $("input[name='bean.moderateComments']").shouldNotBe(checked);

        boolean before = setGlobalFlag("users.moderation.required", true);
        try {
            // With the site imposing moderation the per-weblog box is gone
            // rather than ticked: it is no longer this weblog's decision, and
            // showing an unticked box next to comments that are being held
            // would be a lie.
            openPath(settings);
            $$("input[name='bean.moderateComments']").shouldHave(CollectionCondition.size(0));

            postCommentDirectly(permalink, comment);
            assertFalse(getAnonymously(permalink).contains(comment),
                    "the site moderation switch must hold a comment even on a weblog "
                            + "that does not moderate on its own");
        } finally {
            setGlobalFlag("users.moderation.required", before);
            logout();
        }
    }

    /**
     * The word separator for new entry URLs is a site setting, and it applies to
     * anchors generated from then on.
     *
     * <p>Only reachable at all since this property was promoted from
     * startup-only: before that, seeing the other branch meant restarting the
     * server with a different {@code roller.properties}. It was also a
     * {@code static final} read once at class load, so even a restart-free
     * change would have been ignored.
     *
     * <p>The entry published <em>before</em> the switch keeps its hyphens, which
     * is what stops a change of setting from breaking every existing permalink.
     */
    @Test
    void theEntryUrlSeparatorAppliesToNewEntriesOnly() {
        String suffix = nonce();

        loginAsAdmin();
        String handle = createWeblog();
        String hyphenated = publishEntry(handle, "Hyphen separated title " + suffix);
        assertTrue(hyphenated.contains("hyphen-separated-title"),
                "the shipped default joins words with hyphens, got: " + hyphenated);

        boolean before = setGlobalFlag("weblogentry.title.useUnderscoreSeparator", true);
        try {
            String underscored = publishEntry(handle, "Underscore separated title " + suffix);
            assertTrue(underscored.contains("underscore_separated_title"),
                    "an entry created after the switch must join words with underscores, got: "
                            + underscored);

            assertTrue(getAnonymously(hyphenated).contains("Hyphen separated title"),
                    "and the entry published beforehand must still be served at its "
                            + "original hyphenated permalink");
        } finally {
            setGlobalFlag("weblogentry.title.useUnderscoreSeparator", before);
            logout();
        }
    }

    /**
     * Whether HTML in a comment is markup or text is a site setting.
     *
     * <p>It ships off, which means a comment's angle brackets reach the page
     * escaped. Turning it on is an explicit decision to let readers post markup,
     * and the difference has to be visible in what a reader receives — this is
     * the switch between "a comment can contain a link" and "a comment can
     * contain a tag".
     */
    @Test
    void theCommentHtmlSwitchDecidesWhetherMarkupIsEscaped() {
        String suffix = nonce();
        String escaped = "Plain " + suffix + " <b>not bold</b>";
        String allowed = "Rich " + suffix + " <b>bold</b>";

        loginAsAdmin();
        String handle = createWeblog();
        String permalink = publishEntry(handle, "Comment html " + suffix);

        postCommentDirectly(permalink, escaped);
        assertTrue(getAnonymously(permalink).contains("&lt;b&gt;not bold&lt;/b&gt;"),
                "with the site default, a comment's markup must reach the page escaped");

        boolean before = setGlobalFlag("users.comments.htmlenabled", true);
        try {
            postCommentDirectly(permalink, allowed);
            assertTrue(getAnonymously(permalink).contains("<b>bold</b>"),
                    "with HTML comments enabled, the same markup must reach the page as markup");
        } finally {
            setGlobalFlag("users.comments.htmlenabled", before);
            logout();
        }
    }

    // ---------------------------------------------------------------- fixture

    /** Creates a weblog owned by the seeded admin, on the default theme. */
    private String createWeblog() {
        String handle = "gcfg" + nonce();

        openPath("/roller-ui/createWeblog.rol");
        $("#name").should(visible).setValue("Global Config " + handle);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue("basic");
        $("button[type='submit']").click();

        $("#messages").should(exist);
        return handle;
    }

    /** Publishes one entry and returns its permalink. */
    private String publishEntry(String handle, String title) {
        openPath("/roller-ui/authoring/entryAdd.rol?weblog=" + handle);
        $("#entry").should(exist);
        $("input[name='bean.title']").setValue(title);
        $(EDITOR_BODY).should(visible);
        executeJavaScript("rollerSetEntryText(arguments[0]);", "Body of " + title);
        $("button[formaction$='entryAdd!publish.rol']").click();

        $(PERMALINK).should(exist);
        String permalink = $(PERMALINK).getAttribute("href");
        assertNotNull(permalink, "publishing must expose the entry's permalink");
        return permalink;
    }

    /**
     * Posts a comment by building the form and submitting it, bypassing whatever
     * the theme chose to render.
     *
     * <p>Comment POSTs are exempt from CSRF by design (see {@code SecurityConfig}
     * — an anonymous reader has no ambient authority to protect), so no token is
     * needed, and that exemption is exactly why the servlet's own checks are the
     * ones worth testing. The browser is signed in, which satisfies the weblog's
     * default requirement that commenters be authenticated.
     */
    private void postCommentDirectly(String permalink, String body) {
        Selenide.open(permalink);
        BrowserHealth.current().settle();

        executeJavaScript(
                "var f = document.createElement('form');"
                        + "f.method = 'POST'; f.action = arguments[0];"
                        + "function add(n, v) {"
                        + "  var i = document.createElement('input');"
                        + "  i.type = 'hidden'; i.name = n; i.value = v; f.appendChild(i); }"
                        + "add('method', 'post');"
                        + "add('name', 'Direct Poster');"
                        + "add('email', 'direct@example.invalid');"
                        + "add('url', '');"
                        + "add('content', arguments[1]);"
                        + "document.body.appendChild(f); f.submit();",
                permalink, body);
        BrowserHealth.current().settle();
    }

    private static File testImage() {
        URL resource = GlobalConfigMatrixIT.class.getResource("/hawk.jpg");
        assertNotNull(resource, "hawk.jpg must be on the it-selenium test classpath");
        try {
            return new File(resource.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot resolve test image", e);
        }
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
