package org.apache.roller.weblogger.ui.restapi.auth;

import jakarta.servlet.FilterChain;
import org.apache.roller.weblogger.business.ApiTokenManager;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.ui.restapi.ApiProblemWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiTokenAuthFilterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ApiProblemWriter PROBLEM_WRITER = new ApiProblemWriter(OBJECT_MAPPER);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static ApiToken token(String weblog, ApiToken.Role role) {
        User user = new User();
        user.setUserName("agent");
        ApiToken t = new ApiToken();
        t.setUser(user);
        t.setScopeWeblog(weblog);
        t.setScopeRole(role);
        return t;
    }

    @Test
    void aValidBearerTokenAuthenticatesWithTheUserNameAsPrincipal() throws Exception {
        ApiTokenManager mgr = mock(ApiTokenManager.class);
        when(mgr.authenticate("rlr_good")).thenReturn(token("testblog", ApiToken.Role.POST));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ping");
        request.addHeader("Authorization", "Bearer rlr_good");
        FilterChain chain = mock(FilterChain.class);

        new ApiTokenAuthFilter(() -> mgr, PROBLEM_WRITER)
                .doFilter(request, new MockHttpServletResponse(), chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        // RollerHandlerInterceptor.resolveAuthenticatedUser has a branch for a
        // String principal, so identity resolution works unchanged.
        assertEquals("agent", auth.getPrincipal());
        ApiPrincipal details = (ApiPrincipal) auth.getDetails();
        assertEquals("testblog", details.scopeWeblog());
        assertEquals(ApiToken.Role.POST, details.scopeRole());
        verify(chain).doFilter(any(), any());
    }

    @Test
    void anUnknownTokenLeavesTheContextEmptyAndStillCallsTheChain() throws Exception {
        ApiTokenManager mgr = mock(ApiTokenManager.class);
        when(mgr.authenticate(anyString())).thenReturn(null);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ping");
        request.addHeader("Authorization", "Bearer rlr_bad");
        FilterChain chain = mock(FilterChain.class);

        new ApiTokenAuthFilter(() -> mgr, PROBLEM_WRITER)
                .doFilter(request, new MockHttpServletResponse(), chain);

        // The filter never rejects; authorization is the chain's job, so an
        // unauthenticated request reaches it and gets a 401 there.
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(any(), any());
    }

    /**
     * This filter runs inside the security chain at order 40;
     * {@code PersistenceSessionFilter} (the only thing that otherwise ever
     * releases the current thread's persistence session) is order 60. When
     * authenticate() does not establish a SecurityContext, the request is
     * about to be refused by {@code authorizeHttpRequests} --
     * {@code ExceptionTranslationFilter} hands the 401 straight to {@code
     * ApiAuthenticationEntryPoint} without ever calling the outer chain, so
     * order 60 never runs. Left unreleased, the EntityManager this filter's
     * own digest lookup just bound to this Tomcat worker thread would stay
     * open -- and bound to whatever persistence context it accumulated --
     * until some unrelated later request on the same thread happened to
     * reach order 60 and inherit it.
     */
    @Test
    void aFailedAuthenticationReleasesThePersistenceSessionSinceOrder60NeverRuns() throws Exception {
        MockWeblogger mocks = MockWeblogger.install();
        try {
            ApiTokenManager mgr = mock(ApiTokenManager.class);
            when(mgr.authenticate(anyString())).thenReturn(null);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
            request.addHeader("Authorization", "Bearer rlr_bad");
            FilterChain chain = mock(FilterChain.class);

            new ApiTokenAuthFilter(() -> mgr, PROBLEM_WRITER)
                    .doFilter(request, new MockHttpServletResponse(), chain);

            verify(mocks.weblogger()).release();
        } finally {
            MockWeblogger.uninstall();
        }
    }

    /**
     * The converse of the test above, and just as load-bearing: a
     * successful Bearer authentication always reaches order 60, regardless
     * of which of {@code authorizeHttpRequests}' three rules
     * ({@code POST /api/v1/tokens}'s {@code authenticated()}, {@code
     * /api/v1/ping}'s {@code permitAll()}, or the {@code
     * anyRequest().authenticated()} fallback) applies to the path -- an
     * authenticated caller satisfies {@code authenticated()} outright, and
     * {@code permitAll()} lets the request through independent of
     * authentication either way. So releasing here too would close the
     * EntityManager out from under the controller that is about to run on
     * it. Order 60 must stay the sole releaser on this path.
     */
    @Test
    void aSuccessfulAuthenticationDoesNotReleaseHere() throws Exception {
        MockWeblogger mocks = MockWeblogger.install();
        try {
            ApiTokenManager mgr = mock(ApiTokenManager.class);
            when(mgr.authenticate("rlr_good")).thenReturn(token("testblog", ApiToken.Role.POST));

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ping");
            request.addHeader("Authorization", "Bearer rlr_good");
            FilterChain chain = mock(FilterChain.class);

            new ApiTokenAuthFilter(() -> mgr, PROBLEM_WRITER)
                    .doFilter(request, new MockHttpServletResponse(), chain);

            verify(mocks.weblogger(), never()).release();
        } finally {
            MockWeblogger.uninstall();
        }
    }

    @Test
    void aMissingOrNonBearerHeaderIsIgnored() throws Exception {
        ApiTokenManager mgr = mock(ApiTokenManager.class);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tokens");
        request.addHeader("Authorization", "Basic am9objpwdw==");
        FilterChain chain = mock(FilterChain.class);

        new ApiTokenAuthFilter(() -> mgr, PROBLEM_WRITER)
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(mgr);
    }

    /**
     * A ServletFilter runs outside DispatcherServlet's reach, so
     * ApiExceptionHandler's @RestControllerAdvice can never see an exception
     * thrown from here -- a throttled request must get its problem+json body
     * written directly by the filter, and must never reach the chain.
     */
    @Test
    void aThrottledCallerGetsAProblemJsonResponseAndNeverReachesTheChain() throws Exception {
        ApiTokenManager mgr = mock(ApiTokenManager.class);
        when(mgr.authenticate(anyString())).thenReturn(null);
        // threshold 1: the first call is allowed through, the second is not.
        ApiThrottle throttle = ApiThrottle.forTesting(1, 60);
        ApiTokenAuthFilter filter = new ApiTokenAuthFilter(() -> mgr, throttle, PROBLEM_WRITER);

        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/v1/ping");
        first.addHeader("Authorization", "Bearer rlr_same");
        FilterChain firstChain = mock(FilterChain.class);
        filter.doFilter(first, new MockHttpServletResponse(), firstChain);
        verify(firstChain).doFilter(any(), any());

        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/api/v1/ping");
        second.addHeader("Authorization", "Bearer rlr_same");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain secondChain = mock(FilterChain.class);

        filter.doFilter(second, response, secondChain);

        assertEquals(429, response.getStatus());
        assertEquals("application/problem+json", response.getContentType());
        JsonNode body = OBJECT_MAPPER.readTree(response.getContentAsByteArray());
        assertEquals(429, body.get("status").asInt());
        verifyNoInteractions(secondChain);
    }
}
