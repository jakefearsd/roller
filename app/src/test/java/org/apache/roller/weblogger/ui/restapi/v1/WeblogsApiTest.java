package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.List;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.apache.roller.weblogger.ui.restapi.auth.AdminScoped;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-level tests for {@link WeblogsApi}: routing, the ownership
 * -free (site-wide) reach, and the guards between raw client input and a
 * manager call. {@code actionWeblog} is injected as a request attribute
 * rather than resolved by a real interceptor, matching {@code
 * CategoriesApiTest}'s standalone style -- these do not touch the DB; they
 * run against a Mockito-mocked {@code Weblogger}.
 */
class WeblogsApiTest {

    private MockMvc mockMvc(WeblogsApi controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private Weblogger mockedWeblogger() {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogManager weblogManager = mock(WeblogManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        return weblogger;
    }

    private WeblogsApi controllerFor(Weblogger weblogger) {
        WeblogsApi controller = new WeblogsApi();
        controller.weblogger = weblogger;
        return controller;
    }

    private static Weblog aWeblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setId(handle + "-id");
        weblog.setHandle(handle);
        weblog.setName("Name " + handle);
        weblog.setActive(Boolean.TRUE);
        weblog.setEntryDisplayCount(15);
        return weblog;
    }

    @Test
    void listReturnsEveryWeblogRegardlessOfOwnership() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog mine = aWeblog("mine");
        Weblog theirs = aWeblog("theirs");
        when(weblogger.getWeblogManager().getWeblogs(null, null, null, null, 0, 50))
                .thenReturn(List.of(mine, theirs));

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(2, json.size());
        assertEquals("mine", json.get(0).get("handle").asString());
        assertEquals("theirs", json.get(1).get("handle").asString());
    }

    @Test
    void listRejectsANegativeOffset() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs").param("offset", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogManager(), never())
                .getWeblogs(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void listRejectsANonPositiveLimit() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogManager(), never())
                .getWeblogs(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void getReturnsAWeblogViewForAResolvedHandle() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/{handle}", "myblog").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("myblog", json.get("handle").asString());
        assertEquals("myblog-id", json.get("id").asString());
    }

    /**
     * No {@code actionWeblog} attribute is what a real interceptor leaves
     * behind for an unknown or blank handle -- {@code
     * BaseApiController.requireActionWeblog} must turn that into a clean
     * 404, never an NPE two lines later against a null weblog.
     */
    @Test
    void getIsNotFoundWhenNoActionWeblogWasResolved() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/{handle}", "nosuch"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    /**
     * WeblogPageCache has no CacheHandler and expires only lazily against
     * weblog.lastModified -- CacheManager.invalidate is the only thing that
     * bumps it (see WeblogsApi.update's javadoc), so a save with no
     * corresponding invalidate call would leave a cached home page serving
     * stale content indefinitely. CacheManager.invalidate is a static call,
     * so it is verified with a MockedStatic rather than a Mockito mock.
     */
    @Test
    void patchUpdatesTheGivenFieldsAndLeavesOthersAlone() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        weblog.setTagline("original tagline");

        String body;
        try (org.mockito.MockedStatic<CacheManager> cache = mockStatic(CacheManager.class)) {
            body = mockMvc(controllerFor(weblogger))
                    .perform(patch("/v1/weblogs/{handle}", "myblog")
                            .requestAttr("actionWeblog", weblog)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"New Name\",\"active\":false}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            cache.verify(() -> CacheManager.invalidate(weblog));
        }

        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
        assertEquals("New Name", weblog.getName());
        assertFalse(weblog.getActive());
        assertEquals("original tagline", weblog.getTagline(), "an omitted field must be left untouched");
        verify(weblogger).flush();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("New Name", json.get("name").asString());
        assertFalse(json.get("active").asBoolean());
    }

    @Test
    void patchRejectsABlankName() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/{handle}", "myblog")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
        verify(weblogger, never()).flush();
    }

    /**
     * The lower half of the same guard {@code WeblogConfigController
     * .myValidate} applies to the JSP form (the upper half -- above {@code
     * site.pages.maxEntries} -- needs a bootstrapped runtime config to
     * exercise and is covered by the JSP controller's own tests; this
     * guard's shape is proven here at the boundary that does not need one).
     */
    @Test
    void patchRejectsAnEntryDisplayCountBelowOne() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/{handle}", "myblog")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryDisplayCount\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    /**
     * Review round 1, Important 2: weblog.locale is varchar(20) -- the
     * shortest-typed column any patchable field in this API lands in.
     * "pt-BR-x-something-plausible" clears 20 characters without looking
     * remotely hostile.
     */
    @Test
    void patchRejectsALocaleLongerThanTheColumn() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        String tooLong = "a".repeat(21);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/{handle}", "myblog")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void patchRejectsATimeZoneLongerThanTheColumn() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        String tooLong = "a".repeat(51);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/{handle}", "myblog")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeZone\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void patchRejectsANameLongerThanTheColumn() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        String tooLong = "a".repeat(256);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/{handle}", "myblog")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void patchRejectsAnEmailAddressLongerThanTheColumn() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        String tooLong = "a".repeat(250) + "@example.test";

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/{handle}", "myblog")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailAddress\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void patchRejectsATaglineLongerThanTheColumn() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        String tooLong = "a".repeat(256);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/{handle}", "myblog")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagline\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void patchIsNotFoundWhenNoActionWeblogWasResolved() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/{handle}", "nosuch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"whatever\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void declaresAdminAsTheRequiredGlobalPermissionAndNoWeblogRequirement() {
        assertEquals(List.of(GlobalPermission.ADMIN), new WeblogsApi().requiredGlobalPermissionActions());
        assertTrue(new WeblogsApi().isUserRequired());
        assertFalse(new WeblogsApi().isWeblogRequired(),
                "isWeblogRequired must be false -- GET /v1/weblogs (list) carries no {handle}, so a "
                        + "true value would make RollerHandlerInterceptor 404 the list endpoint outright.");
    }

    /**
     * WeblogsApi is mapped at /v1/weblogs, not /v1/admin, so {@code
     * AdminScopedCoverageTest} does not force this -- see the class javadoc
     * for why it carries the annotation anyway: this reaches ANY weblog on
     * the site, the same reach {@code AdminApi} has over accounts, and a
     * token minted for routine single-weblog automation must not be able to
     * use it just because its user happens to hold GlobalPermission.ADMIN.
     */
    @Test
    void carriesAdminScoped() {
        assertTrue(WeblogsApi.class.isAnnotationPresent(AdminScoped.class),
                "WeblogsApi must carry @AdminScoped -- it reaches every weblog on the site, not just "
                        + "the caller's own, and ApiScopeInterceptor otherwise never applies the "
                        + "ADMIN-role ceiling to it.");
    }
}
