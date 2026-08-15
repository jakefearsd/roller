package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.List;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-level tests for {@link EntriesWriteApi}: routing, status codes,
 * the ownership/authentication checks, and JSON shape -- none of which
 * {@code EntriesApiWriteTest} (the {@code EntryDtos.applyWrite} unit test)
 * can see. actionWeblog/authenticatedUser are injected as request
 * attributes rather than resolved by a real interceptor, matching
 * {@code EntriesApiReadTest}/{@code CategoriesApiTest}'s standalone style;
 * these run against a Mockito-mocked Weblogger, not the DB fixtures.
 */
class EntriesWriteApiControllerTest {

    private MockMvc mockMvc(EntriesWriteApi controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private Weblogger mockedWeblogger() {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        when(weblogger.getUrlStrategy()).thenReturn(mock(URLStrategy.class));
        return weblogger;
    }

    private EntriesWriteApi controllerFor(Weblogger weblogger) {
        EntriesWriteApi controller = new EntriesWriteApi();
        controller.weblogger = weblogger;
        return controller;
    }

    private static Weblog aWeblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setId(handle + "-id");
        weblog.setHandle(handle);
        weblog.setTimeZone("UTC");
        return weblog;
    }

    private static User aUser(String userName) {
        User user = new User();
        user.setUserName(userName);
        return user;
    }

    /**
     * The happy path end to end: title escaped exactly once, creator taken
     * from the authenticated user (never from the request body -- there is
     * no such field on EntryWrite), category resolved by name, 201 with a
     * Location header naming the new entry, and the saved entry echoed
     * back.
     */
    @Test
    void postCreatesAnEntryEscapesTheTitleAndReturnsLocation() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        WeblogEntryManager entryManager = weblogger.getWeblogEntryManager();
        Weblog weblog = aWeblog("myblog");
        WeblogCategory travel = new WeblogCategory(weblog, "Travel", null, null);
        when(entryManager.getWeblogCategoryByName(weblog, "Travel")).thenReturn(travel);
        doAnswer(invocation -> {
            WeblogEntry saved = invocation.getArgument(0);
            saved.setId("entry-99");
            saved.setAnchor("cats-dogs");
            return null;
        }).when(entryManager).saveWeblogEntry(any());

        var result = mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Cats & Dogs\",\"category\":\"Travel\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        assertNotNull(location);
        assertTrue(location.endsWith("/entries/entry-99"), "Location was: " + location);
        String body = result.getResponse().getContentAsString();

        ArgumentCaptor<WeblogEntry> captor = ArgumentCaptor.forClass(WeblogEntry.class);
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(captor.capture());
        assertEquals("Cats &amp; Dogs", captor.getValue().getTitle());
        assertEquals("maiia", captor.getValue().getCreatorUserName());
        assertEquals(travel, captor.getValue().getCategory());
        assertSame(weblog, captor.getValue().getWebsite());

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("Cats &amp; Dogs", json.get("title").asString());
    }

    /** No manager fallback needed: an absent category leaves entry.category null, and
     * JPAWeblogEntryManagerImpl.saveWeblogEntry (not exercised by this mocked test) is
     * what defaults it to the weblog's first category in production. */
    @Test
    void postWithNoCategoryLeavesEntryCategoryUnset() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"No category here\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<WeblogEntry> captor = ArgumentCaptor.forClass(WeblogEntry.class);
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(captor.capture());
        assertNull(captor.getValue().getCategory());
    }

    /** An unknown category name is a 400, never a 500 from a null category reaching save. */
    @Test
    void postWithAnUnknownCategoryIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        when(weblogger.getWeblogEntryManager().getWeblogCategoryByName(weblog, "Nope"))
                .thenReturn(null);

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"category\":\"Nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    /** A mistyped pubTime is a 400 end to end through the controller, not an opaque 500. */
    @Test
    void postWithAMistypedPubTimeIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"pubTime\":\"yesterday-ish\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    /** No authenticatedUser attribute -- the interceptor never having run -- is 401, not an NPE. */
    @Test
    void postWithoutAnAuthenticatedUserIsUnauthorized() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    /** Malformed JSON is 400, matching CategoriesApi/EntriesApi's existing coverage of the same gap. */
    @Test
    void postWithMalformedJsonIsBadRequestNotAnOpaque500() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    /**
     * PATCH found through WeblogOwnership.entry: an absent field (title,
     * here) is left untouched end to end, not just at the DTO layer.
     */
    @Test
    void patchUpdatesOnlyTheFieldsThePatchBodyCarries() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setTitle("kept");
        entry.setText("old body");
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        String body = mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"new body\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals("kept", entry.getTitle());
        assertEquals("new body", entry.getText());
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(entry);
        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("kept", json.get("title").asString());
    }

    /**
     * The IDOR case: an entry belonging to another weblog is
     * indistinguishable from an unknown id -- 404 either way, never the
     * foreign entry's data or a rewrite of it.
     */
    @Test
    void patchIsNotFoundWhenTheEntryBelongsToAnotherWeblog() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog thisWeblog = aWeblog("myblog");
        Weblog anotherWeblog = aWeblog("someoneelse");
        WeblogEntry foreign = new WeblogEntry();
        foreign.setId("entry-1");
        foreign.setWebsite(anotherWeblog);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(foreign);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", thisWeblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"new body\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    /** Tags go through WeblogEntry's own tag-setting path, space-joined. */
    @Test
    void patchWithTagsAppliesThemThroughSetTagsAsString() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[\"travel\",\"food\"]}"))
                .andExpect(status().isOk());

        assertEquals("food travel", entry.getTagsAsString());
    }

    @Test
    void declaresPostAsTheRequiredWeblogPermission() {
        assertEquals(List.of(WeblogPermission.POST),
                new EntriesWriteApi().requiredWeblogPermissionActions());
        assertTrue(new EntriesWriteApi().isUserRequired());
        assertTrue(new EntriesWriteApi().isWeblogRequired());
    }
}
