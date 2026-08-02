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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.ShareLink;
import org.apache.roller.weblogger.ui.core.RollerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MediaFileViewController} and the shared operations it
 * inherits from {@link MediaFileBase}.
 *
 * <p>The destructive operations here — delete, delete-folder, bulk delete, bulk
 * move — are the ones worth pinning. A bulk delete that silently skips a file
 * still reports success; a move that does not check the current directory
 * churns every file in the list. The directory-name validation matters because
 * folder names become path segments.
 */
class MediaFileViewControllerTest extends EditorControllerTestSupport {

    private MediaFileViewController controller;
    private Model model;
    private MediaFileDirectory defaultDirectory;
    private MediaFileDirectory targetDirectory;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new MediaFileViewController());
        model = newModel();

        defaultDirectory = directory("dir-1", "default");
        targetDirectory = directory("dir-2", "photos");

        when(weblogger.getMediaFileManager().getDefaultMediaFileDirectory(weblog))
                .thenReturn(defaultDirectory);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-1"))
                .thenReturn(defaultDirectory);
        when(weblogger.getMediaFileManager().getMediaFileDirectories(weblog))
                .thenReturn(List.of(defaultDirectory, targetDirectory));
    }

    // --- browsing ---

    @Test
    void browsingWithNoDirectoryChosenShowsTheDefaultOne() {
        String view = controller.execute(request, model, null, null, null);

        assertEquals(".MediaFileView", view);
        assertEquals(defaultDirectory, model.getAttribute("currentDirectory"));
        assertEquals("dir-1", model.getAttribute("directoryId"));
    }

    @Test
    void browsingByDirectoryNameResolvesThatDirectory() throws Exception {
        when(weblogger.getMediaFileManager().getMediaFileDirectoryByName(weblog, "photos"))
                .thenReturn(targetDirectory);

        controller.execute(request, model, null, "photos", null);

        assertEquals(targetDirectory, model.getAttribute("currentDirectory"));
    }

    @Test
    void anExplicitDirectoryIdWinsOverADirectoryName() throws Exception {
        // Both arrive from the same page; the id is the unambiguous one.
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-2"))
                .thenReturn(targetDirectory);

        controller.execute(request, model, "dir-2", "default", null);

        assertEquals(targetDirectory, model.getAttribute("currentDirectory"));
        verify(weblogger.getMediaFileManager(), never()).getMediaFileDirectoryByName(any(), any());
    }

    @Test
    void anUnrecognisedSortOrderFallsBackToSortingByName() {
        // The sort key is echoed back into the page as the selected dropdown
        // value, so it must be normalised rather than passed through.
        controller.execute(request, model, null, null, "; DROP TABLE");

        assertEquals("name", model.getAttribute("sortBy"),
                "An unknown sort option must be normalised to the default");
    }

    @Test
    void theChosenSortOrderIsPreservedWhenItIsRecognised() {
        controller.execute(request, model, null, null, "date_uploaded");

        assertEquals("date_uploaded", model.getAttribute("sortBy"));
    }

    @Test
    void filesAreOrderedByTheChosenSortKey() throws Exception {
        // The page has no client-side sorting, so this ordering is what the user
        // sees. Each key has its own comparator and they are easy to transpose.
        // Chosen so the two orderings genuinely disagree: by name alpha comes
        // first, by content type beta ("application/pdf") does.
        MediaFile alpha = mediaFile("file-a", "alpha.jpg");
        MediaFile beta = mediaFile("file-b", "beta.pdf");
        alpha.setContentType("image/jpeg");
        beta.setContentType("application/pdf");
        defaultDirectory.getMediaFiles().add(beta);
        defaultDirectory.getMediaFiles().add(alpha);

        controller.execute(request, model, null, null, "name");
        assertEquals(List.of(alpha, beta), model.getAttribute("childFiles"),
                "The default ordering must be alphabetical by name");

        model = newModel();
        controller.execute(request, model, null, null, "type");
        assertEquals(List.of(beta, alpha), model.getAttribute("childFiles"),
                "Sorting by type must order on content type, not fall back to name");
    }

    @Test
    void theSearchFormOffersEveryFilterOptionTheBeanCanMap() throws Exception {
        // MediaFileSearchBean maps five size comparisons, five file types and
        // three units; an option missing from these lists is a filter the user
        // simply cannot reach.
        controller.execute(request, model, null, null, null);

        assertEquals(5, ((List<?>) model.getAttribute("fileTypes")).size());
        assertEquals(5, ((List<?>) model.getAttribute("sizeFilterTypes")).size());
        assertEquals(3, ((List<?>) model.getAttribute("sizeUnits")).size());
        assertEquals(3, ((List<?>) model.getAttribute("sortOptions")).size());
    }

    @Test
    void destructiveOperationsAreCommitted() throws Exception {
        // Without a flush the delete never reaches the database, yet the page
        // still reports success.
        MediaFile file = mediaFile("file-1", "photo.jpg");
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);

        controller.delete(request, model, "dir-1", "file-1", null);

        assertTrue(weblogger.flushCount() > 0, "A delete must be committed");
    }

    @Test
    void aFailureLoadingTheDirectoryIsReportedRatherThanBlowingUpThePage() throws Exception {
        when(weblogger.getMediaFileManager().getDefaultMediaFileDirectory(weblog))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.execute(request, model, null, null, null);

        assertEquals(".MediaFileView", view);
        assertTrue(errors(model).contains("MediaFile.error.view"),
                "Expected a view error, got: " + errors(model));
    }

    // --- creating directories ---

    @Test
    void creatingADirectoryWithNoNameIsRefused() {
        controller.createNewDirectory(request, model, null, "", null);

        assertTrue(errors(model).contains("mediaFile.error.view.dirNameEmpty"),
                "Expected an empty-name error, got: " + errors(model));
    }

    @Test
    void creatingADirectoryWithASlashInTheNameIsRefused() throws Exception {
        // Directory names become path segments under the weblog's upload root.
        controller.createNewDirectory(request, model, null, "photos/2024", null);

        assertTrue(errors(model).contains("mediaFile.error.view.dirNameInvalid"),
                "Expected an invalid-name error, got: " + errors(model));
        verify(weblogger.getMediaFileManager(), never()).createMediaFileDirectory(any(), any());
    }

    @Test
    void creatingADirectoryThatAlreadyExistsIsReportedAndNotDuplicated() throws Exception {
        weblog.getMediaFileDirectories().add(targetDirectory);

        controller.createNewDirectory(request, model, null, "photos", null);

        verify(weblogger.getMediaFileManager(), never()).createMediaFileDirectory(any(), any());
        assertTrue(messages(model).contains("mediaFile.directoryCreate.error.exists"),
                "Expected a duplicate notice, got: " + messages(model));
    }

    @Test
    void creatingADirectorySwitchesTheViewToTheNewFolder() throws Exception {
        MediaFileDirectory created = directory("dir-3", "trips");
        when(weblogger.getMediaFileManager().createMediaFileDirectory(weblog, "trips"))
                .thenReturn(created);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-3")).thenReturn(created);

        controller.createNewDirectory(request, model, null, "trips", null);

        assertTrue(messages(model).contains("mediaFile.directoryCreate.success"),
                "Expected a success notice, got: " + messages(model));
        assertEquals(created, model.getAttribute("currentDirectory"),
                "After creating a folder the user must be looking at it, not back at the default");
    }

    // --- deleting ---

    @Test
    void deletingAFileRemovesItAndConfirms() throws Exception {
        MediaFile file = mediaFile("file-1", "photo.jpg");
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);

        controller.delete(request, model, "dir-1", "file-1", null);

        verify(weblogger.getMediaFileManager()).removeMediaFile(weblog, file);
        assertTrue(messages(model).contains("mediaFile.delete.success"),
                "Expected a delete confirmation, got: " + messages(model));
    }

    @Test
    void aFailedDeleteIsReportedRatherThanConfirmed() throws Exception {
        when(weblogger.getMediaFileManager().getMediaFile("file-1"))
                .thenThrow(new WebloggerException("no such file"));

        controller.delete(request, model, "dir-1", "file-1", null);

        assertTrue(errors(model).contains("mediaFile.delete.error"),
                "Expected a delete error, got: " + errors(model));
        assertTrue(messages(model).isEmpty(),
                "A failed delete must not also report success");
    }

    @Test
    void bulkDeleteRemovesEverySelectedFile() throws Exception {
        MediaFile first = mediaFile("file-1", "a.jpg");
        MediaFile second = mediaFile("file-2", "b.jpg");
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(first);
        when(weblogger.getMediaFileManager().getMediaFile("file-2")).thenReturn(second);

        controller.deleteSelected(request, model, "dir-1", new String[]{"file-1", "file-2"}, null);

        verify(weblogger.getMediaFileManager()).removeMediaFile(weblog, first);
        verify(weblogger.getMediaFileManager()).removeMediaFile(weblog, second);
    }

    @Test
    void bulkDeleteSkipsIdsThatNoLongerResolve() throws Exception {
        // A stale page can post an id that has already been deleted; that must
        // not take out the rest of the batch with an NPE.
        MediaFile survivor = mediaFile("file-2", "b.jpg");
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(null);
        when(weblogger.getMediaFileManager().getMediaFile("file-2")).thenReturn(survivor);

        controller.deleteSelected(request, model, "dir-1", new String[]{"file-1", "file-2"}, null);

        verify(weblogger.getMediaFileManager()).removeMediaFile(weblog, survivor);
    }

    @Test
    void bulkDeleteWithNothingSelectedRemovesNothing() throws Exception {
        controller.deleteSelected(request, model, "dir-1", new String[0], null);

        verify(weblogger.getMediaFileManager(), never()).removeMediaFile(any(), any());
    }

    @Test
    void deletingAFolderSendsTheUserBackToTheDefaultFolder() throws Exception {
        // The folder they were looking at is gone, so the view has to land
        // somewhere that still exists.
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-2"))
                .thenReturn(targetDirectory);

        controller.deleteFolder(request, model, "dir-2", null, null);

        verify(weblogger.getMediaFileManager()).removeMediaFileDirectory(targetDirectory);
        assertTrue(messages(model).contains("mediaFile.deleteFolder.success"),
                "Expected a folder-delete confirmation, got: " + messages(model));
        assertEquals(defaultDirectory, model.getAttribute("currentDirectory"));
    }

    @Test
    void deletingAFolderWithNoIdDoesNothing() throws Exception {
        controller.deleteFolder(request, model, null, null, null);

        verify(weblogger.getMediaFileManager(), never()).removeMediaFileDirectory(any());
    }

    // --- moving ---

    @Test
    void movingSelectedFilesRelocatesThemToTheChosenFolder() throws Exception {
        MediaFile file = mediaFile("file-1", "photo.jpg");
        file.setDirectory(defaultDirectory);
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-2"))
                .thenReturn(targetDirectory);

        controller.moveSelected(request, model, "dir-1", new String[]{"file-1"}, "dir-2", null);

        verify(weblogger.getMediaFileManager()).moveMediaFile(file, targetDirectory);
        assertTrue(messages(model).contains("mediaFile.move.success"),
                "Expected a move confirmation, got: " + messages(model));
    }

    @Test
    void movingAFileIntoTheFolderItIsAlreadyInIsANoOp() throws Exception {
        // Without this check every "move" would rewrite the file on disk, and
        // the success message would be reported for a move that did nothing.
        MediaFile file = mediaFile("file-1", "photo.jpg");
        file.setDirectory(defaultDirectory);
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-1"))
                .thenReturn(defaultDirectory);

        controller.moveSelected(request, model, "dir-1", new String[]{"file-1"}, "dir-1", null);

        verify(weblogger.getMediaFileManager(), never()).moveMediaFile(any(), any());
        assertTrue(messages(model).isEmpty(),
                "Nothing moved, so nothing should be reported: " + messages(model));
    }

    @Test
    void aFailedMoveIsReported() throws Exception {
        MediaFile file = mediaFile("file-1", "photo.jpg");
        file.setDirectory(defaultDirectory);
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-2"))
                .thenReturn(targetDirectory);
        org.mockito.Mockito.doThrow(new WebloggerException("disk full"))
                .when(weblogger.getMediaFileManager()).moveMediaFile(any(), any());

        controller.moveSelected(request, model, "dir-1", new String[]{"file-1"}, "dir-2", null);

        assertTrue(errors(model).contains("mediaFile.move.errors"),
                "Expected a move error, got: " + errors(model));
    }

    // --- gallery sharing ---

    @Test
    void includingAFileInTheGalleryFlagsItAndSavesIt() throws Exception {
        // Sharing publishes the file into the site-wide gallery, so both the
        // flag and the persist have to happen.
        MediaFile file = mediaFile("file-1", "photo.jpg");
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);

        controller.includeInGallery(request, model, "dir-1", "file-1", null);

        assertTrue(file.getSharedForGallery());
        verify(weblogger.getMediaFileManager()).updateMediaFile(weblog, file);
        assertTrue(messages(model).contains("mediaFile.includeInGallery.success"),
                "Expected a gallery confirmation, got: " + messages(model));
    }

    @Test
    void aFailureSharingToTheGalleryIsReported() throws Exception {
        when(weblogger.getMediaFileManager().getMediaFile("file-1"))
                .thenThrow(new WebloggerException("no such file"));

        controller.includeInGallery(request, model, "dir-1", "file-1", null);

        assertTrue(errors(model).contains("mediaFile.includeInGallery.error"),
                "Expected a gallery error, got: " + errors(model));
    }

    // --- searching ---

    @Test
    void searchingWithNoCriteriaAtAllIsRefused() {
        // An unrestricted search would return the whole library and paginate
        // through it; the form requires at least one filter.
        MediaFileSearchBean bean = new MediaFileSearchBean();

        String view = controller.search(request, model, bean, "dir-1", null);

        assertEquals(".MediaFileView", view);
        assertTrue(errors(model).contains("MediaFile.error.search.empty"),
                "Expected an empty-search error, got: " + errors(model));
    }

    @Test
    void searchingByNameProducesAPagerOfResults() throws Exception {
        MediaFileSearchBean bean = new MediaFileSearchBean();
        bean.setName("photo");
        when(weblogger.getMediaFileManager().searchMediaFiles(any(), any()))
                .thenReturn(List.of(mediaFile("file-1", "photo.jpg")));

        controller.search(request, model, bean, "dir-1", null);

        assertTrue(errors(model).isEmpty(), "Search should have succeeded: " + errors(model));
        assertEquals(1, ((org.apache.roller.weblogger.ui.controllers.pagers.MediaFilePager)
                model.getAttribute("pager")).getItems().size());
    }

    @Test
    void aFullPageOfResultsIsTrimmedAndFlaggedAsHavingMore() throws Exception {
        // The manager is asked for PAGE_SIZE+1 rows precisely so the controller
        // can tell whether a next page exists; the extra row must not be shown.
        MediaFileSearchBean bean = new MediaFileSearchBean();
        bean.setName("photo");
        java.util.List<MediaFile> overflowing = new java.util.ArrayList<>();
        for (int i = 0; i <= MediaFileSearchBean.PAGE_SIZE; i++) {
            overflowing.add(mediaFile("file-" + i, "photo" + i + ".jpg"));
        }
        when(weblogger.getMediaFileManager().searchMediaFiles(any(), any())).thenReturn(overflowing);

        controller.search(request, model, bean, "dir-1", null);

        org.apache.roller.weblogger.ui.controllers.pagers.MediaFilePager pager =
                (org.apache.roller.weblogger.ui.controllers.pagers.MediaFilePager)
                        model.getAttribute("pager");
        assertEquals(MediaFileSearchBean.PAGE_SIZE, pager.getItems().size(),
                "The lookahead row must be trimmed off before display");
    }

    @Test
    void aFailedSearchIsReported() throws Exception {
        MediaFileSearchBean bean = new MediaFileSearchBean();
        bean.setName("photo");
        when(weblogger.getMediaFileManager().searchMediaFiles(any(), any()))
                .thenThrow(new WebloggerException("index corrupt"));

        controller.search(request, model, bean, "dir-1", null);

        assertEquals(1, errors(model).size(),
                "Expected the search failure to be surfaced, got: " + errors(model));
    }

    // --- share links and directory privacy ---

    @org.junit.jupiter.api.Nested
    class ShareLinks {

        @org.junit.jupiter.api.BeforeEach
        void installPasswordEncoder() throws Exception {
            // published by SecurityConfig at context refresh in production
            if (RollerContext.getPasswordEncoder() == null) {
                RollerContext.setPasswordEncoder(RollerContext.createPasswordEncoder());
            }
            when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-2"))
                    .thenReturn(targetDirectory);
        }

        @Test
        void creatingAShareLinkHashesThePasswordBeforeStorage() throws Exception {
            controller.createShareLink(request, model, "dir-2", "gallery-pw", null);

            var captor = org.mockito.ArgumentCaptor.forClass(ShareLink.class);
            verify(weblogger.getShareLinkManager()).createShareLink(captor.capture());
            ShareLink stored = captor.getValue();
            assertEquals(ShareLink.TYPE_DIRECTORY, stored.getTargetType());
            assertEquals("dir-2", stored.getTargetId());
            assertEquals(43, stored.getToken().length(),
                    "the token must come from TokenGenerator, not an entity id");
            assertNotNull(stored.getPasswordHash());
            // the test config runs passwds.encryption.enabled=false (noop),
            // so assert the *shape*: the value went through the configured
            // DelegatingPasswordEncoder (algorithm-id prefix) and verifies
            // via matches(); production config delegates to bcrypt
            assertTrue(stored.getPasswordHash().startsWith("{"),
                    "the stored value must be DelegatingPasswordEncoder output, "
                            + "not raw input: " + stored.getPasswordHash());
            assertTrue(RollerContext.getPasswordEncoder()
                            .matches("gallery-pw", stored.getPasswordHash()),
                    "the stored hash must verify against the chosen password");
            assertTrue(messages(model).contains("shareLink.created"));
        }

        @Test
        void creatingAShareLinkWithoutAPasswordStoresNoHash() throws Exception {
            controller.createShareLink(request, model, "dir-2", "  ", null);

            var captor = org.mockito.ArgumentCaptor.forClass(ShareLink.class);
            verify(weblogger.getShareLinkManager()).createShareLink(captor.capture());
            assertNull(captor.getValue().getPasswordHash(),
                    "a blank password means an ungated link, not a hash of blanks");
        }

        @Test
        void reSharingReplacesTheOldLink() throws Exception {
            ShareLink old = shareLink("old-token-dir2", "dir-2");
            when(weblogger.getShareLinkManager()
                    .getShareLinkForTarget(ShareLink.TYPE_DIRECTORY, "dir-2")).thenReturn(old);

            controller.createShareLink(request, model, "dir-2", null, null);

            verify(weblogger.getShareLinkManager()).removeShareLink(old);
            verify(weblogger.getShareLinkManager()).createShareLink(any());
        }

        @Test
        void aDirectoryFromAnotherWeblogCannotBeShared() throws Exception {
            MediaFileDirectory foreign = directory("dir-x", "theirs");
            org.apache.roller.weblogger.pojos.Weblog other =
                    new org.apache.roller.weblogger.pojos.Weblog();
            other.setId("weblog-2");
            other.setHandle("otherblog");
            foreign.setWeblog(other);
            when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-x"))
                    .thenReturn(foreign);

            controller.createShareLink(request, model, "dir-x", null, null);

            verify(weblogger.getShareLinkManager(), never()).createShareLink(any());
            assertTrue(errors(model).contains("shareLink.error"),
                    "a cross-weblog directoryId must be refused: " + errors(model));
        }

        @Test
        void revokingRemovesTheLinkAndConfirms() throws Exception {
            ShareLink link = shareLink("revoke-token-dir2", "dir-2");
            when(weblogger.getShareLinkManager()
                    .getShareLinkForTarget(ShareLink.TYPE_DIRECTORY, "dir-2")).thenReturn(link);

            controller.revokeShareLink(request, model, "dir-2", null);

            verify(weblogger.getShareLinkManager()).removeShareLink(link);
            assertTrue(messages(model).contains("shareLink.revoked"));
        }

        @Test
        void revokingWithNoLinkReportsAnError() throws Exception {
            controller.revokeShareLink(request, model, "dir-2", null);

            verify(weblogger.getShareLinkManager(), never()).removeShareLink(any());
            assertTrue(errors(model).contains("shareLink.error"));
        }

        @Test
        void togglingPrivacyFlipsTheFlagAndConfirms() throws Exception {
            assertTrue(!targetDirectory.isPrivate());

            controller.togglePrivate(request, model, "dir-2", null);
            assertTrue(targetDirectory.isPrivate(),
                    "the toggle must flip a public directory to private");
            assertTrue(messages(model).contains("shareLink.updated"));

            controller.togglePrivate(request, model, "dir-2", null);
            assertTrue(!targetDirectory.isPrivate(),
                    "a second toggle must flip it back");
        }

        @Test
        void theShareUrlIsExposedToTheView() throws Exception {
            WebloggerRuntimeConfig.setAbsoluteContextURL("http://localhost:8080/roller");
            ShareLink link = shareLink("view-token-dir1", "dir-1");
            when(weblogger.getShareLinkManager()
                    .getShareLinkForTarget(ShareLink.TYPE_DIRECTORY, "dir-1")).thenReturn(link);

            controller.execute(request, model, "dir-1", null, null);

            assertEquals(link, model.getAttribute("directoryShareLink"));
            assertEquals("http://localhost:8080/roller/share/view-token-dir1",
                    model.getAttribute("directoryShareURL"),
                    "the JSP copies this URL verbatim; it must be absolute");
        }

        private ShareLink shareLink(String token, String directoryId) {
            ShareLink link = new ShareLink();
            link.setWeblog(weblog);
            link.setTargetType(ShareLink.TYPE_DIRECTORY);
            link.setTargetId(directoryId);
            link.setToken(token);
            return link;
        }
    }

    // --- helpers ---

    private MediaFileDirectory directory(String id, String name) {
        MediaFileDirectory dir = new MediaFileDirectory();
        dir.setId(id);
        dir.setName(name);
        dir.setWeblog(weblog);
        Set<MediaFile> files = new LinkedHashSet<>(Collections.emptySet());
        dir.setMediaFiles(files);
        return dir;
    }

    private MediaFile mediaFile(String id, String name) {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setName(name);
        file.setWeblog(weblog);
        file.setContentType("image/jpeg");
        return file;
    }
}
