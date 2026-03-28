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

import java.util.TimeZone;
import java.util.UUID;

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
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.ui.core.RollerSession;
import org.apache.roller.weblogger.ui.core.security.CustomUserRegistry;
import org.apache.roller.weblogger.util.MailUtil;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;


/**
 * Actions for registering a new user.
 */
@Controller
@RequestMapping("/roller-ui")
public class RegisterController extends BaseController {

    private static final Log log = LogFactory.getLog(RegisterController.class);
    private static final String DISABLED_RETURN_CODE = "disabled";
    public static final String DEFAULT_ALLOWED_CHARS = "A-Za-z0-9";

    private final AuthMethod authMethod = WebloggerConfig.getAuthMethod();

    @Override
    public boolean isUserRequired() {
        return false;
    }

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public String getPageTitle() {
        return "newUser.addNewUser";
    }

    @ModelAttribute("bean")
    public ProfileBean getBean() {
        return new ProfileBean();
    }

    @GetMapping("/register.rol")
    public String execute(HttpServletRequest request, Model model,
                          @ModelAttribute("bean") ProfileBean bean) {

        populateCommonModel(request, model);
        model.addAttribute("authMethod", authMethod.name());
        addListsToModel(model);

        // if registration is disabled, then don't allow registration
        try {
            if (!WebloggerRuntimeConfig.getBooleanProperty("users.registration.enabled")
                    && WebloggerFactory.getWeblogger().getUserManager().getUserCount() != 0) {
                addError(model, "Register.disabled", request);
                return ".GenericError";
            }
        } catch (Exception e) {
            log.error("Error checking user count", e);
            addError(model, "generic.error.check.logs", request);
            return ".GenericError";
        }

        // For new user default to locale set in browser
        bean.setLocale(request.getLocale().toString());

        // For new user default to timezone of server
        bean.setTimeZone(TimeZone.getDefault().getID());

        try {
            if (WebloggerConfig.getAuthMethod() == AuthMethod.LDAP) {
                User fromSSOUser = CustomUserRegistry.getUserDetailsFromAuthentication(request);
                if (fromSSOUser != null) {
                    bean.copyFrom(fromSSOUser);
                }
            } else if (WebloggerConfig.getAuthMethod() == AuthMethod.CMA) {
                if (request.getUserPrincipal() != null) {
                    bean.setUserName(request.getUserPrincipal().getName());
                    bean.setScreenName(request.getUserPrincipal().getName());
                }
            }
        } catch (Exception ex) {
            log.error("Error reading SSO user data", ex);
            addError(model, "error.editing.user", ex.toString(), request);
        }

        return ".Register";
    }

    @PostMapping("/register!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @ModelAttribute("bean") ProfileBean bean) {

        populateCommonModel(request, model);
        model.addAttribute("authMethod", authMethod.name());
        addListsToModel(model);

        // if registration is disabled, then don't allow registration
        try {
            if (!WebloggerRuntimeConfig.getBooleanProperty("users.registration.enabled")
                    && WebloggerFactory.getWeblogger().getUserManager().getUserCount() != 0) {
                addError(model, "Register.disabled", request);
                return ".GenericError";
            }
        } catch (Exception e) {
            log.error("Error checking user count", e);
            return ".GenericError";
        }

        myValidate(request, model, bean);

        if (!hasErrors(model)) {
            try {
                UserManager mgr = WebloggerFactory.getWeblogger().getUserManager();

                // copy form data into new user pojo
                User ud = new User();
                bean.copyTo(ud);
                ud.setUserName(bean.getUserName());
                ud.setDateCreated(new java.util.Date());
                ud.setEnabled(Boolean.TRUE);

                // If user set both password and passwordConfirm then reset password
                if (!StringUtils.isEmpty(bean.getPasswordText())
                        && !StringUtils.isEmpty(bean.getPasswordConfirm())) {
                    ud.resetPassword(bean.getPasswordText());
                }

                // are we using email activation?
                boolean activationEnabled = WebloggerRuntimeConfig.getBooleanProperty(
                        "user.account.email.activation");
                if (activationEnabled) {
                    ud.setEnabled(Boolean.FALSE);
                    String inActivationCode = UUID.randomUUID().toString();
                    inActivationCode = retryActivationCode(mgr, inActivationCode);
                    ud.setActivationCode(inActivationCode);
                }

                String openidurl = bean.getOpenIdUrl();
                if (openidurl != null) {
                    if (openidurl.endsWith("/")) {
                        openidurl = openidurl.substring(0, openidurl.length() - 1);
                    }
                    ud.setOpenIdUrl(openidurl);
                }

                // save new user
                mgr.addUser(ud);
                WebloggerFactory.getWeblogger().flush();

                // now send activation email if necessary
                String activationStatus = null;
                if (activationEnabled && ud.getActivationCode() != null) {
                    try {
                        MailUtil.sendUserActivationEmail(ud);
                    } catch (WebloggerException ex) {
                        log.error("Error sending activation email to - " + ud.getEmailAddress(), ex);
                    }
                    activationStatus = "pending";
                }
                model.addAttribute("activationStatus", activationStatus);

                // Invalidate session
                request.getSession().removeAttribute(RollerSession.ROLLER_SESSION);
                request.getSession().invalidate();

                model.addAttribute("pageTitle", "welcome.title");
                return ".Welcome";

            } catch (WebloggerException ex) {
                log.error("Error adding new user", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }

        return ".Register";
    }

    @GetMapping("/register!activate.rol")
    public String activate(HttpServletRequest request, Model model,
                           @RequestParam(value = "activationCode", required = false) String activationCode) {

        populateCommonModel(request, model);
        model.addAttribute("authMethod", authMethod.name());

        String activationStatus = null;

        try {
            UserManager mgr = WebloggerFactory.getWeblogger().getUserManager();

            if (activationCode == null) {
                addError(model, "error.activate.user.missingActivationCode", request);
            } else {
                User user = mgr.getUserByActivationCode(activationCode);
                if (user != null) {
                    user.setEnabled(Boolean.TRUE);
                    user.setActivationCode(null);
                    mgr.saveUser(user);
                    WebloggerFactory.getWeblogger().flush();
                    activationStatus = "active";
                } else {
                    addError(model, "error.activate.user.invalidActivationCode", request);
                }
            }
        } catch (WebloggerException e) {
            addError(model, e.getMessage(), request);
            log.error("ERROR in activateUser", e);
        }

        if (hasErrors(model)) {
            activationStatus = "error";
        }

        model.addAttribute("activationStatus", activationStatus);
        model.addAttribute("pageTitle", "welcome.title");
        return ".Welcome";
    }

    private void myValidate(HttpServletRequest request, Model model, ProfileBean bean) {
        // if using external auth, we don't want to error on empty password/username
        boolean usingSSO = authMethod == AuthMethod.LDAP || authMethod == AuthMethod.CMA;
        if (usingSSO) {
            String unusedPassword = WebloggerConfig.getProperty(
                    "users.passwords.externalAuthValue", "<externalAuth>");
            preserveUsernameAndPassword(request, bean, unusedPassword);
        }

        String allowed = WebloggerConfig.getProperty("username.allowedChars");
        if (allowed == null || allowed.isBlank()) {
            allowed = DEFAULT_ALLOWED_CHARS;
        }

        String safe = CharSetUtils.keep(bean.getUserName(), allowed);
        if (!safe.equals(bean.getUserName())) {
            addError(model, "error.add.user.badUserName", request);
        }

        // check password
        if (AuthMethod.ROLLERDB.name().equals(authMethod.name())
                && StringUtils.isEmpty(bean.getPasswordText())) {
            addError(model, "error.add.user.passwordEmpty", request);
            return;
        }

        // User.password does not allow null, so generate one
        if (authMethod.name().equals(AuthMethod.OPENID.name())
                || (authMethod.name().equals(AuthMethod.DB_OPENID.name())
                && !StringUtils.isEmpty(bean.getOpenIdUrl()))) {
            String randomString = RandomStringUtils.secure().nextAlphanumeric(255);
            bean.setPasswordText(randomString);
            bean.setPasswordConfirm(randomString);
        }

        // check that passwords match
        if (!StringUtils.equals(bean.getPasswordText(), bean.getPasswordConfirm())) {
            addError(model, "userRegister.error.mismatchedPasswords", request);
        }

        // check that username is not taken
        checkUsername(model, bean, request);

        // check that OpenID, if provided, is not taken
        checkOpenID(model, bean, request);
    }

    private void preserveUsernameAndPassword(HttpServletRequest request, ProfileBean bean,
                                             String unusedPassword) {
        User fromSSOUser = CustomUserRegistry.getUserDetailsFromAuthentication(request);
        if (fromSSOUser != null) {
            bean.setPasswordText(unusedPassword);
            bean.setPasswordConfirm(unusedPassword);
            bean.setUserName(fromSSOUser.getUserName());
        } else if (request.getUserPrincipal() != null) {
            bean.setUserName(request.getUserPrincipal().getName());
            bean.setPasswordText(unusedPassword);
            bean.setPasswordConfirm(unusedPassword);
        }
    }

    private void checkUsername(Model model, ProfileBean bean, HttpServletRequest request) {
        if (!StringUtils.isEmpty(bean.getUserName())) {
            try {
                UserManager mgr = WebloggerFactory.getWeblogger().getUserManager();
                if (mgr.getUserByUserName(bean.getUserName(), null) != null) {
                    addError(model, "error.add.user.userNameInUse", request);
                    bean.setUserName(null);
                }
            } catch (WebloggerException ex) {
                log.error("error checking for user", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }
    }

    private void checkOpenID(Model model, ProfileBean bean, HttpServletRequest request) {
        if (!StringUtils.isEmpty(bean.getOpenIdUrl())) {
            try {
                UserManager mgr = WebloggerFactory.getWeblogger().getUserManager();
                if (mgr.getUserByOpenIdUrl(bean.getOpenIdUrl()) != null) {
                    addError(model, "error.add.user.openIdInUse", request);
                    bean.setOpenIdUrl(null);
                }
            } catch (WebloggerException ex) {
                log.error("error checking OpenID URL", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }
    }

    private String retryActivationCode(UserManager mgr, String inActivationCode)
            throws WebloggerException {
        if (mgr.getUserByActivationCode(inActivationCode) != null) {
            int numOfRetries = 3;
            for (int i = 0; i < numOfRetries; i++) {
                inActivationCode = UUID.randomUUID().toString();
                if (mgr.getUserByActivationCode(inActivationCode) == null) {
                    break;
                } else {
                    inActivationCode = null;
                }
            }
            if (inActivationCode == null) {
                throw new WebloggerException("error.add.user.activationCodeInUse");
            }
        }
        return inActivationCode;
    }

    private void addListsToModel(Model model) {
        model.addAttribute("localesList", org.apache.roller.weblogger.ui.controllers.util.UIUtils.getLocales());
        model.addAttribute("timeZonesList", org.apache.roller.weblogger.ui.controllers.util.UIUtils.getTimeZones());
    }
}
