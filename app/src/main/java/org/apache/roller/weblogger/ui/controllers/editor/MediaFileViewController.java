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
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.FileIOException;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileComparator;
import org.apache.roller.weblogger.pojos.MediaFileComparator.MediaFileComparatorType;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.MediaFileFilter;
import org.apache.roller.weblogger.ui.struts2.pagers.MediaFilePager;
import org.apache.roller.weblogger.ui.struts2.util.KeyValueObject;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * View media files.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class MediaFileViewController extends MediaFileBase {

    private static final Log log = LogFactory.getLog(MediaFileViewController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "mediaFileView";
    }

    @Override
    public String getPageTitle() {
        return "mediaFileView.title";
    }

    @GetMapping("/mediaFileView.rol")
    public String execute(HttpServletRequest request, Model model,
                          @RequestParam(value = "directoryId", required = false) String directoryId,
                          @RequestParam(value = "directoryName", required = false) String directoryName,
                          @RequestParam(value = "sortBy", required = false) String sortBy) {
        populateCommonModel(request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        loadDropdowns(request, model);
        loadDirectory(request, model, directoryId, directoryName, sortBy);
        return ".MediaFileView";
    }

    @PostMapping("/mediaFileView!createNewDirectory.rol")
    public String createNewDirectory(HttpServletRequest request, Model model,
                                     @RequestParam(value = "directoryId", required = false) String directoryId,
                                     @RequestParam(value = "newDirectoryName", required = false) String newDirectoryName,
                                     @RequestParam(value = "sortBy", required = false) String sortBy) {
        populateCommonModel(request, model);

        boolean dirCreated = false;
        if (StringUtils.isEmpty(newDirectoryName)) {
            addError(model, "mediaFile.error.view.dirNameEmpty", request);
        } else if (newDirectoryName.contains("/")) {
            addError(model, "mediaFile.error.view.dirNameInvalid", request);
        } else {
            try {
                MediaFileManager manager = WebloggerFactory.getWeblogger().getMediaFileManager();
                if (!getActionWeblog(request).hasMediaFileDirectory(newDirectoryName)) {
                    MediaFileDirectory dir = manager.createMediaFileDirectory(getActionWeblog(request), newDirectoryName);
                    WebloggerFactory.getWeblogger().flush();
                    addMessage(model, "mediaFile.directoryCreate.success", newDirectoryName, request);
                    directoryId = dir.getId();
                    dirCreated = true;
                } else {
                    addMessage(model, "mediaFile.directoryCreate.error.exists", newDirectoryName, request);
                }
            } catch (WebloggerException e) {
                log.error("Error creating new directory", e);
                addError(model, "Error creating new directory", request);
            }
        }

        if (dirCreated) {
            model.addAttribute("allDirectories", refreshAllDirectories(request));
        } else {
            model.addAttribute("allDirectories", refreshAllDirectories(request));
        }
        loadDropdowns(request, model);
        loadDirectory(request, model, directoryId, null, sortBy);
        return ".MediaFileView";
    }

    @GetMapping("/mediaFileView!fetchDirectoryContentLight.rol")
    public String fetchDirectoryContentLight(HttpServletRequest request, Model model,
                                             @RequestParam(value = "directoryId", required = false) String directoryId,
                                             @RequestParam(value = "directoryName", required = false) String directoryName,
                                             @RequestParam(value = "sortBy", required = false) String sortBy) {
        populateCommonModel(request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        loadDirectory(request, model, directoryId, directoryName, sortBy);
        return "/WEB-INF/jsps/editor/MediaFileViewLight";
    }

    @PostMapping("/mediaFileView!deleteSelected.rol")
    public String deleteSelected(HttpServletRequest request, Model model,
                                 @RequestParam(value = "directoryId", required = false) String directoryId,
                                 @RequestParam(value = "selectedMediaFiles", required = false) String[] selectedMediaFiles,
                                 @RequestParam(value = "sortBy", required = false) String sortBy) {
        populateCommonModel(request, model);
        doDeleteSelected(selectedMediaFiles, request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        loadDropdowns(request, model);
        loadDirectory(request, model, directoryId, null, sortBy);
        return ".MediaFileView";
    }

    @PostMapping("/mediaFileView!delete.rol")
    public String delete(HttpServletRequest request, Model model,
                         @RequestParam(value = "directoryId", required = false) String directoryId,
                         @RequestParam(value = "mediaFileId", required = false) String mediaFileId,
                         @RequestParam(value = "sortBy", required = false) String sortBy) {
        populateCommonModel(request, model);
        doDeleteMediaFile(mediaFileId, request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        loadDropdowns(request, model);
        loadDirectory(request, model, directoryId, null, sortBy);
        return ".MediaFileView";
    }

    @PostMapping("/mediaFileView!deleteFolder.rol")
    public String deleteFolder(HttpServletRequest request, Model model,
                               @RequestParam(value = "directoryId", required = false) String directoryId,
                               @RequestParam(value = "directoryName", required = false) String directoryName,
                               @RequestParam(value = "sortBy", required = false) String sortBy) {
        populateCommonModel(request, model);

        try {
            MediaFileManager manager = WebloggerFactory.getWeblogger().getMediaFileManager();
            if (directoryId != null) {
                MediaFileDirectory mediaFileDir = manager.getMediaFileDirectory(directoryId);
                manager.removeMediaFileDirectory(mediaFileDir);
                WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(getActionWeblog(request));
                WebloggerFactory.getWeblogger().flush();
                WebloggerFactory.getWeblogger().release();
                addMessage(model, "mediaFile.deleteFolder.success", request);
                CacheManager.invalidate(getActionWeblog(request));

                mediaFileDir = manager.getDefaultMediaFileDirectory(getActionWeblog(request));
                directoryId = mediaFileDir.getId();
            }
        } catch (WebloggerException ex) {
            log.error("Error deleting folder", ex);
        }

        model.addAttribute("allDirectories", refreshAllDirectories(request));
        loadDropdowns(request, model);
        loadDirectory(request, model, directoryId, null, sortBy);
        return ".MediaFileView";
    }

    @PostMapping("/mediaFileView!includeInGallery.rol")
    public String includeInGallery(HttpServletRequest request, Model model,
                                   @RequestParam(value = "directoryId", required = false) String directoryId,
                                   @RequestParam(value = "mediaFileId", required = false) String mediaFileId,
                                   @RequestParam(value = "sortBy", required = false) String sortBy) {
        populateCommonModel(request, model);
        doIncludeMediaFileInGallery(mediaFileId, request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        loadDropdowns(request, model);
        loadDirectory(request, model, directoryId, null, sortBy);
        return ".MediaFileView";
    }

    @PostMapping("/mediaFileView!moveSelected.rol")
    public String moveSelected(HttpServletRequest request, Model model,
                               @RequestParam(value = "directoryId", required = false) String directoryId,
                               @RequestParam(value = "selectedMediaFiles", required = false) String[] selectedMediaFiles,
                               @RequestParam(value = "selectedDirectory", required = false) String selectedDirectory,
                               @RequestParam(value = "sortBy", required = false) String sortBy) {
        populateCommonModel(request, model);
        doMoveSelected(selectedMediaFiles, selectedDirectory, request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        loadDropdowns(request, model);
        loadDirectory(request, model, directoryId, null, sortBy);
        return ".MediaFileView";
    }

    @PostMapping("/mediaFileView!search.rol")
    public String search(HttpServletRequest request, Model model,
                         @ModelAttribute("bean") MediaFileSearchBean bean,
                         @RequestParam(value = "directoryId", required = false) String directoryId,
                         @RequestParam(value = "sortBy", required = false) String sortBy) {
        populateCommonModel(request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        loadDropdowns(request, model);
        loadDirectory(request, model, directoryId, null, sortBy);

        if (StringUtils.isEmpty(bean.getName()) && StringUtils.isEmpty(bean.getTags())
                && StringUtils.isEmpty(bean.getType()) && bean.getSize() == 0) {
            addError(model, "MediaFile.error.search.empty", request);
            return ".MediaFileView";
        }

        MediaFileFilter filter = new MediaFileFilter();
        bean.copyTo(filter);
        MediaFileManager manager = WebloggerFactory.getWeblogger().getMediaFileManager();
        try {
            List<MediaFile> rawResults = manager.searchMediaFiles(getActionWeblog(request), filter);
            boolean hasMore = false;
            List<MediaFile> results = new ArrayList<>(rawResults);
            if (results.size() > MediaFileSearchBean.PAGE_SIZE) {
                results.remove(results.size() - 1);
                hasMore = true;
            }
            model.addAttribute("pager", new MediaFilePager(bean.getPageNum(), results, hasMore));
        } catch (Exception e) {
            log.error("Error applying search criteria", e);
            addError(model, "Error applying search criteria - check Roller logs", request);
        }

        return ".MediaFileView";
    }

    @PostMapping("/mediaFileView!view.rol")
    public String view(HttpServletRequest request, Model model,
                       @RequestParam(value = "viewDirectoryId", required = false) String viewDirectoryId,
                       @RequestParam(value = "sortBy", required = false) String sortBy) {
        populateCommonModel(request, model);
        model.addAttribute("allDirectories", refreshAllDirectories(request));
        loadDropdowns(request, model);
        loadDirectory(request, model, viewDirectoryId, null, sortBy);
        return ".MediaFileView";
    }

    private void loadDirectory(HttpServletRequest request, Model model,
                               String directoryId, String directoryName, String sortBy) {
        MediaFileManager manager = WebloggerFactory.getWeblogger().getMediaFileManager();
        try {
            MediaFileDirectory directory;
            if (StringUtils.isNotEmpty(directoryId)) {
                directory = manager.getMediaFileDirectory(directoryId);
            } else if (StringUtils.isNotEmpty(directoryName)) {
                directory = manager.getMediaFileDirectoryByName(getActionWeblog(request), directoryName);
            } else {
                directory = manager.getDefaultMediaFileDirectory(getActionWeblog(request));
            }

            if (directory != null) {
                List<MediaFile> childFiles = new ArrayList<>(directory.getMediaFiles());

                if ("type".equals(sortBy)) {
                    childFiles.sort(new MediaFileComparator(MediaFileComparatorType.TYPE));
                } else if ("date_uploaded".equals(sortBy)) {
                    childFiles.sort(new MediaFileComparator(MediaFileComparatorType.DATE_UPLOADED));
                } else {
                    sortBy = "name";
                    childFiles.sort(new MediaFileComparator(MediaFileComparatorType.NAME));
                }

                model.addAttribute("childFiles", childFiles);
                model.addAttribute("currentDirectory", directory);
                model.addAttribute("directoryId", directory.getId());
                model.addAttribute("directoryName", directory.getName());
                model.addAttribute("viewDirectoryId", directory.getId());
            }
            model.addAttribute("sortBy", sortBy);

        } catch (Exception ex) {
            log.error("Error viewing media file directory", ex);
            addError(model, "MediaFile.error.view", request);
        }
    }

    private void loadDropdowns(HttpServletRequest request, Model model) {
        model.addAttribute("fileTypes", getFileTypes(request));
        model.addAttribute("sizeFilterTypes", getSizeFilterTypes(request));
        model.addAttribute("sizeUnits", getSizeUnits(request));
        model.addAttribute("sortOptions", getSortOptions(request));
    }

    private List<KeyValueObject> getFileTypes(HttpServletRequest request) {
        List<KeyValueObject> list = new ArrayList<>();
        list.add(new KeyValueObject("mediaFileView.any", getText("mediaFileView.any", request)));
        list.add(new KeyValueObject("mediaFileView.others", getText("mediaFileView.others", request)));
        list.add(new KeyValueObject("mediaFileView.image", getText("mediaFileView.image", request)));
        list.add(new KeyValueObject("mediaFileView.video", getText("mediaFileView.video", request)));
        list.add(new KeyValueObject("mediaFileView.audio", getText("mediaFileView.audio", request)));
        return list;
    }

    private List<KeyValueObject> getSizeFilterTypes(HttpServletRequest request) {
        List<KeyValueObject> list = new ArrayList<>();
        list.add(new KeyValueObject("mediaFileView.gt", getText("mediaFileView.gt", request)));
        list.add(new KeyValueObject("mediaFileView.ge", getText("mediaFileView.ge", request)));
        list.add(new KeyValueObject("mediaFileView.eq", getText("mediaFileView.eq", request)));
        list.add(new KeyValueObject("mediaFileView.le", getText("mediaFileView.le", request)));
        list.add(new KeyValueObject("mediaFileView.lt", getText("mediaFileView.lt", request)));
        return list;
    }

    private List<KeyValueObject> getSizeUnits(HttpServletRequest request) {
        List<KeyValueObject> list = new ArrayList<>();
        list.add(new KeyValueObject("mediaFileView.bytes", getText("mediaFileView.bytes", request)));
        list.add(new KeyValueObject("mediaFileView.kb", getText("mediaFileView.kb", request)));
        list.add(new KeyValueObject("mediaFileView.mb", getText("mediaFileView.mb", request)));
        return list;
    }

    private List<KeyValueObject> getSortOptions(HttpServletRequest request) {
        List<KeyValueObject> list = new ArrayList<>();
        list.add(new KeyValueObject("name", getText("generic.name", request)));
        list.add(new KeyValueObject("date_uploaded", getText("mediaFileView.date", request)));
        list.add(new KeyValueObject("type", getText("mediaFileView.type", request)));
        return list;
    }

    @ModelAttribute("bean")
    public MediaFileSearchBean getSearchBean() {
        return new MediaFileSearchBean();
    }
}
