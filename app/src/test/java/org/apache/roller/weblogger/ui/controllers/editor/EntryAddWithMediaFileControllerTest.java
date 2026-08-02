/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.ui.controllers.editor;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EntryAddWithMediaFileController}.
 *
 * <p>This controller never renders its own page; it builds an HTML snippet
 * from the media files selected in the picker, stuffs it into
 * {@code bean.text}, and forwards straight into {@link EntryAddController}'s
 * template ({@code entryAdd.rol}). The snippet is built by string
 * substitution rather than a template engine, so the exact markup for the
 * image and non-image cases is worth pinning literally - a stray typo in a
 * placeholder like {@code <urlt>} would silently leave garbage in the entry
 * text instead of failing anything visibly.
 */
class EntryAddWithMediaFileControllerTest extends EditorControllerTestSupport {

    private static final String FORWARD = "forward:/roller-ui/authoring/entryAdd.rol";

    private EntryAddWithMediaFileController controller;
    private EntryBean bean;
    private Model model;

    @BeforeEach
    void setUp() {
        controller = prepare(new EntryAddWithMediaFileController());
        bean = new EntryBean();
        model = newModel();
    }

    @Test
    void aSingleImageProducesAnImageTagWithItsPermalinkAndThumbnail() throws Exception {
        MediaFile photo = imageFile("file-1", "photo.jpg", 100, 80);
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(photo);
        when(weblogger.getUrlStrategy().getMediaFileURL(weblog, "file-1", true))
                .thenReturn("http://example.com/media/photo.jpg");
        when(weblogger.getUrlStrategy().getMediaFileThumbnailURL(weblog, "file-1", true))
                .thenReturn("http://example.com/media/photo-thumb.jpg");

        String view = controller.execute(request, model, bean, null, "file-1");

        assertEquals(FORWARD, view);
        String expected = "<p>photo.jpg</p>"
                + "<a href='http://example.com/media/photo.jpg'>"
                + "<img src='http://example.com/media/photo-thumb.jpg' alt='photo.jpg' "
                + "width='100' height='80'></img></a>";
        assertEquals(expected, bean.getText());
    }

    @Test
    void aSingleNonImageFileProducesAPlainLinkWithSizeAndType() throws Exception {
        MediaFile doc = fileOf("file-2", "report.pdf", "application/pdf", 2048);
        when(weblogger.getMediaFileManager().getMediaFile("file-2")).thenReturn(doc);
        when(weblogger.getUrlStrategy().getMediaFileURL(weblog, "file-2", true))
                .thenReturn("http://example.com/media/report.pdf");

        String view = controller.execute(request, model, bean, null, "file-2");

        assertEquals(FORWARD, view);
        assertEquals("<a href='http://example.com/media/report.pdf'>report.pdf</a> "
                + "(2048 bytes, application/pdf)", bean.getText());
    }

    @Test
    void aSingleSelectedImageParamIsTreatedAsAOneElementArray() throws Exception {
        // selectedImages (plural, from a multi-select overlay) and selectedImage
        // (singular, from a single-image insert) arrive from different pages;
        // when only the singular one is present it must be promoted rather than
        // silently ignored.
        MediaFile doc = fileOf("file-3", "notes.txt", "text/plain", 10);
        when(weblogger.getMediaFileManager().getMediaFile("file-3")).thenReturn(doc);
        when(weblogger.getUrlStrategy().getMediaFileURL(weblog, "file-3", true))
                .thenReturn("http://example.com/media/notes.txt");

        controller.execute(request, model, bean, null, "file-3");

        assertEquals("<a href='http://example.com/media/notes.txt'>notes.txt</a> "
                + "(10 bytes, text/plain)", bean.getText());
    }

    @Test
    void multipleSelectedImagesAreConcatenatedInOrder() throws Exception {
        MediaFile first = imageFile("file-1", "one.jpg", 50, 50);
        MediaFile second = imageFile("file-2", "two.jpg", 60, 60);
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(first);
        when(weblogger.getMediaFileManager().getMediaFile("file-2")).thenReturn(second);
        when(weblogger.getUrlStrategy().getMediaFileURL(weblog, "file-1", true))
                .thenReturn("http://example.com/one.jpg");
        when(weblogger.getUrlStrategy().getMediaFileThumbnailURL(weblog, "file-1", true))
                .thenReturn("http://example.com/one-thumb.jpg");
        when(weblogger.getUrlStrategy().getMediaFileURL(weblog, "file-2", true))
                .thenReturn("http://example.com/two.jpg");
        when(weblogger.getUrlStrategy().getMediaFileThumbnailURL(weblog, "file-2", true))
                .thenReturn("http://example.com/two-thumb.jpg");

        controller.execute(request, model, bean, new String[]{"file-1", "file-2"}, null);

        String expected =
                "<p>one.jpg</p><a href='http://example.com/one.jpg'>"
                        + "<img src='http://example.com/one-thumb.jpg' alt='one.jpg' width='50' height='50'></img></a>"
                + "<p>two.jpg</p><a href='http://example.com/two.jpg'>"
                        + "<img src='http://example.com/two-thumb.jpg' alt='two.jpg' width='60' height='60'></img></a>";
        assertEquals(expected, bean.getText(),
                "Multiple selections must be appended in the order they were selected");
    }

    @Test
    void anEnclosureUrlAppendsAnExtraParagraphAfterAnySelectedImages() {
        bean.setEnclosureURL("http://example.com/podcast.mp3");
        registerMessage("mediaFileEdit.includesEnclosure", "Also includes an enclosure:");

        controller.execute(request, model, bean, null, null);

        assertEquals("<p>Also includes an enclosure:<br />"
                + "<a href='http://example.com/podcast.mp3'>http://example.com/podcast.mp3</a></p>",
                bean.getText(),
                "With nothing selected, the enclosure paragraph is the entire generated text");
    }

    @Test
    void theViewIsAlwaysTheForwardToEntryAddRegardlessOfOutcome() throws Exception {
        when(weblogger.getMediaFileManager().getMediaFile("missing"))
                .thenThrow(new WebloggerException("no such file"));

        String view = controller.execute(request, model, bean, null, "missing");

        assertEquals(FORWARD, view, "A failure building the snippet must still forward to entryAdd.rol");
    }

    @Test
    void aFailureResolvingAMediaFileIsSwallowedAndLeavesTheBeanTextUntouched() throws Exception {
        // getMediaFile throws before the StringBuilder is ever assigned back onto
        // the bean, so whatever text the bean already carried must survive
        // untouched rather than being blanked out or half-written.
        bean.setText("original text");
        when(weblogger.getMediaFileManager().getMediaFile("missing"))
                .thenThrow(new WebloggerException("no such file"));

        String view = controller.execute(request, model, bean, null, "missing");

        assertEquals(FORWARD, view);
        assertEquals("original text", bean.getText(),
                "The exception must be swallowed, not crash the request, and must not overwrite "
                        + "the bean's existing text since the failure happens before setText is reached");
    }

    @Test
    void aSelectedFileFromAnotherWeblogContributesNothingToTheSnippet() throws Exception {
        // The generated snippet embeds the file's name and permalink, so a
        // global by-id lookup with no ownership check leaks both into weblog
        // A's draft.
        org.apache.roller.weblogger.pojos.Weblog other =
                new org.apache.roller.weblogger.pojos.Weblog();
        other.setId("weblog-2");
        other.setHandle("otherblog");
        org.apache.roller.weblogger.pojos.MediaFileDirectory theirDirectory =
                new org.apache.roller.weblogger.pojos.MediaFileDirectory();
        theirDirectory.setId("dir-foreign");
        theirDirectory.setName("default");
        theirDirectory.setWeblog(other);
        MediaFile foreign = fileOf("file-x", "their-photo.jpg", "image/jpeg", 100);
        foreign.setWeblog(other);
        foreign.setDirectory(theirDirectory);
        when(weblogger.getMediaFileManager().getMediaFile("file-x")).thenReturn(foreign);

        String view = controller.execute(request, model, bean, null, "file-x");

        assertEquals(FORWARD, view);
        assertEquals("", bean.getText(),
                "a foreign file's name and permalink must not reach this weblog's entry");
    }

    // --- helpers ---

    private MediaFile imageFile(String id, String name, int width, int height) {
        MediaFile file = fileOf(id, name, "image/jpeg", 0);
        file.setWidth(width);
        file.setHeight(height);
        return file;
    }

    private MediaFile fileOf(String id, String name, String contentType, long length) {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setName(name);
        file.setWeblog(weblog);
        file.setContentType(contentType);
        file.setLength(length);
        return file;
    }
}
