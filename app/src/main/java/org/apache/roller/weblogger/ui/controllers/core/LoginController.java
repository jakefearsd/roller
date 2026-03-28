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

import jakarta.servlet.http.HttpServletRequest;

import org.apache.roller.weblogger.config.AuthMethod;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;


/**
 * Handle user logins.
 */
@Controller
@RequestMapping("/roller-ui")
public class LoginController extends BaseController {

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
        return "loginPage.title";
    }

    @GetMapping("/login.rol")
    public String execute(HttpServletRequest request, Model model,
                          @RequestParam(value = "error", required = false) String error) {

        populateCommonModel(request, model);
        model.addAttribute("authMethod", authMethod.name());

        // set action error message if there was login error
        if (error != null) {
            if (authMethod == AuthMethod.OPENID) {
                addError(model, "error.unmatched.openid", request);
            } else {
                addError(model, "error.password.mismatch", request);
            }
        }

        return ".Login";
    }
}
