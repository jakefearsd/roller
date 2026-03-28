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

import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;


/**
 * Allows user to view and pick from list of his/her websites.
 */
@Controller
@RequestMapping("/roller-ui")
public class MainMenuController extends BaseController {

    private static final Log log = LogFactory.getLog(MainMenuController.class);

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public String getPageTitle() {
        return "yourWebsites.title";
    }

    @GetMapping("/menu.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        populateMenuData(request, model);
        return ".MainMenu";
    }

    @GetMapping("/menu!accept.rol")
    public String accept(HttpServletRequest request, Model model,
                         @RequestParam(value = "inviteId", required = false) String inviteId) {

        populateCommonModel(request, model);

        try {
            UserManager umgr = WebloggerFactory.getWeblogger().getUserManager();
            WeblogManager wmgr = WebloggerFactory.getWeblogger().getWeblogManager();
            Weblog weblog = wmgr.getWeblog(inviteId);
            umgr.confirmWeblogPermission(weblog, getAuthenticatedUser(request));
            WebloggerFactory.getWeblogger().flush();
        } catch (WebloggerException ex) {
            log.error("Error handling invitation accept weblog id - " + inviteId, ex);
            addError(model, "yourWebsites.permNotFound", request);
        }

        populateMenuData(request, model);
        return ".MainMenu";
    }

    @GetMapping("/menu!decline.rol")
    public String decline(HttpServletRequest request, Model model,
                          @RequestParam(value = "inviteId", required = false) String inviteId) {

        populateCommonModel(request, model);

        try {
            UserManager umgr = WebloggerFactory.getWeblogger().getUserManager();
            WeblogManager wmgr = WebloggerFactory.getWeblogger().getWeblogManager();
            Weblog weblog = wmgr.getWeblog(inviteId);
            String handle = weblog.getHandle();
            umgr.declineWeblogPermission(weblog, getAuthenticatedUser(request));
            WebloggerFactory.getWeblogger().flush();
            addMessage(model, "yourWebsites.declined", handle, request);
        } catch (WebloggerException ex) {
            log.error("Error handling invitation decline weblog id - " + inviteId, ex);
            addError(model, "yourWebsites.permNotFound", request);
        }

        populateMenuData(request, model);
        return ".MainMenu";
    }

    private void populateMenuData(HttpServletRequest request, Model model) {
        User user = getAuthenticatedUser(request);
        model.addAttribute("existingPermissions", getExistingPermissions(user));
        model.addAttribute("pendingPermissions", getPendingPermissions(user));
        model.addAttribute("userIsAdmin", isUserIsAdmin(user));
    }

    private List<WeblogPermission> getExistingPermissions(User user) {
        try {
            UserManager mgr = WebloggerFactory.getWeblogger().getUserManager();
            return mgr.getWeblogPermissions(user);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<WeblogPermission> getPendingPermissions(User user) {
        try {
            UserManager mgr = WebloggerFactory.getWeblogger().getUserManager();
            return mgr.getPendingWeblogPermissions(user);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private boolean isUserIsAdmin(User user) {
        try {
            GlobalPermission adminPerm = new GlobalPermission(
                    Collections.singletonList(GlobalPermission.ADMIN));
            UserManager umgr = WebloggerFactory.getWeblogger().getUserManager();
            return umgr.checkPermission(adminPerm, user);
        } catch (Exception e) {
            return false;
        }
    }
}
