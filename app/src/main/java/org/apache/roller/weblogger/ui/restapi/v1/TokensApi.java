package org.apache.roller.weblogger.ui.restapi.v1;

import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.ApiTokenManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.auth.ApiPrincipal;
import org.apache.roller.weblogger.ui.restapi.dto.TokenDtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues and manages the automation API's own long-lived credentials.
 *
 * <p>Minting is Basic-auth-only, enforced here rather than in Spring Security
 * config -- see {@code SecurityConfig.apiSecurityFilterChain}'s javadoc for
 * why the chain cannot cleanly forbid a Bearer caller from reaching this
 * endpoint, and {@code TokensApiTest#aBearerAuthenticatedCallerCannotMintAToken}
 * for the test that would catch a regression.
 */
@RestController
@RequestMapping("/v1/tokens")
public class TokensApi {

    private final Weblogger weblogger;

    public TokensApi(Weblogger weblogger) {
        this.weblogger = weblogger;
    }

    @PostMapping("")
    public ResponseEntity<TokenDtos.IssuedToken> issue(@RequestBody TokenDtos.IssueRequest request)
            throws WebloggerException {
        // A token cannot be used to mint another token: that would turn any
        // leaked token into a permanent one, with a scope the thief picks.
        if (currentApiPrincipal() != null) {
            throw ApiException.forbidden("A token cannot be used to mint another token.");
        }

        ApiToken.Role role = parseRole(request.role());
        User user = requireUser();
        Timestamp expiresAt = request.expiresAt() == null ? null : Timestamp.from(request.expiresAt());

        ApiTokenManager.Issued issued = weblogger.getApiTokenManager()
                .issueToken(user, request.label(), request.weblog(), role, expiresAt);

        TokenDtos.TokenView view = TokenDtos.toView(issued.token());
        return ResponseEntity.status(HttpStatus.CREATED).body(new TokenDtos.IssuedToken(issued.rawToken(), view));
    }

    @GetMapping("")
    public List<TokenDtos.TokenView> list() throws WebloggerException {
        // The whole /v1/tokens resource is Basic-only, the same as issue():
        // a leaked, merely-POST-scoped token must not be able to enumerate
        // every token its owner holds.
        if (currentApiPrincipal() != null) {
            throw ApiException.forbidden("Token management requires Basic authentication.");
        }
        User user = requireUser();
        return weblogger.getApiTokenManager().getTokens(user).stream()
                .map(TokenDtos::toView)
                .toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable("id") String id) throws WebloggerException {
        // Same as list(): a leaked token must not be able to revoke any of
        // its owner's other tokens, including an ADMIN one.
        if (currentApiPrincipal() != null) {
            throw ApiException.forbidden("Token management requires Basic authentication.");
        }
        User user = currentUser();
        boolean revoked = weblogger.getApiTokenManager().revoke(user, id);
        if (!revoked) {
            // Not 403: that would confirm the id exists and let one user
            // enumerate another's tokens.
            throw ApiException.notFound("No such token.");
        }
        return ResponseEntity.noContent().build();
    }

    private static ApiToken.Role parseRole(String role) {
        try {
            return ApiToken.Role.valueOf(role.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Unknown role: " + role);
        }
    }

    private User requireUser() {
        User user = currentUser();
        if (user == null) {
            throw ApiException.forbidden("Not authenticated.");
        }
        return user;
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        Object principal = auth.getPrincipal();
        String userName = null;
        if (principal instanceof UserDetails userDetails) {
            userName = userDetails.getUsername();
        } else if (principal instanceof String s && !"anonymousUser".equals(s)) {
            userName = s;
        }
        if (userName == null) {
            return null;
        }
        try {
            return weblogger.getUserManager().getUserByUserName(userName);
        } catch (WebloggerException e) {
            return null;
        }
    }

    private static ApiPrincipal currentApiPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        return auth.getDetails() instanceof ApiPrincipal p ? p : null;
    }
}
