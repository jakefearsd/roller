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

package org.apache.roller.weblogger.ui.controllers.core;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.CharSetUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.themes.SharedTheme;
import org.apache.roller.weblogger.business.themes.ThemeManager;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.ui.controllers.util.UIUtils;
import org.apache.roller.weblogger.util.Utilities;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;


/**
 * Allows user to create a new website.
 */
@Controller
@RequestMapping("/roller-ui")
public class CreateWeblogController extends BaseController {

    private static final Log log = LogFactory.getLog(CreateWeblogController.class);

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public String getPageTitle() {
        return "createWebsite.title";
    }

    @Override
    @InitBinder("bean")
    public void initBeanBinder(WebDataBinder binder) {
        // No prefix — this controller uses Spring form:form tags
    }

    @ModelAttribute("bean")
    public CreateWeblogBean getBean() {
        return new CreateWeblogBean();
    }

    @GetMapping("/createWeblog.rol")
    public String execute(HttpServletRequest request, Model model,
                          @ModelAttribute("bean") CreateWeblogBean bean) {

        populateCommonModel(request, model);
        addListsToModel(model);

        // check if blog administrator has enabled creation of new blogs
        if (!WebloggerRuntimeConfig.getBooleanProperty("site.allowUserWeblogCreation")) {
            addError(model, "createWebsite.disabled", request);
            return ".GenericError";
        }

        User user = getAuthenticatedUser(request);

        try {
            if (!WebloggerConfig.getBooleanProperty("groupblogging.enabled")) {
                UserManager mgr = WebloggerFactory.getWeblogger().getUserManager();
                List<WeblogPermission> permissions = mgr.getWeblogPermissions(user);
                if (!permissions.isEmpty()) {
                    addError(model, "createWebsite.oneBlogLimit", request);
                    return ".GenericError";
                }
            }
        } catch (WebloggerException ex) {
            log.error("error checking for existing weblogs count", ex);
            addError(model, "generic.error.check.logs", request);
            return ".GenericError";
        }

        // pre-populate with some logical defaults
        bean.setLocale(user.getLocale());
        bean.setTimeZone(user.getTimeZone());
        bean.setEmailAddress(user.getEmailAddress());

        return ".CreateWeblog";
    }

    @PostMapping("/createWeblog!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @ModelAttribute("bean") CreateWeblogBean bean,
                       RedirectAttributes redirectAttributes) {

        populateCommonModel(request, model);
        addListsToModel(model);

        User user = getAuthenticatedUser(request);
        try {
            if (!WebloggerConfig.getBooleanProperty("groupblogging.enabled")) {
                UserManager mgr = WebloggerFactory.getWeblogger().getUserManager();
                List<WeblogPermission> permissions = mgr.getWeblogPermissions(user);
                if (!permissions.isEmpty()) {
                    addFlashError(redirectAttributes, "createWebsite.oneBlogLimit", request);
                    return "redirect:/roller-ui/menu.rol";
                }
            }
        } catch (WebloggerException ex) {
            log.error("error checking for existing weblogs count", ex);
        }

        myValidate(request, model, bean);

        if (!hasErrors(model)) {

            Weblog wd = new Weblog(
                    bean.getHandle(),
                    user.getUserName(),
                    bean.getName(),
                    bean.getDescription(),
                    bean.getEmailAddress(),
                    bean.getTheme(),
                    bean.getLocale(),
                    bean.getTimeZone());

            // pick a weblog editor for this weblog
            String def = WebloggerRuntimeConfig.getProperty("users.editor.pages");
            String[] defs = Utilities.stringToStringArray(def, ",");
            wd.setEditorPage(defs[0]);

            try {
                WebloggerFactory.getWeblogger().getWeblogManager().addWeblog(wd);
                WebloggerFactory.getWeblogger().flush();

                addFlashMessage(redirectAttributes, "createWebsite.created", bean.getHandle(), request);
                return "redirect:/roller-ui/menu.rol";

            } catch (WebloggerException e) {
                log.error("ERROR adding weblog", e);
                addError(model, e.getMessage(), request);
            }
        }

        return ".CreateWeblog";
    }

    private void myValidate(HttpServletRequest request, Model model, CreateWeblogBean bean) {
        String allowed = WebloggerConfig.getProperty("username.allowedChars");
        if (allowed == null || allowed.isBlank()) {
            allowed = UIUtils.DEFAULT_ALLOWED_CHARS;
        }

        String safe = CharSetUtils.keep(bean.getHandle(), allowed);
        if (!safe.equals(bean.getHandle())) {
            addError(model, "createWeblog.error.invalidHandle", request);
            return;
        }

        if (!StringUtils.isEmpty(bean.getHandle())) {
            try {
                if (WebloggerFactory.getWeblogger().getWeblogManager()
                        .getWeblogByHandle(bean.getHandle()) != null) {
                    addError(model, "createWeblog.error.handleExists", request);
                    bean.setHandle(null);
                }
            } catch (WebloggerException ex) {
                log.error("error checking for weblog", ex);
                addError(model, "Unexpected error validating weblog -- check Roller logs", request);
            }
        }
    }

    private void addListsToModel(Model model) {
        model.addAttribute("localesList", org.apache.roller.weblogger.ui.controllers.util.UIUtils.getLocales());
        model.addAttribute("timeZonesList", org.apache.roller.weblogger.ui.controllers.util.UIUtils.getTimeZones());

        ThemeManager themeMgr = WebloggerFactory.getWeblogger().getThemeManager();
        List<SharedTheme> themes = themeMgr.getEnabledThemesList();
        model.addAttribute("themes", themes);
    }
}
