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
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.ui.controllers.CustomDomainRules;
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
                Weblog weblog = getActionWeblog(request);

                bean.copyTo(weblog);

                weblogger.getWeblogManager().saveWeblog(weblog);

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
        if (analyticsShareUrl != null && !analyticsShareUrl.matches("(?i)^https?://.*")) {
            addError(model, "websiteSettings.analyticsShareUrl.invalid", request);
        }

        String customDomain = CustomDomainRules.normalise(bean.getCustomDomain());
        bean.setCustomDomain(customDomain);
        if (customDomain != null) {
            if (!CustomDomainRules.isWellFormed(customDomain)) {
                addError(model, "websiteSettings.customDomain.invalid", request);
            } else if (CustomDomainRules.isSiteHost(customDomain,
                    WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl"))) {
                addError(model, "websiteSettings.customDomain.isSiteHost", request);
            } else {
                try {
                    Weblog claimant = weblogger.getWeblogManager()
                            .getWeblogByCustomDomain(customDomain);
                    // Compare against the REAL action weblog, never
                    // bean.getHandle() -- initBeanBinder's "bean." field
                    // prefix binds bean.handle from POST data even though
                    // the JSP never renders it, so a submitted bean.handle
                    // equal to the claimant's own handle must not be able to
                    // make this check pass for a domain this weblog does not
                    // actually own (I4).
                    if (claimant != null
                            && !claimant.getHandle().equals(getActionWeblog(request).getHandle())) {
                        addError(model, "websiteSettings.customDomain.taken", request);
                    }
                } catch (WebloggerException e) {
                    addError(model, "websiteSettings.customDomain.invalid", request);
                }
            }
        }

        // Only warn about the zone when the save is actually going to happen --
        // otherwise a malformed or taken hostname's rejection re-renders the
        // page with an error AND a false "Saved, but..." banner, for a
        // domain that was never persisted at all.
        if (!hasErrors(model) && CustomDomainRules.isOutsideCertZones(customDomain,
                WebloggerConfig.getProperty("vhost.cert.zones"))) {
            model.addAttribute("customDomainWarning", customDomain);
        }
    }

    private void loadFormData(HttpServletRequest request, Model model) {
        model.addAttribute("localesList", org.apache.roller.weblogger.ui.controllers.util.UIUtils.getLocales());
        model.addAttribute("timeZonesList", org.apache.roller.weblogger.ui.controllers.util.UIUtils.getTimeZones());
    }

    @ModelAttribute("bean")
    public WeblogConfigBean getBean() {
        return new WeblogConfigBean();
    }
}
