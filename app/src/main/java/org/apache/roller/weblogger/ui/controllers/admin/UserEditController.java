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

package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.CharSetUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.config.AuthMethod;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.ui.core.RollerLoginSessionManager;
import org.apache.roller.weblogger.ui.controllers.util.UIUtils;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;


/**
 * Controller that allows an admin to create or modify a user profile.
 */
@Controller
@RequestMapping("/roller-ui/admin")
public class UserEditController extends BaseController {

    private static final Log log = LogFactory.getLog(UserEditController.class);

    private final AuthMethod authMethod = WebloggerConfig.getAuthMethod();

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @ModelAttribute("bean")
    public CreateUserBean getBean() {
        return new CreateUserBean();
    }

    // --- Create User ---

    @GetMapping("/createUser.rol")
    public String createUserExecute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "createUser");
        model.addAttribute("pageTitle", "userAdmin.title.createNewUser");

        CreateUserBean bean = new CreateUserBean();
        bean.setLocale(java.util.Locale.getDefault().toString());
        bean.setTimeZone(java.util.TimeZone.getDefault().getID());

        model.addAttribute("bean", bean);
        model.addAttribute("authMethod", authMethod.name());
        addLocalesAndTimezones(model);

        return ".UserEdit";
    }

    @PostMapping("/createUser!save.rol")
    public String createUserSave(HttpServletRequest request, Model model,
                                 @ModelAttribute("bean") CreateUserBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "createUser");
        model.addAttribute("pageTitle", "userAdmin.title.createNewUser");
        model.addAttribute("bean", bean);
        model.addAttribute("authMethod", authMethod.name());
        addLocalesAndTimezones(model);

        User user = new User();

        // validate for create
        myValidate(bean, user, true, model, request);

        if (!hasErrors(model)) {
            bean.copyTo(user);

            if (authMethod == AuthMethod.DB_OPENID) {
                if (StringUtils.isEmpty(user.getPassword())
                        && StringUtils.isEmpty(bean.getPassword())
                        && StringUtils.isEmpty(bean.getOpenIdUrl())) {
                    addError(model, "userRegister.error.missingOpenIDOrPassword", request);
                    return ".UserEdit";
                } else if (StringUtils.isNotEmpty(bean.getOpenIdUrl())
                        && StringUtils.isNotEmpty(bean.getPassword())) {
                    addError(model, "userRegister.error.bothOpenIDAndPassword", request);
                    return ".UserEdit";
                }
            }

            // User.password does not allow null, so generate one
            if (authMethod.equals(AuthMethod.OPENID) ||
                    (authMethod.equals(AuthMethod.DB_OPENID) && !StringUtils.isEmpty(bean.getOpenIdUrl()))) {
                String randomString = RandomStringUtils.secure().nextAlphanumeric(255);
                user.resetPassword(randomString);
            }

            // reset password if set
            if (!StringUtils.isEmpty(bean.getPassword())) {
                user.resetPassword(bean.getPassword());
            }

            try {
                UserManager mgr = WebloggerFactory.getWeblogger().getUserManager();
                // fields not copied over from above copyTo():
                user.setUserName(bean.getUserName());
                user.setDateCreated(new java.util.Date());
                // save new user
                mgr.addUser(user);

                // grant admin role if needed
                if (bean.isAdministrator()) {
                    mgr.grantRole("admin", user);
                }

                WebloggerFactory.getWeblogger().flush();

                addMessage(model, "createUser.add.success", bean.getUserName(), request);
                model.addAttribute("bean", new CreateUserBean());
                return ".UserAdmin";

            } catch (WebloggerException ex) {
                log.error("ERROR in action", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }
        return ".UserEdit";
    }

    @GetMapping("/createUser!cancel.rol")
    public String createUserCancel() {
        return "redirect:/roller-ui/admin/userAdmin.rol";
    }

    // --- Modify User ---

    @GetMapping("/modifyUser.rol")
    public String modifyUserExecute(HttpServletRequest request, Model model,
                                    @ModelAttribute("bean") CreateUserBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "modifyUser");
        model.addAttribute("pageTitle", "userAdmin.title.editUser");

        User user = lookupUser(bean);
        if (user == null) {
            addError(model, "userAdmin.error.userNotFound", request);
            model.addAttribute("bean", bean);
            model.addAttribute("authMethod", authMethod.name());
            addLocalesAndTimezones(model);
            return ".UserAdmin";
        }

        // populate form data from user profile data
        bean.copyFrom(user);
        model.addAttribute("bean", bean);
        model.addAttribute("authMethod", authMethod.name());
        model.addAttribute("permissions", getPermissions(user));
        addLocalesAndTimezones(model);

        return ".UserEdit";
    }

    @GetMapping("/modifyUser!firstSave.rol")
    public String modifyUserFirstSave(HttpServletRequest request, Model model,
                                      @ModelAttribute("bean") CreateUserBean bean) {
        addMessage(model, "createUser.add.success", bean.getUserName(), request);
        return modifyUserExecute(request, model, bean);
    }

    @PostMapping("/modifyUser!save.rol")
    public String modifyUserSave(HttpServletRequest request, Model model,
                                 @ModelAttribute("bean") CreateUserBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "modifyUser");
        model.addAttribute("pageTitle", "userAdmin.title.editUser");
        model.addAttribute("bean", bean);
        model.addAttribute("authMethod", authMethod.name());
        addLocalesAndTimezones(model);

        User user = lookupUser(bean);
        if (user == null) {
            addError(model, "userAdmin.error.userNotFound", request);
            return ".UserAdmin";
        }
        model.addAttribute("permissions", getPermissions(user));

        // validate for modify
        myValidate(bean, user, false, model, request);

        if (!hasErrors(model)) {
            bean.copyTo(user);

            if (authMethod == AuthMethod.DB_OPENID) {
                if (StringUtils.isEmpty(user.getPassword())
                        && StringUtils.isEmpty(bean.getPassword())
                        && StringUtils.isEmpty(bean.getOpenIdUrl())) {
                    addError(model, "userRegister.error.missingOpenIDOrPassword", request);
                    return ".UserEdit";
                } else if (StringUtils.isNotEmpty(bean.getOpenIdUrl())
                        && StringUtils.isNotEmpty(bean.getPassword())) {
                    addError(model, "userRegister.error.bothOpenIDAndPassword", request);
                    return ".UserEdit";
                }
            }

            // User.password does not allow null, so generate one
            if (authMethod.equals(AuthMethod.OPENID) ||
                    (authMethod.equals(AuthMethod.DB_OPENID) && !StringUtils.isEmpty(bean.getOpenIdUrl()))) {
                String randomString = RandomStringUtils.secure().nextAlphanumeric(255);
                user.resetPassword(randomString);
            }

            // reset password if set
            if (!StringUtils.isEmpty(bean.getPassword())) {
                user.resetPassword(bean.getPassword());

                // invalidate user's session if it's not user executing this action
                User authUser = getAuthenticatedUser(request);
                if (authUser != null && !authUser.getUserName().equals(user.getUserName())) {
                    RollerLoginSessionManager sessionManager = RollerLoginSessionManager.getInstance();
                    sessionManager.invalidate(user.getUserName());
                }
            }

            // if user is disabled and not the same as the user executing this action, then invalidate session
            User authUser = getAuthenticatedUser(request);
            if (!user.getEnabled() && authUser != null && !authUser.getUserName().equals(user.getUserName())) {
                RollerLoginSessionManager sessionManager = RollerLoginSessionManager.getInstance();
                sessionManager.invalidate(user.getUserName());
            }

            try {
                UserManager mgr = WebloggerFactory.getWeblogger().getUserManager();
                mgr.saveUser(user);

                // update Admin role as appropriate
                boolean hasAdmin = false;
                GlobalPermission adminPerm =
                        new GlobalPermission(Collections.singletonList(GlobalPermission.ADMIN));
                if (mgr.checkPermission(adminPerm, user)) {
                    hasAdmin = true;
                }
                // grant/revoke admin role if needed
                boolean userEditingSelf = authUser != null && user.equals(authUser);
                if (hasAdmin && !bean.isAdministrator()) {
                    if (!userEditingSelf) {
                        // revoke role
                        mgr.revokeRole("admin", user);
                    } else {
                        addError(model, "userAdmin.cantChangeOwnRole", request);
                    }
                } else if (!hasAdmin && bean.isAdministrator()) {
                    mgr.grantRole("admin", user);
                }
                WebloggerFactory.getWeblogger().flush();

                // successful edit: send user back to user admin page
                model.addAttribute("bean", new CreateUserBean());
                addMessage(model, "userAdmin.userSaved", request);
                return ".UserAdmin";

            } catch (WebloggerException ex) {
                log.error("ERROR in action", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }
        return ".UserEdit";
    }

    @GetMapping("/modifyUser!cancel.rol")
    public String modifyUserCancel() {
        return "redirect:/roller-ui/admin/userAdmin.rol";
    }

    // --- Helpers ---

    private User lookupUser(CreateUserBean bean) {
        try {
            UserManager mgr = WebloggerFactory.getWeblogger().getUserManager();
            if (!StringUtils.isEmpty(bean.getId())) {
                return mgr.getUser(bean.getId());
            } else if (!StringUtils.isEmpty(bean.getUserName())) {
                return mgr.getUserByUserName(bean.getUserName(), null);
            }
        } catch (Exception e) {
            log.error("Error looking up user (id/username):" + bean.getId() + "/" + bean.getUserName(), e);
        }
        return null;
    }

    private void myValidate(CreateUserBean bean, User user, boolean isAdd,
                            Model model, HttpServletRequest request) {
        if (isAdd) {
            String allowed = WebloggerConfig.getProperty("username.allowedChars");
            if (allowed == null || allowed.isBlank()) {
                allowed = UIUtils.DEFAULT_ALLOWED_CHARS;
            }
            String safe = CharSetUtils.keep(bean.getUserName(), allowed);

            if (StringUtils.isEmpty(bean.getUserName())) {
                addError(model, "error.add.user.missingUserName", request);
            } else if (!safe.equals(bean.getUserName())) {
                addError(model, "error.add.user.badUserName", request);
            }
            if ((authMethod == AuthMethod.ROLLERDB ||
                    (authMethod == AuthMethod.DB_OPENID && StringUtils.isEmpty(bean.getOpenIdUrl())))
                    && StringUtils.isEmpty(bean.getPassword())) {
                addError(model, "error.add.user.missingPassword", request);
            }
        } else {
            if (user.getUserName() == null) {
                addError(model, "userAdmin.error.userNotFound", request);
            }
        }
        if ((authMethod == AuthMethod.OPENID) && StringUtils.isEmpty(bean.getOpenIdUrl())) {
            addError(model, "userRegister.error.missingOpenID", request);
        }

        // check that OpenID, if provided, is not taken
        if (!StringUtils.isEmpty(bean.getOpenIdUrl())) {
            try {
                UserManager mgr = WebloggerFactory.getWeblogger().getUserManager();
                User existingUser = mgr.getUserByOpenIdUrl(bean.getOpenIdUrl());
                if (existingUser != null && !(existingUser.getUserName().equals(bean.getUserName()))) {
                    addError(model, "error.add.user.openIdInUse", request);
                }
            } catch (WebloggerException ex) {
                log.error("error checking OpenID URL", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }
    }

    private List<WeblogPermission> getPermissions(User user) {
        try {
            return WebloggerFactory.getWeblogger().getUserManager().getWeblogPermissions(user);
        } catch (WebloggerException ex) {
            log.error("ERROR getting permissions for user " + user.getUserName(), ex);
        }
        return new ArrayList<>();
    }

    private void addLocalesAndTimezones(Model model) {
        model.addAttribute("localesList", UIUtils.getLocales());
        model.addAttribute("timeZonesList", UIUtils.getTimeZones());
    }
}
