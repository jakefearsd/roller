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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.MediaFileDirectoryComparator;
import org.apache.roller.weblogger.pojos.MediaFileDirectoryComparator.DirectoryComparatorType;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.springframework.ui.Model;

/**
 * Base class for all controllers related to media files.
 */
public abstract class MediaFileBase extends BaseController {

    private static final Log log = LogFactory.getLog(MediaFileBase.class);

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.singletonList(WeblogPermission.POST);
    }

    /**
     * Deletes media file.
     */
    protected void doDeleteMediaFile(String mediaFileId, HttpServletRequest request, Model model) {
        try {
            log.debug("Processing delete of file id - " + mediaFileId);
            MediaFileManager manager = WebloggerFactory.getWeblogger().getMediaFileManager();
            MediaFile mediaFile = manager.getMediaFile(mediaFileId);
            manager.removeMediaFile(getActionWeblog(request), mediaFile);
            WebloggerFactory.getWeblogger().flush();
            WebloggerFactory.getWeblogger().release();
            addMessage(model, "mediaFile.delete.success", request);
        } catch (WebloggerException e) {
            log.error("Error deleting media file", e);
            addError(model, "mediaFile.delete.error", mediaFileId, request);
        }
    }

    /**
     * Shares media file for public gallery.
     */
    protected void doIncludeMediaFileInGallery(String mediaFileId, HttpServletRequest request, Model model) {
        try {
            log.debug("Processing include-in-gallery of file id - " + mediaFileId);
            MediaFileManager manager = WebloggerFactory.getWeblogger().getMediaFileManager();
            MediaFile mediaFile = manager.getMediaFile(mediaFileId);
            mediaFile.setSharedForGallery(true);
            manager.updateMediaFile(getActionWeblog(request), mediaFile);
            WebloggerFactory.getWeblogger().flush();
            addMessage(model, "mediaFile.includeInGallery.success", request);
        } catch (WebloggerException e) {
            log.error("Error including media file in gallery", e);
            addError(model, "mediaFile.includeInGallery.error", mediaFileId, request);
        }
    }

    /**
     * Delete selected media files.
     */
    protected void doDeleteSelected(String[] selectedMediaFiles, HttpServletRequest request, Model model) {
        try {
            MediaFileManager manager = WebloggerFactory.getWeblogger().getMediaFileManager();

            if (selectedMediaFiles != null && selectedMediaFiles.length > 0) {
                log.debug("Processing delete of " + selectedMediaFiles.length + " media files.");
                for (String fileId : selectedMediaFiles) {
                    log.debug("Deleting media file - " + fileId);
                    MediaFile mediaFile = manager.getMediaFile(fileId);
                    if (mediaFile != null) {
                        manager.removeMediaFile(getActionWeblog(request), mediaFile);
                    }
                }
            }

            WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(getActionWeblog(request));
            WebloggerFactory.getWeblogger().flush();
            WebloggerFactory.getWeblogger().release();
            addMessage(model, "mediaFile.delete.success", request);

        } catch (WebloggerException e) {
            log.error("Error deleting selected media files", e);
            addError(model, "mediaFile.delete.error", request);
        }
    }

    /**
     * Move selected media files to a directory.
     */
    protected void doMoveSelected(String[] selectedMediaFiles, String selectedDirectory,
                                  HttpServletRequest request, Model model) {
        try {
            int movedFiles = 0;
            MediaFileManager manager = WebloggerFactory.getWeblogger().getMediaFileManager();

            if (selectedMediaFiles != null && selectedMediaFiles.length > 0) {
                log.debug("Processing move of " + selectedMediaFiles.length + " media files.");
                MediaFileDirectory targetDirectory = manager.getMediaFileDirectory(selectedDirectory);
                for (String fileId : selectedMediaFiles) {
                    log.debug("Moving media file - " + fileId + " to directory - " + selectedDirectory);
                    MediaFile mediaFile = manager.getMediaFile(fileId);
                    if (mediaFile != null && !mediaFile.getDirectory().getId().equals(targetDirectory.getId())) {
                        manager.moveMediaFile(mediaFile, targetDirectory);
                        movedFiles++;
                    }
                }
            }

            WebloggerFactory.getWeblogger().flush();
            WebloggerFactory.getWeblogger().release();
            if (movedFiles > 0) {
                addMessage(model, "mediaFile.move.success", request);
            }

        } catch (WebloggerException e) {
            log.error("Error moving selected media files", e);
            addError(model, "mediaFile.move.errors", request);
        }
    }

    /**
     * Refresh the list of directories.
     */
    protected List<MediaFileDirectory> refreshAllDirectories(HttpServletRequest request) {
        try {
            MediaFileManager mgr = WebloggerFactory.getWeblogger().getMediaFileManager();
            List<MediaFileDirectory> directories = mgr.getMediaFileDirectories(getActionWeblog(request));
            List<MediaFileDirectory> sortedDirList = new ArrayList<>(directories);
            sortedDirList.sort(new MediaFileDirectoryComparator(DirectoryComparatorType.NAME));
            return sortedDirList;
        } catch (WebloggerException ex) {
            log.error("Error looking up media file directories", ex);
        }
        return Collections.emptyList();
    }
}
