package org.apache.roller.weblogger.ui.restapi.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.business.ApiTokenManager;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.ApiProblem;
import org.apache.roller.weblogger.ui.restapi.ApiProblemWriter;
import org.apache.roller.weblogger.util.TokenGenerator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns {@code Authorization: Bearer rlr_...} into an authenticated
 * SecurityContext.
 *
 * <p>Never rejects an authentication failure: an absent or bad token simply
 * leaves the context empty and the security chain answers 401. Keeping
 * rejection in one place means the API cannot grow two different
 * unauthenticated responses.
 *
 * <p>The manager arrives through a Supplier because the business tier is
 * built lazily at {@code WebloggerFactory.bootstrap()}, after this filter
 * bean is constructed.
 */
public class ApiTokenAuthFilter extends OncePerRequestFilter {

    private static final Log log = LogFactory.getLog(ApiTokenAuthFilter.class);
    private static final String BEARER = "Bearer ";

    private final Supplier<ApiTokenManager> tokenManager;
    private final ApiThrottle throttle;
    private final ApiProblemWriter problemWriter;

    public ApiTokenAuthFilter(Supplier<ApiTokenManager> tokenManager, ApiProblemWriter problemWriter) {
        this(tokenManager, ApiThrottle.create(), problemWriter);
    }

    ApiTokenAuthFilter(Supplier<ApiTokenManager> tokenManager, ApiThrottle throttle,
                       ApiProblemWriter problemWriter) {
        this.tokenManager = tokenManager;
        this.throttle = throttle;
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        boolean bearer = header != null && header.startsWith(BEARER);
        String rawToken = bearer ? header.substring(BEARER.length()).trim() : null;

        // Key by token where there is one, by client address otherwise -- so
        // the password-taking mint endpoint is rate-limited by caller and
        // every other endpoint by credential. The digest, not the raw token,
        // is the map key: a throttle map holding live credentials in memory
        // is a credential store nobody meant to build.
        String key = bearer ? TokenGenerator.sha256Hex(rawToken) : request.getRemoteAddr();
        if (throttle.isThrottled(key)) {
            writeThrottled(request, response);
            return;
        }

        if (bearer) {
            authenticate(rawToken);
        }
        chain.doFilter(request, response);
    }

    /**
     * A ServletFilter runs before, and entirely outside, DispatcherServlet --
     * so {@code ApiExceptionHandler}'s {@code @RestControllerAdvice} can never
     * see an exception thrown from here. Building the body via {@code
     * ApiException.throttled(...).toProblem(...)} and handing it to the
     * shared {@link ApiProblemWriter} (also used by {@link
     * ApiAuthenticationEntryPoint} and {@link ApiAccessDeniedHandler}, the
     * other two writers that run outside this same reach) is what keeps this
     * error shape from drifting from {@code ApiExceptionHandler}'s.
     */
    private void writeThrottled(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        ApiProblem problem = ApiException
                .throttled("Too many requests. Slow down and retry.")
                .toProblem(request.getRequestURI());
        problemWriter.write(response, problem);
    }

    private void authenticate(String rawToken) {
        try {
            ApiToken token = tokenManager.get().authenticate(rawToken);
            if (token == null) {
                return;
            }
            String userName = token.getUser().getUserName();
            ApiPrincipal principal =
                    new ApiPrincipal(userName, token.getScopeWeblog(), token.getScopeRole());
            var auth = new UsernamePasswordAuthenticationToken(
                    userName, null, List.of(new SimpleGrantedAuthority("api")));
            auth.setDetails(principal);
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (Exception e) {
            // Never let a lookup failure become an authenticated request.
            log.error("Error authenticating API token", e);
        }
    }
}
