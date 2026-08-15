package org.apache.roller.weblogger.ui.restapi.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Applies the token's ceiling on top of the permission checks
 * RollerHandlerInterceptor already performs.
 *
 * <p>This narrows; it never grants. A request that passes here still has to
 * satisfy the caller's real GlobalPermission/WeblogPermission.
 */
public class ApiScopeInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        ApiPrincipal principal = currentPrincipal();
        if (principal == null) {
            return true;
        }

        String handle = pathHandle(request);
        if (principal.scopeWeblog() != null
                && handle != null
                && !principal.scopeWeblog().equals(handle)) {
            // 404 rather than 403: a 403 confirms the weblog exists.
            throw ApiException.notFound("No such weblog.");
        }

        ApiToken.Role role = principal.scopeRole();
        if (isAdminPath(request) && role != ApiToken.Role.ADMIN) {
            throw ApiException.forbidden("This token is not scoped for administration.");
        }
        if (!isRead(request) && role == ApiToken.Role.READ) {
            throw ApiException.forbidden("This token is read-only.");
        }
        return true;
    }

    private static ApiPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        return auth.getDetails() instanceof ApiPrincipal p ? p : null;
    }

    private static String pathHandle(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars instanceof Map<?, ?> map && map.get("handle") instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    private static boolean isAdminPath(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/api/v1/admin/");
    }

    private static boolean isRead(HttpServletRequest request) {
        String method = request.getMethod();
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }
}
