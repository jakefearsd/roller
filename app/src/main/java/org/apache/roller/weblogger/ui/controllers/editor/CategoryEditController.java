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

import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.text.StringEscapeUtils;
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
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Edit a new or existing weblog category.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class CategoryEditController extends BaseController {

    private static final Log log = LogFactory.getLog(CategoryEditController.class);

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.singletonList(WeblogPermission.POST);
    }

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    // --- categoryAdd ---

    @GetMapping("/categoryAdd.rol")
    public String categoryAddExecute(HttpServletRequest request, Model model,
                                     @ModelAttribute("bean") CategoryBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "categoryAdd");
        model.addAttribute("pageTitle", getText("categoryForm.add.title", request));
        return ".CategoryEdit";
    }

    @PostMapping("/categoryAdd!save.rol")
    public String categoryAddSave(HttpServletRequest request, Model model,
                                  @ModelAttribute("bean") CategoryBean bean,
                                  RedirectAttributes redirectAttributes) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "categoryAdd");
        model.addAttribute("pageTitle", getText("categoryForm.add.title", request));

        myValidate(bean, true, request, model);

        if (!hasErrors(model)) {
            try {
                WeblogCategory category = new WeblogCategory();
                category.setWeblog(getActionWeblog(request));
                bean.copyTo(category);

                getActionWeblog(request).addCategory(category);
                category.calculatePosition();

                WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
                wmgr.saveWeblogCategory(category);
                WebloggerFactory.getWeblogger().flush();

                CacheManager.invalidate(getActionWeblog(request));
                addFlashMessage(redirectAttributes, "categoryForm.created", category.getName(), request);

                return "redirect:/roller-ui/authoring/categories.rol?weblog="
                        + getActionWeblog(request).getHandle();
            } catch (Exception ex) {
                log.error("Error saving category", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }

        return ".CategoryEdit";
    }

    // --- categoryEdit ---

    @GetMapping("/categoryEdit.rol")
    public String categoryEditExecute(HttpServletRequest request, Model model,
                                      @ModelAttribute("bean") CategoryBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "categoryEdit");
        model.addAttribute("pageTitle", getText("categoryForm.edit.title", request));

        try {
            WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
            WeblogCategory category = wmgr.getWeblogCategory(bean.getId());
            if (category != null) {
                bean.copyFrom(category);
            }
        } catch (WebloggerException ex) {
            log.error("Error looking up category", ex);
        }

        return ".CategoryEdit";
    }

    @PostMapping("/categoryEdit!save.rol")
    public String categoryEditSave(HttpServletRequest request, Model model,
                                   @ModelAttribute("bean") CategoryBean bean,
                                   RedirectAttributes redirectAttributes) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "categoryEdit");
        model.addAttribute("pageTitle", getText("categoryForm.edit.title", request));

        myValidate(bean, false, request, model);

        if (!hasErrors(model)) {
            try {
                WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
                WeblogCategory category = wmgr.getWeblogCategory(bean.getId());
                bean.copyTo(category);

                wmgr.saveWeblogCategory(category);
                WebloggerFactory.getWeblogger().flush();

                CacheManager.invalidate(getActionWeblog(request));
                addFlashMessage(redirectAttributes, "categoryForm.changesSaved", category.getName(), request);

                return "redirect:/roller-ui/authoring/categories.rol?weblog="
                        + getActionWeblog(request).getHandle();
            } catch (Exception ex) {
                log.error("Error saving category", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }

        return ".CategoryEdit";
    }

    private void myValidate(CategoryBean bean, boolean isAdd, HttpServletRequest request, Model model) {
        if (bean.getName() == null || !bean.getName().equals(StringEscapeUtils.escapeHtml4(bean.getName()))) {
            addError(model, "categoryForm.error.invalidName", request);
        } else if (isAdd) {
            if (getActionWeblog(request).hasCategory(bean.getName())) {
                addError(model, "categoryForm.error.duplicateName", bean.getName(), request);
            }
        } else {
            WeblogCategory wc = getActionWeblog(request).getWeblogCategory(bean.getName());
            if (wc != null && !wc.getId().equals(bean.getId())) {
                addError(model, "categoryForm.error.duplicateName", bean.getName(), request);
            }
        }
    }

    @ModelAttribute("bean")
    public CategoryBean getBean() {
        return new CategoryBean();
    }
}
