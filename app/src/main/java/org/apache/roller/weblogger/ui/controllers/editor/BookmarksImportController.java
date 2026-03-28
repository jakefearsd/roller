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

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.business.BookmarkManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Import opml file into bookmarks folder.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class BookmarksImportController extends BaseController {

    private static final Log log = LogFactory.getLog(BookmarksImportController.class);

    private static final long WRITE_THRESHOLD_IN_MB = 4;
    private static final long WRITE_THRESHOLD = WRITE_THRESHOLD_IN_MB * 1024000;

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "bookmarksImport";
    }

    @Override
    public String getPageTitle() {
        return "bookmarksImport.title";
    }

    @GetMapping("/bookmarksImport.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        return ".BookmarksImport";
    }

    @PostMapping("/bookmarksImport!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @RequestParam(value = "opmlFile", required = false) MultipartFile opmlFile,
                       RedirectAttributes redirectAttributes) {
        populateCommonModel(request, model);

        BookmarkManager bm = WebloggerFactory.getWeblogger().getBookmarkManager();

        if (opmlFile != null && !opmlFile.isEmpty()) {
            try {
                if (opmlFile.getSize() < WRITE_THRESHOLD) {
                    byte[] bytes = opmlFile.getBytes();
                    String data = new String(bytes);

                    SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");
                    Date now = new Date();
                    String folderName = "imported-" + formatter.format(now);

                    bm.importBookmarks(getActionWeblog(request), folderName, data);
                    WebloggerFactory.getWeblogger().flush();
                    CacheManager.invalidate(getActionWeblog(request));

                    addFlashMessage(redirectAttributes, "bookmarksImport.imported", folderName, request);

                    return "redirect:/roller-ui/authoring/bookmarks.rol?weblog="
                            + getActionWeblog(request).getHandle();
                } else {
                    String data = "The file is greater than " + WRITE_THRESHOLD_IN_MB
                            + " MB, and has not been written to stream."
                            + " File Size: " + opmlFile.getSize() + " bytes.";
                    addError(model, "bookmarksImport.error", data, request);
                }
            } catch (Exception ex) {
                log.error("ERROR: importing bookmarks", ex);
                addError(model, "bookmarksImport.error", ex.toString(), request);
            }
        }
        return ".BookmarksImport";
    }
}
