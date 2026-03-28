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
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.business.plugins.PluginManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.plugins.entry.WeblogEntryPlugin;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.ui.core.RollerContext;
import org.apache.roller.weblogger.ui.core.plugins.UIPluginManager;
import org.apache.roller.weblogger.ui.core.plugins.WeblogEntryEditor;
import org.apache.roller.weblogger.util.Bannedwordslist;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Action for modifying weblog configuration.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class WeblogConfigController extends BaseController {

    private static final Log log = LogFactory.getLog(WeblogConfigController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "weblogConfig";
    }

    @Override
    public String getPageTitle() {
        return "websiteSettings.title";
    }

    @GetMapping("/weblogConfig.rol")
    public String execute(HttpServletRequest request, Model model,
                          @ModelAttribute("bean") WeblogConfigBean bean) {
        populateCommonModel(request, model);
        loadFormData(request, model);

        bean.copyFrom(getActionWeblog(request));

        return ".WeblogConfig";
    }

    @PostMapping("/weblogConfig!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @ModelAttribute("bean") WeblogConfigBean bean) {
        populateCommonModel(request, model);
        loadFormData(request, model);

        myValidate(bean, request, model);

        if (!hasErrors(model)) {
            try {
                WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
                Weblog weblog = getActionWeblog(request);

                if (bean.getAnalyticsCode() != null) {
                    bean.setAnalyticsCode(bean.getAnalyticsCode().trim());
                }

                bean.copyTo(weblog);

                if (bean.getBloggerCategoryId() != null
                        && !weblog.getBloggerCategory().getId().equals(bean.getBloggerCategoryId())) {
                    weblog.setBloggerCategory(wmgr.getWeblogCategory(bean.getBloggerCategoryId()));
                }

                if (!weblog.getActive()) {
                    weblog.setAllowComments(Boolean.FALSE);
                    addMessage(model, "websiteSettings.commentsOffForInactiveWeblog", request);
                }

                if (!weblog.isShowAllLangs() && !weblog.isEnableMultiLang()) {
                    weblog.setEnableMultiLang(true);
                }

                WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(weblog);

                if (bean.getApplyCommentDefaults()) {
                    wmgr.applyCommentDefaultsToEntries(weblog);
                }

                WebloggerFactory.getWeblogger().flush();
                addMessage(model, "websiteSettings.savedChanges", request);
                CacheManager.invalidate(weblog);

            } catch (Exception ex) {
                log.error("Error updating weblog config", ex);
                addError(model, "Error updating configuration", request);
            }
        }

        return ".WeblogConfig";
    }

    private void myValidate(WeblogConfigBean bean, HttpServletRequest request, Model model) {
        int maxEntries = WebloggerRuntimeConfig.getIntProperty("site.pages.maxEntries");
        if (bean.getEntryDisplayCount() > maxEntries) {
            addError(model, "websiteSettings.error.entryDisplayCount", request);
        }

        List<Pattern> regexRules = new ArrayList<>();
        List<String> stringRules = new ArrayList<>();
        try {
            Bannedwordslist.populateSpamRules(bean.getBannedwordslist(), stringRules, regexRules, null);
            addMessage(model, "websiteSettings.acceptedBannedwordslist",
                    "" + stringRules.size() + ", " + regexRules.size(), request);
        } catch (Exception e) {
            addError(model, "websiteSettings.error.processingBannedwordslist", e.getMessage(), request);
        }
    }

    private void loadFormData(HttpServletRequest request, Model model) {
        try {
            WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
            model.addAttribute("weblogCategories", wmgr.getWeblogCategories(getActionWeblog(request)));

            UIPluginManager pmgr = RollerContext.getUIPluginManager();
            List<WeblogEntryEditor> editorList = pmgr.getWeblogEntryEditors();
            if (editorList != null) {
                model.addAttribute("editorsList", editorList);
            }

            PluginManager ppmgr = WebloggerFactory.getWeblogger().getPluginManager();
            Map<String, WeblogEntryPlugin> pluginsMap = ppmgr.getWeblogEntryPlugins(getActionWeblog(request));
            model.addAttribute("pluginsList", new ArrayList<>(pluginsMap.values()));

        } catch (Exception ex) {
            log.error("Error preparing weblog config action", ex);
        }

        model.addAttribute("weblogAdminsUntrusted",
                WebloggerConfig.getBooleanProperty("weblogAdminsUntrusted"));
    }

    @ModelAttribute("bean")
    public WeblogConfigBean getBean() {
        return new WeblogConfigBean();
    }
}
