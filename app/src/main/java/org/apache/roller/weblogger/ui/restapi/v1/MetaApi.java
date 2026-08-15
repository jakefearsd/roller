package org.apache.roller.weblogger.ui.restapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.auth.ApiPrincipal;
import org.apache.roller.weblogger.ui.restapi.auth.WeblogScopeExempt;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness for the API surface. Mapped at {@code /v1} because the container
 * strips the {@code /api} prefix -- see ServletRegistrationConfig's
 * API_URL_PATTERNS.
 */
@RestController
@RequestMapping("/v1")
public class MetaApi {

    /**
     * A weblog-scoped token has no weblog to check on this route -- exempt,
     * or {@code ApiScopeInterceptor}'s deny-by-default would lock a CLI's
     * own self-check out of its own scope.
     */
    @WeblogScopeExempt
    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Who the caller is, and -- when authenticated via a scoped API token --
     * what that token's ceiling is. {@code authenticatedUser} is the request
     * attribute {@code RollerHandlerInterceptor} sets for every request,
     * Bearer- or Basic-authenticated alike.
     *
     * <p>{@code @WeblogScopeExempt} for the same reason as {@link #ping()}:
     * this route names no weblog, and a weblog-scoped token must still be
     * able to ask who it is.
     */
    @WeblogScopeExempt
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        User user = (User) request.getAttribute("authenticatedUser");
        if (user == null) {
            throw ApiException.forbidden("Not authenticated.");
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        ApiPrincipal principal =
                auth != null && auth.getDetails() instanceof ApiPrincipal p ? p : null;
        return ResponseEntity.ok(Map.of(
                "userName", user.getUserName(),
                "screenName", user.getScreenName(),
                "globalAdmin", user.hasGlobalPermission(GlobalPermission.ADMIN),
                "tokenScope", principal == null ? Map.of() : Map.of(
                        "weblog", principal.scopeWeblog() == null ? "" : principal.scopeWeblog(),
                        "role", principal.scopeRole().name())));
    }
}
