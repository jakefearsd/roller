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

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.FileIOException;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * Edits metadata for a media file.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class MediaFileEditController extends MediaFileBase {

    private static final Log log = LogFactory.getLog(MediaFileEditController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "mediaFileEdit";
    }

    @Override
    public String getPageTitle() {
        return "mediaFile.edit.title";
    }

    @GetMapping("/mediaFileEdit.rol")
    public String execute(HttpServletRequest request, Model model,
                          @ModelAttribute("bean") MediaFileBean bean,
                          @RequestParam(value = "mediaFileId", required = false) String mediaFileId) {
        populateCommonModel(request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));

        MediaFileManager manager = weblogger.getMediaFileManager();
        try {
            MediaFile mediaFile = manager.getMediaFile(mediaFileId);
            bean.copyFrom(mediaFile);
            model.addAttribute("mediaFileId", mediaFileId);
        } catch (FileIOException ex) {
            addError(model, "uploadFiles.error.upload", bean.getName(), request);
        } catch (Exception e) {
            log.error("Error loading media file " + mediaFileId, e);
            addError(model, "uploadFiles.error.upload", bean.getName(), request);
        }

        return ".MediaFileEdit";
    }

    @PostMapping("/mediaFileEdit!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @ModelAttribute("bean") MediaFileBean bean,
                       @RequestParam(value = "mediaFileId", required = false) String mediaFileId,
                       @RequestParam(value = "uploadedFile", required = false) MultipartFile uploadedFile) {
        populateCommonModel(request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        model.addAttribute("mediaFileId", mediaFileId);

        // resolve directory for validation
        MediaFileDirectory directory = null;
        try {
            MediaFileManager mgr = weblogger.getMediaFileManager();
            if (!StringUtils.isEmpty(bean.getDirectoryId())) {
                directory = mgr.getMediaFileDirectory(bean.getDirectoryId());
            }
        } catch (WebloggerException ex) {
            log.error("Error looking up media file directory", ex);
        }

        // validate
        if (directory != null) {
            MediaFile fileWithSameName = directory.getMediaFile(bean.getName());
            if (fileWithSameName != null && !fileWithSameName.getId().equals(mediaFileId)) {
                addError(model, "MediaFile.error.duplicateName", bean.getName(), request);
            }
        }

        if (!hasErrors(model)) {
            MediaFileManager manager = weblogger.getMediaFileManager();
            try {
                MediaFile mediaFile = manager.getMediaFile(mediaFileId);
                bean.copyTo(mediaFile);

                if (uploadedFile != null && !uploadedFile.isEmpty()) {
                    mediaFile.setLength(uploadedFile.getSize());
                    mediaFile.setContentType(uploadedFile.getContentType());
                    manager.updateMediaFile(getActionWeblog(request), mediaFile, uploadedFile.getInputStream());
                } else {
                    manager.updateMediaFile(getActionWeblog(request), mediaFile);
                }

                // Move file if directory changed
                if (!bean.getDirectoryId().equals(mediaFile.getDirectory().getId())) {
                    doMoveSelected(new String[]{mediaFile.getId()}, bean.getDirectoryId(), request, model);
                }

                weblogger.flush();
                addMessage(model, "mediaFile.update.success", request);
                return ".MediaFileEditSuccess";

            } catch (FileIOException ex) {
                addError(model, "uploadFiles.error.upload", bean.getName(), request);
            } catch (Exception e) {
                log.error("Error uploading file " + bean.getName(), e);
                addError(model, "uploadFiles.error.upload", bean.getName(), request);
            }
        }

        return ".MediaFileEdit";
    }

    /**
     * Destructively crops the image to the given rectangle (measured on the
     * displayed, orientation-corrected image) and regenerates everything
     * derived from its pixels. The confirmation happens client-side; by the
     * time this action runs the decision has been made.
     */
    @PostMapping("/mediaFileEdit!crop.rol")
    public String crop(HttpServletRequest request, Model model,
                       @ModelAttribute("bean") MediaFileBean bean,
                       @RequestParam("mediaFileId") String mediaFileId,
                       @RequestParam("cropX") int cropX,
                       @RequestParam("cropY") int cropY,
                       @RequestParam("cropWidth") int cropWidth,
                       @RequestParam("cropHeight") int cropHeight) {
        populateCommonModel(request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        model.addAttribute("mediaFileId", mediaFileId);

        MediaFileManager manager = weblogger.getMediaFileManager();
        try {
            MediaFile mediaFile = manager.getMediaFile(mediaFileId);
            // Ownership check BEFORE anything else: getMediaFile is a global
            // by-id lookup, and a foreign id must not even leak the file's
            // metadata into this weblog's edit form, let alone reach the
            // manager. (The manager enforces the same boundary again.)
            if (mediaFile == null || mediaFile.getDirectory() == null
                    || !getActionWeblog(request).getId()
                            .equals(mediaFile.getDirectory().getWeblog().getId())) {
                log.warn("Refusing to crop media file " + mediaFileId
                        + ": not owned by weblog " + getActionWeblog(request).getHandle());
                addError(model, "mediaFileEdit.crop.error", request);
                return ".MediaFileEdit";
            }
            // populate the bean up front so the re-rendered form is complete
            // even when the crop itself fails
            bean.copyFrom(mediaFile);

            manager.cropMediaFile(getActionWeblog(request), mediaFile,
                    cropX, cropY, cropWidth, cropHeight);
            weblogger.flush();

            // re-read so the page shows the new dimensions and size
            bean.copyFrom(mediaFile);
            addMessage(model, "mediaFileEdit.crop.success", request);
        } catch (Exception e) {
            log.error("Error cropping media file " + mediaFileId, e);
            addError(model, "mediaFileEdit.crop.error", request);
        }

        return ".MediaFileEdit";
    }

    @ModelAttribute("bean")
    public MediaFileBean getBean() {
        return new MediaFileBean();
    }
}
