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

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Action for resigning from a weblog.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class MemberResignController extends BaseController {

    private static final Log log = LogFactory.getLog(MemberResignController.class);

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.singletonList(WeblogPermission.EDIT_DRAFT);
    }

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "memberResign";
    }

    @Override
    public String getPageTitle() {
        return "yourWebsites.resign";
    }

    @GetMapping("/memberResign.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        return ".MemberResign";
    }

    @PostMapping("/memberResign!resign.rol")
    public String resign(HttpServletRequest request, Model model,
                         RedirectAttributes redirectAttributes) {
        populateCommonModel(request, model);

        try {
            UserManager umgr = WebloggerFactory.getWeblogger().getUserManager();
            umgr.revokeWeblogPermission(getActionWeblog(request), getAuthenticatedUser(request),
                    WeblogPermission.ALL_ACTIONS);
            WebloggerFactory.getWeblogger().flush();

            String weblogHandle = request.getParameter("weblog");
            addFlashMessage(redirectAttributes, "yourWebsites.resigned", weblogHandle != null ? weblogHandle : "", request);

            return "redirect:/roller-ui/menu.rol";
        } catch (WebloggerException ex) {
            log.error("Error doing weblog resign - " + getActionWeblog(request).getHandle(), ex);
            addFlashError(redirectAttributes, "Resignation failed - check system logs", request);
        }

        return "redirect:/roller-ui/menu.rol";
    }
}
