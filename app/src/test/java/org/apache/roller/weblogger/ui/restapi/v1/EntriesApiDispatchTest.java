package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.List;
import org.apache.roller.weblogger.boot.WebMvcConfig;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * The deferred verification Task 5's reviewer could not do from a diff:
 * that a real {@code /api/v1/weblogs/{handle}/...} request reaches
 * {@code RollerHandlerInterceptor} with {@code actionWeblog} resolved from
 * the path variable, AND that a {@code UISecurityEnforced} controller's
 * declared {@code WeblogPermission} is actually enforced -- not just that
 * {@code WeblogOwnership}/{@code ApiException} work as pure functions or
 * that a probe controller's static metadata looks right.
 *
 * <p>This dispatches through a real {@code DispatcherServlet} built from the
 * production {@link WebMvcConfig} bean (not a hand copy of its registration
 * calls) and the real {@link EntriesApi}, with {@code .servletPath("/api")}
 * reproducing exactly what {@code ServletRegistrationConfig}'s {@code
 * "/api/*"} prefix mapping does on a real container -- same recipe as
 * {@code ApiScopeInterceptorDispatchTest}, which is the class that first
 * proved this pattern necessary (a mocked {@code InterceptorRegistry} cannot
 * catch a servlet-prefix-stripping bug; only a real dispatch can).
 *
 * <p>Authenticated with a Basic-auth-shaped principal (a {@code
 * UserDetails}, no {@code ApiPrincipal} in its details) rather than a Bearer
 * token, so {@code ApiScopeInterceptor}'s scope ceiling is a no-op here and
 * what these tests exercise is squarely {@code RollerHandlerInterceptor}'s
 * own {@code UISecurityEnforced} enforcement -- the piece Task 5's review
 * left unconfirmed.
 */
class EntriesApiDispatchTest {

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mockMvc;
    private static MockWeblogger weblogger;

    @Configuration
    @EnableWebMvc
    static class TestConfig {
        @Bean
        WebMvcConfig webMvcConfig() {
            return new WebMvcConfig();
        }

        @Bean
        EntriesApi entriesApi() {
            return new EntriesApi();
        }

        @Bean
        org.apache.roller.weblogger.business.Weblogger weblogger() {
            return weblogger.weblogger();
        }

        @Bean
        ApiExceptionHandler apiExceptionHandler() {
            return new ApiExceptionHandler();
        }
    }

    @BeforeAll
    static void setUpContext() throws Exception {
        weblogger = MockWeblogger.install();

        Weblog blog = new Weblog();
        blog.setId("weblog-1");
        blog.setHandle("myblog");
        when(weblogger.weblogManager().getWeblogByHandle("myblog")).thenReturn(blog);

        User contributor = new User();
        contributor.setUserName("contributor");
        when(weblogger.userManager().getUserByUserName("contributor")).thenReturn(contributor);

        when(weblogger.weblogEntryManager().getWeblogEntries(any(WeblogEntrySearchCriteria.class)))
                .thenReturn(List.of());

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

    private static void authenticateAs(String userName) {
        UserDetails principal = org.springframework.security.core.userdetails.User
                .withUsername(userName).password("n/a").authorities(List.of()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    /**
     * The positive case: a real dispatch to the container-mapped
     * {@code /api/v1/weblogs/{handle}/entries} URL, for a user who holds
     * EDIT_DRAFT on "myblog" (EntriesApi's declared requirement), reaches
     * the real handler and gets a real 200 -- proof that {@code {handle}}
     * was resolved to the weblog {@code EntriesApi.list} actually queried
     * against (getWeblogEntries was stubbed against no particular weblog,
     * but WeblogManager.getWeblogByHandle("myblog") is what had to be
     * reached for actionWeblog to be non-null at all; requireActionWeblog
     * would 404 otherwise).
     */
    @Test
    void theWeblogIsResolvedFromThePathVariableAndAPermittedCallerReachesTheHandler() throws Exception {
        authenticateAs("contributor");
        when(weblogger.userManager().checkPermission(any(), any())).thenReturn(true);

        mockMvc.perform(get("/api/v1/weblogs/myblog/entries")
                        .servletPath("/api").pathInfo("/v1/weblogs/myblog/entries"))
                .andExpect(status().isOk());
    }

    /**
     * The finding this test exists to close: a caller who lacks the
     * WeblogPermission EntriesApi declares must be refused before the
     * handler runs, on a real {@code {handle}}-carrying REST route -- not
     * merely in a unit test of WeblogOwnership or ApiException in
     * isolation. RollerHandlerInterceptor answers a redirect to
     * access-denied.rol (its existing JSP-era behaviour, unchanged by this
     * task) rather than a 403 problem+json body; that mismatch for an API
     * caller is a pre-existing characteristic of the shared interceptor,
     * not something Task 8 introduces, and is called out in the task
     * report rather than silently patched here.
     */
    @Test
    void aCallerLackingTheRequiredWeblogPermissionIsRefused() throws Exception {
        authenticateAs("contributor");
        when(weblogger.userManager().checkPermission(any(), any())).thenReturn(false);

        mockMvc.perform(get("/api/v1/weblogs/myblog/entries")
                        .servletPath("/api").pathInfo("/v1/weblogs/myblog/entries"))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * Control: an unauthenticated request is refused even earlier (redirect
     * to login), proving the 200 above is the permission check actually
     * deciding something rather than every request passing regardless.
     */
    @Test
    void anUnauthenticatedCallerIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/weblogs/myblog/entries")
                        .servletPath("/api").pathInfo("/v1/weblogs/myblog/entries"))
                .andExpect(status().is3xxRedirection());
    }
}
