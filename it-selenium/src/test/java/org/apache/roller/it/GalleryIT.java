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
import com.codeborne.selenide.SelenideElement;
import org.apache.roller.it.support.BrowserHealth;
import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebElement;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;

import static com.codeborne.selenide.CollectionCondition.sizeGreaterThan;
import static com.codeborne.selenide.Condition.appear;
import static com.codeborne.selenide.Condition.attribute;
import static com.codeborne.selenide.Condition.disappear;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static com.codeborne.selenide.Selenide.switchTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Closes the gallery loop end to end in a real browser: upload an image,
 * caption it through the media-file editor, publish an entry containing the
 * {@code [gallery]} shortcode, and prove that the public permalink renders
 * the justified grid and that clicking a tile opens the PhotoSwipe lightbox
 * -- the one deliverable no server-side rendering test can cover, because
 * the lightbox is entirely {@code #showGalleryAssets} JavaScript executing
 * against the shortcode's markup contract ({@code .jgrid} anchors carrying
 * {@code data-pswp-*}/{@code data-caption}).
 *
 * <p>{@code GalleryRenderingTest} proves the markup reaches an anonymous
 * reader through the sanitizer; {@code GalleryAssetsRenderingTest} proves
 * every theme head ships the assets exactly once. This test is the browser
 * half: the ES modules actually load under the theme CSP, the click binds,
 * and the caption panel shows the author's description.
 */
class GalleryIT extends RollerIT {

    private static final String ENTRY_ADD = "/roller-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String MEDIA_ADD = "/roller-ui/authoring/mediaFileAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String MEDIA_VIEW = "/roller-ui/authoring/mediaFileView.rol?weblog=" + WEBLOG_HANDLE;
    private static final String GLOBAL_CONFIG = "/roller-ui/admin/globalConfig.rol";

    /** The editor is Summernote, which hides the textarea behind a contenteditable div. */
    private static final String EDITOR_BODY = ".note-editable";

    /** Rendered on the edit page only once the entry is actually published. */
    private static final String PERMALINK = "#entry_bean_permalink";

    @BeforeEach
    void logIn() {
        loginAsAdmin();
    }

    @Test
    void aPublishedGalleryOpensThePhotoSwipeLightboxWithItsCaption() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String imageName = "gallery-hawk-" + suffix + ".jpg";
        String caption = "A red-tailed hawk over the valley " + suffix;

        enableUploads();
        String mediaFileId = uploadImage();
        renameAndDescribe(mediaFileId, imageName, caption);

        // --- author and publish the gallery entry ---------------------------
        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("input[name='bean.title']").setValue("IT Gallery " + suffix);
        $(EDITOR_BODY).should(visible);
        executeJavaScript(
                "$('#edit_content').summernote('code', arguments[0]);"
                        + "$('#edit_content').val(arguments[0]);",
                "<p>Shot log " + suffix + "</p><p>[gallery dir=\"default\"]</p>");
        $("button[formaction$='entryAdd!publish.rol']").click();
        $(PERMALINK).should(exist);
        String permalink = $(PERMALINK).getAttribute("href");
        assertNotNull(permalink);

        // --- the public page renders the justified grid ---------------------
        Selenide.open(permalink);
        BrowserHealth.current().settle();
        $$(".jgrid figure").shouldHave(sizeGreaterThan(0));

        // this run's image, found by its unique caption payload; the default
        // directory accumulates uploads from other tests in the same suite
        SelenideElement tile = $$(".jgrid a")
                .findBy(attribute("data-caption", caption))
                .should(exist);
        tile.$("img").should(exist);
        tile.parent().$("figcaption").shouldHave(text(caption));

        // --- clicking the tile opens PhotoSwipe -----------------------------
        tile.click();
        $(".pswp").should(appear);
        $(".pswp__img").should(exist);
        $(".pswp__custom-caption").shouldBe(visible).shouldHave(text(caption));
    }

    /**
     * File uploads are globally disabled by default; flipping the runtime
     * property through the admin screen keeps this journey on real UI.
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

    /**
     * Uploads the bundled test image and returns the new media file's id,
     * read off the success page's image-selection checkbox.
     */
    private String uploadImage() {
        openPath(MEDIA_ADD);
        $("#fileControl0").should(exist).uploadFile(testImage());
        $("#uploadButton").click();
        // Only the success page offers to create a post from the upload.
        $("button[formaction$='entryAddWithMediaFile.rol']").should(exist);
        String mediaFileId = $("input[name='selectedImages']").should(exist).getValue();
        assertNotNull(mediaFileId, "the upload success page must carry the new file's id");
        return mediaFileId;
    }

    /**
     * Renames and captions the uploaded file through the media-view edit
     * modal, exactly as an author would. The rename matters twice over: the
     * save-time duplicate-name validation would reject a second "hawk.jpg"
     * in the default directory (other suites upload the same fixture), and
     * the unique caption is what later identifies this run's tile in the
     * public grid. The description is what {@code GalleryShortcode} turns
     * into the figcaption and {@code data-caption} the lightbox displays.
     */
    private void renameAndDescribe(String mediaFileId, String name, String caption) {
        openPath(MEDIA_VIEW);
        // the page's own tile-click handler: fills the modal's iframe and shows it
        executeJavaScript("onClickEdit(arguments[0], arguments[1]);", mediaFileId, name);
        $("#mediafile_edit_lightbox").shouldBe(visible);
        switchTo().frame("mediaFileEditor");
        $("input[name='bean.name']").should(exist).setValue(name);
        $("textarea[name='bean.description']").setValue(caption);
        $("input[name='submit']").click();
        switchTo().defaultContent();
        // the success page calls parent.onEditSuccess(), which closes the
        // modal and re-submits the view form -- wait for it so the save has
        // definitely landed before the entry references the directory
        $("#mediafile_edit_lightbox").should(disappear);
        BrowserHealth.current().settle();

        // ...and then prove the save actually landed. Waiting on the modal
        // alone only proves the browser navigated: the re-submitted view form
        // can still be in flight, and on a slow runner the entry below then
        // renders a gallery whose tile has no caption yet, failing far away
        // from the cause. The renamed tile appearing in the re-rendered view
        // is the first observable evidence the write committed.
        $("img[alt='" + name + "']").should(exist);
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
