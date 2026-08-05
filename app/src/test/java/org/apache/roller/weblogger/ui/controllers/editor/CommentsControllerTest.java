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
package org.apache.roller.weblogger.ui.controllers.editor;

import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntryComment;
import org.apache.roller.weblogger.pojos.WeblogEntryComment.ApprovalStatus;
import org.apache.roller.weblogger.ui.controllers.pagers.CommentsPager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CommentsController}.
 *
 * <p>The three POST handlers here are the ones worth pinning: {@code query}
 * decides whether the "select all matching" bulk-delete affordance appears at
 * all, {@code delete} is destructive across an arbitrary number of comments in
 * one call, and {@code update} folds three independent per-comment decisions
 * (delete, approve, mark spam, else disapprove) plus a reindex side effect into
 * a single loop where getting the precedence wrong is easy and silent.
 */
class CommentsControllerTest extends EditorControllerTestSupport {

    private CommentsController controller;
    private Model model;
    private String previousSearchEnabled;

    @BeforeEach
    void setUp() {
        controller = prepare(new CommentsController());
        model = newModel();
    }

    @AfterEach
    void restoreSearchEnabled() {
        if (previousSearchEnabled != null) {
            overrideConfigProperty("search.enabled", previousSearchEnabled);
            previousSearchEnabled = null;
        }
    }

    // --- getBean ---

    @Test
    void getBeanReturnsAFreshBean() {
        CommentsBean bean = controller.getBean();
        assertEquals(0, bean.getPage(), "A freshly constructed bean must start on page 0");
    }

    // --- execute ---

    @Test
    void executeLoadsThePagerAndTheBeanCheckboxes() throws Exception {
        WeblogEntryComment approved = commentNamed("c-1", ApprovalStatus.APPROVED);
        WeblogEntryComment hidden = commentNamed("c-2", ApprovalStatus.DISAPPROVED);
        WeblogEntryComment pending = commentNamed("c-3", ApprovalStatus.PENDING);
        when(weblogger.getWeblogEntryManager().getComments(any()))
                .thenReturn(List.of(approved, hidden, pending));

        CommentsBean bean = new CommentsBean();
        String view = controller.execute(request, model, bean);

        assertEquals(".Comments", view);
        assertEquals(3, ((CommentsPager) model.getAttribute("pager")).getItems().size(),
                "All three comments returned by the manager must reach the pager");
        assertEquals("c-1,c-2,c-3", bean.getIds(),
                "loadCheckboxes must record every comment id on the page");
        assertEquals(List.of("c-1"), List.of(bean.getApprovedComments()),
                "Only the approved comment should be pre-checked as approved");
    }

    @Test
    void anEntryFilterFromAnotherWeblogIsIgnoredRatherThanEchoedBack() throws Exception {
        // bean.entryId is client input and getWeblogEntry is a global by-id
        // lookup; the resolved entry is put in the model as "queryEntry" and
        // its title is rendered on the page.
        WeblogEntry foreign = new WeblogEntry();
        foreign.setId("entry-x");
        foreign.setTitle("Their unpublished draft");
        org.apache.roller.weblogger.pojos.Weblog other =
                new org.apache.roller.weblogger.pojos.Weblog();
        other.setId("weblog-2");
        other.setHandle("otherblog");
        foreign.setWebsite(other);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-x")).thenReturn(foreign);

        CommentsBean bean = new CommentsBean();
        bean.setEntryId("entry-x");
        controller.execute(request, model, bean);

        assertNull(model.getAttribute("queryEntry"),
                "a foreign entry's title must not be rendered on this weblog's comment screen");
    }

    // --- query ---

    @Test
    void queryFlagsBulkDeleteWhenMoreThanThirtyCommentsMatch() throws Exception {
        List<WeblogEntryComment> matches = manyComments(31);
        when(weblogger.getWeblogEntryManager().getComments(any())).thenReturn(matches);

        String view = controller.query(request, model, new CommentsBean());

        assertEquals(".Comments", view);
        assertEquals(31, model.getAttribute("bulkDeleteCount"),
                "Once the match count exceeds COUNT, the bulk-delete affordance must report it");
    }

    @Test
    void queryDoesNotFlagBulkDeleteAtOrUnderTheCountThreshold() throws Exception {
        List<WeblogEntryComment> matches = manyComments(5);
        when(weblogger.getWeblogEntryManager().getComments(any())).thenReturn(matches);

        controller.query(request, model, new CommentsBean());

        assertNull(model.getAttribute("bulkDeleteCount"),
                "Five matches must not trigger the bulk-delete affordance");
    }

    @Test
    void queryReportsAnErrorInsteadOfPropagatingAManagerFailure() throws Exception {
        when(weblogger.getWeblogEntryManager().getComments(any()))
                .thenThrow(new WebloggerException("index down"));

        String view = controller.query(request, model, new CommentsBean());

        assertEquals(".Comments", view);
        assertTrue(errors(model).contains("Error looking up comments"),
                "Expected a lookup error, got: " + errors(model));
    }

    // --- delete ---

    @Test
    void deleteRemovesMatchingCommentsAndReportsTheCountThenResetsTheBean() throws Exception {
        registerMessage("commentManagement.deleteSuccess", "deleted:{0}");
        when(weblogger.getWeblogEntryManager()
                .removeMatchingComments(any(), any(), any(), any(), any(), any()))
                .thenReturn(3);

        CommentsBean bean = new CommentsBean();
        bean.setSearchString("spammy");

        String view = controller.delete(request, model, bean);

        assertEquals(".Comments", view);
        verify(weblogger.getWeblogEntryManager())
                .removeMatchingComments(weblog, null, "spammy", bean.getStartDate(),
                        bean.getEndDate(), bean.getStatus());
        assertEquals(List.of("deleted:3"), messages(model));

        CommentsBean resetBean = (CommentsBean) model.getAttribute("bean");
        assertNull(resetBean.getSearchString(),
                "The bean in the model after a delete must be a fresh one, not the posted search");
    }

    @Test
    void deleteReindexesAffectedEntriesWhenSearchIsEnabled() throws Exception {
        previousSearchEnabled = overrideConfigProperty("search.enabled", "true");

        WeblogEntry entry1 = new WeblogEntry();
        entry1.setWebsite(weblog);
        entry1.setAnchor("entry-one");
        entry1.setTitle("entry one");
        WeblogEntry entry2 = new WeblogEntry();
        entry2.setWebsite(weblog);
        entry2.setAnchor("entry-two");
        entry2.setTitle("entry two");

        WeblogEntryComment comment1 = commentNamed("c-1", ApprovalStatus.DISAPPROVED);
        comment1.setWeblogEntry(entry1);
        WeblogEntryComment comment2 = commentNamed("c-2", ApprovalStatus.DISAPPROVED);
        comment2.setWeblogEntry(entry2);

        when(weblogger.getWeblogEntryManager().getComments(any()))
                .thenReturn(List.of(comment1, comment2));
        when(weblogger.getWeblogEntryManager()
                .removeMatchingComments(any(), any(), any(), any(), any(), any()))
                .thenReturn(2);

        controller.delete(request, model, new CommentsBean());

        ArgumentCaptor<WeblogEntry> reindexed = ArgumentCaptor.forClass(WeblogEntry.class);
        verify(weblogger.getIndexManager(), times(2)).addEntryReIndexOperation(reindexed.capture());
        assertTrue(reindexed.getAllValues().contains(entry1),
                "The first affected entry must be resubmitted for indexing");
        assertTrue(reindexed.getAllValues().contains(entry2),
                "The second affected entry must be resubmitted for indexing");
    }

    @Test
    void deleteReportsAnErrorInsteadOfPropagatingAManagerFailure() throws Exception {
        when(weblogger.getWeblogEntryManager()
                .removeMatchingComments(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WebloggerException("boom"));

        controller.delete(request, model, new CommentsBean());

        assertTrue(errors(model).contains("Bulk delete failed due to unexpected error"),
                "Expected the bulk-delete failure message, got: " + errors(model));
        assertTrue(messages(model).isEmpty(), "A failed bulk delete must not also report success");
    }

    // --- update ---

    @Test
    void updateDeletesAListedCommentAndSkipsItFromTheApprovalLoop() throws Exception {
        WeblogEntryComment toDelete = commentNamed("c-1", ApprovalStatus.PENDING);
        when(weblogger.getWeblogEntryManager().getComment("c-1")).thenReturn(toDelete);

        CommentsBean bean = new CommentsBean();
        bean.setDeleteComments(new String[]{"c-1"});
        // Also present in the id/approve lists; the delete must win and the
        // approval branch must never see it.
        bean.setIds("c-1");
        bean.setApprovedComments(new String[]{"c-1"});

        controller.update(request, model, bean);

        verify(weblogger.getWeblogEntryManager()).removeComment(toDelete);
        verify(weblogger.getWeblogEntryManager(), never()).saveComment(any());
    }

    @Test
    void updateApprovesAPendingComment() throws Exception {
        WeblogEntryComment pending = commentNamed("c-1", ApprovalStatus.PENDING);
        when(weblogger.getWeblogEntryManager().getComment("c-1")).thenReturn(pending);

        CommentsBean bean = new CommentsBean();
        bean.setIds("c-1");
        bean.setApprovedComments(new String[]{"c-1"});

        controller.update(request, model, bean);

        assertEquals(ApprovalStatus.APPROVED, pending.getStatus());
        verify(weblogger.getWeblogEntryManager()).saveComment(pending);
        assertTrue(weblogger.flushCount() > 0, "A successful update must be committed");
    }

    /**
     * Deleting has to beat approving, because the form posts both.
     *
     * <p>This is the shape of the bug that made spam-flagging useless: the page
     * pre-ticks "approved" for every already-approved comment, so a moderator
     * who ticks a second box submits both, and whichever branch the loop tests
     * first wins. When spam lost that race the comment was re-saved as
     * APPROVED and stayed on the public page. Delete is now the only other
     * action, so it is the one that must not lose the same race.
     */
    @Test
    void updateDeletesACommentEvenWhenItIsAlsoTickedApproved() throws Exception {
        WeblogEntryComment doomed = commentNamed("c-1", ApprovalStatus.APPROVED);
        when(weblogger.getWeblogEntryManager().getComment("c-1")).thenReturn(doomed);

        CommentsBean bean = new CommentsBean();
        bean.setIds("c-1");
        bean.setDeleteComments(new String[]{"c-1"});
        bean.setApprovedComments(new String[]{"c-1"});

        controller.update(request, model, bean);

        verify(weblogger.getWeblogEntryManager()).removeComment(doomed);
        verify(weblogger.getWeblogEntryManager(), never()).saveComment(doomed);
        assertEquals(ApprovalStatus.APPROVED, doomed.getStatus(),
                "The comment is gone, so its in-memory status is irrelevant -- what "
                        + "matters is that it was removed rather than re-saved");
    }

    /**
     * An id that names no comment must be skipped. bean.ids and the delete
     * boxes are posted form values, so a stale page or a hand-edited form can
     * carry an id that has since been deleted; dereferencing the null would
     * 500 the whole batch, losing the moderator's other decisions with it.
     */
    @Test
    void updateSkipsIdsThatNoLongerNameAComment() throws Exception {
        WeblogEntryComment real = commentNamed("c-1", ApprovalStatus.PENDING);
        when(weblogger.getWeblogEntryManager().getComment("c-1")).thenReturn(real);
        when(weblogger.getWeblogEntryManager().getComment("ghost")).thenReturn(null);

        CommentsBean bean = new CommentsBean();
        bean.setIds("ghost,c-1");
        bean.setDeleteComments(new String[]{"ghost"});
        bean.setApprovedComments(new String[]{"c-1"});

        controller.update(request, model, bean);

        assertEquals(ApprovalStatus.APPROVED, real.getStatus(),
                "The real comment's approval must survive a bogus id in the same batch");
        verify(weblogger.getWeblogEntryManager()).saveComment(real);
    }

    @Test
    void updateDisapprovesACommentNotInEitherList() throws Exception {
        WeblogEntryComment approved = commentNamed("c-1", ApprovalStatus.APPROVED);
        when(weblogger.getWeblogEntryManager().getComment("c-1")).thenReturn(approved);

        CommentsBean bean = new CommentsBean();
        bean.setIds("c-1");

        controller.update(request, model, bean);

        assertEquals(ApprovalStatus.DISAPPROVED, approved.getStatus());
        verify(weblogger.getWeblogEntryManager()).saveComment(approved);
    }

    @Test
    void updateLeavesAnAlreadyDisapprovedCommentAlone() throws Exception {
        WeblogEntryComment disapproved = commentNamed("c-1", ApprovalStatus.DISAPPROVED);
        when(weblogger.getWeblogEntryManager().getComment("c-1")).thenReturn(disapproved);

        CommentsBean bean = new CommentsBean();
        bean.setIds("c-1");

        controller.update(request, model, bean);

        assertEquals(ApprovalStatus.DISAPPROVED, disapproved.getStatus());
        verify(weblogger.getWeblogEntryManager(), never()).saveComment(disapproved);
    }

    @Test
    void updateResetsTheBeanButCarriesFiltersAndEntryIdForward() throws Exception {
        CommentsBean bean = new CommentsBean();
        bean.setIds("");
        bean.setSearchString("foo");
        bean.setStartDateString("01/01/24");
        bean.setEndDateString("02/01/24");
        bean.setApprovedString("ONLY_PENDING");
        bean.setEntryId("entry-1");

        controller.update(request, model, bean);

        CommentsBean freshBean = (CommentsBean) model.getAttribute("bean");
        assertTrue(freshBean != bean, "The bean in the model must be a new instance, not the posted one");
        assertEquals("foo", freshBean.getSearchString());
        assertEquals("01/01/24", freshBean.getStartDateString());
        assertEquals("02/01/24", freshBean.getEndDateString());
        assertEquals("ONLY_PENDING", freshBean.getApprovedString());
        assertEquals("entry-1", freshBean.getEntryId(),
                "An incoming entryId must be preserved across the reset");
    }

    @Test
    void updateLeavesEntryIdUnsetWhenNoneWasPosted() throws Exception {
        CommentsBean bean = new CommentsBean();
        bean.setIds("");

        controller.update(request, model, bean);

        CommentsBean freshBean = (CommentsBean) model.getAttribute("bean");
        assertNull(freshBean.getEntryId());
    }

    @Test
    void updateReportsTheExceptionTextInsteadOfPropagatingAFailure() throws Exception {
        registerMessage("commentManagement.updateError", "error:{0}");
        RuntimeException boom = new RuntimeException("boom");
        when(weblogger.getWeblogEntryManager().getComment("c-1")).thenThrow(boom);

        CommentsBean bean = new CommentsBean();
        bean.setIds("c-1");

        controller.update(request, model, bean);

        assertEquals(List.of("error:" + boom), errors(model));
    }

    // --- helpers ---

    private WeblogEntryComment commentNamed(String id, ApprovalStatus status) {
        WeblogEntryComment comment = new WeblogEntryComment();
        comment.setId(id);
        comment.setStatus(status);
        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        comment.setWeblogEntry(entry);
        return comment;
    }

    private List<WeblogEntryComment> manyComments(int count) {
        List<WeblogEntryComment> comments = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            comments.add(commentNamed("c-" + i, ApprovalStatus.APPROVED));
        }
        return comments;
    }
}
