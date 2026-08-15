package org.apache.roller.weblogger.ui.restapi.auth;

import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ApiScopeInterceptorTest {

    private final ApiScopeInterceptor interceptor = new ApiScopeInterceptor();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request(String method, String uri, String handle,
                                                  String scopeWeblog, ApiToken.Role role) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (handle != null) {
            request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                    Map.of("handle", handle));
        }
        var auth = new UsernamePasswordAuthenticationToken("agent", null, List.of());
        auth.setDetails(new ApiPrincipal("agent", scopeWeblog, role));
        SecurityContextHolder.getContext().setAuthentication(auth);
        return request;
    }

    @Test
    void aTokenScopedToOneWeblogCannotReachAnother() {
        MockHttpServletRequest request =
                request("GET", "/api/v1/weblogs/other/entries", "other", "testblog", ApiToken.Role.POST);

        ApiException thrown = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        // 404, not 403: a 403 would confirm that 'other' exists.
        assertEquals(404, thrown.getStatus());
    }

    @Test
    void aReadTokenCannotWrite() {
        MockHttpServletRequest request =
                request("POST", "/api/v1/weblogs/testblog/entries", "testblog", "testblog", ApiToken.Role.READ);

        ApiException thrown = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals(403, thrown.getStatus());
    }

    @Test
    void aPostTokenMayWriteContentButNotReachAdminPaths() throws Exception {
        assertTrue(interceptor.preHandle(
                request("POST", "/api/v1/weblogs/testblog/entries", "testblog", "testblog", ApiToken.Role.POST),
                new MockHttpServletResponse(), new Object()));

        ApiException thrown = assertThrows(ApiException.class, () -> interceptor.preHandle(
                request("POST", "/api/v1/admin/users", null, null, ApiToken.Role.POST),
                new MockHttpServletResponse(), new Object()));
        assertEquals(403, thrown.getStatus());
    }

    @Test
    void anUnscopedAdminTokenPasses() throws Exception {
        assertTrue(interceptor.preHandle(
                request("POST", "/api/v1/admin/users", null, null, ApiToken.Role.ADMIN),
                new MockHttpServletResponse(), new Object()));
    }

    /**
     * Session-authenticated requests (there are none today, but the UI could
     * grow one) carry no ApiPrincipal. The interceptor must not then invent a
     * ceiling -- it defers entirely to RollerHandlerInterceptor.
     */
    @Test
    void aRequestWithNoApiPrincipalIsLeftAlone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ping");
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }
}
