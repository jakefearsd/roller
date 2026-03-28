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

package org.apache.roller.weblogger.ui.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Spring MVC interceptor that replaces the Struts 2 UISecurityInterceptor,
 * UIActionPrepareInterceptor, and UIWeblogInterceptor.
 *
 * <p>For every request handled by a Spring MVC controller it:
 * <ol>
 *   <li>Resolves the authenticated Roller {@link User} from Spring Security
 *       and stores it as request attribute "authenticatedUser".</li>
 *   <li>Resolves the target {@link Weblog} from the "weblog" request parameter
 *       and stores it as request attribute "actionWeblog".</li>
 *   <li>Enforces security rules declared by {@link UISecurityEnforced}.</li>
 *   <li>Calls {@link UIActionPreparable#myPrepare()} if applicable.</li>
 * </ol>
 */
public class RollerHandlerInterceptor implements HandlerInterceptor {

    private static final Log log = LogFactory.getLog(RollerHandlerInterceptor.class);

    private static final String LOGIN_URL = "/roller-ui/login.rol";
    private static final String ACCESS_DENIED_URL = "/roller-ui/access-denied";

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        // Only process Spring MVC handler methods (not static resources, etc.)
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        Object controller = handlerMethod.getBean();

        // Skip all checks if the application hasn't been bootstrapped yet
        // (install/setup controllers need to run before bootstrap)
        if (!WebloggerFactory.isBootstrapped()) {
            return true;
        }

        // --- 1. Resolve authenticated user from Spring Security ---
        User authenticatedUser = resolveAuthenticatedUser();
        if (authenticatedUser != null) {
            request.setAttribute("authenticatedUser", authenticatedUser);
        }

        // --- 2. Resolve weblog from request parameter ---
        String weblogHandle = request.getParameter("weblog");
        Weblog actionWeblog = null;
        if (weblogHandle != null && !weblogHandle.isBlank()) {
            try {
                actionWeblog = WebloggerFactory.getWeblogger()
                        .getWeblogManager()
                        .getWeblogByHandle(weblogHandle);
            } catch (Exception e) {
                log.warn("Error looking up weblog with handle: " + weblogHandle, e);
            }
        }
        if (actionWeblog != null) {
            request.setAttribute("actionWeblog", actionWeblog);
        }

        // --- 3. Enforce security if controller implements UISecurityEnforced ---
        if (controller instanceof UISecurityEnforced secured) {
            UserManager umgr = WebloggerFactory.getWeblogger().getUserManager();

            // Check if user is required
            if (secured.isUserRequired()) {
                if (authenticatedUser == null) {
                    log.debug("DENIED: required user not found, redirecting to login");
                    response.sendRedirect(request.getContextPath() + LOGIN_URL);
                    return false;
                }

                // Check global permissions
                if (secured.requiredGlobalPermissionActions() != null
                        && !secured.requiredGlobalPermissionActions().isEmpty()) {
                    GlobalPermission perm = new GlobalPermission(
                            secured.requiredGlobalPermissionActions());
                    if (!umgr.checkPermission(perm, authenticatedUser)) {
                        if (log.isDebugEnabled()) {
                            log.debug(String.format(
                                    "DENIED: user %s does not have global permission = %s",
                                    authenticatedUser.getUserName(), perm));
                        }
                        response.sendRedirect(request.getContextPath() + ACCESS_DENIED_URL);
                        return false;
                    }
                }

                // Check if weblog is required
                if (secured.isWeblogRequired()) {
                    if (actionWeblog == null) {
                        if (log.isWarnEnabled()) {
                            log.warn(String.format(
                                    "User %s unable to process action because no weblog was defined "
                                            + "(check that the form provides the weblog value).",
                                    authenticatedUser.getUserName()));
                        }
                        response.sendRedirect(request.getContextPath() + ACCESS_DENIED_URL);
                        return false;
                    }

                    // Check weblog-level permissions
                    if (secured.requiredWeblogPermissionActions() != null
                            && !secured.requiredWeblogPermissionActions().isEmpty()) {
                        WeblogPermission required = new WeblogPermission(
                                actionWeblog,
                                secured.requiredWeblogPermissionActions());
                        if (!umgr.checkPermission(required, authenticatedUser)) {
                            if (log.isDebugEnabled()) {
                                log.debug(String.format(
                                        "DENIED: user %s does not have weblog permission = %s",
                                        authenticatedUser.getUserName(), required));
                            }
                            response.sendRedirect(request.getContextPath() + ACCESS_DENIED_URL);
                            return false;
                        }
                    }
                }
            }
        }

        // --- 4. Call myPrepare() if controller implements UIActionPreparable ---
        if (controller instanceof UIActionPreparable preparable) {
            preparable.myPrepare();
        }

        return true;
    }

    /**
     * Resolve the Roller User from the Spring Security context.
     * Returns null if no user is authenticated.
     */
    private User resolveAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object principal = authentication.getPrincipal();
        String username = null;
        if (principal instanceof UserDetails userDetails) {
            username = userDetails.getUsername();
        } else if (principal instanceof String name) {
            // Some authentication tokens store the username directly as a String
            if (!"anonymousUser".equals(name)) {
                username = name;
            }
        }

        if (username == null) {
            return null;
        }

        try {
            return WebloggerFactory.getWeblogger()
                    .getUserManager()
                    .getUserByUserName(username);
        } catch (Exception e) {
            log.error("Error looking up user: " + username, e);
            return null;
        }
    }
}
