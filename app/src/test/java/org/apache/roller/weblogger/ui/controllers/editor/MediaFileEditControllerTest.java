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
package org.apache.roller.weblogger.ui.controllers.editor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MediaFileEditController} and {@link MediaFileBean}.
 *
 * <p>Editing a media file can rename it, replace its contents, and move it
 * between folders. The duplicate-name check is what stops two files in one
 * folder from resolving to the same URL, and it has to exempt the file being
 * edited or no file could be saved without also being renamed.
 */
class MediaFileEditControllerTest extends EditorControllerTestSupport {

    private MediaFileEditController controller;
    private MediaFileBean bean;
    private Model model;
    private MediaFile mediaFile;
    private MediaFileDirectory directory;
    private MediaFileDirectory otherDirectory;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new MediaFileEditController());
        bean = new MediaFileBean();
        model = newModel();

        directory = directory("dir-1", "default");
        otherDirectory = directory("dir-2", "photos");

        mediaFile = new MediaFile();
        mediaFile.setId("file-1");
        mediaFile.setName("photo.jpg");
        mediaFile.setContentType("image/jpeg");
        mediaFile.setWeblog(weblog);
        mediaFile.setDirectory(directory);
        directory.getMediaFiles().add(mediaFile);

        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(mediaFile);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-1")).thenReturn(directory);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-2")).thenReturn(otherDirectory);
        when(weblogger.getMediaFileManager().getMediaFileDirectories(weblog))
                .thenReturn(Collections.emptyList());
    }

    @Test
    void openingTheEditorLoadsTheFilesMetadata() {
        mediaFile.setDescription("A photo");
        mediaFile.setCopyrightText("(c) me");

        String view = controller.execute(request, model, bean, "file-1");

        assertEquals(".MediaFileEdit", view);
        assertEquals("photo.jpg", bean.getName());
        assertEquals("A photo", bean.getDescription());
        assertEquals("(c) me", bean.getCopyrightText());
        assertEquals("dir-1", bean.getDirectoryId());
        assertEquals("file-1", model.getAttribute("mediaFileId"));
    }

    @Test
    void openingTheEditorForAMissingFileReportsAnErrorRatherThanFailing() throws Exception {
        when(weblogger.getMediaFileManager().getMediaFile("gone"))
                .thenThrow(new WebloggerException("no such file"));

        String view = controller.execute(request, model, bean, "gone");

        assertEquals(".MediaFileEdit", view);
        assertTrue(errors(model).contains("uploadFiles.error.upload"),
                "Expected an error, got: " + errors(model));
    }

    @Test
    void savingMetadataUpdatesTheFile() throws Exception {
        bean.setId("file-1");
        bean.setName("photo.jpg");
        bean.setDirectoryId("dir-1");
        bean.setDescription("Updated description");
        bean.setCopyrightText("(c) someone");
        bean.setTagsAsString("holiday beach");

        String view = controller.save(request, model, bean, "file-1", null);

        assertEquals(".MediaFileEditSuccess", view);
        assertEquals("Updated description", mediaFile.getDescription());
        assertEquals("(c) someone", mediaFile.getCopyrightText());
        verify(weblogger.getMediaFileManager()).updateMediaFile(weblog, mediaFile);
        assertTrue(messages(model).contains("mediaFile.update.success"),
                "Expected a save confirmation, got: " + messages(model));
    }

    @Test
    void renamingOntoAnotherFilesNameInTheSameFolderIsRefused() throws Exception {
        // Two files with the same name in one folder collide on their public URL.
        MediaFile existing = new MediaFile();
        existing.setId("file-2");
        existing.setName("taken.jpg");
        existing.setWeblog(weblog);
        existing.setDirectory(directory);
        directory.getMediaFiles().add(existing);

        bean.setId("file-1");
        bean.setName("taken.jpg");
        bean.setDirectoryId("dir-1");

        String view = controller.save(request, model, bean, "file-1", null);

        assertEquals(".MediaFileEdit", view);
        assertTrue(errors(model).contains("MediaFile.error.duplicateName"),
                "Expected a duplicate-name error, got: " + errors(model));
        verify(weblogger.getMediaFileManager(), never()).updateMediaFile(any(), any());
    }

    @Test
    void keepingAFilesOwnNameIsNotADuplicate() throws Exception {
        // The check has to exempt the file being edited, or saving any
        // metadata change without renaming would be impossible.
        bean.setId("file-1");
        bean.setName("photo.jpg");
        bean.setDirectoryId("dir-1");

        String view = controller.save(request, model, bean, "file-1", null);

        assertEquals(".MediaFileEditSuccess", view);
        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
    }

    @Test
    void replacingTheFileContentsUpdatesLengthAndContentType() throws Exception {
        // The stored length and content type are served as Content-Length and
        // Content-Type; leaving the old values would truncate or mistype the
        // download.
        MultipartFile replacement = mock(MultipartFile.class);
        when(replacement.isEmpty()).thenReturn(false);
        when(replacement.getSize()).thenReturn(4096L);
        when(replacement.getContentType()).thenReturn("image/png");
        when(replacement.getInputStream())
                .thenReturn(new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));

        bean.setId("file-1");
        bean.setName("photo.jpg");
        bean.setDirectoryId("dir-1");

        controller.save(request, model, bean, "file-1", replacement);

        assertEquals(4096L, mediaFile.getLength());
        assertEquals("image/png", mediaFile.getContentType());
        verify(weblogger.getMediaFileManager())
                .updateMediaFile(any(), any(), any(java.io.InputStream.class));
    }

    @Test
    void savingWithNoReplacementFileDoesNotRewriteTheContents() throws Exception {
        bean.setId("file-1");
        bean.setName("photo.jpg");
        bean.setDirectoryId("dir-1");

        controller.save(request, model, bean, "file-1", null);

        verify(weblogger.getMediaFileManager(), never())
                .updateMediaFile(any(), any(), any(java.io.InputStream.class));
        verify(weblogger.getMediaFileManager()).updateMediaFile(weblog, mediaFile);
    }

    @Test
    void anEmptyReplacementFileIsTreatedAsNoReplacement() throws Exception {
        // The file input is always present in the form and arrives empty when
        // the user did not choose anything.
        MultipartFile empty = mock(MultipartFile.class);
        when(empty.isEmpty()).thenReturn(true);

        bean.setId("file-1");
        bean.setName("photo.jpg");
        bean.setDirectoryId("dir-1");

        controller.save(request, model, bean, "file-1", empty);

        verify(weblogger.getMediaFileManager(), never())
                .updateMediaFile(any(), any(), any(java.io.InputStream.class));
    }

    @Test
    void changingTheDirectoryMovesTheFile() throws Exception {
        bean.setId("file-1");
        bean.setName("photo.jpg");
        bean.setDirectoryId("dir-2");

        controller.save(request, model, bean, "file-1", null);

        verify(weblogger.getMediaFileManager()).moveMediaFile(mediaFile, otherDirectory);
    }

    @Test
    void leavingTheDirectoryUnchangedDoesNotMoveTheFile() throws Exception {
        bean.setId("file-1");
        bean.setName("photo.jpg");
        bean.setDirectoryId("dir-1");

        controller.save(request, model, bean, "file-1", null);

        verify(weblogger.getMediaFileManager(), never()).moveMediaFile(any(), any());
    }

    @Test
    void aFailedUpdateIsReportedRatherThanConfirmed() throws Exception {
        org.mockito.Mockito.doThrow(new WebloggerException("disk full"))
                .when(weblogger.getMediaFileManager()).updateMediaFile(any(), any());

        bean.setId("file-1");
        bean.setName("photo.jpg");
        bean.setDirectoryId("dir-1");

        String view = controller.save(request, model, bean, "file-1", null);

        assertEquals(".MediaFileEdit", view);
        assertTrue(errors(model).contains("uploadFiles.error.upload"),
                "Expected an error, got: " + errors(model));
        assertTrue(messages(model).isEmpty(), "A failed update must not report success");
    }

    @Test
    void beanRoundTripsTheEditableFields() throws Exception {
        mediaFile.setDescription("A photo");
        mediaFile.setCopyrightText("(c) me");
        mediaFile.setTagsAsString("holiday beach");
        mediaFile.setSharedForGallery(Boolean.TRUE);
        mediaFile.setOriginalPath("/orig/photo.jpg");
        mediaFile.setLength(1234L);

        MediaFileBean copy = new MediaFileBean();
        copy.copyFrom(mediaFile);

        assertEquals("file-1", copy.getId());
        assertEquals("photo.jpg", copy.getName());
        assertEquals("A photo", copy.getDescription());
        assertEquals("(c) me", copy.getCopyrightText());
        assertEquals("dir-1", copy.getDirectoryId());
        assertEquals("image/jpeg", copy.getContentType());
        assertEquals(1234L, copy.getLength());
        assertTrue(copy.isSharedForGallery());
        assertTrue(copy.isIsImage(), "A jpeg must be recognised as an image for the gallery view");

        MediaFile target = new MediaFile();
        copy.copyTo(target);

        assertEquals("photo.jpg", target.getName());
        assertEquals("A photo", target.getDescription());
        assertEquals("(c) me", target.getCopyrightText());
        assertEquals("/orig/photo.jpg", target.getOriginalPath());
        assertTrue(target.getSharedForGallery());
    }

    @Test
    void beanCopyToDoesNotCarryTheDirectoryAcross() throws Exception {
        // Moving between folders is a separate operation with its own storage
        // side effects; a plain field copy would leave the file's row pointing
        // at a folder its bytes are not in.
        MediaFileBean copy = new MediaFileBean();
        copy.copyFrom(mediaFile);
        copy.setDirectoryId("dir-2");

        MediaFile target = new MediaFile();
        copy.copyTo(target);

        org.junit.jupiter.api.Assertions.assertNull(target.getDirectory(),
                "copyTo must leave the directory to the explicit move path");
    }

    private MediaFileDirectory directory(String id, String name) {
        MediaFileDirectory dir = new MediaFileDirectory();
        dir.setId(id);
        dir.setName(name);
        dir.setWeblog(weblog);
        Set<MediaFile> files = new LinkedHashSet<>();
        dir.setMediaFiles(files);
        return dir;
    }
}
