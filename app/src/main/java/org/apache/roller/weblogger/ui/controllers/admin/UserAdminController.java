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

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.roller.weblogger.config.AuthMethod;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;


/**
 * Action which displays user admin search page.
 */
@Controller
@RequestMapping("/roller-ui/admin")
public class UserAdminController extends BaseController {

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }

    @Override
    public String getPageTitle() {
        return "userAdmin.title.searchUser";
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @Override
    public String getActionName() {
        return "userAdmin";
    }

    @GetMapping("/userAdmin.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        AuthMethod authMethod = WebloggerConfig.getAuthMethod();
        model.addAttribute("authMethod", authMethod.name());
        model.addAttribute("bean", new CreateUserBean());

        return ".UserAdmin";
    }

    @PostMapping("/userAdmin!edit.rol")
    public String edit(HttpServletRequest request, Model model,
                       CreateUserBean bean) {
        populateCommonModel(request, model);

        model.addAttribute("authMethod", WebloggerConfig.getAuthMethod().name());
        model.addAttribute("bean", bean);

        return "redirect:/roller-ui/admin/modifyUser.rol?bean.userName=" + bean.getUserName();
    }
}
