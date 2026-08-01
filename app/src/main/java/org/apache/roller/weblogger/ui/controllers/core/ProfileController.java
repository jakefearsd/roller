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

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.config.AuthMethod;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.ui.controllers.BaseController;
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
 * Allows user to edit his/her profile.
 */
@Controller
@RequestMapping("/roller-ui")
public class ProfileController extends BaseController {

    private static final Log log = LogFactory.getLog(ProfileController.class);

    private final AuthMethod authMethod = WebloggerConfig.getAuthMethod();

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public String getPageTitle() {
        return "yourProfile.title";
    }

    @Override
    @InitBinder("bean")
    public void initBeanBinder(WebDataBinder binder) {
        // No prefix — this controller uses Spring form:form tags which don't prefix field names
    }

    @ModelAttribute("bean")
    public ProfileBean getBean() {
        return new ProfileBean();
    }

    @GetMapping("/profile.rol")
    public String execute(HttpServletRequest request, Model model,
                          @ModelAttribute("bean") ProfileBean bean) {

        populateCommonModel(request, model);
        model.addAttribute("authMethod", authMethod.name());
        addListsToModel(model);

        User ud = getAuthenticatedUser(request);
        // load up the form from the users existing profile data
        bean.copyFrom(ud);

        return ".Profile";
    }

    @PostMapping("/profile!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @ModelAttribute("bean") ProfileBean bean,
                       RedirectAttributes redirectAttributes) {

        populateCommonModel(request, model);
        model.addAttribute("authMethod", authMethod.name());
        addListsToModel(model);

        myValidate(request, model, bean);

        if (!hasErrors(model)) {

            // We ONLY modify the user currently logged in
            User existingUser = getAuthenticatedUser(request);

            // copy updated attributes
            bean.copyTo(existingUser);

            // If user set both password and passwordConfirm then reset password
            if (!StringUtils.isEmpty(bean.getPasswordText())
                    && !StringUtils.isEmpty(bean.getPasswordConfirm())) {
                existingUser.resetPassword(bean.getPasswordText());
            }

            try {
                UserManager mgr = weblogger.getUserManager();
                mgr.saveUser(existingUser);
                weblogger.flush();
                addFlashMessage(redirectAttributes, "generic.changes.saved", request);
                return "redirect:/roller-ui/menu.rol";
            } catch (WebloggerException ex) {
                log.error("ERROR in action", ex);
                addError(model, "Unexpected error doing profile save", request);
            }
        }
        return ".Profile";
    }

    private void myValidate(HttpServletRequest request, Model model, ProfileBean bean) {
        if (!StringUtils.equals(bean.getPasswordText(), bean.getPasswordConfirm())) {
            // userRegister.* went away with public self-registration; this page
            // has its own key for the same message.
            addError(model, "yourProfile.passwordsNotSame", request);
        }

        // validate timezone
        if (!StringUtils.isEmpty(bean.getTimeZone())) {
            final Optional<String> first = Arrays.stream(TimeZone.getAvailableIDs())
                    .filter(id -> id.equals(bean.getTimeZone())).findFirst();
            if (first.isEmpty()) {
                addError(model, "error.add.user.invalid.timezone", request);
            }
        }

        // validate locale
        if (!StringUtils.isEmpty(bean.getLocale())) {
            final Optional<Locale> first = Arrays.stream(Locale.getAvailableLocales())
                    .filter(locale -> locale.toString().equals(bean.getLocale())).findFirst();
            if (first.isEmpty() || "".equals(first.get().getDisplayName())) {
                addError(model, "error.add.user.invalid.locale", request);
            }
        }
    }

    private void addListsToModel(Model model) {
        model.addAttribute("localesList", org.apache.roller.weblogger.ui.controllers.util.UIUtils.getLocales());
        model.addAttribute("timeZonesList", org.apache.roller.weblogger.ui.controllers.util.UIUtils.getTimeZones());
    }
}
