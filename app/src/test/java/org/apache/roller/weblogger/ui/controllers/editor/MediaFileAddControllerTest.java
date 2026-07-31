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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * Tests for {@link MediaFileAddController}.
 *
 * <p>This is the one place in the editor where a user hands the server a file
 * and a filename. The filename is used to build a path on disk, so the
 * traversal check ("no slashes, no ..", and truncate at any embedded NUL) is a
 * security control, not a nicety: without it an upload named
 * {@code ../../../etc/cron.d/x} escapes the weblog's upload directory. The
 * content-type fallback matters for a different reason — browsers frequently
 * send {@code application/octet-stream}, and storing that would make every
 * image download instead of display.
 */
class MediaFileAddControllerTest extends EditorControllerTestSupport {

    /**
     * A literal NUL, spelled out rather than embedded in the source: an actual
     * 0x00 byte in a .java file is invisible in every editor and diff tool, and
     * these tests exist precisely to prove it is handled.
     */
    private static final String NUL = String.valueOf((char) 0);

    private MediaFileAddController controller;
    private MediaFileBean bean;
    private Model model;
    private MediaFileDirectory directory;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new MediaFileAddController());
        bean = new MediaFileBean();
        model = newModel();

        directory = new MediaFileDirectory();
        directory.setId("dir-1");
        directory.setName("default");
        directory.setWeblog(weblog);

        when(weblogger.getMediaFileManager().getDefaultMediaFileDirectory(weblog))
                .thenReturn(directory);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-1")).thenReturn(directory);
        when(weblogger.getMediaFileManager().getMediaFileDirectories(weblog))
                .thenReturn(Collections.emptyList());

        // Uploads live behind a runtime switch that defaults to off in an
        // unstubbed business tier; most tests here need it on.
        givenRuntimeProperty("uploads.enabled", "true");
    }

    @Test
    void theUploadFormResolvesTheDefaultDirectoryWhenNoneWasChosen() {
        String view = controller.execute(request, model, bean);

        assertEquals(".MediaFileAdd", view);
        assertEquals(directory, model.getAttribute("directory"));
        assertEquals("dir-1", bean.getDirectoryId(),
                "The resolved directory must be written back to the form so the subsequent "
                        + "POST uploads into the folder the user was looking at");
    }

    @Test
    void theUploadFormCreatesADefaultDirectoryForABrandNewWeblog() throws Exception {
        when(weblogger.getMediaFileManager().getDefaultMediaFileDirectory(weblog)).thenReturn(null);
        when(weblogger.getMediaFileManager().createDefaultMediaFileDirectory(weblog))
                .thenReturn(directory);

        controller.execute(request, model, bean);

        verify(weblogger.getMediaFileManager()).createDefaultMediaFileDirectory(weblog);
        assertEquals(directory, model.getAttribute("directory"));
    }

    @Test
    void uploadingIsRefusedWhenTheSiteAdminHasTurnedUploadsOff() throws Exception {
        givenRuntimeProperty("uploads.enabled", "false");

        String view = controller.save(request, model, bean,
                new MultipartFile[]{upload("photo.jpg", "image/jpeg", "data")});

        assertEquals(".MediaFileAdd", view);
        assertTrue(errors(model).contains("error.upload.disabled"),
                "Expected the uploads-disabled error, got: " + errors(model));
        verify(weblogger.getMediaFileManager(), never()).createMediaFile(any(), any(), any());
    }

    @Test
    void aValidUploadIsStoredAgainstTheWeblogAndDirectory() throws Exception {
        String view = controller.save(request, model, bean,
                new MultipartFile[]{upload("photo.jpg", "image/jpeg", "binary")});

        assertEquals(".MediaFileAddSuccess", view);
        MediaFile stored = captureCreatedFile();
        assertEquals("photo.jpg", stored.getName());
        assertEquals("image/jpeg", stored.getContentType());
        assertEquals(weblog, stored.getWeblog());
        assertEquals(directory, stored.getDirectory(),
                "An upload with no directory must land in the resolved default, not nowhere");
        assertEquals("binary".length(), stored.getLength());
    }

    @Test
    void aFilenameContainingASlashIsRefused() throws Exception {
        // Path traversal: the name is used to build a path under the weblog's
        // upload directory.
        String view = controller.save(request, model, bean,
                new MultipartFile[]{upload("../../etc/passwd", "text/plain", "x")});

        assertEquals(".MediaFileAdd", view);
        assertTrue(errors(model).contains("uploadFiles.error.badPath"),
                "Expected a bad-path error, got: " + errors(model));
        verify(weblogger.getMediaFileManager(), never()).createMediaFile(any(), any(), any());
    }

    @Test
    void aFilenameContainingABackslashIsRefused() throws Exception {
        // Windows-style separators have to be caught too; the check is not
        // about the host OS but about what the storage layer will interpret.
        controller.save(request, model, bean,
                new MultipartFile[]{upload("..\\..\\windows\\system32\\evil.dll", "text/plain", "x")});

        assertTrue(errors(model).contains("uploadFiles.error.badPath"),
                "Expected a bad-path error, got: " + errors(model));
        verify(weblogger.getMediaFileManager(), never()).createMediaFile(any(), any(), any());
    }

    @Test
    void aFilenameContainingDotDotIsRefusedEvenWithoutASeparator() throws Exception {
        controller.save(request, model, bean,
                new MultipartFile[]{upload("photo..jpg", "image/jpeg", "x")});

        assertTrue(errors(model).contains("uploadFiles.error.badPath"),
                "Expected a bad-path error, got: " + errors(model));
        verify(weblogger.getMediaFileManager(), never()).createMediaFile(any(), any(), any());
    }

    @Test
    void aFilenameIsTruncatedAtAnEmbeddedNulByte() throws Exception {
        // The classic poison-NUL trick: "shell.jsp\0.gif" passes an extension
        // check but is written as "shell.jsp" by anything using C string
        // semantics. Roller truncates at the NUL so the stored name is
        // unambiguous.
        controller.save(request, model, bean,
                new MultipartFile[]{upload("photo.jpg" + NUL + ".exe", "image/jpeg", "x")});

        assertEquals("photo.jpg", captureCreatedFile().getName(),
                "Everything from the NUL byte onwards must be discarded");
    }

    @Test
    void aNulTruncatedFilenameIsStillCheckedForTraversal() throws Exception {
        // Truncation happens first, so a payload hidden before the NUL must
        // still be caught.
        controller.save(request, model, bean,
                new MultipartFile[]{upload("../evil" + NUL + ".jpg", "image/jpeg", "x")});

        assertTrue(errors(model).contains("uploadFiles.error.badPath"),
                "Traversal must be detected on the truncated name: " + errors(model));
        verify(weblogger.getMediaFileManager(), never()).createMediaFile(any(), any(), any());
    }

    @Test
    void anOctetStreamContentTypeIsReplacedByOneInferredFromTheExtension() throws Exception {
        // Browsers send octet-stream for anything they do not recognise; storing
        // it would make the file download rather than render.
        controller.save(request, model, bean,
                new MultipartFile[]{upload("photo.jpg", "application/octet-stream", "x")});

        assertEquals("image/jpeg", captureCreatedFile().getContentType(),
                "octet-stream must be replaced by the type implied by the extension");
    }

    @Test
    void aMissingContentTypeIsInferredFromTheExtension() throws Exception {
        controller.save(request, model, bean,
                new MultipartFile[]{upload("photo.jpg", null, "x")});

        assertEquals("image/jpeg", captureCreatedFile().getContentType());
    }

    @Test
    void aBrowserSuppliedContentTypeIsKeptWhenItIsSpecific() throws Exception {
        controller.save(request, model, bean,
                new MultipartFile[]{upload("photo.jpg", "image/png", "x")});

        assertEquals("image/png", captureCreatedFile().getContentType(),
                "A specific content type from the browser must win over the extension guess");
    }

    @Test
    void anUnrecognisedExtensionKeepsTheOctetStreamContentType() throws Exception {
        controller.save(request, model, bean,
                new MultipartFile[]{upload("archive.zzzz", "application/octet-stream", "x")});

        assertEquals("application/octet-stream", captureCreatedFile().getContentType(),
                "With nothing better to infer, the original type must be preserved rather "
                        + "than nulled");
    }

    @Test
    void emptyFileSlotsInTheFormAreSkipped() throws Exception {
        // The upload form renders several file inputs; the user typically fills
        // in one. The empty ones must not become zero-byte media files.
        MultipartFile empty = mock(MultipartFile.class);
        when(empty.isEmpty()).thenReturn(true);

        controller.save(request, model, bean,
                new MultipartFile[]{empty, upload("photo.jpg", "image/jpeg", "x"), null});

        verify(weblogger.getMediaFileManager(), org.mockito.Mockito.times(1))
                .createMediaFile(any(), any(), any());
    }

    @Test
    void submittingNoFilesAtAllJustRedisplaysTheForm() throws Exception {
        String view = controller.save(request, model, bean, new MultipartFile[0]);

        assertEquals(".MediaFileAdd", view);
        verify(weblogger.getMediaFileManager(), never()).createMediaFile(any(), any(), any());
    }

    @Test
    void uploadedImagesAndOtherFilesAreListedSeparatelyOnTheSuccessPage() throws Exception {
        // The success page shows a thumbnail grid for images and a plain list
        // for everything else.
        controller.save(request, model, bean, new MultipartFile[]{
                upload("photo.jpg", "image/jpeg", "x"),
                upload("notes.txt", "text/plain", "y")});

        assertEquals(1, ((java.util.List<?>) model.getAttribute("newImages")).size());
        assertEquals(1, ((java.util.List<?>) model.getAttribute("newFiles")).size());
    }

    @Test
    void aValidationFailureReportedByTheManagerStopsTheSuccessPage() throws Exception {
        // createMediaFile reports quota and file-type violations through the
        // RollerMessages collector rather than by throwing, so a controller that
        // only watched for exceptions would report success on a rejected upload.
        org.mockito.Mockito.doAnswer(invocation -> {
            org.apache.roller.weblogger.util.RollerMessages messages = invocation.getArgument(2);
            messages.addError("error.upload.forbiddenFile");
            return null;
        }).when(weblogger.getMediaFileManager()).createMediaFile(any(), any(), any());

        String view = controller.save(request, model, bean,
                new MultipartFile[]{upload("evil.exe", "application/octet-stream", "x")});

        assertEquals(".MediaFileAdd", view,
                "A rejected upload must not land on the success page");
        assertTrue(errors(model).contains("error.upload.forbiddenFile"),
                "The manager's complaint must reach the user: " + errors(model));
    }

    @Test
    void aFailureWhileStoringOneFileIsReportedAndDoesNotAbortTheBatch() throws Exception {
        org.mockito.Mockito.doThrow(new WebloggerException("disk full"))
                .when(weblogger.getMediaFileManager()).createMediaFile(any(), any(), any());

        String view = controller.save(request, model, bean,
                new MultipartFile[]{upload("photo.jpg", "image/jpeg", "x")});

        assertEquals(".MediaFileAdd", view);
        assertTrue(errors(model).contains("mediaFileAdd.errorUploading"),
                "Expected an upload error, got: " + errors(model));
    }

    @Test
    void cancellingReturnsToTheMediaFileList() {
        assertEquals("redirect:/roller-ui/authoring/mediaFileView.rol?weblog=" + WEBLOG_HANDLE,
                controller.cancel(request));
    }

    @Test
    void cancellingWithNoWeblogInContextStillProducesAUsableRedirect() {
        // The interceptor normally guarantees a weblog, but the cancel link is
        // the one path that tolerates its absence rather than throwing.
        when(request.getAttribute("actionWeblog")).thenReturn(null);

        assertEquals("redirect:/roller-ui/authoring/mediaFileView.rol?weblog=",
                controller.cancel(request));
    }

    // --- helpers ---

    private MediaFile captureCreatedFile() throws WebloggerException {
        ArgumentCaptor<MediaFile> captor = ArgumentCaptor.forClass(MediaFile.class);
        verify(weblogger.getMediaFileManager()).createMediaFile(any(), captor.capture(), any());
        return captor.getValue();
    }

    private MultipartFile upload(String fileName, String contentType, String content)
            throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn(fileName);
        when(file.getContentType()).thenReturn(contentType);
        when(file.getSize()).thenReturn((long) content.length());
        when(file.getInputStream())
                .thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        return file;
    }
}
