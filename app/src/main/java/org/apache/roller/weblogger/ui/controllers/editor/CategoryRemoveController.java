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
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Remove a category.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class CategoryRemoveController extends BaseController {

    private static final Log log = LogFactory.getLog(CategoryRemoveController.class);

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.singletonList(WeblogPermission.POST);
    }

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "categoryRemove";
    }

    @Override
    public String getPageTitle() {
        return "categoryDeleteOK.title";
    }

    @GetMapping("/categoryRemove.rol")
    public String execute(HttpServletRequest request, Model model,
                          @RequestParam(value = "removeId", required = false) String removeId) {
        populateCommonModel(request, model);

        WeblogCategory category = lookupCategory(removeId);
        model.addAttribute("category", category);
        model.addAttribute("removeId", removeId);

        try {
            WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
            List<WeblogCategory> cats = wmgr.getWeblogCategories(getActionWeblog(request));
            List<WeblogCategory> allCategories = new ArrayList<>();
            for (WeblogCategory cat : cats) {
                if (!cat.getId().equals(removeId)) {
                    allCategories.add(cat);
                }
            }
            model.addAttribute("allCategories", allCategories);
        } catch (WebloggerException ex) {
            log.error("Error building categories list", ex);
            addError(model, "generic.error.check.logs", request);
        }

        return ".CategoryRemove";
    }

    @PostMapping("/categoryRemove!remove.rol")
    public String remove(HttpServletRequest request, Model model,
                         @RequestParam(value = "removeId", required = false) String removeId,
                         @RequestParam(value = "targetCategoryId", required = false) String targetCategoryId) {
        populateCommonModel(request, model);

        WeblogCategory category = lookupCategory(removeId);
        if (category != null) {
            try {
                WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();

                if (targetCategoryId != null) {
                    WeblogCategory target = wmgr.getWeblogCategory(targetCategoryId);
                    wmgr.moveWeblogCategoryContents(category, target);
                    WebloggerFactory.getWeblogger().flush();
                }

                CacheManager.invalidate(category);
                wmgr.removeWeblogCategory(category);
                WebloggerFactory.getWeblogger().flush();

                addMessage(model, "categoryForm.removed", category.getName(), request);

                return "redirect:/roller-ui/authoring/categories.rol?weblog="
                        + getActionWeblog(request).getHandle();
            } catch (Exception ex) {
                log.error("Error removing category - " + removeId, ex);
                addError(model, "generic.error.check.logs", request);
            }
        }

        return execute(request, model, removeId);
    }

    private WeblogCategory lookupCategory(String id) {
        if (StringUtils.isEmpty(id)) {
            return null;
        }
        try {
            WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
            return wmgr.getWeblogCategory(id);
        } catch (WebloggerException ex) {
            log.error("Error looking up category", ex);
        }
        return null;
    }
}
