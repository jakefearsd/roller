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

import jakarta.servlet.http.HttpServletRequest;

import net.oauth.OAuthConsumer;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.business.OAuthManager;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;


/**
 * Allows user to view his/her OAuth consumer key and secret.
 */
@Controller
@RequestMapping("/roller-ui")
public class OAuthKeysController extends BaseController {

    private static final Log log = LogFactory.getLog(OAuthKeysController.class);

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public String getPageTitle() {
        return "oauthKeys.title";
    }

    @GetMapping("/oauthKeys.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        boolean flush = false;

        try {
            User ud = getAuthenticatedUser(request);
            OAuthManager omgr = WebloggerFactory.getWeblogger().getOAuthManager();
            OAuthConsumer userConsumer = omgr.getConsumerByUsername(ud.getUserName());
            if (userConsumer == null) {
                String consumerKey = DigestUtils.md5Hex(ud.getUserName());
                userConsumer = omgr.addConsumer(ud.getUserName(), consumerKey);
                flush = true;
            }
            model.addAttribute("userConsumer", userConsumer);

            if (isUserIsAdmin(ud)) {
                OAuthConsumer siteWideConsumer = omgr.getConsumer();
                if (siteWideConsumer == null) {
                    String consumerKey = DigestUtils.md5Hex(
                            WebloggerRuntimeConfig.getAbsoluteContextURL());
                    siteWideConsumer = omgr.addConsumer(consumerKey);
                    flush = true;
                }
                model.addAttribute("siteWideConsumer", siteWideConsumer);
            }

            if (flush) {
                WebloggerFactory.getWeblogger().flush();
            }

        } catch (Exception ex) {
            log.error("ERROR creating or retrieving your OAuth information", ex);
        }

        model.addAttribute("requestTokenURL",
                WebloggerFactory.getWeblogger().getUrlStrategy().getOAuthRequestTokenURL());
        model.addAttribute("authorizationURL",
                WebloggerFactory.getWeblogger().getUrlStrategy().getOAuthAuthorizationURL());
        model.addAttribute("accessTokenURL",
                WebloggerFactory.getWeblogger().getUrlStrategy().getOAuthAccessTokenURL());

        return ".OAuthKeys";
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
