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

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.BookmarkManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.WeblogBookmark;
import org.apache.roller.weblogger.pojos.WeblogBookmarkFolder;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * List bookmarks and folders and allow for moving them around and deleting them.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class BookmarksController extends BaseController {

    private static final Log log = LogFactory.getLog(BookmarksController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "bookmarks";
    }

    @Override
    public String getPageTitle() {
        return "bookmarksForm.rootTitle";
    }

    @GetMapping("/bookmarks.rol")
    public String execute(HttpServletRequest request, Model model,
                          @RequestParam(value = "folderId", required = false) String folderId) {
        populateCommonModel(request, model);
        WeblogBookmarkFolder folder = resolveFolder(request, folderId);
        populateBookmarkModel(request, model, folder);
        return ".Bookmarks";
    }

    @GetMapping("/bookmarks!folderCreated.rol")
    public String folderCreated(HttpServletRequest request, Model model,
                                @RequestParam(value = "folderId", required = false) String folderId) {
        populateCommonModel(request, model);
        addMessage(model, "folderForm.created", request);
        WeblogBookmarkFolder folder = resolveFolder(request, folderId);
        populateBookmarkModel(request, model, folder);
        return ".Bookmarks";
    }

    @PostMapping("/bookmarks!delete.rol")
    public String delete(HttpServletRequest request, Model model,
                         @RequestParam(value = "folderId", required = false) String folderId,
                         @RequestParam(value = "selectedBookmarks", required = false) String[] selectedBookmarks) {
        populateCommonModel(request, model);

        BookmarkManager bmgr = WebloggerFactory.getWeblogger().getBookmarkManager();
        try {
            if (selectedBookmarks != null && selectedBookmarks.length > 0) {
                for (String bookmarkId : selectedBookmarks) {
                    WeblogBookmark bookmark = bmgr.getBookmark(bookmarkId);
                    if (bookmark != null) {
                        bmgr.removeBookmark(bookmark);
                    }
                }
            }
            WebloggerFactory.getWeblogger().flush();
            CacheManager.invalidate(getActionWeblog(request));
        } catch (WebloggerException ex) {
            log.error("Error doing bookmark deletes", ex);
            addError(model, "Error doing bookmark deletes", request);
        }

        WeblogBookmarkFolder folder = resolveFolder(request, folderId);
        populateBookmarkModel(request, model, folder);
        return ".Bookmarks";
    }

    @PostMapping("/bookmarks!deleteFolder.rol")
    public String deleteFolder(HttpServletRequest request, Model model,
                               @RequestParam(value = "folderId", required = false) String folderId) {
        populateCommonModel(request, model);

        try {
            BookmarkManager bmgr = WebloggerFactory.getWeblogger().getBookmarkManager();
            WeblogBookmarkFolder fd = bmgr.getFolder(folderId);
            if (fd != null) {
                if ("default".equals(fd.getName())) {
                    addError(model, "Cannot delete default bookmark", request);
                    WeblogBookmarkFolder folder = resolveFolder(request, folderId);
                    populateBookmarkModel(request, model, folder);
                    return ".Bookmarks";
                }
                bmgr.removeFolder(fd);
                WebloggerFactory.getWeblogger().flush();
                CacheManager.invalidate(getActionWeblog(request));

                WeblogBookmarkFolder defaultFolder = bmgr.getDefaultFolder(getActionWeblog(request));
                populateBookmarkModel(request, model, defaultFolder);
                return ".Bookmarks";
            }
        } catch (WebloggerException ex) {
            log.error("Error deleting folder", ex);
        }

        WeblogBookmarkFolder folder = resolveFolder(request, folderId);
        populateBookmarkModel(request, model, folder);
        return ".Bookmarks";
    }

    @PostMapping("/bookmarks!view.rol")
    public String view(HttpServletRequest request, Model model,
                       @RequestParam(value = "folderId", required = false) String folderId,
                       @RequestParam(value = "viewFolderId", required = false) String viewFolderId) {
        populateCommonModel(request, model);
        String targetId = !StringUtils.isEmpty(viewFolderId) ? viewFolderId : folderId;
        WeblogBookmarkFolder folder = resolveFolder(request, targetId);
        populateBookmarkModel(request, model, folder);
        return ".Bookmarks";
    }

    @PostMapping("/bookmarks!move.rol")
    public String move(HttpServletRequest request, Model model,
                       @RequestParam(value = "folderId", required = false) String folderId,
                       @RequestParam(value = "selectedBookmarks", required = false) String[] selectedBookmarks,
                       @RequestParam(value = "targetFolderId", required = false) String targetFolderId) {
        populateCommonModel(request, model);

        try {
            BookmarkManager bmgr = WebloggerFactory.getWeblogger().getBookmarkManager();
            WeblogBookmarkFolder newFolder = bmgr.getFolder(targetFolderId);
            WeblogBookmarkFolder currentFolder = bmgr.getFolder(folderId);

            if (selectedBookmarks != null && selectedBookmarks.length > 0) {
                for (String bookmarkId : selectedBookmarks) {
                    WeblogBookmark bd = bmgr.getBookmark(bookmarkId);
                    newFolder.addBookmark(bd);
                    bd.setFolder(newFolder);
                    bmgr.saveBookmark(bd);
                    if (currentFolder != null) {
                        currentFolder.getBookmarks().remove(bd);
                    }
                }
            }

            WebloggerFactory.getWeblogger().flush();
            CacheManager.invalidate(getActionWeblog(request));
        } catch (WebloggerException e) {
            log.error("Error doing bookmark move", e);
            addError(model, "bookmarksForm.error.move", request);
        }

        WeblogBookmarkFolder folder = resolveFolder(request, folderId);
        populateBookmarkModel(request, model, folder);
        return ".Bookmarks";
    }

    private WeblogBookmarkFolder resolveFolder(HttpServletRequest request, String folderId) {
        try {
            BookmarkManager bmgr = WebloggerFactory.getWeblogger().getBookmarkManager();
            if (!StringUtils.isEmpty(folderId)) {
                return bmgr.getFolder(folderId);
            } else {
                return bmgr.getDefaultFolder(getActionWeblog(request));
            }
        } catch (WebloggerException ex) {
            log.error("Error looking up folder", ex);
        }
        return null;
    }

    private void populateBookmarkModel(HttpServletRequest request, Model model, WeblogBookmarkFolder folder) {
        model.addAttribute("folder", folder);
        model.addAttribute("folderId", folder != null ? folder.getId() : null);

        List<WeblogBookmarkFolder> allFolders = new ArrayList<>();
        try {
            BookmarkManager bmgr = WebloggerFactory.getWeblogger().getBookmarkManager();
            List<WeblogBookmarkFolder> folders = bmgr.getAllFolders(getActionWeblog(request));
            for (WeblogBookmarkFolder fd : folders) {
                if (folder == null || !fd.getId().equals(folder.getId())) {
                    allFolders.add(fd);
                }
            }
        } catch (WebloggerException ex) {
            log.error("Error building folders list", ex);
        }
        model.addAttribute("allFolders", allFolders);
    }
}
