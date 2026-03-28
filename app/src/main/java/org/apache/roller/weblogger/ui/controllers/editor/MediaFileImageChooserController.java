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
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileComparator;
import org.apache.roller.weblogger.pojos.MediaFileComparator.MediaFileComparatorType;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.MediaFileDirectoryComparator;
import org.apache.roller.weblogger.pojos.MediaFileDirectoryComparator.DirectoryComparatorType;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.struts2.util.KeyValueObject;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Browse media files action (overlay mode).
 */
@Controller
@RequestMapping("/roller-ui/authoring/overlay")
public class MediaFileImageChooserController extends MediaFileBase {

    private static final Log log = LogFactory.getLog(MediaFileImageChooserController.class);

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.singletonList(WeblogPermission.EDIT_DRAFT);
    }

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "mediaFileImageChooser";
    }

    @Override
    public String getPageTitle() {
        return "mediaFileImaegChooser.title";
    }

    @GetMapping("/mediaFileImageChooser.rol")
    public String execute(HttpServletRequest request, Model model,
                          @RequestParam(value = "directoryId", required = false) String directoryId,
                          @RequestParam(value = "directoryName", required = false) String directoryName) {
        populateCommonModel(request, model);
        model.addAttribute("overlayMode", true);

        MediaFileManager manager = WebloggerFactory.getWeblogger().getMediaFileManager();
        try {
            MediaFileDirectory directory;
            if (directoryId != null) {
                directory = manager.getMediaFileDirectory(directoryId);
            } else if (directoryName != null) {
                directory = manager.getMediaFileDirectoryByName(getActionWeblog(request), directoryName);
                directoryId = directory.getId();
            } else {
                directory = manager.getDefaultMediaFileDirectory(getActionWeblog(request));
                directoryId = directory.getId();
            }

            List<MediaFile> childFiles = new ArrayList<>(directory.getMediaFiles());
            childFiles.sort(new MediaFileComparator(MediaFileComparatorType.NAME));

            model.addAttribute("childFiles", childFiles);
            model.addAttribute("currentDirectory", directory);
            model.addAttribute("directoryId", directoryId);

            // List of available directories
            List<MediaFileDirectory> sortedDirList = new ArrayList<>();
            List<MediaFileDirectory> directories = manager.getMediaFileDirectories(getActionWeblog(request));
            for (MediaFileDirectory mediaFileDirectory : directories) {
                if (!"default".equals(mediaFileDirectory.getName()) || !"default".equals(directory.getName())) {
                    sortedDirList.add(mediaFileDirectory);
                }
            }
            sortedDirList.sort(new MediaFileDirectoryComparator(DirectoryComparatorType.NAME));
            model.addAttribute("allDirectories", sortedDirList);

            // build hierarchy
            List<KeyValueObject> directoryHierarchy = new ArrayList<>();
            String fullPath = "/" + directory.getName();
            if (fullPath.length() > 1) {
                String[] directoryNames = fullPath.substring(1).split("/");
                String dirPath = "";
                for (String dirName : directoryNames) {
                    dirPath = dirPath + "/" + dirName;
                    directoryHierarchy.add(new KeyValueObject(dirPath, dirName));
                }
            }
            model.addAttribute("currentDirectoryHierarchy", directoryHierarchy);

        } catch (Exception ex) {
            log.error("Error viewing media file directory", ex);
            addError(model, "MediaFile.error.view", request);
        }

        return ".MediaFileImageChooser";
    }
}
