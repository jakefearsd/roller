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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;


/**
 * Allow user to authorize OAuth access to his/her account.
 */
@Controller
@RequestMapping("/roller-ui")
public class OAuthAuthorizeController extends BaseController {

    private static final Log log = LogFactory.getLog(OAuthAuthorizeController.class);

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public String getPageTitle() {
        return "oauthAuthorize.title";
    }

    @GetMapping("/oauthAuthorize.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        try {
            User ud = getAuthenticatedUser(request);
            model.addAttribute("userName", ud.getUserName());
        } catch (Exception ex) {
            log.error("ERROR fetching user information", ex);
        }

        // Read OAuth attributes from the request (set by OAuth filter)
        String appDesc = (String) request.getAttribute("CONS_DESC");
        String token = (String) request.getAttribute("TOKEN");
        String callback = (String) request.getAttribute("CALLBACK");
        if (callback == null) {
            callback = "";
        }

        model.addAttribute("appDesc", appDesc);
        model.addAttribute("token", token);
        model.addAttribute("callback", callback);

        return ".OAuthAuthorize";
    }
}
