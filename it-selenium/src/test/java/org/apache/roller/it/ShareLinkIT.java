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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebElement;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The password-protected share-link journey, end to end through real UI.
 *
 * <p>Two journeys:
 * <ol>
 * <li><b>Entry share with a password.</b> Write and publish an entry, create
 * a password-protected share link from the editor's share card, then prove
 * the gate: an anonymous (cookieless) fetch of the share URL gets the
 * password form and none of the entry's content; in the browser a wrong
 * password re-renders the form and the right one shows the entry.</li>
 * <li><b>Private-directory share.</b> Create a directory, upload a photo
 * into it, mark the directory private, share it without a password, and
 * prove both halves of the media contract: the share page's grid serves its
 * image through the token-scoped {@code /share/<token>/media/...} route, and
 * the same file 404s on the base {@code /mediaresource/} path.</li>
 * </ol>
 *
 * <p>The tokened share routes themselves are SKIPPED in the Routes catalogue
 * (they are dynamic secrets, not literal URLs) -- this test is their
 * content-asserting coverage, PublicSurfaceIT-style.
 */
class ShareLinkIT extends RollerIT {

    private static final String ENTRY_ADD = "/roller-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String MEDIA_ADD = "/roller-ui/authoring/mediaFileAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String MEDIA_VIEW = "/roller-ui/authoring/mediaFileView.rol?weblog=" + WEBLOG_HANDLE;
    private static final String GLOBAL_CONFIG = "/roller-ui/admin/globalConfig.rol";

    /** The editor is Summernote, which hides the textarea behind a contenteditable div. */
    private static final String EDITOR_BODY = ".note-editable";

    @BeforeEach
    void logIn() {
        loginAsAdmin();
    }

    @Test
    void aPasswordProtectedEntryShareGatesAnonymousReaders() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String title = "IT Share " + suffix;
        String body = "Secret shared body " + suffix + ".";
        String password = "it-share-pw-" + suffix;

        // --- write and publish the entry ------------------------------------
        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("input[name='bean.title']").setValue(title);
        $(EDITOR_BODY).should(visible);
        executeJavaScript(
                "$('#edit_content').summernote('code', arguments[0]);"
                        + "$('#edit_content').val(arguments[0]);", body);
        $("button[formaction$='entryAdd!publish.rol']").click();
        $("#entry_bean_permalink").should(exist);

        // --- create the password-protected share link -----------------------
        $("#entryShareLinkPassword").should(exist).setValue(password);
        $("#createEntryShareLinkButton").click();
        WebElement urlField = $("#entryShareLinkUrl").should(visible).toWebElement();
        String shareUrl = urlField.getAttribute("value");
        assertNotNull(shareUrl);
        assertTrue(shareUrl.contains("/share/"),
                "the editor must show the tokened share URL, got: " + shareUrl);

        // --- the gate, as a cookieless anonymous reader ---------------------
        String anonymous = getAnonymously(shareUrl);
        assertTrue(anonymous.contains("sharePasswordForm"),
                "an anonymous fetch must get the password form:\n" + anonymous);
        assertFalse(anonymous.contains(suffix),
                "nothing of the gated content may leak past the form:\n" + anonymous);

        // --- wrong password stays locked, right password unlocks ------------
        openPath(pathOf(shareUrl));
        $("#sharePasswordForm").should(exist);
        $("#sharePasswordForm input[name='sharePassword']").setValue("not-" + password);
        $("#sharePasswordForm button").click();
        $("#sharePasswordForm").should(exist);
        $(".share-password-error").should(visible);

        $("#sharePasswordForm input[name='sharePassword']").setValue(password);
        $("#sharePasswordForm button").click();
        $("body").shouldHave(text(body));
        $("h1").shouldHave(text(title));
    }

    @Test
    void aPrivateDirectoryShareServesMediaOnlyThroughItsToken() {
        String suffix = Long.toString(System.nanoTime(), 36);
        // the sidebar's directory-name input is maxlength 25
        String directoryName = "itshare" + suffix;

        enableUploads();

        // The sidebar's create-directory control drives mediaFileViewForm,
        // which only renders when the current directory has files -- make
        // sure the default directory has one.
        uploadImage(null);

        // --- create the directory and put a photo in it ---------------------
        openPath(MEDIA_VIEW);
        $("#newDirectoryName").should(exist).setValue(directoryName);
        $("#newDirectoryButton").click();
        $("#directoryShareCard").should(exist);
        uploadImage(directoryName);

        // --- mark it private and share it (no password) ---------------------
        openPath(MEDIA_VIEW + "&directoryName=" + directoryName);
        $("#directoryPrivateToggle").should(exist).click();
        $("#directoryPrivateToggle").should(com.codeborne.selenide.Condition.checked);
        $("#createShareLinkButton").should(exist).click();
        String shareUrl = $("#shareLinkUrl").should(visible).toWebElement().getAttribute("value");
        assertNotNull(shareUrl);

        // --- the share page renders the grid through the token route --------
        openPath(pathOf(shareUrl));
        $(".jgrid").should(exist);
        WebElement image = $(".jgrid img").should(exist).toWebElement();
        String imageSrc = image.getAttribute("src");
        assertNotNull(imageSrc);
        assertTrue(imageSrc.contains("/share/") && imageSrc.contains("/media/"),
                "grid images must be served through the token-scoped media "
                        + "route, got: " + imageSrc);
        // the browser actually loaded it (naturalWidth 0 would mean a broken image)
        Long naturalWidth = executeJavaScript(
                "return document.querySelector('.jgrid img').naturalWidth;");
        assertNotNull(naturalWidth);
        assertTrue(naturalWidth > 0, "the share-scoped image must actually load");

        // --- and the same file 404s on the base media path ------------------
        String mediaFileId = imageSrc.substring(imageSrc.lastIndexOf('/') + 1);
        int queryStart = mediaFileId.indexOf('?');
        if (queryStart >= 0) {
            mediaFileId = mediaFileId.substring(0, queryStart);
        }
        String basePath = baseUrl() + "/" + WEBLOG_HANDLE + "/mediaresource/" + mediaFileId;
        assertEquals(404, anonymousStatusOf(basePath),
                "a private directory's file must 404 on the base media path: " + basePath);
        assertEquals(404, anonymousStatusOf(basePath + "?w=480"),
                "the rendition variant must 404 too");
    }

    // ------------------------------------------------------------- helpers

    /** Server-relative path (context included) of an absolute URL on the instance under test. */
    private String pathOf(String absoluteUrl) {
        String base = baseUrl();
        assertTrue(absoluteUrl.startsWith(base),
                "expected a URL on the instance under test, got: " + absoluteUrl);
        return absoluteUrl.substring(base.length());
    }

    /** HTTP status of a cookieless anonymous GET. */
    private static int anonymousStatusOf(String url) {
        try {
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<Void> response = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(20))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        } catch (Exception e) {
            throw new IllegalStateException("Could not GET " + url + " anonymously", e);
        }
    }

    /**
     * File uploads are globally disabled by default; flip the runtime
     * property through the admin screen, staying on real UI end to end
     * (same helper shape as EditorSeoIT).
     */
    private void enableUploads() {
        openPath(GLOBAL_CONFIG);
        WebElement checkbox = $("input[name='uploads.enabled']").should(exist).toWebElement();
        if (!checkbox.isSelected()) {
            checkbox.click();
        }
        $("#saveButton").click();
        $("#messages").should(exist);
    }

    /** Uploads the bundled test image, into the named directory or the default one. */
    private void uploadImage(String directoryName) {
        openPath(MEDIA_ADD);
        if (directoryName != null) {
            $("select[name='bean.directoryId']").should(exist)
                    .selectOption(directoryName);
        }
        $("#fileControl0").should(exist).uploadFile(testImage());
        $("#uploadButton").click();
        $("button[formaction$='entryAddWithMediaFile.rol']").should(exist);
    }

    private File testImage() {
        URL resource = getClass().getResource("/hawk.jpg");
        assertNotNull(resource, "hawk.jpg must be on the it-selenium test classpath");
        try {
            return new File(resource.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot resolve test image", e);
        }
    }
}
