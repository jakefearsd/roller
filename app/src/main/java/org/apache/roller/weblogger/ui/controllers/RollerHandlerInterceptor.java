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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

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

    private static final Logger log = LoggerFactory.getLogger(RollerHandlerInterceptor.class);

    private static final String LOGIN_URL = "/roller-ui/login.rol";
    private static final String ACCESS_DENIED_URL = "/roller-ui/access-denied.rol";

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

        // --- 2. Resolve weblog from request parameter or URI template ---
        String weblogHandle = resolveWeblogHandle(request);
        Weblog actionWeblog = null;
        if (weblogHandle != null && !weblogHandle.isBlank()) {
            try {
                actionWeblog = WebloggerFactory.getWeblogger()
                        .getWeblogManager()
                        .getWeblogByHandle(weblogHandle);
            } catch (Exception e) {
                log.warn("Error looking up weblog with handle: {}", weblogHandle, e);
            }
        }
        if (actionWeblog != null) {
            request.setAttribute("actionWeblog", actionWeblog);
        }

        // --- 3. Enforce security if controller implements UISecurityEnforced ---
        if (controller instanceof UISecurityEnforced secured) {
            UserManager umgr = WebloggerFactory.getWeblogger().getUserManager();
            boolean apiHandler = isApiHandler(handlerMethod);

            // Check if user is required
            if (secured.isUserRequired()) {
                if (authenticatedUser == null) {
                    if (apiHandler) {
                        throw ApiException.unauthorized("Authentication required.");
                    }
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
                        log.debug("DENIED: user {} does not have global permission = {}",
                                authenticatedUser.getUserName(), perm);
                        if (apiHandler) {
                            throw forbidden();
                        }
                        response.sendRedirect(request.getContextPath() + ACCESS_DENIED_URL);
                        return false;
                    }
                }

                // Check if weblog is required
                if (secured.isWeblogRequired()) {
                    if (actionWeblog == null) {
                        log.warn("User {} unable to process action because no weblog was defined "
                                        + "(check that the form provides the weblog value).",
                                authenticatedUser.getUserName());
                        if (apiHandler) {
                            // Not found, not forbidden: no weblog was even
                            // resolved to check a permission against, so
                            // there is nothing to be "forbidden" from --
                            // matches BaseApiController.requireActionWeblog's
                            // identical contract for the same condition.
                            throw ApiException.notFound("No such weblog.");
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
                            log.debug("DENIED: user {} does not have weblog permission = {}",
                                    authenticatedUser.getUserName(), required);
                            if (apiHandler) {
                                throw forbidden();
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
     * The action weblog's handle for this request.
     *
     * <p>The JSP UI submits it as a {@code weblog} request parameter and has
     * no {@code {handle}} URI template variable on any of its routes; the
     * REST API carries it as a {@code {handle}} template variable, and a
     * REST handler method reads that same variable directly to decide what
     * it acts on. Spring MVC populates URI_TEMPLATE_VARIABLES_ATTRIBUTE
     * during getHandler(), before any interceptor runs, so both are readable
     * here -- which is what lets one interceptor enforce permissions for
     * both surfaces instead of the API growing a second, divergent
     * implementation.
     *
     * <p><b>The path variable wins when both are present.</b> A {@code
     * weblog=} query parameter is JSP vocabulary, not REST vocabulary --
     * nothing in a {@code {handle}}-carrying route's own handler reads it.
     * Preferring the parameter would let this resolver (and therefore
     * RollerHandlerInterceptor's permission check and
     * ApiScopeInterceptor's scope ceiling) agree with each other while both
     * disagree with the handler method actually invoked, which reads {@code
     * {handle}} directly -- a caller could then pass permission and scope
     * checks for one weblog while the handler acted on a different one
     * named only in the query string.
     *
     * <p>Package-visible and static so it can be tested without a container.
     */
    static String resolveWeblogHandle(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars instanceof Map<?, ?> map) {
            Object fromPath = map.get("handle");
            if (fromPath instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        String handle = request.getParameter("weblog");
        if (handle != null && !handle.isBlank()) {
            return handle;
        }
        return null;
    }

    /** The package every REST API controller lives under. */
    private static final String API_PACKAGE_PREFIX = "org.apache.roller.weblogger.ui.restapi";

    /**
     * True when a permission failure on this request must be answered as
     * problem+json rather than a browser redirect.
     *
     * <p>Decided from the handler's own bean-type package, never the
     * request URI -- the same discriminator {@code ApiScopeInterceptor}'s
     * {@code @AdminScoped} check already uses, and for the same reason
     * (see that class's javadoc): {@code getRequestURI()} is undecoded per
     * the servlet spec while Spring routes on the decoded path, so a
     * string test like {@code startsWith("/api/")} can be defeated by
     * encoding while the request still reaches an API-mapped controller.
     *
     * <p>This is the fix for a real bug, not speculative hardening: before
     * this method existed, EVERY permission failure -- API or JSP alike --
     * was answered with a 302 redirect to a login/access-denied JSP page.
     * An automation client following that redirect receives an HTTP 200
     * carrying an HTML form: a *success* status with no data, which is
     * worse to debug than a clean 401/403 and can be mistaken for an empty
     * result. {@code EntriesApiDispatchTest} is what caught this, end to
     * end, through a real dispatch -- exactly the class of bug a unit test
     * of {@code WeblogOwnership}/{@code ApiException} in isolation cannot
     * see.
     */
    private static boolean isApiHandler(HandlerMethod handlerMethod) {
        String pkg = handlerMethod.getBeanType().getPackageName();
        // startsWith(prefix + ".") rather than a bare startsWith(prefix): a
        // sibling package that merely shares the string prefix (e.g. a
        // hypothetical "ui.restapiv2") must not be misclassified.
        return API_PACKAGE_PREFIX.equals(pkg) || pkg.startsWith(API_PACKAGE_PREFIX + ".");
    }

    /**
     * A permission failure's detail is intentionally generic -- it must
     * never name the weblog, the permission, or the user, any of which
     * would tell an unauthorized caller more than "no" about a resource
     * they cannot act on.
     */
    private static ApiException forbidden() {
        return ApiException.forbidden("You do not have permission to perform this action.");
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
            log.error("Error looking up user: {}", username, e);
            return null;
        }
    }
}
