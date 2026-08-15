package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.List;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.search.IndexManager;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.apache.roller.weblogger.ui.restapi.auth.AdminScoped;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-level tests for {@link AdminActionsApi}: routing, the 202
 * status every action shares, and the response body shape. {@code
 * actionWeblog} is injected as a request attribute rather than resolved by
 * a real interceptor, matching {@code CategoriesApiTest}'s standalone style
 * -- these do not touch the DB; they run against a Mockito-mocked
 * {@code Weblogger}.
 */
class AdminActionsApiTest {

    private MockMvc mockMvc(AdminActionsApi controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private Weblogger mockedWeblogger() {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogManager weblogManager = mock(WeblogManager.class);
        IndexManager indexManager = mock(IndexManager.class);
        MediaFileManager mediaFileManager = mock(MediaFileManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogger.getIndexManager()).thenReturn(indexManager);
        when(weblogger.getMediaFileManager()).thenReturn(mediaFileManager);
        return weblogger;
    }

    private AdminActionsApi controllerFor(Weblogger weblogger) {
        AdminActionsApi controller = new AdminActionsApi();
        controller.weblogger = weblogger;
        return controller;
    }

    private static Weblog aWeblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setId(handle + "-id");
        weblog.setHandle(handle);
        return weblog;
    }

    @Test
    void flushCacheQueuesTheFlushAndAnswers202() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        String body = mockMvc(controllerFor(weblogger))
                .perform(post("/v1/admin/weblogs/myblog/actions/flush-cache")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("flush-cache", json.get("action").asString());
        assertEquals("myblog", json.get("weblog").asString());
    }

    @Test
    void rebuildIndexQueuesTheRebuildAndAnswers202() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        String body = mockMvc(controllerFor(weblogger))
                .perform(post("/v1/admin/weblogs/myblog/actions/rebuild-index")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        verify(weblogger.getIndexManager()).rebuildWeblogIndex(weblog);
        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("rebuild-index", json.get("action").asString());
        assertEquals("myblog", json.get("weblog").asString());
    }

    @Test
    void regenerateRenditionsQueuesTheRegenerationAndAnswers202() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        when(weblogger.getMediaFileManager().regenerateRenditions(weblog)).thenReturn(3);

        String body = mockMvc(controllerFor(weblogger))
                .perform(post("/v1/admin/weblogs/myblog/actions/regenerate-renditions")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        verify(weblogger.getMediaFileManager()).regenerateRenditions(weblog);
        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("regenerate-renditions", json.get("action").asString());
        assertEquals("myblog", json.get("weblog").asString());
    }

    /**
     * No {@code actionWeblog} attribute is what a real interceptor leaves
     * behind for an unknown or blank handle -- {@code
     * BaseApiController.requireActionWeblog} must turn that into a clean
     * 404, never an NPE two lines later against a null weblog.
     */
    @Test
    void noResolvedWeblogIsNotFoundNotAnException() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/admin/weblogs/nosuch/actions/flush-cache"))
                .andExpect(status().isNotFound());

        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void declaresAdminAsTheRequiredGlobalPermission() {
        assertEquals(List.of(GlobalPermission.ADMIN),
                new AdminActionsApi().requiredGlobalPermissionActions());
        assertTrue(new AdminActionsApi().isUserRequired());
        assertTrue(new AdminActionsApi().isWeblogRequired());
    }

    /**
     * The one thing this task exists to guard: without {@code @AdminScoped}
     * on the class, {@code ApiScopeInterceptor} never applies the ADMIN-role
     * ceiling to these handlers and a lower-scoped token could run them.
     * {@code AdminScopedCoverageTest} enforces this generically across every
     * {@code /v1/admin}-mapped controller; this test pins the specific fact
     * for this one.
     */
    @Test
    void carriesAdminScoped() {
        assertTrue(AdminActionsApi.class.isAnnotationPresent(AdminScoped.class),
                "AdminActionsApi must carry @AdminScoped or ApiScopeInterceptor never applies the "
                        + "ADMIN-role ceiling to it, leaving a non-admin-scoped token able to run these "
                        + "actions.");
    }
}
