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
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.util.Utilities;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Allows weblog admin to list/modify member permissions, and to grant a
 * fresh permission to an existing account by username. Granting is
 * immediate -- no invitation, no acceptance step -- via the same
 * {@link UserManager#grantWeblogPermission} the table below uses to change
 * an existing member's role.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class MembersController extends BaseController {

    private static final Log log = LogFactory.getLog(MembersController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "members";
    }

    @Override
    public String getPageTitle() {
        return "memberPermissions.title";
    }

    @GetMapping("/members.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        model.addAttribute("weblogPermissions", getWeblogPermissions(request));
        return ".Members";
    }

    @PostMapping("/members!save.rol")
    public String save(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        int numAdmins = 0;
        int removed = 0;
        int changed = 0;
        List<WeblogPermission> permsList = new ArrayList<>();

        try {
            UserManager userMgr = weblogger.getUserManager();
            List<WeblogPermission> permsFromDB = userMgr.getWeblogPermissions(getActionWeblog(request));

            for (WeblogPermission perm : permsFromDB) {
                permsList.add(perm);
            }

            User user = getAuthenticatedUser(request);
            boolean error = false;
            for (WeblogPermission perms : permsList) {
                String sval = request.getParameter("perm-" + perms.getUser().getId());
                if (sval != null) {
                    if (sval.equals(WeblogPermission.ADMIN)) {
                        numAdmins++;
                    }
                    if (perms.getUser().getUserName().equals(user.getUserName())) {
                        if (!sval.equals(WeblogPermission.ADMIN)) {
                            error = true;
                            addError(model, "memberPermissions.noSelfModifications", request);
                        }
                    }
                }
            }
            if (numAdmins == 0) {
                addError(model, "memberPermissions.oneAdminRequired", request);
                error = true;
            }

            for (WeblogPermission perms : permsList) {
                String sval = request.getParameter("perm-" + perms.getUser().getId());
                if (sval != null) {
                    if (!error && !perms.hasAction(sval)) {
                        if ("-1".equals(sval)) {
                            userMgr.revokeWeblogPermission(
                                    perms.getWeblog(), perms.getUser(), WeblogPermission.ALL_ACTIONS);
                            removed++;
                        } else {
                            userMgr.revokeWeblogPermission(
                                    perms.getWeblog(), perms.getUser(), WeblogPermission.ALL_ACTIONS);
                            userMgr.grantWeblogPermission(
                                    perms.getWeblog(), perms.getUser(),
                                    Utilities.stringToStringList(sval, ","));
                            changed++;
                        }
                    }
                }
            }

            if (removed > 0 || changed > 0) {
                weblogger.flush();
            }

        } catch (Exception ex) {
            log.error("Error saving permissions on weblog - " + getActionWeblog(request).getHandle(), ex);
            addError(model, "memberPermissions.saveError", request);
        }

        if (removed > 0) {
            addMessage(model, "memberPermissions.membersRemoved", Integer.toString(removed), request);
        }
        if (changed > 0) {
            addMessage(model, "memberPermissions.membersChanged", Integer.toString(changed), request);
        }

        model.addAttribute("weblogPermissions", getWeblogPermissions(request));
        return ".Members";
    }

    /**
     * Grants an existing account access to this weblog, immediately -- no
     * invitation, no acceptance step. A username that does not resolve to an
     * enabled account is a field error, not an exception. Granting to a user
     * who already has a permission on this weblog updates that row rather
     * than creating a duplicate ({@link UserManager#grantWeblogPermission}
     * already merges into an existing row), and since this only ever adds an
     * action it can never trip the "at least one admin" invariant the way
     * revoking can.
     */
    @PostMapping("/members!grant.rol")
    public String grant(HttpServletRequest request, Model model,
                         @RequestParam(value = "userName", required = false) String userName,
                         @RequestParam(value = "permissionString", required = false) String permissionString) {
        populateCommonModel(request, model);

        UserManager userMgr = weblogger.getUserManager();
        User user = null;
        if (StringUtils.isBlank(userName)) {
            addError(model, "inviteMember.error.userNotFound", request);
        } else {
            try {
                user = userMgr.getUserByUserName(userName);
                if (user == null) {
                    addError(model, "inviteMember.error.userNotFound", request);
                }
            } catch (WebloggerException ex) {
                log.error("Error looking up user by name - " + userName, ex);
                addError(model, "memberPermissions.saveError", request);
            }
        }

        if (!hasErrors(model)) {
            try {
                userMgr.grantWeblogPermission(getActionWeblog(request), user,
                        Utilities.stringToStringList(permissionString, ","));
                weblogger.flush();
                addMessage(model, "memberPermissions.membersChanged", "1", request);
            } catch (Exception ex) {
                log.error("Error granting permission on weblog - " + getActionWeblog(request).getHandle(), ex);
                addError(model, "memberPermissions.saveError", request);
            }
        }

        model.addAttribute("weblogPermissions", getWeblogPermissions(request));
        return ".Members";
    }

    private List<WeblogPermission> getWeblogPermissions(HttpServletRequest request) {
        try {
            return weblogger.getUserManager()
                    .getWeblogPermissions(getActionWeblog(request));
        } catch (WebloggerException ex) {
            log.error("ERROR getting weblog permissions", ex);
        }
        return new ArrayList<>();
    }
}
