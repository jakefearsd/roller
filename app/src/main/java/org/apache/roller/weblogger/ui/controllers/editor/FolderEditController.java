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
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.BookmarkManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.WeblogBookmarkFolder;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Edit a new or existing folder.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class FolderEditController extends BaseController {

    private static final Log log = LogFactory.getLog(FolderEditController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    // --- folderAdd ---

    @GetMapping("/folderAdd.rol")
    public String folderAddExecute(HttpServletRequest request, Model model,
                                   @ModelAttribute("bean") FolderBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "folderAdd");
        model.addAttribute("pageTitle", getText("folderForm.add.title", request));
        return ".FolderEdit";
    }

    @PostMapping("/folderAdd!save.rol")
    public String folderAddSave(HttpServletRequest request, HttpServletResponse response, Model model,
                                @ModelAttribute("bean") FolderBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "folderAdd");
        model.addAttribute("pageTitle", getText("folderForm.add.title", request));

        if (getActionWeblog(request).hasBookmarkFolder(bean.getName())) {
            addError(model, "folderForm.error.duplicateName", bean.getName(), request);
            return ".FolderEdit";
        }

        try {
            WeblogBookmarkFolder folder = new WeblogBookmarkFolder();
            folder.setWeblog(getActionWeblog(request));
            bean.copyTo(folder);

            BookmarkManager bmgr = WebloggerFactory.getWeblogger().getBookmarkManager();
            bmgr.saveFolder(folder);
            WebloggerFactory.getWeblogger().flush();

            CacheManager.invalidate(folder);

            String folderId = folder.getId();
            String sanitizedFolderId = folderId.replace("\n", "").replace("\r", "");
            response.addHeader("folderId", sanitizedFolderId);

            return "redirect:/roller-ui/authoring/bookmarks!folderCreated.rol?weblog="
                    + getActionWeblog(request).getHandle() + "&folderId=" + folderId;
        } catch (Exception ex) {
            log.error("Error saving folder", ex);
            addError(model, "generic.error.check.logs", request);
        }

        return ".FolderEdit";
    }

    // --- folderEdit ---

    @GetMapping("/folderEdit.rol")
    public String folderEditExecute(HttpServletRequest request, Model model,
                                    @ModelAttribute("bean") FolderBean bean,
                                    @RequestParam(value = "folderId", required = false) String folderId) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "folderEdit");
        model.addAttribute("pageTitle", getText("folderForm.edit.title", request));
        model.addAttribute("folderId", folderId);

        try {
            BookmarkManager bmgr = WebloggerFactory.getWeblogger().getBookmarkManager();
            WeblogBookmarkFolder folder = bmgr.getFolder(bean.getId());
            if (folder != null) {
                bean.copyFrom(folder);
            }
        } catch (WebloggerException ex) {
            log.error("Error looking up folder", ex);
        }

        return ".FolderEdit";
    }

    @PostMapping("/folderEdit!save.rol")
    public String folderEditSave(HttpServletRequest request, HttpServletResponse response, Model model,
                                 @ModelAttribute("bean") FolderBean bean,
                                 @RequestParam(value = "folderId", required = false) String folderId,
                                 RedirectAttributes redirectAttributes) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "folderEdit");
        model.addAttribute("pageTitle", getText("folderForm.edit.title", request));
        model.addAttribute("folderId", folderId);

        try {
            BookmarkManager bmgr = WebloggerFactory.getWeblogger().getBookmarkManager();
            WeblogBookmarkFolder folder = bmgr.getFolder(bean.getId());

            if (!bean.getName().equals(folder.getName())
                    && folder.getWeblog().hasBookmarkFolder(bean.getName())) {
                addError(model, "folderForm.error.duplicateName", bean.getName(), request);
                return ".FolderEdit";
            }

            bean.copyTo(folder);
            bmgr.saveFolder(folder);
            WebloggerFactory.getWeblogger().flush();

            CacheManager.invalidate(folder);
            addFlashMessage(redirectAttributes, "folderForm.updated", request);

            String sanitizedFolderId = folderId != null ? folderId.replace("\n", "").replace("\r", "") : "";
            response.addHeader("folderId", sanitizedFolderId);

            return "redirect:/roller-ui/authoring/bookmarks.rol?weblog="
                    + getActionWeblog(request).getHandle() + "&folderId=" + folderId;
        } catch (Exception ex) {
            log.error("Error saving folder", ex);
            addError(model, "generic.error.check.logs", request);
        }

        return ".FolderEdit";
    }

    @ModelAttribute("bean")
    public FolderBean getBean() {
        return new FolderBean();
    }
}
