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
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.codeborne.selenide.Selenide;
import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebElement;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static com.codeborne.selenide.Selenide.switchTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crop journey end to end: upload an image, open it in the media editor's
 * lightbox, apply a crop through the Cropper.js UI (accepting the destructive
 * confirm), and prove the PUBLIC page serves the new, smaller dimensions.
 *
 * <p>{@code MediaFileCropTest} proves the manager re-encodes and regenerates
 * everything; the controller test proves the action wiring. What only a
 * browser can prove is the JSP + Cropper.js custom-element layer itself: the
 * selection drawn on the displayed image must reach the server as a sensible
 * pixel rectangle, and the re-rendered editor and the published page must
 * both tell the same story about the new size.
 */
class MediaCropIT extends RollerIT {

    private static final String ENTRY_ADD = "/roller-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String MEDIA_ADD = "/roller-ui/authoring/mediaFileAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String MEDIA_VIEW = "/roller-ui/authoring/mediaFileView.rol?weblog=" + WEBLOG_HANDLE;
    private static final String GLOBAL_CONFIG = "/roller-ui/admin/globalConfig.rol";

    /** The editor is Summernote, which hides the textarea behind a contenteditable div. */
    private static final String EDITOR_BODY = ".note-editable";

    /** Rendered on the edit page only once the entry is actually published. */
    private static final String PERMALINK = "#entry_bean_permalink";

    /** "&lt;b&gt;Image&lt;/b&gt;: 450 X 336 pixels" as rendered text. */
    private static final Pattern DIMENSIONS = Pattern.compile("Image\\s*:\\s*(\\d+) X (\\d+) pixels");

    /**
     * CI runners (Azure) are materially slower than a dev box: the cropper's
     * custom elements have to be parsed, upgraded and laid out, and the crop
     * POST re-encodes and regenerates renditions server-side before the
     * reload settles. 10s was tight even locally; give both phases real
     * headroom rather than chase flakiness with a bigger number later.
     */
    private static final Duration CI_TOLERANT_TIMEOUT = Duration.ofSeconds(30);

    @BeforeEach
    void logIn() {
        loginAsAdmin();
    }

    @Test
    void croppingThroughTheEditorChangesThePublishedDimensions() {
        String suffix = Long.toString(System.nanoTime(), 36);

        enableUploads();
        String imageName = uploadImage("crop-" + suffix + ".jpg");

        // --- open the freshly uploaded file in the edit lightbox ------------
        openPath(MEDIA_VIEW);
        $("div.mediaObject img[alt='" + imageName + "']").should(exist).click();
        $("#mediafile_edit_lightbox").shouldBe(visible);
        switchTo().frame("mediaFileEditor");

        // the crop section is the new admin surface; its title is the marker
        $("#cropSectionTitle").should(exist);
        $("#cropCanvas").should(exist);
        String mediaFileId = $("#mediaFileId").getAttribute("value");
        assertNotNull(mediaFileId);
        assertFalse(mediaFileId.isBlank(), "the editor must know which file it edits");

        // hawk.jpg is 500x373; remember what the editor says before the crop
        int[] before = displayedDimensions();

        // Cropper.js v2 ships as custom elements (<cropper-image>,
        // <cropper-selection>, ...): the browser has to parse and execute
        // cropper.min.js and run each element's upgrade reaction before any
        // of the cropper's own properties (like $change on a selection)
        // exist. Wait for that distinctly from geometry so a script-loading
        // regression fails with a clear signal instead of a geometry
        // timeout that looks like a layout problem.
        Selenide.Wait().withTimeout(CI_TOLERANT_TIMEOUT).until(d ->
                Boolean.TRUE.equals(executeJavaScript(
                        "return !!(window.customElements"
                        + " && customElements.get('cropper-image')"
                        + " && customElements.get('cropper-selection')"
                        + " && customElements.get('cropper-canvas'));")));

        // Cropper only has a usable geometry once the image has loaded and
        // the initial-coverage selection has been laid out; clicking before
        // that would be prevented by the page's own guard.
        Selenide.Wait().withTimeout(CI_TOLERANT_TIMEOUT).until(d ->
                Boolean.TRUE.equals(executeJavaScript(
                        "var i = document.getElementById('cropImage');"
                        + "var s = document.getElementById('cropSelection');"
                        + "return !!i && !!s && i.getBoundingClientRect().width > 0"
                        + " && s.width > 0;")));

        // Steer the selection to a modest trim (10px off each edge in natural
        // pixels) instead of the default coverage. The file is this test's own
        // uniquely named upload, so the exact trim only has to keep the
        // dimensions assertively smaller than the original.
        executeJavaScript(
                "var i = document.getElementById('cropImage');"
                + "var c = document.getElementById('cropCanvas');"
                + "var s = document.getElementById('cropSelection');"
                + "var ir = i.getBoundingClientRect(), cr = c.getBoundingClientRect();"
                + "var sx = ir.width / arguments[0], sy = ir.height / arguments[1];"
                + "s.$change((ir.left - cr.left) + 10 * sx, (ir.top - cr.top) + 10 * sy,"
                + "          (arguments[0] - 20) * sx, (arguments[1] - 20) * sy);",
                before[0], before[1]);

        // --- apply the crop, accepting the destructive confirm --------------
        $("#cropButton").click();
        Selenide.confirm();

        // The form posts and reloads inside the iframe: the crop itself
        // re-encodes the file and regenerates every rendition server-side,
        // which is the slowest step in the journey on a cold CI runner.
        // Re-entering the frame immediately can also race the reload itself
        // -- the old document may already be tearing down (a stale element
        // or "no such frame" from the driver) before the new one is ready --
        // so retry the switch itself rather than switching once and trusting
        // Selenide's element polling to paper over a torn-down frame.
        reenterFrameAfterPost("mediaFileEditor", "#messages", CI_TOLERANT_TIMEOUT);

        int[] after = displayedDimensions();
        assertTrue(after[0] < before[0] && after[1] < before[1],
                "the crop must shrink the stored dimensions, got " + before[0] + "x"
                        + before[1] + " -> " + after[0] + "x" + after[1]);
        switchTo().defaultContent();

        // --- publish an entry embedding the cropped image --------------------
        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("input[name='bean.title']").setValue("IT Crop " + suffix);
        $(EDITOR_BODY).should(visible);
        executeJavaScript(
                "$('#edit_content').summernote('code', arguments[0]);"
                        + "$('#edit_content').val(arguments[0]);",
                "<p>Cropped image entry " + suffix + " [image id=" + mediaFileId + "]</p>");

        $("button[formaction$='entryAdd!publish.rol']").click();
        $(PERMALINK).should(exist);
        String permalink = $(PERMALINK).getAttribute("href");
        assertNotNull(permalink);

        // --- the public page must serve the cropped dimensions ---------------
        String page = getAnonymously(permalink);
        assertTrue(page.contains("width=\"" + after[0] + "\" height=\"" + after[1] + "\""),
                "the published page must declare the cropped intrinsic dimensions "
                        + after[0] + "x" + after[1] + ". Page:\n" + page);
        assertFalse(page.contains("width=\"" + before[0] + "\" height=\"" + before[1] + "\""),
                "the pre-crop dimensions must be gone from the published page");
    }

    /** Parses the editor's "Image: W X H pixels" file-info line. */
    private int[] displayedDimensions() {
        String text = $("#entry").should(exist).getText();
        Matcher matcher = DIMENSIONS.matcher(text);
        assertTrue(matcher.find(), "expected image dimensions in the editor text, got:\n" + text);
        return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    /**
     * Switches into {@code frameName} and waits for {@code readySelector} to
     * exist, retrying the switch itself rather than switching once and
     * relying on Selenide's element polling. A frame that is mid-reload -
     * exactly the state right after an in-frame POST - can make even the
     * switch itself throw ({@code NoSuchFrameException} while the old
     * document is torn down, {@code StaleElementReferenceException} against
     * a reference from before the reload), and those must be treated as
     * "not ready yet", not as a hard failure.
     */
    private void reenterFrameAfterPost(String frameName, String readySelector, Duration timeout) {
        Selenide.Wait().withTimeout(timeout).until(d -> {
            try {
                switchTo().defaultContent();
                switchTo().frame(frameName);
                return $(readySelector).exists();
            } catch (org.openqa.selenium.NoSuchFrameException | org.openqa.selenium.StaleElementReferenceException
                    | org.openqa.selenium.NoSuchElementException e) {
                return false;
            }
        });
        $(readySelector).should(exist);
    }

    /**
     * File uploads are globally disabled by default; there is nothing to crop
     * until one succeeds. Flipping the runtime property through the admin
     * screen keeps the journey on real UI end to end (same as EditorSeoIT).
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
     * Uploads the bundled test image under a run-unique name and returns that
     * name. The crop destroys the file's original pixels, and the it_weblog
     * media library is shared by every IT in the run — cropping a bare
     * "hawk.jpg" could shrink the file another suite is about to pick from
     * the chooser (EditorSeoIT asserts the 480w srcset rung, which a 480px
     * crop of the 500px fixture no longer produces).
     */
    private String uploadImage(String uniqueName) {
        File image = uniquelyNamedCopy(testImage(), uniqueName);
        openPath(MEDIA_ADD);
        $("#fileControl0").should(exist).uploadFile(image);
        $("#uploadButton").click();
        $("button[formaction$='entryAddWithMediaFile.rol']").should(exist);
        return uniqueName;
    }

    /** Media file names come from the uploaded file's name, so copy the fixture. */
    private static File uniquelyNamedCopy(File original, String uniqueName) {
        try {
            Path dir = Files.createTempDirectory("roller-it-upload");
            dir.toFile().deleteOnExit();
            Path copy = dir.resolve(uniqueName);
            Files.copy(original.toPath(), copy);
            copy.toFile().deleteOnExit();
            return copy.toFile();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot stage a uniquely named upload", e);
        }
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
