package org.apache.roller.weblogger.ui.restapi.auth;

import jakarta.servlet.FilterChain;
import org.apache.roller.weblogger.business.ApiTokenManager;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiTokenAuthFilterTest {

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

        new ApiTokenAuthFilter(() -> mgr)
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

        new ApiTokenAuthFilter(() -> mgr)
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

        new ApiTokenAuthFilter(() -> mgr)
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(mgr);
    }
}
