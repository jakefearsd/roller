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
import org.apache.roller.weblogger.business.BookmarkManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.WeblogBookmark;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Edit a new or existing bookmark (blogroll item).
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class BookmarkEditController extends BaseController {

    private static final Log log = LogFactory.getLog(BookmarkEditController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    // --- bookmarkAdd ---

    @GetMapping("/bookmarkAdd.rol")
    public String bookmarkAddExecute(HttpServletRequest request, Model model,
                                     @ModelAttribute("bean") BookmarkBean bean,
                                     @RequestParam(value = "folderId", required = false) String folderId) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "bookmarkAdd");
        model.addAttribute("pageTitle", getText("bookmarkForm.add.title", request));
        model.addAttribute("folderId", folderId);
        return ".BookmarkEdit";
    }

    @PostMapping("/bookmarkAdd!save.rol")
    public String bookmarkAddSave(HttpServletRequest request, Model model,
                                  @ModelAttribute("bean") BookmarkBean bean,
                                  @RequestParam(value = "folderId", required = false) String folderId) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "bookmarkAdd");
        model.addAttribute("pageTitle", getText("bookmarkForm.add.title", request));
        model.addAttribute("folderId", folderId);

        try {
            BookmarkManager bmgr = WebloggerFactory.getWeblogger().getBookmarkManager();
            WeblogBookmark bookmark = new WeblogBookmark();
            if (!StringUtils.isEmpty(folderId)) {
                bookmark.setFolder(bmgr.getFolder(folderId));
            }

            if (bookmark.getFolder() != null && bookmark.getFolder().hasBookmarkOfName(bean.getName())) {
                addError(model, "bookmarkForm.error.duplicateName", bean.getUrl(), request);
                return ".BookmarkEdit";
            }

            bean.copyTo(bookmark);
            bmgr.saveBookmark(bookmark);
            WebloggerFactory.getWeblogger().flush();
            CacheManager.invalidate(bookmark);
            addMessage(model, "bookmarkForm.created", bookmark.getName(), request);

            return "redirect:/roller-ui/authoring/bookmarks.rol?weblog="
                    + getActionWeblog(request).getHandle() + "&folderId=" + folderId;
        } catch (Exception ex) {
            log.error("Error saving bookmark", ex);
            addError(model, "generic.error.check.logs", request);
        }

        return ".BookmarkEdit";
    }

    // --- bookmarkEdit ---

    @GetMapping("/bookmarkEdit.rol")
    public String bookmarkEditExecute(HttpServletRequest request, Model model,
                                      @ModelAttribute("bean") BookmarkBean bean,
                                      @RequestParam(value = "folderId", required = false) String folderId) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "bookmarkEdit");
        model.addAttribute("pageTitle", getText("bookmarkForm.edit.title", request));
        model.addAttribute("folderId", folderId);

        try {
            BookmarkManager bmgr = WebloggerFactory.getWeblogger().getBookmarkManager();
            WeblogBookmark bookmark = bmgr.getBookmark(bean.getId());
            if (bookmark != null) {
                bean.copyFrom(bookmark);
                model.addAttribute("bookmark", bookmark);
            }
        } catch (WebloggerException ex) {
            log.error("Error looking up bookmark", ex);
        }

        return ".BookmarkEdit";
    }

    @PostMapping("/bookmarkEdit!save.rol")
    public String bookmarkEditSave(HttpServletRequest request, Model model,
                                   @ModelAttribute("bean") BookmarkBean bean,
                                   @RequestParam(value = "folderId", required = false) String folderId) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "bookmarkEdit");
        model.addAttribute("pageTitle", getText("bookmarkForm.edit.title", request));
        model.addAttribute("folderId", folderId);

        try {
            BookmarkManager bmgr = WebloggerFactory.getWeblogger().getBookmarkManager();
            WeblogBookmark bookmark = bmgr.getBookmark(bean.getId());

            if ((StringUtils.isEmpty(bean.getId()) || !bean.getName().equals(bookmark.getName()))
                    && bookmark.getFolder().hasBookmarkOfName(bean.getName())) {
                addError(model, "bookmarkForm.error.duplicateName", bean.getUrl(), request);
                model.addAttribute("bookmark", bookmark);
                return ".BookmarkEdit";
            }

            bean.copyTo(bookmark);
            bmgr.saveBookmark(bookmark);
            WebloggerFactory.getWeblogger().flush();
            CacheManager.invalidate(bookmark);
            addMessage(model, "bookmarkForm.updated", bookmark.getName(), request);

            return "redirect:/roller-ui/authoring/bookmarks.rol?weblog="
                    + getActionWeblog(request).getHandle() + "&folderId=" + folderId;
        } catch (Exception ex) {
            log.error("Error saving bookmark", ex);
            addError(model, "generic.error.check.logs", request);
        }

        return ".BookmarkEdit";
    }

    @ModelAttribute("bean")
    public BookmarkBean getBean() {
        return new BookmarkBean();
    }
}
