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

import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;


/**
 * Allows user to view and pick from list of his/her websites.
 */
@Controller
@RequestMapping("/roller-ui")
public class MainMenuController extends BaseController {

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

    private void populateMenuData(HttpServletRequest request, Model model) {
        User user = getAuthenticatedUser(request);
        model.addAttribute("existingPermissions", getExistingPermissions(user));
        model.addAttribute("userIsAdmin", isUserIsAdmin(user));
    }

    private List<WeblogPermission> getExistingPermissions(User user) {
        try {
            UserManager mgr = weblogger.getUserManager();
            return mgr.getWeblogPermissions(user);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private boolean isUserIsAdmin(User user) {
        try {
            GlobalPermission adminPerm = new GlobalPermission(
                    Collections.singletonList(GlobalPermission.ADMIN));
            UserManager umgr = weblogger.getUserManager();
            return umgr.checkPermission(adminPerm, user);
        } catch (Exception e) {
            return false;
        }
    }
}
