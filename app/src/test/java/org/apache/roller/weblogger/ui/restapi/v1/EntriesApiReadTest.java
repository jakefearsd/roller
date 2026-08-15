package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.List;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.apache.roller.weblogger.ui.restapi.dto.EntryDtos;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EntriesApiReadTest {

    /**
     * Titles are stored escaped, so the DTO carries them through unchanged.
     * Escaping again here would send "&amp;amp;" to every client.
     */
    @Test
    void theViewCarriesTheStoredTitleWithoutReEscaping() {
        WeblogEntry entry = new WeblogEntry();
        entry.setTitle("Cats &amp; Dogs");
        entry.setStatus(WeblogEntry.PubStatus.DRAFT);

        EntryDtos.EntryView view = EntryDtos.toView(entry, null);
        assertEquals("Cats &amp; Dogs", view.title());
    }

    /** TRASHED is reachable only through the explicit status filter. */
    @Test
    void trashedIsNotAWritableStatus() {
        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.parseWritableStatus("TRASHED"));
    }

    @Test
    void anUnknownStatusIsRejected() {
        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.parseWritableStatus("BANANA"));
    }

    @Test
    void theFourWritableStatusesAreAccepted() {
        assertEquals(WeblogEntry.PubStatus.DRAFT, EntryDtos.parseWritableStatus("draft"));
        assertEquals(WeblogEntry.PubStatus.PUBLISHED, EntryDtos.parseWritableStatus("PUBLISHED"));
        assertEquals(WeblogEntry.PubStatus.PENDING, EntryDtos.parseWritableStatus("Pending"));
        assertEquals(WeblogEntry.PubStatus.SCHEDULED, EntryDtos.parseWritableStatus("SCHEDULED"));
    }

    /**
     * Filtering by TRASHED is how the trash list is read, so the filter
     * parser accepts what the write parser refuses. Two parsers, not one with
     * a boolean, so a write check cannot be relaxed into a filter check by
     * flipping an argument.
     */
    @Test
    void theFilterParserAcceptsTrashedWhereTheWriteParserDoesNot() {
        assertEquals(WeblogEntry.PubStatus.TRASHED, EntryDtos.parseFilterStatus("TRASHED"));
        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.parseWritableStatus("TRASHED"));
    }

    @Test
    void theFilterParserRejectsAnUnknownStatus() {
        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.parseFilterStatus("BANANA"));
    }

    // -----------------------------------------------------------------
    // Controller-level tests. The DTO tests above prove EntryDtos alone;
    // these prove routing, status codes, permission-independent business
    // logic (trash exclusion, pagination), and JSON shape -- none of which
    // a DTO-only test can see. actionWeblog is injected as a request
    // attribute rather than resolved by a real interceptor, matching
    // TokensApiTest's standalone style: this class is deliberately
    // interceptor-agnostic. EntriesApiDispatchTest is where the real
    // RollerHandlerInterceptor wiring (weblog resolution + WeblogPermission
    // enforcement) is proven end to end.
    // -----------------------------------------------------------------

    private MockMvc mockMvc(EntriesApi controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private Weblogger mockedWeblogger() {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        return weblogger;
    }

    private EntriesApi controllerFor(Weblogger weblogger) {
        EntriesApi controller = new EntriesApi();
        controller.weblogger = weblogger;
        return controller;
    }

    private static Weblog aWeblog() {
        Weblog weblog = new Weblog();
        weblog.setId("weblog-1");
        weblog.setHandle("myblog");
        return weblog;
    }

    /**
     * WeblogEntrySearchCriteria.includeTrashed defaults to false -- the
     * safety property CLAUDE.md documents. A caller that supplies no status
     * at all must get that default, not something EntriesApi has to
     * remember to set.
     */
    @Test
    void listExcludesTrashByDefault() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any())).thenReturn(List.of());

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/entries").requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isOk());

        ArgumentCaptor<WeblogEntrySearchCriteria> captor =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(weblogger.getWeblogEntryManager()).getWeblogEntries(captor.capture());
        assertNull(captor.getValue().getStatus());
        assertFalse(captor.getValue().isIncludeTrashed());
    }

    /**
     * The only way to see the trash: ask for status=TRASHED by name.
     */
    @Test
    void listWithStatusTrashedIncludesTheTrash() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any())).thenReturn(List.of());

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/entries")
                        .param("status", "TRASHED")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isOk());

        ArgumentCaptor<WeblogEntrySearchCriteria> captor =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(weblogger.getWeblogEntryManager()).getWeblogEntries(captor.capture());
        assertEquals(WeblogEntry.PubStatus.TRASHED, captor.getValue().getStatus());
        assertTrue(captor.getValue().isIncludeTrashed());
    }

    /** A status the filter parser rejects answers 400, not a 500 from a bad enum lookup. */
    @Test
    void listWithAnUnknownStatusIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/entries")
                        .param("status", "BANANA")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    /**
     * One extra row over the requested limit is how hasMore is decided
     * without a second count query -- the extra row itself must never leak
     * into the returned page.
     */
    @Test
    void listReportsHasMoreWithoutLeakingTheExtraRow() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog();
        WeblogEntry first = new WeblogEntry();
        first.setId("e1");
        first.setAnchor("first");
        first.setWebsite(weblog);
        WeblogEntry second = new WeblogEntry();
        second.setId("e2");
        second.setAnchor("second");
        second.setWebsite(weblog);
        when(weblogger.getUrlStrategy()).thenReturn(mock(org.apache.roller.weblogger.business.URLStrategy.class));
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any()))
                .thenReturn(List.of(first, second));

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/entries")
                        .param("limit", "1")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(1, json.get("items").size());
        assertEquals("first", json.get("items").get(0).get("anchor").asString());
        assertTrue(json.get("hasMore").asBoolean());
        assertEquals(1, json.get("limit").asInt());
    }

    @Test
    void getReturnsTheEntryWhenItBelongsToTheActionWeblog() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog();
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setAnchor("hello-world");
        entry.setTitle("Hello World");
        entry.setWebsite(weblog);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);
        when(weblogger.getUrlStrategy()).thenReturn(mock(org.apache.roller.weblogger.business.URLStrategy.class));

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("Hello World", json.get("title").asString());
        assertEquals("hello-world", json.get("anchor").asString());
    }

    /**
     * The IDOR case: an id that belongs to a different weblog than the one
     * this request is scoped to is indistinguishable from an unknown id --
     * both answer 404 via WeblogOwnership.entry, never the entry's data.
     */
    @Test
    void getIsNotFoundWhenTheEntryBelongsToAnotherWeblog() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog thisWeblog = aWeblog();
        Weblog anotherWeblog = new Weblog();
        anotherWeblog.setId("weblog-2");
        anotherWeblog.setHandle("someoneelse");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(anotherWeblog);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", thisWeblog))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void getIsNotFoundWhenNoSuchEntryExistsAtAll() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getWeblogEntryManager().getWeblogEntry(anyString())).thenReturn(null);

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/entries/{id}", "no-such-id")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isNotFound());
    }

    /**
     * EntriesApi declares UISecurityEnforced.requiredWeblogPermissionActions
     * as EDIT_DRAFT: reads are open to contributors, not just editors.
     */
    @Test
    void declaresEditDraftAsTheRequiredWeblogPermission() {
        assertEquals(List.of(org.apache.roller.weblogger.pojos.WeblogPermission.EDIT_DRAFT),
                new EntriesApi().requiredWeblogPermissionActions());
        assertTrue(new EntriesApi().isUserRequired());
        assertTrue(new EntriesApi().isWeblogRequired());
    }
}
