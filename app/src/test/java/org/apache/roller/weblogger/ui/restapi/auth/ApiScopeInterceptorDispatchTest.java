package org.apache.roller.weblogger.ui.restapi.auth;

import java.util.List;
import org.apache.roller.weblogger.boot.WebMvcConfig;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Dispatches a real request through a real {@code DispatcherServlet} wired
 * with the production {@link WebMvcConfig} bean, rather than asserting on a
 * mocked {@code InterceptorRegistry}.
 *
 * <p>This is the class the "/api/**" bug needed and did not have.
 * {@code WebMvcConfigTest} only proves the code passes the string it
 * passes -- it cannot detect that the string is the wrong one for how the
 * servlet is actually mapped, because it never asks Spring to match
 * anything against a real request. This class does: it configures each
 * request's {@code servletPath}/{@code pathInfo} to reproduce exactly what
 * {@code ServletRegistrationConfig}'s {@code "/api/*"} PREFIX mapping
 * produces on a real container (confirmed empirically against this exact
 * Spring version -- an unmodified request to {@code /api/v1/probe} 404s,
 * the same request with {@code .servletPath("/api")} reaches a controller
 * mapped at {@code /v1/probe}), so the interceptor pattern under test is
 * matched against the same lookup path production computes.
 *
 * <p>It also exercises {@code ApiScopeInterceptor} reading
 * {@code request.getAttribute("actionWeblog")} rather than re-deriving the
 * weblog from the path: {@code aTokenCannotEscapeItsScopeViaTheWeblogQueryParameter}
 * sends a request whose {@code {handle}} path variable and {@code weblog}
 * query parameter name two different weblogs, and only passes if the
 * ceiling agrees with whichever one {@code RollerHandlerInterceptor} (which
 * runs first -- see below) actually resolved and would enforce permissions
 * against.
 *
 * <p><b>Registration order.</b> {@code WebMvcConfig.addInterceptors}
 * registers {@code RollerHandlerInterceptor} before
 * {@code ApiScopeInterceptor}, and Spring applies an
 * {@code InterceptorRegistry}'s interceptors' {@code preHandle} in
 * registration order, so {@code actionWeblog} is set before
 * {@code ApiScopeInterceptor} ever runs. This class imports the real
 * {@code WebMvcConfig} bean (not a hand copy of its registration calls), so
 * a future reordering in that class is exercised here automatically rather
 * than needing this test to be told about it separately.
 */
class ApiScopeInterceptorDispatchTest {

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mockMvc;
    private static MockWeblogger weblogger;

    /** A stand-in for a real weblog-scoped API resource, carrying a {@code {handle}} path variable. */
    @RestController
    static class ProbeApi {
        @GetMapping("/v1/weblogs/{handle}/entries")
        public ResponseEntity<String> entries(@PathVariable("handle") String handle) {
            return ResponseEntity.ok("ok:" + handle);
        }
    }

    @Configuration
    @EnableWebMvc
    static class TestConfig {
        @Bean
        WebMvcConfig webMvcConfig() {
            return new WebMvcConfig();
        }

        @Bean
        ProbeApi probeApi() {
            return new ProbeApi();
        }

        @Bean
        ApiExceptionHandler apiExceptionHandler() {
            return new ApiExceptionHandler();
        }
    }

    @BeforeAll
    static void setUpContext() {
        weblogger = MockWeblogger.install();
        Weblog weblogA = new Weblog();
        weblogA.setId("weblog-a");
        weblogA.setHandle("A");
        Weblog weblogB = new Weblog();
        weblogB.setId("weblog-b");
        weblogB.setHandle("B");
        try {
            when(weblogger.weblogManager().getWeblogByHandle("A")).thenReturn(weblogA);
            when(weblogger.weblogManager().getWeblogByHandle("B")).thenReturn(weblogB);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class);
        context.refresh();
        mockMvc = webAppContextSetup(context).build();
    }

    @AfterAll
    static void tearDownContext() {
        if (context != null) {
            context.close();
        }
        MockWeblogger.uninstall();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWithScope(String scopeWeblog, ApiToken.Role role) {
        var auth = new UsernamePasswordAuthenticationToken("agent", null, List.of());
        auth.setDetails(new ApiPrincipal("agent", scopeWeblog, role));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * The Critical-1 regression case: a token scoped to weblog A must not
     * reach weblog B's resources through the real, container-mapped
     * "/api/v1/..." path. Fails under the "/api/**" registration bug
     * because ApiScopeInterceptor never runs at all for a real dispatch --
     * the request reaches ProbeApi and returns 200.
     */
    @Test
    void aTokenScopedToOneWeblogCannotReachAnotherThroughARealDispatch() throws Exception {
        authenticateWithScope("A", ApiToken.Role.POST);

        mockMvc.perform(get("/api/v1/weblogs/B/entries").servletPath("/api").pathInfo("/v1/weblogs/B/entries"))
                .andExpect(status().isNotFound());
    }

    /**
     * Control: proves the pipeline dispatches successfully end to end when
     * the token's scope matches, so the 404 above is the interceptor
     * actually deciding something, not every request failing regardless.
     */
    @Test
    void aTokenScopedToTheRequestedWeblogReachesTheHandler() throws Exception {
        authenticateWithScope("B", ApiToken.Role.POST);

        mockMvc.perform(get("/api/v1/weblogs/B/entries").servletPath("/api").pathInfo("/v1/weblogs/B/entries"))
                .andExpect(status().isOk());
    }

    /**
     * Important-2: the ceiling must track the SAME weblog the permission
     * check resolved and will enforce against, not re-derive its own answer
     * from the path. {@code weblog=A} wins over the path's {@code {handle}=B}
     * (RollerHandlerInterceptor.resolveWeblogHandle prefers the request
     * parameter), so a token scoped to B must be refused here even though
     * the path segment literally says B -- the real, permission-checked
     * target is A, and B is not what this token may touch.
     */
    @Test
    void aTokenCannotEscapeItsScopeViaTheWeblogQueryParameter() throws Exception {
        authenticateWithScope("B", ApiToken.Role.POST);

        mockMvc.perform(get("/api/v1/weblogs/B/entries?weblog=A")
                        .servletPath("/api").pathInfo("/v1/weblogs/B/entries"))
                .andExpect(status().isNotFound());
    }

    /**
     * The mirror image of the case above: a token scoped to A, reached via
     * a path segment that says B but a weblog= query parameter that says A,
     * must be ALLOWED -- because the effective, permission-checked target is
     * A, which the token really is scoped for. A naive path-only ceiling
     * would wrongly compare "A" against the path's "B" and refuse this.
     */
    @Test
    void aTokenScopedToTheQueryParameterWeblogIsNotBlockedByADifferentPathHandle() throws Exception {
        authenticateWithScope("A", ApiToken.Role.POST);

        mockMvc.perform(get("/api/v1/weblogs/B/entries?weblog=A")
                        .servletPath("/api").pathInfo("/v1/weblogs/B/entries"))
                .andExpect(status().isOk())
                .andExpect(result -> assertEquals("ok:B", result.getResponse().getContentAsString()));
    }
}
