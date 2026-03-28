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

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.util.MailUtil;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Allows website admin to invite new members to website.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class MembersInviteController extends BaseController {

    private static final Log log = LogFactory.getLog(MembersInviteController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "invite";
    }

    @Override
    public String getPageTitle() {
        return "inviteMember.title";
    }

    @GetMapping("/invite.rol")
    public String execute(HttpServletRequest request, Model model,
                          RedirectAttributes redirectAttributes) {
        populateCommonModel(request, model);

        if (!WebloggerConfig.getBooleanProperty("groupblogging.enabled")) {
            addFlashError(redirectAttributes, "inviteMember.disabled", request);
            return "redirect:/roller-ui/authoring/members.rol?weblog="
                    + getActionWeblog(request).getHandle();
        }

        return ".MembersInvite";
    }

    @PostMapping("/invite!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @RequestParam(value = "userName", required = false) String userName,
                       @RequestParam(value = "permissionString", required = false) String permissionString,
                       RedirectAttributes redirectAttributes) {
        populateCommonModel(request, model);

        if (!WebloggerConfig.getBooleanProperty("groupblogging.enabled")) {
            addFlashError(redirectAttributes, "inviteMember.disabled", request);
            return "redirect:/roller-ui/authoring/members.rol?weblog="
                    + getActionWeblog(request).getHandle();
        }

        UserManager umgr = WebloggerFactory.getWeblogger().getUserManager();
        User user = null;
        try {
            user = umgr.getUserByUserName(userName);
            if (user == null) {
                addError(model, "inviteMember.error.userNotFound", request);
            }
        } catch (WebloggerException ex) {
            log.error("Error looking up user by id - " + userName, ex);
            addError(model, "Error looking up invitee", request);
        }

        if (hasErrors(model)) {
            model.addAttribute("userName", userName);
            model.addAttribute("permissionString", permissionString);
            return ".MembersInvite";
        }

        try {
            WeblogPermission perm = umgr.getWeblogPermissionIncludingPending(getActionWeblog(request), user);
            if (perm != null && perm.isPending()) {
                addError(model, "inviteMember.error.userAlreadyInvited", request);
            } else if (perm != null) {
                addError(model, "inviteMember.error.userAlreadyMember", request);
            }
        } catch (WebloggerException ex) {
            log.error("Error looking up permissions for weblog - " + getActionWeblog(request).getHandle(), ex);
            addError(model, "Error checking existing permissions", request);
        }

        if (!hasErrors(model)) {
            try {
                umgr.grantWeblogPermissionPending(getActionWeblog(request), user,
                        Collections.singletonList(permissionString));
                WebloggerFactory.getWeblogger().flush();

                addFlashMessage(redirectAttributes, "inviteMember.userInvited", request);

                if (MailUtil.isMailConfigured()) {
                    try {
                        MailUtil.sendWeblogInvitation(getActionWeblog(request), user);
                    } catch (WebloggerException e) {
                        addFlashMessage(redirectAttributes, "error.untranslated", e.getMessage(), request);
                    }
                }

                return "redirect:/roller-ui/authoring/members.rol?weblog="
                        + getActionWeblog(request).getHandle();

            } catch (Exception ex) {
                log.error("Error creating user invitation", ex);
                addError(model, "Error creating user invitation - check Roller logs", request);
            }
        }

        model.addAttribute("userName", userName);
        model.addAttribute("permissionString", permissionString);
        return ".MembersInvite";
    }
}
