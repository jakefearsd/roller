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
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.util.RollerMessages;
import org.apache.roller.weblogger.util.RollerMessages.RollerMessage;
import org.apache.roller.weblogger.util.Utilities;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * Adds a new media file.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class MediaFileAddController extends MediaFileBase {

    private static final Log log = LogFactory.getLog(MediaFileAddController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "mediaFileAdd";
    }

    @Override
    public String getPageTitle() {
        return "mediaFileAdd.title";
    }

    @GetMapping("/mediaFileAdd.rol")
    public String execute(HttpServletRequest request, Model model,
                          @ModelAttribute("bean") MediaFileBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        resolveDirectory(request, model, bean);
        return ".MediaFileAdd";
    }

    @PostMapping("/mediaFileAdd!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @ModelAttribute("bean") MediaFileBean bean,
                       @RequestParam(value = "uploadedFiles", required = false) MultipartFile[] uploadedFiles) {
        populateCommonModel(request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        MediaFileDirectory directory = resolveDirectory(request, model, bean);

        // validate
        if (!WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled")) {
            addError(model, "error.upload.disabled", request);
            return ".MediaFileAdd";
        }

        if (!hasErrors(model) && uploadedFiles != null && uploadedFiles.length > 0) {
            MediaFileManager manager = WebloggerFactory.getWeblogger().getMediaFileManager();
            RollerMessages errors = new RollerMessages();
            List<MediaFile> uploaded = new ArrayList<>();
            List<MediaFile> newImages = new ArrayList<>();
            List<MediaFile> newFiles = new ArrayList<>();

            for (MultipartFile uploadedFile : uploadedFiles) {
                if (uploadedFile == null || uploadedFile.isEmpty()) {
                    continue;
                }

                try {
                    MediaFile mediaFile = new MediaFile();
                    bean.copyTo(mediaFile);

                    String fileName = uploadedFile.getOriginalFilename();
                    if (fileName != null) {
                        int terminated = fileName.indexOf('\000');
                        if (terminated != -1) {
                            fileName = fileName.substring(0, terminated).trim();
                        }
                        if (fileName.indexOf('/') != -1 || fileName.indexOf('\\') != -1 || fileName.contains("..")) {
                            addError(model, "uploadFiles.error.badPath", fileName, request);
                            continue;
                        }
                    }

                    mediaFile.setName(fileName);
                    mediaFile.setDirectory(directory);
                    mediaFile.setWeblog(getActionWeblog(request));
                    mediaFile.setLength(uploadedFile.getSize());
                    mediaFile.setInputStream(uploadedFile.getInputStream());

                    String contentType = uploadedFile.getContentType();
                    if (contentType == null || contentType.endsWith("/octet-stream")) {
                        String ctype = Utilities.getContentTypeFromFileName(mediaFile.getName());
                        if (ctype != null) {
                            contentType = ctype;
                        }
                    }
                    mediaFile.setContentType(contentType);

                    manager.createMediaFile(getActionWeblog(request), mediaFile, errors);
                    WebloggerFactory.getWeblogger().flush();

                    if (mediaFile.isImageFile()) {
                        newImages.add(mediaFile);
                    } else {
                        newFiles.add(mediaFile);
                    }
                    uploaded.add(mediaFile);

                } catch (Exception e) {
                    log.error("Error uploading media file", e);
                    addError(model, "mediaFileAdd.errorUploading", bean.getName(), request);
                }
            }

            for (Iterator<RollerMessage> it = errors.getErrors(); it.hasNext();) {
                RollerMessage msg = it.next();
                addError(model, msg.getKey(), request);
            }

            if (!uploaded.isEmpty() && !hasErrors(model)) {
                addMessage(model, "uploadFiles.uploadedFiles", request);
                for (MediaFile upload : uploaded) {
                    addMessage(model, "uploadFiles.uploadedFile", upload.getPermalink(), request);
                }

                model.addAttribute("newImages", newImages);
                model.addAttribute("newFiles", newFiles);
                return ".MediaFileAddSuccess";
            }
        }

        return ".MediaFileAdd";
    }

    private MediaFileDirectory resolveDirectory(HttpServletRequest request, Model model, MediaFileBean bean) {
        try {
            MediaFileManager mgr = WebloggerFactory.getWeblogger().getMediaFileManager();
            MediaFileDirectory directory;

            if (!StringUtils.isEmpty(bean.getDirectoryId())) {
                directory = mgr.getMediaFileDirectory(bean.getDirectoryId());
            } else {
                String directoryName = request.getParameter("directoryName");
                if (StringUtils.isNotEmpty(directoryName)) {
                    directory = mgr.getMediaFileDirectoryByName(getActionWeblog(request), directoryName);
                } else {
                    directory = mgr.getDefaultMediaFileDirectory(getActionWeblog(request));
                    if (directory == null) {
                        directory = mgr.createDefaultMediaFileDirectory(getActionWeblog(request));
                    }
                }
            }

            if (directory != null) {
                bean.setDirectoryId(directory.getId());
                model.addAttribute("directory", directory);
            }
            WebloggerFactory.getWeblogger().flush();
            return directory;

        } catch (WebloggerException ex) {
            log.error("Error looking up media file directory", ex);
        }
        return null;
    }

    @ModelAttribute("bean")
    public MediaFileBean getBean() {
        return new MediaFileBean();
    }
}
