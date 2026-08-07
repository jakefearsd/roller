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

import com.codeborne.selenide.CollectionCondition;

import static com.codeborne.selenide.Condition.checked;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.value;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-weblog settings, each proved by what a reader gets rather than by the
 * checkbox staying ticked.
 *
 * <p>Weblog Settings has around twenty fields and exactly one of them
 * ({@code requireAuthenticatedComments}, in {@code CommentIT}) was ever moved
 * from its default. A setting that persists but changes nothing is the failure
 * mode this whole page is prone to — the checkbox comes back ticked either way,
 * so only the public side can tell you it worked.
 *
 * <p><b>Every test owns its own weblog</b> and touches no site-wide
 * configuration. That keeps them independent of each other and of the rest of
 * the suite, and it is what would let this class run in parallel if the suite
 * ever does: the global-flag permutations live in a separate class precisely so
 * they can be serialised on their own.
 */
class WeblogConfigMatrixIT extends RollerIT {

    /** The editor's editable surface; text goes in through the page's own seam. */
    private static final String EDITOR_BODY = ".CodeMirror";

    /** Rendered on the edit page only once the entry is actually published. */
    private static final String PERMALINK = "#entry_bean_permalink";

    /**
     * A comment held for moderation must not reach the public page, and must
     * arrive there once approved.
     *
     * <p>Moderation is the only gate this fork has left — marking a comment as
     * spam now means deleting it — so "held" has to mean genuinely invisible,
     * not merely flagged. Checked anonymously: the author is signed in and can
     * see their own weblog either way.
     */
    @Test
    void aModeratedWeblogHoldsACommentUntilItIsApproved() {
        String suffix = nonce();
        String comment = "Held for moderation " + suffix;

        loginAsAdmin();
        String handle = createWeblog();
        String permalink = publishEntry(handle, "Moderated " + suffix);

        setCheckbox(handle, "bean.moderateComments", true);

        postCommentAsSignedInAuthor(permalink, comment);
        assertFalse(getAnonymously(permalink).contains(comment),
                "a comment awaiting moderation must not be on the public page");

        approveComment(handle, comment);
        assertTrue(getAnonymously(permalink).contains(comment),
                "an approved comment must reach the public page");
        logout();
    }

    /**
     * Turning comments off at the weblog closes the door, not just the form.
     *
     * <p>The theme stops rendering the form, which is what an honest reader
     * sees — but a form nobody renders stops nobody who posts directly, and
     * comment POSTs are deliberately exempt from CSRF (an anonymous reader has
     * no session to protect). So this posts the way a spam script would: it
     * rebuilds the form in the page and submits it. The servlet is the only
     * check that counts.
     */
    @Test
    void switchingCommentsOffAtTheWeblogRefusesThemAtTheServletToo() {
        String suffix = nonce();
        String welcome = "Posted while open " + suffix;
        String refused = "Posted while closed " + suffix;

        loginAsAdmin();
        String handle = createWeblog();
        String permalink = publishEntry(handle, "Comments off " + suffix);

        // Baseline through the SAME back door the refusal is tested with. If
        // the synthetic post were silently going nowhere -- a typo in the
        // action, a form the browser never submits -- the refusal below would
        // pass for that reason instead of the setting. Proving the mechanism
        // works while comments are open is what makes its failure afterwards
        // mean something.
        postCommentDirectly(permalink, welcome);
        assertTrue(getAnonymously(permalink).contains(welcome),
                "a directly-posted comment must land while comments are open -- "
                        + "otherwise the refusal below proves nothing");

        setCheckbox(handle, "bean.allowComments", false);

        Selenide.open(permalink);
        BrowserHealth.current().settle();
        $$("form[name='commentForm']").shouldHave(CollectionCondition.size(0));

        postCommentDirectly(permalink, refused);
        assertFalse(getAnonymously(permalink).contains(refused),
                "a comment posted straight to the servlet must be refused once the "
                        + "weblog has comments switched off");
        assertTrue(getAnonymously(permalink).contains(welcome),
                "and the comment posted while they were open must survive");
        logout();
    }

    /**
     * The weblog's locale reaches the rendered page.
     *
     * <p>Asserted on the theme's own message lookups rather than on dates: the
     * day template resolves {@code macro.weblog.comments} through the bundle for
     * the weblog's locale, so a French weblog says "Commentaires". A date would
     * work too but drags in month-name and timezone-boundary flakiness for no
     * extra proof.
     */
    @Test
    void theWeblogsLocaleDecidesTheLanguageOfTheRenderedPage() {
        String suffix = nonce();

        loginAsAdmin();
        String handle = createWeblog();
        publishEntry(handle, "Localised " + suffix);

        // Anchored on the day template's own comments link. The page carries
        // other, hardcoded English ("Recent Comments (Atom)" in
        // #showAutodiscoveryLinks), so a bare search for "Comments" would be
        // true whatever the locale.
        String englishLabel = "commentsLink\">Comments";
        String frenchLabel = "commentsLink\">Commentaires";

        String english = readerHome(handle);
        assertTrue(english.contains(englishLabel),
                "the default weblog must render the English bundle, got: " + snippet(english));

        selectOption(handle, "bean.locale", "fr");

        String french = readerHome(handle);
        assertTrue(french.contains(frenchLabel),
                "a French weblog must resolve theme messages through the French bundle, got: "
                        + snippet(french));
        assertFalse(french.contains(englishLabel),
                "and must not still be emitting the English label");
        logout();
    }

    /**
     * {@code entryDisplayCount} decides how many entries a reader gets on one
     * page. Two entries and a limit of one is the smallest arrangement that
     * tells the difference between "the setting works" and "there was only ever
     * one entry".
     */
    @Test
    void theEntryDisplayCountLimitsWhatTheFrontPageShows() {
        String suffix = nonce();
        String older = "Older post " + suffix;
        String newer = "Newer post " + suffix;

        loginAsAdmin();
        String handle = createWeblog();
        publishEntry(handle, older);
        publishEntry(handle, newer);

        String both = readerHome(handle);
        assertTrue(both.contains(older) && both.contains(newer),
                "both entries must be on the front page before the limit is applied");

        setNumber(handle, "bean.entryDisplayCount", 1);

        String limited = readerHome(handle);
        assertTrue(limited.contains(newer),
                "the newest entry must survive the limit, got: " + snippet(limited));
        assertFalse(limited.contains(older),
                "the older entry must fall off a one-entry page, got: " + snippet(limited));
        logout();
    }

    /**
     * Deactivating a weblog takes it out of the site's SEO surface.
     *
     * <p>{@code active} does not hide the weblog's pages — it withdraws it from
     * the listings a crawler is pointed at, which is the only place the flag is
     * observable. Both checks are anonymous fetches, so this costs almost
     * nothing on top of the fixture.
     *
     * <p>Deactivating also forces comments off ({@code WeblogConfigController}),
     * asserted here so that side effect cannot disappear unnoticed.
     *
     * <p>There is deliberately no test for the per-weblog analytics snippet: its
     * textarea renders only when {@code weblogAdminsUntrusted} is off, and this
     * fork keeps it on — a weblog admin cannot inject script into their own page
     * head. The field exists in the bean and in {@code #showAnalyticsTrackingCode},
     * but no supported configuration exposes it.
     */
    @Test
    void deactivatingAWeblogWithdrawsItFromTheSitemaps() {
        String suffix = nonce();

        loginAsAdmin();
        String handle = createWeblog();
        publishEntry(handle, "Sitemapped " + suffix);

        String perWeblogSitemap = baseUrl() + "/sitemap-" + handle + ".xml";
        assertTrue(getAnonymously(baseUrl() + "/sitemap.xml").contains("sitemap-" + handle + ".xml"),
                "an active weblog must be listed in the sitemap index");
        assertEquals(200, statusAnonymously(perWeblogSitemap),
                "and must serve its own sitemap");

        setCheckbox(handle, "bean.active", false);

        assertFalse(getAnonymously(baseUrl() + "/sitemap.xml").contains("sitemap-" + handle + ".xml"),
                "a deactivated weblog must drop out of the sitemap index");
        assertEquals(404, statusAnonymously(perWeblogSitemap),
                "and its own sitemap must stop being served");

        openSettings(handle);
        $("input[name='bean.allowComments']").shouldNotBe(checked);
        logout();
    }

    // -------------------------------------------------------- settings edits

    /**
     * Ticks or unticks one checkbox on Weblog Settings and saves.
     *
     * <p>Reads the value back afterwards. An unticked checkbox posts nothing at
     * all, so a form that binds it wrongly still redirects and still reports
     * success — only re-reading the page proves the setting took.
     */
    private void setCheckbox(String handle, String name, boolean value) {
        openSettings(handle);
        if ($("input[name='" + name + "']").isSelected() != value) {
            $("input[name='" + name + "']").click();
        }
        saveSettings();

        openSettings(handle);
        if (value) {
            $("input[name='" + name + "']").shouldBe(checked);
        } else {
            $("input[name='" + name + "']").shouldNotBe(checked);
        }
    }

    private void selectOption(String handle, String name, String optionValue) {
        openSettings(handle);
        $("select[name='" + name + "']").selectOptionByValue(optionValue);
        saveSettings();

        openSettings(handle);
        $("select[name='" + name + "']").shouldHave(value(optionValue));
    }

    private void setNumber(String handle, String name, int number) {
        openSettings(handle);
        $("input[name='" + name + "']").setValue(Integer.toString(number));
        saveSettings();

        openSettings(handle);
        $("input[name='" + name + "']").shouldHave(value(Integer.toString(number)));
    }

    private void setTextArea(String handle, String name, String text) {
        openSettings(handle);
        $("textarea[name='" + name + "']").setValue(text);
        saveSettings();

        openSettings(handle);
        $("textarea[name='" + name + "']").shouldHave(value(text));
    }

    private void openSettings(String handle) {
        openPath("/roller-ui/authoring/weblogConfig.rol?weblog=" + handle);
        $("input[name='bean.name']").should(visible);
    }

    private void saveSettings() {
        $("button[type='submit'].btn-success").should(visible).click();
        $("#messages").should(exist);
        assertTrue($$("#errors").isEmpty(),
                "saving the weblog settings reported an error: "
                        + ($$("#errors").isEmpty() ? "" : $("#errors").getText()));
        BrowserHealth.current().settle();
    }

    // ------------------------------------------------------------- comments

    /**
     * Posts a comment as the signed-in author.
     *
     * <p>A new weblog requires a sign-in to comment, so this uses the session
     * already open rather than turning that requirement off — one fewer setting
     * moved away from its default per test.
     */
    private void postCommentAsSignedInAuthor(String permalink, String body) {
        Selenide.open(permalink);
        BrowserHealth.current().settle();
        $("form[name='commentForm']").should(exist);
        $("textarea[name='content']").setValue(body);
        $("form[name='commentForm'] input[type='submit'], "
                + "form[name='commentForm'] button[type='submit']").should(visible).click();
        BrowserHealth.current().settle();
        $(".comments-form").shouldNotHave(text("error"));
    }

    /**
     * Posts a comment by building the form and submitting it, bypassing
     * whatever the theme chose to render.
     *
     * <p>Comment POSTs are exempt from CSRF by design (see {@code SecurityConfig}
     * — an anonymous reader has no ambient authority to protect), so no token is
     * needed. That exemption is exactly why the servlet's own checks have to
     * hold.
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

    /** Approves the comment carrying the given text, on this weblog's own queue. */
    private void approveComment(String handle, String body) {
        openPath("/roller-ui/authoring/comments.rol?weblog=" + handle);
        $("input[name='bean.approvedComments']").should(exist);

        String commentId = executeJavaScript(
                "var boxes = document.querySelectorAll(\"input[name='bean.approvedComments']\");"
                        + "for (var i = 0; i < boxes.length; i++) {"
                        + "  var row = boxes[i].closest('tr');"
                        + "  if (row && row.textContent.indexOf(arguments[0]) >= 0) {"
                        + "    return boxes[i].value; } }"
                        + "return null;",
                body);
        assertNotNull(commentId, "the held comment must be listed on the moderation page");

        $("input[name='bean.approvedComments'][value='" + commentId + "']").click();
        $("input[type='submit'].btn-primary").should(visible).click();
        $("#messages").should(exist);
        BrowserHealth.current().settle();
    }

    // ---------------------------------------------------------------- weblog

    /** Creates a weblog owned by the seeded admin, on the default theme. */
    private String createWeblog() {
        String handle = "wcfg" + nonce();

        openPath("/roller-ui/createWeblog.rol");
        $("#name").should(visible).setValue("Weblog Config " + handle);
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
     * The weblog home page as an anonymous reader gets it. The trailing slash
     * is load-bearing: the bare handle redirects and the anonymous fetch helper
     * does not follow redirects, so without it every assertion reads an empty
     * body.
     */
    private String readerHome(String handle) {
        return getAnonymously(baseUrl() + "/" + handle + "/");
    }

    /** The status code an anonymous reader gets, which a body alone cannot show. */
    private static int statusAnonymously(String url) {
        try {
            return java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(url))
                            .timeout(java.time.Duration.ofSeconds(20))
                            .GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            throw new IllegalStateException("Could not GET " + url + " anonymously", e);
        }
    }

    private static String snippet(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? "<<" + flat + ">>" : "<<" + flat.substring(0, 300) + "...>>";
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
