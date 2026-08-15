package org.apache.roller.weblogger.ui.restapi.auth;

import jakarta.servlet.FilterChain;
import org.apache.roller.weblogger.business.ApiTokenManager;
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
