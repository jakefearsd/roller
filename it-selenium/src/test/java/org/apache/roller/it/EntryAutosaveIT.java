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

import java.net.URI;
import java.time.Duration;

import org.apache.roller.it.support.BrowserHealth;
import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.codeborne.selenide.Selenide;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local draft recovery, end to end, in a real browser.
 *
 * <p>{@code roller-draft.js} (unit-tested for its JSP wiring already) is
 * exercised here the way an author actually meets it: type something,
 * abandon the page, come back. Four cases: a snapshot is offered back, a
 * properly saved draft offers nothing (the content-comparison rule, not a
 * timestamp), Discard removes the offer for good, and the page editor
 * recovers the same way the entry editor does.
 */
class EntryAutosaveIT extends RollerIT {

    private static final String ENTRY_ADD = "/roller-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String PAGE_ADD = "/roller-ui/authoring/pageEdit.rol?weblog=" + WEBLOG_HANDLE;

    private static final String EDITOR_BODY = ".CodeMirror";
    private static final String DRAFT_BAR = "#draftRecoveryBar";
    private static final String DRAFT_RESTORE = ".draft-bar-restore";
    private static final String DRAFT_DISCARD = ".draft-bar-discard";

    private static final String DRAFT_PREFIX = "roller.draft.v1:";

    /**
     * The debounce inside roller-draft.js is 2 seconds; this is how long a
     * wait for the resulting localStorage write is allowed to take on a
     * loaded CI runner before the test gives up and reports "no snapshot was
     * ever written" rather than a mysteriously missing recovery bar.
     */
    private static final Duration SNAPSHOT_TIMEOUT = Duration.ofSeconds(10);

    private String contextPath;

    @BeforeEach
    void logInAndClearDrafts() {
        loginAsAdmin();
        contextPath = URI.create(baseUrl()).getPath();
        // localStorage survives across tests that share a browser session
        // (and, more importantly, this suite runs its whole class in one
        // JVM against a shared instance), so a snapshot left behind by one
        // test -- or by a previous run against the same profile -- can be
        // offered to the next test and make it pass or fail for the wrong
        // reason. Clear this module's own keys once there is an origin to
        // operate on (right after login), so every test here starts from an
        // empty draft store.
        clearStoredDrafts();
    }

    @Test
    void typingThenReloadingOffersTheTextBack() {
        String suffix = nonce();
        String body = "A paragraph nobody saved yet " + suffix;

        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("input[name='bean.title']").setValue("IT Autosave " + suffix);
        $(EDITOR_BODY).should(visible);
        executeJavaScript("rollerSetEntryText(arguments[0]);", body);

        waitForDraftSnapshot(entryDraftKey("entryAdd", "new"));
        reloadWithoutLeaveWarning();

        $("#entry").should(exist);
        $(DRAFT_BAR).shouldBe(visible);
        $(DRAFT_RESTORE).click();

        String recovered = executeJavaScript("return rollerGetEntryText();");
        assertTrue(recovered != null && recovered.contains(body),
                "restore did not bring the unsaved text back; the editor holds: " + recovered);
    }

    @Test
    void savingThenReloadingOffersNothing() {
        String suffix = nonce();
        String body = "A paragraph that gets saved properly " + suffix;

        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("input[name='bean.title']").setValue("IT Autosave Saved " + suffix);
        $(EDITOR_BODY).should(visible);
        executeJavaScript("rollerSetEntryText(arguments[0]);", body);

        String newDraftKey = entryDraftKey("entryAdd", "new");
        waitForDraftSnapshot(newDraftKey);

        // A real save: the submit handler flushes the pending snapshot and
        // the page lands on entryEdit!firstSave with a real bean.id.
        $("button[formaction$='entryAdd!saveDraft.rol']").click();
        $("input[name='bean.id']").should(exist);

        // This is the stale-detection rule: the entryAdd:new snapshot is
        // still sitting in storage (nothing has cleared it yet), but its
        // content now matches exactly what the server just rendered. If the
        // comparison ever regressed from content to a timestamp, this
        // snapshot -- saved seconds ago -- would look "recent" and get
        // offered anyway.
        reloadWithoutLeaveWarning();

        $("#entry").should(exist);
        $(DRAFT_BAR).shouldNotBe(visible);
        assertTrue(!storageHasKey(newDraftKey),
                "a snapshot matching the saved content must not survive in storage");
    }

    @Test
    void discardingRemovesTheOfferForGood() {
        String suffix = nonce();
        String body = "A paragraph nobody wants recovered " + suffix;

        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("input[name='bean.title']").setValue("IT Autosave Discard " + suffix);
        $(EDITOR_BODY).should(visible);
        executeJavaScript("rollerSetEntryText(arguments[0]);", body);

        waitForDraftSnapshot(entryDraftKey("entryAdd", "new"));
        reloadWithoutLeaveWarning();

        $("#entry").should(exist);
        $(DRAFT_BAR).shouldBe(visible);
        $(DRAFT_DISCARD).click();
        $(DRAFT_BAR).shouldNotBe(visible);

        reloadWithoutLeaveWarning();
        $("#entry").should(exist);
        $(DRAFT_BAR).shouldNotBe(visible);
    }

    @Test
    void thePageEditorRecoversTheSameWay() {
        String suffix = nonce();
        String body = "A page paragraph nobody saved yet " + suffix;

        openPath(PAGE_ADD);
        $("#pageEditForm").should(exist);
        $(EDITOR_BODY).should(visible);
        executeJavaScript("rollerSetEntryText(arguments[0]);", body);

        waitForDraftSnapshot(pageDraftKey("new"));
        reloadWithoutLeaveWarning();

        $("#pageEditForm").should(exist);
        $(DRAFT_BAR).shouldBe(visible);
        $(DRAFT_RESTORE).click();

        String recovered = executeJavaScript("return rollerGetEntryText();");
        assertTrue(recovered != null && recovered.contains(body),
                "restore did not bring the unsaved page text back; the editor holds: " + recovered);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Reloads the current page after silencing the editor's own
     * {@code beforeunload} leave-warning.
     *
     * <p>Typing anything installs that handler, and an unhandled Chrome
     * beforeunload dialog blocks the whole WebDriver session -- every
     * command after it fails, not just this one navigation. Clearing it
     * here tests draft recovery rather than the leave-warning, which has no
     * coverage today and gains none here.
     */
    private void reloadWithoutLeaveWarning() {
        executeJavaScript(
                "window.onbeforeunload = null;"
                + "if (window.jQuery) { jQuery(window).off('beforeunload'); }");
        Selenide.refresh();
        BrowserHealth.current().settle();
    }

    /** Waits, on the actual condition, for the debounced snapshot to land in storage. */
    private void waitForDraftSnapshot(String key) {
        Selenide.Wait().withTimeout(SNAPSHOT_TIMEOUT)
                .withMessage(() -> "no draft snapshot was ever written for key " + key)
                .until(d -> storageHasKey(key));
    }

    /** Whether a storage key currently holds anything. */
    private boolean storageHasKey(String key) {
        return Boolean.TRUE.equals(executeJavaScript(
                "return window.localStorage.getItem(arguments[0]) !== null;", key));
    }

    private void clearStoredDrafts() {
        executeJavaScript(
                "var prefix = arguments[0];"
                + "var doomed = [];"
                + "for (var i = 0; i < window.localStorage.length; i++) {"
                + "  var k = window.localStorage.key(i);"
                + "  if (k && k.indexOf(prefix) === 0) { doomed.push(k); }"
                + "}"
                + "for (var j = 0; j < doomed.length; j++) { window.localStorage.removeItem(doomed[j]); }",
                DRAFT_PREFIX);
    }

    private String entryDraftKey(String action, String id) {
        return DRAFT_PREFIX + contextPath + ":" + WEBLOG_HANDLE + ":" + action + ":" + id;
    }

    private String pageDraftKey(String id) {
        return DRAFT_PREFIX + contextPath + ":" + WEBLOG_HANDLE + ":pageEdit:" + id;
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
