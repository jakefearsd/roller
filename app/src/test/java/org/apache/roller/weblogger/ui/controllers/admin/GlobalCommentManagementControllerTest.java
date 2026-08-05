/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.CommentSearchCriteria;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntryComment;
import org.apache.roller.weblogger.pojos.WeblogEntryComment.ApprovalStatus;
import org.apache.roller.weblogger.ui.controllers.pagers.CommentsPager;
import org.apache.roller.weblogger.ui.controllers.util.KeyValueObject;
import org.apache.roller.weblogger.util.cache.CacheHandler;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link GlobalCommentManagementController}, the site-wide comment
 * moderation screen.
 *
 * <p>Two things here are destructive -- bulk delete and the spam checkbox
 * sweep -- so the tests pin down exactly which criteria reach the manager and
 * exactly which comments are written or removed.
 */
class GlobalCommentManagementControllerTest {

    /** The controller's page size; it asks for one extra row to detect "more". */
    private static final int PAGE_SIZE = 30;

    /**
     * Registered once with {@code CacheManager}, which has no way to remove a
     * handler, and reset before each test. It is how the tests observe the
     * cache invalidation that has to follow a comment change.
     */
    private static final CacheHandler CACHE_HANDLER = org.mockito.Mockito.mock(CacheHandler.class);

    static {
        CacheManager.registerHandler(CACHE_HANDLER);
    }

    private MockWeblogger weblogger;
    private GlobalCommentManagementController controller;
    private ExtendedModelMap model;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.install();
        controller = ControllerTestFixture.withMessages(new GlobalCommentManagementController());
        model = new ExtendedModelMap();
        org.mockito.Mockito.reset(CACHE_HANDLER);
    }

    @AfterEach
    void tearDown() {
        MockWeblogger.uninstall();
    }

    // --- listing ---

    @Test
    void commentModerationIsAdminOnlyAndNeedsNoWeblog() {
        // This screen reaches across every blog on the site, so the interceptor
        // has to require the global admin permission before the handler runs.
        assertEquals(List.of(GlobalPermission.ADMIN), controller.requiredGlobalPermissionActions());
        assertFalse(controller.isWeblogRequired());
        assertEquals("commentManagement.title", controller.getPageTitle());
        assertEquals("admin", controller.getDesiredMenu());
        assertEquals("globalCommentManagement", controller.getActionName());
        assertNotNull(controller.getBean(), "Spring seeds the filter form from this");
    }

    @Test
    void theFirstPageAsksForOneRowMoreThanItShowsAndOffersEveryStatusFilter() throws Exception {
        when(weblogger.weblogEntryManager().getComments(any())).thenReturn(List.of());

        String view = controller.execute(request(), model);

        assertEquals(".GlobalCommentManagement", view);
        assertEquals("globalCommentManagement", model.getAttribute("actionName"));
        assertEquals("admin", model.getAttribute("desiredMenu"));
        CommentSearchCriteria criteria = capturedCriteria();
        assertEquals(0, criteria.getOffset());
        assertEquals(PAGE_SIZE + 1, criteria.getMaxResults(), "one spare row is how the pager knows there is more");
        assertTrue(criteria.isReverseChrono(), "newest comments first");
        assertNull(criteria.getStatus(), "the unfiltered view must not hide any status");
        assertEquals(0, model.getAttribute("bulkDeleteCount"));

        List<?> options = (List<?>) model.getAttribute("commentStatusOptions");
        assertEquals(4, options.size());
        assertEquals("ALL", ((KeyValueObject) options.get(0)).getKey());
    }

    @Test
    void afullPageIsTrimmedBackToThePageSizeAndFlaggedAsHavingMore() throws Exception {
        when(weblogger.weblogEntryManager().getComments(any())).thenReturn(comments(PAGE_SIZE + 1));
        when(weblogger.urlStrategy().getActionURL(any(), any(), isNull(), any(), eq(false)))
                .thenReturn("http://example.com/roller-ui/admin/globalCommentManagement.rol");

        controller.execute(request(), model);

        CommentsPager pager = (CommentsPager) model.getAttribute("pager");
        assertEquals(PAGE_SIZE, pager.getItems().size(), "the spare row is a lookahead, not something to display");
        assertTrue(pager.isMoreItems());
        assertEquals("c0", ((WeblogEntryComment) model.getAttribute("firstComment")).getId());
        assertEquals("c29", ((WeblogEntryComment) model.getAttribute("lastComment")).getId());
        assertTrue(pager.getNextLink().startsWith("http://example.com/roller-ui/admin/globalCommentManagement.rol"),
                "the next-page link is built on the action URL: " + pager.getNextLink());
        assertEquals("c0", pager.getItems().get(0).getId(),
                "the page must start at the first comment the query returned");
    }

    @Test
    void aShortPageIsShownWholeWithNoNextLink() throws Exception {
        when(weblogger.weblogEntryManager().getComments(any())).thenReturn(comments(3));

        controller.execute(request(), model);

        CommentsPager pager = (CommentsPager) model.getAttribute("pager");
        assertEquals(3, pager.getItems().size());
        assertFalse(pager.isMoreItems());
        assertNull(pager.getNextLink());
    }

    @Test
    void anEmptyResultLeavesNoFirstOrLastComment() throws Exception {
        when(weblogger.weblogEntryManager().getComments(any())).thenReturn(List.of());

        controller.execute(request(), model);

        assertNull(model.getAttribute("firstComment"));
        assertNull(model.getAttribute("lastComment"));
        assertTrue(((CommentsPager) model.getAttribute("pager")).getItems().isEmpty());
    }

    @Test
    void aFailedLookupIsReportedInsteadOfShowingAnEmptyListAsIfItWereTheTruth() throws Exception {
        when(weblogger.weblogEntryManager().getComments(any())).thenThrow(new WebloggerException("database down"));

        String view = controller.execute(request(), model);

        assertEquals(".GlobalCommentManagement", view);
        assertEquals(List.of("commentManagement.lookupError"), ControllerTestFixture.errors(model));
    }

    // --- querying ---

    @Test
    void everyFilterTheAdminTypedIsPassedToTheQuery() throws Exception {
        GlobalCommentManagementBean bean = new GlobalCommentManagementBean();
        bean.setSearchString("viagra");
        bean.setStartDateString("03/01/24");
        bean.setEndDateString("03/31/24");
        bean.setApprovedString("ONLY_DISAPPROVED");
        bean.setPage(2);
        when(weblogger.weblogEntryManager().getComments(any())).thenReturn(List.of());

        String view = controller.query(request(), model, bean);

        assertEquals(".GlobalCommentManagement", view);
        CommentSearchCriteria criteria = capturedCriteria();
        assertEquals("viagra", criteria.getSearchText());
        assertEquals(ApprovalStatus.DISAPPROVED, criteria.getStatus());
        assertEquals(2 * PAGE_SIZE, criteria.getOffset(), "page 2 must skip the first two pages of results");
        assertEquals(dayOfMonth(criteria.getStartDate()), 1);
        assertEquals(dayOfMonth(criteria.getEndDate()), 31);
    }

    @Test
    void bulkDeleteIsOfferedOnlyWhenTheMatchesRunPastTheFirstPage() throws Exception {
        // The button deletes everything matching, not just the visible page, so
        // it is only meaningful when there is more than one page of matches.
        when(weblogger.weblogEntryManager().getComments(any())).thenReturn(comments(PAGE_SIZE + 5));
        GlobalCommentManagementBean bean = new GlobalCommentManagementBean();
        bean.setSearchString("viagra");
        bean.setApprovedString("ONLY_DISAPPROVED");
        bean.setStartDateString("03/01/24");
        bean.setEndDateString("03/31/24");

        controller.query(request(), model, bean);

        assertEquals(PAGE_SIZE + 5, model.getAttribute("bulkDeleteCount"));
        assertEquals("commentManagement.title", model.getAttribute("pageTitle"));

        // The count has to be of the same comments the delete button will
        // remove -- every part of the filter, not just some of it -- so it runs
        // the filter unpaged rather than reusing the page query.
        ArgumentCaptor<CommentSearchCriteria> captor = ArgumentCaptor.forClass(CommentSearchCriteria.class);
        verify(weblogger.weblogEntryManager(), org.mockito.Mockito.times(2)).getComments(captor.capture());
        CommentSearchCriteria countCriteria = captor.getAllValues().get(1);
        assertEquals("viagra", countCriteria.getSearchText());
        assertEquals(ApprovalStatus.DISAPPROVED, countCriteria.getStatus());
        assertTrue(countCriteria.isReverseChrono());
        assertEquals(1, dayOfMonth(countCriteria.getStartDate()));
        assertEquals(31, dayOfMonth(countCriteria.getEndDate()));
    }

    @Test
    void bulkDeleteIsNotOfferedForASinglePageOfMatches() throws Exception {
        when(weblogger.weblogEntryManager().getComments(any())).thenReturn(comments(PAGE_SIZE));

        controller.query(request(), model, new GlobalCommentManagementBean());

        assertEquals(0, model.getAttribute("bulkDeleteCount"));
        CommentsPager pager = (CommentsPager) model.getAttribute("pager");
        assertEquals(PAGE_SIZE, pager.getItems().size(),
                "an exactly-full page is shown whole -- nothing is trimmed off the end");
        assertFalse(pager.isMoreItems(), "and there is no next page to offer");
    }

    @Test
    void aQueryLeavesTheTypedFilterOnTheForm() throws Exception {
        // The filter has to survive so the admin can page through the results.
        when(weblogger.weblogEntryManager().getComments(any()))
                .thenReturn(List.of(comment("c1", ApprovalStatus.DISAPPROVED),
                        comment("c2", ApprovalStatus.APPROVED)));
        GlobalCommentManagementBean bean = new GlobalCommentManagementBean();
        bean.setSearchString("viagra");

        controller.query(request(), model, bean);

        assertSame(bean, model.getAttribute("bean"));
        assertEquals("viagra", bean.getSearchString());
        assertEquals(2, ((CommentsPager) model.getAttribute("pager")).getItems().size());
        assertEquals(4, ((List<?>) model.getAttribute("commentStatusOptions")).size());
    }

    @Test
    void thePagerLinksCarryTheFilterSoPagingDoesNotWidenTheSearch() throws Exception {
        // The next/previous links are built from this URL. Dropping the filter
        // would quietly turn "page 2 of the spam" into "page 2 of everything".
        when(weblogger.weblogEntryManager().getComments(any())).thenReturn(List.of());
        GlobalCommentManagementBean bean = new GlobalCommentManagementBean();
        bean.setSearchString("viagra");
        bean.setStartDateString("03/01/24");
        bean.setEndDateString("03/31/24");
        bean.setApprovedString("ONLY_DISAPPROVED");

        controller.query(request(), model, bean);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        verify(weblogger.urlStrategy()).getActionURL(
                eq("globalCommentManagement"), eq("/roller-ui/admin"), isNull(), params.capture(), eq(false));
        assertEquals(Map.of(
                        "bean.searchString", "viagra",
                        "bean.startDateString", "03/01/24",
                        "bean.endDateString", "03/31/24",
                        "bean.approvedString", "ONLY_DISAPPROVED"),
                params.getValue());
    }

    @Test
    void anUnfilteredPagerLinkCarriesNoFilterParameters() throws Exception {
        when(weblogger.weblogEntryManager().getComments(any())).thenReturn(List.of());

        controller.query(request(), model, new GlobalCommentManagementBean());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        verify(weblogger.urlStrategy()).getActionURL(
                any(), any(), isNull(), params.capture(), eq(false));
        assertEquals(Map.of("bean.approvedString", "ALL"), params.getValue(),
                "only the status filter has a value at rest");
    }

    @Test
    void aFailedCountIsReportedAndLeavesBulkDeleteOff() throws Exception {
        // The page query succeeds and only the count query fails, so exactly one
        // complaint should reach the admin -- and no bulk delete button, which
        // would otherwise be sized from a number nobody could compute.
        when(weblogger.weblogEntryManager().getComments(any()))
                .thenReturn(List.of())
                .thenThrow(new WebloggerException("database down"));

        controller.query(request(), model, new GlobalCommentManagementBean());

        assertEquals(0, model.getAttribute("bulkDeleteCount"));
        assertEquals(List.of("commentManagement.lookupError"), ControllerTestFixture.errors(model));
    }

    // --- bulk delete ---

    @Test
    void bulkDeleteRemovesExactlyWhatTheFilterDescribedAndSaysHowMany() throws Exception {
        GlobalCommentManagementBean bean = new GlobalCommentManagementBean();
        bean.setSearchString("viagra");
        bean.setApprovedString("ONLY_DISAPPROVED");
        when(weblogger.weblogEntryManager().removeMatchingComments(
                any(), any(), anyString(), any(), any(), any())).thenReturn(17);

        String view = controller.delete(request(), model, bean);

        assertEquals(".GlobalCommentManagement", view);
        // Weblog and entry are null: this screen deletes across the whole site.
        verify(weblogger.weblogEntryManager()).removeMatchingComments(
                null, null, "viagra", null, null, ApprovalStatus.DISAPPROVED);
        assertEquals(List.of("commentManagement.deleteSuccess[17]"), ControllerTestFixture.messages(model));
    }

    @Test
    void afterABulkDeleteTheFilterIsClearedSoTheAdminSeesWhatIsLeft() throws Exception {
        GlobalCommentManagementBean bean = new GlobalCommentManagementBean();
        bean.setSearchString("viagra");
        bean.setApprovedString("ONLY_DISAPPROVED");
        when(weblogger.weblogEntryManager().getComments(any()))
                .thenReturn(List.of(comment("c1", ApprovalStatus.APPROVED)));

        controller.delete(request(), model, bean);

        GlobalCommentManagementBean shown = (GlobalCommentManagementBean) model.getAttribute("bean");
        assertNull(shown.getSearchString(), "leaving the old filter in place would suggest nothing was deleted");
        assertEquals("ALL", shown.getApprovedString());
        assertEquals(0, model.getAttribute("bulkDeleteCount"));
        assertEquals("admin", model.getAttribute("desiredMenu"));
        assertEquals(4, ((List<?>) model.getAttribute("commentStatusOptions")).size());
        assertNotNull(model.getAttribute("pager"), "the page is redrawn with what is left");
    }

    @Test
    void aFailedBulkDeleteIsReportedRatherThanClaimingSuccess() throws Exception {
        when(weblogger.weblogEntryManager().removeMatchingComments(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WebloggerException("database down"));

        controller.delete(request(), model, new GlobalCommentManagementBean());

        assertEquals(List.of("commentManagement.deleteError"), ControllerTestFixture.errors(model));
        assertEquals(List.of(), ControllerTestFixture.messages(model));
    }

    // --- per-comment updates ---

    @Test
    void tickedDeleteBoxesRemoveThoseCommentsAndNothingElse() throws Exception {
        WeblogEntryComment doomed = comment("c1", ApprovalStatus.APPROVED);
        WeblogEntryComment spared = comment("c2", ApprovalStatus.APPROVED);
        when(weblogger.weblogEntryManager().getComment("c1")).thenReturn(doomed);
        when(weblogger.weblogEntryManager().getComment("c2")).thenReturn(spared);
        GlobalCommentManagementBean bean = new GlobalCommentManagementBean();
        bean.setDeleteComments(new String[]{"c1"});

        String view = controller.update(request(), model, bean);

        assertEquals(".GlobalCommentManagement", view);
        verify(weblogger.weblogEntryManager()).removeComment(doomed);
        verify(weblogger.weblogEntryManager(), never()).removeComment(spared);
        verify(weblogger.weblogger()).flush();
        assertEquals(List.of("commentManagement.updateSuccess"), ControllerTestFixture.messages(model));
        // The page is redrawn from a clean filter with the remaining comments.
        GlobalCommentManagementBean redrawn = (GlobalCommentManagementBean) model.getAttribute("bean");
        assertEquals("admin", model.getAttribute("desiredMenu"));
        assertNull(redrawn.getSearchString());
        assertEquals(0, model.getAttribute("bulkDeleteCount"));
        assertEquals(4, ((List<?>) model.getAttribute("commentStatusOptions")).size());
        assertNotNull(model.getAttribute("pager"));
    }

    /**
     * The site administrator's screen changes no statuses at all any more.
     * Approval belongs to whoever owns the weblog; this screen deletes, and a
     * comment it leaves alone must come out of the update byte-for-byte
     * unchanged rather than being quietly disapproved across somebody else's
     * blog.
     */
    @Test
    void updatingDoesNotRestatusAnythingItWasNotAskedToDelete() throws Exception {
        WeblogEntryComment approved = comment("c1", ApprovalStatus.APPROVED);
        WeblogEntryComment pending = comment("c2", ApprovalStatus.PENDING);
        when(weblogger.weblogEntryManager().getComment("c1")).thenReturn(approved);
        when(weblogger.weblogEntryManager().getComment("c2")).thenReturn(pending);
        GlobalCommentManagementBean bean = new GlobalCommentManagementBean();

        controller.update(request(), model, bean);

        assertEquals(ApprovalStatus.APPROVED, approved.getStatus());
        assertEquals(ApprovalStatus.PENDING, pending.getStatus());
        verify(weblogger.weblogEntryManager(), never()).saveComment(any());
    }

    /**
     * The deleted comment's weblog page is cached. Without the invalidation the
     * comment goes on being served to readers after it is gone from the
     * database.
     */
    @Test
    void deletingACommentInvalidatesTheCacheForItsWeblog() throws Exception {
        WeblogEntryComment doomed = comment("c1", ApprovalStatus.APPROVED);
        when(weblogger.weblogEntryManager().getComment("c1")).thenReturn(doomed);
        GlobalCommentManagementBean bean = new GlobalCommentManagementBean();
        bean.setDeleteComments(new String[]{"c1"});

        controller.update(request(), model, bean);

        verify(weblogger.weblogEntryManager()).removeComment(doomed);
        verify(CACHE_HANDLER).invalidate(doomed.getWeblogEntry().getWebsite());
    }

    /**
     * A delete box naming a comment that is already gone must be skipped. The
     * ids are posted form values, so a stale page carries them; dereferencing
     * the null would 500 and lose the admin's other deletions with it.
     */
    @Test
    void deletingAnIdThatNoLongerExistsIsSkippedRatherThanFatal() throws Exception {
        WeblogEntryComment real = comment("c2", ApprovalStatus.APPROVED);
        when(weblogger.weblogEntryManager().getComment("ghost")).thenReturn(null);
        when(weblogger.weblogEntryManager().getComment("c2")).thenReturn(real);
        GlobalCommentManagementBean bean = new GlobalCommentManagementBean();
        bean.setDeleteComments(new String[]{"ghost", "c2"});

        controller.update(request(), model, bean);

        verify(weblogger.weblogEntryManager()).removeComment(real);
        assertEquals(List.of("commentManagement.updateSuccess"), ControllerTestFixture.messages(model));
    }

    @Test
    void aFailedUpdateIsReportedRatherThanClaimingSuccess() throws Exception {
        when(weblogger.weblogEntryManager().getComment("c1")).thenThrow(new WebloggerException("database down"));
        GlobalCommentManagementBean bean = new GlobalCommentManagementBean();
        bean.setDeleteComments(new String[]{"c1"});

        controller.update(request(), model, bean);

        assertEquals(List.of("commentManagement.updateError"), ControllerTestFixture.errors(model));
        assertEquals(List.of(), ControllerTestFixture.messages(model));
    }

    // --- helpers ---

    private CommentSearchCriteria capturedCriteria() throws WebloggerException {
        ArgumentCaptor<CommentSearchCriteria> captor = ArgumentCaptor.forClass(CommentSearchCriteria.class);
        verify(weblogger.weblogEntryManager(), org.mockito.Mockito.atLeastOnce()).getComments(captor.capture());
        return captor.getAllValues().get(0);
    }

    private static int dayOfMonth(java.util.Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        return cal.get(Calendar.DAY_OF_MONTH);
    }

    private static List<WeblogEntryComment> comments(int count) {
        List<WeblogEntryComment> comments = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            comments.add(comment("c" + i, ApprovalStatus.APPROVED));
        }
        return comments;
    }

    /**
     * A comment attached to an entry and weblog, as the update path expects.
     * The commenter name is set from the id because {@code WeblogEntryComment}
     * compares on name/post time/entry, and two comments that differ only by id
     * would otherwise be indistinguishable to Mockito's argument matching.
     */
    private static WeblogEntryComment comment(String id, ApprovalStatus status) {
        Weblog weblog = new Weblog();
        weblog.setHandle("testblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        WeblogEntryComment comment = new WeblogEntryComment();
        comment.setId(id);
        comment.setName("commenter-" + id);
        comment.setStatus(status);
        comment.setWeblogEntry(entry);
        return comment;
    }

    private jakarta.servlet.http.HttpServletRequest request() {
        User admin = new User();
        admin.setUserName("admin");
        return ControllerTestFixture.requestFor(admin);
    }
}
