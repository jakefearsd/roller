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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.business.plugins.PluginManager;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.plugins.entry.WeblogEntryPlugin;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.ui.controllers.BaseController;
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
                WeblogEntryManager wmgr = weblogger.getWeblogEntryManager();
                Weblog weblog = getActionWeblog(request);

                if (bean.getAnalyticsCode() != null) {
                    bean.setAnalyticsCode(bean.getAnalyticsCode().trim());
                }

                // Resolve the category BEFORE copyTo mutates the managed
                // weblog. Anything that throws after that point leaves a
                // half-applied change on an entity the session will still
                // commit, while the page reports a failure.
                //
                // The null check is the bug this ordering was written for: a
                // weblog's blogger category is set to null when that category
                // is deleted (removeWeblogCategory does it deliberately), and
                // this line then threw on EVERY subsequent save. The settings
                // page answered "Error updating configuration" forever, with
                // no way back through the UI.
                WeblogCategory newBloggerCategory = null;
                if (bean.getBloggerCategoryId() != null) {
                    WeblogCategory current = weblog.getBloggerCategory();
                    if (current == null || !current.getId().equals(bean.getBloggerCategoryId())) {
                        newBloggerCategory = wmgr.getWeblogCategory(bean.getBloggerCategoryId());
                    }
                }

                bean.copyTo(weblog);

                if (newBloggerCategory != null) {
                    weblog.setBloggerCategory(newBloggerCategory);
                }

                if (!weblog.getActive()) {
                    weblog.setAllowComments(Boolean.FALSE);
                    addMessage(model, "websiteSettings.commentsOffForInactiveWeblog", request);
                }

                if (!weblog.isShowAllLangs() && !weblog.isEnableMultiLang()) {
                    weblog.setEnableMultiLang(true);
                }

                weblogger.getWeblogManager().saveWeblog(weblog);

                if (bean.getApplyCommentDefaults()) {
                    wmgr.applyCommentDefaultsToEntries(weblog);
                }

                weblogger.flush();
                addMessage(model, "websiteSettings.savedChanges", request);
                CacheManager.invalidate(weblog);

            } catch (Exception ex) {
                log.error("Error updating weblog config", ex);
                addError(model, "Error updating configuration", request);
            }
        }

        return ".WeblogConfig";
    }

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private void myValidate(WeblogConfigBean bean, HttpServletRequest request, Model model) {
        int maxEntries = WebloggerRuntimeConfig.getIntProperty("site.pages.maxEntries");
        if (bean.getEntryDisplayCount() > maxEntries) {
            addError(model, "websiteSettings.error.entryDisplayCount", request);
        }

        String newsletterListUuid = StringUtils.trimToNull(bean.getNewsletterListUuid());
        if (newsletterListUuid != null && !UUID_PATTERN.matcher(newsletterListUuid).matches()) {
            addError(model, "websiteSettings.newsletterListUuid.invalid", request);
        }

        String analyticsSiteId = StringUtils.trimToNull(bean.getAnalyticsSiteId());
        if (analyticsSiteId != null && !UUID_PATTERN.matcher(analyticsSiteId).matches()) {
            addError(model, "websiteSettings.analyticsSiteId.invalid", request);
        }

        String analyticsShareUrl = StringUtils.trimToNull(bean.getAnalyticsShareUrl());
        if (analyticsShareUrl != null && !analyticsShareUrl.matches("^https?://.*")) {
            addError(model, "websiteSettings.analyticsShareUrl.invalid", request);
        }
    }

    private void loadFormData(HttpServletRequest request, Model model) {
        try {
            WeblogEntryManager wmgr = weblogger.getWeblogEntryManager();
            model.addAttribute("weblogCategories", wmgr.getWeblogCategories(getActionWeblog(request)));

            PluginManager ppmgr = weblogger.getPluginManager();
            Map<String, WeblogEntryPlugin> pluginsMap = ppmgr.getWeblogEntryPlugins(getActionWeblog(request));
            model.addAttribute("pluginsList", new ArrayList<>(pluginsMap.values()));

        } catch (Exception ex) {
            log.error("Error preparing weblog config action", ex);
        }

        model.addAttribute("weblogAdminsUntrusted",
                WebloggerConfig.getBooleanProperty("weblogAdminsUntrusted"));

        model.addAttribute("localesList", org.apache.roller.weblogger.ui.controllers.util.UIUtils.getLocales());
        model.addAttribute("timeZonesList", org.apache.roller.weblogger.ui.controllers.util.UIUtils.getTimeZones());
        model.addAttribute("commentDaysList", getCommentDaysList(request));
        model.addAttribute("defaultPlugins", getActionWeblog(request).getDefaultPlugins());
    }

    private Map<Integer, String> getCommentDaysList(HttpServletRequest request) {
        Map<Integer, String> map = new LinkedHashMap<>();
        map.put(0, getText("weblogEdit.unlimitedCommentDays", request));
        map.put(1, getText("weblogEdit.days1", request));
        map.put(2, getText("weblogEdit.days2", request));
        map.put(3, getText("weblogEdit.days3", request));
        map.put(7, getText("weblogEdit.days7", request));
        map.put(14, getText("weblogEdit.days14", request));
        map.put(30, getText("weblogEdit.days30", request));
        map.put(60, getText("weblogEdit.days60", request));
        map.put(90, getText("weblogEdit.days90", request));
        map.put(-1, getText("weblogEdit.noComments", request));
        return map;
    }

    @ModelAttribute("bean")
    public WeblogConfigBean getBean() {
        return new WeblogConfigBean();
    }
}
