/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package org.apache.roller.weblogger.ui.controllers.editor;

import java.util.ArrayList;
import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EntriesController}.
 *
 * <p>The controller asks the manager for one row more than a page ({@code
 * COUNT + 1}) purely to learn whether a next page exists, then has to trim
 * that lookahead row back off before the page ever sees it - get that
 * arithmetic wrong and the list either drops a real entry or shows a
 * "next page" link that leads nowhere. The status filter's "ALL" case is a
 * sentinel that must map to a null {@code PubStatus} rather than an
 * enum value, and the category list always leads with a synthetic "Any"
 * option that has to survive even a failed category lookup, since without it
 * the search form would have no way to clear a category filter.
 */
class EntriesControllerTest extends EditorControllerTestSupport {

    private static final int COUNT = 30;

    private EntriesController controller;
    private Model model;
    private EntriesBean bean;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new EntriesController());
        model = newModel();
        bean = new EntriesBean();

        // Every test exercises getCategories() and the entries query
        // unconditionally, so both need a non-null default: an unstubbed mock
        // returns null, and this controller does not guard against that -
        // only against the checked WebloggerException.
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any())).thenReturn(new ArrayList<>());
        when(weblogger.getWeblogEntryManager().getWeblogCategories(weblog)).thenReturn(new ArrayList<>());
    }

    @Test
    void aNormalQueryBuildsAPagerWithTheReturnedEntriesAndNoMorePage() throws Exception {
        List<WeblogEntry> entries = List.of(entryNamed("entry-1"), entryNamed("entry-2"));
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any())).thenReturn(entries);

        String view = controller.execute(request, model, bean);

        assertEquals(".Entries", view);
        EntriesPagerLike pager = pager(model);
        assertEquals(entries, pager.items);
        assertFalse(pager.moreItems, "Two results out of a 30-row page must not claim a next page");
    }

    @Test
    void aFullPageWithOneExtraRowIsTrimmedAndFlaggedAsHavingMore() throws Exception {
        // COUNT+1 rows is exactly what the controller asks the manager for; the
        // 31st row is the lookahead and must never reach the page.
        List<WeblogEntry> overflowing = new ArrayList<>();
        for (int i = 0; i < COUNT + 1; i++) {
            overflowing.add(entryNamed("entry-" + i));
        }
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any())).thenReturn(overflowing);

        controller.execute(request, model, bean);

        EntriesPagerLike pager = pager(model);
        assertEquals(COUNT, pager.items.size(), "The lookahead row must be trimmed off before display");
        assertEquals(overflowing.subList(0, COUNT), pager.items);
        assertTrue(pager.moreItems, "A trimmed lookahead row means a next page exists");
    }

    @Test
    void statusAllMapsToANullPubStatusFilter() throws Exception {
        bean.setStatus("ALL");

        controller.execute(request, model, bean);

        ArgumentCaptor<WeblogEntrySearchCriteria> captor =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        org.mockito.Mockito.verify(weblogger.getWeblogEntryManager()).getWeblogEntries(captor.capture());
        assertNull(captor.getValue().getStatus(), "\"ALL\" must not be passed through as a literal enum value");
    }

    @Test
    void aSpecificStatusIsPassedThroughAsItsPubStatusEnumValue() throws Exception {
        bean.setStatus("DRAFT");

        controller.execute(request, model, bean);

        ArgumentCaptor<WeblogEntrySearchCriteria> captor =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        org.mockito.Mockito.verify(weblogger.getWeblogEntryManager()).getWeblogEntries(captor.capture());
        assertEquals(WeblogEntry.PubStatus.DRAFT, captor.getValue().getStatus());
    }

    @Test
    void categoriesAlwaysStartWithASyntheticAnyOptionOnSuccess() throws Exception {
        WeblogCategory travel = new WeblogCategory();
        travel.setName("Travel");
        when(weblogger.getWeblogEntryManager().getWeblogCategories(weblog)).thenReturn(List.of(travel));

        controller.execute(request, model, bean);

        @SuppressWarnings("unchecked")
        List<WeblogCategory> categories = (List<WeblogCategory>) model.getAttribute("categories");
        assertEquals(2, categories.size());
        assertEquals("Any", categories.get(0).getName());
        assertEquals("Travel", categories.get(1).getName());
    }

    @Test
    void categoriesStillStartsWithAnyWhenTheCategoryLookupFails() throws Exception {
        when(weblogger.getWeblogEntryManager().getWeblogCategories(weblog))
                .thenThrow(new WebloggerException("database down"));

        controller.execute(request, model, bean);

        @SuppressWarnings("unchecked")
        List<WeblogCategory> categories = (List<WeblogCategory>) model.getAttribute("categories");
        assertEquals(1, categories.size(),
                "A failed category lookup must still leave the synthetic \"Any\" entry so the form has "
                        + "something to bind to");
        assertEquals("Any", categories.get(0).getName());
    }

    @Test
    void sortByOptionsOffersExactlyTheTwoSupportedSortFields() throws Exception {
        controller.execute(request, model, bean);

        assertEquals(2, ((List<?>) model.getAttribute("sortByOptions")).size());
    }

    @Test
    void statusOptionsOffersExactlyTheFiveSupportedStatuses() throws Exception {
        controller.execute(request, model, bean);

        assertEquals(5, ((List<?>) model.getAttribute("statusOptions")).size());
    }

    @Test
    void aFailedEntryLookupIsReportedAndStillProducesAPager() throws Exception {
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any()))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.execute(request, model, bean);

        assertEquals(".Entries", view, "The list page must still render after a failed query");
        assertTrue(errors(model).contains("Error looking up entries"),
                "Expected the lookup failure to be reported, got: " + errors(model));

        // The try block throws before `entries` or `hasMore` are ever assigned,
        // so the pager gets built with the pre-try defaults: a null list and
        // hasMore=false. EntriesPager's own getItems() returns exactly what it
        // was constructed with, without defensively substituting an empty list.
        EntriesPagerLike pager = pager(model);
        assertNull(pager.items, "A failed lookup leaves the pager's entry list null rather than empty");
        assertFalse(pager.moreItems);
    }

    // --- helpers ---

    private WeblogEntry entryNamed(String id) {
        WeblogEntry entry = new WeblogEntry();
        entry.setId(id);
        return entry;
    }

    /**
     * Reads the {@code pager} model attribute through the fields that matter to
     * these tests, without spreading {@code EntriesPager} casts across every
     * test method.
     */
    private EntriesPagerLike pager(Model model) {
        org.apache.roller.weblogger.ui.controllers.pagers.EntriesPager pager =
                (org.apache.roller.weblogger.ui.controllers.pagers.EntriesPager) model.getAttribute("pager");
        EntriesPagerLike result = new EntriesPagerLike();
        result.items = pager.getItems();
        result.moreItems = pager.isMoreItems();
        return result;
    }

    private static final class EntriesPagerLike {
        List<WeblogEntry> items;
        boolean moreItems;
    }
}
